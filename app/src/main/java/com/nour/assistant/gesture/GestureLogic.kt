package com.nour.assistant.gesture

import kotlin.math.abs

/** إيماءة مخصصة (من المستخدم): بصمة + معرّف */
class CustomPose(val id: String, val features: FloatArray)

sealed class GEvent {
    /** مؤشر (0..1 في صورة الكاميرا بعد التنعيم). scroll: +1 تمرير لتحت، -1 تمرير لفوق، 0 لا شيء */
    data class Cursor(val nx: Float, val ny: Float, val pinching: Boolean, val scroll: Int) : GEvent()
    data class Tap(val nx: Float, val ny: Float) : GEvent()
    /** down=true: المحتوى بيتحرك لتحت (الأصابع نزلت) */
    data class Scroll(val down: Boolean) : GEvent()
    data class Custom(val id: String) : GEvent()
    object Screenshot : GEvent()
    object HandLost : GEvent()
}

class GestureConfig(
    var pinchClose: Float = 0.30f,
    var pinchOpen: Float = 0.45f,
    /** أول ما الإبهام يقرّب لحد النسبة دي نثبّت المؤشر (عشان القرص مايحركوش) */
    var pinchApproach: Float = 0.70f,
    var fingerDownZone: Float = 0.30f,
    var fingerUpZone: Float = 0.20f,
    /** حساسية التمرير (أكبر = أسهل) */
    var sensitivity: Float = 1f,
    /** أقصى فرق بين الأصابع عشان نعتبرهم بيتحركوا "مع بعض" */
    var maxFingerSpread: Float = 0.55f,
    var scrollRepeatMs: Long = 550,
    var customHoldMs: Long = 450,
    var customCooldownMs: Long = 1200,
    var customThreshold: Float = 0.30f,
    var screenshotHoldMs: Long = 700,
    var screenshotCooldownMs: Long = 2500,
    var lostAfterMs: Long = 450
)

/**
 * ماكينة حالة الإيماءات:
 *  - قرص الإبهام والسبابة = ضغط (والمؤشر بيتثبّت أول ما الإبهام يقرّب)
 *  - الأصابع (الوسطى/البنصر/الخنصر) ينزلوا مع بعض = تمرير لتحت، يطلعوا = تمرير لفوق
 *  - علامة النصر ✌ (سبابة + وسطى بس) ثابتة = لقطة شاشة
 *  - إيماءات المستخدم المخصصة (بصمة) لازم تثبت [GestureConfig.customHoldMs]
 */
class GestureLogic(private val cfg: GestureConfig = GestureConfig()) {
    var customs: List<CustomPose> = emptyList()

    private val fx = OneEuro(); private val fy = OneEuro()
    private var pinching = false; private var pinchFrames = 0
    private var lastSeen = 0L
    private val anchor = FloatArray(2)

    // تثبيت المؤشر
    private var frozen = false; private var freezeX = 0f; private var freezeY = 0f
    private var lastX = 0.5f; private var lastY = 0.5f

    // تمرير بالأصابع
    private var baseline = Float.NaN
    private var zone = 0
    private var lastScrollAt = 0L; private var prevV = Float.NaN

    // علامة النصر (لقطة شاشة)
    private var victorySince = 0L; private var lastShotAt = -1_000_000L; private var victoryLatched = false

    private var candidate: String? = null; private var candidateSince = 0L; private var lastCustomAt = -1_000_000L
    private var customLatched = false

    var lastFeatures: FloatArray? = null
        private set

    fun reset() {
        fx.reset(); fy.reset(); pinching = false; pinchFrames = 0; frozen = false
        baseline = Float.NaN; zone = 0; prevV = Float.NaN
        victorySince = 0L; victoryLatched = false
        candidate = null; customLatched = false; lastFeatures = null
    }

