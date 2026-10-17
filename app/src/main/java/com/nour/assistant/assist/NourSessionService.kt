package com.nour.assistant.assist

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import com.nour.assistant.AiClient
import com.nour.assistant.MainActivity
import com.nour.assistant.Prefs
import com.nour.assistant.R
import com.nour.assistant.overlay.OverlayController
import com.nour.assistant.tools.NourTools
import com.nour.assistant.wake.WakeController

class NourSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NourSession(this)
}

/**
 * النافذة الصغيرة اللي بتظهر فوق أي تطبيق لما تدوس دوسة طويلة على Home/Power.
 * مش بتفتح المايك لوحدها — المايك بيشتغل بس لما تدوس عليه.
 */
class NourSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private var overlay: OverlayController? = null
    private val themed = ContextThemeWrapper(ctx, R.style.Theme_NourAI)

    override fun onCreate() {
        super.onCreate()
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
        }
    }

    override fun onCreateContentView(): View {
        val prefs = Prefs(context)
        val tools = NourTools(context)
        val c = OverlayController(themed, AiClient(tools) { prefs.toConfig() }, object : OverlayController.Callbacks {
            override fun onDismiss() = hide()
            override fun onOpenFull() = openApp(false)
            override fun onNeedMicPermission() = openApp(true)
            override fun onLaunch(intent: Intent) {
                NourTools.safeStart({ i ->
                    try { startAssistantActivity(i) } catch (_: Exception) { context.startActivity(i) }
                }, intent)
                hide()
            }
        }, tools)
        overlay = c
        return c.root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        WakeController.sessionActive = true
        overlay?.onShow(args?.getBoolean(WakeController.EXTRA_AUTO_MIC, false) == true)
    }

    override fun onHide() {
        overlay?.onHide()
        WakeController.sessionActive = false
        WakeController.handoffUntil = android.os.SystemClock.elapsedRealtime() + 1000
        super.onHide()
    }

    override fun onBackPressed() { overlay?.dismissAnimated() ?: hide() }

    override fun onDestroy() {
        overlay?.destroy(); overlay = null
        super.onDestroy()
    }

    private fun openApp(requestMic: Boolean) {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_MIC, requestMic)
        try { startAssistantActivity(i) } catch (_: Exception) {
            try { context.startActivity(i) } catch (_: Exception) {}
        }
        hide()
    }
}
