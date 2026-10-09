package com.example.babymotionalert

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, MotionDetectionService::class.java).apply {
                    action = MotionDetectionService.ACTION_START
                    putExtra(MotionDetectionService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MotionDetectionService.EXTRA_RESULT_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
                statusText.text = "Status: ARMING (20s grace, then monitoring)"
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Sound detection needs the microphone permission", Toast.LENGTH_LONG).show()
            }
        }

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                val uri: android.net.Uri? =
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (uri != null) {
                    prefs.edit().putString("alarmSoundUri", uri.toString()).apply()
                } else {
                    prefs.edit().remove("alarmSoundUri").apply() // back to system default
                }
                updateAlarmSoundButton()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        // ---- Motion sensitivity (log scale 0.05% .. 5%) ----
        val sensitivitySeek = findViewById<SeekBar>(R.id.sensitivitySeek)
        val sensitivityLabel = findViewById<TextView>(R.id.sensitivityLabel)
        val saved = prefs.getInt("triggerPercentX100", 20) // default 0.2%
        sensitivitySeek.max = 100
        sensitivitySeek.progress = percentX100ToProgress(saved)
        sensitivityLabel.text = labelFor(saved)

        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val x100 = progressToPercentX100(value)
                sensitivityLabel.text = labelFor(x100)
                prefs.edit().putInt("triggerPercentX100", x100).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---- Sound detection ----
        val soundSwitch = findViewById<Switch>(R.id.soundSwitch)
        val soundSeek = findViewById<SeekBar>(R.id.soundSeek)
        val soundLabel = findViewById<TextView>(R.id.soundLabel)

        soundSwitch.isChecked = prefs.getBoolean("soundEnabled", false)
        val savedSound = prefs.getInt("soundSensitivity", 50)
        soundSeek.progress = savedSound
        soundLabel.text = soundLabelFor(savedSound)

        soundSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("soundEnabled", checked).apply()
            if (checked && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            if (checked) {
                Toast.makeText(this, "Applies the next time you press Start", Toast.LENGTH_SHORT).show()
            }
        }

        soundSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                soundLabel.text = soundLabelFor(value)
                prefs.edit().putInt("soundSensitivity", value).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---- Alarm sound on/off + flashlight ----
        val alarmSoundSwitch = findViewById<Switch>(R.id.alarmSoundSwitch)
        alarmSoundSwitch.isChecked = prefs.getBoolean("alarmSoundEnabled", true)
        alarmSoundSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("alarmSoundEnabled", checked).apply()
        }

        val flashlightSwitch = findViewById<Switch>(R.id.flashlightSwitch)
        flashlightSwitch.isChecked = prefs.getBoolean("flashlightEnabled", false)
        flashlightSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("flashlightEnabled", checked).apply()
        }

        // ---- Double-clap snooze ----
        val clapSwitch = findViewById<Switch>(R.id.clapSwitch)
        clapSwitch.isChecked = prefs.getBoolean("clapSnoozeEnabled", true)
        clapSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("clapSnoozeEnabled", checked).apply()
            if (checked && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }

        // ---- Clap debug recording ----
        val clapDebugSwitch = findViewById<Switch>(R.id.clapDebugSwitch)
        clapDebugSwitch.isChecked = prefs.getBoolean("clapDebugEnabled", false)
        clapDebugSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("clapDebugEnabled", checked).apply()
        }

        // ---- Attend snooze duration (3..20 min) ----
        val snoozeSeek = findViewById<SeekBar>(R.id.snoozeSeek)
        val snoozeLabel = findViewById<TextView>(R.id.snoozeLabel)
        val savedSnooze = prefs.getInt("snoozeMinutes", 10).coerceIn(3, 20)
        snoozeSeek.max = 17 // 0..17 -> 3..20 min
        snoozeSeek.progress = savedSnooze - 3
        snoozeLabel.text = snoozeLabelFor(savedSnooze)

        snoozeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val minutes = value + 3
                snoozeLabel.text = snoozeLabelFor(minutes)
                prefs.edit().putInt("snoozeMinutes", minutes).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---- Alarm sound picker ----
        findViewById<Button>(R.id.alarmSoundButton).setOnClickListener {
            val current = prefs.getString("alarmSoundUri", null)?.let { android.net.Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            }
            ringtonePickerLauncher.launch(intent)
        }
        updateAlarmSoundButton()

        // ---- Buttons ----
        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, MotionDetectionService::class.java).apply {
                action = MotionDetectionService.ACTION_STOP
            })
            statusText.text = "Status: stopped"
        }

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            if (isClickerEnabled()) {
                Toast.makeText(
                    this,
                    "Already on \u2014 it will press Continue for you automatically",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Find 'Baby Motion Alert' in the list and switch it on",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            updateAccessibilityButton()
        }
        updateAccessibilityButton()

        findViewById<Button>(R.id.testButton).setOnClickListener {
            startService(Intent(this, MotionDetectionService::class.java).apply {
                action = MotionDetectionService.ACTION_TEST_ALARM
            })
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButton()
    }

    private fun isClickerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val full = "$packageName/${ReconnectClickerService::class.java.name}"
        val short = "$packageName/.ReconnectClickerService"
        return enabled.split(':').any {
            it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
        }
    }

    private fun updateAccessibilityButton() {
        val btn = findViewById<Button>(R.id.accessibilityButton)
        btn.text = if (isClickerEnabled())
            "Auto-reconnect clicker: ON \u2713"
        else
            "Enable auto-reconnect clicker"
    }

    // slider 0..100  ->  percentX100 5..500 (log scale)
    private fun progressToPercentX100(progress: Int): Int {
        val x100 = 5.0 * 10.0.pow(progress / 50.0)
        return x100.roundToInt().coerceIn(5, 500)
    }

    private fun percentX100ToProgress(x100: Int): Int {
        val p = 50.0 * log10(x100.coerceIn(5, 500) / 5.0)
        return p.roundToInt().coerceIn(0, 100)
    }

    private fun updateAlarmSoundButton() {
        val btn = findViewById<Button>(R.id.alarmSoundButton)
        val saved = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("alarmSoundUri", null)
        val name = if (saved == null) {
            "System default"
        } else {
            try {
                RingtoneManager.getRingtone(this, android.net.Uri.parse(saved))
                    ?.getTitle(this) ?: "Custom"
            } catch (_: Exception) {
                "Custom"
            }
        }
        btn.text = "Alarm sound: $name"
    }

    private fun labelFor(x100: Int): String {
        val pct = x100 / 100.0
        return "Trigger threshold: %.2f%% of screen changed (lower = more sensitive)".format(pct)
    }

    private fun soundLabelFor(value: Int): String {
        return "Sound sensitivity: $value/100 (higher = triggers on quieter sounds)"
    }

    private fun snoozeLabelFor(minutes: Int): String {
        return "Attend snooze: $minutes min (tap floating button when attending; auto re-arms)"
    }
}
