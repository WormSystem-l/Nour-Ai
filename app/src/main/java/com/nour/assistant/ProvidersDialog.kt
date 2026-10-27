package com.nour.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nour.assistant.ui.NourDialog
import com.nour.assistant.ui.NourTheme
import java.util.concurrent.Executors

/**
 * نافذة إدارة مزوّدي الذكاء الاصطناعي (من 1 لـ 5): الاسم + الـ URL + الـ API Key + تفعيل + اختبار + حذف.
 * كلهم بيشتغلوا مع بعض وموديلاتهم بتظهر مجمّعة في الشاشة الرئيسية.
 */
object ProvidersDialog {
    private class Card(var id: String, val name: EditText, val url: EditText, val key: EditText, val enabled: Switch, val result: TextView, val root: View)

    private val io = Executors.newFixedThreadPool(3)

    fun show(act: Activity, onSaved: () -> Unit) {
        val prefs = Prefs(act)
        val pal = NourTheme.palette(act)
        val d = act.resources.displayMetrics.density
        val main = Handler(Looper.getMainLooper())
        fun dp(v: Float) = (v * d).toInt()

        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(14f)) }
        root.addView(TextView(act).apply { text = L.t("مزوّدو الذكاء الاصطناعي", "AI providers"); textSize = 20f; setTextColor(pal.text); setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(act).apply {
            text = L.t("أضف من 1 إلى 5 مواقع (API + URL). كلهم شغالين مع بعض، وموديلاتهم بتظهر في الشاشة الرئيسية.",
                "Add 1 to 5 providers (API + URL). They all work together and their models appear on the main screen.")
            textSize = 13f; setTextColor(pal.textDim); setPadding(0, dp(4f), 0, dp(8f))
        })

        val cardsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val cards = ArrayList<Card>()
        val addBtn = TextView(act)

        fun field(hint: String, pw: Boolean = false, ltr: Boolean = false) = EditText(act).apply {
            this.hint = hint; setHintTextColor(pal.textFaint); setTextColor(pal.text); textSize = 14.5f; setSingleLine()
            background = GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = dp(14f).toFloat() }
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            inputType = when {
                pw -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                ltr -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (ltr) { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        }

        fun refreshAdd() {
            addBtn.alpha = if (cards.size >= ProviderStore.MAX) 0.35f else 1f
            addBtn.text = "＋  " + L.t("إضافة مزوّد (${cards.size}/${ProviderStore.MAX})", "Add provider (${cards.size}/${ProviderStore.MAX})")
        }

        fun addCard(p: Provider?, preset: ProviderPresets.Preset? = null) {
            if (cards.size >= ProviderStore.MAX) { Toast.makeText(act, L.t("الحد الأقصى 5 مزوّدين", "Maximum 5 providers"), Toast.LENGTH_SHORT).show(); return }
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; background = NourTheme.glass(act, 20f, pal); setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = field(L.t("اسم المزوّد", "Provider name")).apply { setText(p?.name ?: preset?.name ?: "") }
            val sw = Switch(act).apply { isChecked = p?.enabled ?: true }
            head.addView(name, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10f) })
            val url = field("https://…/v1", ltr = true).apply { setText(p?.baseUrl ?: preset?.url ?: "") }
            val key = field("API Key", pw = true, ltr = true).apply { setText(p?.apiKey ?: "") }
            val res = TextView(act).apply { textSize = 12.5f; setTextColor(pal.textDim); setPadding(dp(4f), dp(6f), 0, 0) }
            val actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8f), 0, 0) }
            fun pill(t: String, color: Int, click: () -> Unit) = TextView(act).apply {
                text = t; textSize = 13.5f; setTextColor(color); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(dp(16f), dp(8f), dp(16f), dp(8f)); background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(18f).toFloat() }
                isClickable = true; setOnClickListener { click() }
            }
            val card = Card(p?.id ?: ProviderStore.newId(), name, url, key, sw, res, box)
            actions.addView(pill(L.t("اختبار", "Test"), pal.accent) {
                val pr = Provider(card.id, name.text.toString(), url.text.toString().trim().trimEnd('/'), key.text.toString().trim())
                if (!pr.baseUrl.startsWith("http")) { res.text = "⚠️ " + L.t("الـ URL لازم يبدأ بـ https://", "URL must start with https://"); return@pill }
                res.setTextColor(pal.textDim); res.text = L.t("بختبر…", "Testing…")
                io.execute {
                    val r = ModelsClient.fetchBlocking(pr)
                    main.post {
                        if (r.error == null) { res.setTextColor(0xFF86EFAC.toInt()); res.text = "✓ " + L.t("شغال — ${ModelsClient.arrange(r.models, emptyList()).size} موديل", "Works — ${ModelsClient.arrange(r.models, emptyList()).size} models") }
                        else { res.setTextColor(pal.danger); res.text = "✗ " + r.error }
                    }
                }
            })
            actions.addView(pill(L.t("حذف", "Delete"), pal.danger) {
                cards.remove(card); cardsBox.removeView(box); refreshAdd()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) })
            box.addView(head); box.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
            box.addView(key, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) }); box.addView(res); box.addView(actions)
            cards.add(card)
            cardsBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
            refreshAdd()
        }

        prefs.providers().forEach { addCard(it) }
        if (cards.isEmpty()) addCard(null, ProviderPresets.list.first())

        root.addView(cardsBox)

        // قوالب جاهزة
        root.addView(TextView(act).apply { text = L.t("قوالب جاهزة (بتملا الاسم والرابط):", "Presets (fill name & URL):"); textSize = 12.5f; setTextColor(pal.textFaint); setPadding(dp(4f), dp(4f), 0, dp(6f)) })
        val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        ProviderPresets.list.forEach { pr ->
            chips.addView(TextView(act).apply {
                text = pr.name; textSize = 13f; setTextColor(pal.text); setPadding(dp(14f), dp(8f), dp(14f), dp(8f)); isClickable = true
                background = NourTheme.glass(act, 18f, pal)
                setOnClickListener {
                    // لو فيه كرت فاضي نملاه، وإلا نضيف كرت جديد
                    val empty = cards.firstOrNull { it.url.text.isBlank() && it.key.text.isBlank() }
                    if (empty != null) { empty.name.setText(pr.name); empty.url.setText(pr.url) } else addCard(null, pr)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f) })
        }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        addBtn.apply {
            textSize = 15f; setTextColor(pal.text); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16f), dp(13f), dp(16f), dp(13f)); background = NourTheme.glass(act, 22f, pal); isClickable = true
            setOnClickListener { addCard(null) }
        }
        root.addView(addBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        refreshAdd()

        val dlg = NourDialog.sheet(act, ScrollView(act).apply { addView(root); overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }, fullHeight = true)

        val save = TextView(act).apply {
            text = L.t("حفظ", "Save"); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f)); background = NourTheme.accentFill(act, 24f, pal); isClickable = true
        }
        save.setOnClickListener {
            val list = ArrayList<Provider>()
            for (c in cards) {
                val u = c.url.text.toString().trim().trimEnd('/')
                val k = c.key.text.toString().trim()
                if (u.isEmpty() && k.isEmpty()) continue
                if (!u.startsWith("http://") && !u.startsWith("https://")) { c.url.error = L.t("لازم يبدأ بـ https://", "Must start with https://"); return@setOnClickListener }
                list.add(Provider(c.id, c.name.text.toString().trim().ifBlank { hostOf(u) }, u, k, c.enabled.isChecked))
            }
            ProviderStore(act).save(list)
            // لو المزوّد النشط اتحذف/اتقفل: أول مزوّد مفعّل
            if (list.none { it.id == prefs.lastProviderId && it.enabled }) {
                list.firstOrNull { it.enabled }?.let { prefs.lastProviderId = it.id; prefs.model = "" }
            }
            Toast.makeText(act, L.t("اتحفظت ✓", "Saved ✓"), Toast.LENGTH_SHORT).show()
            dlg.dismiss(); onSaved()
        }
        // زرار الحفظ ثابت تحت
        (root.parent as? ScrollView)?.let { sv ->
            val holder = sv.parent as? ViewGroup
            holder?.addView(save, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16f), 0, dp(16f), dp(14f)) })
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, dp(84f))
        }
        dlg.show()
    }

    private fun hostOf(u: String) = try { java.net.URI(u).host?.removePrefix("api.")?.substringBefore('.')?.replaceFirstChar { it.uppercase() } ?: "AI" } catch (_: Exception) { "AI" }
}
