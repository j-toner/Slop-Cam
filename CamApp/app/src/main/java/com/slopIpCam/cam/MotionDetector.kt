package com.slopIpCam.cam

import android.graphics.Bitmap
import kotlin.math.abs

class MotionDetector(
    private val sensitivity: Int = 30,
    private val minChangedFraction: Float = 0.02f,
    private val sampleStep: Int = 4
) {
    fun analyze(current: Bitmap, previous: Bitmap): Boolean {
        val w = current.width
        val h = current.height
        var changed = 0
        var total = 0
        for (y in 0 until h step sampleStep) {
            for (x in 0 until w step sampleStep) {
                val currLuma = luma(current.getPixel(x, y))
                val prevLuma = luma(previous.getPixel(x, y))
                if (abs(currLuma - prevLuma) > sensitivity) changed++
                total++
            }
        }
        return total > 0 && (changed.toFloat() / total) >= minChangedFraction
    }

    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
