package com.slopIpCam.cam

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CamService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var wsClient: WsClient
    private lateinit var flashlight: FlashlightManager
    private lateinit var pttPlayer: PttPlayer
    private lateinit var motionDetector: MotionDetector
    private var rtspStreamer: RtspStreamer? = null

    private lateinit var handler: Handler
    private var pollRunnable: Runnable? = null
    private lateinit var analysisExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var lastSnapshotMs = 0L
    @Volatile private var oneShotSnapshot = false

    companion object {
        const val CHANNEL_ID = "slopIpCam"
        const val NOTIF_ID = 1
        const val KEY_STATUS = "service_status"
        // the status pref survives process death, so it can read "Reconnecting..."
        // while nothing is running — this flag is the live truth for the UI
        @Volatile var running = false
            private set
        const val EXTRA_TAKE_SNAPSHOT = "take_snapshot"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CamService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CamService::class.java))
        fun snapshot(ctx: Context) = ctx.startService(
            Intent(ctx, CamService::class.java).putExtra(EXTRA_TAKE_SNAPSHOT, true))
    }

    override fun onCreate() {
        running = true
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Idle"))

        handler = Handler(mainLooper)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val wsUrl = (prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws") ?: "ws://100.x.x.x:8080/ws") + "?role=cam"
        val mediamtxHost = prefs.getString("mediamtx_host", "100.x.x.x") ?: "100.x.x.x"
        val rtspUser = prefs.getString("rtsp_user", "slopcam") ?: "slopcam"
        val rtspPass = prefs.getString("rtsp_pass", "") ?: ""
        // no baked-in default: publish auth fails closed until the user sets
        // the password (matching mediamtx.yml authInternalUsers) in Settings
        val rtspUrl = if (rtspUser.isNotEmpty() && rtspPass.isNotEmpty()) {
            "rtsp://${URLEncoder.encode(rtspUser, "UTF-8")}:${URLEncoder.encode(rtspPass, "UTF-8")}@$mediamtxHost:8554/cam"
        } else null

        flashlight = FlashlightManager(this)
        pttPlayer = PttPlayer()
        motionDetector = MotionDetector(
            sensitivity = prefs.getInt("motion_sensitivity", 30)
        )
        rtspStreamer = RtspStreamer(this) { err -> updateNotification("Stream error: $err") }

        wsClient = WsClient(
            url = wsUrl,
            onText = { msg -> handleCommand(msg, rtspUrl) },
            onBinary = { pcm -> pttPlayer.write(pcm) },
            onConnected = { updateNotification(if (rtspStreamer?.isStreaming == true) "Streaming" else "Idle") },
            onDisconnected = { updateNotification("Reconnecting...") }
        )
        wsClient.connect()
        startMotionWatchPolling()
        updateNotification("Connecting...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_TAKE_SNAPSHOT, false) == true) takeSnapshot()
        return START_STICKY
    }

    // posted to the main handler so it serializes after a pending
    // START_STREAM — otherwise isStreaming reads stale mid-startup
    private fun takeSnapshot() = handler.post {
        val streamer = rtspStreamer
        if (streamer != null && streamer.isStreaming) {
            // stream owns the camera — pull the frame from the GL pipeline
            streamer.takePhoto { bmp -> analysisExecutor.execute { sendSnapshot(bmp) } }
        } else {
            // analyzer sends the next frame; if the camera isn't bound, bind it —
            // the poll loop unbinds again within 2s when motion watch is off
            oneShotSnapshot = true
            if (cameraProvider == null) startMotionWatch()
        }
    }

    private fun handleCommand(msg: String, rtspUrl: String?) {
        when {
            msg.startsWith("CMD:START_STREAM") -> {
                if (rtspUrl == null) {
                    updateNotification("Set RTSP password in Settings")
                    return
                }
                val res = msg.substringAfter("CMD:START_STREAM:").trim()
                val (w, h) = resolutionDimensions(res)
                // RTSP stream and MotionWatch cannot share the camera
                handler.post {
                    stopMotionWatch()
                    rtspStreamer?.start(rtspUrl, w, h)
                    updateNotification("Streaming")
                }
            }
            msg == "CMD:STOP_STREAM" -> {
                handler.post {
                    rtspStreamer?.stop()
                    updateNotification("Idle")
                    // poll loop re-enables motion watch if it's switched on
                }
            }
            msg == "CMD:SNAPSHOT" -> takeSnapshot()
            msg == "CMD:FLASHLIGHT_ON" -> setTorch(true)
            msg == "CMD:FLASHLIGHT_OFF" -> setTorch(false)
        }
    }

    private fun setTorch(on: Boolean) {
        val streamer = rtspStreamer
        // while streaming, the camera device is owned by RootEncoder —
        // CameraManager.setTorchMode would throw, use the stream's lantern
        if (streamer != null && streamer.isStreaming) {
            if (!streamer.setTorch(on)) flashlight.setTorch(on)
        } else {
            flashlight.setTorch(on)
        }
    }

    private fun resolutionDimensions(res: String) = when (res) {
        "360p" -> Pair(640, 360)
        "1080p" -> Pair(1920, 1080)
        else -> Pair(1280, 720)
    }

    private fun startMotionWatchPolling() {
        if (pollRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                val on = getSharedPreferences("runtime", MODE_PRIVATE)
                    .getBoolean("motion_watch", false)
                val streaming = rtspStreamer?.isStreaming == true
                if (on && !streaming && cameraProvider == null) startMotionWatch()
                else if ((!on || streaming) && cameraProvider != null && !oneShotSnapshot)
                    stopMotionWatch() // keep camera bound while a one-shot is pending
                handler.postDelayed(this, 2000)
            }
        }
        pollRunnable = r
        handler.post(r)
    }

    private fun startMotionWatch() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalMs = (prefs.getString("snapshot_interval_s", "30")?.toLongOrNull() ?: 30L) * 1000L

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val streamer = rtspStreamer
            if (streamer?.isStreaming == true) { // lost the race to a stream start
                if (oneShotSnapshot) { // redirect a pending one-shot to the stream
                    oneShotSnapshot = false
                    streamer.takePhoto { bmp -> analysisExecutor.execute { sendSnapshot(bmp) } }
                }
                return@addListener
            }
            cameraProvider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            var prevLuma: ByteArray? = null
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                try {
                    if (oneShotSnapshot) {
                        oneShotSnapshot = false
                        sendSnapshot(proxy.toBitmap()) // manual snap skips the interval limit
                    }
                    val plane = proxy.planes[0]
                    val buf = plane.buffer
                    val luma = ByteArray(buf.remaining())
                    buf.get(luma)
                    val prev = prevLuma
                    if (prev != null && prev.size == luma.size &&
                        motionDetector.analyze(luma, prev, proxy.width, proxy.height, plane.rowStride)
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastSnapshotMs >= intervalMs) {
                            lastSnapshotMs = now
                            sendSnapshot(proxy.toBitmap())
                        }
                    }
                    prevLuma = luma
                } finally {
                    proxy.close()
                }
            }
            cameraProvider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopMotionWatch() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    // runs on analysisExecutor — JPEG compression stays off the main thread
    private fun sendSnapshot(bmp: Bitmap) {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        wsClient.sendBinary(out.toByteArray())
    }

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
        // MainActivity mirrors this via a preference listener
        getSharedPreferences("runtime", MODE_PRIVATE)
            .edit().putString(KEY_STATUS, status).apply()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "SlopCam", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        running = false
        getSharedPreferences("runtime", MODE_PRIVATE)
            .edit().putString(KEY_STATUS, "Off").apply()
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        stopMotionWatch()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        wsClient.disconnect()
        rtspStreamer?.stop()
        pttPlayer.release()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
