package com.nour.assistant.tools

import android.content.Context
import org.json.JSONArray

/** ذاكرة نور: حقائق قصيرة عنك بتتخزن على الجهاز بس (لو فعّلتها من الإعدادات) */
class MemoryStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("nour_memory", Context.MODE_PRIVATE)

    fun all(): List<String> = try {
        val a = JSONArray(p.getString("facts", "[]")); (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun write(list: List<String>) { p.edit().putString("facts", JSONArray(list).toString()).apply() }

    /** بيضيف حقيقة (بيتجاهل المكرر/الفاضي، وبيشيل الأقدم لو عدّى الحد). بيرجّع true لو اتضافت */
    fun add(fact: String): Boolean {
        val f = normalize(fact) ?: return false
        val cur = all()
        if (cur.any { sameFact(it, f) }) return false
        write((cur + f).takeLast(MAX))
        return true
    }

    fun remove(index: Int) { val c = all().toMutableList(); if (index in c.indices) { c.removeAt(index); write(c) } }
    fun replace(index: Int, fact: String) { val c = all().toMutableList(); val f = normalize(fact); if (index in c.indices && f != null) { c[index] = f; write(c) } }
    fun clear() = write(emptyList())

    /** نص جاهز للـ system prompt (محدود الحجم) */
    fun promptBlock(maxChars: Int = 1200): String {
        val sb = StringBuilder()
        for (f in all()) { if (sb.length + f.length + 3 > maxChars) break; sb.append("- ").append(f).append('\n') }
        return sb.toString().trim()
    }

    companion object {
        const val MAX = 60
        const val MAX_LEN = 160
        fun normalize(raw: String): String? {
            val t = raw.replace(Regex("\\s+"), " ").trim().trim('-', '•', ' ')
            if (t.length < 3) return null
            // مبنحفظش أسرار
            if (Regex("(?i)(password|passcode|api[_ -]?key|token|otp|pin\\b|كلمة (ال)?(سر|مرور)|رقم سري|\\b\\d{12,}\\b)").containsMatchIn(t)) return null
            return t.take(MAX_LEN)
        }
        fun sameFact(a: String, b: String) = a.trim().lowercase() == b.trim().lowercase()
    }
}
