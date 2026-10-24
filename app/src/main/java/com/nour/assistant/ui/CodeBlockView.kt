package com.nour.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nour.assistant.L

/** بلوك كود احترافي: عنوان باللغة + زرار نسخ، وجسم monospace بتلوين خفيف وتمرير أفقي */
class CodeBlockView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val pal = NourTheme.palette(ctx)
    private val langTv = TextView(ctx)
    private val copyIcon = ImageView(ctx)
    private val copyTv = TextView(ctx)
    private val body = TextView(ctx)
    private var code = ""
    private var lastSig = ""

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(pal.codeBg); cornerRadius = 14 * d; setStroke((1 * d).toInt().coerceAtLeast(1), pal.glassStroke)
        }
        // رأس البلوك
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply { setColor(pal.codeHeader); cornerRadii = floatArrayOf(14 * d, 14 * d, 14 * d, 14 * d, 0f, 0f, 0f, 0f) }
        }
        langTv.apply { textSize = 12f; setTextColor(pal.textDim); typeface = Typeface.MONOSPACE; letterSpacing = 0.04f }
        head.addView(langTv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val copyBtn = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = 20 * d }
            isClickable = true; isFocusable = true
            contentDescription = L.t("نسخ الكود", "Copy code")
        }
        copyIcon.setImageResource(com.nour.assistant.R.drawable.ic_copy)
        copyIcon.setColorFilter(pal.textDim)
        copyBtn.addView(copyIcon, LayoutParams((16 * d).toInt(), (16 * d).toInt()))
        copyTv.apply { text = L.t("نسخ", "Copy"); textSize = 12.5f; setTextColor(pal.text); setPadding((6 * d).toInt(), 0, 0, 0) }
        copyBtn.addView(copyTv)
        copyBtn.setOnClickListener { copy() }
        head.addView(copyBtn)
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // الجسم
        body.apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(pal.codeFg)
            setHorizontallyScrolling(true); setTextIsSelectable(true)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            setLineSpacing(0f, 1.15f)
            layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(body) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(lang: String, code: String, closed: Boolean) {
        val sig = "$lang|$closed|${code.length}|${code.hashCode()}"
        if (sig == lastSig) return
        lastSig = sig
        this.code = code
        langTv.text = lang.ifBlank { "code" }.lowercase()
        // التلوين أثناء الكتابة الطويلة تقيل، فبنلوّن لما البلوك يقفل أو لو قصير
        val sp = SpannableString(code.ifEmpty { " " })
        if (closed || code.length < 1600) {
            for (h in CodeHighlighter.highlight(code, lang)) {
                val c = when (h.kind) {
                    CodeHighlighter.Kind.KEYWORD -> 0xFFC4B5FD.toInt()
                    CodeHighlighter.Kind.STRING -> 0xFF86EFAC.toInt()
                    CodeHighlighter.Kind.NUMBER -> 0xFFFCD34D.toInt()
                    CodeHighlighter.Kind.COMMENT -> 0xFF7C7C8A.toInt()
                }
                sp.setSpan(ForegroundColorSpan(c), h.start, h.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        body.text = sp
    }

    private fun copy() {
        try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", code))
        } catch (_: Exception) { return }
        copyTv.text = L.t("تم النسخ", "Copied")
        copyIcon.setImageResource(com.nour.assistant.R.drawable.ic_check)
        copyIcon.setColorFilter(0xFF86EFAC.toInt())
        removeCallbacks(reset)
        postDelayed(reset, 1700)
    }

    private val reset = Runnable {
        copyTv.text = L.t("نسخ", "Copy")
        copyIcon.setImageResource(com.nour.assistant.R.drawable.ic_copy)
        copyIcon.setColorFilter(pal.textDim)
    }

    @Suppress("unused") private val keep = Spannable::class
}
