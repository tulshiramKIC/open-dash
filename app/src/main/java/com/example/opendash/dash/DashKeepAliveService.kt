package com.example.opendash.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.opendash.MainActivity
import com.example.opendash.R
import com.example.opendash.util.DebugLog

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
        private const val CHANNEL_ID   = "opendash_dash"
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
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    override fun onDestroy() {
        releaseLocks()
        DebugLog.i(TAG) { "Foreground service stopped — locks released" }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Dash streaming", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Keeps the map streaming to the Tripper Dash" }
            )
        }
    }

    private fun buildNotification(): Notification {
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

        val (title, text) = notificationContent()
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