    fun onFrame(t: Long, hand: Hand?): List<GEvent> {
        if (hand == null) {
            if (lastSeen != 0L && t - lastSeen > cfg.lostAfterMs) { reset(); lastSeen = 0L; return listOf(GEvent.HandLost) }
            return emptyList()
        }
        lastSeen = t
        val out = ArrayList<GEvent>(3)
        val w = hand.frameW.toFloat(); val h = hand.frameH.toFloat()

        HandMath.anchor(hand, anchor)
        val sx = fx.filter(anchor[0] / w, t); val sy = fy.filter(anchor[1] / h, t)
        val pr = HandMath.pinchRatio(hand)
        val feats = HandMath.features(hand)
        lastFeatures = feats
        val ext = HandMath.fingerExtensions(hand)

        val approaching = pr < cfg.pinchApproach

        // ---- علامة النصر: سبابة + وسطى مفرودين، بنصر + خنصر مثنيين
        val victory = !approaching && !pinching && ext[0] > 0.45f && ext[1] > 0.45f && ext[2] < 0.15f && ext[3] < 0.15f
        if (victory) {
            if (victorySince == 0L) victorySince = t
            if (!victoryLatched && t - victorySince >= cfg.screenshotHoldMs && t - lastShotAt >= cfg.screenshotCooldownMs) {
                victoryLatched = true; lastShotAt = t; out += GEvent.Screenshot
            }
        } else { victorySince = 0L; victoryLatched = false }

        // ---- تمرير بالأصابع (الأصابع لازم تتحرك مع بعض)
        var scrollMode = 0
        if (!victory && !pinching) {
            // الوسطى + البنصر + الخنصر (السبابة بره الحسبة عشان القرص مايعملش تمرير بالغلط)
            val used = floatArrayOf(ext[1], ext[2], ext[3])
            val v = used.average().toFloat()
            val spread = used.max() - used.min()
            // السبابة لازم تتحرك معاهم (غير وقت القرص) عشان "الإشارة بإصبع واحد" مايبقاش تمرير
            val indexOk = approaching || abs(ext[0] - v) <= cfg.maxFingerSpread
            if (spread <= cfg.maxFingerSpread && indexOk) {
                if (baseline.isNaN()) baseline = v
                val d = v - baseline
                val sens = cfg.sensitivity.coerceIn(0.4f, 2.5f)
                val z = when { d < -cfg.fingerDownZone / sens -> 1; d > cfg.fingerUpZone / sens -> -1; else -> 0 }
                val calm = !prevV.isNaN() && abs(v - prevV) < 0.04f
                if (z == 0 && calm) baseline += (v - baseline) * 0.03f   // المحايد بيتعلم لما اليد تهدى
                if (z != zone) {
                    zone = z
                    if (z != 0) { out += GEvent.Scroll(z > 0); lastScrollAt = t }
                } else if (z != 0 && t - lastScrollAt >= cfg.scrollRepeatMs) {
                    out += GEvent.Scroll(z > 0); lastScrollAt = t
                }
                prevV = v
            } else { zone = 0; prevV = Float.NaN }
            scrollMode = zone
        } else { zone = 0 }

        // ---- تثبيت المؤشر: لما الإبهام يقرّب للسبابة أو الأصابع بتتحرك
        val wantFreeze = approaching || pinching || zone != 0 || victory
        if (wantFreeze && !frozen) { frozen = true; freezeX = lastX; freezeY = lastY }
        if (!wantFreeze) { frozen = false }
        val cx = if (frozen) freezeX else sx
        val cy = if (frozen) freezeY else sy
        if (!frozen) { lastX = sx; lastY = sy }

        // ---- القرص = ضغط
        if (!pinching) {
            if (pr < cfg.pinchClose) pinchFrames++ else pinchFrames = 0
            if (pinchFrames >= 2) { pinching = true; out += GEvent.Tap(freezeX.takeIf { frozen } ?: cx, freezeY.takeIf { frozen } ?: cy) }
        } else if (pr > cfg.pinchOpen) { pinching = false; pinchFrames = 0 }
        out += GEvent.Cursor(cx, cy, pinching, scrollMode)

        // ---- إيماءات المستخدم
        if (customs.isNotEmpty() && !wantFreeze) matchCustom(t, feats, out)
        return out
    }

    private fun matchCustom(t: Long, f: FloatArray, out: MutableList<GEvent>) {
        var best: CustomPose? = null; var bd = Float.MAX_VALUE; var second = Float.MAX_VALUE
        for (c in customs) {
            val d = HandMath.featureDistance(f, c.features)
            if (d < bd) { second = bd; bd = d; best = c } else if (d < second) second = d
        }
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

        fun spread(list: List<FloatArray>): Float {
            val m = avgFeatures(list)
            return list.map { HandMath.featureDistance(it, m) }.average().toFloat()
        }
    }
}
