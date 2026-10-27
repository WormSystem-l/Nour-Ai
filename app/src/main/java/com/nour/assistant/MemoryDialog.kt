package com.nour.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nour.assistant.tools.MemoryStore
import com.nour.assistant.ui.NourDialog
import com.nour.assistant.ui.NourTheme

/** إدارة ذاكرة نور: شوف اللي نور فاكره عنك، أضف، عدّل بالضغط، أو امسح */
object MemoryDialog {
    fun show(act: Activity, onChanged: () -> Unit) {
        val store = MemoryStore(act)
        val pal = NourTheme.palette(act)
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(18f)) }
        root.addView(TextView(act).apply { text = L.t("ذاكرة نور", "Nour's memory"); textSize = 20f; setTextColor(pal.text); setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(act).apply {
            text = L.t("حقائق عنك على جهازك بس. بتتبعت للموديل كجزء من الرسالة عشان يفهمك أحسن. مفيش كلمات سر أو أرقام حساسة بتتحفظ.",
                "Facts about you, stored on your device only. They are sent to the model as part of the prompt so it understands you better. Passwords and sensitive numbers are never saved.")
            textSize = 12.5f; setTextColor(pal.textDim); setPadding(0, dp(4f), 0, dp(10f))
        })
        val add = EditText(act).apply {
            hint = L.t("أضف حاجة عنك… (مثلاً: اسمي هادي)", "Add something about you…"); setHintTextColor(pal.textFaint); setTextColor(pal.text); textSize = 14.5f
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            background = NourTheme.glass(act, 20f, pal); setPadding(dp(16f), dp(11f), dp(16f), dp(11f))
        }
        root.addView(add, LinearLayout.LayoutParams(-1, -2))
        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10f), 0, 0) }
        root.addView(listBox)
        val clear = TextView(act).apply {
            text = L.t("مسح الكل", "Clear all"); textSize = 14f; setTextColor(pal.danger); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f)); isClickable = true
            background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(20f).toFloat() }
        }
        root.addView(clear, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        fun refresh() {
            listBox.removeAllViews()
            val facts = store.all()
            clear.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
            if (facts.isEmpty()) listBox.addView(TextView(act).apply { text = L.t("لسه مفيش حاجة محفوظة.", "Nothing saved yet."); setTextColor(pal.textFaint); textSize = 14f; setPadding(dp(4f), dp(8f), 0, 0) })
            facts.forEachIndexed { i, f ->
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14f), dp(10f), dp(6f), dp(10f)); background = NourTheme.glass(act, 16f, pal)
                }
                row.addView(TextView(act).apply { text = f; textSize = 14.5f; setTextColor(pal.text); isClickable = true
                    setOnClickListener {
                        // تعديل: نحط النص في خانة الإضافة ونمسح القديم
                        add.setText(f); add.setSelection(f.length); store.remove(i); refresh(); onChanged()
                    } }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(act).apply { text = "✕"; textSize = 16f; setTextColor(pal.textDim); setPadding(dp(14f), dp(6f), dp(12f), dp(6f)); isClickable = true
                    setOnClickListener { store.remove(i); refresh(); onChanged() } })
                listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) })
            }
        }
        add.setOnEditorActionListener { _, _, _ ->
            if (store.add(add.text.toString())) { add.setText(""); refresh(); onChanged() }
            true
        }
        clear.setOnClickListener {
            NourDialog.confirm(act, L.t("مسح الذاكرة؟", "Clear memory?"), L.t("كل اللي نور فاكره عنك هيتمسح.", "Everything Nour remembers about you will be deleted."),
                L.t("مسح", "Clear"), L.t("إلغاء", "Cancel"), danger = true) { store.clear(); refresh(); onChanged() }
        }
        refresh()
        NourDialog.sheet(act, ScrollView(act).apply { addView(root); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }, fullHeight = true).show()
    }

    @Suppress("unused") private val keep = Color.BLACK
}
