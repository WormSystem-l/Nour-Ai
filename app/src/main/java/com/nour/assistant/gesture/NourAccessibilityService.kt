package com.nour.assistant.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.os.SystemClock
import com.nour.assistant.AppLog
import com.nour.assistant.wake.WakeController

/**
 * بينفذ الإيماءات على الشاشة (ضغط/تمرير/رجوع...) ويعرض مؤشر اليد.
 * لازم المستخدم يفعّله من إعدادات "إمكانية الوصول". مبنقراش محتوى الشاشة خالص.
 */
class NourAccessibilityService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var cursor: GlassCursorView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private var lastWatchdog = 0L

    /**
     * حارس: النظام بيحافظ على خدمة إمكانية الوصول أكتر من أي خدمة تانية، فبنستغل أحداث النوافذ
     * (بتيجي طول ما بتستخدم الموبايل) عشان نرجّع خدمة النداء/الإيماءات لو النظام قتلها.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWatchdog < 20_000) return
        lastWatchdog = now
        try {
            GestureService.ensure(this)
            WakeController.ensureService(this)
        } catch (e: Throwable) { AppLog.eOnce("watchdog", "ensure failed", e) }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideCursor(); instance = null
        return super.onUnbind(intent)
    }

    fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 60))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long = 260) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(GestureDescription.StrokeDescription(p, 0, durMs))
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        try { dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null) } catch (_: Exception) {}
    }

    fun global(action: Int) { try { performGlobalAction(action) } catch (_: Exception) {} }

    // ------------------------------------------------------------------ المؤشر

    fun showCursor(x: Float, y: Float, pinching: Boolean, scroll: Int = 0) {
        val v = cursor ?: addCursor() ?: return
        v.target(x, y, pinching, scroll)
    }

    fun pulse() { cursor?.pulse() }
    fun flashScreen() { cursor?.flashScreen() }

    fun hideCursor() {
        val v = cursor ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        cursor = null
    }

    private fun addCursor(): GlassCursorView? = try {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = GlassCursorView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        w.addView(v, lp)
        wm = w; cursor = v; v
    } catch (e: Exception) { AppLog.eOnce("a11y", "addCursor failed", e); null }

    companion object {
        @Volatile var instance: NourAccessibilityService? = null
    }
}
