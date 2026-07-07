package com.slopIpCam.cam

import org.junit.Assert.*
import org.junit.Test

class MotionDetectorTest {
    private val w = 100
    private val h = 100

    private fun solidLuma(value: Int) = ByteArray(w * h) { value.toByte() }

    @Test
    fun `identical frames produce no motion`() {
        val det = MotionDetector(sensitivity = 30, minChangedFraction = 0.01f)
        val frame = solidLuma(0x80)
        assertFalse(det.analyze(frame, frame, w, h, w))
    }

    @Test
    fun `completely different frames trigger motion`() {
        val det = MotionDetector(sensitivity = 30, minChangedFraction = 0.01f)
        assertTrue(det.analyze(solidLuma(0x00), solidLuma(0xFF), w, h, w))
    }

    @Test
    fun `small brightness change below sensitivity does not trigger`() {
        val det = MotionDetector(sensitivity = 50, minChangedFraction = 0.01f)
        assertFalse(det.analyze(solidLuma(0x30), solidLuma(0x35), w, h, w))
    }

    @Test
    fun `respects row stride padding`() {
        val stride = 128
        val det = MotionDetector(sensitivity = 30, minChangedFraction = 0.01f)
        val a = ByteArray(stride * h) { 0x00 }
        val b = ByteArray(stride * h) { 0x00 }
        // change only padding bytes beyond width — must NOT count as motion
        for (y in 0 until h) for (x in w until stride) b[y * stride + x] = 0xFF.toByte()
        assertFalse(det.analyze(a, b, w, h, stride))
    }

    @Test
    fun `localized change above fraction triggers`() {
        val det = MotionDetector(sensitivity = 30, minChangedFraction = 0.02f, sampleStep = 4)
        val a = solidLuma(0x00)
        val b = solidLuma(0x00)
        // light up top 10 rows (10% of frame)
        for (y in 0 until 10) for (x in 0 until w) b[y * w + x] = 0xFF.toByte()
        assertTrue(det.analyze(b, a, w, h, w))
    }
}
