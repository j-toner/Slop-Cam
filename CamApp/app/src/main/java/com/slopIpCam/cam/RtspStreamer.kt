package com.slopIpCam.cam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

    private var currentPhysical: String? = null

    /**
     * Toggle between the main and ultra-wide back lenses while streaming.
     * Widest = shortest focal length. Pixels usually expose one logical
     * back camera and hide the ultra-wide as a physical sub-camera, so
     * fall back to physical switching when there's only one logical id.
     * Returns the label of the lens now in use, or null on failure.
     */
    fun toggleLens(): String? {
        if (!stream.isStreaming) return null
        val source = stream.videoSource as? Camera2Source ?: return null
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val focal = { id: String ->
            try {
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: Float.MAX_VALUE
            } catch (e: Exception) { Float.MAX_VALUE }
        }
        val backIds = cm.cameraIdList.filter {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        return try {
            if (backIds.size >= 2) {
                val sorted = backIds.sortedBy(focal)
                val wide = sorted.first()
                val next = if (source.getCurrentCameraId() == wide) sorted.last() else wide
                source.openCameraId(next)
                if (next == wide) "ultra-wide" else "main"
            } else {
                val physical = source.physicalCamerasAvailable()
                if (physical.size < 2) {
                    Log.w("RtspStreamer", "no second lens exposed")
                    return null
                }
                val sorted = physical.sortedBy(focal)
                val wide = sorted.first()
                val next = if (currentPhysical == wide) sorted.last() else wide
                source.openPhysicalCamera(next)
                currentPhysical = next
                if (next == wide) "ultra-wide" else "main"
            }
        } catch (e: Exception) {
            Log.e("RtspStreamer", "lens switch failed: ${e.message}")
            null
        }
    }

    /**
     * Tap the stream's camera frames for motion analysis (Y plane + dims).
     * Runs on the ImageReader thread; the camera stays owned by the stream.
     */
    fun addMotionFrameListener(cb: (ByteArray, Int, Int, Int) -> Unit): Boolean {
        val source = stream.videoSource as? Camera2Source ?: return false
        return try {
            source.addImageListener(320, 240, false,
                object : com.pedro.encoder.input.video.Camera2ApiManager.ImageCallback {
                    override fun onImageAvailable(image: android.media.Image) {
                        try {
                            val plane = image.planes[0]
                            val buf = plane.buffer
                            val luma = ByteArray(buf.remaining())
                            buf.get(luma)
                            cb(luma, image.width, image.height, plane.rowStride)
                        } finally {
                            runCatching { image.close() }
                        }
                    }
                })
            true
        } catch (e: Exception) {
            Log.e("RtspStreamer", "addImageListener failed: ${e.message}")
            false
        }
    }

    fun removeMotionFrameListener() {
        runCatching { (stream.videoSource as? Camera2Source)?.removeImageListener() }
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
