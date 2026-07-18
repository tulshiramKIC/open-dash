package com.example.opendash.data

import android.content.Context
import com.example.opendash.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bring-your-own API keys, editable in Settings → API Keys — no rebuild needed.
 *
 * Resolution order per key: user-entered value → BuildConfig baked-in default (from
 * local.properties, for developer builds) → blank, which simply disables the feature
 * the key unlocks. The map itself is keyless (OpenFreeMap), so the app always works;
 * keys only ADD capabilities:
 *  - Google Maps  → best routing (true two-wheeler profile) + place search
 *  - Mapbox       → routing fallback + waypoint stops + search fallback
 *  - Supabase     → Group Ride live location (URL + anon key; the anon key is a
 *    publishable client key — ride groups can share the same pair freely)
 *
 * Values live in app-private SharedPreferences. All of these are client-side keys by
 * design (they ship inside public mobile apps), so device-private storage is adequate.
 */
object ApiKeys {
    private const val PREFS = "api_keys"
    private const val K_GOOGLE = "google_maps"
    private const val K_MAPBOX = "mapbox"
    private const val K_SUPABASE_URL = "supabase_url"
    private const val K_SUPABASE_ANON = "supabase_anon"

    data class Values(
        val googleMaps: String = "",
        val mapbox: String = "",
        val supabaseUrl: String = "",
        val supabaseAnon: String = "",
    )

    /** User-entered values only (what the Settings fields show). */
    private val _user = MutableStateFlow(Values())
    val user = _user.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _user.value = Values(
            googleMaps = p.getString(K_GOOGLE, "") ?: "",
            mapbox = p.getString(K_MAPBOX, "") ?: "",
            supabaseUrl = p.getString(K_SUPABASE_URL, "") ?: "",
            supabaseAnon = p.getString(K_SUPABASE_ANON, "") ?: "",
        )
    }

    fun save(values: Values) {
        val trimmed = Values(
            googleMaps = values.googleMaps.trim(),
            mapbox = values.mapbox.trim(),
            supabaseUrl = values.supabaseUrl.trim().removeSuffix("/"),
            supabaseAnon = values.supabaseAnon.trim(),
        )
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(K_GOOGLE, trimmed.googleMaps)
            ?.putString(K_MAPBOX, trimmed.mapbox)
            ?.putString(K_SUPABASE_URL, trimmed.supabaseUrl)
            ?.putString(K_SUPABASE_ANON, trimmed.supabaseAnon)
            ?.apply()
        _user.value = trimmed
    }

    // ── Effective values (user overrides build default) — use these at call sites ──
    val googleMaps: String get() = _user.value.googleMaps.ifBlank { BuildConfig.GOOGLE_MAPS_API_KEY }
    val mapbox: String get() = _user.value.mapbox.ifBlank { BuildConfig.MAPBOX_ACCESS_TOKEN }
    val supabaseUrl: String get() = _user.value.supabaseUrl.ifBlank { BuildConfig.SUPABASE_URL }
    val supabaseAnon: String get() = _user.value.supabaseAnon.ifBlank { BuildConfig.SUPABASE_ANON_KEY }

    /** True when the build bakes in a default for the given effective getter's source. */
    fun hasBuildDefault(key: String): Boolean = when (key) {
        K_GOOGLE -> BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()
        K_MAPBOX -> BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()
        K_SUPABASE_URL -> BuildConfig.SUPABASE_URL.isNotBlank()
        K_SUPABASE_ANON -> BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
        else -> false
    }

    const val KEY_GOOGLE = K_GOOGLE
    const val KEY_MAPBOX = K_MAPBOX
    const val KEY_SUPABASE_URL = K_SUPABASE_URL
    const val KEY_SUPABASE_ANON = K_SUPABASE_ANON
}
