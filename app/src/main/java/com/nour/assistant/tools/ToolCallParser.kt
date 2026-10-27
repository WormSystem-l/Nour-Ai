package com.nour.assistant.tools

import org.json.JSONObject

data class ToolCall(val id: String, val name: String, val args: JSONObject)

/**
 * Fallback للموديلات اللي مبتدعمش Function Calling: بتطلّع الأمر كـ JSON جوه النص.
 * بيلاقي أي JSON فيه اسم أداة معروفة (name/tool/function + arguments/args/parameters)
 * وبيشيله من النص اللي بيظهر للمستخدم.
 */
object ToolCallParser {

    fun extract(text: String, names: Set<String>): Pair<String, List<ToolCall>> {
        if (names.isEmpty() || !text.contains('{')) return text to emptyList()
        val calls = mutableListOf<ToolCall>()
        val cuts = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') { i++; continue }
            val end = matchBrace(text, i)
            if (end < 0) { i++; continue }
            val obj = try { JSONObject(text.substring(i, end + 1)) } catch (_: Exception) { null }
            val call = obj?.let { toCall(it, names, calls.size) }
            if (call != null) { calls += call; cuts += i..end; i = end + 1 } else i++
        }
        if (calls.isEmpty()) return text to emptyList()
        val sb = StringBuilder(text)
        for (r in cuts.reversed()) sb.delete(r.first, r.last + 1)
        var clean = sb.toString()
        clean = clean.replace(Regex("</?tool(?:_call)?s?>", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("```[a-zA-Z]*\\s*```"), "")
        clean = clean.replace(Regex("\\n{3,}"), "\n\n").trim()
        return clean to calls
    }

    private fun toCall(o: JSONObject, names: Set<String>, n: Int): ToolCall? {
        // {"tool_call": {...}} أو {"function": {...}}
        for (k in listOf("tool_call", "function_call", "function")) {
            o.optJSONObject(k)?.let { inner -> toCall(inner, names, n)?.let { return it } }
        }
        val name = listOf("name", "tool", "tool_name", "function").firstNotNullOfOrNull { k ->
            o.optString(k, "").takeIf { it in names }
        } ?: return null
        val raw = listOf("arguments", "args", "parameters", "input").firstNotNullOfOrNull { k ->
            if (o.has(k)) o.opt(k) else null
        }
        val args = when (raw) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            else -> JSONObject()
        }
        return ToolCall("call_text_$n", name, args)
    }

    /** مكان القوس } اللي بيقفل القوس { في [start] (بيحترم النصوص اللي جوه "") أو -1 */
    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when { esc -> esc = false; c == '\\' -> esc = true; c == '"' -> inStr = false }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }
}
