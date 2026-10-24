package com.nour.assistant.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.nour.assistant.voice.RecognizerPicker

/**
 * النظام بيطلب إن المساعد يكون عنده RecognitionService، ولما تختار Nour كمساعد
 * بعض الأجهزة بتخليها خدمة الصوت الافتراضية. عشان الكتابة بالصوت في باقي التطبيقات
 * متبوظش، الخدمة دي بتحوّل الطلب لمحرك الصوت الحقيقي في الجهاز (جوجل غالبًا).
 */
class NourRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent?, cb: Callback?) {
        if (cb == null) return
        release()
        val comp = RecognizerPicker.pick(this)
        val r = try { comp?.let { SpeechRecognizer.createSpeechRecognizer(this, it) } } catch (_: Exception) { null }
        if (r == null) { safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        inner = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = safe { cb.readyForSpeech(p ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { cb.beginningOfSpeech() }
            override fun onRmsChanged(v: Float) = safe { cb.rmsChanged(v) }
            override fun onBufferReceived(b: ByteArray?) = safe { if (b != null) cb.bufferReceived(b) }
            override fun onEndOfSpeech() = safe { cb.endOfSpeech() }
            override fun onError(e: Int) { safe { cb.error(e) }; release() }
            override fun onResults(b: Bundle?) { safe { cb.results(b ?: Bundle()) }; release() }
            override fun onPartialResults(b: Bundle?) = safe { cb.partialResults(b ?: Bundle()) }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        try { r.startListening(intent ?: Intent()) } catch (_: Exception) {
            safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }; release()
        }
    }

    override fun onStopListening(cb: Callback?) { try { inner?.stopListening() } catch (_: Exception) {} }
    override fun onCancel(cb: Callback?) { try { inner?.cancel() } catch (_: Exception) {}; release() }
    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() { try { inner?.destroy() } catch (_: Exception) {}; inner = null }
    private inline fun safe(f: () -> Unit) { try { f() } catch (_: Exception) {} }
}
