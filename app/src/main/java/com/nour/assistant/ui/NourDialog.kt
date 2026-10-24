package com.nour.assistant.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** نوافذ منبثقة بنفس الثيم (زجاج على أسود أو ألوان). مفيش AlertDialog الافتراضي. */
object NourDialog {

    private fun base(ctx: Context): Dialog = Dialog(ctx).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.62f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** نافذة سفلية (Bottom sheet) بحركة طلوع ناعمة. fullHeight: تاخد 90% من الشاشة */
    fun sheet(ctx: Context, content: View, fullHeight: Boolean = false): Dialog {
        val p = NourTheme.palette(ctx)
        val dlg = base(ctx)
        val d = ctx.resources.displayMetrics.density
        val r = 26 * d
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(if (p.black) 0xFF121214.toInt() else 0xFF17124A.toInt(), if (p.black) 0xFF050506.toInt() else 0xFF0B0830.toInt())).apply {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setStroke((1 * d).toInt().coerceAtLeast(1), p.glassStroke)
            }
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        // مقبض
        val handle = View(ctx).apply { background = GradientDrawable().apply { setColor(0x44FFFFFF); cornerRadius = 3 * d } }
        card.addView(handle, FrameLayout.LayoutParams((40 * d).toInt(), (4 * d).toInt(), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        card.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * d).toInt() })
        dlg.setContentView(card)
        dlg.window?.apply {
            setGravity(Gravity.BOTTOM)
            val h = if (fullHeight) (ctx.resources.displayMetrics.heightPixels * 0.9f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }
        dlg.setOnShowListener {
            card.translationY = ctx.resources.displayMetrics.heightPixels * 0.4f; card.alpha = 0f
            card.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        return dlg
    }

    /** نافذة تأكيد زجاجية: [الإجراء] [إلغاء] */
    fun confirm(ctx: Context, title: String, message: String, positive: String, negative: String, danger: Boolean = false, onPositive: () -> Unit) {
        val p = NourTheme.palette(ctx)
        val d = ctx.resources.displayMetrics.density
        val dlg = base(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (22 * d).toInt(), (22 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(if (p.black) 0xFF151517.toInt() else 0xFF1B1560.toInt(), if (p.black) 0xFF08080A.toInt() else 0xFF0D0A38.toInt())).apply {
                cornerRadius = 24 * d; setStroke((1 * d).toInt().coerceAtLeast(1), p.glassStroke)
            }
        }
        box.addView(TextView(ctx).apply { text = title; textSize = 18f; setTextColor(p.text); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(ctx).apply { text = message; textSize = 14.5f; setTextColor(p.textDim); setPadding(0, (8 * d).toInt(), 0, (18 * d).toInt()) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(t: String, fill: Boolean, color: Int, click: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 15f; setTextColor(if (fill) Color.WHITE else color); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding((22 * d).toInt(), (11 * d).toInt(), (22 * d).toInt(), (11 * d).toInt())
            background = if (fill) GradientDrawable().apply { setColor(color); cornerRadius = 22 * d } else GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = 22 * d }
            isClickable = true; setOnClickListener { click() }
        }
        row.addView(btn(negative, false, p.text) { dlg.dismiss() })
        row.addView(btn(positive, true, if (danger) 0xFFE5484D.toInt() else p.accent) { dlg.dismiss(); onPositive() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (10 * d).toInt() })
        box.addView(row)
        dlg.setContentView(box)
        dlg.window?.setLayout((ctx.resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.setOnShowListener { box.scaleX = 0.92f; box.scaleY = 0.92f; box.alpha = 0f; box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start() }
        dlg.show()
    }
}
