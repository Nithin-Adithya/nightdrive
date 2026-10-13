package com.nithin.nightcast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Plays the AirPlay audio stream, always 44.1 kHz stereo. Music arrives as ALAC, already decoded
 * to PCM by the native side; screen-mirroring audio arrives as AAC-ELD and is decoded here.
 * Frames are played as they arrive, on a thread of their own so the network thread never waits
 * on the audio hardware.
 *
 * It is also the clock the mirrored picture is timed against: every frame carries the phone's
 * timestamp, and [clockNanos] says which timestamp is coming out of the speakers right now.
 */
internal class AudioPlayer {

    private class Frame(val data: ByteArray, val length: Int, val isPcm: Boolean, val ptsNanos: Long)

    private val queue = LinkedBlockingDeque<Frame>()
    private var thread: Thread? = null

    @Volatile private var compressionType = 0
    @Volatile private var flushRequested = false
    @Volatile private var gain = 1f
    @Volatile private var muted = false

    // What has been handed to the AudioTrack, for clockNanos(). Guarded by clockLock.
    private val clockLock = Any()
    private val timestamp = AudioTimestamp()
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var writtenUntilNanos = 0L

    /** SystemClock.uptimeMillis() of the last frame received; 0 before any. */
    @Volatile var lastFrameAt = 0L
        private set

    @Synchronized
    fun configure(compressionType: Int) {
        this.compressionType = compressionType
        if (thread == null) {
            thread = Thread(::run, "nightcast-audio").apply { start() }
        }
    }

    fun enqueue(data: ByteArray, length: Int, isPcm: Boolean, ptsNanos: Long) {
        // A music stream opens with a couple of seconds sent at once; past that, something is stuck.
        if (queue.size >= MAX_QUEUED_FRAMES) queue.pollFirst()
        queue.addLast(Frame(data, length, isPcm, ptsNanos))
        lastFrameAt = SystemClock.uptimeMillis()
    }

    /** Drops what is buffered: the phone paused, skipped track or scrubbed. */
    fun flush() {
        queue.clear()
        flushRequested = true
    }

    /** AirPlay volume: -30 dB (quietest) to 0 dB (full), or -144 for mute. */
    fun setVolume(decibels: Float) {
        gain = if (decibels <= -30f) 0f else 10f.pow(decibels.coerceAtMost(0f) / 20f)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    /**
     * The phone-clock timestamp being heard at this moment, or null when nothing is playing.
     * It is the timestamp written last, minus what is still waiting inside the AudioTrack.
     */
    fun clockNanos(): Long? = synchronized(clockLock) {
        val current = track ?: return null
        if (framesWritten == 0L || SystemClock.uptimeMillis() - lastFrameAt > CLOCK_STALE_MS) return null
        val played = try {
            if (current.getTimestamp(timestamp)) {
                timestamp.framePosition + (System.nanoTime() - timestamp.nanoTime) * SAMPLE_RATE / 1_000_000_000L
            } else {
                current.playbackHeadPosition.toLong() and 0xffffffffL
            }
        } catch (e: RuntimeException) {
            return null
        }
        val waiting = (framesWritten - played).coerceIn(0, SAMPLE_RATE * 2L)
        writtenUntilNanos - waiting * 1_000_000_000L / SAMPLE_RATE
    }

    private fun write(target: AudioTrack, pcm: ByteArray, length: Int, ptsNanos: Long) {
        target.write(pcm, 0, length)
        val frames = length / BYTES_PER_SAMPLE_PAIR
        synchronized(clockLock) {
            framesWritten += frames
            writtenUntilNanos = ptsNanos + frames * 1_000_000_000L / SAMPLE_RATE
        }
    }

    @Synchronized
    fun stop() {
        val running = thread ?: return
        thread = null
        running.interrupt()
        running.join(1000)
        queue.clear()
        lastFrameAt = 0
    }

    private fun run() {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE * BYTES_PER_SAMPLE_PAIR / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        var decoder: MediaCodec? = null
        var decoderType = 0
        var appliedVolume = -1f
        val info = MediaCodec.BufferInfo()
        var pcm = ByteArray(8192)
        synchronized(clockLock) {
            this.track = track
            framesWritten = 0
        }
        try {
            track.play()
            while (!Thread.currentThread().isInterrupted) {
                val volume = if (muted) 0f else gain
                if (volume != appliedVolume) {
                    track.setVolume(volume)
                    appliedVolume = volume
                }
                if (flushRequested) {
                    flushRequested = false
                    track.pause()
                    track.flush()
                    synchronized(clockLock) { framesWritten = 0 }
                    track.play()
                    decoder?.flush()
                }
                val frame = queue.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue
                if (frame.isPcm) {
                    write(track, frame.data, frame.length, frame.ptsNanos)
                    continue
                }
                val type = compressionType
                if (!looksValid(type, frame.data[0].toInt() and 0xff)) continue
                if (decoder == null || decoderType != type) {
                    decoder?.release()
                    decoder = createAacDecoder(type)
                    decoderType = type
                }
                val input = decoder.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = decoder.getInputBuffer(input)!!
                    buffer.clear()
                    buffer.put(frame.data, 0, frame.length)
                    decoder.queueInputBuffer(input, 0, frame.length, frame.ptsNanos / 1000, 0)
                }
                while (true) {
                    val output = decoder.dequeueOutputBuffer(info, 0)
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (output < 0) break
                    val buffer = decoder.getOutputBuffer(output)!!
                    if (info.size > pcm.size) pcm = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(pcm, 0, info.size)
                    decoder.releaseOutputBuffer(output, false)
                    write(track, pcm, info.size, info.presentationTimeUs * 1000)
                }
            }
        } catch (e: InterruptedException) {
            // stop() was called.
        } catch (e: RuntimeException) {
            Log.w(TAG, "audio playback failed", e)
        } finally {
            synchronized(clockLock) { this.track = null }
            decoder?.release()
            track.release()
        }
    }

    /** The first byte of a correctly decrypted frame is predictable; anything else would play as noise. */
    private fun looksValid(type: Int, firstByte: Int) = when (type) {
        TYPE_AAC_ELD -> firstByte in 0x8c..0x8e || firstByte in 0x80..0x82
        TYPE_AAC_LC -> firstByte == 0xff
        else -> true
    }

    private fun createAacDecoder(type: Int): MediaCodec {
        // AudioSpecificConfig for 44.1 kHz stereo: AAC-ELD with 480-sample frames, or plain AAC-LC.
        val specificConfig = if (type == TYPE_AAC_ELD) {
            byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
        } else {
            byteArrayOf(0x12, 0x10)
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(specificConfig))
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private companion object {
        const val TAG = "NightcastAudio"
        const val SAMPLE_RATE = 44100
        const val BYTES_PER_SAMPLE_PAIR = 4
        const val TYPE_AAC_LC = 4
        const val TYPE_AAC_ELD = 8
        const val MAX_QUEUED_FRAMES = 1500
        const val CLOCK_STALE_MS = 500L
    }
}
