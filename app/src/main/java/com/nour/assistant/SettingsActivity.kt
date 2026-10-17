package com.nour.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.nour.assistant.gesture.GestureService
import com.nour.assistant.gesture.NourAccessibilityService
import com.nour.assistant.gesture.GestureEditorActivity
import com.nour.assistant.wake.WakeController
import com.nour.assistant.wake.WakeModels
import com.nour.assistant.wake.WakeWordService
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var wakeSwitch: SwitchCompat
    private lateinit var wakeStatus: TextView
    private lateinit var wakeProgress: ProgressBar
    private lateinit var btnApps: MaterialButton
    private var downloading = false
    private lateinit var gestureSwitch: SwitchCompat
    private lateinit var gestureStatus: TextView

    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { prefs.gestureEnabled = true; GestureService.start(this) }
        else { prefs.gestureEnabled = false; gestureSwitch.isChecked = false; Toast.makeText(this, L.t("محتاج إذن الكاميرا", "Camera permission is needed"), Toast.LENGTH_LONG).show() }
        refreshGesture()
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enableWake() else { setWake(false); Toast.makeText(this, R.string.mic_permission, Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val p = Prefs(this); prefs = p
        val base = findViewById<EditText>(R.id.baseUrl).apply { setText(p.baseUrl) }
        val model = findViewById<EditText>(R.id.model).apply { setText(p.model) }
        val key = findViewById<EditText>(R.id.apiKey).apply { setText(p.apiKey) }
        val sys = findViewById<EditText>(R.id.system).apply { setText(p.system) }
        wakeSwitch = findViewById(R.id.wakeSwitch)
        wakeStatus = findViewById(R.id.wakeStatus)
        wakeProgress = findViewById(R.id.wakeProgress)
        btnApps = findViewById(R.id.btnApps)

        val btnLang = findViewById<MaterialButton>(R.id.btnVoiceLang)
        fun langName(m: String) = getString(when (m) { "ar" -> R.string.voice_lang_ar; "en" -> R.string.voice_lang_en; else -> R.string.voice_lang_auto })
        btnLang.text = getString(R.string.voice_lang_button, langName(p.voiceLang))
        btnLang.setOnClickListener {
            p.voiceLang = when (p.voiceLang) { "auto" -> "ar"; "ar" -> "en"; else -> "auto" }
            btnLang.text = getString(R.string.voice_lang_button, langName(p.voiceLang))
        }
        findViewById<View>(R.id.btnBattery).setOnClickListener {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }

        gestureSwitch = findViewById(R.id.gestureSwitch)
        gestureStatus = findViewById(R.id.gestureStatus)
        gestureSwitch.isChecked = p.gestureEnabled
        gestureSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) { p.gestureEnabled = false; GestureService.stop(this) }
            else if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) camPermission.launch(Manifest.permission.CAMERA)
            else { p.gestureEnabled = true; GestureService.start(this) }
            refreshGesture()
        }
        findViewById<SwitchCompat>(R.id.reverseSwitch).apply {
            isChecked = p.reverseScroll
            setOnCheckedChangeListener { _, v -> p.reverseScroll = v; GestureService.instance?.reload() }
        }
        findViewById<View>(R.id.btnAccess).setOnClickListener {
            try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        }
        findViewById<View>(R.id.btnGestures).setOnClickListener { startActivity(Intent(this, GestureEditorActivity::class.java)) }

        btnApps.setOnClickListener { startActivity(Intent(this, AppPickerActivity::class.java)) }

        wakeSwitch.isChecked = p.wakeEnabled
        wakeSwitch.setOnCheckedChangeListener { _, on ->
            if (downloading) return@setOnCheckedChangeListener
            if (!on) { p.wakeEnabled = false; WakeWordService.stop(this); refreshWake() }
            else if (!WakeController.hasMic(this)) micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else enableWake()
        }

        findViewById<View>(R.id.save).setOnClickListener {
            val url = base.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                base.error = getString(R.string.bad_url); return@setOnClickListener
            }
            if (model.text.isBlank()) { model.error = getString(R.string.need_model); return@setOnClickListener }
            p.baseUrl = url
            p.model = model.text.toString()
            p.apiKey = key.text.toString()
            p.system = sys.text.toString()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        btnApps.text = getString(R.string.apps_button, prefs.allowedApps.size)
        refreshWake()
        refreshGesture()
    }

    private fun refreshGesture() {
        val access = NourAccessibilityService.instance != null
        gestureStatus.text = (if (prefs.gestureEnabled) getString(R.string.gesture_on) else getString(R.string.gesture_off)) +
            (if (prefs.gestureEnabled && !access) "\n⚠️ " + L.t("خدمة التحكم (إمكانية الوصول) لسه مش مفعّلة.", "The control service (Accessibility) is not enabled yet.") else "")
    }

    private fun setWake(on: Boolean) {
        wakeSwitch.setOnCheckedChangeListener(null)
        wakeSwitch.isChecked = on
        wakeSwitch.setOnCheckedChangeListener { _, v ->
            if (downloading) return@setOnCheckedChangeListener
            if (!v) { prefs.wakeEnabled = false; WakeWordService.stop(this); refreshWake() }
            else if (!WakeController.hasMic(this)) micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else enableWake()
        }
    }

    /** تنزيل موديلات النداء (لو مش موجودة) وبعدين تشغيل الخدمة */
    private fun enableWake() {
        if (WakeModels.isReady(this)) { startWake(); return }
        if (downloading) return
        downloading = true
        wakeProgress.visibility = View.VISIBLE
        wakeProgress.progress = 0
        wakeStatus.text = getString(R.string.wake_downloading, WakeModels.AR.approxMb + WakeModels.EN.approxMb)
        lifecycleScope.launch {
            val total = WakeModels.AR.approxMb + WakeModels.EN.approxMb
            val arShare = WakeModels.AR.approxMb.toFloat() / total
            val okAr = WakeModels.download(this@SettingsActivity, WakeModels.AR) { f ->
                runOnUiThread { wakeProgress.progress = (f * arShare * 100).toInt() }
            }
            if (okAr) {
                // الإنجليزي اختياري: لو فشل النداء بالعربي والإنجليزي المنطوق بالعربي شغال برضو
                WakeModels.download(this@SettingsActivity, WakeModels.EN) { f ->
                    runOnUiThread { wakeProgress.progress = ((arShare + f * (1 - arShare)) * 100).toInt() }
                }
            }
            downloading = false
            wakeProgress.visibility = View.GONE
            if (okAr) startWake() else {
                setWake(false); prefs.wakeEnabled = false
                wakeStatus.text = getString(R.string.wake_download_failed)
            }
        }
    }

    private fun startWake() {
        prefs.wakeEnabled = true
        WakeWordService.start(this)
        setWake(true)
        refreshWake()
    }

    private fun refreshWake() {
        if (downloading) return
        wakeStatus.text = when {
            prefs.wakeEnabled && WakeModels.isReady(this) -> getString(R.string.wake_on)
            else -> getString(R.string.wake_off)
        }
    }
}
