package com.nour.assistant.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import com.nour.assistant.Prefs

/**
 * الثيمين:
 *  - BLACK: أسود تماماً #000000 + زجاج محايد (الافتراضي)
 *  - COLOR: الألوان العصرية (بنفسجي/نيلي) زي النافذة المنبثقة
 */
object NourTheme {
    class Palette(
        val black: Boolean,
        val bg: Int, val bgTop: Int, val bgBottom: Int,
        val text: Int, val textDim: Int, val textFaint: Int,
        val glassA: Int, val glassB: Int, val glassStroke: Int,
        val accent: Int, val accent2: Int,
        val codeBg: Int, val codeHeader: Int, val codeFg: Int,
        val danger: Int
    )

    private val BLACK = Palette(
        black = true, bg = 0xFF000000.toInt(), bgTop = 0xFF000000.toInt(), bgBottom = 0xFF000000.toInt(),
        text = 0xFFF5F5F7.toInt(), textDim = 0xB3FFFFFF.toInt(), textFaint = 0x66FFFFFF,
        glassA = 0x1FFFFFFF, glassB = 0x0FFFFFFF, glassStroke = 0x2BFFFFFF,
        accent = 0xFF8B5CF6.toInt(), accent2 = 0xFF6366F1.toInt(),
        codeBg = 0xFF0A0A0C.toInt(), codeHeader = 0xFF16161A.toInt(), codeFg = 0xFFE9E9F0.toInt(),
        danger = 0xFFFF6B6B.toInt()
    )

    private val COLOR = Palette(
        black = false, bg = 0xFF07061F.toInt(), bgTop = 0xFF0B0A2E.toInt(), bgBottom = 0xFF1B1060.toInt(),
        text = 0xFFF4F1FF.toInt(), textDim = 0xB3E9E3FF.toInt(), textFaint = 0x66E9E3FF,
        glassA = 0x33A78BFA, glassB = 0x1A6366F1, glassStroke = 0x55C4B5FD,
        accent = 0xFFA78BFA.toInt(), accent2 = 0xFF6366F1.toInt(),
        codeBg = 0xFF0C0A2A.toInt(), codeHeader = 0xFF191444.toInt(), codeFg = 0xFFE9D5FF.toInt(),
        danger = 0xFFFF8A8A.toInt()
    )

    fun isBlack(ctx: Context) = Prefs(ctx).themeMode != "color"
    fun palette(ctx: Context): Palette = if (isBlack(ctx)) BLACK else COLOR
    fun palette(black: Boolean): Palette = if (black) BLACK else COLOR

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    /** زجاج: طبقة أساس شفافة + لمعة علوية + إطار رفيع */
    fun glass(ctx: Context, radiusDp: Float, p: Palette = palette(ctx), strong: Boolean = false): Drawable {
        val r = dp(ctx, radiusDp).toFloat()
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (strong) boost(p.glassA) else p.glassA, if (strong) boost(p.glassB) else p.glassB)).apply {
            cornerRadius = r; setStroke(dp(ctx, 1f), p.glassStroke)
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x00FFFFFF)).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(base, sheen))
    }

    private fun boost(c: Int): Int {
        val a = ((c ushr 24) * 1.6f).toInt().coerceAtMost(255)
        return (a shl 24) or (c and 0x00FFFFFF)
    }

    fun circleGlass(ctx: Context, p: Palette = palette(ctx)): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.glassA, p.glassB)).apply {
            shape = GradientDrawable.OVAL; setStroke(dp(ctx, 1f), p.glassStroke)
        }

    /** زرار مميز (إرسال...) بتدرج لون Nour */
    fun accentFill(ctx: Context, radiusDp: Float = 24f, p: Palette = palette(ctx), oval: Boolean = false): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.accent, p.accent2)).apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    /** خلفية الشاشات: أسود تماماً أو تدرج + توهجات ألوان */
    fun background(ctx: Context, p: Palette = palette(ctx)): Drawable {
        if (p.black) return ColorDrawable(p.bg)
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.bgTop, p.bgBottom))
        fun glow(cx: Float, cy: Float, color: Int, radiusDp: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(cx, cy)
            gradientRadius = dp(ctx, radiusDp).toFloat()
            colors = intArrayOf(color, color and 0x00FFFFFF)
        }
        return LayerDrawable(arrayOf(base, glow(0.15f, 0.05f, 0x558B5CF6, 420f), glow(0.9f, 0.85f, 0x444F46E5, 460f)))
    }

    /** بيطبق الخلفية وألوان الشريط العلوي/السفلي على الشاشة */
    fun applyScreen(act: Activity, root: View? = null) {
        val p = palette(act)
        act.window.setBackgroundDrawable(background(act, p))
        root?.setBackgroundColor(Color.TRANSPARENT)
        act.window.statusBarColor = Color.TRANSPARENT
        act.window.navigationBarColor = if (p.black) Color.BLACK else p.bgBottom
        if (Build.VERSION.SDK_INT >= 23) {
            val v = act.window.decorView
            v.systemUiVisibility = v.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }
}
