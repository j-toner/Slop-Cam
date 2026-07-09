package com.slopIpCam.cam

import android.content.Context
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtsp.RtspStream
import com.pedro.rtsp.rtsp.Protocol

class RtspStreamer(
    private val context: Context,
    private val onError: (String) -> Unit = {}
) : ConnectChecker {
    private val stream = RtspStream(context, this)

    val isStreaming: Boolean get() = stream.isStreaming

    fun start(
        rtspUrl: String,
        width: Int = 1280,
        height: Int = 720,
        bitrateBps: Int = 2_000_000,
        rotation: Int = -1
    ) {
        if (stream.isStreaming) return
        stream.prepareAudio(44100, true, 128_000)
        // match the encoded frame to the phone's orientation at stream start —
        // rotation 90/270 swaps encoder dims to portrait, otherwise the GL
        // pipeline squeezes the rotated image into a landscape frame.
        // callers pass a sensor-derived rotation because the display rotation
        // is frozen while the screen is off/locked
        val rot = if (rotation >= 0) rotation else CameraHelper.getCameraOrientation(context)
        if (!stream.prepareVideo(width, height, bitrateBps, rotation = rot)) {
            onError("prepareVideo failed")
            return
        }
        stream.getStreamClient().setProtocol(Protocol.TCP)
        stream.startStream(rtspUrl)
        Log.i("RtspStreamer", "streaming started")
    }

    fun stop() {
        if (stream.isStreaming) stream.stopStream()
    }

    /** Grab a frame from the live stream's GL pipeline. */
    fun takePhoto(cb: (android.graphics.Bitmap) -> Unit) {
        stream.getGlInterface().takePhoto { bmp -> cb(bmp) }
    }

    /** Torch control while the stream owns the camera device. */
    fun setTorch(on: Boolean): Boolean {
        val source = stream.videoSource as? Camera2Source ?: return false
        return try {
            if (on) source.enableLantern() else source.disableLantern()
            true
        } catch (e: Exception) {
            Log.e("RtspStreamer", "lantern failed: ${e.message}")
            false
        }
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { Log.i("RtspStreamer", "RTSP connected") }
    override fun onConnectionFailed(reason: String) { onError(reason); stop() }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        Log.i("RtspStreamer", "RTSP disconnected")
        // server-side teardown (e.g. mediamtx restart) must reset isStreaming,
        // otherwise a later start() no-ops forever on a dead session
        stop()
    }
    override fun onAuthError() { onError("RTSP auth error") }
    override fun onAuthSuccess() {}
}
