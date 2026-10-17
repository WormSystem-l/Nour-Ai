package com.nour.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale
import com.nour.assistant.L

/**
 * استماع جوه التطبيق نفسه (من غير ما تظهر شاشة جوجل).
 * لازم يتنادى من الـ Main Thread.
 */
class VoiceInput(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onReady() {}
        fun onLevel(level: Float) {}          // 0..1
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onError(message: String, needsPermission: Boolean = false)
        fun onStopped() {}
    }

    private var recognizer: SpeechRecognizer? = null
    private var lastRetryable = false
    var isListening = false
        private set

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /** retry: محاولة تانية من غير لغات إضافية لو المحرك رفضها */
    fun start(language: String? = null, retry: Boolean = false) {
        if (isListening) return
        val (primary, extra) = languagesFor(com.nour.assistant.Prefs(ctx).voiceLang, Locale.getDefault().toLanguageTag())
        val lang = language ?: primary
        lastRetryable = !retry
        if (!hasPermission()) { listener.onError(L.t("محتاج إذن المايك", "Microphone permission needed"), needsPermission = true); return }
        val r = create() ?: run { listener.onError(L.t("مفيش محرك تعرف على الصوت في الجهاز", "No speech recognition engine on this device")); return }
        recognizer = r
        r.setRecognitionListener(callbacks)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // عربي + إنجليزي في نفس الجملة: لغة تانية بجانب الأساسية (جوجل بيتجاهلها لو مش مدعومة)
            if (!retry && language == null && extra.isNotEmpty()) putStringArrayListExtra(EXTRA_ADDITIONAL_LANGUAGES, ArrayList(extra))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        isListening = true
        try { r.startListening(intent) } catch (e: Exception) {
            isListening = false; listener.onError(L.t("مقدرتش أشغل المايك", "Couldn't start the microphone")); release()
        }
    }

    fun stop() { try { recognizer?.stopListening() } catch (_: Exception) {} }

    fun cancel() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        if (isListening) { isListening = false; listener.onStopped() }
        release()
    }

    fun release() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun create(): SpeechRecognizer? {
        RecognizerPicker.pick(ctx)?.let { comp ->
            return try { SpeechRecognizer.createSpeechRecognizer(ctx, comp) } catch (_: Exception) { null }
        }
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            return try { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (_: Exception) { null }
        }
        return null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { MixedText.pickBest(it) }
                ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
        }
        override fun onResults(b: Bundle?) {
            isListening = false
            val t = MixedText.pickBest(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            listener.onStopped()
            if (t.isNotBlank()) listener.onFinal(t) else listener.onError(L.t("ماسمعتش حاجة، جرب تاني", "Didn't catch that, try again"))
            release()
        }
        override fun onError(error: Int) {
            // 12/13: اللغة مش مدعومة/مش متنزلة: جرب تاني بالعربي بس من غير لغات إضافية
            if ((error == 12 || error == 13) && lastRetryable) {
                isListening = false; release(); start("ar-EG", retry = true); return
            }
            isListening = false
            listener.onStopped()
            listener.onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> L.t("ماسمعتش حاجة، جرب تاني", "Didn't catch that, try again")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> L.t("مشكلة في الإنترنت", "Network problem")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> L.t("محتاج إذن المايك", "Microphone permission needed")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> L.t("المايك مشغول، جرب كمان ثانية", "Microphone busy, try again in a second")
                    SpeechRecognizer.ERROR_AUDIO -> L.t("مشكلة في المايك", "Microphone problem")
                    else -> L.t("حصل خطأ في التعرف على الصوت", "Speech recognition error") + " ($error)"
                },
                needsPermission = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            )
            release()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        const val EXTRA_ADDITIONAL_LANGUAGES = "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"

        /**
         * (اللغة الأساسية، اللغات الإضافية).
         * auto: عربي دايماً أساسي (حتى لو الجهاز إنجليزي) + إنجليزي + لغة الجهاز لو غير كده.
         */
        fun languagesFor(mode: String, deviceTag: String): Pair<String, List<String>> = when (mode) {
            "ar" -> "ar-EG" to listOf("en-US")
            "en" -> "en-US" to listOf("ar-EG")
            else -> {
                val dev = deviceTag.ifBlank { "en-US" }
                val extras = linkedSetOf("en-US")
                if (!dev.startsWith("ar", true) && !dev.startsWith("en", true)) extras += dev
                "ar-EG" to extras.toList()
            }
        }
    }
}
