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

    fun write(pcm: ByteArray) { track.write(pcm, 0, pcm.size) }

    fun release() { track.stop(); track.release() }
}
