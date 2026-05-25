package com.slopIpCam.cam

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

class MotionDetectorTest {
    private fun solidBitmap(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    @Test
    fun `identical frames produce no motion`() {
        val det = MotionDetector(sensitivity = 30, minChangedFraction = 0.01f)
        val frame = solidBitmap(0xFF808080.toInt())
        assertFalse(det.analyze(frame, frame))
    }

    @Test
    fun `completely different frames trigger motion`() {
        val det = MotionDetector(sensitivity = 30, minChangedFraction = 0.01f)
        val black = solidBitmap(0xFF000000.toInt())
        val white = solidBitmap(0xFFFFFFFF.toInt())
        assertTrue(det.analyze(black, white))
    }

    @Test
    fun `small brightness change below sensitivity does not trigger`() {
        val det = MotionDetector(sensitivity = 50, minChangedFraction = 0.01f)
        val dark = solidBitmap(0xFF303030.toInt())
        val slightlyLighter = solidBitmap(0xFF353535.toInt())
        assertFalse(det.analyze(dark, slightlyLighter))
    }
}
