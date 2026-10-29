package com.nour.assistant.ui

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * عارض ردود الذكاء الاصطناعي: فقرات منسّقة (Markdown) + بلوكات كود احترافية بزرار نسخ.
 * بيتحدّث تدريجياً أثناء الكتابة (بيعيد رسم الجزء اللي اتغيّر بس) عشان مفيش وميض.
 */
class MarkdownView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private sealed class Seg {
        data class Text(val lines: List<MdParser.Block>) : Seg()
        data class Code(val lang: String, val code: String, val closed: Boolean) : Seg()
    }

    private val d = resources.displayMetrics.density
    private val shown = ArrayList<Pair<Seg, View>>()
    var textColor = 0xFFFFFFFF.toInt()
    var textSizeSp = 16f
    var theme: Markdown.Theme = Markdown.DARK
    /** النص الخام اللي اتعرض (بيفيد الاختبارات وإمكانية الوصول) */
    var plainText: String = ""
        private set

    init { orientation = VERTICAL }

    fun setMarkdown(text: String) {
        plainText = text
        val segs = split(text)
        // امسح اللي زيادة
        while (shown.size > segs.size) { removeViewAt(shown.size - 1); shown.removeAt(shown.size - 1) }
        for ((i, seg) in segs.withIndex()) {
            val cur = shown.getOrNull(i)
            when (seg) {
                is Seg.Text -> {
                    val tv: TextView
                    if (cur != null && cur.first is Seg.Text) {
                        if (cur.first == seg) continue
                        tv = cur.second as TextView
                        shown[i] = seg to tv
                    } else {
                        if (cur != null) { removeViewAt(i); shown.removeAt(i) }
                        tv = newText(); addView(tv, i, lp(i)); shown.add(i, seg to tv)
                    }
                    tv.text = Markdown.renderBlocks(context, seg.lines, theme)
                }
                is Seg.Code -> {
                    val cv: CodeBlockView
                    if (cur != null && cur.first is Seg.Code) { cv = cur.second as CodeBlockView; shown[i] = seg to cv }
                    else {
                        if (cur != null) { removeViewAt(i); shown.removeAt(i) }
                        cv = CodeBlockView(context); addView(cv, i, lp(i)); shown.add(i, seg to cv)
                    }
                    cv.bind(seg.lang, seg.code, seg.closed)
                }
            }
        }
    }

    fun clear() { removeAllViews(); shown.clear(); plainText = "" }

    private fun lp(i: Int) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = (10 * d).toInt() }

    private fun newText() = TextView(context).apply {
        setTextColor(textColor); textSize = textSizeSp; setLineSpacing(0f, 1.28f)
        setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance()
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun split(text: String): List<Seg> {
        val out = ArrayList<Seg>()
        var acc = ArrayList<MdParser.Block>()
        fun flush() { if (acc.any { it !is MdParser.Block.Line || it.kind != MdParser.Kind.BLANK }) out += Seg.Text(acc.toList()); acc = ArrayList() }
        for (b in MdParser.parse(text)) {
            if (b is MdParser.Block.Code) { flush(); out += Seg.Code(b.lang, b.code, b.closed) } else acc += b
        }
        flush()
        return out
    }
}
