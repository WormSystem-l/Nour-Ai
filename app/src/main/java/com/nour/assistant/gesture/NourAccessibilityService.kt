package com.nour.assistant.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * بينفذ الإيماءات على الشاشة (ضغط/تمرير/رجوع...) ويعرض مؤشر اليد.
 * لازم المستخدم يفعّله من إعدادات "إمكانية الوصول". مبنقراش محتوى الشاشة خالص.
 */
class NourAccessibilityService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var cursor: CursorView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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

    fun showCursor(x: Float, y: Float, pinching: Boolean) {
        val v = cursor ?: addCursor() ?: return
        v.cursorX = x; v.cursorY = y; v.pinching = pinching
        v.invalidate()
    }

    fun hideCursor() {
        val v = cursor ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        cursor = null
    }

    private fun addCursor(): CursorView? = try {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = CursorView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        w.addView(v, lp)
        wm = w; cursor = v; v
    } catch (_: Exception) { null }

    private class CursorView(ctx: Context) : View(ctx) {
        var cursorX = -100f; var cursorY = -100f; var pinching = false
        private val d = resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = (if (pinching) 11f else 20f) * d
            ring.color = if (pinching) 0xFFF0ABFC.toInt() else 0xCCC4B5FD.toInt()
            dot.color = if (pinching) 0xFFF0ABFC.toInt() else 0xFF8B5CF6.toInt()
            c.drawCircle(cursorX, cursorY, r, ring)
            c.drawCircle(cursorX, cursorY, 4f * d, dot)
        }
    }

    companion object {
        @Volatile var instance: NourAccessibilityService? = null
    }
}
