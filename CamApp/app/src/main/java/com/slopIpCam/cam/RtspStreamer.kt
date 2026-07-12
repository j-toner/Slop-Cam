package com.slopIpCam.cam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
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
        // callers pass the device's physical orientation (sensor-derived); if
        // somehow absent, fall back to the display-derived rotation rather
        // than the camera sensor's intrinsic orientation, which would encode
        // a wrongly-rotated (distorted) frame when the phone isn't portrait.
        val rot = if (rotation >= 0) rotation else displayRotation(context)
        if (!stream.prepareVideo(width, height, bitrateBps, rotation = rot)) {
            onError("prepareVideo failed")
            return
        }
        // attach AFTER prepareVideo: the video source's width/height are 0
        // until prepareVideo init()s it, and Camera2 builds the tap's
        // ImageReader from those dims immediately — attaching earlier threw
        // "image dimensions must be positive" and killed in-stream motion
        // detection for the whole process. The camera isn't running yet at
        // this point, so the reader still joins the session cleanly.
        if (!motionListenerAttached && motionFrameCb != null) {
            motionListenerAttached = attachMotionListener()
        }
        stream.getStreamClient().setProtocol(Protocol.TCP)
        stream.startStream(rtspUrl)
        Log.i("RtspStreamer", "streaming started")
    }

    fun stop() {
        if (stream.isStreaming) stream.stopStream()
    }

    // device physical orientation (0/90/180/270) from the display rotation;
    // the encoder rotation for that is (physical + 90) % 360.
    @Suppress("DEPRECATION")
    private fun displayRotation(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return (wm.defaultDisplay.rotation * 90 + 90) % 360
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

    /** Set before the first start(); frames arrive on the ImageReader thread. */
    var motionFrameCb: ((ByteArray, Int, Int, Int) -> Unit)? = null
    private var lastMotionFrameMs = 0L
    @Volatile private var tapFramesSeen = false

    /**
     * Tap the stream's camera frames for motion analysis (Y plane + dims).
     * Must be attached while the camera is NOT running — RootEncoder tears
     * the whole camera session down and rebuilds it otherwise. Called from
     * start() between prepareVideo (which sets the source dims the tap's
     * ImageReader is created from) and startStream (which opens the camera).
     * Returns whether the tap actually attached, so a failure is retried on
     * the next start instead of silently disabling motion-while-streaming.
     */
    private fun attachMotionListener(): Boolean {
        val cb = motionFrameCb ?: return false
        val source = stream.videoSource as? Camera2Source ?: return false
        return try {
            source.addImageListener(
                android.graphics.ImageFormat.YUV_420_888, /* maxImages = */ 2, false,
                object : com.pedro.encoder.input.video.Camera2ApiManager.ImageCallback {
                    override fun onImageAvailable(image: android.media.Image) {
                        try {
                            if (!tapFramesSeen) {
                                tapFramesSeen = true
                                Log.i("RtspStreamer", "motion tap delivering frames")
                            }
                            // ~2 fps is plenty for motion; skip the copy otherwise
                            val now = System.currentTimeMillis()
                            if (now - lastMotionFrameMs < 500) return
                            lastMotionFrameMs = now
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
            Log.i("RtspStreamer", "motion tap attached")
            true
        } catch (e: Exception) {
            Log.e("RtspStreamer", "addImageListener failed: ${e.message}")
            false
        }
    }

    private var motionListenerAttached = false

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
    // stop() matters: startStream set isStreaming synchronously and an auth
    // failure doesn't clear it — without the reset every retry no-ops on the
    // isStreaming guard and the cam is stuck showing "Streaming" while dead
    override fun onAuthError() { onError("RTSP auth error"); stop() }
    override fun onAuthSuccess() {}
}
