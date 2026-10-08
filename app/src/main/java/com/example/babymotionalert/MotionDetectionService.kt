package com.example.babymotionalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class MotionDetectionService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_TEST_ALARM = "test_alarm"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "motion_monitor"
        private const val NOTIF_ID = 1

        // --- Detection tuning ---
        private const val CAPTURE_WIDTH = 320          // downscaled mirror width (cheap to process)
        private const val FRAME_INTERVAL_MS = 400L     // compare a frame every 0.4s
        private const val PIXEL_DIFF_THRESHOLD = 28    // luminance delta (0-255) to count a pixel as "changed"
        private const val WARMUP_FRAMES = 5            // ignore first frames after start
        private const val ALARM_DURATION_MS = 5000L    // how long the beep + red flash lasts
        private const val COOLDOWN_MS = 8000L          // silence period after an alarm ends
        private const val FLASH_TOGGLE_MS = 250L       // red overlay blink rate
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var prevLuma: ByteArray? = null
    private var lastProcessed = 0L
    private var framesSeen = 0
    private var alarmActive = false
    private var cooldownUntil = 0L

    private var overlayView: View? = null
    private var mediaPlayer: MediaPlayer? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture (e.g. via status bar) -> shut down cleanly
            stopEverything()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data != null) startCapture(code, data) else stopSelf()
            }
            ACTION_TEST_ALARM -> {
                startAsForeground()
                triggerAlarm(test = true)
            }
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Motion monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Baby Motion Alert")
            .setContentText("Watching the screen for movement")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = pm.getMediaProjection(resultCode, data) ?: run { stopSelf(); return }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        val metrics = resources.displayMetrics
        val scale = CAPTURE_WIDTH.toFloat() / metrics.widthPixels
        val capW = CAPTURE_WIDTH
        val capH = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)

        captureThread = HandlerThread("capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val now = System.currentTimeMillis()
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (now - lastProcessed < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    lastProcessed = now
                    processFrame(image.planes[0].buffer, image.planes[0].rowStride, capW, capH)
                } finally {
                    image.close()
                }
            }, captureHandler)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "motion_mirror", capW, capH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )

        prevLuma = null
        framesSeen = 0
        cooldownUntil = System.currentTimeMillis() + 2000 // ignore the permission dialog disappearing
    }

    private fun processFrame(buffer: java.nio.ByteBuffer, rowStride: Int, w: Int, h: Int) {
        // Sample a grid of pixels (every 2nd pixel) and build a luminance map
        val stepX = 2
        val stepY = 2
        val cols = w / stepX
        val rows = h / stepY
        val luma = ByteArray(cols * rows)

        var idx = 0
        for (y in 0 until rows) {
            val rowOffset = (y * stepY) * rowStride
            for (x in 0 until cols) {
                val p = rowOffset + (x * stepX) * 4
                val r = buffer.get(p).toInt() and 0xFF
                val g = buffer.get(p + 1).toInt() and 0xFF
                val b = buffer.get(p + 2).toInt() and 0xFF
                luma[idx++] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
        }

        framesSeen++
        val prev = prevLuma
        prevLuma = luma

        if (prev == null || prev.size != luma.size || framesSeen <= WARMUP_FRAMES) return
        if (alarmActive) return
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) return

        var changed = 0
        for (i in luma.indices) {
            val d = (luma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)
            if (d > PIXEL_DIFF_THRESHOLD || d < -PIXEL_DIFF_THRESHOLD) changed++
        }

        val triggerPercentX10 = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("triggerPercentX10", 15)
        val threshold = luma.size * triggerPercentX10 / 1000.0

        if (changed > threshold) {
            mainHandler.post { triggerAlarm(test = false) }
        }
    }

    // ---------------- Alarm + red flash ----------------

    private fun triggerAlarm(test: Boolean) {
        if (alarmActive) return
        alarmActive = true
        startSound()
        startRedFlash()
        mainHandler.postDelayed({
            stopSound()
            stopRedFlash()
            alarmActive = false
            cooldownUntil = System.currentTimeMillis() + if (test) 0 else COOLDOWN_MS
        }, ALARM_DURATION_MS)
    }

    private fun startSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MotionDetectionService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Fall back to a raw beep if the alarm sound fails
            try {
                val tg = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 100)
                tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ALARM_DURATION_MS.toInt())
            } catch (_: Exception) { }
        }
    }

    private fun stopSound() {
        mediaPlayer?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
    }

    private fun startRedFlash() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this).apply {
            setBackgroundColor(Color.argb(140, 255, 0, 0))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            wm.addView(view, params)
            overlayView = view
            blink(view, visible = false)
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun blink(view: View, visible: Boolean) {
        if (overlayView !== view) return
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        mainHandler.postDelayed({ blink(view, !visible) }, FLASH_TOGGLE_MS)
    }

    private fun stopRedFlash() {
        overlayView?.let {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun stopEverything() {
        stopSound()
        stopRedFlash()
        alarmActive = false
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        mediaProjection = null
        if (::captureThread.isInitialized) captureThread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
