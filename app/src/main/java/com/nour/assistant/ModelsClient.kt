package com.nour.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** بيجيب الموديلات المتاحة من كل مزوّد (GET {base}/models) — OpenAI / Groq / OpenRouter / Gemini (OpenAI-compatible) */
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

    /** نداء شبكة مباشر (لازم على Thread خلفي) */
    fun fetchBlocking(p: Provider): Result {
        if (p.apiKey.isBlank()) return Result(emptyList(), L.t("المفتاح فاضي", "API key is empty"))
        var c: HttpURLConnection? = null
        return try {
            c = (URL("${p.baseUrl.trimEnd('/')}/models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 18_000
                setRequestProperty("Authorization", "Bearer ${p.apiKey}")
                setRequestProperty("Accept", "application/json")
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                AppLog.eOnce("models", "${p.host} HTTP $code ${body.take(120).replace('\n', ' ')}")
                return Result(emptyList(), AiClient.friendlyError(code, body))
            }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val arr = o.optJSONArray("data") ?: o.optJSONArray("models")
                ?: return Result(emptyList(), L.t("رد غير متوقع من السيرفر.", "Unexpected response from the server."))
            Result(parseIds(arr), null)
        } catch (e: java.net.UnknownHostException) {
            Result(emptyList(), L.t("مفيش إنترنت أو العنوان غلط.", "No internet connection, or the URL is wrong."))
        } catch (e: Exception) {
            AppLog.eOnce("models", "${p.host} fetch failed", e)
            Result(emptyList(), L.t("تعذّر جلب الموديلات: ", "Couldn't load models: ") + e.message)
        } finally { c?.disconnect() }
    }

    fun parseIds(arr: JSONArray): List<String> = (0 until arr.length()).mapNotNull { i ->
        when (val e = arr.opt(i)) {
            is JSONObject -> e.optString("id").ifBlank { e.optString("name") }.ifBlank { null }
            is String -> e
            else -> null
        }
    }

    // ---- كاش دائم (القايمة تفتح فوراً وتتحدّث في الخلفية)
    private fun cachePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("nour_models", Context.MODE_PRIVATE)

    fun cached(ctx: Context, providerId: String): List<String> = try {
        val a = JSONArray(cachePrefs(ctx).getString(providerId, "[]")); (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    fun saveCache(ctx: Context, providerId: String, ids: List<String>) {
        cachePrefs(ctx).edit().putString(providerId, JSONArray(ids).toString()).apply()
    }
}
