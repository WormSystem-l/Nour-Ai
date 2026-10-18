package com.nour.assistant

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/** قايمة الموديلات المتاحة من الـ API بتاعك: بحث + ⭐ تثبيت + اختيار (زي Gemini/ChatGPT) */
object ModelPicker {
    fun show(act: AppCompatActivity, onChosen: (String) -> Unit) {
        val prefs = Prefs(act)
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dlg = BottomSheetDialog(act)

        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16)) }
        root.addView(TextView(act).apply { text = L.t("اختار الموديل", "Choose a model"); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val search = EditText(act).apply { hint = L.t("ابحث أو اكتب اسم موديل…", "Search or type a model name…"); setSingleLine() }
        root.addView(search)
        val status = TextView(act).apply { textSize = 13f; alpha = 0.8f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(status)
        val bar = ProgressBar(act).apply { isIndeterminate = true }
        root.addView(bar, LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val rv = RecyclerView(act).apply { layoutManager = LinearLayoutManager(act) }
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        dlg.setContentView(root)

        var all: List<String> = emptyList()
        var shown: List<String> = emptyList()
        var query = ""
        fun custom(q: String): List<String> = if (q.isNotBlank() && q !in all) listOf(q.trim()) else emptyList()

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = shown.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10)); isClickable = true
                    background = androidx.core.content.ContextCompat.getDrawable(act, android.R.drawable.list_selector_background)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val name = TextView(act).apply { textSize = 15f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); id = 1 }
                val star = TextView(act).apply { textSize = 20f; setPadding(dp(12), 0, dp(4), 0); id = 2 }
                row.addView(name); row.addView(star)
                return object : RecyclerView.ViewHolder(row) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val id = shown[i]
                val row = h.itemView as LinearLayout
                val name = row.findViewById<TextView>(1); val star = row.findViewById<TextView>(2)
                val pinned = id in prefs.pinnedModels
                name.text = (if (id == prefs.model) "✓ " else "") + id + (if (id !in all) "   (" + L.t("اسم مخصص", "custom") + ")" else "")
                star.text = if (pinned) "★" else "☆"
                star.alpha = if (pinned) 1f else 0.5f
                row.setOnClickListener { onChosen(id); dlg.dismiss() }
                star.setOnClickListener {
                    val p = prefs.pinnedModels
                    prefs.pinnedModels = if (pinned) p - id else p + id
                    all = ModelsClient.arrange(all, prefs.pinnedModels)
                    refilter()
                }
            }
            fun refilter() {
                val q = query.trim().lowercase()
                shown = (if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) }) + custom(query)
                notifyDataSetChanged()
            }
        }
        rv.adapter = adapter

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString().orEmpty(); adapter.refilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        fun load(force: Boolean) {
            bar.visibility = View.VISIBLE; status.text = L.t("بجيب الموديلات المتاحة…", "Loading available models…")
            act.lifecycleScope.launch {
                val r = ModelsClient.list(prefs.toConfig(), force)
                bar.visibility = View.GONE
                if (r.error != null) {
                    all = ModelsClient.arrange(prefs.pinnedModels, prefs.pinnedModels)
                    status.text = r.error + "\n" + L.t("تقدر تكتب اسم الموديل يدوي فوق.", "You can still type a model name above.")
                } else {
                    all = ModelsClient.arrange(r.models, prefs.pinnedModels)
                    status.text = L.t("${all.size} موديل متاح", "${all.size} models available")
                }
                adapter.refilter()
            }
        }
        dlg.show()
        load(false)
    }
}
