package com.nithin.nightcast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * The one AirPlay receiver in the process: owns the native server and the players, and holds
 * what the UI shows. ReceiverService keeps it alive; MirrorActivity lends it a Surface.
 */
internal object Receiver : NativeReceiver.Sink {

    fun interface Listener {
        /** Something readable on [Receiver] changed. Always called on the main thread. */
        fun onReceiverChanged()
    }

    private const val TAG = "NightcastReceiver"
    private const val PREFS = "nightcast"
    private const val KEY_NAME = "name"
    private const val KEY_HW_ADDRESS = "hw_address"
    private const val KEY_REQUIRE_PIN = "require_pin"
    private const val REGISTER_FILE = "paired_clients.txt"
    private const val DEFAULT_NAME = "Nightdrive"
    private const val MAX_FPS = 30
    private const val PLAYING_WINDOW_MS = 1500L
    private const val PIN_TIMEOUT_MS = 60_000L
    private const val HANDOVER_WAIT_MS = 1000L
    private const val MAX_KEPT_PHOTOS = 6

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val native by lazy { NativeReceiver(this) }

    val audio = AudioPlayer()
    val video = VideoPlayer(audioClock = audio::clockNanos) { width, height ->
        videoWidth = width
        videoHeight = height
        notifyChanged()
    }

    // Main thread only; made in start() because they need a Context.
    private var cast: CastPlayer? = null
    var remote: RemoteControl? = null
        private set
    private var surface: Surface? = null

