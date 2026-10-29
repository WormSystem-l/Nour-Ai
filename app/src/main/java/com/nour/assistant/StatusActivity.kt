package com.nour.assistant

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nour.assistant.gesture.GestureService
import com.nour.assistant.gesture.NourAccessibilityService
import com.nour.assistant.wake.WakeController
import com.nour.assistant.wake.WakeModels
import com.nour.assistant.wake.WakeWordService

/**
 * حالة التطبيق: كل ميزة شغالة ولا لأ ولماذا، وزرار إصلاح لكل واحدة.
 * أندرويد (وخصوصاً هواوي/شاومي/سامسونج) ممكن يقتل الخدمات الخلفية، والشاشة دي بتقولك إيه اللي ناقص.
 */
class StatusActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt()); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = L.t("حالة التطبيق", "App status"); textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(box); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })
        val fix = Button(this).apply { text = L.t("🔧 إصلاح تلقائي (إعادة تشغيل الخدمات)", "🔧 Auto-fix (restart services)") }
        fix.setOnClickListener { WakeController.ensureService(this); GestureService.ensure(this); box.postDelayed({ render() }, 800) }
        root.addView(fix)
        setContentView(root)
        com.nour.assistant.ui.NourTheme.applyScreen(this, root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun row(ok: Boolean?, title: String, detail: String, action: String? = null, onClick: (() -> Unit)? = null) {
        val d = resources.displayMetrics.density
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt()) }
        val icon = when (ok) { true -> "✅"; false -> "⚠️"; null -> "➖" }
        r.addView(TextView(this).apply { text = "$icon  $title"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        r.addView(TextView(this).apply { text = detail; textSize = 13f; alpha = 0.8f })
        if (action != null && onClick != null) r.addView(Button(this).apply { text = action; setOnClickListener { onClick() }; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START } })
        box.addView(r)
        box.addView(View(this).apply { setBackgroundColor(0x22888888); layoutParams = LinearLayout.LayoutParams(-1, 1) })
    }

    private fun open(action: String, pkgUri: Boolean = false) {
        try { startActivity(Intent(action).apply { if (pkgUri) data = Uri.parse("package:$packageName") }) }
        catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
    }

    private fun render() {
        box.removeAllViews()
        val p = Prefs(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager

        // نداء "يا نور"
        val wakeOn = p.wakeEnabled
        row(if (!wakeOn) null else WakeWordService.running,
            L.t("نداء «يا نور»", "Wake word"),
            when {
                !wakeOn -> L.t("مقفول من الإعدادات.", "Off in settings.")
                !WakeModels.isReady(this) -> L.t("موديلات النداء لسه ما اتنزلتش.", "Wake models are not downloaded yet.")
                !WakeController.hasMic(this) -> L.t("محتاج إذن المايك.", "Needs microphone permission.")
                WakeWordService.running -> L.t("شغال (بيسمع والشاشة مفتوحة).", "Running (listens while the screen is on).")
                else -> L.t("واقف — النظام ممكن يكون قتله. افتح التطبيق أو اضغط إصلاح.", "Stopped — the system may have killed it. Open the app or tap fix.")
            }, if (wakeOn) L.t("تشغيل", "Start") else null) { WakeController.ensureService(this); render() }

        // الإيماءات
        val gOn = p.gestureEnabled
        row(if (!gOn) null else GestureService.running, L.t("إيماءات اليد", "Hand gestures"),
            when {
                !gOn -> L.t("مقفولة من الإعدادات.", "Off in settings.")
                GestureService.running -> L.t("شغالة (الكاميرا الأمامية).", "Running (front camera).")
                else -> L.t("واقفة — افتح التطبيق أو اضغط تشغيل.", "Stopped — open the app or tap start.")
            }, if (gOn) L.t("تشغيل", "Start") else null) { GestureService.ensure(this); render() }

        // إمكانية الوصول
        val acc = NourAccessibilityService.instance != null
        row(acc, L.t("خدمة التحكم (إمكانية الوصول)", "Control service (Accessibility)"),
            if (acc) L.t("مفعّلة. بتنفّذ الضغط والتمرير وبتحرس الخدمات.", "Enabled. Performs taps/scrolls and keeps services alive.")
            else L.t("مش مفعّلة: الإيماءات مش هتقدر تضغط أو تمرر.", "Not enabled: gestures can't tap or scroll."),
            L.t("فتح الإعدادات", "Open settings")) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }

        // المساعد الافتراضي
        val assistant = try { Settings.Secure.getString(contentResolver, "assistant").orEmpty() } catch (_: Exception) { "" }
        val isDefault = assistant.contains(packageName)
        row(isDefault, L.t("المساعد الافتراضي", "Default assistant"),
            if (isDefault) L.t("Nour هو المساعد (ضغطة طويلة على الرئيسية). لو عايز «اسأل عن الصفحة» فعّل «استخدام النص من الشاشة» من نفس الصفحة.",
                "Nour is the assistant (long-press Home). For “Ask about page”, enable “Use text from screen” on that settings page.")
            else L.t("اختار Nour كمساعد عشان النافذة الزجاجية والنداء يشتغلوا.", "Choose Nour as the assistant so the glass window and wake word work."),
            L.t("فتح الإعدادات", "Open settings")) { open(Settings.ACTION_VOICE_INPUT_SETTINGS) }

        // البطارية
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        row(ignoring, L.t("استثناء البطارية", "Battery optimization"),
            if (ignoring) L.t("Nour مستثنى من توفير الطاقة (الخدمات بتعيش أطول).", "Nour is exempt from battery optimization (services live longer).")
            else L.t("النظام ممكن يقتل الخدمات الخلفية. اختار «لا تقيّد/السماح».", "The system may kill background services. Choose “Don't optimize/Allow”."),
            L.t("فتح الإعدادات", "Open settings")) { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }

        // الظهور فوق التطبيقات
        val overlay = Settings.canDrawOverlays(this)
        row(overlay, L.t("الظهور فوق التطبيقات (اختياري)", "Display over other apps (optional)"),
            L.t("بيساعد النظام يسمح بإعادة تشغيل الخدمات من الخلفية. مش لازم.", "Helps the system allow restarting services from the background. Optional."),
            L.t("فتح الإعدادات", "Open settings")) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }

        // التشغيل التلقائي حسب الشركة
        val oem = oemIntent()
        row(null, L.t("التشغيل التلقائي / التشغيل في الخلفية (${Build.MANUFACTURER})", "Auto-start / background run (${Build.MANUFACTURER})"),
            L.t("هواوي وشاومي وأوبو وفيفو وسامسونج عندهم قايمة لازم تضيف فيها Nour عشان يفضل شغال.",
                "Huawei, Xiaomi, Oppo, Vivo and Samsung have a list where Nour must be allowed to keep running."),
            L.t("فتح إعدادات الشركة", "Open vendor settings")) {
            try { startActivity(oem ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
        }

        // الشبكة والتصدير
        row(null, L.t("سجل الأخطاء", "Error log"), L.t("لو حصلت مشكلة صدّر السجل وابعته.", "If something goes wrong, export the log and send it."),
            L.t("تصدير", "Export")) { AppLog.shareIntent(this)?.let { startActivity(it) } }
    }

    private fun oemIntent(): Intent? {
        val m = Build.MANUFACTURER.lowercase()
        val c: ComponentName? = when {
            m.contains("huawei") || m.contains("honor") -> ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            m.contains("oppo") || m.contains("realme") -> ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            m.contains("vivo") -> ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            m.contains("samsung") -> ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            else -> null
        }
        return c?.let { Intent().setComponent(it) }
    }

}
