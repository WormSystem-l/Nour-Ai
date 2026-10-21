package com.nour.assistant.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nour.assistant.ConversationRepo
import com.nour.assistant.ConversationStore
import com.nour.assistant.L
import com.nour.assistant.R

/**
 * القايمة الجانبية (☰): محادثاتك القديمة مع بحث. ضغطة تفتح المحادثة، وضغطة طويلة = حذف/إلغاء.
 * مبنية يدوي (من غير DrawerLayout) عشان الحركة والشكل زجاجي بالكامل.
 */
class ChatDrawer(ctx: Context) : FrameLayout(ctx) {
    var onNewChat: () -> Unit = {}
    var onOpenChat: (String) -> Unit = {}
    var onDeleteChat: (String) -> Unit = {}
    var onSettings: () -> Unit = {}
    var currentId: () -> String = { "" }

    private val d = resources.displayMetrics.density
    private val pal = NourTheme.palette(ctx)
    private val scrim = View(ctx).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f }
    private val panel = LinearLayout(ctx)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val search = EditText(ctx)
    private var query = ""
    var isOpen = false
        private set
    private val panelW = (minOf(resources.displayMetrics.widthPixels * 0.84f, 340 * d)).toInt()

    init {
        visibility = GONE
        addView(scrim, LayoutParams(-1, -1))
        scrim.setOnClickListener { close() }
        panel.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(if (pal.black) 0xFF0B0B0D.toInt() else 0xFF140F48.toInt(), if (pal.black) 0xFF000000.toInt() else 0xFF080625.toInt())).apply { setStroke((1 * d).toInt().coerceAtLeast(1), pal.glassStroke) }
            isClickable = true
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            fitsSystemWindows = true
        }
        addView(panel, LayoutParams(panelW, -1, Gravity.START))

        // رأس: كرة + اسم
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((4 * d).toInt(), (6 * d).toInt(), 0, (10 * d).toInt()) }
        head.addView(NourOrbView(ctx), LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()))
        head.addView(TextView(ctx).apply { text = "Nour"; textSize = 20f; setTextColor(pal.text); setTypeface(typeface, Typeface.BOLD); setPadding((8 * d).toInt(), 0, 0, 0) })
        panel.addView(head)

        // بحث
        val sbox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = NourTheme.glass(ctx, 22f, pal); setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
        }
        sbox.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_search); setColorFilter(pal.textDim) }, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))
        search.apply {
            hint = L.t("ابحث في المحادثات", "Search chats"); setHintTextColor(pal.textFaint); setTextColor(pal.text); textSize = 15f
            background = null; setSingleLine(); setPadding((10 * d).toInt(), (11 * d).toInt(), 0, (11 * d).toInt())
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { query = e?.toString().orEmpty(); rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        sbox.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(sbox, LinearLayout.LayoutParams(-1, (46 * d).toInt()))

        // محادثة جديدة
        panel.addView(row(R.drawable.ic_new_chat, L.t("محادثة جديدة", "New chat"), null, false) { close(); onNewChat() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })

        panel.addView(TextView(ctx).apply {
            text = L.t("المحادثات", "Chats"); textSize = 12.5f; setTextColor(pal.textFaint); letterSpacing = 0.06f
            setPadding((8 * d).toInt(), (16 * d).toInt(), 0, (6 * d).toInt())
        })
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
        panel.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        panel.addView(row(R.drawable.ic_settings, L.t("الإعدادات", "Settings"), null, false) { close(); onSettings() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
    }

    private fun row(icon: Int?, title: String, sub: String?, selected: Boolean, onClick: () -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (11 * d).toInt(), (12 * d).toInt(), (11 * d).toInt())
            background = if (selected) NourTheme.glass(context, 16f, pal, strong = true) else null
            isClickable = true; isFocusable = true
        }
        if (icon != null) r.addView(ImageView(context).apply { setImageResource(icon); setColorFilter(pal.text) }, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply { text = title; textSize = 15.5f; setTextColor(pal.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub != null) col.addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(pal.textFaint) })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        r.setOnClickListener { onClick() }
        return r
    }

    /** إعادة بناء قايمة المحادثات من الملف */
    fun rebuild() {
        list.removeAllViews()
        val ar = java.util.Locale.getDefault().language == "ar"
        val now = System.currentTimeMillis()
        val items = try { ConversationStore.repo(context).list(query) } catch (_: Exception) { emptyList() }
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (query.isBlank()) L.t("لسه مفيش محادثات.", "No chats yet.") else L.t("مفيش نتايج.", "No results.")
                setTextColor(pal.textFaint); textSize = 14f; setPadding((10 * d).toInt(), (12 * d).toInt(), 0, 0)
            })
            return
        }
        val cur = currentId()
        for (m in items) {
            val v = row(null, m.title.ifBlank { L.t("محادثة", "Chat") }, ConversationRepo.ago(now, m.updatedAt, ar), m.id == cur) { close(); onOpenChat(m.id) }
            v.setOnLongClickListener {
                NourDialog.confirm(context, L.t("حذف المحادثة؟", "Delete this chat?"),
                    L.t("«${m.title.take(40)}» هتتمسح نهائياً.", "“${m.title.take(40)}” will be deleted permanently."),
                    L.t("حذف", "Delete"), L.t("إلغاء", "Cancel"), danger = true) { onDeleteChat(m.id); rebuild() }
                true
            }
            list.addView(v, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun sign(): Float = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f

    fun open() {
        if (isOpen) return
        isOpen = true
        rebuild()
        visibility = VISIBLE
        panel.translationX = sign() * panelW
        scrim.alpha = 0f
        panel.animate().translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(1.9f)).start()
        scrim.animate().alpha(1f).setDuration(300).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        search.clearFocus()
        panel.animate().translationX(sign() * panelW).setDuration(240).start()
        scrim.animate().alpha(0f).setDuration(240).withEndAction { if (!isOpen) visibility = GONE }.start()
    }

    @Suppress("unused") private val keepColor = Color.BLACK
    @Suppress("unused") private val keepVG: Class<*> = ViewGroup::class.java
}
