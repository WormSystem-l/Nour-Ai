package com.nour.assistant.assist

import android.service.voice.VoiceInteractionService

/** وجود الخدمة دي هو اللي بيخلي النظام يعرض التطبيق كخيار "مساعد رقمي" */
class NourVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        if (instance === this) instance = null
        super.onShutdown()
    }

    companion object {
        /** بتستخدمها خدمة النداء عشان تفتح النافذة الزجاجية (showSession) */
        @Volatile var instance: NourVoiceInteractionService? = null
    }
}
