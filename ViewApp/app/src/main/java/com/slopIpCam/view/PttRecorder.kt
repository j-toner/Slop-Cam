package com.slopIpCam.view

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class PttRecorder(private val onChunk: (ByteArray) -> Unit) {
    private val sampleRate = 16000
    // getMinBufferSize returns an error code (<= 0) on some devices — fall
    // back to 1s of 16-bit mono rather than constructing a broken recorder
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).let { if (it > 0) it else sampleRate * 2 }
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (job?.isActive == true) return
        // a rapid stop()->start() can overlap the previous recorder's
        // release; construction or startRecording may then fail — bail
        // instead of crashing, the next press works
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            return
        }
        // the coroutine owns rec for its whole lifetime — no shared mutable
        // reference for stop() to null out mid-read
        job = scope.launch {
            try {
                val buf = ByteArray(bufferSize)
                while (isActive) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 0) onChunk(buf.copyOf(read))
                    else if (read < 0) break
                }
            } finally {
                try { rec.stop() } catch (_: IllegalStateException) {}
                rec.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
