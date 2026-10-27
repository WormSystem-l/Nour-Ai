package com.nour.assistant.tools

import com.nour.assistant.AppLog
import com.nour.assistant.L
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * بحث وقراءة صفحات للموديل (مجاني، من غير API Key): DuckDuckGo (HTML) ثم Wikipedia كاحتياطي.
 * النتائج مصغّرة جداً (عناوين + مقتطفات قصيرة) عشان التوكنز، والصفحات بتتقلّص لأهم الفقرات للسؤال.
 */
object WebTools {
    private const val UA = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 1_500_000
    const val SEARCH_BUDGET = 1500
    const val PAGE_BUDGET = 2500

    private val duckEngine = object : SearchEngine {
        override val id = "DuckDuckGo"
        override fun search(query: String, max: Int): SearchOut {
            val found = ArrayList<Source>()
            val t = duck(query, max, found)
            if (t.isEmpty()) throw IllegalStateException("no results (blocked or empty)")
            return SearchOut(t, found.map { it.copy(query = query) })
        }
    }

    private val wikiEngine = object : SearchEngine {
        override val id = "Wikipedia"
        override fun search(query: String, max: Int): SearchOut {
            val found = ArrayList<Source>()
            val t = wikipedia(query, found)
            if (t.isEmpty()) throw IllegalStateException("no results")
            return SearchOut(t, found.map { it.copy(query = query) })
        }
    }

    /** سلسلة: Parallel (مجاني بدون مفتاح) ← DuckDuckGo ← Wikipedia. لو واحد وقع بنكمّل بالتاني. */
    val chain = SearchChain(listOf(ParallelMcpEngine(), duckEngine, wikiEngine))

    fun search(queryRaw: String, max: Int = 5): String = searchFull(queryRaw, max).text

    /** نص مصغّر للموديل + المصادر المنظّمة (للزرار اللي تحت الرد) */
    fun searchFull(queryRaw: String, max: Int = 5): SearchOut {
        val q = queryRaw.trim().take(200)
        if (q.isEmpty()) return SearchOut("Error: empty query", emptyList())
        val r = chain.search(q, max)
        r.out?.let { return it }
        AppLog.eOnce("web", "all search engines failed: " + r.errors.joinToString(" | "))
        chain.resetBreakers()   // المحاولة الجاية تجرب الكل من جديد
        return SearchOut("SEARCH_FAILED: the web search service could not be reached right now (no internet or the services are unavailable). " +
            "Tell the user honestly and offer to retry. Do NOT claim that you are unable to browse or search the web.", emptyList())
    }

    // ---------------------------------------------------------------- DuckDuckGo

    private fun duck(q: String, max: Int, found: MutableList<Source>): String {
        val html = http("https://html.duckduckgo.com/html/", "q=" + URLEncoder.encode(q, "UTF-8")) ?: ""
        if (html.contains("anomaly", true) || html.contains("captcha", true)) { AppLog.eOnce("web", "duckduckgo asked for a captcha"); return lite(q, max, found) }
        val doc = Jsoup.parse(html)
        val rows = ArrayList<Triple<String, String, String>>()
        for (r in doc.select("div.result")) {
            if (r.hasClass("result--ad")) continue
            val a = r.selectFirst("a.result__a") ?: continue
            val url = WebText.decodeDdg(a.attr("href"))
            if (!WebText.isSafeUrl(url)) continue
            val sn = r.selectFirst(".result__snippet")?.text().orEmpty()
            rows += Triple(a.text().trim(), WebText.clip(sn.trim(), 230), url)
            found += Source(a.text().trim(), url, WebText.clip(sn.trim(), 230))
            if (rows.size >= max) break
        }
        if (rows.isEmpty()) {
            // احتياطي: لو شكل الصفحة اتغير
            val re = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            for (m in re.findAll(html)) {
                val url = WebText.decodeDdg(m.groupValues[1])
                if (WebText.isSafeUrl(url)) { val t = Jsoup.parse(m.groupValues[2]).text(); rows += Triple(t, "", url); found += Source(t, url) }
                if (rows.size >= max) break
            }
        }
        if (rows.isEmpty()) return lite(q, max, found)
        val sb = StringBuilder("Search results for \"$q\":\n")
        rows.forEachIndexed { i, (t, s, u) ->
            val host = try { java.net.URI(u).host?.removePrefix("www.") } catch (_: Exception) { null } ?: ""
            sb.append("${i + 1}) ").append(WebText.clip(t, 110))
            if (s.isNotEmpty()) sb.append(" — ").append(s)
            sb.append(" [").append(host).append("]\n   ").append(u).append('\n')
        }
        return WebText.clip(sb.toString().trim(), SEARCH_BUDGET)
    }

