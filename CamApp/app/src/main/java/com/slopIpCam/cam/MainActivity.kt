package com.slopIpCam.cam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Deliberately minimal: the camera service starts itself whenever this
 * screen opens (streaming, motion watch, and recording are all driven
 * remotely from the viewer app), leaving just Settings and power save.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    // strong reference required: SharedPreferences holds listeners weakly
    private val statusListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == CamService.KEY_STATUS) {
                statusText.text = "Status: ${prefs.getString(key, "Off")}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnDim).setOnClickListener { enterDimMode() }
        findViewById<View>(R.id.dimOverlay).setOnClickListener { exitDimMode() }

        startServiceWithPermissions()
    }

    private fun runtimePrefs() = getSharedPreferences("runtime", MODE_PRIVATE)

    override fun onStart() {
        super.onStart()
        val status = if (CamService.running)
            runtimePrefs().getString(CamService.KEY_STATUS, "Off") else "Off"
        statusText.text = "Status: $status"
        runtimePrefs().registerOnSharedPreferenceChangeListener(statusListener)
    }

    override fun onStop() {
        runtimePrefs().unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onStop()
    }

    private fun startServiceWithPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            CamService.start(this)
        } else {
            // service starts in onRequestPermissionsResult once granted
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val cameraAndMicGranted = requiredPermissions()
            .filter { it != Manifest.permission.POST_NOTIFICATIONS }
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (cameraAndMicGranted) {
            CamService.start(this)
        } else {
            statusText.text = "Camera/mic permission required"
        }
    }

    /**
     * Power save: black overlay + zero backlight, pixels effectively off on
     * OLED. Keeps the screen technically awake so Android never dozes the
     * camera service. Tap anywhere to wake.
     */
    private fun enterDimMode() {
        findViewById<View>(R.id.dimOverlay).visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0f }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitDimMode() {
        findViewById<View>(R.id.dimOverlay).visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start" // kept for BootReceiver's intent
        private const val REQ_PERMS = 1
    }
}
