package com.example.opendash.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import com.example.opendash.MainActivity
import com.example.opendash.R
import com.example.opendash.dash.nav.VoiceManager
import com.example.opendash.dash.nav.VoiceMode
import com.example.opendash.data.IntercomEngine
import com.example.opendash.data.NavSettings
import com.example.opendash.util.DebugLog
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.DashUiState
import com.example.opendash.viewmodel.DashViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps OpenDash streaming to the dash while the
 * phone screen is OFF — the whole reason this app exists.
 *
 * It does NOT own the streaming pipeline (that stays in DashViewModel, which
 * survives Activity onStop). Its job is purely to stop Android from freezing
 * the process:
 *   • PARTIAL_WAKE_LOCK  — CPU keeps running the 4 Hz encoder/RTP loop with
 *     the screen off (otherwise Doze suspends the coroutine → dash times out).
 *   • WifiLock (LOW_LATENCY / HIGH_PERF) — stops WiFi power-save from tearing
 *     down the WifiNetworkSpecifier link to the Tripper hotspot.
 *   • Ongoing notification — required for a foreground service; also lets the
 *     rider re-open the app.
 */
class DashKeepAliveService : Service() {
    companion object {
        private const val TAG          = "DashKeepAlive"
        // v4: bumped from IMPORTANCE_LOW, which OEM lock screens (ColorOS especially)
        // render as a minimised row that won't expand or draw a custom view. Channels are
        // immutable once created, so raising importance needs a new id.
        private const val CHANNEL_ID   = "opendash_dash_v4"
        private const val CHANNEL_ID_OLD = "opendash_dash_v3"
        private const val NOTIF_ID     = 4701
        const val ACTION_START = "com.example.opendash.DASH_START"
        const val ACTION_STOP  = "com.example.opendash.DASH_STOP"

        // The service is shared by every feature that must survive screen-off. Each
        // holds it with its own reason; it stops only when the last reason is gone
        // (previously trail-recording's stop() would kill the dash stream's locks).
        const val REASON_DASH  = "dash"
        const val REASON_TRAIL = "trail"
        const val REASON_RIDE  = "ride"
        private val reasons = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun start(context: Context, reason: String = REASON_DASH) {
            reasons.add(reason)
            val i = Intent(context, DashKeepAliveService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context, reason: String = REASON_DASH) {
            val removed = reasons.remove(reason)
            if (!removed) return
            if (reasons.isEmpty()) {
                context.startService(
                    Intent(context, DashKeepAliveService::class.java).setAction(ACTION_STOP)
                )
            } else {
                // Still held by another feature — just refresh the notification text.
                context.startService(
                    Intent(context, DashKeepAliveService::class.java).setAction(ACTION_START)
                )
            }
        }

        internal fun notificationContent(): Pair<String, String> = when {
            REASON_DASH in reasons ->
                "OpenDash — streaming to dash" to "Map is live on the Tripper. Screen can stay off."
            REASON_TRAIL in reasons ->
                "OpenDash — recording trail" to "GPS trail recording is running."
            else ->
                "OpenDash — group ride" to "Sharing your live location with the ride."
        }

        const val ACTION_TOGGLE_MIC   = "com.example.opendash.ACTION_TOGGLE_MIC"
        const val ACTION_TOGGLE_VOICE = "com.example.opendash.ACTION_TOGGLE_VOICE"
        const val ACTION_TOGGLE_THEME = "com.example.opendash.ACTION_TOGGLE_THEME"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * MediaStyle only gets the lock-screen treatment — controls visible without unlocking
     * or expanding — when backed by a real session token. It brings a seek bar and an
     * artwork slot that can't be turned off; that's an accepted trade, because a custom
     * notification view isn't rendered on this phone's lock screen at all, and Quick
     * Settings tiles are no use to riders who disable the shade on the lock screen.
     */
    private var mediaSession: android.media.session.MediaSession? = null

    /**
     * The media card always draws an artwork slot; with no album art it falls back to a
     * generic music note. Our launcher icon is the honest thing to put there.
     */
    private val notificationArt: android.graphics.Bitmap? by lazy {
        runCatching {
            val drawable = androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
                ?: return@runCatching null
            val size = 256
            val bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888
            )
            drawable.setBounds(0, 0, size, size)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap
        }.getOrNull()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastUiState: DashUiState? = null

    /** Shared by the notification actions and the lock-screen media custom actions. */
    private fun dispatchToggle(action: String?) {
        when (action) {
            ACTION_TOGGLE_MIC -> IntercomEngine.toggleMute()
            ACTION_TOGGLE_VOICE -> {
                val vm = VoiceManager.get(this)
                vm.setMode(
                    when (vm.mode.value) {
                        VoiceMode.OFF -> VoiceMode.CHIME
                        VoiceMode.CHIME -> VoiceMode.FULL
                        VoiceMode.FULL -> VoiceMode.OFF
                    }
                )
            }
            ACTION_TOGGLE_THEME -> NavSettings.setMapTheme(
                this,
                when (NavSettings.mapTheme.value) {
                    NavSettings.MapTheme.DAY -> NavSettings.MapTheme.NIGHT
                    NavSettings.MapTheme.NIGHT -> NavSettings.MapTheme.AUTO
                    NavSettings.MapTheme.AUTO -> NavSettings.MapTheme.DAY
                }
            )
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            dispatchToggle(intent?.action)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is the OS re-delivering START_STICKY after killing us. If nothing
        // still holds the service, don't resurrect the notification.
        if (intent == null && reasons.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else        -> startForegroundLocks()
        }
        // START_STICKY: if the OS kills us under memory pressure, restart so the
        // ride keeps streaming.
        return START_STICKY
    }

    private fun startForegroundLocks() {
        createChannel()
        // The LOCATION type is what lets GPS keep updating with the screen off —
        // without it Android 14+ freezes location for backgrounded apps and the
        // rider marker sticks at its first fix. But declaring it WITHOUT the runtime
        // location grant is a fatal SecurityException on 14+, so only include it when
        // the permission is actually held (LocationTracker no-ops without it anyway).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocationPermission()) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            // Without the MICROPHONE type, Android 11+ background-restricts the mic the moment
            // OpenDash leaves the foreground (another app opens, screen locks) — capture is
            // muted and WebRTC's shared audio unit tears down playout with it, killing the
            // intercom both ways. Only add it during a ride and with RECORD_AUDIO actually
            // granted; declaring it without the runtime grant is a fatal SecurityException.
            if (hasMicPermission() && REASON_RIDE in reasons) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, buildNotification(), fgsType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        acquireLocks()
        DebugLog.i(TAG) { "Foreground service up — wake+wifi locks held" }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun acquireLocks() {
        if (wakeLock != null) return   // re-entrant ACTION_START (notification refresh)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opendash:dash").apply {
            setReferenceCounted(false)
            acquire()
        }

        // HIGH_PERF keeps the link awake (prevents WiFi power-save from dropping the
        // WifiNetworkSpecifier connection) at lower power than LOW_LATENCY. 4 fps /
        // ~200 kbps doesn't need low-latency mode's extra power draw.
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "opendash:dash").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        mediaSession = android.media.session.MediaSession(this, "OpenDash").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    dispatchToggle(action)
                }
                // A rider's headset/handlebar buttons should still drive their music, not
                // get swallowed by our session just because it's the most recent one.
                override fun onPlay() { com.example.opendash.media.MediaInfoProvider.currentInstance?.play() }
                override fun onPause() { com.example.opendash.media.MediaInfoProvider.currentInstance?.pause() }
                override fun onSkipToNext() { com.example.opendash.media.MediaInfoProvider.currentInstance?.skipNext() }
                override fun onSkipToPrevious() { com.example.opendash.media.MediaInfoProvider.currentInstance?.skipPrevious() }
            })
            // Default session audio attributes are USAGE_MEDIA, which marks us as a music
            // player — that's what feeds OEM "now playing" indicators (ColorOS Fluid
            // Cloud). We're navigation/intercom, so say so.
            setPlaybackToLocal(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            isActive = true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_MIC)
            addAction(ACTION_TOGGLE_VOICE)
            addAction(ACTION_TOGGLE_THEME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        serviceScope.launch {
            combine(
                DashViewModel.activeViewModel.flatMapLatest { vm ->
                    vm?.ui ?: flowOf(null)
                },
                IntercomEngine.state,
                VoiceManager.get(this@DashKeepAliveService).mode,
                NavSettings.mapTheme
            ) { uiState, intercom, voice, theme ->
                lastUiState = uiState
                buildNotification(uiState, intercom, voice, theme)
            }.collect { notification ->
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, notification)
            }
        }
    }

    /**
     * Swiping the app out of recents is an explicit "I'm done" — tear everything down
     * rather than leaving an ongoing notification (and wake+wifi locks) behind. Without
     * this the service outlives the task and the notification is unkillable short of
     * force-stopping the app.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLog.i(TAG) { "Task removed — shutting down keep-alive" }
        reasons.clear()
        runCatching { com.example.opendash.data.GroupRide.leaveRide() }
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        serviceScope.cancel()
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
        releaseLocks()
        DebugLog.i(TAG) { "Foreground service stopped — locks released" }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel(CHANNEL_ID_OLD) }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Dash streaming", NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Keeps the map streaming to the Tripper Dash"
                    // Default importance is what gets the notification rendered in full,
                    // but a ride notification must never make noise.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun buildNotification(
        uiState: DashUiState? = lastUiState,
        intercom: IntercomEngine.State = IntercomEngine.state.value,
        voice: VoiceMode = VoiceManager.get(this).mode.value,
        theme: NavSettings.MapTheme = NavSettings.mapTheme.value
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        // Only surface the dash connection stage when the dash is actually what's holding
        // the service — an intercom-only session showing "Offline" reads as a fault.
        val title = when {
            REASON_DASH in reasons -> when (uiState?.stage) {
                ConnStage.STREAMING -> "🏍 OpenDash — Connected"
                ConnStage.OFFLINE -> "🏍 OpenDash — Offline"
                ConnStage.WIFI -> "🏍 OpenDash — Connecting WiFi..."
                ConnStage.AUTH -> "🏍 OpenDash — Authenticating..."
                else -> "🏍 OpenDash"
            }
            REASON_TRAIL in reasons -> "OpenDash — recording trail"
            else -> "OpenDash Intercom"
        }

        // Only say something when there's something to say. The old blurb ("Sharing your
        // live location with the ride") was noise on every intercom session.
        val text = StringBuilder()
        if (uiState != null && uiState.hasRoute && uiState.etaMinutes != null) {
            text.append("ETA: ${uiState.etaMinutes}m")
            if (uiState.destinationName != null) {
                text.append(" to ${uiState.destinationName}")
            }
        } else if (REASON_DASH in reasons || REASON_TRAIL in reasons) {
            text.append(notificationContent().second)
        }

        val micLabel = if (!intercom.isIntercomActive) {
            "Mic (Off)"
        } else if (intercom.isMuted) {
            "Unmute Mic"
        } else {
            "Mute Mic"
        }
        val voiceLabel = when (voice) {
            VoiceMode.OFF -> "Chime (Off)"
            VoiceMode.CHIME -> "Chime (On)"
            VoiceMode.FULL -> "TTS (Full)"
        }
        val themeLabel = when (theme) {
            NavSettings.MapTheme.DAY -> "Day Map"
            NavSettings.MapTheme.NIGHT -> "Night Map"
            NavSettings.MapTheme.AUTO -> "Auto Map"
        }

        val micIcon = if (intercom.isMuted) {
            R.drawable.ic_mic_off
        } else {
            R.drawable.ic_mic
        }
        val micAction = Notification.Action.Builder(
            micIcon,
            micLabel,
            PendingIntent.getBroadcast(this, 1, Intent(ACTION_TOGGLE_MIC).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ).build()

        val voiceIcon = when (voice) {
            VoiceMode.OFF -> R.drawable.ic_volume_off
            VoiceMode.CHIME -> R.drawable.ic_volume_chime
            VoiceMode.FULL -> R.drawable.ic_volume_up
        }
        val voiceAction = Notification.Action.Builder(
            voiceIcon,
            voiceLabel,
            PendingIntent.getBroadcast(this, 2, Intent(ACTION_TOGGLE_VOICE).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ).build()

        val themeIcon = when (theme) {
            NavSettings.MapTheme.DAY -> R.drawable.ic_sun
            NavSettings.MapTheme.NIGHT -> R.drawable.ic_moon
            NavSettings.MapTheme.AUTO -> R.drawable.ic_map_theme
        }
        val themeAction = Notification.Action.Builder(
            themeIcon,
            themeLabel,
            PendingIntent.getBroadcast(this, 3, Intent(ACTION_TOGGLE_THEME).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ).build()

        // The lock-screen media UI takes its labels from the session metadata, not the
        // notification's title/text, and builds its buttons from the PlaybackState —
        // notification actions are ignored there. Declaring only custom actions (no
        // PLAY_PAUSE/SKIP) is what puts our controls in those slots instead of generic
        // music transport buttons.
        mediaSession?.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, text.toString())
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, notificationArt)
                .build()
        )
        mediaSession?.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setState(
                    android.media.session.PlaybackState.STATE_PLAYING,
                    android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .addCustomAction(
                    android.media.session.PlaybackState.CustomAction
                        .Builder(ACTION_TOGGLE_MIC, micLabel, micIcon).build()
                )
                .addCustomAction(
                    android.media.session.PlaybackState.CustomAction
                        .Builder(ACTION_TOGGLE_VOICE, voiceLabel, voiceIcon).build()
                )
                .addCustomAction(
                    android.media.session.PlaybackState.CustomAction
                        .Builder(ACTION_TOGGLE_THEME, themeLabel, themeIcon).build()
                )
                .build()
        )

        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .also { s -> mediaSession?.sessionToken?.let { s.setMediaSession(it) } }
        builder.setStyle(style)
        builder.setContentTitle(title)
        if (text.isNotBlank()) builder.setContentText(text)
        notificationArt?.let { builder.setLargeIcon(it) }

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(micAction)
            .addAction(voiceAction)
            .addAction(themeAction)
            .build()
    }
}
