package com.nour.assistant.gesture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.KeyEvent
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import android.accessibilityservice.AccessibilityService
import com.nour.assistant.L
import com.nour.assistant.MainActivity
import com.nour.assistant.Prefs
import com.nour.assistant.R
import com.nour.assistant.wake.WakeController
import java.util.concurrent.Executors

/**
 * التحكم بإيماءات اليد: الكاميرا الأمامية + نموذجين On-Device (الفيديو مبيتخزنش ومبيتبعتش).
 * شغال والشاشة مفتوحة بس، وبيقف لوحده لما الشاشة تتقفل.
 */
class GestureService : LifecycleService() {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var tracker: HandTracker? = null
    private val cfg = GestureConfig()
    private val logic = GestureLogic(cfg)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var screenOn = true
    private var lastFrame = 0L
    private var lastToast = 0L
    private var reverse = false
    private var saved: List<SavedGesture> = emptyList()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            if (screenOn) bindCamera() else unbindCamera()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        startAsForeground()
        reload()
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        if (screenOn) bindCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        reload()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        unbindCamera()
        NourAccessibilityService.instance?.hideCursor()
        exec.execute { tracker?.close(); tracker = null }
        exec.shutdown()
        super.onDestroy()
    }

    /** إعادة تحميل إيماءات المستخدم والإعدادات (بعد التعديل من الشاشة) */
    fun reload() {
        saved = GestureStore.load(this)
        logic.customs = saved.map { CustomPose(it.id, it.features) }
        reverse = Prefs(this).reverseScroll
    }

    // ------------------------------------------------------------------ الكاميرا

    private fun bindCamera() {
        if (!hasCamera()) { stopSelf(); return }
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get(); provider = p
                if (!screenOn) return@addListener
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { analyze(it) }
                p.unbindAll()
                val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                camera = cam
                // الإضاءة الخافتة: نرفع التعريض
                val st = cam.cameraInfo.exposureState
                if (st.isExposureCompensationSupported) {
                    cam.cameraControl.setExposureCompensationIndex((st.exposureCompensationRange.upper * 2) / 3)
                }
            } catch (e: Exception) {
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        camera = null
        main.post { NourAccessibilityService.instance?.hideCursor() }
        logic.reset()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < MIN_FRAME_GAP_MS) return
            lastFrame = now
            val raw = image.toBitmap()
            val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) } // مرآة
            val frame = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (raw !== frame) raw.recycle()
            val t = tracker ?: HandTracker(this).also { tracker = it }
            val res = t.process(frame)
            val hand = res.hand
            val events = logic.onFrame(now, hand)
            if (hand != null) logic.lastFeatures?.let { GestureRecorder.feed(it) }
            frame.recycle()
            if (events.isNotEmpty()) main.post { events.forEach { handle(it) } }
        } catch (e: Throwable) {
            // مانوقفش الخدمة بسبب إطار واحد
        } finally {
            image.close()
        }
    }

    // ------------------------------------------------------------------ التنفيذ

    private fun map(nx: Float, ny: Float, w: Int, h: Int): Pair<Float, Float> {
        // نستخدم الجزء الأوسط من الكاميرا للشاشة كلها (حركة أصغر = تعب أقل)
        val x = ((nx - 0.5f) / ACTIVE_X + 0.5f).coerceIn(0f, 1f) * w
        val y = ((ny - 0.5f) / ACTIVE_Y + 0.5f).coerceIn(0f, 1f) * h
        return x to y
    }

    private fun handle(e: GEvent) {
        val svc = NourAccessibilityService.instance
        if (svc == null) {
            if (e is GEvent.Tap || e is GEvent.Scroll || e is GEvent.Custom) needAccessibility()
            return
        }
        val (w, h) = svc.screenSize()
        when (e) {
            is GEvent.Cursor -> { curX = e.nx; curY = e.ny; val p = map(e.nx, e.ny, w, h); svc.showCursor(p.first, p.second, e.pinching) }
            is GEvent.Tap -> { val p = map(e.nx, e.ny, w, h); svc.tap(p.first, p.second) }
            is GEvent.Scroll -> perform(if (e.up != reverse) GestureAction.SCROLL_UP else GestureAction.SCROLL_DOWN, svc, w, h)
            is GEvent.Custom -> saved.firstOrNull { it.id == e.id }?.let { g -> GestureAction.fromKey(g.action)?.let { perform(it, svc, w, h) } }
            GEvent.HandLost -> svc.hideCursor()
        }
    }

    private fun perform(a: GestureAction, svc: NourAccessibilityService, w: Int, h: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        when (a) {
            GestureAction.BACK -> svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
            GestureAction.HOME -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            GestureAction.NOTIFICATIONS -> svc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.LOCK -> if (Build.VERSION.SDK_INT >= 28) svc.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            // الإبهام لفوق = سحب لفوق (المحتوى بيتحرك لفوق)
            GestureAction.SCROLL_UP -> svc.swipe(w / 2f, h * 0.68f, w / 2f, h * 0.32f)
            GestureAction.SCROLL_DOWN -> svc.swipe(w / 2f, h * 0.32f, w / 2f, h * 0.68f)
            GestureAction.TAP -> {
                // آخر مكان للمؤشر
                svc.tap(lastCursorX(w), lastCursorY(h))
            }
            GestureAction.VOLUME_UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            GestureAction.VOLUME_DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            GestureAction.PLAY_PAUSE -> {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            GestureAction.OPEN_NOUR -> WakeController.onWakeWord(this)
        }
    }

    private var curX = 0.5f; private var curY = 0.5f
    private fun lastCursorX(w: Int): Float = map(curX, curY, w, 1).first
    private fun lastCursorY(h: Int): Float = map(curX, curY, 1, h).second

    private fun needAccessibility() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToast < 20_000) return
        lastToast = now
        Toast.makeText(this, L.t("فعّل «التحكم بإيماءات نور» من إعدادات إمكانية الوصول عشان الإيماءات تشتغل.",
            "Enable “Nour gesture control” in Accessibility settings so gestures can work."), Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ الإشعار

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("إيماءات نور", "Nour gestures"), NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(L.t("التحكم بإيماءات اليد شغال", "Hand gesture control is on"))
            .setSilent(true).setOngoing(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    companion object {
        private const val CHANNEL = "nour_gesture_silent"
        private const val NOTIF_ID = 78
        private const val MIN_FRAME_GAP_MS = 55L      // ~18 إطار/ثانية بحد أقصى (توفير بطارية)
        private const val ACTIVE_X = 0.70f
        private const val ACTIVE_Y = 0.60f

        @Volatile var running = false
            private set
        @Volatile var instance: GestureService? = null

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, GestureService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, GestureService::class.java)) }

        /** من MainActivity (ظاهرة): أندرويد 14 بيسمح بتشغيل خدمة الكاميرا منها بس */
        fun ensure(ctx: Context) {
            val ok = Prefs(ctx).gestureEnabled &&
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok && !running) start(ctx)
        }
    }
}
