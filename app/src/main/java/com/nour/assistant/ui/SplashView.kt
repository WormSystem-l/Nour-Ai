package com.nour.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * انيميشن الفتح (بيشتغل كل مرة التطبيق يتفتح):
 *  1) شاشة سودا ونقطة نور بتظهر في النص
 *  2) النقطة تتحول لقطرة زجاج سائلة بقفزة ناعمة + موجة صدمة + شرارات
 *  3) الاسم "Nour" يظهر بلمعة وبلور خفيف بيتحل
 *  4) القطرة تطلع لمكانها في الشاشة الرئيسية والخلفية تتلاشى وباقي الواجهة تدخل
 * مرسوم بالكود (60fps) بدون ملفات تقيلة. لمسة = تخطّي سريع.
 */
class SplashView(ctx: Context) : FrameLayout(ctx) {
    /** يتنادى لما الانيميشن يخلص (شيل الـ View) */
    var onFinished: () -> Unit = {}
    /** تقدّم دخول الواجهة 0..1 (آخر مرحلة) */
    var onEnter: (Float) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val pal = NourTheme.palette(ctx)
    private val orb = NourOrbView(ctx)
    private val title = ShimmerTextView(ctx)
    private val bg = NourTheme.background(ctx, pal)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private val orbSize = (200 * d).toInt()
    private var cx0 = 0f; private var cy0 = 0f
    private var tx = Float.NaN; private var ty = 0f; private var tSize = 0f
    private var bgAlpha = 255
    private var t = 0f
    private var anim: ValueAnimator? = null
    private var done = false

    init {
        setWillNotDraw(false)
        isClickable = true
        addView(orb, LayoutParams(orbSize, orbSize, Gravity.TOP or Gravity.START))
        orb.alpha = 0f
        title.apply {
            text = "Nour"; textSize = 40f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; alpha = 0f; periodMs = 1200
        }
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        setOnClickListener { skip() }
    }

    /** مكان وحجم كرة الشاشة الرئيسية (بإحداثيات الشاشة) عشان القطرة تنزل عليها بالظبط */
    fun setTarget(centerX: Float, centerY: Float, sizePx: Float) { tx = centerX; ty = centerY; tSize = sizePx }

