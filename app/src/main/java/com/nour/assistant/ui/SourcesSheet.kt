package com.nour.assistant.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nour.assistant.L
import com.nour.assistant.tools.Source

/** نافذة المصادر: المواقع اللي نور بحث فيها/قرأها في رد واحد بس، مجمّعة حسب عملية البحث */
object SourcesSheet {
    fun show(act: Activity, sources: List<Source>) {
        val pal = NourTheme.palette(act)
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(18f)) }
        box.addView(TextView(act).apply { text = L.t("المصادر", "Sources") + "  ·  ${sources.size}"; textSize = 19f; setTextColor(pal.text); setTypeface(typeface, Typeface.BOLD) })
        var lastQuery: String? = null
        for (s in sources) {
            if (s.query.isNotBlank() && s.query != lastQuery) {
                lastQuery = s.query
                box.addView(TextView(act).apply {
                    text = L.t("البحث عن: ", "Searched for: ") + s.query + (if (s.via.isNotBlank()) L.t("  ·  عبر ", "  ·  via ") + s.via else "")
                    textSize = 12.5f; setTextColor(pal.textFaint); setPadding(dp(4f), dp(14f), 0, dp(6f))
                })
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true
                setPadding(dp(14f), dp(11f), dp(14f), dp(11f)); background = NourTheme.glass(act, 18f, pal)
                setOnClickListener { try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
            }
            row.addView(TextView(act).apply { text = s.title.ifBlank { s.domain }; textSize = 15f; setTextColor(pal.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            row.addView(TextView(act).apply { text = s.domain; textSize = 12.5f; setTextColor(pal.accent); setPadding(0, dp(2f), 0, 0) })
            if (s.snippet.isNotBlank()) row.addView(TextView(act).apply { text = s.snippet; textSize = 13f; setTextColor(pal.textDim); maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4f), 0, 0) })
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        }
        NourDialog.sheet(act, ScrollView(act).apply { addView(box); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }, fullHeight = sources.size > 4).show()
    }
}
