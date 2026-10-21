package com.nour.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/** موجة صوت زجاجية: 3 موجات متداخلة بألوان متدرجة بتتحرك مع مستوى الصوت */
class WaveView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val paints = listOf(0xFF8B5CF6, 0xFF3B82F6, 0xFF22D3EE).map { col ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.6f * d; color = col.toInt()
        }
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()
    private val specs = arrayOf(floatArrayOf(1.0f, 1.6f, 0f), floatArrayOf(0.7f, 2.2f, 1.7f), floatArrayOf(0.45f, 3.0f, 3.1f))
    private var t = 0f
    private var amp = 0.12f
    /** 0..1 */
    var level = 0f
    var thinking = false

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            t += if (thinking) 0.09f else 0.06f
            val target = if (thinking) 0.22f + 0.08f * sin(t * 2f) else 0.28f + level * 0.72f
            amp += (target - amp) * 0.18f
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!anim.isStarted) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(v: View, visibility: Int) {
        super.onVisibilityChanged(v, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) { if (!anim.isStarted) anim.start() } else anim.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        fillPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0x338B5CF6, 0x333B82F6, 0x3322D3EE), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val mid = h / 2f
        for (i in specs.indices) {
            val a = specs[i][0]; val freq = specs[i][1]; val off = specs[i][2]
            path.reset()
            var x = 0f
            while (x <= w) {
                val n = x / w
                val env = sin(n * PI).toFloat()              // الأطراف هادية والنص عالي
                val y = mid + sin(n * freq * 2 * PI.toFloat() + t * (1.2f + i * 0.35f) + off) * env * amp * a * (h * 0.48f)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 3f * d
            }
            if (i == 0) { // ظل ملون تحت الموجة الأساسية
                fillPath.set(path); fillPath.lineTo(w, mid); fillPath.lineTo(0f, mid); fillPath.close()
                c.drawPath(fillPath, fillPaint)
            }
            paints[i].alpha = (255 * (1f - i * 0.25f)).toInt()
            c.drawPath(path, paints[i])
        }
    }
}
