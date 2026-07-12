package com.slopIpCam.cam

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue

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

    // write() is called from OkHttp's WS reader thread, and a blocking
    // AudioTrack.write there stalls every queued control command behind
    // real-time audio drain (OkHttp delivers messages one at a time on that
    // thread). A dedicated playback thread drains this queue instead; the
    // WS callback only enqueues. ~64 chunks ≈ 2.5s of 16kHz mono audio —
    // when the queue is full the oldest chunk is dropped to bound latency.
    private val queue = ArrayBlockingQueue<ByteArray>(64)
    @Volatile private var released = false

    private val playThread = Thread({
        try {
            while (!released) {
                val pcm = queue.take()
                track.write(pcm, 0, pcm.size)
            }
        } catch (e: InterruptedException) {
            // release() interrupts a blocked take()
        } catch (e: IllegalStateException) {
            // track released mid-write; done
        }
    }, "PttPlayback").apply { isDaemon = true }

    init {
        track.play()
        playThread.start()
    }

    fun write(pcm: ByteArray) {
        if (released) return
        if (!queue.offer(pcm)) {
            queue.poll() // full: drop the oldest chunk, keep latency bounded
            queue.offer(pcm)
        }
    }

    fun release() {
        released = true
        // interrupt unblocks take(); stop() makes an in-flight uninterruptible
        // track.write return early
        playThread.interrupt()
        track.stop()
        track.release()
    }
}
