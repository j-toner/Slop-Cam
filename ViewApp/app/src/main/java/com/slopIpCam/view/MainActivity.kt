package com.slopIpCam.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import org.webrtc.*

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var wsClient: WsClient
    private lateinit var pttRecorder: PttRecorder
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var webRtcJob: Job? = null
    private lateinit var eglBase: EglBase
    private lateinit var renderer: SurfaceViewRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val resolution = prefs.getString("stream_resolution", "720p") ?: "720p"
        val wsUrl = (prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws")
            ?: "ws://100.x.x.x:8080/ws") + "?role=viewer&res=$resolution"
        val mediamtxBase = prefs.getString("mediamtx_base_url", "http://100.x.x.x:8889")
            ?: "http://100.x.x.x:8889"

        eglBase = EglBase.create()
        renderer = findViewById(R.id.webrtcView)
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(false)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        pttRecorder = PttRecorder { pcm -> wsClient.sendBinary(pcm) }

        wsClient = WsClient(
            url = wsUrl,
            onText = { msg -> handleServerMsg(msg) },
            onConnected = {
                setStatus("Connected")
                restartWebRtcIfNeeded(mediamtxBase)
            },
            onDisconnected = { setStatus("Reconnecting...") }
        )
        wsClient.connect()

        findViewById<ToggleButton>(R.id.btnFlashlight).setOnCheckedChangeListener { _, on ->
            wsClient.sendText(if (on) "CMD:FLASHLIGHT_ON" else "CMD:FLASHLIGHT_OFF")
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
            wsClient.sendText("CMD:SNAPSHOT")
            Toast.makeText(this, "Snapshot requested", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSnapshots).setOnClickListener {
            startActivity(Intent(this, SnapshotsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { findViewById<TextView>(R.id.tvStatus).text = text }
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
        if (msg.startsWith("SNAPSHOT:")) {
            runOnUiThread {
                Toast.makeText(this, "New motion snapshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        wsClient.disconnect()
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
