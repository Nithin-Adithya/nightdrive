package com.nithin.nightcast

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the mirrored screen's H.264 stream onto a Surface.
 *
 * iOS sends a keyframe only when mirroring starts or the picture size changes, so the decoder
 * must survive the screen going away: while there is no Surface it keeps decoding into a
 * placeholder, and picks up drawing again when a new Surface arrives.
 *
 * Lip sync: sound takes a few hundred milliseconds to get through the audio hardware while a
 * decoded picture is ready at once, so each picture is held back until [audioClock] (the phone
 * timestamp now being heard) reaches the picture's own timestamp.
 */
internal class VideoPlayer(
    private val audioClock: () -> Long? = { null },
    private val onSize: (width: Int, height: Int) -> Unit
) {

    private val lock = Any()
    private val placeholderTexture = SurfaceTexture(false)
    private val placeholder = Surface(placeholderTexture)

    private var surface: Surface? = null
    @Volatile private var drawing = false

    private var codec: MediaCodec? = null
    private var config: H264.Config? = null
    private var inputs = LinkedBlockingQueue<Int>()
    private var callbackThread: HandlerThread? = null

    fun setSurface(target: Surface?) = synchronized(lock) {
        surface = target
        drawing = target != null
        val current = codec ?: return
        try {
            current.setOutputSurface(target ?: placeholder)
        } catch (e: RuntimeException) {
            // Without a way to move the decoder the picture is lost until the next keyframe.
            Log.w(TAG, "decoder would not switch surface", e)
            releaseLocked()
        }
    }

    /** Takes one Annex-B access unit. Called on the native mirror thread. */
    fun feed(data: ByteArray, length: Int, ptsNanos: Long) = synchronized(lock) {
        try {
            val incoming = H264.leadingConfig(data, length)
            if (incoming != null && (codec == null || !incoming.sameAs(config))) {
                startLocked(incoming)
            }
            val current = codec ?: return
            // Skipping a frame would smear every frame after it, so wait for the decoder instead.
            val index = inputs.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS) ?: return
            val buffer = current.getInputBuffer(index) ?: return
            buffer.clear()
            if (length > buffer.capacity()) {
                current.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }
            buffer.put(data, 0, length)
            current.queueInputBuffer(index, 0, length, ptsNanos / 1000, 0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "decoder failed", e)
            releaseLocked()
        }
    }

    fun release() = synchronized(lock) { releaseLocked() }

    private fun startLocked(newConfig: H264.Config) {
        releaseLocked()
        val (width, height) = H264.pictureSize(newConfig.sps) ?: (1920 to 1080)
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(newConfig.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(newConfig.pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 512 * 1024))
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        val queue = LinkedBlockingQueue<Int>()
        val thread = HandlerThread("nightcast-video").apply { start() }
        val handler = Handler(thread.looper)
        val created = MediaCodec.createDecoderByType(MIME)
        try {
            created.setCallback(Callback(queue, handler), handler)
            created.configure(format, surface ?: placeholder, null, 0)
            created.start()
        } catch (e: RuntimeException) {
            created.release()
            thread.quitSafely()
            throw e
        }
        codec = created
        config = newConfig
        inputs = queue
        callbackThread = thread
        Log.i(TAG, "decoding ${width}x$height")
    }

    private fun releaseLocked() {
        val current = codec ?: return
        codec = null
        config = null
        try {
            current.stop()
        } catch (e: RuntimeException) {
            // Already in an error state; release() below is still required.
        }
        current.release()
        callbackThread?.quitSafely()
        callbackThread = null
    }

    private inner class Callback(
        private val queue: LinkedBlockingQueue<Int>,
        private val handler: Handler
    ) : MediaCodec.Callback() {

        /** When the most recently scheduled picture is due, so pictures never overtake each other. */
        private var lastDueAt = 0L
        private var held = 0

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            queue.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val now = SystemClock.uptimeMillis()
            var dueAt = now
            val clock = if (drawing) audioClock() else null
            if (clock != null) {
                val aheadMillis = (info.presentationTimeUs * 1000 - clock) / 1_000_000
                // A gap this large means the two timestamps are unrelated, not that sound is late.
                if (aheadMillis in 1..MAX_TRUSTED_GAP_MS) dueAt = now + minOf(aheadMillis, MAX_HOLD_MS)
            }
            // Held pictures are decoder buffers it cannot reuse; past a few, stop adding delay.
            if (held >= MAX_HELD) dueAt = now
            dueAt = maxOf(dueAt, lastDueAt)
            lastDueAt = dueAt
            if (dueAt <= now) {
                show(codec, index)
            } else {
                held++
                handler.postAtTime({
                    held--
                    show(codec, index)
                }, dueAt)
            }
        }

        private fun show(codec: MediaCodec, index: Int) {
            try {
                codec.releaseOutputBuffer(index, drawing)
            } catch (e: RuntimeException) {
                // The decoder was released between the callback being posted and running.
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            var width = format.getInteger(MediaFormat.KEY_WIDTH)
            var height = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (format.containsKey(CROP_RIGHT) && format.containsKey(CROP_LEFT)) {
                width = format.getInteger(CROP_RIGHT) - format.getInteger(CROP_LEFT) + 1
            }
            if (format.containsKey(CROP_BOTTOM) && format.containsKey(CROP_TOP)) {
                height = format.getInteger(CROP_BOTTOM) - format.getInteger(CROP_TOP) + 1
            }
            onSize(width, height)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "decoder error", e)
        }
    }

    private companion object {
        const val TAG = "NightcastVideo"
        const val MIME = "video/avc"
        const val INPUT_WAIT_MS = 500L
        const val MAX_HOLD_MS = 250L
        const val MAX_TRUSTED_GAP_MS = 3000L
        const val MAX_HELD = 6
        const val CROP_LEFT = "crop-left"
        const val CROP_RIGHT = "crop-right"
        const val CROP_TOP = "crop-top"
        const val CROP_BOTTOM = "crop-bottom"
    }
}