    /** صفحة DuckDuckGo الخفيفة (بتتحجب أقل) */
    private fun lite(q: String, max: Int, found: MutableList<Source>): String {
        val html = http("https://lite.duckduckgo.com/lite/", "q=" + URLEncoder.encode(q, "UTF-8")) ?: return ""
        val doc = Jsoup.parse(html)
        val links = doc.select("a.result-link")
        val snips = doc.select("td.result-snippet")
        val sb = StringBuilder("Search results for \"$q\":\n")
        var n = 0
        for ((i, a) in links.withIndex()) {
            val url = WebText.decodeDdg(a.attr("href"))
            if (!WebText.isSafeUrl(url)) continue
            val sn = WebText.clip(snips.getOrNull(i)?.text().orEmpty().trim(), 230)
            found += Source(a.text().trim(), url, sn)
            val host = try { java.net.URI(url).host?.removePrefix("www.") } catch (_: Exception) { null } ?: ""
            sb.append("${++n}) ").append(WebText.clip(a.text().trim(), 110)).append(if (sn.isNotEmpty()) " — $sn" else "").append(" [").append(host).append("]\n   ").append(url).append('\n')
            if (n >= max) break
        }
        return if (n == 0) "" else WebText.clip(sb.toString().trim(), SEARCH_BUDGET)
    }

    // ---------------------------------------------------------------- Wikipedia (احتياطي مجاني ومستقر)

    private fun wikipedia(q: String, found: MutableList<Source>): String {
        val lang = if (Locale.getDefault().language == "ar" || q.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=3&srsearch=" + URLEncoder.encode(q, "UTF-8")
        val body = http(url, null) ?: return ""
        val arr = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: return ""
        if (arr.length() == 0) return ""
        val sb = StringBuilder("Wikipedia results for \"$q\":\n")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            val sn = Jsoup.parse(o.optString("snippet")).text()
            val link = "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
            sb.append("${i + 1}) ").append(title).append(" — ").append(WebText.clip(sn, 230)).append("\n   ").append(link).append('\n')
            found += Source(title, link, WebText.clip(sn, 230))
        }
        return WebText.clip(sb.toString().trim(), SEARCH_BUDGET)
    }

    // ---------------------------------------------------------------- قراءة صفحة

    class PageOut(val text: String, val title: String, val url: String, val ok: Boolean)

    fun readPage(urlRaw: String, focus: String?, maxChars: Int = PAGE_BUDGET): String = readPageFull(urlRaw, focus, maxChars).text

    /** قراءة صفحة كاملة (بحد حروف) مع عنوانها. [maxChars] كبير لو عايز الصفحة كلها (اسأل عن الصفحة) */
    fun readPageFull(urlRaw: String, focus: String?, maxChars: Int = PAGE_BUDGET): PageOut {
        val url = urlRaw.trim()
        if (!WebText.isSafeUrl(url)) return PageOut("Error: this URL is not allowed.", "", url, false)
        val html = try { http(url, null, htmlOnly = true) } catch (e: Exception) { AppLog.eOnce("web", "readPage failed", e); null }
            ?: return PageOut("Error: could not open the page.", "", url, false)
        val doc = Jsoup.parse(html, url)
        doc.select("script,style,noscript,nav,footer,header,aside,form,iframe,svg,button,figure,[role=navigation],[role=banner],[aria-hidden=true]").remove()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.selectFirst("[role=main]") ?: doc.body() ?: return PageOut("Error: empty page.", "", url, false)
        val paras = ArrayList<String>()
        for (e in root.select("h1,h2,h3,p,li,td,blockquote")) {
            val t = e.ownText().ifBlank { e.text() }.trim()
            val tag = e.tagName()
            if (tag.startsWith("h") && t.length >= 3) paras += t
            else if (t.length >= 40) paras += t
        }
        val body = WebText.condense(paras, focus, maxChars)
        if (body.isEmpty()) return PageOut("Error: the page has no readable text.", doc.title().trim(), url, false)
        val title = doc.title().trim()
        return PageOut((if (title.isNotEmpty()) "Title: ${WebText.clip(title, 120)}\n" else "") + "Source: $url\n" + body, title, url, true)
    }

    // ---------------------------------------------------------------- HTTP

    private fun http(url0: String, post: String?, htmlOnly: Boolean = false): String? {
        var url = url0
        var hops = 0
        while (hops++ < 4) {
            if (!WebText.isSafeUrl(url)) return null
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 9000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "${Locale.getDefault().language},en;q=0.8")
            c.setRequestProperty("Accept", if (htmlOnly) "text/html,application/xhtml+xml" else "*/*")
            try {
                if (post != null && hops == 1) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(post.toByteArray()) }
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    url = URL(URL(url), loc).toString(); continue
                }
                if (code !in 200..299) { AppLog.eOnce("web", "HTTP $code for ${URL(url).host}"); return null }
                val type = c.contentType.orEmpty().lowercase()
                if (htmlOnly && !(type.contains("html") || type.contains("text/plain") || type.isEmpty())) return null
                val buf = ByteArrayOutputStream()
                c.inputStream.use { ins ->
                    val b = ByteArray(16 * 1024)
                    while (buf.size() < MAX_BYTES) { val n = ins.read(b); if (n < 0) break; buf.write(b, 0, n) }
                }
                val cs = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8"
                return String(buf.toByteArray(), try { charset(cs) } catch (_: Exception) { Charsets.UTF_8 })
            } finally { c.disconnect() }
        }
        return null
    }

    @Suppress("unused") private val keepL = L
}
