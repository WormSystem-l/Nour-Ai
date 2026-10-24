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
class ChatAdapter(private val items: List<Msg>, private val onSources: (List<com.nour.assistant.tools.Source>) -> Unit = {}) : RecyclerView.Adapter<ChatAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val user: TextView = v.findViewById(R.id.userText)
        val bot: LinearLayout = v.findViewById(R.id.botBox)
        val thinking: ThinkingView = v.findViewById(R.id.thinking)
        val md: MarkdownView = v.findViewById(R.id.md)
        val status: ThinkingView = v.findViewById(R.id.status)
        val sourcesBtn: TextView = v.findViewById(R.id.sourcesBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_msg, parent, false)
        val h = VH(v)
        val pal = NourTheme.palette(parent.context)
        h.user.background = NourTheme.glass(parent.context, 22f, pal, strong = true)
        h.user.setTextColor(pal.text)
        h.user.maxWidth = (parent.resources.displayMetrics.widthPixels * 0.82f).toInt()
        h.md.textColor = pal.text
        h.thinking.setColor(pal.text); h.status.setColor(pal.text)
        h.sourcesBtn.background = NourTheme.glass(parent.context, 20f, pal)
        h.sourcesBtn.setTextColor(pal.text)
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
        val placeholder = m.content == "…"
        h.thinking.visibility = if (placeholder) View.VISIBLE else View.GONE
        if (placeholder) h.thinking.setLabel(m.status ?: "Nour")
        h.md.visibility = if (placeholder) View.GONE else View.VISIBLE
        if (!placeholder) {
            h.md.textColor = if (m.isError) pal.danger else pal.text
            h.md.setMarkdown(m.content)
        }
        // حالة أثناء الرد (جاري البحث...) بعد ما النص يبدأ
        val showStatus = !placeholder && m.status != null
        h.status.visibility = if (showStatus) View.VISIBLE else View.GONE
        if (showStatus) h.status.setLabel(m.status ?: "Nour")
        // زرار المصادر (للرد ده بس)
        if (m.sources.isNotEmpty() && !placeholder && m.status == null) {
            h.sourcesBtn.visibility = View.VISIBLE
            h.sourcesBtn.text = com.nour.assistant.L.t("المصادر", "Sources") + "  ·  ${m.sources.size}"
            h.sourcesBtn.setOnClickListener { onSources(m.sources) }
        } else h.sourcesBtn.visibility = View.GONE
    }
}
