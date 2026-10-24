package com.nour.assistant

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nour.assistant.ui.NourDialog
import com.nour.assistant.ui.NourTheme
import java.util.concurrent.Executors

/**
 * قايمة الموديلات من كل المزوّدين المفعّلين مع بعض (مقسّمة بعنوان لكل مزوّد):
 * بحث + ⭐ تثبيت + اكتب اسم موديل يدوي. القايمة بتفتح فوراً من الكاش وبتتحدّث في الخلفية.
 * اختيارك بيتحفظ (آخر موديل بيرجع لوحده بعد الخروج والدخول).
 */
object ModelPicker {
    private sealed class Row {
        data class Header(val provider: Provider, val count: Int, val note: String?) : Row()
        data class Model(val provider: Provider, val id: String) : Row()
        data class Custom(val provider: Provider, val id: String) : Row()
    }

    private val io = Executors.newFixedThreadPool(3)

    fun show(act: Activity, onChosen: (Provider, String) -> Unit) {
        val prefs = Prefs(act)
        val pal = NourTheme.palette(act)
        val d = act.resources.displayMetrics.density
        val main = Handler(Looper.getMainLooper())
        val providers = prefs.providers().filter { it.enabled && it.baseUrl.isNotBlank() }

        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (14 * d).toInt()) }
        root.addView(TextView(act).apply { text = L.t("اختار الموديل", "Choose a model"); textSize = 19f; setTextColor(pal.text); setTypeface(typeface, Typeface.BOLD) })
        val search = EditText(act).apply {
            hint = L.t("ابحث أو اكتب اسم موديل…", "Search or type a model name…"); setHintTextColor(pal.textFaint); setTextColor(pal.text); textSize = 15f
            setSingleLine(); background = NourTheme.glass(act, 22f, pal); setPadding((16 * d).toInt(), (11 * d).toInt(), (16 * d).toInt(), (11 * d).toInt())
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() })
        val status = TextView(act).apply { textSize = 12.5f; setTextColor(pal.textDim); setPadding((4 * d).toInt(), (8 * d).toInt(), 0, (4 * d).toInt()) }
        root.addView(status)
        val rv = RecyclerView(act).apply { layoutManager = LinearLayoutManager(act); overScrollMode = View.OVER_SCROLL_NEVER }
        root.addView(rv, LinearLayout.LayoutParams(-1, (400 * d).toInt()))

        val dlg = NourDialog.sheet(act, root, fullHeight = false)
        var query = ""
        val models = HashMap<String, List<String>>()      // providerId -> ids (مرتبة)
        val notes = HashMap<String, String?>()            // ملاحظة خطأ لكل مزوّد
        val loading = HashSet<String>()
        var rows: List<Row> = emptyList()

        fun key(p: Provider, id: String) = "${p.id}|$id"

        fun rebuild() {
            val q = query.trim().lowercase()
            val out = ArrayList<Row>()
            val pins = prefs.pinnedModels
            for (p in providers) {
                val all = models[p.id].orEmpty()
                val filtered = if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) }
                // المثبّتة الأول
                val sorted = filtered.filter { key(p, it) in pins } + filtered.filter { key(p, it) !in pins }
                out += Row.Header(p, all.size, if (p.id in loading) L.t("بيتحدّث…", "updating…") else notes[p.id])
                sorted.forEach { out += Row.Model(p, it) }
            }
            val typed = query.trim()
            if (typed.isNotEmpty() && providers.none { p -> models[p.id].orEmpty().any { it.equals(typed, true) } }) {
                providers.firstOrNull { it.id == prefs.lastProviderId }?.let { out.add(0, Row.Custom(it, typed)) }
                    ?: providers.firstOrNull()?.let { out.add(0, Row.Custom(it, typed)) }
            }
            rows = out
            rv.adapter?.notifyDataSetChanged()
            status.text = when {
                providers.isEmpty() -> L.t("مفيش مزوّد مفعّل. أضف API من الإعدادات.", "No active provider. Add an API in Settings.")
                loading.isNotEmpty() && models.values.all { it.isEmpty() } -> L.t("بجيب الموديلات المتاحة…", "Loading available models…")
                else -> L.t("${models.values.sumOf { it.size }} موديل من ${providers.size} مزوّد", "${models.values.sumOf { it.size }} models from ${providers.size} providers")
            }
        }

        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(i: Int) = when (rows[i]) { is Row.Header -> 0; else -> 1 }
            override fun onCreateViewHolder(parent: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val ctx = parent.context
                val v = if (t == 0) LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                    addView(TextView(ctx).apply { id = 11; textSize = 13.5f; setTextColor(pal.accent); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.04f }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ProgressBar(ctx).apply { id = 13; isIndeterminate = true }, LinearLayout.LayoutParams((16 * d).toInt(), (16 * d).toInt()))
                    addView(TextView(ctx).apply { id = 12; textSize = 12f; setTextColor(pal.textFaint); setPadding((8 * d).toInt(), 0, 0, 0); maxWidth = (190 * d).toInt(); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                } else LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * d).toInt(), (11 * d).toInt(), (6 * d).toInt(), (11 * d).toInt()); isClickable = true
                    addView(TextView(ctx).apply { id = 21; textSize = 15f; setTextColor(pal.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(ctx).apply { id = 22; textSize = 21f; setPadding((14 * d).toInt(), 0, (10 * d).toInt(), 0) })
                }
                v.layoutParams = RecyclerView.LayoutParams(-1, -2)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val v = h.itemView
                when (val r = rows[i]) {
                    is Row.Header -> {
                        v.findViewById<TextView>(11).text = r.provider.name.uppercase()
                        v.findViewById<TextView>(12).text = r.note ?: ""
                        v.findViewById<View>(13).visibility = if (r.provider.id in loading) View.VISIBLE else View.GONE
                    }
                    is Row.Model -> bindModel(v, r.provider, r.id, false)
                    is Row.Custom -> bindModel(v, r.provider, r.id, true)
                }
            }
            private fun bindModel(v: View, p: Provider, id: String, custom: Boolean) {
                val sel = prefs.model == id && prefs.lastProviderId == p.id
                val pinned = key(p, id) in prefs.pinnedModels
                v.findViewById<TextView>(21).apply {
                    text = (if (sel) "✓  " else "") + id + (if (custom) "   · " + L.t("اسم مخصص (${p.name})", "custom (${p.name})") else "")
                    setTextColor(if (sel) pal.accent else pal.text)
                }
                v.background = if (sel) NourTheme.glass(act, 14f, pal, strong = true) else null
                v.findViewById<TextView>(22).apply {
                    text = if (pinned) "★" else "☆"; setTextColor(if (pinned) 0xFFFFC857.toInt() else pal.textFaint)
                    visibility = if (custom) View.INVISIBLE else View.VISIBLE
                    setOnClickListener {
                        val cur = prefs.pinnedModels; val k = key(p, id)
                        prefs.pinnedModels = if (pinned) cur - k else cur + k
                        rebuild()
                    }
                }
                v.setOnClickListener { prefs.selectModel(p.id, id); onChosen(p, id); dlg.dismiss() }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { query = e?.toString().orEmpty(); rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // 1) الكاش فوراً   2) تحديث من الشبكة لكل مزوّد بالتوازي
        for (p in providers) models[p.id] = ModelsClient.arrange(ModelsClient.cached(act, p.id), emptyList())
        rebuild()
        for (p in providers) {
            loading += p.id
            io.execute {
                val r = ModelsClient.fetchBlocking(p)
                main.post {
                    loading -= p.id
                    if (r.error == null) { models[p.id] = ModelsClient.arrange(r.models, emptyList()); notes[p.id] = null; ModelsClient.saveCache(act, p.id, models[p.id].orEmpty()) }
                    else notes[p.id] = r.error
                    if (dlg.isShowing) rebuild()
                }
            }
        }
        dlg.show()
    }

    @Suppress("unused") private val keepShape = GradientDrawable::class
}
