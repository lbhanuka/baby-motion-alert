package com.example.babymotionalert

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

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
                statusText.text = "Status: MONITORING"
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val sensitivitySeek = findViewById<SeekBar>(R.id.sensitivitySeek)
        val sensitivityLabel = findViewById<TextView>(R.id.sensitivityLabel)
        // Stored value = % of pixels that must change to trigger, x10 (so 15 = 1.5%)
        val saved = prefs.getInt("triggerPercentX10", 15)
        sensitivitySeek.progress = saved
        sensitivityLabel.text = labelFor(saved)

        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value.coerceAtLeast(2) // never allow 0 -> constant alarms
                sensitivityLabel.text = labelFor(v)
                prefs.edit().putInt("triggerPercentX10", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

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

        findViewById<Button>(R.id.testButton).setOnClickListener {
            startService(Intent(this, MotionDetectionService::class.java).apply {
                action = MotionDetectionService.ACTION_TEST_ALARM
            })
        }
    }

    private fun labelFor(vX10: Int): String {
        val pct = vX10 / 10.0
        return "Trigger threshold: $pct% of screen changed (lower = more sensitive)"
    }
}
