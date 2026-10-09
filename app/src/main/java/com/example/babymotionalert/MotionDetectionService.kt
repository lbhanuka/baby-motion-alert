package com.example.babymotionalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

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
        private const val GRACE_PERIOD_MS = 20000L     // time to switch to the camera app before detection arms
        private const val RESUME_GRACE_MS = 5000L      // short grace after resuming from pause/snooze
        private const val ALARM_DURATION_MS = 5000L    // how long the beep + red flash lasts
        private const val COOLDOWN_MS = 8000L          // silence period after an alarm ends
        private const val FLASH_TOGGLE_MS = 250L       // red overlay blink rate during alarm
        private const val LONG_PRESS_MS = 600L         // hold the floating button this long = indefinite pause

        /** While now < suppressUntil, detection ignores everything (set by ReconnectClickerService). */
        @Volatile
        @JvmStatic
        var suppressUntil: Long = 0L

        // --- Double-clap snooze tuning ---
        private const val CLAP_FLOOR_FACTOR = 3.0    // ...and this many times the ambient RMS floor
        private const val CLAP_GAP_MIN_MS = 180L     // two claps this far apart...
        private const val CLAP_GAP_MAX_MS = 1500L    // ...but no further -> snooze
        private const val CLAP_AFTER_ALARM_MS = 10000L // keep listening this long after the ring stops

        // --- Sound detection tuning ---
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHUNK_MS = 100         // analyse audio in 100 ms chunks
        private const val LOUD_CHUNKS_TO_TRIGGER = 8   // ~0.8 s of sustained sound = crying
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
    private var armedFlashRunnable: Runnable? = null

    // Suspension state: indefinite pause (long-press) or timed snooze ("attending mode")
    @Volatile
    private var pausedIndefinitely = false
    @Volatile
    private var snoozeUntil = 0L
    private var snoozeTicker: Runnable? = null

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    private var overlayView: View? = null
    private var floatingButton: TextView? = null
    private var mediaPlayer: MediaPlayer? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture (e.g. via status bar) -> shut down cleanly
            stopEverything()
            stopSelf()
        }
    }

    private fun isSuspended(): Boolean =
        pausedIndefinitely || System.currentTimeMillis() < snoozeUntil

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

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
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
        pausedIndefinitely = false
        snoozeUntil = 0L

        // Grace period: no alarms until it elapses, so you can switch to the camera app.
        cooldownUntil = System.currentTimeMillis() + GRACE_PERIOD_MS

        // Sound detection (captures the audio the camera app plays, not the room)
        startAudioDetection(projection)

        // Floating pause/snooze button, visible on top of other apps
        mainHandler.post { addFloatingButton() }

        // Silent confirmation blink now ("monitoring started")...
        mainHandler.post { silentBlink(times = 2) }

        // ...and another silent blink when detection actually arms.
        armedFlashRunnable = Runnable {
            if (mediaProjection != null && !isSuspended()) silentBlink(times = 2)
        }.also { mainHandler.postDelayed(it, GRACE_PERIOD_MS) }
    }

    // ---------------- Floating button: tap = snooze/resume, long-press = pause ----------------

    private fun addFloatingButton() {
        if (floatingButton != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        val button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = resources.displayMetrics.heightPixels / 3
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val touchSlop = (8 * density)
        var longPressRunnable: Runnable? = null

        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    longPressed = false
                    longPressRunnable = Runnable {
                        longPressed = true
                        onLongPress()
                    }.also { mainHandler.postDelayed(it, LONG_PRESS_MS) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (e.actionMasked == MotionEvent.ACTION_UP && !moved && !longPressed) onTap()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(button, params)
            floatingButton = button
            styleFloatingButton()
        } catch (_: Exception) {
            floatingButton = null
        }
    }

    /** Tap: armed -> snooze (attending); snoozing/paused -> re-arm. Also dismisses a ringing alarm. */
    private fun onTap() {
        if (mediaProjection == null) return
        when {
            pausedIndefinitely || System.currentTimeMillis() < snoozeUntil -> resumeMonitoring()
            else -> startSnooze()
        }
    }

    /** Long-press: indefinite pause (until tapped). Also dismisses a ringing alarm. */
    private fun onLongPress() {
        if (mediaProjection == null) return
        dismissAlarm()
        cancelSnoozeTicker()
        snoozeUntil = 0L
        pausedIndefinitely = true
        styleFloatingButton()
    }

    private fun startSnooze() {
        dismissAlarm()
        pausedIndefinitely = false
        val minutes = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("snoozeMinutes", 10).coerceIn(3, 20)
        snoozeUntil = System.currentTimeMillis() + minutes * 60_000L
        startSnoozeTicker()
        styleFloatingButton()
    }

    private fun resumeMonitoring() {
        dismissAlarm()
        cancelSnoozeTicker()
        pausedIndefinitely = false
        snoozeUntil = 0L
        cooldownUntil = System.currentTimeMillis() + RESUME_GRACE_MS
        mainHandler.postDelayed({
            if (mediaProjection != null && !isSuspended()) silentBlink(times = 1)
        }, RESUME_GRACE_MS)
        styleFloatingButton()
    }

    private fun startSnoozeTicker() {
        cancelSnoozeTicker()
        snoozeTicker = object : Runnable {
            override fun run() {
                if (mediaProjection == null) return
                val remaining = snoozeUntil - System.currentTimeMillis()
                if (remaining <= 0) {
                    // Snooze over -> auto re-arm with a confirmation blink
                    snoozeUntil = 0L
                    snoozeTicker = null
                    cooldownUntil = System.currentTimeMillis() + RESUME_GRACE_MS
                    mainHandler.postDelayed({
                        if (mediaProjection != null && !isSuspended()) silentBlink(times = 1)
                    }, RESUME_GRACE_MS)
                    styleFloatingButton()
                } else {
                    styleFloatingButton()
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }.also { mainHandler.postDelayed(it, 0L) }
    }

    private fun cancelSnoozeTicker() {
        snoozeTicker?.let { mainHandler.removeCallbacks(it) }
        snoozeTicker = null
    }

    private fun styleFloatingButton() {
        val button = floatingButton ?: return
        val now = System.currentTimeMillis()
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        when {
            pausedIndefinitely -> {
                bg.setColor(Color.argb(220, 110, 110, 110))
                button.textSize = 22f
                button.text = "\u25B6" // play
            }
            now < snoozeUntil -> {
                bg.setColor(Color.argb(230, 230, 150, 0))
                button.textSize = 13f
                val secs = ((snoozeUntil - now) / 1000L).coerceAtLeast(0)
                button.text = "%d:%02d".format(secs / 60, secs % 60)
            }
            else -> {
                bg.setColor(Color.argb(200, 0, 150, 70))
                button.textSize = 22f
                button.text = "\u23F8" // pause
            }
        }
        button.background = bg
    }

    private fun removeFloatingButton() {
        floatingButton?.let {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        floatingButton = null
    }

    // ---------------- Sound detection (playback capture) ----------------

    private fun startAudioDetection(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("soundEnabled", false)) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, AUDIO_SAMPLE_RATE)) // ~0.5 s of 16-bit mono
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (_: Exception) {
            null
        } ?: return

        audioRecord = rec
        try {
            rec.startRecording()
        } catch (_: Exception) {
            rec.release(); audioRecord = null; return
        }

        audioThread = Thread {
            val chunk = ShortArray(AUDIO_SAMPLE_RATE * AUDIO_CHUNK_MS / 1000)
            var loudChunks = 0
            while (!Thread.currentThread().isInterrupted && audioRecord === rec) {
                val n = try { rec.read(chunk, 0, chunk.size) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n == 0) continue

                if (isSuspended() || System.currentTimeMillis() < suppressUntil) { loudChunks = 0; continue }

                var sum = 0.0
                for (i in 0 until n) {
                    val v = chunk[i].toDouble()
                    sum += v * v
                }
                val rms = sqrt(sum / n)

                if (rms > soundThresholdRms()) loudChunks++ else loudChunks = 0

                if (loudChunks >= LOUD_CHUNKS_TO_TRIGGER) {
                    loudChunks = 0
                    val now = System.currentTimeMillis()
                    if (!alarmActive && now >= cooldownUntil) {
                        mainHandler.post { triggerAlarm(test = false) }
                    }
                }
            }
        }.also {
            it.name = "audio-detect"
            it.start()
        }
    }

    /** Slider 0..100 (higher = more sensitive) -> RMS threshold 8000 (needs loud) .. 200 (very quiet). */
    private fun soundThresholdRms(): Double {
        val s = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("soundSensitivity", 50)
        return 8000.0 * (200.0 / 8000.0).pow(s / 100.0)
    }

    private fun stopAudioDetection() {
        audioThread?.interrupt()
        audioThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
    }

    // ---------------- Motion detection ----------------

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
        if (isSuspended() || alarmActive) return
        val now = System.currentTimeMillis()
        if (now < cooldownUntil || now < suppressUntil) return

        var changed = 0
        for (i in luma.indices) {
            val d = (luma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)
            if (d > PIXEL_DIFF_THRESHOLD || d < -PIXEL_DIFF_THRESHOLD) changed++
        }

        // Stored value = % of pixels x100 (so 20 = 0.20%). Range 5..500.
        val triggerPercentX100 = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("triggerPercentX100", 20)
        val threshold = luma.size * triggerPercentX100 / 10000.0

        if (changed > threshold) {
            mainHandler.post { triggerAlarm(test = false) }
        }
    }

    // ---------------- Alarm + red flash ----------------

    private fun triggerAlarm(test: Boolean) {
        if (alarmActive || (isSuspended() && !test)) return
        alarmActive = true
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val soundOn = prefs.getBoolean("alarmSoundEnabled", true)
        clapStopRunnable?.let { mainHandler.removeCallbacks(it) }
        clapStopRunnable = null
        if (soundOn) {
            // The raw mic hears our own alarm as "claps", so close the listener
            // BEFORE the ring starts; it reopens the moment the ring ends.
            stopClapListener()
            startSound()
        } else {
            startClapListener() // silent alarm: listen the whole time
        }
        if (prefs.getBoolean("flashlightEnabled", false)) startTorchBlink()
        startRedFlash()
        mainHandler.postDelayed({
            if (!alarmActive) return@postDelayed // already dismissed via the floating button
            stopSound()
            stopRedFlash()
            stopTorchBlink()
            // Ring over -> NOW open the clap window for the quiet tail, so our own
            // alarm sound can never snooze itself.
            startClapListener()
            clapStopRunnable = Runnable {
                clapStopRunnable = null
                stopClapListener()
            }.also { mainHandler.postDelayed(it, CLAP_AFTER_ALARM_MS) }
            alarmActive = false
            cooldownUntil = System.currentTimeMillis() + if (test) 0 else COOLDOWN_MS
        }, ALARM_DURATION_MS)
    }

    private fun dismissAlarm() {
        clapStopRunnable?.let { mainHandler.removeCallbacks(it) }
        clapStopRunnable = null
        stopClapListener()
        if (!alarmActive) return
        stopSound()
        stopRedFlash()
        stopTorchBlink()
        alarmActive = false
    }

    // ---------------- Double-clap snooze (mic, only while alarm rings) ----------------

    private var clapRecord: AudioRecord? = null
    private var clapThread: Thread? = null
    private var clapAec: android.media.audiofx.AcousticEchoCanceler? = null
    private var clapStopRunnable: Runnable? = null
    private var duckedMediaVolume: Int = -1

    /** Slider 0..100 (higher = more sensitive) -> required clap peak 15000 .. 3000. */
    private fun clapPeakMin(): Int {
        val s = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("clapSensitivity", 50)
        return (15000.0 * (3000.0 / 15000.0).pow(s / 100.0)).toInt()
    }

    private fun startClapListener() {
        if (clapThread != null) return
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("clapSnoozeEnabled", true)) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val sr = AUDIO_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(
                // VOICE_RECOGNITION: raw mic path, no noise suppression / speech AGC.
                // (VOICE_COMMUNICATION's noise suppressor was erasing the claps.)
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sr / 2) * 2
            )
        } catch (_: Exception) { null } ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }

        clapRecord = rec
        try { rec.startRecording() } catch (_: Exception) {
            rec.release(); clapRecord = null; return
        }

        // Fail-safe: mute the camera feed's playback while we listen, so the
        // baby's cry from this phone's speaker can't fake a double-clap.
        // Cry DETECTION is unaffected (playback capture taps the stream
        // before the volume control).
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (duckedMediaVolume < 0) {
            duckedMediaVolume = try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
            if (duckedMediaVolume >= 0) {
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) } catch (_: Exception) {}
            }
        }

        val debug = prefs.getBoolean("clapDebugEnabled", false)

        clapThread = Thread {
            val chunk = ShortArray(sr / 20) // 50 ms
            var floor = 500.0
            var prevSpike = false
            var lastClapAt = 0L
            var chunksSeen = 0
            var detected = false
            val windowStart = System.currentTimeMillis()
            val pcm = if (debug) java.io.ByteArrayOutputStream() else null
            val log = if (debug) StringBuilder("ms,rms,peak,floor,thr,spike,edge\n") else null
            val pcmCap = sr * 2 * 60 // at most 60 s of audio per window

            while (!Thread.currentThread().isInterrupted && clapRecord === rec) {
                val n = try { rec.read(chunk, 0, chunk.size) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n == 0) continue

                var sum = 0.0
                var peak = 0
                for (i in 0 until n) {
                    val v = chunk[i].toInt()
                    val a = abs(v)
                    if (a > peak) peak = a
                    sum += v.toDouble() * v
                }
                val rms = sqrt(sum / n)
                chunksSeen++

                if (pcm != null && pcm.size() < pcmCap) {
                    for (i in 0 until n) {
                        val v = chunk[i].toInt()
                        pcm.write(v and 0xFF)
                        pcm.write((v shr 8) and 0xFF)
                    }
                }

                val peakMin = clapPeakMin()
                val isSpike = chunksSeen > 4 &&
                    peak > peakMin &&
                    rms > floor * CLAP_FLOOR_FACTOR
                if (!isSpike) floor = 0.9 * floor + 0.1 * rms // adapt floor on quiet chunks only

                val edge = isSpike && !prevSpike // rising edge = one clap
                log?.append(System.currentTimeMillis() - windowStart)?.append(',')
                    ?.append(rms.toInt())?.append(',')?.append(peak)?.append(',')
                    ?.append(floor.toInt())?.append(',')
                    ?.append(peakMin)?.append(',')
                    ?.append(if (isSpike) 1 else 0)?.append(',')
                    ?.append(if (edge) 1 else 0)?.append('\n')

                if (edge) {
                    mainHandler.post { clapFeedbackBlink() } // green flash: clap registered
                    val now = System.currentTimeMillis()
                    val gap = now - lastClapAt
                    if (lastClapAt != 0L && gap in CLAP_GAP_MIN_MS..CLAP_GAP_MAX_MS) {
                        detected = true
                        mainHandler.post { startSnooze() }
                        break
                    }
                    lastClapAt = now
                }
                prevSpike = isSpike
            }

            if (pcm != null && log != null && pcm.size() > 0) {
                writeClapDump(pcm.toByteArray(), log.toString(), detected)
            }
        }.also {
            it.name = "clap-detect"
            it.start()
        }
    }

    /** Brief GREEN flash: one clap was registered (clap again to snooze). */
    private fun clapFeedbackBlink() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 220, 90))
        }
        try {
            wm.addView(view, overlayParams())
        } catch (_: Exception) {
            return
        }
        mainHandler.postDelayed({
            try { wm.removeView(view) } catch (_: Exception) {}
        }, 250L)
    }

    private fun writeClapDump(pcm: ByteArray, log: String, detected: Boolean) {
        try {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val base = "clap_" + stamp + if (detected) "_detected" else "_missed"
            saveToDownloads("$base.wav", "audio/wav", wavBytes(pcm, AUDIO_SAMPLE_RATE))
            saveToDownloads("$base.csv", "text/csv", log.toByteArray())
            mainHandler.post {
                android.widget.Toast.makeText(
                    this,
                    "Clap debug saved: Downloads/BabyMotionAlert/$base",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Exception) {}
    }

    private fun wavBytes(pcm: ByteArray, sampleRate: Int): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(44 + pcm.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + pcm.size); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(pcm.size); bb.put(pcm)
        return bb.array()
    }

    private fun saveToDownloads(name: String, mime: String, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/BabyMotionAlert"
                )
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
        } else {
            val dir = java.io.File(getExternalFilesDir(null), "clapdebug").apply { mkdirs() }
            java.io.File(dir, name).writeBytes(data)
        }
    }

    private fun stopClapListener() {
        if (duckedMediaVolume >= 0) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, duckedMediaVolume, 0) } catch (_: Exception) {}
            duckedMediaVolume = -1
        }
        clapThread?.interrupt()
        clapThread = null
        clapAec?.let { try { it.release() } catch (_: Exception) {} }
        clapAec = null
        clapRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        clapRecord = null
    }

    // ---------------- Flashlight blink ----------------

    private var torchId: String? = null
    private var torchOn = false
    private var torchRunnable: Runnable? = null

    private fun startTorchBlink() {
        if (torchRunnable != null) return
        val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = try {
            val ids = cm.cameraIdList
            ids.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
            } ?: ids.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null } ?: return

        torchId = id
        torchOn = false
        torchRunnable = object : Runnable {
            override fun run() {
                val tid = torchId ?: return
                torchOn = !torchOn
                try { cm.setTorchMode(tid, torchOn) } catch (_: Exception) {}
                mainHandler.postDelayed(this, FLASH_TOGGLE_MS)
            }
        }.also { mainHandler.post(it) }
    }

    private fun stopTorchBlink() {
        torchRunnable?.let { mainHandler.removeCallbacks(it) }
        torchRunnable = null
        torchId?.let {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            try { cm.setTorchMode(it, false) } catch (_: Exception) {}
        }
        torchOn = false
        torchId = null
    }

    private fun startSound() {
        try {
            val savedUri = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("alarmSoundUri", null)
            val uri = savedUri?.let { android.net.Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
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

    /** Brief red blink(s) with NO sound -- used as "monitoring started" / "armed" signals. */
    private fun silentBlink(times: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this).apply {
            setBackgroundColor(Color.argb(140, 255, 0, 0))
        }
        try {
            wm.addView(view, overlayParams())
        } catch (_: Exception) {
            return
        }
        val onMs = 300L
        val offMs = 200L
        var step = 0
        val totalSteps = times * 2 - 1 // on,off,on ... ending on
        fun advance() {
            step++
            if (step > totalSteps) {
                try { wm.removeView(view) } catch (_: Exception) {}
                return
            }
            view.visibility = if (step % 2 == 0) View.VISIBLE else View.INVISIBLE
            mainHandler.postDelayed({ advance() }, if (step % 2 == 0) onMs else offMs)
        }
        view.visibility = View.VISIBLE
        mainHandler.postDelayed({ advance() }, onMs)
    }

    private fun overlayParams(): WindowManager.LayoutParams {
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
        return params
    }

    private fun startRedFlash() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this).apply {
            setBackgroundColor(Color.argb(140, 255, 0, 0))
        }
        try {
            wm.addView(view, overlayParams())
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
        stopTorchBlink()
        clapStopRunnable?.let { mainHandler.removeCallbacks(it) }
        clapStopRunnable = null
        stopClapListener()
        stopAudioDetection()
        removeFloatingButton()
        cancelSnoozeTicker()
        alarmActive = false
        pausedIndefinitely = false
        snoozeUntil = 0L
        armedFlashRunnable?.let { mainHandler.removeCallbacks(it) }
        armedFlashRunnable = null
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
