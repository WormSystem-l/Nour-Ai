package com.nour.assistant

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nour.assistant.ui.Markdown

class ChatAdapter(private val items: List<Msg>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) { val t: TextView = v.findViewById(R.id.text) }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_msg, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val m = items[i]
        val user = m.role == "user"
        Markdown.prepare(h.t)
        h.t.text = if (user || m.isError) m.content else Markdown.render(h.t.context, m.content, Markdown.LIGHT)
        h.t.setBackgroundResource(if (user) R.drawable.bubble_user else R.drawable.bubble_bot)
        h.t.setTextColor(when { user -> Color.WHITE; m.isError -> Color.rgb(183, 28, 28); else -> Color.BLACK })
        val lp = h.t.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (user) Gravity.END else Gravity.START
        h.t.layoutParams = lp
    }
}
