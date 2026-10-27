package com.nour.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageButton

/** زرار دائري بتدرج لوني لامع (للمايك) — بينبض لما يكون شغال */
class GradientOrbButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    AppCompatImageButton(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private var sweep: SweepGradient? = null
    private var a = 0f
    private var pulse = 0f
    var active = false
        set(v) { field = v; if (v) anim.start() else { anim.cancel(); pulse = 0f }; invalidate() }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; a = (a + 4f) % 360f; invalidate() }
    }

    init { background = null; scaleType = ScaleType.CENTER; setColorFilter(Color.WHITE) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(w / 2f, h / 2f,
            intArrayOf(0xFF7C3AED.toInt(), 0xFF4338CA.toInt(), 0xFF1D4ED8.toInt(), 0xFF7C3AED.toInt()), null)
        hl.shader = RadialGradient(w * 0.35f, h * 0.28f, w * 0.45f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 5f * d
        sweep?.let { m.setRotate(a, cx, cy); it.setLocalMatrix(m); fill.shader = it; halo.shader = it }
        if (active) {
            halo.strokeWidth = 2f * d
            halo.alpha = ((1f - pulse) * 200).toInt()
            c.drawCircle(cx, cy, r + pulse * 5f * d, halo)
        }
        fill.alpha = if (isEnabled) 255 else 90
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, hl)
        super.onDraw(c)
    }
}
