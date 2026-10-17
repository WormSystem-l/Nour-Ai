package com.nour.assistant.gesture

import kotlin.math.abs

/** إيماءة مخصصة (من المستخدم): بصمة + معرّف */
class CustomPose(val id: String, val features: FloatArray)

sealed class GEvent {
    /** مؤشر (إحداثيات 0..1 في صورة الكاميرا بعد التنعيم) */
    data class Cursor(val nx: Float, val ny: Float, val pinching: Boolean) : GEvent()
    data class Tap(val nx: Float, val ny: Float) : GEvent()
    /** up=true: الإبهام لفوق */
    data class Scroll(val up: Boolean) : GEvent()
    data class Custom(val id: String) : GEvent()
    object HandLost : GEvent()
}

class GestureConfig(
    var pinchClose: Float = 0.30f,
    var pinchOpen: Float = 0.45f,
    var thumbZone: Float = 0.30f,
    var scrollRepeatMs: Long = 550,
    var customHoldMs: Long = 450,
    var customCooldownMs: Long = 1200,
    var customThreshold: Float = 0.30f,
    var lostAfterMs: Long = 450
)

/**
 * ماكينة حالة الإيماءات:
 *  - قرص الإبهام والسبابة = ضغط (Tap) في مكان المؤشر قبل القرص بلحظة (عشان القرص مايحركش المؤشر)
 *  - الإبهام لوحده لفوق/لتحت = تمرير (Scroll)، ويتكرر لو فضل مرفوع
 *  - إيماءات المستخدم المخصصة: لازم تثبت [GestureConfig.customHoldMs]
 */
class GestureLogic(private val cfg: GestureConfig = GestureConfig()) {
    var customs: List<CustomPose> = emptyList()

    private val fx = OneEuro(); private val fy = OneEuro()
    private var pinching = false; private var pinchFrames = 0
    private var lastSeen = 0L
    private class Sample(val t: Long, val x: Float, val y: Float)
    private val history = ArrayDeque<Sample>()

    private var baseline = Float.NaN
    private var zone = 0                 // +1 فوق ، -1 تحت ، 0 محايد
    private var zoneSince = 0L; private var lastScrollAt = 0L

    private var candidate: String? = null; private var candidateSince = 0L; private var lastCustomAt = -1_000_000L
    private var customLatched = false

    /** آخر بصمة (للتسجيل) */
    var lastFeatures: FloatArray? = null
        private set

    fun reset() {
        fx.reset(); fy.reset(); pinching = false; pinchFrames = 0; history.clear()
        baseline = Float.NaN; zone = 0; candidate = null; customLatched = false; lastFeatures = null
    }

    fun onFrame(t: Long, hand: Hand?): List<GEvent> {
        if (hand == null) {
            if (lastSeen != 0L && t - lastSeen > cfg.lostAfterMs) { reset(); lastSeen = 0L; return listOf(GEvent.HandLost) }
            return emptyList()
        }
        lastSeen = t
        val out = ArrayList<GEvent>(2)
        val w = hand.frameW.toFloat(); val h = hand.frameH.toFloat()

        // المؤشر = طرف السبابة منعّم
        val rawX = hand.xs[HandMath.INDEX_TIP] / w; val rawY = hand.ys[HandMath.INDEX_TIP] / h
        val sx = fx.filter(rawX, t); val sy = fy.filter(rawY, t)
        history.addLast(Sample(t, sx, sy))
        while (history.isNotEmpty() && t - history.first().t > 700) history.removeFirst()

        val pr = HandMath.pinchRatio(hand)
        val feats = HandMath.features(hand)
        lastFeatures = feats

        // ---- القرص
        if (!pinching) {
            if (pr < cfg.pinchClose) pinchFrames++ else pinchFrames = 0
            if (pinchFrames >= 2) {
                pinching = true
                val lockT = history.filter { t - it.t in 150..450 }
                val p = if (lockT.isNotEmpty()) Pair(lockT.map { it.x }.average().toFloat(), lockT.map { it.y }.average().toFloat())
                else history.firstOrNull()?.let { Pair(it.x, it.y) } ?: Pair(sx, sy)
                out += GEvent.Tap(p.first, p.second)
            }
        } else if (pr > cfg.pinchOpen) { pinching = false; pinchFrames = 0 }
        out += GEvent.Cursor(sx, sy, pinching)

        // ---- تمرير بالإبهام (بس لو مفيش قرص والإبهام بعيد عن السبابة)
        if (!pinching && pr > cfg.pinchOpen) {
            val v = HandMath.thumbAxis(hand)
            if (baseline.isNaN()) baseline = v
            val d = v - baseline
            val z = when { d > cfg.thumbZone -> 1; d < -cfg.thumbZone -> -1; else -> 0 }
            if (z == 0) baseline += (v - baseline) * 0.01f   // المحايد بيتعلم ببطء
            if (z != zone) {
                zone = z; zoneSince = t
                if (z != 0) { out += GEvent.Scroll(z > 0); lastScrollAt = t }
            } else if (z != 0 && t - lastScrollAt >= cfg.scrollRepeatMs) {
                out += GEvent.Scroll(z > 0); lastScrollAt = t
            }
        } else zone = 0

        // ---- إيماءات المستخدم
        if (customs.isNotEmpty() && !pinching && zone == 0) matchCustom(t, feats, out)
        return out
    }

    private fun matchCustom(t: Long, f: FloatArray, out: MutableList<GEvent>) {
        var best: CustomPose? = null; var bd = Float.MAX_VALUE; var second = Float.MAX_VALUE
        for (c in customs) {
            val d = HandMath.featureDistance(f, c.features)
            if (d < bd) { second = bd; bd = d; best = c } else if (d < second) second = d
        }
        // لازم تكون قريبة كفاية وواضحة عن اللي بعدها
        val ok = best != null && bd <= cfg.customThreshold && (second - bd) > 0.04f
        if (!ok) { candidate = null; customLatched = false; return }
        if (candidate != best!!.id) { candidate = best.id; candidateSince = t; return }
        if (!customLatched && t - candidateSince >= cfg.customHoldMs && t - lastCustomAt >= cfg.customCooldownMs) {
            customLatched = true; lastCustomAt = t
            out += GEvent.Custom(best.id)
        }
    }

    companion object {
        fun avgFeatures(list: List<FloatArray>): FloatArray {
            val o = FloatArray(42)
            for (f in list) for (i in 0 until 42) o[i] += f[i] / list.size
            return o
        }

        /** قياس ثبات التسجيل: متوسط بُعد العينات عن المتوسط (أقل = أثبت) */
        fun spread(list: List<FloatArray>): Float {
            val m = avgFeatures(list)
            return list.map { HandMath.featureDistance(it, m) }.average().toFloat()
        }

        fun absf(x: Float) = abs(x)
    }
}
