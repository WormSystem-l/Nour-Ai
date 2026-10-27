package com.nour.assistant.gesture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * تتبع اليد On-Device بنموذجين ONNX (MediaPipe):
 *  1) palm_detector.onnx  : بيلاقي الكف (192x192)
 *  2) hand_landmarks.onnx : 21 نقطة لليد (224x224)
 * بعد أول اكتشاف بنكمل بالتتبع من نقاط الإطار اللي فات (من غير كاشف الكف) = أسرع وأثبت.
 * الإضاءة الخافتة: تفتيح تلقائي (gamma) قبل النموذج. الصورة المغوّشة: بنتجاهل الإطار.
 */
class HandTracker(ctx: Context) : AutoCloseable {
    class Result(val hand: Hand?, val blurred: Boolean, val dark: Boolean)

    private val env = OrtEnvironment.getEnvironment()
    private val palm: OrtSession
    private val pose: OrtSession
    private val anchors = HandMath.palmAnchors()

    private val bmp192 = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    private val bmp224 = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
    private val cv192 = Canvas(bmp192)
    private val cv224 = Canvas(bmp224)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pix192 = IntArray(192 * 192)
    private val pix224 = IntArray(224 * 224)
    private val in192 = FloatArray(192 * 192 * 3)
    private val in224 = FloatArray(224 * 224 * 3)
    private val luma = IntArray(224 * 224)

    private var trackX: FloatArray? = null
    private var trackY: FloatArray? = null

