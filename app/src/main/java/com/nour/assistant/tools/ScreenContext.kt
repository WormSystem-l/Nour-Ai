package com.nour.assistant.tools

import android.os.SystemClock

/** آخر محتوى شاشة/صفحة وصلنا من نظام المساعد (AssistStructure). On-device، بيتحذف بعد 3 دقايق */
object ScreenContext {
    @Volatile var text: String = ""
    @Volatile var url: String? = null
    @Volatile var pkg: String? = null
    @Volatile private var at = 0L

    fun set(text: String, url: String?, pkg: String?) {
        this.text = text; this.url = url; this.pkg = pkg; at = SystemClock.elapsedRealtime()
    }

    fun clear() { text = ""; url = null; pkg = null }

    fun has(): Boolean = text.isNotBlank() && SystemClock.elapsedRealtime() - at < 3 * 60_000

    /** نص جاهز للإرسال للموديل، محدود الحجم */
    fun forModel(max: Int = 5000): String {
        val head = buildString {
            pkg?.let { append("app: ").append(it).append('\n') }
            url?.let { append("url: ").append(it).append('\n') }
        }
        return head + WebText.clip(text, max)
    }
}
