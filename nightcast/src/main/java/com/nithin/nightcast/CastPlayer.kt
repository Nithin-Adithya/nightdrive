package com.nithin.nightcast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays what an app casts with its AirPlay button (YouTube, Safari, Photos videos). Unlike
 * mirroring, the phone sends no pictures: it hands over a stream address and this player fetches
 * and plays the video itself, reporting progress back for the phone's scrubber.
 *
 * Main thread only.
 */
internal class CastPlayer(
    context: Context,
    private val report: (duration: Double, position: Double, rate: Float, bufferEmpty: Boolean, bufferFull: Boolean, finished: Boolean) -> Unit,
    private val onChanged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    var active = false
        private set
    var width = 0
        private set
    var height = 0
        private set

    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                width = videoSize.width
                height = videoSize.height
                onChanged()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "cast playback failed", error)
                finish()
            }
        })
    }

    private val reporter = object : Runnable {
        override fun run() {
            sendReport()
            handler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    fun play(location: String, startSeconds: Float, surface: Surface?) {
        // The library serves app casts as playlists from a local proxy, whose address has no
        // file extension to recognise; direct links (Safari) are left for the player to sniff.
        val isPlaylist = location.contains("://localhost") || location.contains(".m3u8")
        val item = MediaItem.Builder().setUri(location).apply {
            if (isPlaylist) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        finished = false
        active = true
        width = 0
        height = 0
        if (surface != null) player.setVideoSurface(surface)
        player.setMediaItem(item, (startSeconds * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
        handler.removeCallbacks(reporter)
        handler.post(reporter)
        onChanged()
    }

    fun seek(seconds: Float) {
        if (active) player.seekTo((seconds * 1000).toLong())
    }

    /** The phone only ever asks for 1 (play) or 0 (pause). */
    fun setRate(rate: Float) {
        if (active) player.playWhenReady = rate != 0f
    }

    fun setVolume(gain: Float) {
        player.volume = gain
    }

    fun setSurface(surface: Surface?) {
        if (!active) return
        if (surface != null) player.setVideoSurface(surface) else player.clearVideoSurface()
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(reporter)
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurface()
        width = 0
        height = 0
        onChanged()
    }

    /** Ended or failed: say so, and the phone winds the session down on its next progress poll. */
    private fun finish() {
        finished = true
        sendReport()
    }

    private fun sendReport() {
        val duration = player.duration
        val known = duration != C.TIME_UNSET && duration > 0
        report(
            if (known) duration / 1000.0 else 0.0,
            if (known) player.currentPosition / 1000.0 else -1.0,
            if (player.isPlaying) 1f else 0f,
            player.playbackState == Player.STATE_BUFFERING,
            player.bufferedPercentage >= 99,
            finished
        )
    }

    private companion object {
        const val TAG = "NightcastCast"
        const val REPORT_INTERVAL_MS = 500L
    }
}
