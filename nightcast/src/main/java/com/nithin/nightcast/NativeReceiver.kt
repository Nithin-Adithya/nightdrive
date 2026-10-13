package com.nithin.nightcast

import androidx.annotation.Keep

/**
 * The native AirPlay server (UxPlay's library behind jni_bridge.c). The on* methods are called
 * from the library's own threads, never the main thread.
 */
@Keep
internal class NativeReceiver(private val sink: Sink) {

    interface Sink {
        fun onConnections(count: Int)
        fun onClient(name: String, model: String)
        fun onPin(pin: String)
        fun onMirror(running: Boolean)
        fun onVideo(data: ByteArray, length: Int, ptsNanos: Long)
        fun onVideoReset(type: Int)
        fun onAudioFormat(compressionType: Int)
        fun onAudio(data: ByteArray, length: Int, isPcm: Boolean, ptsNanos: Long)
        fun onAudioFlush()
        fun onVolume(decibels: Float)
        fun onMetadata(dmap: ByteArray?)
        fun onCoverArt(image: ByteArray?)
        fun onRemoteControl(dacpId: String, activeRemote: String)
        fun onCastPlay(location: String, startSeconds: Float)
        fun onCastSeek(seconds: Float)
        fun onCastRate(rate: Float)
        fun onCastStop()

        /** A picture to show or keep; false if asked to show a kept one that is gone. */
        fun onPhoto(assetKey: String?, action: String?, jpeg: ByteArray?): Boolean

        /** The phone started (with seconds per picture) or stopped a slideshow. */
        fun onSlideshow(playing: Boolean, slideSeconds: Int)

        /** The answer to [nativeRequestSlide]; null when the phone has no picture with that number. */
        fun onSlidePhoto(jpeg: ByteArray?)
    }

    init {
        System.loadLibrary("nightcastjni")
        nativeInit()
    }

    private external fun nativeInit()

    /** Starts the server and advertises it. Returns the TCP port, or a negative number on failure. */
    external fun nativeStart(
        name: String,
        hwAddress: ByteArray,
        keyFile: String,
        registerFile: String,
        language: String,
        width: Int,
        height: Int,
        maxFps: Int,
        requirePin: Boolean,
        debug: Boolean
    ): Int

    external fun nativeStop()

    /** What the casting player is doing, for the phone's scrubber. Times are in seconds. */
    external fun nativeSetPlaybackInfo(
        duration: Double,
        position: Double,
        rate: Float,
        bufferEmpty: Boolean,
        bufferFull: Boolean,
        finished: Boolean
    )

    /** Asks the phone for picture [number] of the running slideshow, counting from 1. */
    external fun nativeRequestSlide(number: Int)

    private fun onConnections(count: Int) = sink.onConnections(count)
    private fun onClient(name: String, model: String) = sink.onClient(name, model)
    private fun onPin(pin: String) = sink.onPin(pin)
    private fun onMirror(running: Boolean) = sink.onMirror(running)

    private fun onVideo(data: ByteArray, length: Int, ptsNanos: Long, isH265: Boolean) {
        if (!isH265) sink.onVideo(data, length, ptsNanos)
    }

    private fun onVideoReset(type: Int) = sink.onVideoReset(type)
    private fun onAudioFormat(compressionType: Int, samplesPerFrame: Int) = sink.onAudioFormat(compressionType)
    private fun onAudio(data: ByteArray, length: Int, isPcm: Boolean, ptsNanos: Long) = sink.onAudio(data, length, isPcm, ptsNanos)
    private fun onAudioFlush() = sink.onAudioFlush()
    private fun onVolume(decibels: Float) = sink.onVolume(decibels)
    private fun onMetadata(dmap: ByteArray?) = sink.onMetadata(dmap)
    private fun onCoverArt(image: ByteArray?) = sink.onCoverArt(image)
    private fun onProgress(start: Long, current: Long, end: Long) = Unit
    private fun onRemoteControl(dacpId: String, activeRemote: String) = sink.onRemoteControl(dacpId, activeRemote)
    private fun onCastPlay(location: String, startSeconds: Float) = sink.onCastPlay(location, startSeconds)
    private fun onCastSeek(seconds: Float) = sink.onCastSeek(seconds)
    private fun onCastRate(rate: Float) = sink.onCastRate(rate)
    private fun onCastStop() = sink.onCastStop()
    private fun onPhoto(assetKey: String?, action: String?, jpeg: ByteArray?) = sink.onPhoto(assetKey, action, jpeg)
    private fun onSlideshow(playing: Boolean, slideSeconds: Int) = sink.onSlideshow(playing, slideSeconds)
    private fun onSlidePhoto(jpeg: ByteArray?) = sink.onSlidePhoto(jpeg)

    companion object {
        // reset_type_t in raop.h
        const val RESET_TAKEN_OVER = 0
        const val RESET_MIRROR_ENDED = 1
        const val RESET_CAST_ENDED = 2
        const val RESET_CAST_FINISHED = 3
        const val RESET_CAST_STARTING = 4
        const val RESET_MIRROR_TO_CAST = 5
    }
}
