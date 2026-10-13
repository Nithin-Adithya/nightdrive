package com.nithin.mediatest

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock

/** Starts (once) a fake playing media session with artwork, then closes itself. */
class DemoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (session == null) session = createSession()
        postNavigation()
        finish()
    }

    private fun createSession(): MediaSession {
        val session = MediaSession(applicationContext, "demo")
        var playing = true
        var position = 72_000L
        var since = SystemClock.elapsedRealtime()

        fun now() = if (playing) position + SystemClock.elapsedRealtime() - since else position
        fun publish() {
            since = SystemClock.elapsedRealtime()
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            session.setPlaybackState(
                PlaybackState.Builder().setActions(actions).setState(state, position, 1f).build()
            )
        }

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Coastline Drive")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test Signal")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 214_000L)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork())
                .build()
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                position = now()
                playing = true
                publish()
            }

            override fun onPause() {
                position = now()
                playing = false
                publish()
            }

            override fun onSeekTo(pos: Long) {
                position = pos
                publish()
            }
        })
        publish()
        session.isActive = true
        return session
    }

    /** A fake turn-by-turn notification, shaped like the one a navigation app keeps posted. */
    private fun postNavigation() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW)
        )
        val arrow = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(arrow)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 16f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        val turn = Path().apply {
            moveTo(52f, 124f)
            lineTo(52f, 60f)
            lineTo(108f, 60f)
            moveTo(84f, 34f)
            lineTo(110f, 60f)
            lineTo(84f, 86f)
        }
        canvas.drawPath(turn, paint)
        val notification = Notification.Builder(this, "nav")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setLargeIcon(arrow)
            .setContentTitle("350 m")
            .setContentText("Turn right onto MG Road")
            .setSubText("12 min · 5.4 km · 10:48 pm")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .build()
        manager.notify(1, notification)
    }

    private fun artwork(): Bitmap {
        val size = 400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFFFF2D75.toInt(), 0xFF7A2CFF.toInt(), 0xFF2D6BFF.toInt()), null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = RadialGradient(
            size * 0.72f, size * 0.3f, size * 0.4f, 0xFFFFC53D.toInt(), 0x00FFC53D, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return bitmap
    }

    companion object {
        var session: MediaSession? = null
    }
}
