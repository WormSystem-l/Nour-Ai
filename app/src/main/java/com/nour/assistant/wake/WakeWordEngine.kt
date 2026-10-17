package com.nour.assistant.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * بيسمع المايك On-Device بـ Vosk ويدور على "يا نور" / "Hey Nour" / "Hi Nour" بس (grammar محدود).
 * مفيش أي صوت بيتبعت لأي حتة. [start]/[stop] بيمسكوا ويسيبوا المايك فعلاً.
 */
class WakeWordEngine(private val ctx: Context, private val onWake: () -> Unit) {

    private var arModel: Model? = null
    private var enModel: Model? = null
    private var arRec: Recognizer? = null
    private var enRec: Recognizer? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    /** رقم الجولة: بيمنع Thread قديم يكمل شغل بعد stop() ثم start() بسرعة (كان بيوقف الاستماع) */
    @Volatile private var gen = 0
    /** audio session بتاع تسجيلنا، عشان نفرّقه عن تسجيل التطبيقات التانية */
    @Volatile var sessionId: Int = -1
        private set
    @Volatile private var lastWake = 0L

    val isRunning get() = running

    fun start() {
        if (running) return
        running = true
        val my = ++gen
        thread = Thread({ loop(my) }, "nour-wake").also { it.isDaemon = true; it.start() }
    }

    /** بيسيب المايك (بيستنى الـ Thread يخلص لحد ثانية) */
    fun stop() {
        running = false
        gen++
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) try { t.join(1000) } catch (_: InterruptedException) {}
    }

    /** بيفرّغ الموديلات من الرام */
    fun release() {
        stop()
        runCatching { arRec?.close() }; runCatching { enRec?.close() }
        runCatching { arModel?.close() }; runCatching { enModel?.close() }
        arRec = null; enRec = null; arModel = null; enModel = null
    }

    private fun load(): Boolean {
        if (arRec != null) return true
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val ar = WakeModels.path(ctx, WakeModels.AR) ?: return false
            arModel = Model(ar.absolutePath)
            arRec = Recognizer(arModel, SAMPLE_RATE.toFloat(), grammar(WakeMatcher.GRAMMAR_AR)).also { it.setWords(true) }
            WakeModels.path(ctx, WakeModels.EN)?.let { p ->
                runCatching {
                    enModel = Model(p.absolutePath)
                    enRec = Recognizer(enModel, SAMPLE_RATE.toFloat(), grammar(WakeMatcher.GRAMMAR_EN)).also { it.setWords(true) }
                }
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed", e)
            false
        }
    }

    private fun grammar(l: List<String>) = JSONArray(l).toString()

    @SuppressLint("MissingPermission")
    private fun loop(my: Int) {
        var rec: AudioRecord? = null
        val alive = { running && gen == my }
        try {
            if (!load()) { if (gen == my) running = false; return }
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { if (gen == my) running = false; return }
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { if (gen == my) running = false; return }
            if (!alive()) return
            sessionId = rec.audioSessionId
            arRec?.reset(); enRec?.reset()
            rec.startRecording()
            val buf = ShortArray(SAMPLE_RATE / 4) // 250ms
            val ring = IntArray(12) // أعلى شدة صوت في آخر ~3 ثواني
            var pos = 0
            while (alive()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) break else continue }
                if (!alive()) break
                ring[pos++ % ring.size] = rms(buf, n)
                var hit = feed(arRec, buf, n)
                if (!hit) hit = feed(enRec, buf, n)
                // hit معناها: جملة خلصت وفيها النداء بثقة كافية
                if (hit && ring.max() >= MIN_SPEECH_RMS) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastWake > COOLDOWN_MS) {
                        lastWake = now
                        arRec?.reset(); enRec?.reset()
                        ring.fill(0)
                        onWake()
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "wake loop failed", e)
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            if (gen == my) { sessionId = -1; running = false }
        }
    }

    private fun rms(b: ShortArray, n: Int): Int {
        var sum = 0.0
        for (i in 0 until n) sum += b[i].toDouble() * b[i]
        return Math.sqrt(sum / n).toInt()
    }

    /**
     * بيغذي Recognizer واحد. القرار بيتاخد على النتيجة النهائية بس (بعد ما الجملة تخلص)،
     * عشان "يا" لوحدها أو "يا محمد" مايفتحوش. بيرجّع true لو النداء اتأكد بثقة كافية.
     */
    private fun feed(r: Recognizer?, buf: ShortArray, n: Int): Boolean {
        if (r == null) return false
        return try {
            if (!r.acceptWaveForm(buf, n)) return false
            val o = JSONObject(r.result)
            val text = o.optString("text", "")
            if (!WakeMatcher.matches(text)) return false
            // ثقة كلمة "نور": لو Vosk مدّي ثقة وطلعت ضعيفة نتجاهل
            val words = o.optJSONArray("result")
            var ok = true
            if (words != null) for (i in 0 until words.length()) {
                val w = words.optJSONObject(i) ?: continue
                val word = com.nour.assistant.tools.AppResolver.norm(w.optString("word", ""))
                if ((word.startsWith("نور") || word.startsWith("nour") || word.startsWith("noor") || word == "nor" || word == "newer") &&
                    w.optDouble("conf", 1.0) < WakeMatcher.MIN_NAME_CONF) ok = false
            }
            ok
        } catch (_: Exception) { false }
    }

    companion object {
        private const val TAG = "NourWake"
        private const val SAMPLE_RATE = 16000
        private const val COOLDOWN_MS = 3000L
        /** أقل شدة صوت (RMS) لازم تتسمع أثناء الجملة، عشان ضوضاء التلفزيون/الهمس البعيد مايفتحش */
        private const val MIN_SPEECH_RMS = 700
    }
}
