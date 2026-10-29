package com.nour.assistant.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout

/** علامة التفكير: كرة Nour بتلف + اسم "Nour" بلمعة. (بدل النقط التلاتة) */
class ThinkingView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val orb = NourOrbView(ctx)
    private val label = ShimmerTextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        orb.mode = NourOrbView.Mode.THINKING
        addView(orb, LayoutParams((30 * d).toInt(), (30 * d).toInt()))
        label.apply { text = "Nour"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); letterSpacing = 0.04f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * d).toInt() })
        setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
    }

    /** مثلاً: "Nour · بيبحث في الويب…" */
    fun setLabel(t: String) { label.text = t }
    fun setColor(c: Int) { label.setTextColor(c) }
}
