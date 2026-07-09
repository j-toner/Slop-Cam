package com.slopIpCam.view

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import org.webrtc.*

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wsClient: WsClient? = null
    private var activeWsUrl: String? = null
    private var activeMediamtxBase: String? = null
    private lateinit var pttRecorder: PttRecorder
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var webRtcJob: Job? = null
    private lateinit var eglBase: EglBase
    private lateinit var renderer: SurfaceViewRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enterFullscreen()

        eglBase = EglBase.create()
        renderer = findViewById(R.id.webrtcView)
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(false)
        // show the whole frame at true aspect; letterbox only where unavoidable
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        pttRecorder = PttRecorder { pcm -> wsClient?.sendBinary(pcm) }

        findViewById<ToggleButton>(R.id.btnFlashlight).setOnCheckedChangeListener { _, on ->
            wsClient?.sendText(if (on) "CMD:FLASHLIGHT_ON" else "CMD:FLASHLIGHT_OFF")
        }

        val pttBtn = findViewById<Button>(R.id.btnPtt)
        pttBtn.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) pttRecorder.start()
                    else ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.RECORD_AUDIO), 2
                    )
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> pttRecorder.stop()
            }
            true
        }

        findViewById<Button>(R.id.btnSnap).setOnClickListener {
            wsClient?.sendText("CMD:SNAPSHOT")
            Toast.makeText(this, "Snapshot requested", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSnapshots).setOnClickListener {
            startActivity(Intent(this, SnapshotsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // onStart runs both on first launch and on return from Settings, so a
    // changed server address / resolution takes effect without an app restart
    override fun onStart() {
        super.onStart()
        reconnectIfConfigChanged()
        // if already connected, push toggle changes now; a fresh connection
        // syncs from its onConnected callback instead
        syncCamConfig()
        requestNotifPermissionIfNeeded()
    }

    /**
     * Push motion/security prefs to the cam and server. Only prefs the user
     * has actually touched are sent, so an untouched (or freshly installed)
     * viewer never stomps state set elsewhere. Commands are idempotent.
     */
    private fun syncCamConfig() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.contains("motion_snaps")) {
            wsClient?.sendText(
                if (prefs.getBoolean("motion_snaps", false)) "CMD:MOTION_ON"
                else "CMD:MOTION_OFF"
            )
        }
        if (prefs.contains("security_mode")) {
            if (prefs.getBoolean("security_mode", false)) {
                val fps = prefs.getString("record_fps", "1")
                val res = prefs.getString("record_resolution", "480p")
                wsClient?.sendText("CMD:SECURITY_ON:$fps:$res")
            } else {
                wsClient?.sendText("CMD:SECURITY_OFF")
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("motion_notify", false)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3
            )
        }
    }

    private fun reconnectIfConfigChanged() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val resolution = prefs.getString("stream_resolution", "720p") ?: "720p"
        val wsUrl = (prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws")
            ?: "ws://100.x.x.x:8080/ws") + "?role=viewer&res=$resolution"
        val mediamtxBase = prefs.getString("mediamtx_base_url", "http://100.x.x.x:8889")
            ?: "http://100.x.x.x:8889"
        if (wsUrl == activeWsUrl && mediamtxBase == activeMediamtxBase) return
        activeWsUrl = wsUrl
        activeMediamtxBase = mediamtxBase

        setStatus("Connecting...")
        wsClient?.disconnect()
        scope.launch {
            // stop a WHEP retry loop still aimed at the old address
            webRtcJob?.cancelAndJoin()
            webRtcJob = null
            peerConnection?.dispose()
            peerConnection = null
            val client = WsClient(
                url = wsUrl,
                onText = { msg -> handleServerMsg(msg) },
                onConnected = {
                    setStatus("Connected")
                    restartWebRtcIfNeeded(mediamtxBase)
                    syncCamConfig()
                },
                onDisconnected = { setStatus("Reconnecting...") }
            )
            wsClient = client
            client.connect()
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.tvStatus).apply {
                this.text = text
                val active = text == "Streaming" || text == "Connected"
                setTextColor(getColor(if (active) R.color.neon else R.color.text_dim))
            }
        }
    }

    /** Edge-to-edge video: hide status/nav bars, swipe from an edge peeks them back. */
    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen() // re-hide bars after dialogs/app switches
    }

    /** Called on every WS (re)connect — reuse a healthy PeerConnection, rebuild a dead one. */
    private fun restartWebRtcIfNeeded(mediamtxBase: String) {
        scope.launch {
            val state = peerConnection?.connectionState()
            if (state == PeerConnection.PeerConnectionState.CONNECTED ||
                state == PeerConnection.PeerConnectionState.CONNECTING
            ) return@launch
            webRtcJob?.cancelAndJoin()
            peerConnection?.dispose()
            peerConnection = null
            webRtcJob = launch(Dispatchers.IO) { startWebRtc(mediamtxBase) }
        }
    }

    private suspend fun startWebRtc(mediamtxBase: String) = coroutineScope {
        val whepUrl = "$mediamtxBase/cam/whep"
        val factory = peerConnectionFactory ?: return@coroutineScope

        val pc = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()),
            object : PeerConnection.Observer {
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onIceCandidate(c: IceCandidate) {}
                override fun onAddStream(stream: MediaStream) {
                    // Plan-B era callback, kept as fallback; addSink dedupes
                    stream.videoTracks.firstOrNull()?.addSink(renderer)
                }
                override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {
                    // Unified Plan delivery path — this is what actually fires
                    (r?.track() as? VideoTrack)?.addSink(renderer)
                }
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                    if (s == PeerConnection.PeerConnectionState.CONNECTED) setStatus("Streaming")
                    else if (s == PeerConnection.PeerConnectionState.FAILED) setStatus("Stream failed")
                }
            }
        ) ?: return@coroutineScope
        peerConnection = pc

        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))

        val offerDeferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { offerDeferred.complete(sdp) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) {
                offerDeferred.completeExceptionally(RuntimeException("createOffer: $e"))
            }
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
        val offerSdp = try { offerDeferred.await() } catch (e: Exception) {
            android.util.Log.e("MainActivity", "offer failed", e)
            return@coroutineScope
        }

        val localSet = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { localSet.complete(Unit) }
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) {
                localSet.completeExceptionally(RuntimeException("setLocal: $e"))
            }
        }, offerSdp)
        try { localSet.await() } catch (e: Exception) {
            android.util.Log.e("MainActivity", "setLocal failed", e)
            return@coroutineScope
        }

        // cam needs a moment to start publishing after START_STREAM — retry WHEP
        var answerSdp: String? = null
        var attempt = 0
        val whep = WhepClient(whepUrl)
        while (isActive && answerSdp == null) {
            answerSdp = whep.postOffer(offerSdp.description)
            if (answerSdp == null) {
                attempt++
                setStatus("Waiting for stream (retry $attempt)...")
                delay(minOf(1000L * attempt, 10_000L))
            }
        }
        if (answerSdp == null) return@coroutineScope

        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) {
                android.util.Log.e("MainActivity", "setRemote failed: $e")
                setStatus("Stream failed")
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    private fun handleServerMsg(msg: String) {
        when {
            msg == "EVENT:MOTION" -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                if (prefs.getBoolean("motion_notify", false)) notifyMotion()
            }
            msg.startsWith("SNAPSHOT:") -> runOnUiThread {
                Toast.makeText(this, "New snapshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun notifyMotion() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("motion", "Motion alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, SnapshotsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, "motion")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Motion detected")
            .setContentText("SlopCam saw movement — snapshot saved")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(2, notif)
    }

    override fun onDestroy() {
        wsClient?.disconnect()
        pttRecorder.stop()
        webRtcJob?.cancel()
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        renderer.release()
        eglBase.release()
        scope.cancel()
        super.onDestroy()
    }
}
