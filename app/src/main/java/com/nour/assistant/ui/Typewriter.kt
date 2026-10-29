package com.nour.assistant.ui

import android.os.Handler
import android.os.Looper

/**
 * بيعرض النص كلمة كلمة بشكل ناعم مهما كان الرد بيوصل على دفعات كبيرة أو صغيرة.
 * [feed] ممكن تتنادى من أي Thread. [onText] و [onDone] بيتنادوا على الـ Main Thread.
 */
class Typewriter(
    private val onText: (String) -> Unit,
    private val onDone: (String) -> Unit = {},
    private val tickMs: Long = 32L
) {
    private val handler = Handler(Looper.getMainLooper())
    private val target = StringBuilder()
    @Volatile private var finished = false
    private var shown = 0
    private var running = false
    private var cancelled = false

    fun feed(delta: String) {
        synchronized(target) { target.append(delta) }
        handler.post { ensureRunning() }
    }

    /** النص الكامل وصل: كمّل العرض وبعدين نادِ onDone */
    fun finish(finalText: String? = null) {
        if (finalText != null) synchronized(target) { if (target.isEmpty()) target.append(finalText) }
        finished = true
        handler.post { ensureRunning() }
    }

    fun cancel() { cancelled = true; handler.removeCallbacksAndMessages(null) }

    private fun ensureRunning() {
        if (running || cancelled) return
        running = true
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (cancelled) return
            val full = synchronized(target) { target.toString() }
            if (shown < full.length) {
                shown = nextIndex(full, shown)
                onText(full.substring(0, shown))
            }
            if (shown >= full.length) {
                running = false
                if (finished) onDone(full)
                return
            }
            handler.postDelayed(this, tickMs)
        }
    }

    companion object {
        /** الحرف اللي هنعرض لحد عنده: كلمة واحدة، أو أكتر لو فيه نص كتير متأخر */
        fun nextIndex(full: String, shown: Int): Int {
            if (shown >= full.length) return full.length
            val backlog = full.length - shown
            val words = when { backlog > 400 -> 6; backlog > 160 -> 3; backlog > 60 -> 2; else -> 1 }
            var i = shown
            repeat(words) {
                while (i < full.length && full[i].isWhitespace()) i++
                while (i < full.length && !full[i].isWhitespace()) i++
            }
            return i.coerceAtMost(full.length)
        }
    }
}
