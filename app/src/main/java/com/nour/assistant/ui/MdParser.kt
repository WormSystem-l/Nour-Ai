package com.nour.assistant.ui

/** محلل Markdown بسيط (بدون Android) لردود الذكاء الاصطناعي: ** * ## ` ``` - 1. > ~~ [نص](رابط) */
object MdParser {
    enum class Kind { NORMAL, H1, H2, H3, BULLET, NUMBER, QUOTE, RULE, BLANK }

    data class Seg(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null
    )

    sealed class Block {
        data class Line(val kind: Kind, val segs: List<Seg>, val indent: Int = 0, val marker: String = "") : Block()
        data class Code(val lang: String, val code: String, val closed: Boolean) : Block()
    }

    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            if (t.startsWith("```")) {
                val lang = t.removePrefix("```").trim()
                val buf = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith("```")) { closed = true; i++; break }
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(lines[i]); i++
                }
                out += Block.Code(lang, buf.toString(), closed)
                continue
            }
            out += parseLine(line)
            i++
        }
        return out
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val NUMBER = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    private fun parseLine(line: String): Block.Line {
        if (line.isBlank()) return Block.Line(Kind.BLANK, emptyList())
        if (RULE.matches(line)) return Block.Line(Kind.RULE, emptyList())
        HEADING.matchEntire(line.trim())?.let { m ->
            val lvl = m.groupValues[1].length
            val kind = when (lvl) { 1 -> Kind.H1; 2 -> Kind.H2; else -> Kind.H3 }
            return Block.Line(kind, inline(m.groupValues[2].trim().trimEnd('#').trim()))
        }
        if (line.trimStart().startsWith(">")) {
            return Block.Line(Kind.QUOTE, inline(line.trimStart().removePrefix(">").trim()))
        }
        BULLET.matchEntire(line)?.let { m ->
            return Block.Line(Kind.BULLET, inline(m.groupValues[2]), indent = m.groupValues[1].length / 2)
        }
        NUMBER.matchEntire(line)?.let { m ->
            return Block.Line(Kind.NUMBER, inline(m.groupValues[3]), indent = m.groupValues[1].length / 2, marker = m.groupValues[2] + ".")
        }
        return Block.Line(Kind.NORMAL, inline(line))
    }

    // ------------------------------------------------------------------ inline

    fun inline(s: String): List<Seg> {
        val out = mutableListOf<Seg>()
        parseInline(s, Seg(""), out)
        return merge(out)
    }

    private fun parseInline(s: String, style: Seg, out: MutableList<Seg>) {
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += style.copy(text = sb.toString()); sb.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // \* \_ \` : حرف مهروب
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!~>") { sb.append(s[i + 1]); i += 2; continue }
            // `code`
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) { flush(); out += style.copy(text = s.substring(i + 1, end), code = true); i = end + 1; continue }
            }
            // [text](url)
            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                val end = if (close > 0) s.indexOf(')', close + 2) else -1
                if (close > 0 && end > 0) {
                    flush()
                    parseInline(s.substring(i + 1, close), style.copy(link = s.substring(close + 2, end)), out)
                    i = end + 1; continue
                }
            }
            // *** أو ___ : عريض ومائل
            if ((c == '*' || c == '_') && s.startsWith("$c$c$c", i)) {
                val end = s.indexOf("$c$c$c", i + 3)
                if (end > i + 3 && !(c == '_' && wordChar(s, i - 1))) {
                    flush(); parseInline(s.substring(i + 3, end), style.copy(bold = true, italic = true), out); i = end + 3; continue
                }
            }
            // ** أو __ : عريض
            if ((c == '*' || c == '_') && s.startsWith("$c$c", i)) {
                val end = s.indexOf("$c$c", i + 2)
                if (end > i + 2 && !(c == '_' && wordChar(s, i - 1))) {
                    flush(); parseInline(s.substring(i + 2, end), style.copy(bold = true), out); i = end + 2; continue
                }
            }
            // ~~ شطب
            if (c == '~' && s.startsWith("~~", i)) {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) { flush(); parseInline(s.substring(i + 2, end), style.copy(strike = true), out); i = end + 2; continue }
            }
            // * أو _ : مائل
            if (c == '*' || c == '_') {
                val ok = if (c == '_') !wordChar(s, i - 1) else true
                var end = -1
                if (ok && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                    var j = i + 1
                    while (j < s.length) {
                        val k = s.indexOf(c, j)
                        if (k < 0) break
                        // مش جزء من ** ، ومفيش مسافة قبله
                        val dbl = (k + 1 < s.length && s[k + 1] == c) || (k > 0 && s[k - 1] == c && k - 1 > i)
                        if (!dbl && !s[k - 1].isWhitespace() && (c != '_' || !wordChar(s, k + 1))) { end = k; break }
                        j = k + 1
                    }
                }
                if (end > i + 1) { flush(); parseInline(s.substring(i + 1, end), style.copy(italic = true), out); i = end + 1; continue }
            }
            sb.append(c); i++
        }
        flush()
    }

    private fun wordChar(s: String, i: Int) = i in s.indices && (s[i].isLetterOrDigit())

    private fun merge(l: List<Seg>): List<Seg> {
        val r = mutableListOf<Seg>()
        for (s in l) {
            val p = r.lastOrNull()
            if (p != null && p.copy(text = "") == s.copy(text = "")) r[r.size - 1] = p.copy(text = p.text + s.text) else r += s
        }
        return r
    }
}
