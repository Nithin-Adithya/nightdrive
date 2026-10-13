package com.nithin.nightdrive

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings

/** Tracks the most relevant active media session. Use from the main thread only. */
class NowPlaying(private val context: Context, private val onChange: (State) -> Unit) {

    data class State(
        val hasAccess: Boolean = false,
        val active: Boolean = false,
        val playing: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val art: Bitmap? = null,
        val packageName: String? = null,
        /** Track length in milliseconds, or 0 when unknown (for example a live stream). */
        val duration: Long = 0
    )

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private var controller: MediaController? = null
    private var listening = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = pick(null)
    }

    val packageName: String? get() = controller?.packageName

    fun start() {
        if (!hasAccess()) {
            publish()
            return
        }
        try {
            if (!listening) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component)
                listening = true
            }
            pick(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            publish()
        }
    }

    fun stop() {
        if (listening) {
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
            listening = false
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    fun playPause() {
        val c = controller ?: return
        if (isPlaying(c)) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    /** Current playback position in milliseconds, advanced from the session's last report. */
    fun position(): Long {
        val state = controller?.playbackState ?: return 0
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val since = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (since * state.playbackSpeed).toLong()
        }
        return maxOf(0L, position)
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    private fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun pick(sessions: List<MediaController>?) {
        val next = sessions?.firstOrNull { isPlaying(it) } ?: sessions?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback)
        }
        publish()
    }

    private fun isPlaying(c: MediaController): Boolean {
        val state = c.playbackState?.state
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        onChange(
            State(
                hasAccess = hasAccess(),
                active = c != null,
                playing = c != null && isPlaying(c),
                title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                packageName = c?.packageName,
                duration = maxOf(0L, md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            )
        )
    }
}
