package com.slopIpCam.cam

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.util.Log
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
    // shared by the CameraX analyzer thread and the stream's ImageReader thread
    @Volatile private var lastSnapshotMs = 0L
    @Volatile private var oneShotSnapshot = false

    // rotation follow: sensor-derived encoder rotation (-1 = no reading yet),
    // the rotation the live stream was started with, and the params needed
    // to restart it when the phone is turned
    private var deviceRotation = -1
    private var appliedRotation = -1
    // physical clockwise angle of the device (0/90/180/270) from the
    // orientation sensor — display rotation is frozen for a service
    @Volatile private var devicePhysicalDeg = 0
    @Volatile private var lastMotionEventMs = 0L
    private var activeRtspUrl: String? = null
    private var activeWidth = 1280
    private var activeHeight = 720
    private var orientationListener: android.view.OrientationEventListener? = null
    // last command wins: true after START_STREAM, false after STOP_STREAM —
    // error/retry paths only restart the stream while this is set
    @Volatile private var shouldStream = false
    // WS callbacks arrive on OkHttp threads and can land after onDestroy has
    // torn everything down (released player, stopped streamer) — gate them
    @Volatile private var destroyed = false
    // a ProcessCameraProvider future is in flight; stops the 2s poll loop
    // from queueing a second bind while the first hasn't resolved yet
    private var motionWatchStarting = false

    companion object {
        const val CHANNEL_ID = "slopIpCam"
        const val NOTIF_ID = 1
        const val KEY_STATUS = "service_status"
        // the status pref survives process death, so it can read "Reconnecting..."
        // while nothing is running — this flag is the live truth for the UI
        @Volatile var running = false
            private set
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CamService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CamService::class.java))
    }

    override fun onCreate() {
        running = true
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Idle"))

        handler = Handler(mainLooper)
        analysisExecutor = Executors.newSingleThreadExecutor()

        flashlight = FlashlightManager(this)
        pttPlayer = PttPlayer()
        rtspStreamer = RtspStreamer(this) { err ->
            updateNotification("Stream error: $err")
            handler.postDelayed({ tryStartStream() }, 5000)
        }.also { streamer ->
            streamer.motionFrameCb = { luma, w, h, stride ->
                if (motionWatchEnabled) onStreamMotionFrame(luma, w, h, stride)
            }
        }

        connectControl()
        // settings changes take effect without a manual service restart:
        // reconnect the control plane with the new URL/credentials
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)
        startMotionWatchPolling()
        startOrientationWatch()
        updateNotification("Connecting...")
    }

    // strong reference: SharedPreferences holds listeners weakly
    private val prefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("ctrl_server_url", "mediamtx_host", "rtsp_user",
                    "rtsp_pass", "motion_sensitivity")
            ) handler.post { if (!destroyed) reconnectControl() }
        }

    // publish URL for the current settings; null until a password is set
    @Volatile private var publishUrl: String? = null

    // (re)reads connection settings and (re)opens the control WebSocket —
    // called at startup and again whenever a connection pref changes
    private fun connectControl() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val wsUrl = (prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws") ?: "ws://100.x.x.x:8080/ws") + "?role=cam"
        val mediamtxHost = prefs.getString("mediamtx_host", "100.x.x.x") ?: "100.x.x.x"
        val rtspUser = prefs.getString("rtsp_user", "slopcam") ?: "slopcam"
        val rtspPass = prefs.getString("rtsp_pass", "") ?: ""
        // no baked-in default: publish auth fails closed until the user sets
        // the password (matching mediamtx.yml authInternalUsers) in Settings
        publishUrl = if (rtspUser.isNotEmpty() && rtspPass.isNotEmpty()) {
            "rtsp://${URLEncoder.encode(rtspUser, "UTF-8")}:${URLEncoder.encode(rtspPass, "UTF-8")}@$mediamtxHost:8554/cam"
        } else null
        motionDetector = MotionDetector(
            sensitivity = prefs.getInt("motion_sensitivity", 30)
        )
        wsClient = WsClient(
            url = wsUrl,
            onText = { msg -> if (!destroyed) handleCommand(msg) },
            onBinary = { pcm -> if (!destroyed) pttPlayer.write(pcm) },
            onConnected = { if (!destroyed) updateNotification(if (rtspStreamer?.isStreaming == true) "Streaming" else "Idle") },
            onDisconnected = { if (!destroyed) updateNotification("Reconnecting...") }
        )
        wsClient.connect()
    }

    private fun reconnectControl() {
        wsClient.disconnect()
        updateNotification("Settings changed, reconnecting...")
        connectControl()
    }

    private fun startOrientationWatch() {
        // Seed the orientation from the display *before* the sensor delivers
        // its first event. OrientationEventListener only fires on change, so
        // a phone already sitting in landscape (after a (re)start, or with
        // the screen off) would otherwise keep the -1 "unknown" rotation —
        // which the streamer falls back to the sensor's intrinsic orientation
        // and encodes a wrongly-rotated, distorted frame. The display
        // rotation is correct even with the screen off, since the device
        // hasn't physically moved.
        val seed = physicalDegFromDisplay()
        devicePhysicalDeg = seed
        deviceRotation = (seed + 90) % 360
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val quantized = ((deg + 45) / 90 % 4) * 90
                devicePhysicalDeg = quantized
                // sensor degrees are clockwise from natural portrait; the
                // encoder wants 90 for portrait, 0/180 for landscape
                val rotation = (quantized + 90) % 360
                if (rotation == deviceRotation) return
                deviceRotation = rotation
                // debounce: wait for the phone to settle before restarting
                handler.removeCallbacks(rotationRestart)
                handler.postDelayed(rotationRestart, 2000)
            }
        }.also { it.enable() }
    }

    // restart the live stream so the encoded frame matches the new
    // orientation — encoder dimensions cannot change mid-stream. The start
    // half is delayed: RootEncoder needs a beat to release the encoders and
    // camera after stop(), an immediate start() fails silently
    private val rotationRestart = Runnable {
        val streamer = rtspStreamer ?: return@Runnable
        if (!streamer.isStreaming || deviceRotation == appliedRotation) return@Runnable
        Log.i("CamService", "rotation changed to $deviceRotation, restarting stream")
        // viewers reconnect immediately instead of waiting for ICE failure
        wsClient.sendText("EVENT:STREAM_RESTART")
        streamer.stop()
        handler.postDelayed({ tryStartStream() }, 500)
    }

    // idempotent stream start; failures come back through the streamer's
    // onError, which reschedules this — so transient camera/network errors
    // retry instead of leaving a dead "Streaming" state
    private fun tryStartStream() {
        val streamer = rtspStreamer ?: return
        val url = activeRtspUrl ?: return
        if (!shouldStream || streamer.isStreaming) return
        // the poll loop may have re-bound motion watch while the stream was
        // down (error retry, rotation restart) — the camera must be free or
        // the two stacks evict each other in a loop
        stopMotionWatch()
        appliedRotation = if (deviceRotation >= 0) deviceRotation
        else (physicalDegFromDisplay() + 90) % 360
        streamPrevLuma = null // don't diff frames across stream sessions
        streamer.start(url, activeWidth, activeHeight, rotation = appliedRotation)
        if (streamer.isStreaming) updateNotification("Streaming")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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

    private fun handleCommand(msg: String) {
        when {
            msg.startsWith("CMD:START_STREAM") -> {
                val rtspUrl = publishUrl
                if (rtspUrl == null) {
                    updateNotification("Set RTSP password in Settings")
                    return
                }
                val res = msg.substringAfter("CMD:START_STREAM:").trim()
                val (w, h) = resolutionDimensions(res)
                // RTSP stream and MotionWatch cannot share the camera
                handler.post {
                    stopMotionWatch()
                    activeRtspUrl = rtspUrl
                    activeWidth = w
                    activeHeight = h
                    shouldStream = true
                    tryStartStream()
                }
            }
            msg == "CMD:STOP_STREAM" -> {
                handler.post {
                    shouldStream = false
                    rtspStreamer?.stop()
                    updateNotification("Idle")
                    // poll loop re-enables motion watch if it's switched on
                }
            }
            msg == "CMD:SNAPSHOT" -> takeSnapshot()
            msg == "CMD:FLASHLIGHT_ON" -> { muteMotionDetection(); setTorch(true) }
            msg == "CMD:FLASHLIGHT_OFF" -> { muteMotionDetection(); setTorch(false) }
            // remote motion config from the viewer (via ctrl-server):
            // snap=1 enables motion-snapshot uploads, detect=1 enables
            // motion detection (EVENT:MOTION). The two are independent — a
            // motion-recording viewer sends detect=1 with snap=0.
            msg.startsWith("CMD:MOTION:") -> handleMotionCmd(msg)
            msg == "CMD:SWITCH_LENS" -> handler.post {
                muteMotionDetection()
                val lens = rtspStreamer?.toggleLens()
                if (lens != null) wsClient.sendText("EVENT:LENS:$lens")
            }
        }
    }

    // CMD:MOTION:snap=1:detect=0 — snapshots and detection are tracked
    // separately so recording on motion works without forcing snapshots.
    private fun handleMotionCmd(msg: String) {
        val snap = msg.substringAfter("snap=", "0").substringBefore(":").toIntOrNull() ?: 0
        val detect = msg.substringAfter("detect=", "0").substringBefore(":").toIntOrNull() ?: 0
        getSharedPreferences("runtime", MODE_PRIVATE).edit().apply {
            putBoolean("motion_watch", snap == 1)
            putBoolean("motion_detect", detect == 1)
        }.apply()
    }

    // user-initiated camera changes (torch, lens switch) transform the whole
    // frame and would read as motion — mute detection while auto-exposure
    // resettles. Frame buffers keep updating during the mute, so the first
    // compared pair afterwards is recent and doesn't diff across the change.
    @Volatile private var motionMuteUntilMs = 0L

    private fun muteMotionDetection(ms: Long = 3000) {
        motionMuteUntilMs = System.currentTimeMillis() + ms
    }

    private fun motionMuted() = System.currentTimeMillis() < motionMuteUntilMs

    private fun setTorch(on: Boolean) {
        val streamer = rtspStreamer
        // while streaming, the camera device is owned by RootEncoder —
        // CameraManager.setTorchMode targets the same busy camera and would
        // just throw, so there is no fallback from a failed lantern call
        if (streamer != null && streamer.isStreaming) {
            streamer.setTorch(on)
        } else {
            flashlight.setTorch(on)
        }
    }

    // The device's physical orientation (0/90/180/270 clockwise from natural
    // portrait) taken from the display rotation — always available, even with
    // the screen off, unlike OrientationEventListener change events.
    @Suppress("DEPRECATION")
    private fun physicalDegFromDisplay(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return wm.defaultDisplay.rotation * 90
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
                val prefs = getSharedPreferences("runtime", MODE_PRIVATE)
                val watch = prefs.getBoolean("motion_watch", false)
                val detect = prefs.getBoolean("motion_detect", false)
                val streaming = rtspStreamer?.isStreaming == true
                // detection drives motion analysis + EVENT:MOTION; snapshots
                // (motion_watch) are saved only when the user enabled them
                motionSnapshots = watch
                motionWatchEnabled = watch || detect
                val needDetect = motionWatchEnabled
                if (needDetect && !streaming && cameraProvider == null) startMotionWatch()
                else if ((!needDetect || streaming) && cameraProvider != null && !oneShotSnapshot)
                    stopMotionWatch() // keep camera bound while a one-shot is pending
                handler.postDelayed(this, 2000)
            }
        }
        pollRunnable = r
        handler.post(r)
    }

    @Volatile private var motionWatchEnabled = false
    @Volatile private var motionSnapshots = false
    // written on the ImageReader thread, reset from the main thread on each
    // stream (re)start — volatile so the reset is actually seen
    @Volatile private var streamPrevLuma: ByteArray? = null

    // runs on the camera ImageReader thread
    private fun onStreamMotionFrame(luma: ByteArray, w: Int, h: Int, stride: Int) {
        val prev = streamPrevLuma
        streamPrevLuma = luma
        if (prev == null || prev.size != luma.size) return
        if (motionMuted()) return // user-initiated scene change settling
        if (!motionDetector.analyze(luma, prev, w, h, stride)) return
        val now = System.currentTimeMillis()
        if (now - lastMotionEventMs >= 5000) {
            lastMotionEventMs = now
            Log.i("CamService", "in-stream motion -> EVENT:MOTION")
            wsClient.sendText("EVENT:MOTION")
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalMs = (prefs.getString("snapshot_interval_s", "30")?.toLongOrNull() ?: 30L) * 1000L
        // snapshots are only saved when the user enabled motion snapshots —
        // detection + EVENT:MOTION still run regardless (see motionSnapshots)
        if (motionSnapshots && now - lastSnapshotMs >= intervalMs) {
            lastSnapshotMs = now
            // GL pipeline frame is already upright
            rtspStreamer?.takePhoto { bmp -> analysisExecutor.execute { sendSnapshot(bmp) } }
        }
    }

    private fun startMotionWatch() {
        if (motionWatchStarting) return // a provider future is already in flight
        motionWatchStarting = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalMs = (prefs.getString("snapshot_interval_s", "30")?.toLongOrNull() ?: 30L) * 1000L

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            motionWatchStarting = false
            // the future can resolve after onDestroy — binding a DESTROYED
            // lifecycle throws and would crash the (already gone) service
            if (destroyed) return@addListener
            val streamer = rtspStreamer
            // lost the race to a stream start: shouldStream is set before the
            // publish comes up, so check it too or this bind grabs the camera
            // out from under the starting stream
            if (streamer?.isStreaming == true || shouldStream) {
                if (oneShotSnapshot && streamer?.isStreaming == true) {
                    oneShotSnapshot = false // redirect a pending one-shot to the stream
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
                        // manual snap skips the interval limit
                        sendSnapshot(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
                    }
                    val plane = proxy.planes[0]
                    val buf = plane.buffer
                    val luma = ByteArray(buf.remaining())
                    buf.get(luma)
                    val prev = prevLuma
                    if (prev != null && prev.size == luma.size && !motionMuted() &&
                        motionDetector.analyze(luma, prev, proxy.width, proxy.height, plane.rowStride)
                    ) {
                        val now = System.currentTimeMillis()
                        // motion events drive notifications and motion-record
                        // clips; throttled independently of snapshots
                        if (now - lastMotionEventMs >= 5000) {
                            lastMotionEventMs = now
                            Log.i("CamService", "camerax motion -> EVENT:MOTION")
                            wsClient.sendText("EVENT:MOTION")
                        }
                        if (motionSnapshots && now - lastSnapshotMs >= intervalMs) {
                            lastSnapshotMs = now
                            sendSnapshot(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
                        }
                    }
                    prevLuma = luma
                } finally {
                    proxy.close()
                }
            }
            try {
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
            } catch (e: Exception) {
                Log.e("CamService", "motion watch bind failed: ${e.message}")
                stopMotionWatch() // poll loop retries while detection is wanted
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopMotionWatch() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    // runs on analysisExecutor — JPEG compression stays off the main thread.
    // analysisRotation is CameraX's upright rotation assuming the device sits
    // at its display rotation, which is frozen for a service — subtract the
    // real physical angle or snaps flip when the phone is rotated
    private fun sendSnapshot(bmp: Bitmap, analysisRotation: Int = 0) {
        val rot = if (analysisRotation == 0) 0
            else ((analysisRotation - devicePhysicalDeg) % 360 + 360) % 360
        val upright = if (rot == 0) bmp else Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true
        )
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, 80, out)
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
        destroyed = true // gate any WS callback still in flight on OkHttp threads
        running = false
        getSharedPreferences("runtime", MODE_PRIVATE)
            .edit().putString(KEY_STATUS, "Off").apply()
        shouldStream = false
        pollRunnable = null
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        orientationListener?.disable()
        // clears the poll loop, rotation restart, and any pending stream
        // retries — none may fire after the service is gone
        handler.removeCallbacksAndMessages(null)
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
