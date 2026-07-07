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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tvStatus)
        serviceSwitch = findViewById(R.id.switchService)
        motionSwitch = findViewById(R.id.switchMotion)

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
            serviceSwitch.isChecked = true // triggers startServiceWithPermissions
        }
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
