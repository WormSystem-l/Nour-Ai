package com.nour.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.TextView

/** نص بتمر عليه لمعة (Shimmer) — بيستخدم في علامة التفكير والسبلاتش */
class ShimmerTextView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : TextView(ctx, attrs) {
    private val m = Matrix()
    private var gradient: LinearGradient? = null
    private var attached = false
    /** سرعة اللمعة (دورة كل كام ثانية) */
    var periodMs = 1600L
    var shimmer = true
        set(v) { field = v; if (v) kick() else { paint.shader = null; invalidate() } }

    private val loop = object : Runnable {
        override fun run() { if (!attached || !shimmer) return; invalidate(); postOnAnimation(this) }
    }

    private fun kick() { if (attached) { removeCallbacks(loop); postOnAnimation(loop) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; kick() }
    override fun onDetachedFromWindow() { attached = false; removeCallbacks(loop); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        if (shimmer && width > 0) {
            val base = currentTextColor
            val dim = (base and 0x00FFFFFF) or (0x55 shl 24)
            val w = width.toFloat()
            val g = gradient ?: LinearGradient(0f, 0f, w * 0.6f, 0f, intArrayOf(dim, base, dim), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP).also { gradient = it }
            val t = (SystemClock.uptimeMillis() % periodMs) / periodMs.toFloat()
            m.setTranslate(-w * 0.6f + (w * 1.6f) * t, 0f)
            g.setLocalMatrix(m)
            paint.shader = g
        }
        super.onDraw(c)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); gradient = null }
}