    override fun onLayout(changed: Boolean, l: Int, t0: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t0, r, b)
        val w = r - l; val h = b - t0
        cx0 = w / 2f; cy0 = h * 0.42f
        orb.layout((cx0 - orbSize / 2f).toInt(), (cy0 - orbSize / 2f).toInt(), (cx0 + orbSize / 2f).toInt(), (cy0 + orbSize / 2f).toInt())
        orb.pivotX = orbSize / 2f; orb.pivotY = orbSize / 2f
        val tw = title.measuredWidth.takeIf { it > 0 } ?: (160 * d).toInt()
        val th = title.measuredHeight.takeIf { it > 0 } ?: (56 * d).toInt()
        val top = (cy0 + orbSize * 0.40f + 22 * d).toInt()
        title.layout((cx0 - tw / 2f).toInt(), top, (cx0 + tw / 2f).toInt(), top + th)
        bg.setBounds(0, 0, w, h)
        if (anim == null && !done) start()
    }

    private fun start() {
        val total = TOTAL
        anim = ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong(); interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; step(t); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { finish() }
            })
            start()
        }
    }

    /** تخطّي: قفز لمرحلة الطلوع (لسه سلس) */
    fun skip() { val a = anim ?: return; if (a.currentPlayTime < 1350) a.currentPlayTime = 1350 }

    private fun finish() {
        if (done) return
        done = true
        onEnter(1f)
        onFinished()
    }

    private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; return 1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2) }
    private fun easeOutCubic(x: Float) = 1 - (1 - x).pow(3)
    private fun easeInOutCubic(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

    private fun step(t: Float) {
        // قطرة الزجاج: تظهر وتكبر بقفزة
        val born = seg(t, 180f, 880f)
        val scale = if (born <= 0f) 0.02f else 0.06f + (1f - 0.06f) * easeOutBack(born)
        orb.alpha = seg(t, 160f, 420f)
        orb.mode = if (t < 1100f) NourOrbView.Mode.LISTENING else NourOrbView.Mode.IDLE
        orb.level = if (t < 1100f) 0.55f * (1f - seg(t, 600f, 1100f)) else 0f

        // الاسم
        val tin = seg(t, 640f, 1020f)
        val tout = seg(t, 1400f, 1640f)
        title.alpha = easeOutCubic(tin) * (1f - tout)
        title.translationY = (14f * (1f - easeOutCubic(tin)) - 10f * tout) * d
        title.letterSpacing = 0.40f - 0.32f * easeOutCubic(tin)
        title.setShadowLayer((26f * (1f - easeOutCubic(tin))) * d + 0.01f, 0f, 0f, 0xCCC4B5FD.toInt())

        // طلوع القطرة لمكانها
        val mv = easeInOutCubic(seg(t, 1350f, TOTAL))
        var s = scale; var dx = 0f; var dy = 0f
        if (!tx.isNaN()) {
            val targetScale = tSize / orbSize
            s = scale + (targetScale - scale) * mv
            dx = (tx - cx0) * mv; dy = (ty - cy0) * mv
        } else { s = scale * (1f - 0.45f * mv); dy = -cy0 * 0.1f * mv }
        orb.scaleX = s; orb.scaleY = s; orb.translationX = dx; orb.translationY = dy

        // الخلفية تتلاشى وباقي الواجهة تدخل
        val fade = seg(t, 1450f, TOTAL)
        bgAlpha = (255 * (1f - easeOutCubic(fade))).toInt()
        bg.alpha = bgAlpha
        onEnter(seg(t, 1400f, TOTAL))
    }

    override fun onDraw(c: Canvas) {
        if (bgAlpha > 0) bg.draw(c)
        val t = t
        // 1) نقطة النور الأولى
        val p0 = seg(t, 0f, 260f) * (1f - seg(t, 420f, 760f))
        if (p0 > 0f) {
            val r = (8f + 44f * seg(t, 0f, 520f)) * d
            glow.shader = RadialGradient(cx0, cy0, r, intArrayOf(Color(0xFFFFFFFF, (255 * p0).toInt()), Color(0xFFA78BFA, (140 * p0).toInt()), 0x00000000), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx0, cy0, r, glow)
        }
        // 2) موجة الصدمة
        val ringP = seg(t, 330f, 1250f)
        if (ringP > 0f && ringP < 1f) {
            val r = (50f + 230f * easeOutCubic(ringP)) * d
            ring.strokeWidth = (3f * (1f - ringP) + 0.8f) * d
            ring.color = Color(0xFFC4B5FD, (150 * (1f - ringP)).toInt())
            c.drawCircle(cx0, cy0, r, ring)
            val r2 = (30f + 170f * easeOutCubic(seg(t, 420f, 1250f))) * d
            ring.color = Color(0xFF8B5CF6, (90 * (1f - ringP)).toInt())
            c.drawCircle(cx0, cy0, r2, ring)
        }
        // 3) شرارات
        val sp = seg(t, 420f, 1250f)
        if (sp > 0f && sp < 1f) {
            for (i in 0 until 18) {
                val a = (i / 18f) * 2f * PI.toFloat() + i * 0.37f
                val dist = (70f + (60f + (i % 4) * 22f) * easeOutCubic(sp)) * d
                dot.color = Color(if (i % 2 == 0) 0xFFC4B5FD else 0xFFFFFFFF, (230 * (1f - sp)).toInt())
                c.drawCircle(cx0 + cos(a) * dist, cy0 + sin(a) * dist, (1.6f + (i % 3)) * d * (1f - sp * 0.5f), dot)
            }
        }
    }

    @Suppress("FunctionName")
    private fun Color(rgb: Long, alpha: Int): Int = ((alpha.coerceIn(0, 255)) shl 24) or (rgb.toInt() and 0x00FFFFFF)

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    companion object {
        const val TOTAL = 1900f
        @Suppress("unused") val keepView: Class<*> = View::class.java
    }
}
