package com.nour.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService

/** بيختار محرك تعرف على الصوت حقيقي في الجهاز (مش الخدمة الشكلية بتاعتنا) */
object RecognizerPicker {
    private val PREFERRED = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.tts",
        "com.google.android.as",
        "com.samsung.android.bixby.agent",
        "com.huawei.vassistant",
        "com.miui.voiceassist"
    )

    fun pick(ctx: Context): ComponentName? {
        val services = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .map { ComponentName(it.packageName, it.name) }
        return choose(services, ctx.packageName)
    }

    fun choose(all: List<ComponentName>, ownPackage: String): ComponentName? {
        val others = all.filter { it.packageName != ownPackage }
        for (p in PREFERRED) others.firstOrNull { it.packageName == p }?.let { return it }
        return others.firstOrNull()
    }
}
