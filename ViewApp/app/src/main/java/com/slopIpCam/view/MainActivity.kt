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
    private lateinit var eglBase: EglBase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val wsUrl = (prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws") ?: "") + "?role=viewer"
        val mediamtxBase = prefs.getString("mediamtx_base_url", "http://100.x.x.x:8889") ?: ""

        eglBase = EglBase.create()
        val renderer = findViewById<SurfaceViewRenderer>(R.id.webrtcView)
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
                runOnUiThread { findViewById<TextView>(R.id.tvStatus).text = "Connected" }
                startWebRtc(mediamtxBase)
            },
            onDisconnected = {
                runOnUiThread { findViewById<TextView>(R.id.tvStatus).text = "Reconnecting..." }
            }
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

        findViewById<Button>(R.id.btnSnapshots).setOnClickListener {
            startActivity(Intent(this, SnapshotsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun startWebRtc(mediamtxBase: String) {
        scope.launch(Dispatchers.IO) {
            val whepUrl = "$mediamtxBase/cam/whep"
            val renderer = findViewById<SurfaceViewRenderer>(R.id.webrtcView)

            val pc = peerConnectionFactory!!.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()),
                object : PeerConnection.Observer {
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
                    override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
                    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                    override fun onIceCandidate(c: IceCandidate) {}
                    override fun onAddStream(stream: MediaStream) {
                        stream.videoTracks.firstOrNull()?.addSink(renderer)
                    }
                    override fun onRemoveStream(s: MediaStream?) {}
                    override fun onDataChannel(d: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
                    override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {}
                }
            ) ?: return@launch
            peerConnection = pc

            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))

            val offerDeferred = CompletableDeferred<SessionDescription>()
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { offerDeferred.complete(sdp) }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) {}
            }, MediaConstraints())
            val offerSdp = offerDeferred.await()

            pc.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() {}
                override fun onCreateSuccess(s: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) {}
            }, offerSdp)

            val answerSdp = WhepClient(whepUrl).postOffer(offerSdp.description) ?: return@launch
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    runOnUiThread { findViewById<TextView>(R.id.tvStatus).text = "Streaming" }
                }
                override fun onCreateSuccess(s: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) {
                    android.util.Log.e("MainActivity", "setRemote failed: $e")
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }
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
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        eglBase.release()
        scope.cancel()
        super.onDestroy()
    }
}
