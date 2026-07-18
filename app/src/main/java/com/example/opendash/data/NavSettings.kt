package com.example.opendash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation preferences. [liveTraffic] controls whether the route's traffic coloring is
 * refreshed periodically while riding (a routing API call every couple of minutes) versus
 * only computed once at planning time. Off by default — turn it on once a billed API key is
 * ready, since it costs a handful of extra calls per ride.
 */
object NavSettings {
    private const val PREFS = "appearance"
    private const val KEY_LIVE_TRAFFIC = "live_traffic"
    private const val KEY_CUSTOM_TRAILS = "custom_trails_enabled"
    private const val KEY_BIKE_MARKER = "bike_marker"

    private val _liveTraffic = MutableStateFlow(false)
    val liveTraffic = _liveTraffic.asStateFlow()

    private val _customTrailsEnabled = MutableStateFlow(true)
    val customTrailsEnabled = _customTrailsEnabled.asStateFlow()

    // Dash-map rider marker style: false = arrow (fresh-install default), true = bike.
    private val _bikeMarker = MutableStateFlow(false)
    val bikeMarker = _bikeMarker.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _liveTraffic.value = prefs.getBoolean(KEY_LIVE_TRAFFIC, false)
        _customTrailsEnabled.value = prefs.getBoolean(KEY_CUSTOM_TRAILS, true)
        _bikeMarker.value = prefs.getBoolean(KEY_BIKE_MARKER, false)
    }

    fun setLiveTraffic(context: Context, on: Boolean) {
        _liveTraffic.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LIVE_TRAFFIC, on).apply()
    }

    fun setCustomTrailsEnabled(context: Context, on: Boolean) {
        _customTrailsEnabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CUSTOM_TRAILS, on).apply()
    }

    fun setBikeMarker(context: Context, on: Boolean) {
        _bikeMarker.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BIKE_MARKER, on).apply()
    }
}
