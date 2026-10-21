package com.nour.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nour.assistant.tools.ToolCall
import com.nour.assistant.tools.ToolCallParser
import com.nour.assistant.tools.ToolHost
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val role: String, val content: String, val isError: Boolean = false)

data class AiConfig(val baseUrl: String, val model: String, val apiKey: String, val system: String)

/** نتيجة الطلب: يا رد ناجح يا رسالة خطأ */
sealed class AiResult {
    data class Ok(val text: String) : AiResult()
    data class Err(val message: String) : AiResult()
}

/** عميل لأي API متوافق مع OpenAI Chat Completions (OpenAI, Groq, OpenRouter, Gemini, Ollama...) */
class AiClient(
    private val tools: ToolHost? = null,
    private val config: () -> AiConfig
) {

    /** طلب عادي (من غير Streaming) */
    suspend fun chat(history: List<Msg>): AiResult = chatStream(history, stream = false) { }

    private class Round(
        val text: String = "",
        val calls: List<ToolCall> = emptyList(),
        val err: AiResult.Err? = null,
        val code: Int = 200,
        val errBody: String = ""
    )

    /**
     * طلب بـ Streaming: الرد بيوصل حتة حتة وبنبعت كل حتة لـ [onDelta] (على Thread خلفي).
     * لو السيرفر مش بيدعم Streaming ورجّع JSON عادي، بنبعت الرد كله مرة واحدة.
     * لو فيه أدوات (open_app): الموديل ينادي عليها (Function Calling)، ولو مش بيدعمها بنرجع لـ JSON parsing.
     */
    suspend fun chatStream(
        history: List<Msg>,
        stream: Boolean = true,
        onDelta: (String) -> Unit
    ): AiResult = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.apiKey.isBlank()) return@withContext AiResult.Err(L.t("⚠️ حط الـ API Key من الإعدادات الأول.", "⚠️ Add your API Key in Settings first."))
        if (!cfg.baseUrl.startsWith("http://") && !cfg.baseUrl.startsWith("https://"))
            return@withContext AiResult.Err(L.t("⚠️ الـ Base URL لازم يبدأ بـ https://", "⚠️ Base URL must start with https://"))
        if (cfg.model.isBlank()) return@withContext AiResult.Err(L.t("⚠️ اختار موديل من أعلى الشاشة الرئيسية.", "⚠️ Pick a model from the top of the main screen."))

        val host = tools
        val toolsOn = host != null && host.hasTools
        val key = cfg.baseUrl + "|" + cfg.model
        var native = toolsOn && key !in promptModeKeys

        fun systemText(): String = listOfNotNull(
            cfg.system.takeIf { it.isNotBlank() },
            host?.systemNote(native)?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")

        val msgs = JSONArray()
        val sys0 = systemText()
        if (sys0.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", sys0))
        history.filter { !it.isError && it.content.isNotBlank() }.takeLast(20)
            .forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val out = StringBuilder()
        var round = 0
        while (true) {
            round++
            val allowTools = toolsOn && round <= MAX_ROUNDS
            if (round > 1 && out.isNotEmpty()) { out.append("\n\n"); onDelta("\n\n") }

            val promptMode = allowTools && !native
            var r = post(cfg, msgs, if (allowTools && native) host!!.specs() else null,
                stream = stream && !promptMode, emit = !promptMode, onDelta = onDelta)

            // الموديل مش بيدعم الأدوات: نرجع لوضع الـ JSON parsing ونعيد الطلب
            if (r.err != null && allowTools && native && r.code in listOf(400, 404, 422) && looksLikeNoTools(r.errBody)) {
                promptModeKeys += key
                native = false
                msgs.put(0, JSONObject().put("role", "system").put("content", systemText()))
                r = post(cfg, msgs, null, stream = false, emit = false, onDelta = onDelta)
            }
            val failure = r.err
            if (failure != null) return@withContext failure

            var text = r.text
            var calls = r.calls
            var nativeCalls = calls.isNotEmpty()
            if (allowTools && calls.isEmpty()) {
                val (clean, found) = ToolCallParser.extract(text, host!!.toolNames)
                if (found.isNotEmpty()) { text = clean; calls = found; nativeCalls = false }
            }
            // الوضع اللي مفيهوش emit: نبعت النص النضيف بنفسنا
            if (!native && allowTools && text.isNotBlank()) onDelta(text)
            if (text.isNotBlank()) out.append(text.trim())

            if (calls.isEmpty() || !allowTools) break

            // نفّذ الأدوات وارجع للموديل بالنتيجة
            val results = calls.take(MAX_CALLS).map { c ->
                try { host!!.run(c) } catch (e: Exception) { "Error: ${e.message}" }
            }
            if (nativeCalls) {
                val arr = JSONArray()
                calls.take(MAX_CALLS).forEach { c ->
                    arr.put(JSONObject().put("id", c.id).put("type", "function").put("function",
                        JSONObject().put("name", c.name).put("arguments", c.args.toString())))
                }
                msgs.put(JSONObject().put("role", "assistant").put("content", r.text).put("tool_calls", arr))
                calls.take(MAX_CALLS).forEachIndexed { i, c ->
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", results[i]))
                }
            } else {
                msgs.put(JSONObject().put("role", "assistant").put("content", r.text))
                val report = calls.take(MAX_CALLS).mapIndexed { i, c -> "[tool_result ${c.name}] ${results[i]}" }.joinToString("\n")
                msgs.put(JSONObject().put("role", "user").put("content", "$report\nNow reply briefly to the user (do not call the tool again)."))
            }
        }

        val text = out.toString().trim()
        if (text.isEmpty()) AiResult.Err(L.t("الموديل رجّع رد فاضي.", "The model returned an empty reply."))
        else AiResult.Ok(text)
    }

    /** طلب واحد للسيرفر */
    private fun post(
        cfg: AiConfig, msgs: JSONArray, toolsJson: JSONArray?,
        stream: Boolean, emit: Boolean, onDelta: (String) -> Unit
    ): Round {
        val body = JSONObject().put("model", cfg.model).put("messages", msgs).apply {
            if (stream) put("stream", true)
            if (toolsJson != null) { put("tools", toolsJson); put("tool_choice", "auto") }
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL("${cfg.baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", if (stream) "text/event-stream, application/json" else "application/json")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                com.nour.assistant.AppLog.eOnce("ai", "HTTP $code ${err.take(160).replace('\n', ' ')}")
                return Round(err = AiResult.Err(friendlyError(code, err)), code = code, errBody = err)
            }
            val type = conn.contentType.orEmpty()
            val sink: (String) -> Unit = { if (emit) onDelta(it) }
            val parsed = conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                if (stream && type.contains("event-stream")) readSseFull(r.lineSequence(), sink)
                else {
                    val all = r.readText()
                    // بعض السيرفرات بترجع SSE من غير Content-Type صح
                    if (stream && all.trimStart().startsWith("data:")) readSseFull(all.lineSequence(), sink)
                    else parseFull(all).also { if (it.error == null && it.text.isNotEmpty()) sink(it.text) }
                }
            }
            if (parsed.error != null) return Round(err = AiResult.Err(parsed.error))
            return Round(text = parsed.text, calls = parsed.calls)
        } catch (e: java.net.UnknownHostException) {
            return Round(err = AiResult.Err(L.t("مفيش إنترنت أو العنوان غلط.", "No internet connection, or the URL is wrong.")))
        } catch (e: java.net.SocketTimeoutException) {
            return Round(err = AiResult.Err(L.t("السيرفر اتأخر في الرد، جرب تاني.", "The server took too long. Try again.")))
        } catch (e: Exception) {
            return Round(err = AiResult.Err(L.t("حصل خطأ في الاتصال: ", "Connection error: ") + e.message))
        } finally {
            conn?.disconnect()
        }
    }

    /** نتيجة قراءة رد واحد */
    class Parsed(val text: String, val calls: List<ToolCall>, val error: String? = null)

    companion object {
        private const val MAX_ROUNDS = 5
        private const val MAX_CALLS = 3

        /** موديلات اتجربت ورفضت الأدوات: بنكلمها بوضع الـ JSON parsing على طول */
        private val promptModeKeys: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        fun looksLikeNoTools(body: String): Boolean {
            val b = body.lowercase()
            return b.contains("tool") || b.contains("function")
        }

        /** قراءة رد SSE بتاع OpenAI: سطور data: {...} وآخرها data: [DONE] (مع tool_calls لو موجودة) */
        fun readSseFull(lines: Sequence<String>, onDelta: (String) -> Unit): Parsed {
            class Acc { var id = ""; var name = ""; val args = StringBuilder() }
            val sb = StringBuilder()
            val slots = java.util.TreeMap<Int, Acc>()
            for (raw in lines) {
                val line = raw.trim()
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val o = try { JSONObject(data) } catch (_: Exception) { continue }
                o.optJSONObject("error")?.let { e ->
                    return Parsed("", emptyList(), L.t("خطأ من الخدمة: ", "Service error: ") + e.optString("message", "?"))
                }
                val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta")
                val piece = delta?.optString("content", "")?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: choice.optJSONObject("message")?.optString("content", "")?.takeIf { it.isNotEmpty() && it != "null" }
                if (piece != null) { sb.append(piece); onDelta(piece) }
                val tcs = delta?.optJSONArray("tool_calls") ?: choice.optJSONObject("message")?.optJSONArray("tool_calls")
                if (tcs != null) for (i in 0 until tcs.length()) {
                    val t = tcs.optJSONObject(i) ?: continue
                    val f = t.optJSONObject("function")
                    val fname = f?.optString("name", "").orEmpty().let { if (it == "null") "" else it }
                    val idx = when {
                        t.has("index") -> t.optInt("index")
                        fname.isNotEmpty() -> slots.size
                        else -> maxOf(slots.size - 1, 0)
                    }
                    val a = slots.getOrPut(idx) { Acc() }
                    t.optString("id", "").takeIf { it.isNotEmpty() && it != "null" }?.let { a.id = it }
                    if (fname.isNotEmpty()) a.name = fname
                    when (val ar = f?.opt("arguments")) {
                        is String -> a.args.append(ar)
                        is JSONObject -> a.args.append(ar.toString())
                    }
                }
            }
            val calls = slots.values.filter { it.name.isNotEmpty() }.mapIndexed { i, a ->
                ToolCall(a.id.ifEmpty { "call_$i" }, a.name, argsOf(a.args.toString()))
            }
            return Parsed(sb.toString().trim(), calls)
        }

        fun readSse(lines: Sequence<String>, onDelta: (String) -> Unit): AiResult {
            val p = readSseFull(lines, onDelta)
            p.error?.let { return AiResult.Err(it) }
            return if (p.text.isEmpty()) AiResult.Err(L.t("الموديل رجّع رد فاضي.", "The model returned an empty reply.")) else AiResult.Ok(p.text)
        }

        fun parseFull(json: String): Parsed = try {
            val msg = JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val content = msg.optString("content", "").let { if (it == "null") "" else it }.trim()
            val tcs = msg.optJSONArray("tool_calls")
            val calls = if (tcs == null) emptyList() else (0 until tcs.length()).mapNotNull { i ->
                val t = tcs.optJSONObject(i) ?: return@mapNotNull null
                val f = t.optJSONObject("function") ?: return@mapNotNull null
                val name = f.optString("name", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val args = when (val a = f.opt("arguments")) {
                    is JSONObject -> a
                    is String -> argsOf(a)
                    else -> JSONObject()
                }
                ToolCall(t.optString("id", "").ifEmpty { "call_$i" }, name, args)
            }
            if (content.isEmpty() && calls.isEmpty()) Parsed("", emptyList(), L.t("الموديل رجّع رد فاضي.", "The model returned an empty reply."))
            else Parsed(content, calls)
        } catch (e: Exception) {
            Parsed("", emptyList(), L.t("رد غير متوقع من السيرفر.", "Unexpected response from the server."))
        }

        fun parse(json: String): AiResult {
            val p = parseFull(json)
            return if (p.error != null) AiResult.Err(p.error) else AiResult.Ok(p.text)
        }

        private fun argsOf(s: String): JSONObject = try { JSONObject(s.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }

        fun friendlyError(code: Int, body: String): String {
            val apiMsg = try {
                val o = JSONObject(body)
                o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: o.optString("message").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null } ?: body.take(200)
            val hint = when (code) {
                401, 403 -> L.t("الـ API Key غلط أو ملوش صلاحية.", "Invalid API Key or no access.")
                404 -> L.t("الـ Base URL أو اسم الموديل غلط.", "Wrong Base URL or model name.")
                429 -> L.t("عديت الحد المسموح، استنى شوية.", "Rate limit reached. Please wait a bit.")
                in 500..599 -> L.t("مشكلة في سيرفر الخدمة.", "The service is having problems.")
                else -> L.t("خطأ", "Error")
            }
            return "$hint ($code)\n$apiMsg".trim()
        }
    }
}
