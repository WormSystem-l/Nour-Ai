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
}
