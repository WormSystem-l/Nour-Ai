package com.nour.assistant.gesture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SavedGesture(val id: String, val name: String, val action: String, val features: FloatArray)

/** تخزين إيماءات المستخدم المخصصة (على الجهاز بس) */
object GestureStore {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("nour_gestures", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<SavedGesture> = try {
        val a = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val f = o.getJSONArray("f")
            if (f.length() != 42) null
            else SavedGesture(o.getString("id"), o.optString("name"), o.getString("action"), FloatArray(42) { f.getDouble(it).toFloat() })
        }
    } catch (_: Exception) { emptyList() }

    fun save(ctx: Context, list: List<SavedGesture>) {
        val a = JSONArray()
        list.forEach { g ->
            a.put(JSONObject().put("id", g.id).put("name", g.name).put("action", g.action)
                .put("f", JSONArray().also { arr -> g.features.forEach { arr.put(it.toDouble()) } }))
        }
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    fun add(ctx: Context, g: SavedGesture) = save(ctx, load(ctx) + g)
    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filter { it.id != id })
}

/** تسجيل إيماءة جديدة: بنجمع عينات لحد ما تثبت، وبنرجّع متوسطها */
object GestureRecorder {
    class Session(
        val target: Int,
        val onProgress: (Int, Int) -> Unit,
        val onDone: (FloatArray?) -> Unit
    ) { val samples = ArrayList<FloatArray>() }

    @Volatile var session: Session? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start(target: Int = 30, onProgress: (Int, Int) -> Unit, onDone: (FloatArray?) -> Unit) {
        session = Session(target, onProgress, onDone)
    }

    fun cancel() { session = null }

    /** بتتنادى من خدمة الإيماءات مع كل إطار فيه يد */
    fun feed(features: FloatArray) {
        val s = session ?: return
        s.samples.add(features)
        main.post { s.onProgress(s.samples.size, s.target) }
        if (s.samples.size >= s.target) {
            session = null
            val spread = GestureLogic.spread(s.samples)
            val result = if (spread <= MAX_SPREAD) GestureLogic.avgFeatures(s.samples) else null
            main.post { s.onDone(result) }
        }
    }

    /** أقصى تشتت مسموح للتسجيل (لو اليد اتحركت كتير نرفض) */
    const val MAX_SPREAD = 0.12f
}
