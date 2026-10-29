package com.nour.assistant.wake

import kotlin.math.max

/** كشف وجود كلام (VAD) بمعدل ضوضاء متغير: بيسمح بالتقاط الصوت البعيد وبيوفّر بطارية (مفيش تحليل في الصمت) */
class Vad(private val ratio: Float, private val minRms: Float = 90f) {
    var noise = 150f
        private set

    /** rms: شدة الصوت في القطعة الحالية. بيرجّع true لو فيها كلام */
    fun update(rms: Float): Boolean {
        val voiced = rms > max(noise * ratio, minRms)
        if (!voiced) noise = if (rms < noise) noise * 0.8f + rms * 0.2f else noise * 0.997f + rms * 0.003f
        noise = max(noise, 20f)
        return voiced
    }
}

/** تكبير تلقائي للصوت الضعيف (البعيد) قبل Vosk */
class Agc(private val target: Float = 2200f, private val maxGain: Float = 10f) {
    private var level = 700f
    fun gain(rms: Float, voiced: Boolean): Float {
        if (voiced) level = level * 0.5f + rms * 0.5f
        return (target / max(level, 1f)).coerceIn(1f, maxGain)
    }

    companion object {
        fun apply(src: ShortArray, n: Int, gain: Float, dst: ShortArray) {
            for (i in 0 until n) dst[i] = (src[i] * gain).toInt().coerceIn(-32767, 32767).toShort()
        }

        fun rms(b: ShortArray, n: Int): Float {
            if (n <= 0) return 0f
            var s = 0.0
            for (i in 0 until n) s += b[i].toDouble() * b[i]
            return Math.sqrt(s / n).toFloat()
        }
    }
}

/** بيقسّم الصوت لـ "جُمل" حسب الـ VAD: START أول ما الكلام يبدأ، END بعد ما يسكت [hangoverMs] */
class Segmenter(private val vad: Vad, private val hangoverMs: Long = 550, private val maxMs: Long = 4000) {
    enum class Ev { IDLE, START, CONT, END }

    var speaking = false
        private set
    var voicedChunks = 0
        private set
    private var start = 0L
    private var lastVoice = 0L

    fun step(rms: Float, now: Long): Ev {
        val voiced = vad.update(rms)
        if (!speaking) {
            if (!voiced) return Ev.IDLE
            speaking = true; voicedChunks = 1; start = now; lastVoice = now
            return Ev.START
        }
        if (voiced) { voicedChunks++; lastVoice = now }
        if (now - lastVoice > hangoverMs || now - start > maxMs) { speaking = false; return Ev.END }
        return Ev.CONT
    }
}

/** مستويات حساسية النداء */
class WakeTuning(val arConf: Float, val enConf: Float, val vadRatio: Float) {
    companion object {
        /** 0 = منخفضة (أقل تفعيل بالغلط)، 1 = متوسطة، 2 = عالية (تسمع من بعيد) */
        fun level(l: Int) = when (l) {
            0 -> WakeTuning(0.65f, 0.75f, 3.0f)
            1 -> WakeTuning(0.50f, 0.70f, 2.4f)
            else -> WakeTuning(0.35f, 0.60f, 1.9f)
        }
    }
}
