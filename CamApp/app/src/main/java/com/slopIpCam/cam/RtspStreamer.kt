package com.slopIpCam.cam

import android.content.Context
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.library.rtsp.RtspStream
import com.pedro.rtsp.rtsp.Protocol

class RtspStreamer(
    context: Context,
    private val onError: (String) -> Unit = {}
) : ConnectChecker {
    private val stream = RtspStream(context, this)

    fun start(rtspUrl: String, width: Int = 1280, height: Int = 720, bitrateBps: Int = 2_000_000) {
        if (stream.isStreaming) return
        stream.prepareAudio(44100, true, 128_000)
        if (!stream.prepareVideo(width, height, bitrateBps)) {
            onError("prepareVideo failed")
            return
        }
        stream.getStreamClient().setProtocol(Protocol.TCP)
        stream.startStream(rtspUrl)
        Log.i("RtspStreamer", "streaming to $rtspUrl")
    }

    fun stop() {
        if (stream.isStreaming) stream.stopStream()
    }

    override fun onConnectionSuccess() = Log.i("RtspStreamer", "RTSP connected")
    override fun onConnectionFailed(reason: String) { onError(reason); stop() }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() = Log.i("RtspStreamer", "RTSP disconnected")
    override fun onAuthError() = onError("RTSP auth error")
    override fun onAuthSuccess() {}
}
