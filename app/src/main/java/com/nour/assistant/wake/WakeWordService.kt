package com.nour.assistant.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nour.assistant.L
import com.nour.assistant.MainActivity
import com.nour.assistant.R

/**
 * نداء "يا نور": شغال والشاشة مفتوحة بس، وبيوقف المايك لوحده لو:
 *  - الشاشة اتقفلت
 *  - في مكالمة
 *  - أي تطبيق تاني بيسجل صوت (فويس، تسجيل شاشة...)
 *  - الكاميرا مفتوحة
 *  - نافذة Nour الزجاجية ظاهرة (محرك جوجل بياخد المايك)
 * وبيرجع يسمع لوحده لما كل ده يخلص.
 */
class WakeWordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: WakeWordEngine
    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private val busyCameras = HashSet<String>()
    private var camManager: CameraManager? = null
    private var camCallback: CameraManager.AvailabilityCallback? = null
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            evaluate()
            // فتح الشاشة: ابدأ الاستماع فوراً، وأعد المحاولة كذا مرة لو المايك لسه بيتحرر
            if (screenOn) listOf(400L, 1200L, 2500L).forEach { main.postDelayed({ evaluate() }, it) }
        }
    }

    private val ticker = object : Runnable {
        override fun run() { evaluate(); main.postDelayed(this, TICK_MS) }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        power = getSystemService(POWER_SERVICE) as PowerManager
        screenOn = power.isInteractive
        engine = WakeWordEngine(this) { main.post { WakeController.onWakeWord(this); evaluate() } }
        startAsForeground()
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registerCamera()
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!WakeController.canRun(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        camCallback?.let { runCatching { camManager?.unregisterAvailabilityCallback(it) } }
        engine.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------ مراقبة المايك

    private fun evaluate() {
        if (!running) return
        val listen = shouldListen()
        if (listen && !engine.isRunning) engine.start()
        else if (!listen && engine.isRunning) engine.stop()
    }

    private fun shouldListen(): Boolean {
        if (!WakeController.canRun(this)) { stopSelf(); return false }
        if (!screenOn || !power.isInteractive) return false
        if (WakeController.sessionBlocksMic() || SystemClock.elapsedRealtime() < WakeController.handoffUntil) return false
        if (inCall()) return false
        if (busyCameras.isNotEmpty() && !com.nour.assistant.gesture.GestureService.running) return false
        return !othersRecordingDebounced()
    }

    private fun inCall(): Boolean {
        val m = audio.mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION ||
            m == AudioManager.MODE_RINGTONE || m == 4 /* MODE_CALL_SCREENING */
    }

    /** في تطبيق تاني بيسجل صوت (مش إحنا)؟ */
    private fun othersRecording(): Boolean = try {
        val mine = engine.sessionId
        audio.activeRecordingConfigurations.any { it.clientAudioSessionId != mine || mine == -1 }
    } catch (_: Exception) { false }

    private var othersSince = 0L

    /** مانوقفش إلا لو تطبيق تاني بيسجل فعلاً لأكتر من 1.5 ثانية (بيمنع الإيقاف الغلط) */
    private fun othersRecordingDebounced(): Boolean {
        if (!othersRecording()) { othersSince = 0; return false }
        val now = SystemClock.elapsedRealtime()
        if (othersSince == 0L) othersSince = now
        return now - othersSince >= 1500
    }

    private fun registerCamera() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val cb = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) { busyCameras.remove(cameraId); evaluate() }
                override fun onCameraUnavailable(cameraId: String) { busyCameras.add(cameraId); evaluate() }
            }
            cm.registerAvailabilityCallback(cb, main)
            camManager = cm; camCallback = cb
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ الإشعار الصامت

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_MIN: صامت تماماً وبدون أيقونة في شريط الحالة (أندرويد بيجبر أي خدمة مايك على وجود إشعار)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("نداء نور", "Nour wake word"),
            NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null, null); enableVibration(false); enableLights(false); setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(L.t("Nour بيسمع النداء", "Nour is listening for the wake word"))
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    companion object {
        private const val CHANNEL = "nour_wake_silent"
        private const val NOTIF_ID = 77
        private const val TICK_MS = 1200L
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java)) } catch (e: Exception) { com.nour.assistant.AppLog.eOnce("wake", "start refused by system", e) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeWordService::class.java)) }
    }
}
