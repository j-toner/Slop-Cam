package com.slopIpCam.view

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class PttRecorder(private val onChunk: (ByteArray) -> Unit) {
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (job?.isActive == true) return
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        rec.startRecording()
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
