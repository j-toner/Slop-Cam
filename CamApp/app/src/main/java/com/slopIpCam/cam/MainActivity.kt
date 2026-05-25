package com.slopIpCam.cam

import android.Manifest
import android.content.pm.PackageManager
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
            if (on) {
                requestPermissionsIfNeeded()
                CamService.start(this)
            } else {
                CamService.stop(this)
            }
        }

        motionSwitch.setOnCheckedChangeListener { _, on ->
            getSharedPreferences("runtime", MODE_PRIVATE)
                .edit().putBoolean("motion_watch", on).apply()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
}
