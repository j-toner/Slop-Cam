package com.slopIpCam.cam

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class PttPlayer {
    private val sampleRate = 16000
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
        .build()

    init { track.play() }

    // writes arrive on OkHttp's WS reader thread and can race release() at
    // service teardown — a write on a released AudioTrack throws
    @Volatile private var released = false

    fun write(pcm: ByteArray) {
        if (released) return
        try {
            track.write(pcm, 0, pcm.size)
        } catch (e: IllegalStateException) {
            // released between the flag check and the write; drop the chunk
        }
    }

    fun release() {
        released = true
        track.stop()
        track.release()
    }
}
