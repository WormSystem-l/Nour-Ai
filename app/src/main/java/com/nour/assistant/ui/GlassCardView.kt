package com.nour.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin

/**
 * كارت "Liquid Glass" مرسوم بالكامل بالكود — من غير Blur حقيقي، فبيشتغل على كل الأجهزة.
 * الطبقات (من تحت لفوق):
 *  1) توهج ناعم ملون حوالين الكارت
 *  2) زجاج غامق شبه شفاف بتدرج رأسي
 *  3) بقع ألوان "Aurora" بتتحرك ببطء جوه الزجاج (إحساس السائل)
 *  4) لمعة علوية (Specular highlight) + لمعة قطرية خفيفة
 *  5) حافة داخلية رفيعة فاتحة من فوق وباهتة من تحت
 *  6) إطار متدرج غامق بيلف حوالين الكارت
 */
class GlassCardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING }

    private val d = resources.displayMetrics.density
    val glowPad = 14f * d
    var cornerRadius = 30f * d
        set(v) { field = v; invalidate() }

    private var colors = intArrayOf(
        0xFF6D28D9.toInt(), // بنفسجي غامق
        0xFF4338CA.toInt(), // نيلي
        0xFF1D4ED8.toInt(), // أزرق ملكي
        0xFF86198F.toInt(), // فوشيا غامق
        0xFF6D28D9.toInt()
    )

    private val rect = RectF()
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val diag = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val blobMatrix = Matrix()
    private val diagMatrix = Matrix()
    private var blobColors = intArrayOf(0x336D28D9, 0x2B1D4ED8, 0x244338CA)
    private var blobShaders = makeBlobs(blobColors)
    private var blobAlpha = blobColors.map { Color.alpha(it) }
    private var fillColors = intArrayOf(0xE61A1B2A.toInt(), 0xEB111220.toInt(), 0xF209090F.toInt())

    private fun makeBlobs(cs: IntArray) = cs.map { RadialGradient(0f, 0f, 1f, (it and 0x00FFFFFF) or 0xFF000000.toInt(), it and 0x00FFFFFF, Shader.TileMode.CLAMP) }

    /** الثيم الأسود: زجاج أسود تماماً بحواف فضية. الثيم الملوّن: بنفسجي/نيلي (الافتراضي القديم) */
    fun applyBlack(black: Boolean) {
        if (black) {
            colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF9A9AA2.toInt(), 0xFF3A3A40.toInt(), 0xFFD6D6DC.toInt(), 0xFFFFFFFF.toInt())
            blobColors = intArrayOf(0x16FFFFFF, 0x10C7C7D1, 0x0CFFFFFF)
            fillColors = intArrayOf(0xF4000000.toInt(), 0xF7000000.toInt(), 0xFB000000.toInt())
        } else {
            colors = intArrayOf(0xFF6D28D9.toInt(), 0xFF4338CA.toInt(), 0xFF1D4ED8.toInt(), 0xFF86198F.toInt(), 0xFF6D28D9.toInt())
            blobColors = intArrayOf(0x336D28D9, 0x2B1D4ED8, 0x244338CA)
            fillColors = intArrayOf(0xE61A1B2A.toInt(), 0xEB111220.toInt(), 0xF209090F.toInt())
        }
        blobShaders = makeBlobs(blobColors); blobAlpha = blobColors.map { Color.alpha(it) }
        if (width > 0) { sweep = SweepGradient(width / 2f, height / 2f, colors, null); rebuildFill() }
        invalidate()
    }

    private fun rebuildFill() {
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, fillColors, null, Shader.TileMode.CLAMP)
    }
    private var sweep: SweepGradient? = null

    private var angle = 0f
    private var phase = 0f
    private var intensity = 0.55f       // قوة الإطار والتوهج
    private var targetIntensity = 0.55f
    private var speed = 1f
    var mode = Mode.IDLE
        set(v) {
            field = v
            targetIntensity = when (v) { Mode.IDLE -> 0.55f; Mode.LISTENING -> 1f; Mode.THINKING -> 0.85f }
            speed = when (v) { Mode.IDLE -> 1f; Mode.LISTENING -> 2.6f; Mode.THINKING -> 3.4f }
        }
    /** مستوى الصوت 0..1 عشان التوهج ينبض مع الكلام */
    var level = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            angle = (angle + dt * 40f * speed) % 360f
            phase += dt * 0.35f * speed
            intensity += (targetIntensity + level * 0.35f - intensity) * (dt * 6f).coerceAtMost(1f)
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val p = glowPad.toInt()
        setPadding(p + paddingLeft, p + paddingTop, p + paddingRight, p + paddingBottom)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!animator.isStarted) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rect.set(glowPad, glowPad, w - glowPad, h - glowPad)
        sweep = SweepGradient(w / 2f, h / 2f, colors, null)
        rebuildFill()
        inner.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x4DFFFFFF, 0x10FFFFFF, 0x08FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        diag.shader = LinearGradient(rect.left, rect.top, rect.left + 140f * d, rect.top + 220f * d,
            intArrayOf(0x00FFFFFF, 0x0CFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, rect.top, 0f, rect.top + 70f * d,
            intArrayOf(0x1AFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val r = cornerRadius
        val sw = sweep ?: return
        matrix.setRotate(angle, width / 2f, height / 2f)
        sw.setLocalMatrix(matrix)

        // 1) توهج: كذا خط بشفافية بتقل بدل BlurMaskFilter (أسرع وشغال في كل الأجهزة)
        glow.shader = sw
        for (i in 4 downTo 1) {
            glow.strokeWidth = i * 3.2f * d
            glow.alpha = (intensity * 40 / i).toInt().coerceIn(0, 255)
            c.drawRoundRect(rect, r, r, glow)
        }

        // 2) الزجاج
        c.drawRoundRect(rect, r, r, fill)

        // 3) Aurora جوه الزجاج
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save(); c.clipPath(path)
        drawBlob(c, 0.22f + 0.10f * sin(phase), 0.10f + 0.06f * cos(phase * 1.3f), 0.55f, 0)
        drawBlob(c, 0.80f + 0.08f * cos(phase * 0.9f), 0.20f + 0.08f * sin(phase * 1.1f), 0.50f, 1)
        drawBlob(c, 0.55f + 0.15f * sin(phase * 0.7f), 0.95f, 0.60f, 2)
        // 4) لمعة علوية + لمعة قطرية
        c.drawRect(rect.left, rect.top, rect.right, rect.top + 70f * d, sheen)
        // لمعة قطرية خفيفة بتتحرك ببطء (إحساس الزجاج)
        diagMatrix.setTranslate(rect.width() * (0.15f + 0.1f * sin(phase * 0.5f)), 0f)
        diag.shader?.setLocalMatrix(diagMatrix)
        c.drawRect(rect, diag)
        c.restore()

        // 5) الحافة الداخلية
        c.drawRoundRect(rect, r, r, inner)

        // 6) الإطار المتدرج
        ring.shader = sw
        ring.strokeWidth = 1.6f * d
        ring.alpha = (90 + intensity * 130).toInt().coerceIn(0, 255)
        c.drawRoundRect(rect, r, r, ring)
    }

    private fun drawBlob(c: Canvas, fx: Float, fy: Float, fr: Float, idx: Int) {
        val cx = rect.left + rect.width() * fx
        val cy = rect.top + rect.height() * fy
        val rad = maxOf(rect.width(), 1f) * fr
        blobMatrix.setScale(rad, rad); blobMatrix.postTranslate(cx, cy)
        val sh = blobShaders[idx]; sh.setLocalMatrix(blobMatrix)
        blob.shader = sh
        blob.alpha = (blobAlpha[idx] * (0.7f + intensity * 0.6f)).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, rad, blob)
    }
}
