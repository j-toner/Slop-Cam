package com.slopIpCam.cam

import kotlin.math.abs

/**
 * Compares luma (Y) planes of consecutive frames. Operates directly on the
 * YUV_420_888 Y plane so no Bitmap allocation is needed per frame.
 */
class MotionDetector(
    private val sensitivity: Int = 30,
    private val minChangedFraction: Float = 0.02f,
    private val sampleStep: Int = 4
) {
    fun analyze(
        current: ByteArray,
        previous: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int
    ): Boolean {
        var changed = 0
        var total = 0
        for (y in 0 until height step sampleStep) {
            val row = y * rowStride
            for (x in 0 until width step sampleStep) {
                val idx = row + x
                if (idx >= current.size || idx >= previous.size) continue
                val currLuma = current[idx].toInt() and 0xff
                val prevLuma = previous[idx].toInt() and 0xff
                if (abs(currLuma - prevLuma) > sensitivity) changed++
                total++
            }
        }
        return total > 0 && (changed.toFloat() / total) >= minChangedFraction
    }
}
