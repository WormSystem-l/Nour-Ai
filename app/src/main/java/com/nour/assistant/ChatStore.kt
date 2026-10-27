package com.nour.assistant

import android.content.Context

/**
 * المحادثة الحالية: مشتركة بين النافذة الصغيرة والتطبيق الكامل (نفس الـ process).
 * بتتحفظ تلقائياً في سجل المحادثات (القايمة الجانبية).
 */
object ChatStore {
    val messages: MutableList<Msg> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var currentId: String = ProviderStore.newId()
        private set

    fun history(): List<Msg> = synchronized(messages) { messages.toList() }
    fun clear() = messages.clear()

    /** محادثة جديدة فاضية */
    fun startNew() { messages.clear(); currentId = ProviderStore.newId() }

    /** فتح محادثة قديمة */
    fun open(ctx: Context, id: String): Boolean {
        val c = ConversationStore.repo(ctx).load(id) ?: return false
        messages.clear(); messages.addAll(c.messages); currentId = c.id
        return true
    }

    /** حفظ المحادثة الحالية (من غير علامة التفكير المؤقتة) */
    fun persist(ctx: Context) {
        val real = history().filter { !(it.role == "assistant" && (it.content == "…" || it.content.isBlank())) }
        val firstUser = real.firstOrNull { it.role == "user" } ?: return
        ConversationStore.saveAsync(ctx, Conversation(currentId, ConversationRepo.titleFrom(firstUser.content), System.currentTimeMillis(), real.toMutableList()))
    }
}
