package com.nour.assistant

import com.nour.assistant.gesture.*
import org.junit.Assert.*
import org.junit.Test

class GestureLogicTest {
    private val MCP = arrayOf(floatArrayOf(-0.25f, -0.9f), floatArrayOf(0f, -1.0f), floatArrayOf(0.22f, -0.95f), floatArrayOf(0.42f, -0.85f))
    private val LEN = floatArrayOf(0.7f, 0.78f, 0.67f, 0.5f)

    /** ext: امتداد كل إصبع (1 = مفرود، -0.4 = نازل). */
    private fun hand(ext: FloatArray = floatArrayOf(1f, 1f, 1f, 1f), thumb: FloatArray = floatArrayOf(-0.8f, -0.7f), ox: Float = 240f, rot: Float = 0f): Hand {
        val fin = ArrayList<FloatArray>()
        fin += floatArrayOf(0f, 0f); fin += floatArrayOf(-0.3f, -0.2f); fin += floatArrayOf(-0.5f, -0.4f); fin += floatArrayOf(-0.65f, -0.55f); fin += thumb
        for (f in 0 until 4) {
            fin += MCP[f]
            for (k in 1..3) fin += floatArrayOf(MCP[f][0], MCP[f][1] - LEN[f] * ext[f] * k / 3f)
        }
        val c = Math.cos(rot.toDouble()).toFloat(); val sn = Math.sin(rot.toDouble()).toFloat()
        val xs = FloatArray(21) { ox + (c * fin[it][0] - sn * fin[it][1]) * 100f }
        val ys = FloatArray(21) { 450f + (sn * fin[it][0] + c * fin[it][1]) * 100f }
        return Hand(xs, ys, FloatArray(21), 480, 640)
    }

    private class Runner(val lg: GestureLogic) {
        var t = 100_000L
        val ev = ArrayList<GEvent>()
        fun run(h: Hand?, n: Int) { repeat(n) { ev += lg.onFrame(t, h); t += 33 } }
        inline fun <reified T : GEvent> all() = ev.filterIsInstance<T>()
    }

    @Test fun pinch_tapsOnce_andCursorStaysFrozen() {
        val r = Runner(GestureLogic())
        r.run(hand(), 30)
        val tip = floatArrayOf(-0.28f, -1.6f)
        for (i in 1..10) {
            val a = i / 10f
            r.run(hand(thumb = floatArrayOf(-0.8f + (tip[0] + 0.05f + 0.8f) * a, -0.7f + (tip[1] + 0.05f + 0.7f) * a), ext = floatArrayOf(1f - 0.12f * a, 1f, 1f, 1f)), 1)
        }
        r.run(hand(thumb = floatArrayOf(tip[0] + 0.05f, tip[1] + 0.05f), ext = floatArrayOf(0.88f, 1f, 1f, 1f)), 6)
        assertEquals(1, r.all<GEvent.Tap>().size)
        val ys = r.all<GEvent.Cursor>().takeLast(16).map { it.ny }
        assertTrue((ys.max() - ys.min()) * 640f < 6f)
    }

    @Test fun fingersDownTogether_scrollDown_andUpTogether_scrollUp() {
        val r = Runner(GestureLogic())
        r.run(hand(), 40)
        assertTrue(r.all<GEvent.Scroll>().isEmpty())
        r.run(hand(ext = floatArrayOf(-0.4f, -0.4f, -0.4f, -0.4f)), 6)
        assertTrue(r.all<GEvent.Scroll>().first().down)
        r.run(hand(), 40)
        assertTrue(r.all<GEvent.Scroll>().all { it.down })   // الرجوع للوضع العادي مش تمرير عكسي

        val r2 = Runner(GestureLogic())
        r2.run(hand(ext = floatArrayOf(0.45f, 0.45f, 0.45f, 0.45f)), 60)
        r2.run(hand(ext = floatArrayOf(1f, 1f, 1f, 1f)), 6)
        assertTrue(r2.all<GEvent.Scroll>().isNotEmpty() && !r2.all<GEvent.Scroll>().first().down)
    }

    @Test fun victorySign_screenshotOnce_noScroll() {
        val r = Runner(GestureLogic())
        r.run(hand(), 30)
        r.run(hand(ext = floatArrayOf(1f, 1f, -0.4f, -0.4f)), 40)
        assertEquals(1, r.ev.count { it === GEvent.Screenshot })
        assertTrue(r.all<GEvent.Scroll>().isEmpty())
    }

    @Test fun pointingWithOneFinger_isNotScroll() {
        val r = Runner(GestureLogic())
        r.run(hand(), 30); r.run(hand(ext = floatArrayOf(1f, -0.4f, -0.4f, -0.4f)), 40)
        assertTrue(r.all<GEvent.Scroll>().isEmpty())
        assertTrue(r.ev.none { it === GEvent.Screenshot })
    }

    @Test fun featuresIgnorePositionAndRotation() {
        assertTrue(HandMath.featureDistance(HandMath.features(hand()), HandMath.features(hand(ox = 150f, rot = 0.6f))) < 0.02f)
    }

    @Test fun customPose_firesOnce_afterHold() {
        val lg = GestureLogic()
        val peace3 = floatArrayOf(1f, 1f, 1f, -0.4f)
        lg.customs = listOf(CustomPose("p3", HandMath.features(hand(ext = peace3))), CustomPose("fist", HandMath.features(hand(ext = floatArrayOf(-0.4f, -0.4f, -0.4f, -0.4f)))))
        val r = Runner(lg)
        r.run(hand(ext = peace3, ox = 260f), 25)
        assertEquals(listOf("p3"), r.all<GEvent.Custom>().map { it.id })
    }

    @Test fun anchorsCount() { assertEquals(4032, HandMath.palmAnchors().size) }
}
