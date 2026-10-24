package com.nour.assistant

import java.util.Locale

/** نصوص الكود اللي مش في resources: عربي لو لغة الموبايل عربي، غير كده إنجليزي */
object L {
    val isArabic: Boolean get() = Locale.getDefault().language == "ar"
    fun t(ar: String, en: String) = if (isArabic) ar else en

    /** لغة التعرف على الصوت حسب لغة الموبايل */
    fun speechLanguage(): String {
        val l = Locale.getDefault()
        return when {
            l.language == "ar" && l.country.isNullOrEmpty() -> "ar-EG"
            l.language == "ar" -> l.toLanguageTag()
            else -> l.toLanguageTag()
        }
    }
}
