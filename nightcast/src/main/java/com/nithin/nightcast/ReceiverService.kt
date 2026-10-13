package com.nithin.nightcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Keeps the receiver running and discoverable while the app is not on screen, and does the
 * things only a long-lived component can: hold the Wi-Fi locks, follow network changes, take
 * audio focus, and publish the playing track as a media session.
 */
class ReceiverService : Service(), Receiver.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var audioManager: AudioManager
    private lateinit var session: MediaSession

    private var knownAddresses = emptyList<String>()
    private var restartPending = false
    private var ticks = 0
    private var focusHeld = false
    private var wasShowing = false
    private var wasPlaying = false
    private var shownPin: String? = null
    private var notificationText = ""

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(::onFocusChange, handler)
            .build()
    }

    private val ticker = object : Runnable {
        override fun run() {
            Receiver.refreshPlaying()
            if (++ticks % ADDRESS_CHECK_TICKS == 0) checkNetwork()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
        notificationText = statusText()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Without the multicast lock Android's Wi-Fi driver filters out the mDNS queries
        // iPhones send to find AirPlay receivers.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("nightcast-mdns").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nightcast-stream").apply { acquire() }

        audioManager = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "Nightcast")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = Receiver.remote?.playPause() ?: Unit
            override fun onPause() = Receiver.remote?.playPause() ?: Unit
            override fun onSkipToNext() = Receiver.remote?.next() ?: Unit
            override fun onSkipToPrevious() = Receiver.remote?.previous() ?: Unit
        })

        knownAddresses = Receiver.localAddresses()
        Receiver.addListener(this)
        Receiver.start(this)
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        Receiver.removeListener(this)
        Receiver.stop()
        abandonFocus()
        session.release()
        if (wifiLock.isHeld) wifiLock.release()
        if (multicastLock.isHeld) multicastLock.release()
        super.onDestroy()
    }

    /**
     * The mDNS responder announces on the interface it found at start-up, so the server is
     * restarted when the set of addresses changes: Wi-Fi joining late after boot, or the unit's
     * hotspot being switched on. Waits for a quiet moment rather than cutting off a stream.
     */
    private fun checkNetwork() {
        val now = Receiver.localAddresses()
        if (now != knownAddresses) {
            knownAddresses = now
            restartPending = true
        }
        if (Receiver.port < 0 && now.isNotEmpty()) restartPending = true
        if (restartPending && Receiver.connections == 0) {
            restartPending = false
            Log.i(TAG, "network is now $now, restarting receiver")
            Receiver.restart(this)
        }
    }

    override fun onReceiverChanged() {
        val showing = Receiver.mirroring || Receiver.casting || Receiver.photo != null
        val playing = Receiver.playing
        val pin = Receiver.pin

        // A picture arriving, or a PIN the driver has to read, needs the screen.
        if ((showing && !wasShowing) || (pin != null && pin != shownPin)) {
            if (!MirrorActivity.visible) openMirror(pin)
        }
        if ((!showing && pin == null) || MirrorActivity.visible) dismissAlert()
        if (playing && !wasPlaying) requestFocus()
        if (Receiver.connections == 0) abandonFocus()
        wasShowing = showing
        wasPlaying = playing
        shownPin = pin

        updateSession()
        val text = statusText()
        if (text != notificationText) {
            notificationText = text
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * Brings the screen up by itself. Android lets a background app do that only once it holds
     * "display over other apps". Without it the next best thing is a heads-up notification with
     * a full-screen intent: one tap, or no tap at all if the display was idle.
     */
    private fun openMirror(pin: String?) {
        val intent = Intent(this, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not open the mirror screen", e)
            }
        }
        val open = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (pin != null) getString(R.string.alert_pin, pin) else getString(R.string.alert_open)
        val alert = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(statusText())
            .setContentText(text)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, alert)
    }

    private fun dismissAlert() {
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    // ---- Audio focus ----------------------------------------------------------------------------

    private fun requestFocus() {
        Receiver.audio.setMuted(false)
        if (focusHeld) return
        focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> Receiver.audio.setMuted(false)
            // A phone call or a prompt: the stream cannot be paused from here, so go silent.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Receiver.audio.setMuted(true)
            AudioManager.AUDIOFOCUS_LOSS -> {
                Receiver.audio.setMuted(true)
                focusHeld = false
            }
        }
    }

    // ---- Media session and notification ---------------------------------------------------------

    /** Publishes the track so launchers and steering-wheel displays can show what is playing. */
    private fun updateSession() {
        val title = Receiver.title
        val streamingMusic = Receiver.connections > 0 && !Receiver.mirroring && !Receiver.casting && title != null
        if (!streamingMusic) {
            if (session.isActive) session.isActive = false
            return
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, Receiver.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, Receiver.album)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, Receiver.coverArt)
                .build()
        )
        val state = if (Receiver.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        // Transport buttons only work once the phone's remote-control service has been found.
        val actions = if (Receiver.remoteAvailable) {
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        } else {
            0L
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (Receiver.playing) 1f else 0f)
                .build()
        )
        if (!session.isActive) session.isActive = true
    }

    private fun statusText(): String {
        val client = Receiver.clientName ?: getString(R.string.unknown_device)
        return when {
            Receiver.pin != null -> getString(R.string.status_pairing)
            Receiver.mirroring -> getString(R.string.status_mirroring, client)
            Receiver.casting -> getString(R.string.status_casting, client)
            Receiver.photo != null -> getString(R.string.status_photo, client)
            Receiver.connections > 0 -> getString(R.string.status_playing, client)
            Receiver.port > 0 -> getString(R.string.status_ready, Receiver.name)
            else -> getString(R.string.status_offline)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MirrorActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NightcastService"
        private const val CHANNEL = "receiver"
        private const val ALERT_CHANNEL = "alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val TICK_MS = 1000L
        private const val ADDRESS_CHECK_TICKS = 3

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }
    }
}
