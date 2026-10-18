package com.nour.assistant.gesture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nour.assistant.L
import com.nour.assistant.Prefs
import java.util.UUID

/**
 * إيماءاتي: إيماءات افتراضية (قرص = ضغط، إبهام = تمرير) + إيماءاتك المخصصة:
 * بتختار الفعل، وبتثبّت وضعية يدك قدام الكاميرا الأمامية ثواني، ونور بيحفظ بصمتها (على الجهاز بس).
 */
class GestureEditorActivity : AppCompatActivity() {
    private lateinit var listBox: LinearLayout
    private var pendingAction: GestureAction? = null
    private var dialog: AlertDialog? = null
    private var startedHere = false

    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingAction?.let { record(it) } else Toast.makeText(this, L.t("محتاج إذن الكاميرا", "Camera permission is needed"), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); fitsSystemWindows = true }
        root.addView(TextView(this).apply {
            text = L.t("الإيماءات الافتراضية", "Default gestures"); textSize = 18f; setPadding(0, 0, 0, dp(4)); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = L.t("• قرص الإبهام مع السبابة = ضغط (المؤشر بيتثبت أول ما الإبهام يقرّب)\n• الأصابع كلها تنزل تحت مع بعض = تمرير لتحت\n• الأصابع كلها تطلع فوق مع بعض = تمرير لفوق\n• علامة النصر ✌ (سبابة + وسطى) ثابتة نص ثانية = لقطة شاشة",
                "• Pinch thumb + index = tap (the cursor freezes as the thumb approaches)\n• All fingers bend down together = scroll down\n• All fingers go up together = scroll up\n• Hold the victory sign ✌ (index + middle) = screenshot")
            textSize = 14f; alpha = 0.85f
        })
        root.addView(TextView(this).apply {
            text = L.t("إيماءاتي", "My gestures"); textSize = 18f; setPadding(0, dp(18), 0, dp(4)); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val add = Button(this).apply { text = L.t("＋ إضافة إيماءة جديدة", "＋ Add a new gesture") }
        add.setOnClickListener { chooseAction() }
        root.addView(add)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val d = resources.displayMetrics.density
        val items = GestureStore.load(this)
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply { text = L.t("لسه مفيش إيماءات مخصصة.", "No custom gestures yet."); alpha = 0.7f; setPadding(0, (12 * d).toInt(), 0, 0) })
            return
        }
        items.forEach { g ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt()) }
            row.addView(TextView(this).apply {
                text = "✋ " + (GestureAction.fromKey(g.action)?.label ?: g.action); textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(Button(this).apply {
                text = L.t("حذف", "Delete")
                setOnClickListener { GestureStore.remove(this@GestureEditorActivity, g.id); GestureService.instance?.reload(); refresh() }
            })
            listBox.addView(row)
        }
    }

    private fun chooseAction() {
        val acts = GestureAction.values()
        AlertDialog.Builder(this)
            .setTitle(L.t("اختار الفعل اللي الإيماءة هتنفذه", "Choose what the gesture does"))
            .setItems(acts.map { it.label }.toTypedArray()) { _, i -> startRecording(acts[i]) }
            .show()
    }

    private fun startRecording(a: GestureAction) {
        pendingAction = a
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            camPermission.launch(Manifest.permission.CAMERA); return
        }
        record(a)
    }

    private fun record(a: GestureAction) {
        if (!GestureService.running) { startedHere = true; GestureService.start(this) }
        val d = resources.displayMetrics.density
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        val msg = TextView(this).apply {
            text = L.t("ثبّت وضعية يدك قدام الكاميرا الأمامية (إضاءة كويسة وكف واضح) لحد ما الشريط يكمل…",
                "Hold your hand pose in front of the front camera until the bar fills…")
            setPadding(0, 0, 0, (12 * d).toInt())
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), 0); addView(msg); addView(bar) }
        dialog = AlertDialog.Builder(this).setTitle(a.label).setView(box)
            .setNegativeButton(L.t("إلغاء", "Cancel")) { _, _ -> GestureRecorder.cancel(); finishRecording() }
            .setCancelable(false).show()
        // نديها ثانية تشغّل الكاميرا قبل ما نجمع عينات
        bar.postDelayed({
            GestureRecorder.start(30, onProgress = { n, t -> bar.progress = n * 100 / t }, onDone = { f ->
                dialog?.dismiss(); dialog = null
                if (f == null) {
                    Toast.makeText(this, L.t("الوضعية مش ثابتة أو اليد مش واضحة. جرّب تاني.", "The pose wasn't steady or the hand wasn't clear. Try again."), Toast.LENGTH_LONG).show()
                } else {
                    GestureStore.add(this, SavedGesture(UUID.randomUUID().toString(), a.label, a.key, f))
                    GestureService.instance?.reload()
                    Toast.makeText(this, L.t("اتحفظت ✓", "Saved ✓"), Toast.LENGTH_SHORT).show()
                    refresh()
                }
                finishRecording()
            })
        }, 1500)
    }

    private fun finishRecording() {
        // لو إحنا اللي شغلنا الخدمة مؤقتاً ومفعّلة مش من الإعدادات، نقفلها
        if (startedHere && !Prefs(this).gestureEnabled) GestureService.stop(this)
        startedHere = false
    }

    override fun onDestroy() {
        GestureRecorder.cancel(); dialog?.dismiss(); finishRecording()
        super.onDestroy()
    }
}
