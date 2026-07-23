package com.example.opendash.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Crash detection + SOS. Runs only while a ride context is active (dash streaming,
 * trail recording, intercom/group ride, or in-app navigation) AND the Settings toggle
 * is on — never in the background of normal phone use.
 *
 * Detection is a two-stage state machine tuned against false positives:
 *   1. IMPACT — acceleration magnitude spike > [IMPACT_G] g (a pothole hits 3–4 g for
 *      a few ms; a crash sustains higher).
 *   2. STILLNESS — within [STILL_WINDOW_MS] after the spike, the phone stays nearly
 *      motionless (low accel variance) — a rider who hit a pothole keeps moving, a
 *      crashed one doesn't.
 * Both stages pass → [Alert.Counting]: loud alarm + full-screen countdown for
 * [COUNTDOWN_S] seconds. Rider taps "I'm OK" → cancelled. No response → one SMS with
 * a maps link to the emergency contact (SMS works where mobile data doesn't).
 */
object CrashDetector {
    private const val TAG = "CrashDetector"

    // ── Tuning ──
    private const val IMPACT_G = 6.0f            // sustained-spike threshold, in g
    private const val STILL_WINDOW_MS = 8_000L   // observe this long after the spike
    private const val STILL_START_MS = 2_000L    // ignore the first moments (tumbling)
    private const val STILL_VARIANCE = 1.2f      // m/s² stddev considered "not moving"
    private const val COUNTDOWN_S = 30

    sealed class Alert {
        data object None : Alert()
        /** Impact confirmed; SOS fires at [deadlineMs] unless cancelled. */
        data class Counting(val deadlineMs: Long) : Alert()
        data class Sent(val contact: String) : Alert()
        data class Failed(val reason: String) : Alert()
        /** A ridemate's OpenDash fired SOS on the group-ride channel. */
        data class PeerSos(val name: String, val lat: Double, val lng: Double) : Alert() {
            val hasLocation: Boolean get() = lat != 0.0 || lng != 0.0
        }
    }

    private val _alert = MutableStateFlow<Alert>(Alert.None)
    val alert = _alert.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var running = false
    private var watchJob: Job? = null
    private var countdownJob: Job? = null
    private var alarmJob: Job? = null

    // Recent accel magnitudes for the stillness variance check.
    private val window = ArrayDeque<Float>()
    @Volatile private var impactAtMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
            val now = System.currentTimeMillis()

            // Keep detecting while a *peer's* SOS is on screen — two riders can go down
            // in the same incident, and our own countdown then takes over the display.
            if (_alert.value !is Alert.None && _alert.value !is Alert.PeerSos) return

            if (impactAtMs == 0L) {
                if (g > IMPACT_G) {
                    impactAtMs = now
                    synchronized(window) { window.clear() }
                    DebugLog.i(TAG) { "impact spike ${"%.1f".format(g)}g — watching for stillness" }
                    startStillnessWatch()
                }
            } else if (now - impactAtMs > STILL_START_MS) {
                synchronized(window) {
                    window.addLast(g * 9.81f)
                    if (window.size > 120) window.removeFirst()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Ride contexts (dash stream / trail / intercom-ride / in-app nav) hold the detector
    // armed while at least one is live; the Settings toggle gates actual arming.
    private var contexts = 0

    @Synchronized
    fun enterRideContext(context: Context) {
        contexts++
        start(context)
    }

    @Synchronized
    fun exitRideContext() {
        contexts = (contexts - 1).coerceAtLeast(0)
        if (contexts == 0) stop()
    }

    /** Toggle flipped on mid-ride: arm now if a ride context is already live. */
    fun startIfRideActive(context: Context) {
        if (contexts > 0) start(context)
    }

    private fun start(context: Context) {
        appContext = context.applicationContext
        if (running || !NavSettings.crashSosEnabled.value) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = sm
        // GAME rate (~50 Hz): fast enough to catch a real impact, cheap enough for a ride.
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        running = true
        DebugLog.i(TAG) { "armed" }
    }

    /** Disarm the sensor (contexts stay counted; re-arms if the toggle returns). */
    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(listener)
        sensorManager = null
        running = false
        impactAtMs = 0L
        watchJob?.cancel(); watchJob = null
        if (_alert.value is Alert.None) stopAlarm()
        DebugLog.i(TAG) { "disarmed" }
    }

    private fun startStillnessWatch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            delay(STILL_WINDOW_MS)
            val samples = synchronized(window) { window.toFloatArray() }
            impactAtMs = 0L
            if (samples.size < 20) return@launch
            val mean = samples.average().toFloat()
            var variance = 0f
            samples.forEach { variance += (it - mean) * (it - mean) }
            val stddev = sqrt(variance / samples.size)
            DebugLog.i(TAG) { "post-impact stddev=${"%.2f".format(stddev)} m/s² (${samples.size} samples)" }
            if (stddev < STILL_VARIANCE) beginCountdown()
        }
    }

    private fun beginCountdown() {
        val deadline = System.currentTimeMillis() + COUNTDOWN_S * 1000L
        _alert.value = Alert.Counting(deadline)
        startAlarm()
        postFullScreenAlert()
        countdownJob?.cancel()
        countdownJob = scope.launch {
            delay(COUNTDOWN_S * 1000L)
            if (_alert.value is Alert.Counting) sendSos()
        }
    }

    /** Rider tapped "I'm OK" — stand down completely. */
    fun cancel() {
        countdownJob?.cancel(); countdownJob = null
        stopAlarm()
        dismissAlertNotification()
        _alert.value = Alert.None
        impactAtMs = 0L
    }

    /** Reset after Sent/Failed/PeerSos was acknowledged in the UI. */
    fun acknowledge() {
        if (_alert.value is Alert.Counting) return
        stopAlarm()
        dismissAlertNotification()
        _alert.value = Alert.None
    }

    /**
     * A ride peer's SOS arrived on the group-ride channel. Alarm + full-screen alert on
     * this phone regardless of our own Crash-SOS toggle — that setting arms *our*
     * detector; being told a ridemate went down is part of being in the ride.
     */
    fun onPeerSos(context: Context, name: String, lat: Double, lng: Double) {
        appContext = appContext ?: context.applicationContext
        // Our own live countdown/SOS outranks a peer's alert on this screen.
        if (_alert.value is Alert.Counting) return
        _alert.value = Alert.PeerSos(name, lat, lng)
        startAlarm()
        postFullScreenAlert(
            title = "$name may have crashed",
            text = "SOS from their OpenDash — tap to see their last position",
        )
    }

    private fun sendSos() {
        stopAlarm()
        val ctx = appContext ?: run { _alert.value = Alert.Failed("no context"); return }
        val loc: Location? = com.example.opendash.dash.map.LocationTracker(ctx).let { t ->
            // One-shot last-known read; the tracker isn't started for this.
            runCatching { t.lastKnown() }.getOrNull()
        }
        // Ride mesh first, unconditionally: ridemates are the closest possible help, and
        // this path needs no emergency contact, no SMS permission — just the ride channel
        // that's already up. SMS below can then still fail without silencing the mesh.
        val meshInformed = GroupRide.broadcastSos(loc?.latitude, loc?.longitude)
        val contact = NavSettings.sosContact.value
        if (contact.isBlank()) {
            _alert.value = if (meshInformed) Alert.Sent("your ride group (no SMS contact set)")
            else Alert.Failed("No emergency contact set")
            return
        }
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            _alert.value = if (meshInformed) Alert.Sent("your ride group (SMS not permitted)")
            else Alert.Failed("SMS permission not granted")
            return
        }
        val name = GroupRide.state.value.riderName.ifBlank { "an OpenDash rider" }
        val where = if (loc != null) {
            "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        } else "location unavailable"
        val msg = "SOS from $name: possible motorcycle crash detected. " +
            "No response for ${COUNTDOWN_S}s. Last position: $where"
        runCatching {
            val sms = ctx.getSystemService(android.telephony.SmsManager::class.java)
            sms.sendMultipartTextMessage(contact, null, sms.divideMessage(msg), null, null)
        }.onSuccess {
            DebugLog.i(TAG) { "SOS sent to $contact" }
            _alert.value = Alert.Sent(contact)
        }.onFailure {
            DebugLog.e(TAG, { "SOS send failed: ${it.message}" }, it)
            _alert.value = Alert.Failed(it.message ?: "SMS send failed")
        }
    }

    // ── Alarm + full-screen alert plumbing ──
    private var tone: ToneGenerator? = null

    private fun startAlarm() {
        alarmJob?.cancel()
        alarmJob = scope.launch {
            runCatching { tone = ToneGenerator(AudioManager.STREAM_ALARM, 100) }
            while (isActive && (_alert.value is Alert.Counting || _alert.value is Alert.PeerSos)) {
                runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 800) }
                delay(1_000)
            }
        }
    }

    private fun stopAlarm() {
        alarmJob?.cancel(); alarmJob = null
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
    }

    private const val CHANNEL_ID = "opendash_crash"
    private const val NOTIF_ID = 4711

    /** Wakes the screen and opens the app on an alert, even from pocket. */
    private fun postFullScreenAlert(
        title: String = "Possible crash detected",
        text: String = "SOS SMS in ${COUNTDOWN_S}s — open to cancel",
    ) {
        val ctx = appContext ?: return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Crash alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 11,
                Intent(ctx, com.example.opendash.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = android.app.Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(com.example.opendash.R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .setFullScreenIntent(open, true)
                .setOngoing(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun dismissAlertNotification() {
        runCatching {
            appContext?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }
}
