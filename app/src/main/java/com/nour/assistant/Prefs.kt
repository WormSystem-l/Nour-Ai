package com.nour.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** تطبيق مسموح لنور يفتحه */
data class AllowedApp(val label: String, val pkg: String)

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("nour", Context.MODE_PRIVATE)
    var baseUrl: String
        get() = p.getString("base", DEFAULT_BASE)!!
        set(v) = p.edit().putString("base", v.trim().trimEnd('/')).apply()
    var model: String
        get() = p.getString("model", DEFAULT_MODEL)!!
        set(v) = p.edit().putString("model", v.trim()).apply()
    var apiKey: String
        get() = p.getString("key", "")!!
        set(v) = p.edit().putString("key", v.trim()).apply()
    var system: String
        get() = p.getString("sys", DEFAULT_SYSTEM)!!
        set(v) = p.edit().putString("sys", v).apply()

    /** نداء "يا نور" */
    var wakeEnabled: Boolean
        get() = p.getBoolean("wake", false)
        set(v) = p.edit().putBoolean("wake", v).apply()

    /** التحكم بإيماءات اليد */
    var gestureEnabled: Boolean
        get() = p.getBoolean("gesture", false)
        set(v) = p.edit().putBoolean("gesture", v).apply()
    /** عكس اتجاه التمرير بالإبهام */
    var reverseScroll: Boolean
        get() = p.getBoolean("revscroll", false)
        set(v) = p.edit().putBoolean("revscroll", v).apply()

    /** لغة الصوت: auto (عربي + إنجليزي + لغة الجهاز) / ar / en */
    var voiceLang: String
        get() = p.getString("vlang", "auto")!!
        set(v) = p.edit().putString("vlang", v).apply()

    /** التطبيقات اللي نور مسموحله يفتحها (بدون حد أقصى) */
    var allowedApps: List<AllowedApp>
        get() = try {
            val a = JSONArray(p.getString("apps", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { AllowedApp(it.optString("label"), it.optString("pkg")) }
                .filter { it.pkg.isNotBlank() }
        } catch (_: Exception) { emptyList() }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("label", it.label).put("pkg", it.pkg)) }
            p.edit().putString("apps", a.toString()).apply()
        }

    fun toConfig() = AiConfig(baseUrl, model, apiKey, system)

    companion object {
        const val DEFAULT_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_SYSTEM = "You are Nour, a friendly smart assistant. Always reply in the same language the user writes in (Egyptian Arabic if they write in Arabic), and keep answers concise."
    }
}