    /** Pictures the phone sent ahead of showing them, oldest first, by its asset key. */
    private val keptPhotos = object : LinkedHashMap<String, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_KEPT_PHOTOS
    }
    @Volatile private var screenSize = 1920

    // A running slideshow. The phone only says "go"; this side fetches the pictures one ahead
    // and turns the pages. Main thread only.
    private var slideshow = false
    private var slideMillis = 3000L
    private var slideRequested = 0
    private var slideShown = false
    private var nextSlide: Bitmap? = null
    private val turnSlide = object : Runnable {
        override fun run() {
            nextSlide?.let {
                nextSlide = null
                photo = it
                notifyChanged()
                requestSlide(slideRequested + 1)
            }
            main.postDelayed(this, slideMillis)
        }
    }
    private val clearPin = Runnable {
        pin = null
        notifyChanged()
    }

    /** TCP port the server listens on, or -1 while it is not running. */
    @Volatile var port = -1; private set
    @Volatile var name = DEFAULT_NAME; private set
    @Volatile var requirePin = true; private set
    @Volatile var connections = 0; private set
    @Volatile var clientName: String? = null; private set
    /** The code a new phone has to type in, while one is waiting to pair. */
    @Volatile var pin: String? = null; private set
    @Volatile var mirroring = false; private set
    @Volatile var casting = false; private set
    @Volatile var playing = false; private set
    @Volatile var videoWidth = 0; private set
    @Volatile var videoHeight = 0; private set
    @Volatile var title: String? = null; private set
    @Volatile var artist: String? = null; private set
    @Volatile var album: String? = null; private set
    @Volatile var coverArt: Bitmap? = null; private set
    /** A picture sent with the AirPlay button in Photos, while it is being shown. */
    @Volatile var photo: Bitmap? = null; private set

    /** Whether the phone can be told to play, pause and skip. */
    val remoteAvailable: Boolean get() = remote?.available == true

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    private fun notifyChanged() {
        main.post { listeners.forEach { it.onReceiverChanged() } }
    }

    // ---- Lifecycle ------------------------------------------------------------------------------

    /** Call on the main thread. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (cast == null) {
            cast = CastPlayer(app, native::nativeSetPlaybackInfo) {
                casting = cast?.active == true
                if (casting) {
                    videoWidth = cast?.width ?: 0
                    videoHeight = cast?.height ?: 0
                }
                notifyChanged()
            }
            remote = RemoteControl(app, ::notifyChanged)
        }
        worker.execute {
            if (port > 0) return@execute
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            name = prefs.getString(KEY_NAME, null) ?: DEFAULT_NAME
            requirePin = prefs.getBoolean(KEY_REQUIRE_PIN, true)
            // The whole panel, including the area the system bars normally take.
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
            val width = maxOf(metrics.widthPixels, metrics.heightPixels)
            val height = minOf(metrics.widthPixels, metrics.heightPixels)
            screenSize = width
            port = native.nativeStart(
                name,
                hardwareAddress(app),
                File(app.filesDir, "pairing.pem").absolutePath,
                File(app.filesDir, REGISTER_FILE).absolutePath,
                Locale.getDefault().toLanguageTag(),
                width, height, MAX_FPS,
                requirePin,
                BuildConfig.DEBUG
            )
            if (port < 0) Log.w(TAG, "server did not start")
            notifyChanged()
        }
    }

    fun stop() {
        worker.execute {
            if (port > 0) native.nativeStop()
            port = -1
            connections = 0
            clearSession()
            notifyChanged()
        }
    }

    /** The network or a setting changed: the server reads both only when it starts. */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    fun rename(context: Context, newName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, newName).apply()
        restart(context)
    }

    fun setRequirePin(context: Context, required: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REQUIRE_PIN, required).apply()
        restart(context)
    }

    fun hasPairedPhones(context: Context) = File(context.filesDir, REGISTER_FILE).length() > 0

    /** Every phone has to enter a PIN again. */
    fun forgetPairedPhones(context: Context) {
        val app = context.applicationContext
        worker.execute { File(app.filesDir, REGISTER_FILE).delete() }
        restart(app)
    }

    /** Lends (or takes back, with null) the Surface pictures are drawn on. Main thread. */
    fun setSurface(target: Surface?) {
        surface = target
        video.setSurface(target)
        cast?.setSurface(target)
    }

    /** Re-evaluates [playing]; called once a second by the service while it runs. */
    fun refreshPlaying() {
        val last = audio.lastFrameAt
        val now = last != 0L && SystemClock.uptimeMillis() - last < PLAYING_WINDOW_MS
        if (now != playing) {
            playing = now
            notifyChanged()
        }
    }

    /**
     * AirPlay identifies a receiver by a MAC-style address. Android no longer exposes the real
     * one, so a random locally-administered address is made once and kept.
     */
    private fun hardwareAddress(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_HW_ADDRESS, null)?.let { saved ->
            if (saved.length == 12) return ByteArray(6) { saved.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val fresh = ByteArray(6).also { SecureRandom().nextBytes(it) }
        fresh[0] = ((fresh[0].toInt() and 0xfe) or 0x02).toByte()
        prefs.edit().putString(KEY_HW_ADDRESS, fresh.joinToString("") { "%02x".format(it) }).apply()
        return fresh
    }

    /** IPv4 addresses of every interface that is up, e.g. Wi-Fi and the unit's own hotspot. */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .sorted()
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun clearSession() {
        audio.stop()
        video.release()
        main.post {
            cast?.stop()
            remote?.clear()
            endSlideshow()
        }
        main.removeCallbacks(clearPin)
        pin = null
        mirroring = false
        playing = false
        clientName = null
        videoWidth = 0
        videoHeight = 0
        title = null
        artist = null
        album = null
        coverArt = null
        photo = null
        synchronized(keptPhotos) { keptPhotos.clear() }
    }

    // ---- Callbacks from the native threads ------------------------------------------------------

    override fun onConnections(count: Int) {
        connections = count
        if (count <= 0) clearSession()
        notifyChanged()
    }

    override fun onClient(name: String, model: String) {
        clientName = name.ifEmpty { model }.ifEmpty { null }
        main.removeCallbacks(clearPin)
        pin = null
        notifyChanged()
    }

    override fun onPin(pin: String) {
        this.pin = pin
        main.removeCallbacks(clearPin)
        main.postDelayed(clearPin, PIN_TIMEOUT_MS)
        notifyChanged()
    }

    override fun onMirror(running: Boolean) {
        if (running && casting) {
            // The cast player still owns the Surface; the decoder cannot start until it lets go.
            val released = CountDownLatch(1)
            main.post {
                cast?.stop()
                released.countDown()
            }
            released.await(HANDOVER_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        mirroring = running
        if (running) main.post { endSlideshow() }
        if (running) photo = null
        if (!running) video.release()
        notifyChanged()
    }

    override fun onVideo(data: ByteArray, length: Int, ptsNanos: Long) {
        video.feed(data, length, ptsNanos)
    }

    override fun onVideoReset(type: Int) {
        val endsMirror = type == NativeReceiver.RESET_TAKEN_OVER || type == NativeReceiver.RESET_MIRROR_ENDED ||
            type == NativeReceiver.RESET_MIRROR_TO_CAST
        val endsCast = type == NativeReceiver.RESET_TAKEN_OVER || type == NativeReceiver.RESET_CAST_ENDED ||
            type == NativeReceiver.RESET_CAST_FINISHED
        if (endsMirror) {
            video.release()
            mirroring = false
        }
        if (endsCast) main.post { cast?.stop() }
        notifyChanged()
    }

    override fun onAudioFormat(compressionType: Int) {
        audio.configure(compressionType)
    }

    override fun onAudio(data: ByteArray, length: Int, isPcm: Boolean, ptsNanos: Long) {
        audio.enqueue(data, length, isPcm, ptsNanos)
    }

    override fun onAudioFlush() {
        audio.flush()
    }

    override fun onVolume(decibels: Float) {
        audio.setVolume(decibels)
        // The same slider on the phone drives a cast's volume. -30 dB and below is silence.
        val gain = if (decibels <= -30f) 0f else 10f.pow(decibels.coerceAtMost(0f) / 20f)
        main.post { cast?.setVolume(gain) }
    }

    /** Track details arrive as a DMAP "mlit" list: 4-char tag, 4-byte big-endian length, value. */
    override fun onMetadata(dmap: ByteArray?) {
        if (dmap == null || dmap.size < 8) return
        var at = 8
        while (at + 8 <= dmap.size) {
            val tag = String(dmap, at, 4, Charsets.US_ASCII)
            val length = ((dmap[at + 4].toInt() and 0xff) shl 24) or ((dmap[at + 5].toInt() and 0xff) shl 16) or
                ((dmap[at + 6].toInt() and 0xff) shl 8) or (dmap[at + 7].toInt() and 0xff)
            at += 8
            if (length < 0 || at + length > dmap.size) break
            when (tag) {
                "minm" -> title = String(dmap, at, length, Charsets.UTF_8)
                "asar" -> artist = String(dmap, at, length, Charsets.UTF_8)
                "asal" -> album = String(dmap, at, length, Charsets.UTF_8)
            }
            at += length
        }
        notifyChanged()
    }

    override fun onCoverArt(image: ByteArray?) {
        coverArt = image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        notifyChanged()
    }

    override fun onRemoteControl(dacpId: String, activeRemote: String) {
        main.post { remote?.setTarget(dacpId, activeRemote) }
    }

    override fun onCastPlay(location: String, startSeconds: Float) {
        Log.i(TAG, "cast: $location from ${startSeconds}s")
        video.release()
        mirroring = false
        photo = null
        main.post {
            endSlideshow()
            cast?.play(location, startSeconds, surface)
        }
    }

    override fun onCastSeek(seconds: Float) {
        main.post { cast?.seek(seconds) }
    }

    override fun onCastRate(rate: Float) {
        main.post { cast?.setRate(rate) }
    }

    /** The phone's "stop": it ends a cast and also takes a picture off the screen. */
    override fun onCastStop() {
        main.post {
            cast?.setRate(0f)
            endSlideshow()
        }
        if (photo != null) {
            photo = null
            notifyChanged()
        }
    }

    override fun onPhoto(assetKey: String?, action: String?, jpeg: ByteArray?): Boolean {
        val data = when (action) {
            "cacheOnly" -> {
                if (assetKey != null && jpeg != null) synchronized(keptPhotos) { keptPhotos[assetKey] = jpeg }
                return true
            }
            "displayCached" -> synchronized(keptPhotos) { keptPhotos[assetKey] } ?: return false
            else -> jpeg ?: return true
        }
        val decoded = decodePhoto(data) ?: return true
        photo = decoded
        notifyChanged()
        return true
    }

    override fun onSlideshow(playing: Boolean, slideSeconds: Int) {
        main.post {
            endSlideshow()
            if (playing) {
                slideshow = true
                slideMillis = slideSeconds.coerceIn(1, 60) * 1000L
                requestSlide(1)
            }
        }
    }

    override fun onSlidePhoto(jpeg: ByteArray?) {
        val picture = jpeg?.let(::decodePhoto)
        main.post {
            if (!slideshow) return@post
            if (picture == null) {
                // Past the last picture: go round again, unless there are none at all.
                if (slideRequested > 1) requestSlide(1)
                return@post
            }
            if (slideShown) {
                nextSlide = picture
            } else {
                slideShown = true
                photo = picture
                notifyChanged()
                requestSlide(slideRequested + 1)
                main.postDelayed(turnSlide, slideMillis)
            }
        }
    }

    private fun requestSlide(number: Int) {
        slideRequested = number
        worker.execute { native.nativeRequestSlide(number) }
    }

    private fun endSlideshow() {
        if (!slideshow) return
        slideshow = false
        slideShown = false
        nextSlide = null
        main.removeCallbacks(turnSlide)
        photo = null
        notifyChanged()
    }

    /** Decodes no larger than the screen needs: camera pictures are many times its size. */
    private fun decodePhoto(jpeg: ByteArray): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the rotation recorded by the camera.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= screenSize) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= screenSize) sample *= 2
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not decode the photo", e)
        null
    }
}
