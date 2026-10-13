package com.nithin.nightcast

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The screen: the mirrored picture when a phone is mirroring, the playing track when it is only
 * sending audio, and otherwise how to connect.
 */
class MirrorActivity : Activity(), SurfaceHolder.Callback, Receiver.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var idle: View
    private lateinit var nowPlaying: View
    private lateinit var pinPanel: View
    private lateinit var photo: ImageView
    private var shownPicture: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        root = findViewById(R.id.root)
        surface = findViewById(R.id.surface)
        idle = findViewById(R.id.idle)
        nowPlaying = findViewById(R.id.now_playing)
        pinPanel = findViewById(R.id.pin_panel)
        photo = findViewById(R.id.photo)
        surface.holder.addCallback(this)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }

        findViewById<View>(R.id.name).setOnClickListener { askForName() }
        findViewById<View>(R.id.auto_open).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        findViewById<View>(R.id.pin_toggle).setOnClickListener {
            Receiver.setRequirePin(this, !Receiver.requirePin)
        }
        findViewById<View>(R.id.forget).setOnClickListener { confirmForget() }
        findViewById<View>(R.id.previous).setOnClickListener { Receiver.remote?.previous() }
        findViewById<View>(R.id.play_pause).setOnClickListener { Receiver.remote?.playPause() }
        findViewById<View>(R.id.next).setOnClickListener { Receiver.remote?.next() }

        ReceiverService.start(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        Receiver.addListener(this)
        render()
    }

    override fun onStop() {
        Receiver.removeListener(this)
        visible = false
        super.onStop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Receiver.setSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = Receiver.setSurface(null)

    override fun onReceiverChanged() = render()

    private fun render() {
        val pin = Receiver.pin
        val mirroring = Receiver.mirroring || Receiver.casting
        val picture = if (mirroring) null else Receiver.photo
        val audioOnly = !mirroring && picture == null && Receiver.connections > 0 &&
            (Receiver.title != null || Receiver.playing)
        pinPanel.visibility = if (pin != null) View.VISIBLE else View.GONE
        photo.visibility = if (picture != null && pin == null) View.VISIBLE else View.GONE
        if (picture !== shownPicture) {
            shownPicture = picture
            photo.setImageBitmap(picture)
            if (picture != null) {
                photo.alpha = 0f
                photo.animate().alpha(1f).setDuration(PHOTO_FADE_MS).start()
            }
        }
        idle.visibility = if (mirroring || audioOnly || picture != null || pin != null) View.GONE else View.VISIBLE
        nowPlaying.visibility = if (audioOnly && pin == null) View.VISIBLE else View.GONE
        if (pin != null) {
            findViewById<TextView>(R.id.pin).text = pin.toCharArray().joinToString(" ")
        } else if (mirroring) {
            fitSurface()
        } else if (audioOnly) {
            renderNowPlaying()
        } else {
            renderIdle()
        }
    }

    private fun renderIdle() {
        val running = Receiver.port > 0
        val addresses = Receiver.localAddresses()
        findViewById<TextView>(R.id.name).text = Receiver.name
        findViewById<TextView>(R.id.hint).text = getString(R.string.hint_connect, Receiver.name)
        findViewById<TextView>(R.id.status).text = when {
            addresses.isEmpty() -> getString(R.string.status_no_network)
            !running -> getString(R.string.status_starting)
            else -> getString(R.string.status_network, addresses.joinToString("  ·  "))
        }
        findViewById<View>(R.id.auto_open).visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.pin_toggle).setText(
            if (Receiver.requirePin) R.string.pin_on else R.string.pin_off
        )
        findViewById<View>(R.id.forget).visibility =
            if (Receiver.requirePin && Receiver.hasPairedPhones(this)) View.VISIBLE else View.GONE
    }

    private fun renderNowPlaying() {
        val art = Receiver.coverArt
        findViewById<ImageView>(R.id.art).apply {
            if (art != null) setImageBitmap(art) else setImageResource(R.drawable.ic_cast)
            clipToOutline = true
        }
        findViewById<TextView>(R.id.track).text = Receiver.title ?: getString(R.string.audio_untitled)
        findViewById<TextView>(R.id.artist).text =
            listOfNotNull(Receiver.artist, Receiver.album).filter { it.isNotEmpty() }.joinToString(" — ")
        findViewById<TextView>(R.id.source).text =
            getString(R.string.status_playing, Receiver.clientName ?: getString(R.string.unknown_device))
        findViewById<View>(R.id.transport).visibility = if (Receiver.remoteAvailable) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.play_pause).setImageResource(
            if (Receiver.playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setMessage(R.string.forget_message)
            .setPositiveButton(R.string.forget) { _, _ -> Receiver.forgetPairedPhones(this) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Sizes the picture to the largest rectangle of the video's shape that fits the screen. */
    private fun fitSurface() {
        val videoWidth = Receiver.videoWidth
        val videoHeight = Receiver.videoHeight
        if (videoWidth <= 0 || videoHeight <= 0 || root.width == 0 || root.height == 0) return
        val scale = minOf(root.width.toFloat() / videoWidth, root.height.toFloat() / videoHeight)
        val width = (videoWidth * scale).toInt()
        val height = (videoHeight * scale).toInt()
        val params = surface.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height) {
            surface.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun askForName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(Receiver.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != Receiver.name) Receiver.rename(this, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PHOTO_FADE_MS = 400L

        /** Whether the screen is showing, so the service knows not to open it again. */
        @Volatile
        var visible = false
            private set
    }
}
