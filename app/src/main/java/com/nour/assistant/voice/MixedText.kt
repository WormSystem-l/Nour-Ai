package com.nour.assistant.voice

/** مساعد اختيار أحسن نتيجة لما المستخدم بيخلط عربي وإنجليزي في نفس الجملة */
object MixedText {
    fun hasArabic(s: String) = s.any { it in '\u0600'..'\u06FF' }
    fun hasLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }
    fun isMixed(s: String) = hasArabic(s) && hasLatin(s)

    /**
     * جوجل بيرجّع كذا نتيجة بديلة. لو فيها نتيجة فيها عربي وإنجليزي مع بعض بناخدها
     * (الكلمة الإنجليزية بحروفها الإنجليزية)، غير كده الأولى (الأعلى ثقة).
     */
    fun pickBest(results: List<String>): String {
        val r = results.map { it.trim() }.filter { it.isNotEmpty() }
        if (r.isEmpty()) return ""
        return r.firstOrNull { isMixed(it) } ?: r.first()
    }
}
