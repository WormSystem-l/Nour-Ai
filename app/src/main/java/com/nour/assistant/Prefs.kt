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

    /** حساسية نداء "يا نور": 0 منخفضة / 1 متوسطة / 2 عالية (تسمع من بعيد) */
    var wakeSens: Int
        get() = p.getInt("wsens", 2)
        set(v) = p.edit().putInt("wsens", v.coerceIn(0, 2)).apply()

    /** حساسية تمرير الأصابع: 0 منخفضة / 1 متوسطة / 2 عالية */
    var gestureSens: Int
        get() = p.getInt("gsens", 1)
        set(v) = p.edit().putInt("gsens", v.coerceIn(0, 2)).apply()

    /** السماح لنور بالبحث في الإنترنت */
    var webSearch: Boolean
        get() = p.getBoolean("web", true)
        set(v) = p.edit().putBoolean("web", v).apply()

    /** موديلات مثبّتة (⭐) تظهر الأول في القايمة */
    var pinnedModels: List<String>
        get() = try { val a = JSONArray(p.getString("pins", "[]")); (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { emptyList() }
        set(v) = p.edit().putString("pins", JSONArray(v).toString()).apply()

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
