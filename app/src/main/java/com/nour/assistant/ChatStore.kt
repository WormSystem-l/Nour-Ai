package com.nour.assistant

/** المحادثة مشتركة بين النافذة الصغيرة والتطبيق الكامل (نفس الـ process) */
object ChatStore {
    val messages: MutableList<Msg> = java.util.Collections.synchronizedList(mutableListOf())
    fun history(): List<Msg> = synchronized(messages) { messages.toList() }
    fun clear() = messages.clear()
}
