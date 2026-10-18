package com.nour.assistant.ui

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
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * علامة Nour: "قطرة نور سائلة" — كرة زجاج حية جواها نور بنفسجي ونيلي بيتحرك.
 * مرسومة بالكود بالكامل (Custom View):
 *  - IDLE: بتتنفس ببطء
 *  - LISTENING: حافتها بتتموج مع صوت المستخدم ([level] من 0 لـ 1)
 *  - THINKING: النور جواها بيلف بسرعة وحلقة صغيرة بتدور حواليها
 */
class NourOrbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING }

    var mode: Mode = Mode.IDLE
    /** شدة الصوت 0..1 (بتتنعم تلقائياً) */
    var level: Float = 0f
    /** false = رسمة ثابتة (لو عايزها من غير حركة) */
    var animated: Boolean = true
        set(v) { field = v; if (v) startLoop() else removeCallbacks(loop); invalidate() }

    private val d = resources.displayMetrics.density
    private var t = 0f
    private var lv = 0f
    private var spin = 0f
    private var last = 0L
    private var attached = false

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val oval = RectF()

    private val blobColors = intArrayOf(0xFFA78BFA.toInt(), 0xFF6366F1.toInt(), 0xFF8B5CF6.toInt(), 0xFF4F46E5.toInt())
    private val phases = floatArrayOf(0f, 1.9f, 3.6f, 5.1f)
    private val orbitX = floatArrayOf(0.46f, 0.38f, 0.52f, 0.30f)
    private val orbitY = floatArrayOf(0.38f, 0.50f, 0.34f, 0.46f)

    private val loop = object : Runnable {
        override fun run() {
            if (!attached || !animated) return
            val now = SystemClock.uptimeMillis()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0f, 0.1f)
            last = now
            t += dt
            lv += (level.coerceIn(0f, 1f) - lv) * min(1f, dt * 10f)
            val speed = when (mode) { Mode.IDLE -> 0.55f; Mode.LISTENING -> 0.9f + lv * 1.2f; Mode.THINKING -> 2.8f }
            spin += dt * speed
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun startLoop() { if (attached && animated) { last = 0L; removeCallbacks(loop); postOnAnimation(loop) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; startLoop() }
    override fun onDetachedFromWindow() { attached = false; removeCallbacks(loop); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        val def = (44 * d).toInt()
        setMeasuredDimension(resolveSize(def, w), resolveSize(def, h))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val full = min(width, height) / 2f
        if (full <= 0f) return

        val breathe = when (mode) {
            Mode.IDLE -> 1f + 0.035f * sin(t * 2f * PI.toFloat() / 3.4f)
            Mode.LISTENING -> 1f + 0.02f * sin(t * 2f * PI.toFloat() / 1.6f) + 0.07f * lv
            Mode.THINKING -> 1f + 0.025f * sin(t * 2f * PI.toFloat() / 1.1f)
        }
        val r = full * 0.80f * breathe

        // 1) هالة النور حوالين الكرة
        glow.shader = RadialGradient(cx, cy, full, intArrayOf(0x668B5CF6, 0x226366F1, 0x00000000),
            floatArrayOf(r / full * 0.82f, r / full * 1.05f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, full, glow)

        // 2) شكل الجسم: دايرة، وبتتموج مع الصوت
        buildBody(cx, cy, r)

        c.save()
        c.clipPath(path)

        // أساس الزجاج (نيلي غامق -> بنفسجي)
        body.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.25f, r * 1.35f,
            intArrayOf(0xFF7B6CF6.toInt(), 0xFF4A2FC6.toInt(), 0xFF231A78.toInt(), 0xFF0E0B3A.toInt()),
            floatArrayOf(0f, 0.38f, 0.74f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.05f, body)

        // النور المتحرك جوه (بيلف لما يفكر)
        c.save()
        if (mode == Mode.THINKING) c.rotate((spin * 55f) % 360f, cx, cy)
        for (i in blobColors.indices) {
            val a = spin * (1f + i * 0.23f) + phases[i]
            val bx = cx + cos(a) * r * orbitX[i]
            val by = cy + sin(a * 1.3f) * r * orbitY[i]
            val br = r * (0.52f + 0.09f * sin(t * 1.7f + phases[i]) + 0.12f * lv)
            blob.shader = RadialGradient(bx, by, br, intArrayOf(withAlpha(blobColors[i], 0xCC), withAlpha(blobColors[i], 0x00)),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(bx, by, br, blob)
        }
        c.restore()

        // قلب مضيء
        val cr = r * (0.46f + 0.10f * lv)
        core.shader = RadialGradient(cx - r * 0.06f, cy + r * 0.04f, cr,
            intArrayOf(0x66FFFFFF, 0x22C4B5FD, 0x00C4B5FD), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, cr, core)
        c.restore()

        // 3) حافة الزجاج (أفتح عند الطرف)
        rim.shader = RadialGradient(cx, cy, r, intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x44FFFFFF, 0x99FFFFFF.toInt()),
            floatArrayOf(0f, 0.70f, 0.92f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, rim)

        // انعكاس لامع فوق-شمال
        c.save()
        c.rotate(-32f, cx, cy)
        oval.set(cx - r * 0.55f, cy - r * 0.86f, cx + r * 0.15f, cy - r * 0.44f)
        spec.shader = LinearGradient(oval.left, oval.top, oval.right, oval.bottom, 0xD0FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(oval, spec)
        c.restore()
        // لمعة صغيرة تحت-يمين
        oval.set(cx + r * 0.30f, cy + r * 0.52f, cx + r * 0.62f, cy + r * 0.68f)
        spec.shader = LinearGradient(oval.left, oval.top, oval.right, oval.bottom, 0x00FFFFFF, 0x66C4B5FD, Shader.TileMode.CLAMP)
        c.drawOval(oval, spec)

        // 4) حلقة بتدور وقت التفكير
        if (mode == Mode.THINKING) {
            ring.strokeWidth = 2f * d
            ring.color = 0xCCC4B5FD.toInt()
            val rr = r * 1.12f
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(oval, (t * 260f) % 360f, 70f, false, ring)
            ring.color = 0x66A78BFA
            c.drawArc(oval, (t * 260f + 180f) % 360f, 40f, false, ring)
        }
    }

    private fun buildBody(cx: Float, cy: Float, r: Float) {
        path.reset()
        val amp = if (mode == Mode.LISTENING) 0.045f * lv else 0.0f
        if (amp < 0.002f) { path.addCircle(cx, cy, r, Path.Direction.CW); return }
        val n = 72
        for (i in 0..n) {
            val th = (i.toFloat() / n) * 2f * PI.toFloat()
            val k = 1f + amp * sin(3f * th + t * 5.5f) + amp * 0.6f * sin(5f * th - t * 4.1f)
            val x = cx + cos(th) * r * k
            val y = cy + sin(th) * r * k
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
