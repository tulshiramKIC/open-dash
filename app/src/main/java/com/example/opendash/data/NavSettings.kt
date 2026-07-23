package com.example.opendash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation preferences. Live-traffic refresh while riding is always on (throttled inside
 * DashViewModel) — no toggle, per the no-fake-toggles rule.
 */
object NavSettings {
    private const val PREFS = "appearance"
    private const val KEY_CUSTOM_TRAILS = "custom_trails_enabled"
    private const val KEY_BIKE_MARKER = "bike_marker"
    private const val KEY_MAP_THEME = "map_theme"
    private const val KEY_INTERCOM_VOLUME = "intercom_volume"
    private const val KEY_MESH_CODE = "mesh_code"
    private const val KEY_CRASH_SOS = "crash_sos_enabled"
    private const val KEY_SOS_CONTACT = "sos_contact"
    private const val KEY_NORTH_UP_NAV = "north_up_nav"
    private const val KEY_CHASE_RIDER = "chase_rider_enabled"

    /** Map day/night theme. AUTO switches to night at [NIGHT_START_HOUR] and back at [DAY_START_HOUR]. */
    enum class MapTheme { DAY, NIGHT, AUTO }

    const val NIGHT_START_HOUR = 19 // 7 pm
    const val DAY_START_HOUR = 6    // 6 am

    private val _customTrailsEnabled = MutableStateFlow(true)
    val customTrailsEnabled = _customTrailsEnabled.asStateFlow()

    // Dash-map rider marker style: false = arrow (fresh-install default), true = bike.
    private val _bikeMarker = MutableStateFlow(false)
    val bikeMarker = _bikeMarker.asStateFlow()

    private val _mapTheme = MutableStateFlow(MapTheme.AUTO)
    val mapTheme = _mapTheme.asStateFlow()

    private val _intercomVolume = MutableStateFlow(1.0f)
    val intercomVolume = _intercomVolume.asStateFlow()

    private val _meshCode = MutableStateFlow("")
    val meshCode = _meshCode.asStateFlow()

    // Crash detection + SOS SMS. Off by default — arming an accelerometer watch and
    // sending SMS on the rider's behalf must be an explicit opt-in.
    private val _crashSosEnabled = MutableStateFlow(false)
    val crashSosEnabled = _crashSosEnabled.asStateFlow()

    private val _sosContact = MutableStateFlow("")
    val sosContact = _sosContact.asStateFlow()

    /**
     * Navigation camera style, Google's "keep map north up" option: false (default) =
     * heading-up, the map rotates with travel and the rider icon points screen-up;
     * true = map stays north-up and only the beam/icon rotates.
     */
    private val _northUpNav = MutableStateFlow(false)
    val northUpNav = _northUpNav.asStateFlow()

    // "Navigate to a rider" (chase). Each active chase polls the routing API as riders
    // move, so it's a togglable feature — off avoids any Router billing entirely.
    private val _chaseRiderEnabled = MutableStateFlow(true)
    val chaseRiderEnabled = _chaseRiderEnabled.asStateFlow()


    /** Whether the map should render dark right now, given the current mode + clock. */
    fun nightActive(theme: MapTheme = _mapTheme.value): Boolean = when (theme) {
        MapTheme.DAY -> false
        MapTheme.NIGHT -> true
        MapTheme.AUTO -> {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            hour >= NIGHT_START_HOUR || hour < DAY_START_HOUR
        }
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _customTrailsEnabled.value = prefs.getBoolean(KEY_CUSTOM_TRAILS, true)
        _bikeMarker.value = prefs.getBoolean(KEY_BIKE_MARKER, false)
        _mapTheme.value = runCatching {
            MapTheme.valueOf(prefs.getString(KEY_MAP_THEME, MapTheme.AUTO.name)!!)
        }.getOrDefault(MapTheme.AUTO)
        _intercomVolume.value = prefs.getFloat(KEY_INTERCOM_VOLUME, 1.0f)
        _meshCode.value = prefs.getString(KEY_MESH_CODE, "") ?: ""
        _crashSosEnabled.value = prefs.getBoolean(KEY_CRASH_SOS, false)
        _sosContact.value = prefs.getString(KEY_SOS_CONTACT, "") ?: ""
        _northUpNav.value = prefs.getBoolean(KEY_NORTH_UP_NAV, false)
        _chaseRiderEnabled.value = prefs.getBoolean(KEY_CHASE_RIDER, true)
    }

    fun setChaseRiderEnabled(context: Context, on: Boolean) {
        _chaseRiderEnabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CHASE_RIDER, on).apply()
    }

    fun setNorthUpNav(context: Context, on: Boolean) {
        _northUpNav.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NORTH_UP_NAV, on).apply()
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

    fun setMapTheme(context: Context, theme: MapTheme) {
        _mapTheme.value = theme
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAP_THEME, theme.name).apply()
    }

    fun setIntercomVolume(context: Context, vol: Float) {
        _intercomVolume.value = vol
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_INTERCOM_VOLUME, vol).apply()
        IntercomEngine.updateIntercomVolume(vol)
    }

    fun setMeshCode(context: Context, code: String) {
        val sanitized = code.trim().uppercase().take(6)
        _meshCode.value = sanitized
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESH_CODE, sanitized).apply()
    }

    fun setCrashSosEnabled(context: Context, on: Boolean) {
        _crashSosEnabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CRASH_SOS, on).apply()
        // Live sessions react immediately — no restart needed.
        if (on) CrashDetector.startIfRideActive(context) else CrashDetector.stop()
    }

    fun setSosContact(context: Context, number: String) {
        val sanitized = number.filter { it.isDigit() || it == '+' }.take(16)
        _sosContact.value = sanitized
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SOS_CONTACT, sanitized).apply()
    }
}
