package com.nour.assistant.wake

import com.nour.assistant.tools.AppResolver

/** بيقرر لو النص اللي Vosk سمعه هو نداء "يا نور" / "Hey Nour" / "Hi Nour" */
object WakeMatcher {
    private val AR_CALL = setOf("يا", "ياا", "هاي", "هي", "هاى", "هيي", "هاي")
    private val AR_NAME = setOf("نور", "نوور", "نورر")
    private val EN_CALL = setOf("hey", "hi", "hay", "hei")
    private val EN_NAME = setOf("nour", "noor", "nor", "nur", "newer")

    /** الجُمل اللي بنحطها كـ grammar لـ Vosk (بتحد التعرف على النداء بس = أسرع وأدق وأخف على البطارية) */
    val GRAMMAR_AR = listOf("يا نور", "يا نوور", "يانور", "هاي نور", "هي نور") + decoysAr() + "[unk]"
    val GRAMMAR_EN = listOf(
        "hey nour", "hi nour", "hey noor", "hi noor", "hey nor", "hi nor", "hey newer", "hi newer"
    ) + decoysEn() + "[unk]"

    /**
     * جمل "شبه النداء" بتتحط في الـ grammar عشان لما حد يقول "يا محمد" الموديل يلاقي له تفسير
     * صح ومايتجبرش يفسرها "يا نور" (ده كان سبب فتح التطبيق على "يا" لوحدها).
     */
    private fun decoysAr(): List<String> {
        val names = listOf("محمد", "احمد", "علي", "عمر", "مصطفى", "محمود", "يوسف", "حسن", "ماما", "بابا", "حبيبي", "صاحبي",
            "عم", "استاذ", "دكتور", "باشا", "نورا", "نورهان", "منور", "نهى", "نادر", "نبيل", "ندى", "سلمى", "ريم", "كريم", "هبه", "مريم")
        return listOf("يا", "هاي", "هي") .flatMap { c -> names.map { "$c $it" } } + listOf("يا الله", "يا رب", "يا ساتر")
    }

    private fun decoysEn(): List<String> {
        val names = listOf("mom", "man", "john", "guys", "google", "there", "nora", "noah", "norm", "nick", "mike", "sam", "alex", "dad", "bro", "siri", "alexa")
        return listOf("hey", "hi").flatMap { c -> names.map { "$c $it" } }
    }

    /** أقل ثقة لكلمة "نور" نفسها (لو Vosk بيدّي ثقة) */
    const val MIN_NAME_CONF = 0.70

    fun matches(textRaw: String): Boolean {
        if (textRaw.isBlank()) return false
        val tokens = textRaw.trim().lowercase().split(Regex("\\s+")).map { AppResolver.norm(it) }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 3) return false   // النداء لازم يكون جملة قصيرة لوحدها
        // "يانور" ملزوقة
        if (tokens.any { it == "يانور" || it == "يانوور" }) return true
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t in AR_NAME || t in EN_NAME) {
                val prev = tokens.getOrNull(i - 1) ?: continue
                if (prev in AR_CALL || prev in EN_CALL) return true
            }
        }
        return false
    }
}
