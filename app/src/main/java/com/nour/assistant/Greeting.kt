package com.nour.assistant

/** تحية حسب الوقت (للشاشة الرئيسية) */
object Greeting {
    fun forHour(h: Int, ar: Boolean): String = when (h) {
        in 5..11 -> if (ar) "صباح الخير" else "Good morning"
        in 12..16 -> if (ar) "نهارك سعيد" else "Good afternoon"
        in 17..22 -> if (ar) "مساء الخير" else "Good evening"
        else -> if (ar) "ليلة هادية" else "Good night"
    }

    fun subtitle(ar: Boolean): String =
        if (ar) "إزاي أقدر أساعدك النهارده؟" else "How can I help you today?"
}
