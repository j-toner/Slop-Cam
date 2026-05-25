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
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        record!!.startRecording()
        job = scope.launch {
            val buf = ByteArray(bufferSize)
            while (isActive) {
                val read = record!!.read(buf, 0, buf.size)
                if (read > 0) onChunk(buf.copyOf(read))
            }
        }
    }

    fun stop() {
        job?.cancel()
        record?.stop()
        record?.release()
        record = null
    }
}
