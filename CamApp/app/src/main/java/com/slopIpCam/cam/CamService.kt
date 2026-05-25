package com.slopIpCam.cam

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.preference.PreferenceManager

class CamService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var wsClient: WsClient
    private lateinit var flashlight: FlashlightManager
    private lateinit var pttPlayer: PttPlayer
    private var rtspStreamer: RtspStreamer? = null

    companion object {
        const val CHANNEL_ID = "slopIpCam"
        const val NOTIF_ID = 1
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CamService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CamService::class.java))
    }

    override fun onCreate() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Idle"))

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val wsUrl = (prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws") ?: "ws://100.x.x.x:8080/ws") + "?role=cam"
        val mediamtxHost = prefs.getString("mediamtx_host", "100.x.x.x") ?: "100.x.x.x"

        flashlight = FlashlightManager(this)
        pttPlayer = PttPlayer()
        rtspStreamer = RtspStreamer(this) { err -> updateNotification("Stream error: $err") }

        wsClient = WsClient(
            url = wsUrl,
            onText = { msg -> handleCommand(msg, mediamtxHost) },
            onBinary = { pcm -> pttPlayer.write(pcm) },
            onConnected = { updateNotification("Idle") },
            onDisconnected = { updateNotification("Reconnecting...") }
        )
        wsClient.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pollMotionWatch()
        return START_STICKY
    }

    private fun handleCommand(msg: String, mediamtxHost: String) {
        when {
            msg.startsWith("CMD:START_STREAM") -> {
                val res = msg.substringAfter("CMD:START_STREAM:").trim()
                val (w, h) = resolutionDimensions(res)
                rtspStreamer?.start("rtsp://$mediamtxHost:8554/cam", w, h)
                updateNotification("Streaming")
            }
            msg == "CMD:STOP_STREAM" -> {
                rtspStreamer?.stop()
                updateNotification("Idle")
            }
            msg == "CMD:FLASHLIGHT_ON" -> flashlight.setTorch(true)
            msg == "CMD:FLASHLIGHT_OFF" -> flashlight.setTorch(false)
        }
    }

    private fun resolutionDimensions(res: String) = when (res) {
        "360p" -> Pair(640, 360)
        "1080p" -> Pair(1920, 1080)
        else -> Pair(1280, 720)
    }

    private fun pollMotionWatch() {
        val handler = android.os.Handler(mainLooper)
        handler.post(object : Runnable {
            override fun run() {
                val on = getSharedPreferences("runtime", MODE_PRIVATE)
                    .getBoolean("motion_watch", false)
                if (on && cameraProvider == null) startMotionWatch()
                else if (!on && cameraProvider != null) stopMotionWatch()
                handler.postDelayed(this, 2000)
            }
        })
    }

    // Populated by MotionWatch extension (Task C10)
    var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null
    fun startMotionWatch() {}
    fun stopMotionWatch() {}

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SlopCam")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .build()
    }

    fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "SlopCam", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        wsClient.disconnect()
        rtspStreamer?.stop()
        pttPlayer.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
