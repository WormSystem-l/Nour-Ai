package com.nour.assistant.tools

/** مصدر استخدمه نور في رد معين (بحث أو صفحة قرأها) */
data class Source(val title: String, val url: String, val snippet: String = "", val query: String = "", val via: String = "") {
    val domain: String get() = try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }
}

/** أحداث الأدوات أثناء الرد (لعرض "جاري البحث" إلخ) */
sealed class ToolEvent {
    /** label: نص الحالة للعرض (فاضي = من غير حالة ظاهرة) */
    data class Start(val name: String) : ToolEvent()
    data class End(val name: String) : ToolEvent()
}
