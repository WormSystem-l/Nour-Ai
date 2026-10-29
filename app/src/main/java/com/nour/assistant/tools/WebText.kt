package com.nour.assistant.tools

import java.net.URLDecoder

/** دوال نقية لتصغير صفحات الويب قبل ما تروح للموديل (توفير توكنز) */
object WebText {
    /** روابط DuckDuckGo بتيجي كـ //duckduckgo.com/l/?uddg=<الرابط الحقيقي>&rut=... */
    fun decodeDdg(href: String): String {
        var h = href.trim()
        val i = h.indexOf("uddg=")
        if (i >= 0) {
            val end = h.indexOf('&', i).let { if (it < 0) h.length else it }
            h = try { URLDecoder.decode(h.substring(i + 5, end), "UTF-8") } catch (_: Exception) { h }
        }
        if (h.startsWith("//")) h = "https:$h"
        return h
    }

    /** منع الوصول لعناوين داخلية (حماية) */
    fun isSafeUrl(url: String): Boolean {
        val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return false }
        val scheme = u.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (u.userInfo != null) return false
        val host = u.host?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) return false
        if (host.startsWith("[") || host.contains(':')) return false
        val p = host.split('.')
        if (p.size == 4 && p.all { it.toIntOrNull() != null }) {
            val a = p[0].toInt(); val b = p[1].toInt()
            if (a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 192 && b == 168) || (a == 172 && b in 16..31)) return false
        }
        return true
    }

    fun tokens(q: String): List<String> =
        q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct()

    /**
     * بيختار أهم الفقرات للسؤال (بالكلمات) في حدود [max] حرف، وبيرتبها زي ما هي في الصفحة.
     * لو مفيش سؤال بياخد من أول الصفحة.
     */
    fun condense(paras: List<String>, query: String?, max: Int): String {
        val clean = paras.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return ""
        val toks = if (query.isNullOrBlank()) emptyList() else tokens(query)
        val order = clean.indices.sortedWith(compareByDescending<Int> { i ->
            val l = clean[i].lowercase(); toks.count { l.contains(it) }
        }.thenBy { it })
        val chosen = ArrayList<Int>(); var used = 0
        for (i in order) {
            val len = clean[i].length + 1
            if (used + len > max) { if (chosen.isEmpty()) { chosen += i; used = max }; continue }
            chosen += i; used += len
            if (used >= max) break
        }
        chosen.sort()
        val s = chosen.joinToString("\n") { clean[it] }
        return if (s.length > max) s.take(max) + "…" else s
    }

    fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max).trimEnd() + "…"
}