    var palmThreshold = 0.5f
    var poseThreshold = 0.70f

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        fun load(name: String) = env.createSession(ctx.assets.open("models/$name").use { it.readBytes() }, opts)
        palm = load("palm_detector.onnx")
        pose = load("hand_landmarks.onnx")
    }

    override fun close() {
        runCatching { palm.close() }; runCatching { pose.close() }
    }

    fun reset() { trackX = null; trackY = null }

    fun process(frame: Bitmap): Result {
        var dark = false
        val tx = trackX; val ty = trackY
        if (tx != null && ty != null) {
            val r = runPose(frame, HandMath.roiFromPalm(tx, ty))
            dark = r.dark
            if (r.hand != null) { remember(r.hand); return Result(r.hand, false, dark) }
            if (r.blurred) return Result(null, true, dark)   // مغوّش: نحتفظ بالتتبع ونتجاهل الإطار
            reset()
        }
        val det = detectPalm(frame) ?: return Result(null, false, false)
        val r = runPose(frame, HandMath.roiFromPalm(det.first, det.second))
        if (r.hand != null) remember(r.hand)
        return Result(r.hand, r.blurred, r.dark)
    }

    private fun remember(h: Hand) {
        val ids = HandMath.PALM_IDS
        trackX = FloatArray(7) { h.xs[ids[it]] }
        trackY = FloatArray(7) { h.ys[ids[it]] }
    }

    // ---------------------------------------------------------------- كاشف الكف

    private fun detectPalm(frame: Bitmap): Pair<FloatArray, FloatArray>? {
        val w = frame.width; val h = frame.height
        val ratio = min(192f / h, 192f / w)
        val nw = (w * ratio).toInt(); val nh = (h * ratio).toInt()
        val left = (192 - nw) / 2; val top = (192 - nh) / 2
        cv192.drawColor(Color.BLACK)
        val m = Matrix().apply { setScale(ratio, ratio); postTranslate(left.toFloat(), top.toFloat()) }
        cv192.drawBitmap(frame, m, paint)
        bmp192.getPixels(pix192, 0, 192, 0, 0, 192, 192)
        fill(pix192, 192 * 192, in192, false)

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(in192), longArrayOf(1, 192, 192, 3))
        tensor.use {
            palm.run(mapOf("input_1" to tensor)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val raw = (res.get(0).value as Array<Array<FloatArray>>)[0]      // [2016][18]
                @Suppress("UNCHECKED_CAST")
                val sc = (res.get(1).value as Array<Array<FloatArray>>)[0]       // [2016][1]
                var best = -1; var bestLogit = -1e9f
                for (i in sc.indices) if (sc[i][0] > bestLogit) { bestLogit = sc[i][0]; best = i }
                if (best < 0) return null
                val score = 1f / (1f + exp(-bestLogit))
                if (score < palmThreshold) return null

                val scale = max(w, h).toFloat()
                val padX = left / ratio; val padY = top / ratio
                val ax = anchors[best * 2]; val ay = anchors[best * 2 + 1]
                val r = raw[best]
                val bw = r[2] / 192f * scale; val bh = r[3] / 192f * scale
                if (bw < 12f || bh < 12f) return null
                val xs = FloatArray(7); val ys = FloatArray(7)
                for (k in 0 until 7) {
                    xs[k] = (r[4 + 2 * k] / 192f + ax) * scale - padX
                    ys[k] = (r[5 + 2 * k] / 192f + ay) * scale - padY
                }
                return xs to ys
            }
        }
    }

    // ---------------------------------------------------------------- نقاط اليد

    private class PoseOut(val hand: Hand?, val blurred: Boolean, val dark: Boolean)

    private fun runPose(frame: Bitmap, roi: HandMath.Roi): PoseOut {
        val tmp = FloatArray(2)
        val pts = FloatArray(6)
        roi.toSource(0f, 0f, 224, tmp); pts[0] = tmp[0]; pts[1] = tmp[1]
        roi.toSource(224f, 0f, 224, tmp); pts[2] = tmp[0]; pts[3] = tmp[1]
        roi.toSource(0f, 224f, 224, tmp); pts[4] = tmp[0]; pts[5] = tmp[1]
        val outToSrc = Matrix()
        outToSrc.setPolyToPoly(floatArrayOf(0f, 0f, 224f, 0f, 0f, 224f), 0, pts, 0, 3)
        val srcToOut = Matrix()
        if (!outToSrc.invert(srcToOut)) return PoseOut(null, false, false)

        cv224.drawColor(Color.BLACK)
        cv224.drawBitmap(frame, srcToOut, paint)
        bmp224.getPixels(pix224, 0, 224, 0, 0, 224, 224)
        val stats = fill(pix224, 224 * 224, in224, true)
        val dark = stats.first < 70f
        if (stats.second < MIN_SHARPNESS) return PoseOut(null, true, dark)

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(in224), longArrayOf(1, 224, 224, 3))
        tensor.use {
            pose.run(mapOf("input_1" to tensor)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val lm = (res.get(0).value as Array<FloatArray>)[0]                 // 63
                @Suppress("UNCHECKED_CAST")
                val conf = (res.get(1).value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val hand = (res.get(2).value as Array<FloatArray>)[0][0]
                if (conf < poseThreshold) return PoseOut(null, false, dark)

                val xy = FloatArray(42)
                for (i in 0 until 21) { xy[i * 2] = lm[i * 3]; xy[i * 2 + 1] = lm[i * 3 + 1] }
                outToSrc.mapPoints(xy)
                val xs = FloatArray(21) { xy[it * 2] }
                val ys = FloatArray(21) { xy[it * 2 + 1] }
                val zs = FloatArray(21) { lm[it * 3 + 2] * roi.side / 224f }
                // لو اليد خرجت بره الصورة تمامًا نتجاهلها
                val cx = xs.average().toFloat(); val cy = ys.average().toFloat()
                if (cx < -frame.width * 0.2f || cx > frame.width * 1.2f || cy < -frame.height * 0.2f || cy > frame.height * 1.2f)
                    return PoseOut(null, false, dark)
                return PoseOut(Hand(xs, ys, zs, frame.width, frame.height, conf, hand), false, dark)
            }
        }
    }

    // ---------------------------------------------------------------- تحسين الصورة

    /**
     * بيملا مصفوفة الإدخال (RGB/255) مع تفتيح تلقائي (gamma) لو الصورة غامقة.
     * بيرجّع (متوسط الإضاءة 0..255، حدّة الصورة = تباين Laplacian).
     */
    private fun fill(pix: IntArray, n: Int, out: FloatArray, wantSharp: Boolean): Pair<Float, Float> {
        var sum = 0L
        var k = 0
        while (k < n) { val p = pix[k]; sum += ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10; k += 4 }
        val mean = sum.toFloat() / ((n + 3) / 4)
        val gamma = if (mean >= 110f) 1f else (ln(0.45) / ln(max(mean, 8f) / 255.0)).toFloat().coerceIn(0.35f, 1f)
        val lut = FloatArray(256) { ((it / 255f).pow(gamma)) }
        var o = 0
        for (i in 0 until n) {
            val p = pix[i]
            val r = lut[p shr 16 and 255]; val g = lut[p shr 8 and 255]; val b = lut[p and 255]
            out[o++] = r; out[o++] = g; out[o++] = b
            if (wantSharp) luma[i] = ((r * 0.3f + g * 0.6f + b * 0.1f) * 255f).toInt()
        }
        var sharp = Float.MAX_VALUE
        if (wantSharp) {
            val w = 224; var s = 0.0; var s2 = 0.0; var c = 0
            var y = 2
            while (y < w - 2) {
                var x = 2
                while (x < w - 2) {
                    val i = y * w + x
                    val l = 4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                    s += l; s2 += l.toDouble() * l; c++
                    x += 2
                }
                y += 2
            }
            val mu = s / c
            sharp = (s2 / c - mu * mu).toFloat()
        }
        return mean to sharp
    }

    companion object {
        /** أقل تباين Laplacian: أقل من كده الإطار مغوّش جدًا ونتجاهله */
        const val MIN_SHARPNESS = 6f
    }
}
