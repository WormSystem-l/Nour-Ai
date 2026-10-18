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

    fun search(queryRaw: String, max: Int = 5): String {
        val q = queryRaw.trim().take(200)
        if (q.isEmpty()) return "Error: empty query"
        val out = try { duck(q, max) } catch (e: Exception) { AppLog.eOnce("web", "duckduckgo failed: ${e.javaClass.simpleName}", e); "" }
        if (out.isNotEmpty()) return out
        val wiki = try { wikipedia(q) } catch (e: Exception) { AppLog.eOnce("web", "wikipedia failed", e); "" }
        return wiki.ifEmpty { "No results found (search service unreachable or no internet). Tell the user." }
    }

    // ---------------------------------------------------------------- DuckDuckGo

    private fun duck(q: String, max: Int): String {
        val html = http("https://html.duckduckgo.com/html/", "q=" + URLEncoder.encode(q, "UTF-8")) ?: return ""
        val doc = Jsoup.parse(html)
        val rows = ArrayList<Triple<String, String, String>>()
        for (r in doc.select("div.result")) {
            if (r.hasClass("result--ad")) continue
            val a = r.selectFirst("a.result__a") ?: continue
            val url = WebText.decodeDdg(a.attr("href"))
            if (!WebText.isSafeUrl(url)) continue
            val sn = r.selectFirst(".result__snippet")?.text().orEmpty()
            rows += Triple(a.text().trim(), WebText.clip(sn.trim(), 230), url)
            if (rows.size >= max) break
        }
        if (rows.isEmpty()) {
            // احتياطي: لو شكل الصفحة اتغير
            val re = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            for (m in re.findAll(html)) {
                val url = WebText.decodeDdg(m.groupValues[1])
                if (WebText.isSafeUrl(url)) rows += Triple(Jsoup.parse(m.groupValues[2]).text(), "", url)
                if (rows.size >= max) break
            }
        }
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("Search results for \"$q\":\n")
        rows.forEachIndexed { i, (t, s, u) ->
            val host = try { java.net.URI(u).host?.removePrefix("www.") } catch (_: Exception) { null } ?: ""
            sb.append("${i + 1}) ").append(WebText.clip(t, 110))
            if (s.isNotEmpty()) sb.append(" — ").append(s)
            sb.append(" [").append(host).append("]\n   ").append(u).append('\n')
        }
        return WebText.clip(sb.toString().trim(), SEARCH_BUDGET)
    }

    // ---------------------------------------------------------------- Wikipedia (احتياطي مجاني ومستقر)

    private fun wikipedia(q: String): String {
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
            sb.append("${i + 1}) ").append(title).append(" — ").append(WebText.clip(sn, 230))
                .append("\n   https://$lang.wikipedia.org/wiki/").append(URLEncoder.encode(title.replace(' ', '_'), "UTF-8")).append('\n')
        }
        return WebText.clip(sb.toString().trim(), SEARCH_BUDGET)
    }

    // ---------------------------------------------------------------- قراءة صفحة

    fun readPage(urlRaw: String, focus: String?, maxChars: Int = PAGE_BUDGET): String {
        val url = urlRaw.trim()
        if (!WebText.isSafeUrl(url)) return "Error: this URL is not allowed."
        val html = try { http(url, null, htmlOnly = true) } catch (e: Exception) { AppLog.eOnce("web", "readPage failed", e); null }
            ?: return "Error: could not open the page."
        val doc = Jsoup.parse(html, url)
        doc.select("script,style,noscript,nav,footer,header,aside,form,iframe,svg,button,figure,[role=navigation],[role=banner],[aria-hidden=true]").remove()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.selectFirst("[role=main]") ?: doc.body() ?: return "Error: empty page."
        val paras = ArrayList<String>()
        for (e in root.select("h1,h2,h3,p,li,td,blockquote")) {
            val t = e.ownText().ifBlank { e.text() }.trim()
            val tag = e.tagName()
            if (tag.startsWith("h") && t.length >= 3) paras += t
            else if (t.length >= 40) paras += t
        }
        val body = WebText.condense(paras, focus, maxChars)
        if (body.isEmpty()) return "Error: the page has no readable text."
        val title = doc.title().trim()
        return (if (title.isNotEmpty()) "Title: ${WebText.clip(title, 120)}\n" else "") + "Source: $url\n" + body
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
