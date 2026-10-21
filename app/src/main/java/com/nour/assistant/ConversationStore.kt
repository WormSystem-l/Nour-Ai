package com.nour.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Conversation(val id: String, var title: String, var updatedAt: Long, val messages: MutableList<Msg>)

/** مخزن المحادثات (ملف JSON واحد). منطق نقي عشان يتجرب بدون Android. */
class ConversationRepo(private val file: File, private val maxConversations: Int = 200) {
    class Meta(val id: String, val title: String, val updatedAt: Long, val count: Int)

    private fun readAll(): MutableList<Conversation> = try {
        if (!file.exists()) mutableListOf() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val ms = o.optJSONArray("m") ?: JSONArray()
                Conversation(o.getString("id"), o.optString("t"), o.optLong("u"),
                    (0 until ms.length()).map { i -> ms.getJSONObject(i) }
                        .map { m -> Msg(m.optString("r"), m.optString("c"), m.optBoolean("e", false)) }.toMutableList())
            }.toMutableList()
        }
    } catch (_: Exception) { mutableListOf() }

    private fun writeAll(list: List<Conversation>) {
        val a = JSONArray()
        list.sortedByDescending { it.updatedAt }.take(maxConversations).forEach { c ->
            a.put(JSONObject().put("id", c.id).put("t", c.title).put("u", c.updatedAt).put("m", JSONArray().also { arr ->
                c.messages.forEach { m -> arr.put(JSONObject().put("r", m.role).put("c", m.content).put("e", m.isError)) }
            }))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString()); tmp.renameTo(file)
    }

    @Synchronized fun list(query: String = ""): List<Meta> {
        val q = query.trim().lowercase()
        return readAll().sortedByDescending { it.updatedAt }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.messages.any { m -> m.content.lowercase().contains(q) } }
            .map { Meta(it.id, it.title, it.updatedAt, it.messages.size) }
    }

    @Synchronized fun load(id: String): Conversation? = readAll().firstOrNull { it.id == id }

    @Synchronized fun save(c: Conversation) {
        val all = readAll()
        all.removeAll { it.id == c.id }
        all.add(c)
        writeAll(all)
    }

    @Synchronized fun delete(id: String) { val all = readAll(); all.removeAll { it.id == id }; writeAll(all) }
    @Synchronized fun clear() { writeAll(emptyList()) }

    companion object {
        /** عنوان المحادثة من أول رسالة للمستخدم */
        fun titleFrom(first: String): String {
            val t = first.replace(Regex("\\s+"), " ").trim()
            return if (t.length <= 42) t else t.take(42).trimEnd() + "…"
        }

        /** "منذ 5 دقايق" */
        fun ago(now: Long, then: Long, ar: Boolean): String {
            val m = ((now - then) / 60_000).coerceAtLeast(0)
            return when {
                m < 1 -> if (ar) "دلوقتي" else "just now"
                m < 60 -> if (ar) "منذ $m دقيقة" else "${m}m ago"
                m < 60 * 24 -> (m / 60).let { if (ar) "منذ $it ساعة" else "${it}h ago" }
                m < 60 * 24 * 30 -> (m / 1440).let { if (ar) "منذ $it يوم" else "${it}d ago" }
                else -> (m / 43200).let { if (ar) "منذ $it شهر" else "${it}mo ago" }
            }
        }
    }
}

object ConversationStore {
    @Volatile private var repo: ConversationRepo? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun repo(ctx: Context): ConversationRepo =
        repo ?: ConversationRepo(File(ctx.applicationContext.filesDir, "chats.json")).also { repo = it }

    /** الكتابة على Thread خلفي عشان الواجهة تفضل سلسة */
    fun saveAsync(ctx: Context, c: Conversation) { val r = repo(ctx); io.execute { try { r.save(c) } catch (e: Throwable) { AppLog.eOnce("chats", "save failed", e) } } }
}
