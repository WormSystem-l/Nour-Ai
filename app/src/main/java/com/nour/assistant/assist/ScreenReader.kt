package com.nour.assistant.assist

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View

/** بيستخلص النص الظاهر من AssistStructure (محتوى الشاشة الحالية) بحجم صغير */
object ScreenReader {
    private const val MAX_NODES = 2500
    private const val MAX_CHARS = 9000

    fun extract(structure: AssistStructure?, selfPackage: String): String {
        if (structure == null) return ""
        val seen = LinkedHashSet<String>()
        var nodes = 0
        fun walk(n: AssistStructure.ViewNode, depth: Int) {
            if (nodes++ > MAX_NODES || depth > 45) return
            if (n.visibility != View.VISIBLE) return
            if (n.isAssistBlocked) return
            val pwd = n.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            if (!pwd) {
                val t = n.text?.toString()?.trim().orEmpty()
                val c = n.contentDescription?.toString()?.trim().orEmpty()
                val s = if (t.length >= 2) t else if (c.length >= 3) c else ""
                if (s.isNotEmpty()) seen.add(s.take(600))
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        for (w in 0 until structure.windowNodeCount) {
            val win = structure.getWindowNodeAt(w)
            if (structure.activityComponent?.packageName == selfPackage) continue
            walk(win.rootViewNode, 0)
        }
        val sb = StringBuilder()
        for (s in seen) { if (sb.length + s.length > MAX_CHARS) break; sb.append(s).append('\n') }
        return sb.toString().trim()
    }

    private val URL_IDS = listOf("url_bar", "url_view", "urlbar", "location_bar_edit_text", "mozac_browser_toolbar_url_view", "url_text", "omnibox")

    /** لو المتصفح ماداناش رابط الصفحة (AssistContent.webUri) نحاول نجيبه من شريط العنوان */
    fun guessUrl(structure: AssistStructure?, selfPackage: String): String? {
        if (structure == null) return null
        var found: String? = null
        fun walk(n: AssistStructure.ViewNode, depth: Int) {
            if (found != null || depth > 45) return
            val id = n.idEntry?.lowercase().orEmpty()
            if (URL_IDS.any { id.contains(it) }) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (looksLikeUrl(t)) { found = normalizeUrl(t); return }
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        for (w in 0 until structure.windowNodeCount) {
            if (structure.activityComponent?.packageName == selfPackage) continue
            walk(structure.getWindowNodeAt(w).rootViewNode, 0)
        }
        return found
    }

    fun looksLikeUrl(t: String): Boolean {
        if (t.isBlank() || t.contains(' ') || t.length > 2048) return false
        return Regex("^(https?://)?([\\w-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(t)
    }

    fun normalizeUrl(t: String): String = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}
