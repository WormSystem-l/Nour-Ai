package com.nour.assistant.tools

import com.nour.assistant.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Parallel Search (MCP عبر Streamable HTTP) — مجاني بدون API key ولا حساب (بحدود أقل للمجهولين).
 * المحرك الأساسي: بيرجّع مقتطفات من الصفحات نفسها فبيوفّر توكنز. الاستعلامات بتتبعت لـ Parallel.
 */
class ParallelMcpEngine(
    private val url: String = "https://search.parallel.ai/mcp",
    private val timeoutMs: Int = 9000
) : SearchEngine {
    override val id = "Parallel"

    private val sessionId = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var mcpSession: String? = null
    @Volatile private var initialized = false
    private var nextId = 1

    private class Resp(val code: Int, val contentType: String, val session: String?, val body: String)

    override fun search(query: String, max: Int): SearchOut {
        val args = JSONObject()
            .put("objective", "Find accurate, up-to-date information to answer: $query")
            .put("search_queries", JSONArray().put(shortQuery(query)))
            .put("session_id", sessionId)
        val text = try { callTool("web_search", args) } catch (e: NeedInit) { initialize(); callTool("web_search", args) }
        return parseToolText(text, query, max)
    }

    private class NeedInit : Exception()

    private fun initialize() {
        val init = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", "initialize").put("params",
            JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "nour-ai").put("version", "1")))
        val r = post(init)
        if (r.code !in 200..299) throw IllegalStateException("initialize HTTP ${r.code}")
        mcpSession = r.session ?: mcpSession
        rpcResult(r) // يتأكد مفيش error
        runCatching { post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")) }
        initialized = true
    }

    private fun callTool(name: String, args: JSONObject): String {
        val req = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
        val r = post(req)
        if (r.code == 400 || r.code == 404 || r.code == 409) { if (!initialized) throw NeedInit() }
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code}")
        val res = rpcResult(r)
        if (res.optBoolean("isError", false)) throw IllegalStateException("tool error: " + contentText(res).take(100))
        return contentText(res)
    }

    private fun contentText(res: JSONObject): String {
        val c = res.optJSONArray("content") ?: return res.optString("text", "")
        val sb = StringBuilder()
        for (i in 0 until c.length()) { val o = c.optJSONObject(i) ?: continue; if (o.optString("type", "text") == "text") sb.append(o.optString("text")).append('\n') }
        return sb.toString().trim()
    }

    private fun rpcResult(r: Resp): JSONObject {
        val o = parseRpcBody(r.body, r.contentType) ?: throw IllegalStateException("empty response")
        o.optJSONObject("error")?.let { e ->
            val msg = e.optString("message", "rpc error")
            if (!initialized && (msg.contains("session", true) || msg.contains("initializ", true))) throw NeedInit()
            throw IllegalStateException(msg)
        }
        return o.optJSONObject("result") ?: throw IllegalStateException("no result")
    }

    private fun post(body: JSONObject): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            mcpSession?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val buf = ByteArrayOutputStream()
            stream?.use { ins -> val b = ByteArray(8192); while (buf.size() < 1_000_000) { val n = ins.read(b); if (n < 0) break; buf.write(b, 0, n) } }
            return Resp(code, c.contentType.orEmpty(), c.getHeaderField("Mcp-Session-Id"), buf.toString("UTF-8"))
        } finally { c.disconnect() }
    }

    companion object {
        /** رد MCP ممكن يجي JSON عادي أو SSE (data: {...}) */
        fun parseRpcBody(body: String, contentType: String): JSONObject? {
            val t = body.trim()
            if (t.isEmpty()) return null
            if (contentType.contains("event-stream", true) || t.startsWith("event:") || t.startsWith("data:")) {
                var last: JSONObject? = null
                for (line in t.lineSequence()) {
                    val l = line.trim()
                    if (!l.startsWith("data:")) continue
                    val d = l.removePrefix("data:").trim()
                    if (d.isEmpty() || d == "[DONE]") continue
                    try { val o = JSONObject(d); if (o.has("result") || o.has("error")) last = o } catch (_: Exception) {}
                }
                return last
            }
            return try { JSONObject(t) } catch (_: Exception) { null }
        }

        fun shortQuery(q: String): String = q.trim().split(Regex("\\s+")).take(8).joinToString(" ")

        private val URL_RE = Regex("https?://[^\\s)\\]>\"'<]+")

        /**
         * نص أداة البحث -> نص مصغّر + مصادر. لو JSON بنبني نص مختصر بنفسنا، ولو نص عادي (markdown)
         * بنحافظ عليه (محدود الحجم) ونطلّع الروابط كمصادر.
         */
        fun parseToolText(text: String, query: String, max: Int): SearchOut {
            val t = text.trim()
            if (t.isEmpty()) throw IllegalStateException("empty text")
            if (t.startsWith("{") || t.startsWith("[")) {
                val sources = ArrayList<Source>(); val sb = StringBuilder("Search results for \"$query\":\n")
                collect(try { if (t.startsWith("[")) JSONArray(t) else JSONObject(t) } catch (_: Exception) { null }, sources, sb, max)
                if (sources.isNotEmpty()) return SearchOut(WebText.clip(sb.toString().trim(), 4000), sources.map { it.copy(query = query) }, "Parallel")
            }
            val sources = ArrayList<Source>()
            val seen = HashSet<String>()
            for (m in URL_RE.findAll(t)) {
                val u = m.value.trimEnd('.', ',', ';')
                if (!seen.add(u) || !WebText.isSafeUrl(u)) continue
                val before = t.substring(0, m.range.first).lines().lastOrNull { it.isNotBlank() && !it.contains("http") }.orEmpty()
                val title = before.trim().trimStart('#', '*', '-', ' ', '>').trimEnd('*', ':', ' ').take(120).ifBlank { hostOf(u) }
                sources += Source(title, u, "", query)
                if (sources.size >= max + 3) break
            }
            if (sources.isEmpty() && t.length < 40) throw IllegalStateException("no usable results")
            return SearchOut(WebText.clip("Search results for \"$query\":\n$t", 4000), sources, "Parallel")
        }

        private fun hostOf(u: String) = try { java.net.URI(u).host?.removePrefix("www.") ?: u } catch (_: Exception) { u }

        private fun collect(node: Any?, out: MutableList<Source>, sb: StringBuilder, max: Int) {
            if (out.size >= max) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, sb, max)
                is JSONObject -> {
                    val u = node.optString("url")
                    if (u.startsWith("http") && WebText.isSafeUrl(u)) {
                        val title = node.optString("title").ifBlank { hostOf(u) }
                        val ex = node.opt("excerpts") ?: node.opt("excerpt") ?: node.opt("snippet") ?: node.opt("content")
                        val snip = when (ex) {
                            is JSONArray -> (0 until ex.length()).joinToString(" ") { ex.optString(it) }
                            null -> ""
                            else -> ex.toString()
                        }.replace(Regex("\\s+"), " ").trim()
                        out += Source(title, u, WebText.clip(snip, 300))
                        sb.append("${out.size}) ").append(WebText.clip(title, 110))
                        if (snip.isNotEmpty()) sb.append(" — ").append(WebText.clip(snip, 420))
                        sb.append(" [").append(hostOf(u)).append("]\n   ").append(u).append('\n')
                    } else {
                        val keys = node.keys()
                        while (keys.hasNext()) collect(node.opt(keys.next()), out, sb, max)
                    }
                }
            }
        }
    }

    @Suppress("unused") private val log = AppLog
}
