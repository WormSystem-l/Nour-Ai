package com.nour.assistant.gesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * مؤشر اليد: قطرة زجاج سائلة (نفس لغة علامة Nour). بيتحرك بنعومة 60fps حتى لو الكاميرا أبطأ،
 * وبيصغر ويطلّع موجة لما تضغط، وبيظهر أسهم ↑↓ لما التمرير يتعرف، وفلاش لما تاخد لقطة شاشة.
 */
class GlassCursorView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var tx = -300f; private var ty = -300f
    private var cx = -300f; private var cy = -300f
    private var placed = false
    private var pinching = false
    private var scroll = 0
    private var scale = 1f
    private var ripple = -1f
    private var flash = 0f
    private var t = 0f
    private var px = -300f; private var py = -300f
    private var last = 0L
    private var attached = false

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val oval = RectF()

    fun target(x: Float, y: Float, pinch: Boolean, scrollMode: Int) {
        tx = x; ty = y; pinching = pinch; scroll = scrollMode
        if (!placed) { cx = x; cy = y; placed = true }
        start()
    }

    fun pulse() { ripple = 0f; start() }
    fun flashScreen() { flash = 1f; start() }

    private fun start() { if (attached) { removeCallbacks(loop); postOnAnimation(loop) } }

    private val loop = object : Runnable {
        override fun run() {
            if (!attached) return
            val now = SystemClock.uptimeMillis()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0.001f, 0.1f)
            last = now
            t += dt
            val a = 1f - exp(-dt / 0.045f)            // تنعيم زمني (مستقل عن معدل الإطارات)
            cx += (tx - cx) * a; cy += (ty - cy) * a
            val ts = if (pinching) 0.66f else 1f
            scale += (ts - scale) * (1f - exp(-dt / 0.06f))
            if (ripple >= 0f) { ripple += dt * 2.4f; if (ripple > 1f) ripple = -1f }
            if (flash > 0f) flash = (flash - dt * 3f).coerceAtLeast(0f)
            // نرسم المنطقة حوالين المؤشر بس (أخف على الجهاز من إعادة رسم الشاشة كلها)
            if (flash > 0f) invalidate() else {
                val m = (22f * d * 4f).toInt()
                val l = minOf(cx, px).toInt() - m; val tp = minOf(cy, py).toInt() - m
                val rr = maxOf(cx, px).toInt() + m; val bt = maxOf(cy, py).toInt() + m
                invalidate(l, tp, rr, bt)
            }
            px = cx; py = cy
            val moving = kotlin.math.abs(tx - cx) + kotlin.math.abs(ty - cy) > 0.5f
            // بنوقف الحلقة لما كل حاجة تهدى (توفير بطارية) وبترجع مع أول تحديث
            if (moving || ripple >= 0f || flash > 0f || kotlin.math.abs(ts - scale) > 0.01f || scroll != 0) postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; last = 0L; postOnAnimation(loop) }
    override fun onDetachedFromWindow() { attached = false; removeCallbacks(loop); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        if (flash > 0f) c.drawColor(Color.argb((flash * 110).toInt(), 255, 255, 255))
        if (!placed) return
        val r = 22f * d * scale

        // هالة ناعمة
        glow.shader = RadialGradient(cx, cy, r * 2.3f, intArrayOf(0x558B5CF6, 0x228B5CF6, 0x00000000), floatArrayOf(0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 2.3f, glow)

        // جسم الزجاج
        body.shader = RadialGradient(cx - r * 0.25f, cy - r * 0.3f, r * 1.35f,
            intArrayOf(0xB3FFFFFF.toInt(), 0x66C4B5FD, 0x886D5CF0.toInt(), 0xCC2A1F7A.toInt()),
            floatArrayOf(0f, 0.35f, 0.72f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, body)

        // سائل بيتحرك جوه
        val cols = intArrayOf(0xAAA78BFA.toInt(), 0xAA6366F1.toInt())
        for (i in 0..1) {
            val a = t * (0.9f + i * 0.5f) + i * 2.4f
            val bx = cx + cos(a) * r * 0.32f; val by = cy + sin(a * 1.3f) * r * 0.3f
            val br = r * (0.55f + 0.06f * sin(t * 2f + i))
            blob.shader = RadialGradient(bx, by, br, intArrayOf(cols[i], cols[i] and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawCircle(bx, by, br, blob)
        }

        // حافة زجاج
        rim.shader = RadialGradient(cx, cy, r, intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x55FFFFFF, 0xCCFFFFFF.toInt()),
            floatArrayOf(0f, 0.72f, 0.92f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, rim)

        // لمعة
        c.save(); c.rotate(-30f, cx, cy)
        oval.set(cx - r * 0.55f, cy - r * 0.9f, cx + r * 0.2f, cy - r * 0.45f)
        spec.shader = LinearGradient(oval.left, oval.top, oval.right, oval.bottom, 0xE6FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(oval, spec); c.restore()

        // موجة الضغط
        if (ripple >= 0f) {
            line.strokeWidth = 2.2f * d
            line.color = Color.argb(((1f - ripple) * 200).toInt(), 196, 181, 253)
            c.drawCircle(cx, cy, r * (1f + ripple * 1.7f), line)
        }

        // أسهم التمرير
        if (scroll != 0) {
            line.strokeWidth = 2.6f * d; line.color = 0xF2FFFFFF.toInt()
            val dir = if (scroll > 0) 1f else -1f
            val bob = (sin(t * 7f) * 1.5f) * d
            for (k in 0..1) {
                val oy = cy + (k - 0.5f) * 7f * d * dir + bob * dir - 0.5f * d * dir
                path.reset()
                path.moveTo(cx - 6f * d, oy - 3f * d * dir)
                path.lineTo(cx, oy + 3f * d * dir)
                path.lineTo(cx + 6f * d, oy - 3f * d * dir)
                line.alpha = if (k == 0) 140 else 255
                c.drawPath(path, line)
            }
        }
    }
}
