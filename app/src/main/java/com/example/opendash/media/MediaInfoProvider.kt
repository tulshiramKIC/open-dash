package com.example.opendash.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaInfoProvider(private val context: Context) {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, OpenDashNotificationListener::class.java)
    private var controller: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = bind(null)
    }

    private val sessionsCallback = MediaSessionManager.OnActiveSessionsChangedListener(::bind)

    fun start() {
        currentInstance = this
        if (!isAccessGranted(context)) {
            DebugLog.i(TAG) { "Notification access unavailable; media forwarding is disabled" }
            return
        }
        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsCallback, listenerComponent)
            bind(sessionManager?.getActiveSessions(listenerComponent))
        }.onFailure { DebugLog.w(TAG) { "Media session binding failed: ${it.javaClass.simpleName}" } }
    }

    fun stop() {
        if (currentInstance == this) {
            currentInstance = null
        }
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsCallback) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        _nowPlaying.value = null
    }

    fun skipNext(): Boolean = runCatching {
        controller?.transportControls?.skipToNext()
        controller != null
    }.getOrDefault(false)

    fun skipPrevious(): Boolean = runCatching {
        controller?.transportControls?.skipToPrevious()
        controller != null
    }.getOrDefault(false)

    fun play(): Boolean = runCatching {
        controller?.transportControls?.play()
        controller != null
    }.getOrDefault(false)

    fun pause(): Boolean = runCatching {
        controller?.transportControls?.pause()
        controller != null
    }.getOrDefault(false)

    /** Toggle play/pause off the current playback state; falls back to play() if unknown. */
    fun playPause(): Boolean = runCatching {
        val c = controller ?: return false
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
        true
    }.getOrDefault(false)

    /** Media-stream volume steps, shown by the system volume UI. */
    fun volumeUp() = adjustVolume(android.media.AudioManager.ADJUST_RAISE)
    fun volumeDown() = adjustVolume(android.media.AudioManager.ADJUST_LOWER)

    private fun adjustVolume(direction: Int): Boolean = runCatching {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        am.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            direction,
            android.media.AudioManager.FLAG_SHOW_UI,
        )
        true
    }.getOrDefault(false)

    private fun bind(sessions: List<MediaController>?) {
        // Skip our own session — DashKeepAliveService publishes one purely to get
        // lock-screen controls. Binding to it would show OpenDash as the "now playing"
        // track and make the intercom's auto-pause tell itself to pause.
        val next = sessions?.firstOrNull { it.packageName != context.packageName }
        if (next?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = next
        controller?.registerCallback(controllerCallback)
        publish()
    }

    private fun publish() {
        val metadata = controller?.metadata ?: run {
            _nowPlaying.value = null
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (title.isBlank() && artist.isBlank()) {
            _nowPlaying.value = null
            return
        }
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val isPlaying = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        _nowPlaying.value = NowPlaying(title, album, artist, art, isPlaying)
    }

    companion object {
        private const val TAG = "MediaInfoProvider"

        @Volatile var currentInstance: MediaInfoProvider? = null
            private set

        fun isAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return enabled.split(':').any { it.contains(context.packageName) }
        }

        fun accessSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
