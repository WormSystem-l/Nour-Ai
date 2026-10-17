package com.nour.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.nour.assistant.L

/**
 * بيحوّل رد الذكاء الاصطناعي لنص منسّق:
 * ** عريض، * مائل، # عناوين، ` كود، ``` بلوك كود (بخلفية وزرار «نسخ»)، قوايم، اقتباس.
 */
object Markdown {
    class Theme(
        val text: Int,
        val codeBg: Int,
        val codeFg: Int,
        val accent: Int
    )

    val DARK = Theme(text = 0xFFFFFFFF.toInt(), codeBg = 0x66000000, codeFg = 0xFFE9D5FF.toInt(), accent = 0xFFC4B5FD.toInt())
    val LIGHT = Theme(text = 0xFF000000.toInt(), codeBg = 0x1F000000, codeFg = 0xFF3B0764.toInt(), accent = 0xFF6D28D9.toInt())

    /** اربط الـ TextView بحيث الروابط وزرار النسخ يشتغلوا (مع الحفاظ على التحديد) */
    fun prepare(tv: TextView) {
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    fun render(ctx: Context, text: String, theme: Theme): CharSequence {
        if (text.isEmpty()) return text
        val blocks = MdParser.parse(text)
        val sb = SpannableStringBuilder()
        var prevBlank = true
        blocks.forEachIndexed { idx, b ->
            when (b) {
                is MdParser.Block.Line -> {
                    if (b.kind == MdParser.Kind.BLANK) {
                        // سطر فاضي واحد بس بين الفقرات
                        if (!prevBlank && idx != blocks.lastIndex) sb.append('\n')
                        prevBlank = true
                        return@forEachIndexed
                    }
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    appendLine(sb, b, theme, ctx)
                    prevBlank = false
                }
                is MdParser.Block.Code -> {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    appendCode(sb, b, theme, ctx)
                    prevBlank = false
                }
            }
        }
        // شيل أي أسطر فاضية في الآخر
        while (sb.isNotEmpty() && sb.last() == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun appendLine(sb: SpannableStringBuilder, b: MdParser.Block.Line, th: Theme, ctx: Context) {
        val lineStart = sb.length
        when (b.kind) {
            MdParser.Kind.BULLET -> sb.append("  ".repeat(b.indent)).append("• ")
            MdParser.Kind.NUMBER -> sb.append("  ".repeat(b.indent)).append(b.marker).append(' ')
            MdParser.Kind.QUOTE -> sb.append("▎ ")
            MdParser.Kind.RULE -> { sb.append("────────"); sb.setSpan(ForegroundColorSpan(th.accent), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); return }
            else -> {}
        }
        for (s in b.segs) appendSeg(sb, s, th, ctx)
        val end = sb.length
        if (end > lineStart) when (b.kind) {
            MdParser.Kind.H1 -> { sb.setSpan(RelativeSizeSpan(1.45f), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            MdParser.Kind.H2 -> { sb.setSpan(RelativeSizeSpan(1.28f), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            MdParser.Kind.H3 -> { sb.setSpan(RelativeSizeSpan(1.12f), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            MdParser.Kind.QUOTE -> { sb.setSpan(StyleSpan(Typeface.ITALIC), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(th.accent), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            else -> {}
        }
    }

    private fun appendSeg(sb: SpannableStringBuilder, s: MdParser.Seg, th: Theme, ctx: Context) {
        if (s.text.isEmpty()) return
        val st = sb.length
        sb.append(s.text)
        val en = sb.length
        val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (s.bold && s.italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), st, en, f)
        else if (s.bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, en, f)
        else if (s.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), st, en, f)
        if (s.strike) sb.setSpan(StrikethroughSpan(), st, en, f)
        if (s.code) {
            sb.setSpan(TypefaceSpan("monospace"), st, en, f)
            sb.setSpan(BackgroundColorSpan(th.codeBg), st, en, f)
            sb.setSpan(ForegroundColorSpan(th.codeFg), st, en, f)
        }
        s.link?.let { url ->
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(w: View) {
                    try { w.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
                override fun updateDrawState(ds: TextPaint) { ds.color = th.accent; ds.isUnderlineText = true }
            }, st, en, f)
        }
    }

    /** بلوك كود: سطر عنوان (اللغة + «نسخ») وبعده الكود monospace بخلفية غامقة */
    private fun appendCode(sb: SpannableStringBuilder, b: MdParser.Block.Code, th: Theme, ctx: Context) {
        val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val h0 = sb.length
        val label = (if (b.lang.isNotBlank()) b.lang else "code")
        sb.append(" ").append(label).append("   ")
        val c0 = sb.length
        val copy = "⧉ " + L.t("نسخ", "Copy")
        sb.append(copy).append(" ")
        val h1 = sb.length
        sb.setSpan(BackgroundColorSpan(th.codeBg), h0, h1, f)
        sb.setSpan(ForegroundColorSpan(th.accent), h0, c0, f)
        sb.setSpan(RelativeSizeSpan(0.8f), h0, h1, f)
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(w: View) {
                try {
                    val cm = w.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("code", b.code))
                    Toast.makeText(w.context, L.t("تم النسخ ✓", "Copied ✓"), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            override fun updateDrawState(ds: TextPaint) { ds.color = th.accent; ds.isUnderlineText = false; ds.isFakeBoldText = true }
        }, c0, h1 - 1, f)
        sb.append('\n')
        val k0 = sb.length
        val code = b.code.ifEmpty { " " }
        sb.append(code)
        val k1 = sb.length
        sb.setSpan(TypefaceSpan("monospace"), k0, k1, f)
        sb.setSpan(BackgroundColorSpan(th.codeBg), k0, k1, f)
        sb.setSpan(ForegroundColorSpan(th.codeFg), k0, k1, f)
        sb.setSpan(RelativeSizeSpan(0.9f), k0, k1, f)
        sb.setSpan(LeadingMarginSpan.Standard(12, 12), k0, k1, Spanned.SPAN_PARAGRAPH)
    }
}
