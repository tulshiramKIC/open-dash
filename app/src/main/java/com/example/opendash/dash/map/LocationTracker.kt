package com.example.opendash.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** GPS position via LocationManager (no Play Services dependency). */
class LocationTracker(context: Context) {
    companion object {
        private const val TAG = "LocationTracker"
        // While a GPS fix is younger than this, ignore coarse NETWORK fixes entirely.
        private const val GPS_STALE_MS = 10_000L
    }

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    private val listener = LocationListener { loc ->
        val cur = _location.value
        if (acceptFix(cur, loc)) {
            _location.value = loc
            DebugLog.d(TAG) { "fix ${loc.provider} acc=${loc.accuracy} (${loc.latitude},${loc.longitude})" }
        } else {
            DebugLog.d(TAG) { "REJECT ${loc.provider} acc=${loc.accuracy} dt=${loc.time - (cur?.time ?: 0)}ms" }
        }
    }

    private var rejectStreak = 0

    private fun acceptFix(cur: Location?, loc: Location): Boolean {
        if (cur == null) {
            rejectStreak = 0
            return true
        }
        // If the new fix is significantly more accurate than the current one, always accept it
        if (loc.hasAccuracy() && cur.hasAccuracy() && loc.accuracy < cur.accuracy - 10f) {
            rejectStreak = 0
            return true
        }
        val isGps = loc.provider == LocationManager.GPS_PROVIDER || loc.provider == "fused"
        if (!isGps && (cur.provider == LocationManager.GPS_PROVIDER || cur.provider == "fused") &&
            loc.time - cur.time < GPS_STALE_MS
        ) return false
        if (loc.time < cur.time) return false
        val dt = (loc.time - cur.time) / 1000.0
        val jump = cur.distanceTo(loc)
        if (dt > 0 && jump > 200f && jump / dt > 85.0) return reject()
        if (dt in 0.0..6.0 && rejectStreak < 3) {
            val plausibleSpeed = maxOf(cur.speed, loc.speed).coerceAtLeast(1f)
            val expected = plausibleSpeed * dt
            val noise = loc.accuracy + cur.accuracy
            val gate = expected + noise * 1.5 + 12f
            if (jump > gate && jump > 25f) return reject()
        }
        rejectStreak = 0
        return true
    }

    private fun reject(): Boolean {
        rejectStreak++
        return false
    }

    private var running = false

    private fun hasFineLocationPermission(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() ||
            appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnownFrom(provider: String): Location? {
        if (!hasLocationPermission() || provider == LocationManager.GPS_PROVIDER && !hasFineLocationPermission()) {
            return null
        }
        return runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    /** Starts every permitted provider independently so one unavailable provider cannot stop the others. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!hasLocationPermission()) {
            DebugLog.w(TAG) { "Location permission missing — GPS disabled" }
            return
        }

        val providers = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            providers.add("fused")
        }
        providers.add(LocationManager.GPS_PROVIDER)
        providers.add(LocationManager.NETWORK_PROVIDER)

        // Try to initialize with last known location from best available provider
        var lastKnownLoc: Location? = null
        for (provider in providers) {
            lastKnownLoc = lastKnownFrom(provider)
            if (lastKnownLoc != null) break
        }
        if (lastKnownLoc != null) {
            _location.value = lastKnownLoc
        }

        var registered = false
        for (provider in providers) {
            if (provider == LocationManager.GPS_PROVIDER && !hasFineLocationPermission()) continue
            if (provider == "fused" && !hasFineLocationPermission()) continue
            runCatching {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 500L, 0f, listener, Looper.getMainLooper())
                    registered = true
                }
            }.onFailure { error ->
                DebugLog.w(TAG) { "$provider updates unavailable: ${error.message}" }
            }
        }
        running = registered
        if (registered) DebugLog.i(TAG) { "Location updates started" }
        else DebugLog.w(TAG) { "No location provider is enabled" }
    }

    fun stop() {
        if (!running) return
        lm.removeUpdates(listener)
        running = false
    }

    /** Best last-known fix without starting updates (for routing before connecting). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        val valLoc = _location.value
        if (valLoc != null) return valLoc
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            lastKnownFrom("fused")?.let { return it }
        }
        return lastKnownFrom(LocationManager.GPS_PROVIDER)
            ?: lastKnownFrom(LocationManager.NETWORK_PROVIDER)
            ?: lastKnownFrom(LocationManager.PASSIVE_PROVIDER)
    }
}
