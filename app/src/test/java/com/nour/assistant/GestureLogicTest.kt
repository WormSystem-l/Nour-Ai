package com.nour.assistant

import com.nour.assistant.gesture.*
import org.junit.Assert.*
import org.junit.Test

class GestureLogicTest {
    private val T = arrayOf(
        floatArrayOf(0f, 0f), floatArrayOf(-0.3f, -0.2f), floatArrayOf(-0.5f, -0.4f), floatArrayOf(-0.65f, -0.55f), floatArrayOf(-0.8f, -0.7f),
        floatArrayOf(-0.25f, -0.9f), floatArrayOf(-0.27f, -1.2f), floatArrayOf(-0.28f, -1.4f), floatArrayOf(-0.28f, -1.6f),
        floatArrayOf(0f, -1.0f), floatArrayOf(0f, -1.35f), floatArrayOf(0f, -1.58f), floatArrayOf(0f, -1.78f),
        floatArrayOf(0.22f, -0.95f), floatArrayOf(0.24f, -1.25f), floatArrayOf(0.25f, -1.45f), floatArrayOf(0.26f, -1.62f),
        floatArrayOf(0.42f, -0.85f), floatArrayOf(0.46f, -1.05f), floatArrayOf(0.48f, -1.2f), floatArrayOf(0.5f, -1.33f))

    private fun hand(thumb: FloatArray? = null, ox: Float = 320f, rot: Float = 0f): Hand {
        val xs = FloatArray(21); val ys = FloatArray(21)
        val c = Math.cos(rot.toDouble()).toFloat(); val sn = Math.sin(rot.toDouble()).toFloat()
        for (i in 0 until 21) {
            var px = T[i][0]; var py = T[i][1]
            if (i == 4 && thumb != null) { px = thumb[0]; py = thumb[1] }
            xs[i] = ox + (c * px - sn * py) * 100f; ys[i] = 400f + (sn * px + c * py) * 100f
        }
        return Hand(xs, ys, FloatArray(21), 480, 640)
    }

    private fun run(lg: GestureLogic, h: Hand?, n: Int, t0: Long): Pair<List<GEvent>, Long> {
        var t = t0; val ev = ArrayList<GEvent>()
        repeat(n) { ev += lg.onFrame(t, h); t += 66 }
        return ev to t
    }

    @Test fun pinchTapsOnce_andThumbScrolls() {
        val lg = GestureLogic()
        var (_, t) = run(lg, hand(), 10, 100_000)
        val tip = T[8]
        val pinch = hand(thumb = floatArrayOf(tip[0] + 0.05f, tip[1] + 0.05f))
        var r = run(lg, pinch, 12, t); t = r.second
        assertEquals(1, r.first.count { it is GEvent.Tap })
        r = run(lg, hand(), 30, t); t = r.second
        assertTrue(r.first.none { it is GEvent.Scroll })
        r = run(lg, hand(thumb = floatArrayOf(-0.8f, -1.15f)), 4, t); t = r.second
        assertTrue((r.first.single { it is GEvent.Scroll } as GEvent.Scroll).up)
        r = run(lg, hand(), 10, t); t = r.second
        r = run(lg, hand(thumb = floatArrayOf(-0.8f, -0.2f)), 4, t)
        assertFalse((r.first.single { it is GEvent.Scroll } as GEvent.Scroll).up)
    }

    @Test fun featuresIgnorePositionAndRotation() {
        val d = HandMath.featureDistance(HandMath.features(hand()), HandMath.features(hand(ox = 200f, rot = 0.6f)))
        assertTrue(d < 0.02f)
    }

    @Test fun anchorsCount() { assertEquals(4032, HandMath.palmAnchors().size) }
}
