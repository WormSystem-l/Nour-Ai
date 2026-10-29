package com.nour.assistant.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.nour.assistant.AppLog
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
            Log.e(TAG, "model load failed", e); AppLog.e(TAG, "model load failed", e)
            false
        }
    }

    private fun grammar(l: List<String>) = JSONArray(l).toString()

    @SuppressLint("MissingPermission")
    private fun loop(my: Int) {
        var rec: AudioRecord? = null
        val alive = { running && gen == my }
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            if (!load()) { if (gen == my) running = false; return }
            val tune = WakeTuning.level(com.nour.assistant.Prefs(ctx).wakeSens)
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { if (gen == my) running = false; return }
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.e(TAG, "AudioRecord not initialized (mic busy?)")
                if (gen == my) running = false; return
            }
            if (!alive()) return
            sessionId = rec.audioSessionId
            arRec?.reset(); enRec?.reset()
            rec.startRecording()

            // الاستماع الموفّر للبطارية: Vosk بيشتغل بس وقت الكلام (VAD)، وفي الصمت بنحسب شدة الصوت بس
            arConf = tune.arConf; enConf = tune.enConf
            val vad = Vad(tune.vadRatio)
            val seg = Segmenter(vad)
            val agc = Agc()
            val chunk = ShortArray(SAMPLE_RATE / 6)            // ~166ms
            val amp = ShortArray(chunk.size)
            val pre = ArrayDeque<ShortArray>()                 // آخر ~660ms قبل الكلام (عشان أول الكلمة ماتضيعش)
            var speakingFed = false

            while (alive()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) { if (n < 0) break else continue }
                if (!alive()) break
                val level = Agc.rms(chunk, n)
                val ev = seg.step(level, SystemClock.elapsedRealtime())
                val gain = agc.gain(level, seg.speaking || ev == Segmenter.Ev.START)
                when (ev) {
                    Segmenter.Ev.IDLE -> {
                        pre.addLast(chunk.copyOf(n)); while (pre.size > 4) pre.removeFirst()
                    }
                    Segmenter.Ev.START -> {
                        arRec?.reset(); enRec?.reset()
                        for (p in pre) { Agc.apply(p, p.size, gain, amp); feed(amp, p.size) }
                        pre.clear()
                        Agc.apply(chunk, n, gain, amp); feed(amp, n); speakingFed = true
                    }
                    Segmenter.Ev.CONT -> { Agc.apply(chunk, n, gain, amp); if (feed(amp, n)) fire(tune) }
                    Segmenter.Ev.END -> {
                        Agc.apply(chunk, n, gain, amp); feed(amp, n)
                        // الجملة خلصت: نطلب النتيجة النهائية
                        if (speakingFed && seg.voicedChunks >= 2 && finals(tune)) fire(tune)
                        arRec?.reset(); enRec?.reset(); speakingFed = false
                    }
                }
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "wake loop failed", e)
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            if (gen == my) { sessionId = -1; running = false }
        }
    }

    private var pendingHit = false

    private fun fire(@Suppress("UNUSED_PARAMETER") tune: WakeTuning) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWake <= COOLDOWN_MS) return
        lastWake = now
        pendingHit = false
        arRec?.reset(); enRec?.reset()
        onWake()
    }

    /** بيغذي الـ Recognizers. لو نتيجة نهائية وسط الكلام وفيها النداء بيرجّع true */
    private fun feed(buf: ShortArray, n: Int): Boolean {
        var hit = false
        if (feedOne(arRec, buf, n, arConf)) hit = true
        if (feedOne(enRec, buf, n, enConf)) hit = true
        return hit
    }

    private var arConf = 0.5f
    private var enConf = 0.7f

    private fun feedOne(r: Recognizer?, buf: ShortArray, n: Int, minConf: Float): Boolean {
        if (r == null) return false
        return try { r.acceptWaveForm(buf, n) && accept(JSONObject(r.result), minConf) } catch (_: Exception) { false }
    }

    private fun finals(tune: WakeTuning): Boolean {
        arConf = tune.arConf; enConf = tune.enConf
        var hit = false
        arRec?.let { r -> try { if (accept(JSONObject(r.finalResult), tune.arConf)) hit = true } catch (_: Exception) {} }
        enRec?.let { r -> try { if (accept(JSONObject(r.finalResult), tune.enConf)) hit = true } catch (_: Exception) {} }
        return hit
    }

    /** النتيجة فيها النداء؟ وكلمة "نور" ثقتها كافية؟ */
    private fun accept(o: JSONObject, minConf: Float): Boolean {
        val text = o.optString("text", "")
        if (!WakeMatcher.matches(text)) return false
        val words = o.optJSONArray("result") ?: return true
        for (i in 0 until words.length()) {
            val w = words.optJSONObject(i) ?: continue
            val word = com.nour.assistant.tools.AppResolver.norm(w.optString("word", ""))
            if ((word.startsWith("نور") || word.startsWith("nour") || word.startsWith("noor") || word == "nor" || word == "newer" || word == "nur") &&
                w.optDouble("conf", 1.0) < minConf) return false
        }
        return true
    }

    companion object {
        private const val TAG = "NourWake"
        private const val SAMPLE_RATE = 16000
        private const val COOLDOWN_MS = 3000L

    }
}
