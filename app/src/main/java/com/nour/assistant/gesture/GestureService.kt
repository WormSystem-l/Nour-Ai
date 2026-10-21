package com.nour.assistant.gesture

import android.accessibilityservice.AccessibilityService
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
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
import com.nour.assistant.AppLog
import com.nour.assistant.L
import com.nour.assistant.MainActivity
import com.nour.assistant.Prefs
import com.nour.assistant.R
import com.nour.assistant.wake.WakeController
import java.util.concurrent.Executors

/**
 * التحكم بإيماءات اليد: الكاميرا الأمامية + نموذجين On-Device (الفيديو مبيتخزنش ومبيتبعتش).
 * توفير بطارية: سرعة كاملة بس وقت وجود يد، ووضع خمول (2-3 إطار/ثانية) لما مفيش يد،
 * ووقف تلقائي لما الشاشة تتقفل أو الموبايل في الجيب (حساس القرب) أو البطارية أقل من 15%.
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
    private var near = false
    private var lowBattery = false
    private var bound = false
    private var lastFrame = 0L
    private var lastHandAt = 0L
    private var lastToast = 0L
    private var reverse = false
    private var saved: List<SavedGesture> = emptyList()
    private var curX = 0.5f; private var curY = 0.5f

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            refreshBinding()
        }
    }

    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val n = e.values[0] < (e.sensor.maximumRange.coerceAtMost(5f)) * 0.9f
            if (n != near) { near = n; refreshBinding() }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val batteryTick = object : Runnable {
        override fun run() { checkBattery(); main.postDelayed(this, 60_000) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        try { startAsForeground() } catch (e: Throwable) { AppLog.e("gesture", "startForeground failed", e); stopSelf(); return }
        reload()
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } catch (_: Exception) {}
        checkBattery()
        main.postDelayed(batteryTick, 60_000)
        refreshBinding()
        AppLog.i("gesture", "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        reload()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; instance = null
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(proximity) }
        unbindCamera()
        NourAccessibilityService.instance?.hideCursor()
        exec.execute { tracker?.close(); tracker = null }
        exec.shutdown()
        AppLog.i("gesture", "service stopped")
        super.onDestroy()
    }

    /** إعادة تحميل إيماءات المستخدم والإعدادات (بعد التعديل من الشاشة) */
    fun reload() {
        val p = Prefs(this)
        saved = GestureStore.load(this)
        logic.customs = saved.map { CustomPose(it.id, it.features) }
        reverse = p.reverseScroll
        cfg.sensitivity = when (p.gestureSens) { 0 -> 0.75f; 2 -> 1.4f; else -> 1f }
    }

    // ------------------------------------------------------------------ الكاميرا + توفير الطاقة

    private fun checkBattery() {
        try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val low = level < LOW_BATTERY && !charging
            if (low != lowBattery) {
                lowBattery = low
                if (low) Toast.makeText(this, L.t("البطارية أقل من $LOW_BATTERY% — الإيماءات اتوقفت مؤقتاً", "Battery below $LOW_BATTERY% — gestures paused"), Toast.LENGTH_LONG).show()
                refreshBinding()
            }
        } catch (_: Exception) {}
    }

    private fun shouldRun() = screenOn && !near && !lowBattery

    private fun refreshBinding() { if (shouldRun()) bindCamera() else unbindCamera() }

    private fun bindCamera() {
        if (bound) return
        if (!hasCamera()) { AppLog.w("gesture", "no camera permission"); stopSelf(); return }
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                if (!shouldRun() || bound) return@addListener
                val p = f.get(); provider = p
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(360, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { analyze(it) }
                p.unbindAll()
                val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                camera = cam; bound = true
                val st = cam.cameraInfo.exposureState
                if (st.isExposureCompensationSupported) cam.cameraControl.setExposureCompensationIndex((st.exposureCompensationRange.upper * 2) / 3)
            } catch (e: Throwable) {
                AppLog.e("gesture", "bindCamera failed", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        camera = null; bound = false
        main.post { NourAccessibilityService.instance?.hideCursor() }
        logic.reset()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** فاصل الإطارات: سريع لما فيه يد، وبيبطّأ تدريجياً لما مفيش (توفير بطارية) */
    private fun frameGap(now: Long): Long {
        val idle = now - lastHandAt
        return when {
            lastHandAt == 0L || idle > 60_000 -> 800
            idle > 10_000 -> 350
            idle > 1_500 -> 110
            else -> 28
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < frameGap(now)) return
            lastFrame = now
            val raw = image.toBitmap()
            val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) } // مرآة
            val frame = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (raw !== frame) raw.recycle()
            val t = tracker ?: HandTracker(this).also { tracker = it }
            val res = t.process(frame)
            val hand = res.hand
            if (hand != null) lastHandAt = now
            val events = logic.onFrame(now, hand)
            if (hand != null) logic.lastFeatures?.let { GestureRecorder.feed(it) }
            frame.recycle()
            if (events.isNotEmpty()) main.post { events.forEach { handle(it) } }
        } catch (e: Throwable) {
            AppLog.eOnce("gesture", "analyze failed: ${e.javaClass.simpleName}", e)
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
            if (e is GEvent.Tap || e is GEvent.Scroll || e is GEvent.Custom || e is GEvent.Screenshot) needAccessibility()
            return
        }
        val (w, h) = svc.screenSize()
        when (e) {
            is GEvent.Cursor -> { curX = e.nx; curY = e.ny; val p = map(e.nx, e.ny, w, h); svc.showCursor(p.first, p.second, e.pinching, if (reverse) -e.scroll else e.scroll) }
            is GEvent.Tap -> { val p = map(e.nx, e.ny, w, h); svc.pulse(); svc.tap(p.first, p.second) }
            is GEvent.Scroll -> perform(if (e.down != reverse) GestureAction.SCROLL_DOWN else GestureAction.SCROLL_UP, svc, w, h)
            is GEvent.Custom -> saved.firstOrNull { it.id == e.id }?.let { g -> GestureAction.fromKey(g.action)?.let { perform(it, svc, w, h) } }
            GEvent.Screenshot -> perform(GestureAction.SCREENSHOT, svc, w, h)
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
            GestureAction.SCREENSHOT -> {
                // نخفي المؤشر لحظة عشان مايظهرش في اللقطة، وبعدها فلاش خفيف
                if (Build.VERSION.SDK_INT >= 28) {
                    svc.hideCursor()
                    main.postDelayed({ svc.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 280)
                    main.postDelayed({ svc.showCursor(w / 2f, h / 2f, false, 0); svc.flashScreen() }, 900)
                } else Toast.makeText(this, L.t("لقطة الشاشة بالإيماءة محتاجة أندرويد 9+", "Gesture screenshot needs Android 9+"), Toast.LENGTH_SHORT).show()
            }
            // تمرير لتحت = نكشف محتوى أسفل = السحب بإصبع لفوق
            GestureAction.SCROLL_DOWN -> svc.swipe(w / 2f, h * 0.68f, w / 2f, h * 0.32f)
            GestureAction.SCROLL_UP -> svc.swipe(w / 2f, h * 0.32f, w / 2f, h * 0.68f)
            GestureAction.TAP -> { val p = map(curX, curY, w, h); svc.pulse(); svc.tap(p.first, p.second) }
            GestureAction.VOLUME_UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            GestureAction.VOLUME_DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            GestureAction.PLAY_PAUSE -> {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            GestureAction.OPEN_NOUR -> WakeController.onWakeWord(this)
        }
    }

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
        private const val ACTIVE_X = 0.70f
        private const val ACTIVE_Y = 0.60f
        private const val LOW_BATTERY = 15

        @Volatile var running = false
            private set
        @Volatile var instance: GestureService? = null

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, GestureService::class.java)) }
            catch (e: Exception) { AppLog.eOnce("gesture", "start refused by system", e) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, GestureService::class.java)) }

        /** من MainActivity (ظاهرة) أو حارس إمكانية الوصول */
        fun ensure(ctx: Context) {
            val ok = Prefs(ctx).gestureEnabled &&
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok && !running) start(ctx)
        }
    }
}
