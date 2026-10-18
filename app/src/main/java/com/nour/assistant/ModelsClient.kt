package com.nour.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** بيجيب الموديلات المتاحة من الـ API (GET {base}/models) — شغال مع OpenAI / Groq / OpenRouter / Gemini (OpenAI-compatible) */
object ModelsClient {
    class Result(val models: List<String>, val error: String?)

    private val SKIP = listOf("embed", "whisper", "tts", "moderat", "guard", "rerank", "dall-e", "imagen", "veo", "transcri",
        "speech", "realtime", "aqa", "bison", "gecko", "-audio", "audio-", "image-gen", "text-to-")

    fun isChat(id: String): Boolean { val l = id.lowercase(); return SKIP.none { l.contains(it) } }

    fun clean(id: String) = id.removePrefix("models/")

    /** تنظيف + ترتيب (المثبّتة الأول ثم أبجدي) */
    fun arrange(ids: List<String>, pinned: List<String>): List<String> {
        val all = ids.map { clean(it) }.filter { isChat(it) }.distinct()
        val pins = pinned.filter { it in all }
        return pins + all.filter { it !in pins }.sortedBy { it.lowercase() }
    }

    @Volatile private var cacheKey = ""
    @Volatile private var cache: List<String> = emptyList()

    suspend fun list(cfg: AiConfig, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (cfg.apiKey.isBlank()) return@withContext Result(emptyList(), L.t("حط الـ API Key من الإعدادات الأول.", "Add your API key in Settings first."))
        val key = cfg.baseUrl + "|" + cfg.apiKey.hashCode()
        if (!force && key == cacheKey && cache.isNotEmpty()) return@withContext Result(cache, null)
        var c: HttpURLConnection? = null
        try {
            c = (URL("${cfg.baseUrl.trimEnd('/')}/models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                setRequestProperty("Accept", "application/json")
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                AppLog.eOnce("models", "HTTP $code ${body.take(120)}")
                return@withContext Result(emptyList(), AiClient.friendlyError(code, body))
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val arr = o.optJSONArray("data") ?: o.optJSONArray("models")
                ?: return@withContext Result(emptyList(), L.t("رد غير متوقع من السيرفر.", "Unexpected response from the server."))
            val ids = (0 until arr.length()).mapNotNull { i ->
                val e = arr.opt(i)
                when (e) { is JSONObject -> e.optString("id").ifBlank { e.optString("name") }.ifBlank { null }; is String -> e; else -> null }
            }
            cacheKey = key; cache = ids
            Result(ids, null)
        } catch (e: java.net.UnknownHostException) {
            Result(emptyList(), L.t("مفيش إنترنت أو العنوان غلط.", "No internet connection, or the URL is wrong."))
        } catch (e: Exception) {
            AppLog.eOnce("models", "list failed", e)
            Result(emptyList(), L.t("تعذّر جلب الموديلات: ", "Couldn't load models: ") + e.message)
        } finally { c?.disconnect() }
    }
}
