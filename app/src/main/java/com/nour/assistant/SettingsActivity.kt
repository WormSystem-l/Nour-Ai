package com.nour.assistant

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nour.assistant.gesture.GestureEditorActivity
import com.nour.assistant.gesture.GestureService
import com.nour.assistant.gesture.NourAccessibilityService
import com.nour.assistant.ui.NourDialog
import com.nour.assistant.ui.NourOrbView
import com.nour.assistant.ui.NourTheme
import com.nour.assistant.wake.WakeController
import com.nour.assistant.wake.WakeModels
import com.nour.assistant.wake.WakeWordService
import kotlinx.coroutines.launch

/** الإعدادات بتصميم جديد: كروت زجاجية مقسّمة، وبتتغير مع الثيم (أسود تماماً / ألوان). */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var pal: NourTheme.Palette
    private lateinit var box: LinearLayout
    private var downloading = false
    private var downloadProgress = 0
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Float) = (v * d).toInt()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enableWake() else { prefs.wakeEnabled = false; Toast.makeText(this, R.string.mic_permission, Toast.LENGTH_LONG).show(); render() }
    }
    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { prefs.gestureEnabled = true; GestureService.start(this) }
        else { prefs.gestureEnabled = false; Toast.makeText(this, L.t("محتاج إذن الكاميرا", "Camera permission is needed"), Toast.LENGTH_LONG).show() }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pal = NourTheme.palette(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        NourTheme.applyScreen(this, root)
        // الشريط العلوي
        val top = FrameLayout(this).apply { setPadding(dp(8f), 0, dp(8f), 0) }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(pal.text); background = null; setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            contentDescription = L.t("رجوع", "Back"); setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(46f), dp(46f), Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(TextView(this).apply { text = getString(R.string.settings); textSize = 19f; setTextColor(pal.text); setTypeface(typeface, Typeface.BOLD) },
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56f)))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(4f), dp(14f), dp(30f)) }
        root.addView(ScrollView(this).apply { addView(box); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); if (::box.isInitialized) render() }

    // ------------------------------------------------------------------ مكونات الواجهة

    private fun section(title: String): LinearLayout {
        box.addView(TextView(this).apply {
            text = title; textSize = 12.5f; setTextColor(pal.textFaint); letterSpacing = 0.06f
            setPadding(dp(8f), dp(20f), 0, dp(8f))
        })
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = NourTheme.glass(this@SettingsActivity, 22f, pal) }
        box.addView(card, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun divider(card: LinearLayout) {
        if (card.childCount > 0) card.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) },
            LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(16f); marginEnd = dp(16f) })
    }

    private fun textCol(title: String, sub: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(pal.text) })
        if (!sub.isNullOrBlank()) addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(pal.textDim); setPadding(0, dp(2f), 0, 0) })
    }

    private fun row(card: LinearLayout, title: String, sub: String? = null, value: String? = null, onClick: () -> Unit): View {
        divider(card)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16f), dp(14f), dp(14f), dp(14f)); isClickable = true; isFocusable = true }
        r.addView(textCol(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(pal.accent); setPadding(dp(8f), 0, dp(4f), 0); maxWidth = dp(150f); maxLines = 1 })
        r.addView(TextView(this).apply { text = if (rtl()) "‹" else "›"; textSize = 22f; setTextColor(pal.textFaint); setPadding(dp(6f), 0, 0, 0) })
        r.setOnClickListener { onClick() }
        card.addView(r, LinearLayout.LayoutParams(-1, -2))
        return r
    }

    private fun rtl() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun switchRow(card: LinearLayout, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        divider(card)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16f), dp(12f), dp(14f), dp(12f)) }
        r.addView(textCol(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, 0xFFB0B0B8.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pal.accent, 0x55FFFFFF))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        card.addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun lvl(i: Int) = L.t(listOf("منخفضة", "متوسطة", "عالية")[i], listOf("Low", "Medium", "High")[i])

    // ------------------------------------------------------------------ بناء الصفحة

    private fun render() {
        box.removeAllViews()
        themeSection()
        aiSection()
        memorySection()
        appsSection()
        voiceSection()
        gestureSection()
        systemSection()
    }

    /** اختيار الثيم بمعاينة */
    private fun themeSection() {
        val card = section(L.t("المظهر", "APPEARANCE"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12f), dp(12f), dp(12f), dp(12f)) }
        fun option(black: Boolean, label: String): View {
            val p = NourTheme.palette(black)
            val selected = (prefs.themeMode != "color") == black
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6f), dp(6f), dp(6f), dp(8f)) }
            val prev = FrameLayout(this).apply {
                background = NourTheme.background(this@SettingsActivity, p).constantState?.newDrawable()?.mutate()
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(16f).toFloat()) }
                }
            }
            prev.addView(NourOrbView(this).apply { animated = false }, FrameLayout.LayoutParams(dp(56f), dp(56f), Gravity.CENTER))
            // خطوط رمزية للشات
            prev.addView(View(this).apply { background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(8f).toFloat() } },
                FrameLayout.LayoutParams(dp(60f), dp(10f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10f) })
            c.addView(prev, LinearLayout.LayoutParams(dp(112f), dp(120f)))
            c.addView(TextView(this).apply { text = label; textSize = 13.5f; setTextColor(if (selected) pal.accent else pal.text); setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL); setPadding(0, dp(8f), 0, 0) })
            c.background = if (selected) GradientDrawable().apply { setColor(0x14FFFFFF); setStroke(dp(2f), pal.accent); cornerRadius = dp(20f).toFloat() } else null
            c.isClickable = true
            c.setOnClickListener {
                if (!selected) { prefs.themeMode = if (black) "black" else "color"; recreate() }
            }
            return c
        }
        row.addView(option(true, L.t("أسود", "Black")), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(option(false, L.t("ألوان عصرية", "Modern colors")), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
    }

    private fun aiSection() {
        val card = section(L.t("الذكاء الاصطناعي", "AI"))
        val ps = prefs.providers()
        val on = ps.count { it.enabled }
        row(card, L.t("مزوّدو الذكاء الاصطناعي", "AI providers"),
            if (ps.isEmpty()) L.t("أضف API و URL (من 1 إلى 5)", "Add API & URL (1 to 5)") else L.t("$on مفعّل من ${ps.size} — كلهم شغالين مع بعض", "$on active of ${ps.size} — all work together"),
            value = prefs.activeProvider()?.name) { ProvidersDialog.show(this) { render() } }
        row(card, L.t("الموديل الحالي", "Current model"), null, value = prefs.model.ifBlank { L.t("لم يُختر", "none") }) { ModelPicker.show(this) { _, _ -> render() } }
        switchRow(card, L.t("البحث في الإنترنت", "Web search"), L.t("نور يبحث لوحده لما يحتاج (نتايج مصغّرة، مجاني)", "Nour searches by itself when needed (small results, free)"), prefs.webSearch) { prefs.webSearch = it }
        row(card, L.t("تعليمات النظام", "System prompt"), L.t("شخصية نور وطريقة ردوده", "Nour's personality and reply style")) { editSystemPrompt() }
        val langName = L.t(when (prefs.voiceLang) { "ar" -> "عربي"; "en" -> "إنجليزي"; else -> "تلقائي" }, when (prefs.voiceLang) { "ar" -> "Arabic"; "en" -> "English"; else -> "Auto" })
        row(card, L.t("لغة الصوت", "Voice language"), L.t("تلقائي = عربي + إنجليزي مع بعض", "Auto = Arabic + English together"), value = langName) {
            prefs.voiceLang = when (prefs.voiceLang) { "auto" -> "ar"; "ar" -> "en"; else -> "auto" }; render()
        }
    }

    private fun memorySection() {
        val card = section(L.t("الذاكرة", "MEMORY"))
        switchRow(card, L.t("ذاكرة نور", "Nour's memory"), L.t("نور يفتكر حاجات عنك (اسمك، اهتماماتك…) على جهازك بس", "Nour remembers things about you (name, interests…), stored on your device only"), prefs.memoryEnabled) { prefs.memoryEnabled = it; render() }
        if (prefs.memoryEnabled) {
            val n = com.nour.assistant.tools.MemoryStore(this).all().size
            row(card, L.t("إدارة الذاكرة", "Manage memory"), L.t("شوف / عدّل / امسح اللي نور فاكره", "View / edit / delete what Nour remembers"), value = n.toString()) { MemoryDialog.show(this) { render() } }
        }
    }

    private fun appsSection() {
        val card = section(L.t("المهام", "TASKS"))
        row(card, L.t("التطبيقات المسموحة لنور", "Apps Nour may open"), L.t("نور يفتحها لو طلبت منه", "Nour opens them when you ask"), value = prefs.allowedApps.size.toString()) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun voiceSection() {
        val card = section(L.t("نداء «يا نور»", "WAKE WORD"))
        val ready = WakeModels.isReady(this)
        val sub = when {
            downloading -> L.t("بنزّل الموديلات… $downloadProgress%", "Downloading models… $downloadProgress%")
            prefs.wakeEnabled && ready -> getString(R.string.wake_on)
            else -> getString(R.string.wake_off)
        }
        switchRow(card, L.t("استمع لـ «يا نور» / Hey Nour", "Listen for “Ya Nour” / Hey Nour"), sub, prefs.wakeEnabled || downloading) { on ->
            if (downloading) return@switchRow
            if (!on) { prefs.wakeEnabled = false; WakeWordService.stop(this); render() }
            else if (!WakeController.hasMic(this)) micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else enableWake()
        }
        if (downloading) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = downloadProgress; progressTintList = ColorStateList.valueOf(pal.accent) }
            card.addView(pb, LinearLayout.LayoutParams(-1, dp(6f)).apply { setMargins(dp(16f), 0, dp(16f), dp(12f)) })
        }
        row(card, L.t("حساسية النداء", "Sensitivity"), L.t("عالية = يسمع من بعيد (ممكن يفتح بالغلط أكتر)", "High = hears from afar (more false triggers)"), value = lvl(prefs.wakeSens)) {
            prefs.wakeSens = (prefs.wakeSens + 1) % 3
            if (WakeWordService.running) { WakeWordService.stop(this); WakeWordService.start(this) }
            render()
        }
    }

    private fun gestureSection() {
        val card = section(L.t("إيماءات اليد", "HAND GESTURES"))
        val access = NourAccessibilityService.instance != null
        val sub = (if (prefs.gestureEnabled) getString(R.string.gesture_on) else getString(R.string.gesture_off)) +
            (if (prefs.gestureEnabled && !access) "\n⚠️ " + L.t("خدمة التحكم (إمكانية الوصول) لسه مش مفعّلة.", "The control service (Accessibility) is not enabled yet.") else "")
        switchRow(card, getString(R.string.gesture_switch), sub, prefs.gestureEnabled) { on ->
            if (!on) { prefs.gestureEnabled = false; GestureService.stop(this); render() }
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) camPermission.launch(Manifest.permission.CAMERA)
            else { prefs.gestureEnabled = true; GestureService.start(this); render() }
        }
        row(card, getString(R.string.gesture_access_button), null, value = if (access) "✓" else null) {
            try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        }
        row(card, getString(R.string.gesture_editor_button), L.t("إيماءاتك المخصصة", "Your custom gestures")) { startActivity(Intent(this, GestureEditorActivity::class.java)) }
        row(card, L.t("حساسية تمرير الأصابع", "Finger-scroll sensitivity"), null, value = lvl(prefs.gestureSens)) {
            prefs.gestureSens = (prefs.gestureSens + 1) % 3; GestureService.instance?.reload(); render()
        }
        switchRow(card, getString(R.string.gesture_reverse), null, prefs.reverseScroll) { prefs.reverseScroll = it; GestureService.instance?.reload() }
    }

    private fun systemSection() {
        val card = section(L.t("النظام", "SYSTEM"))
        row(card, getString(R.string.status_button), L.t("إيه شغال وإيه ناقص + إصلاح", "What's running, what's missing + fixes")) { startActivity(Intent(this, StatusActivity::class.java)) }
        row(card, getString(R.string.battery_button)) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
        row(card, getString(R.string.export_log), L.t("لو حصلت مشكلة صدّره وابعته", "If something goes wrong, export and send it")) {
            val i = AppLog.shareIntent(this)
            if (i != null) startActivity(i) else Toast.makeText(this, L.t("تعذّر إنشاء السجل", "Couldn't create the log"), Toast.LENGTH_SHORT).show()
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        row(card, L.t("عن Nour", "About Nour"), "v$ver") {}
    }

    // ------------------------------------------------------------------ تفاصيل

    private fun editSystemPrompt() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(4f), dp(18f), dp(16f)) }
        root.addView(TextView(this).apply { text = L.t("تعليمات النظام", "System prompt"); textSize = 19f; setTextColor(pal.text); setTypeface(typeface, Typeface.BOLD) })
        val et = EditText(this).apply {
            setText(prefs.system); setTextColor(pal.text); textSize = 14.5f; minLines = 6; maxLines = 12; gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = dp(16f).toFloat() }; setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        root.addView(et, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        val dlg = NourDialog.sheet(this, root)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(12f), 0, 0) }
        fun btn(t: String, fill: Boolean, click: () -> Unit) = TextView(this).apply {
            text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(if (fill) Color.WHITE else pal.text); gravity = Gravity.CENTER
            setPadding(dp(22f), dp(11f), dp(22f), dp(11f)); isClickable = true
            background = if (fill) NourTheme.accentFill(this@SettingsActivity, 22f, pal) else GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(22f).toFloat() }
            setOnClickListener { click() }
        }
        actions.addView(btn(L.t("افتراضي", "Reset"), false) { et.setText(Prefs.DEFAULT_SYSTEM) })
        actions.addView(btn(L.t("حفظ", "Save"), true) { prefs.system = et.text.toString(); dlg.dismiss(); Toast.makeText(this, L.t("اتحفظت ✓", "Saved ✓"), Toast.LENGTH_SHORT).show() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10f) })
        root.addView(actions)
        dlg.show()
    }

    /** تنزيل موديلات النداء (لو مش موجودة) وبعدين تشغيل الخدمة */
    private fun enableWake() {
        if (WakeModels.isReady(this)) { startWake(); return }
        if (downloading) return
        downloading = true; downloadProgress = 0; render()
        lifecycleScope.launch {
            val total = WakeModels.AR.approxMb + WakeModels.EN.approxMb
            val arShare = WakeModels.AR.approxMb.toFloat() / total
            val okAr = WakeModels.download(this@SettingsActivity, WakeModels.AR) { f ->
                runOnUiThread { val pct = (f * arShare * 100).toInt(); if (pct != downloadProgress) { downloadProgress = pct; render() } }
            }
            if (okAr) {
                // الإنجليزي اختياري: لو فشل النداء العربي لسه شغال
                WakeModels.download(this@SettingsActivity, WakeModels.EN) { f ->
                    runOnUiThread { val pct = ((arShare + f * (1 - arShare)) * 100).toInt(); if (pct != downloadProgress) { downloadProgress = pct; render() } }
                }
            }
            downloading = false
            if (okAr) startWake() else { prefs.wakeEnabled = false; Toast.makeText(this@SettingsActivity, R.string.wake_download_failed, Toast.LENGTH_LONG).show(); render() }
        }
    }

    private fun startWake() { prefs.wakeEnabled = true; WakeWordService.start(this); render() }
}
