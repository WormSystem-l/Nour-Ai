package com.nour.assistant.wake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.nour.assistant.MainActivity
import com.nour.assistant.Prefs
import com.nour.assistant.assist.NourVoiceInteractionService

/** نقطة التحكم المشتركة بين خدمة النداء والنافذة الزجاجية */
object WakeController {
    const val EXTRA_AUTO_MIC = "auto_mic"

    /** النافذة الزجاجية ظاهرة دلوقتي (يبقى مايكنا لازم يكون مقفول) */
    @Volatile var sessionActive = false
        set(v) { field = v; if (v) sessionSince = SystemClock.elapsedRealtime() }
    @Volatile var sessionSince = 0L

    /** لو النافذة "علقت" كظاهرة (مفيش onHide) أكتر من 3 دقايق، نتجاهلها ونرجع نسمع */
    fun sessionBlocksMic(): Boolean =
        sessionActive && SystemClock.elapsedRealtime() - sessionSince < 3 * 60_000
    /** فترة سماح بعد النداء لحد ما النافذة تظهر، عشان مانسمعش مع محرك جوجل */
    @Volatile var handoffUntil = 0L

    fun hasMic(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun canRun(ctx: Context) = Prefs(ctx).wakeEnabled && WakeModels.isReady(ctx) && hasMic(ctx)

    /**
     * أندرويد 14: مينفعش نشغّل خدمة مايك من الخلفية (بعد الريستارت مثلاً)،
     * فلازم المستخدم يفتح التطبيق مرة، ودي بتتنادى من MainActivity.onResume.
     */
    fun ensureService(ctx: Context) {
        if (canRun(ctx) && !WakeWordService.running) WakeWordService.start(ctx)
    }

    /** اتسمع النداء: افتح النافذة الزجاجية وشغّل المايك (محرك جوجل) */
    fun onWakeWord(ctx: Context) {
        handoffUntil = SystemClock.elapsedRealtime() + 6000
        val svc = NourVoiceInteractionService.instance
        val shown = svc != null && try {
            svc.showSession(Bundle().apply { putBoolean(EXTRA_AUTO_MIC, true) }, 0); true
        } catch (_: Exception) { false }
        if (!shown) {
            // لو Nour مش هو المساعد الافتراضي: نحاول نفتح التطبيق نفسه
            try {
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_AUTO_MIC, true))
            } catch (_: Exception) { handoffUntil = 0 }
        }
    }
}
