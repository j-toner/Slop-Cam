package com.slopIpCam.cam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var serviceSwitch: Switch
    private lateinit var motionSwitch: Switch

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
        serviceSwitch = findViewById(R.id.switchService)
        motionSwitch = findViewById(R.id.switchMotion)

        // reflect a service that is already running before attaching the
        // listener, so the programmatic setChecked doesn't restart it;
        // CamService.running (not the status pref) is the live truth —
        // the pref goes stale if the process died mid-"Reconnecting..."
        serviceSwitch.isChecked = CamService.running
        motionSwitch.isChecked = runtimePrefs().getBoolean("motion_watch", false)

        serviceSwitch.setOnCheckedChangeListener { _, on ->
            if (on) startServiceWithPermissions() else CamService.stop(this)
        }

        motionSwitch.setOnCheckedChangeListener { _, on ->
            getSharedPreferences("runtime", MODE_PRIVATE)
                .edit().putBoolean("motion_watch", on).apply()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            serviceSwitch.isChecked = true // no-op if already checked
            startServiceWithPermissions()  // so start explicitly, idempotent
        }
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
        if (cameraAndMicGranted && serviceSwitch.isChecked) {
            CamService.start(this)
        } else if (!cameraAndMicGranted) {
            serviceSwitch.isChecked = false
            statusText.text = "Camera/mic permission required"
        }
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val REQ_PERMS = 1
    }
}
