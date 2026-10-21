package com.nour.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nour.assistant.ui.MarkdownView
import com.nour.assistant.ui.NourTheme
import com.nour.assistant.ui.ThinkingView

/**
 * رسالة المستخدم: كبسولة زجاجية. رد نور: نص مباشر بدون فقاعة (زي ChatGPT/Claude)
 * مع بلوكات كود احترافية، وعلامة التفكير بتظهر بدل النقط.
 */
class ChatAdapter(private val items: List<Msg>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val user: TextView = v.findViewById(R.id.userText)
        val bot: LinearLayout = v.findViewById(R.id.botBox)
        val thinking: ThinkingView = v.findViewById(R.id.thinking)
        val md: MarkdownView = v.findViewById(R.id.md)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_msg, parent, false)
        val h = VH(v)
        val pal = NourTheme.palette(parent.context)
        h.user.background = NourTheme.glass(parent.context, 22f, pal, strong = true)
        h.user.setTextColor(pal.text)
        h.user.maxWidth = (parent.resources.displayMetrics.widthPixels * 0.82f).toInt()
        h.md.textColor = pal.text
        h.thinking.setColor(pal.text)
        return h
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        val pal = NourTheme.palette(h.itemView.context)
        if (m.role == "user") {
            h.user.visibility = View.VISIBLE; h.bot.visibility = View.GONE
            h.user.text = m.content
            return
        }
        h.user.visibility = View.GONE; h.bot.visibility = View.VISIBLE
        if (m.content == "…") {
            h.thinking.visibility = View.VISIBLE; h.md.visibility = View.GONE
        } else {
            h.thinking.visibility = View.GONE; h.md.visibility = View.VISIBLE
            h.md.textColor = if (m.isError) pal.danger else pal.text
            h.md.setMarkdown(m.content)
        }
    }
}
