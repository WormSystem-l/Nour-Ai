package com.nour.assistant.gesture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** يد واحدة: 21 نقطة بالبكسل (في صورة العرض x العرض) + ثقة + يمين/شمال (0..1) */
class Hand(
    val xs: FloatArray, val ys: FloatArray, val zs: FloatArray,
    val frameW: Int, val frameH: Int,
    val conf: Float = 1f, val rightness: Float = 1f
)

/** رياضيات اليد (بدون Android عشان تتجرب لوحدها) */
object HandMath {
    /** نقاط الكف السبعة: المعصم + قواعد الأصابع + قاعدة الإبهام (نفس ترتيب كاشف الكف) */
    val PALM_IDS = intArrayOf(0, 5, 9, 13, 17, 1, 2)

    const val WRIST = 0
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9

    /** Anchors كاشف الكف (192x192): 2016 مركز (x,y) متطابقة مع المرجع بالظبط */
    fun palmAnchors(): FloatArray {
        val out = FloatArray(2016 * 2)
        var k = 0
        for ((stride, per) in listOf(8 to 2, 16 to 6)) {
            val g = 192 / stride
            for (y in 0 until g) for (x in 0 until g) repeat(per) {
                out[k++] = (x + 0.5f) / g
                out[k++] = (y + 0.5f) / g
            }
        }
        return out
    }

    /**
     * منطقة الاهتمام (ROI) لنموذج النقاط: مربع مدوّر حوالين اليد.
     * بيتحسب من 7 نقاط كف (من كاشف الكف أو من نقاط اليد في الإطار اللي فات).
     */
    class Roi(val rad: Float, val pivotX: Float, val pivotY: Float, val cx: Float, val cy: Float, val side: Float) {
        /** نقطة في صورة الـ crop (0..size) -> نقطة في الصورة الأصلية */
        fun toSource(u: Float, v: Float, size: Int, out: FloatArray) {
            val s = side / size
            val rx = cx + (u - size / 2f) * s
            val ry = cy + (v - size / 2f) * s
            val c = cos(rad); val sn = sin(rad)
            out[0] = pivotX + c * rx - sn * ry
            out[1] = pivotY + sn * rx + c * ry
        }
    }

    fun roiFromPalm(px: FloatArray, py: FloatArray): Roi {
        val p1x = px[0]; val p1y = py[0]; val p2x = px[2]; val p2y = py[2]
        var rad = (PI / 2 - atan2((-(p2y - p1y)).toDouble(), (p2x - p1x).toDouble()))
        rad -= 2 * PI * floor((rad + PI) / (2 * PI))
        val r = rad.toFloat()
        var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE
        for (i in px.indices) { mnx = min(mnx, px[i]); mxx = max(mxx, px[i]); mny = min(mny, py[i]); mxy = max(mxy, py[i]) }
        val pvx = (mnx + mxx) / 2; val pvy = (mny + mxy) / 2
        val c = cos(r); val sn = sin(r)
        var rmnx = Float.MAX_VALUE; var rmny = Float.MAX_VALUE; var rmxx = -Float.MAX_VALUE; var rmxy = -Float.MAX_VALUE
        for (i in px.indices) {
            val dx = px[i] - pvx; val dy = py[i] - pvy
            val x = c * dx + sn * dy
            val y = -sn * dx + c * dy
            rmnx = min(rmnx, x); rmxx = max(rmxx, x); rmny = min(rmny, y); rmxy = max(rmxy, y)
        }
        val w = rmxx - rmnx; val h = rmxy - rmny
        return Roi(r, pvx, pvy, (rmnx + rmxx) / 2, (rmny + rmxy) / 2 - 0.4f * h, 3f * max(w, h))
    }

    fun dist(h: Hand, a: Int, b: Int) = hypot(h.xs[a] - h.xs[b], h.ys[a] - h.ys[b])

    /** حجم الكف (المعصم -> قاعدة الوسطى) بالبكسل */
    fun palmSize(h: Hand) = max(dist(h, WRIST, MIDDLE_MCP), 1f)

    /** نسبة المسافة بين طرف الإبهام وطرف السبابة لحجم الكف (صغيرة = قرص) */
    fun pinchRatio(h: Hand) = dist(h, THUMB_TIP, INDEX_TIP) / palmSize(h)

    /** موضع الإبهام على محور اليد (من المعصم لأصابع) نسبةً لقاعدة السبابة، بوحدات حجم الكف: أعلى = موجب */
    fun thumbAxis(h: Hand): Float {
        val ux = h.xs[MIDDLE_MCP] - h.xs[WRIST]; val uy = h.ys[MIDDLE_MCP] - h.ys[WRIST]
        val n = max(hypot(ux, uy), 1f)
        val dx = h.xs[THUMB_TIP] - h.xs[INDEX_MCP]; val dy = h.ys[THUMB_TIP] - h.ys[INDEX_MCP]
        return (dx * ux + dy * uy) / n / n
    }

    /**
     * بصمة وضعية اليد (42 رقم): النقاط بالنسبة للمعصم، مدوّرة بحيث المعصم->الوسطى لفوق،
     * ومقسومة على حجم الكف، ومتعكسة لو اليد شمال. فمش بتتأثر بالمكان ولا الحجم ولا الدوران.
     */
    fun features(h: Hand): FloatArray {
        val ux = h.xs[MIDDLE_MCP] - h.xs[WRIST]; val uy = h.ys[MIDDLE_MCP] - h.ys[WRIST]
        val n = max(hypot(ux, uy), 1f)
        val cx = ux / n; val cy = uy / n         // محور اليد
        val mirror = if (h.rightness < 0.5f) -1f else 1f
        val out = FloatArray(42)
        for (i in 0 until 21) {
            val dx = h.xs[i] - h.xs[WRIST]; val dy = h.ys[i] - h.ys[WRIST]
            val along = (dx * cx + dy * cy) / n      // طول على المحور
            val across = (dx * -cy + dy * cx) / n    // عرض
            out[i * 2] = across * mirror
            out[i * 2 + 1] = along
        }
        return out
    }

    /** متوسط المسافة بين بصمتين (بوحدات حجم الكف) */
    fun featureDistance(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until 21) s += hypot(a[i * 2] - b[i * 2], a[i * 2 + 1] - b[i * 2 + 1])
        return s / 21f
    }
}

/** One Euro Filter: تنعيم حركة المؤشر (ثابت لما اليد ساكنة وسريع لما تتحرك) */
class OneEuro(private val minCutoff: Float = 1.2f, private val beta: Float = 0.02f, private val dCutoff: Float = 1f) {
    private var xPrev = 0f; private var dxPrev = 0f; private var tPrev = 0L; private var init = false

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, tMs: Long): Float {
        if (!init) { init = true; xPrev = x; dxPrev = 0f; tPrev = tMs; return x }
        val dt = max((tMs - tPrev) / 1000f, 1e-3f)
        tPrev = tMs
        val dx = (x - xPrev) / dt
        val ad = alpha(dCutoff, dt)
        val dxHat = ad * dx + (1 - ad) * dxPrev
        dxPrev = dxHat
        val cutoff = minCutoff + beta * kotlin.math.abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * xPrev
        xPrev = xHat
        return xHat
    }

    fun reset() { init = false }
}
