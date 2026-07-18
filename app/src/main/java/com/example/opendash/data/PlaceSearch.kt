package com.example.opendash.data

import com.example.opendash.BuildConfig
import com.example.opendash.util.DebugLog
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * A place candidate. Suggestions carry a provider id but no coordinates (those come from a
 * follow-up details/retrieve call); the Android-geocoder fallback carries coordinates and no id.
 */
data class Place(
    val name: String,
    val address: String,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val mapboxId: String? = null,
    val googleId: String? = null,
)

/**
 * In-app destination search. Providers, best first:
 *  1. Google Places (New) — same index as the Google Maps search box, by far the best
 *     coverage of Indian addresses/POIs. Used when GOOGLE_MAPS_API_KEY is set.
 *  2. Mapbox Search Box — used when only MAPBOX_ACCESS_TOKEN is set (or Google errors out).
 *  3. Caller-side fallback: RouteViewModel geocodes via the on-device Android Geocoder
 *     when this returns empty.
 *
 * Both providers use suggest → resolve with a shared session token (billed per session).
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"
    private const val MB_SUGGEST = "https://api.mapbox.com/search/searchbox/v1/suggest"
    private const val MB_RETRIEVE = "https://api.mapbox.com/search/searchbox/v1/retrieve"
    private const val G_AUTOCOMPLETE = "https://places.googleapis.com/v1/places:autocomplete"
    private const val G_PLACE = "https://places.googleapis.com/v1/places"

    /** Autocomplete suggestions near an optional proximity point. Empty on error / no keys.
     *  [context] is optional — when provided, a local SQLite offline cache is checked as a
     *  fallback so search works with no internet after a region has been downloaded.
     */
    suspend fun suggest(
        query: String, proxLat: Double?, proxLng: Double?, sessionToken: String,
        context: Context? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            val google = googleSuggest(query, proxLat, proxLng, sessionToken)
            if (google.isNotEmpty()) return@withContext google
        }
        val mapbox = mapboxSuggest(query, proxLat, proxLng, sessionToken)
        if (mapbox.isNotEmpty()) return@withContext mapbox
        // Offline fallback: local SQLite index built at region-download time.
        if (context != null) {
            val offline = suggestOffline(query, context)
            if (offline.isNotEmpty()) return@withContext offline
        }
        emptyList()
    }

    /** Resolve a picked suggestion to coordinates. Null on error. */
    suspend fun resolve(place: Place, sessionToken: String): Pair<Double, Double>? = when {
        place.googleId != null -> googleDetails(place.googleId, sessionToken)
        place.mapboxId != null -> retrieve(place.mapboxId, sessionToken)
        else -> place.lat to place.lng
    }

    // ── Google Places (New) ────────────────────────────────────────────────

    private suspend fun googleSuggest(
        query: String, proxLat: Double?, proxLng: Double?, sessionToken: String,
    ): List<Place> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("input", query)
                put("sessionToken", sessionToken)
                if (proxLat != null && proxLng != null) {
                    put("locationBias", JSONObject().put("circle", JSONObject()
                        .put("center", JSONObject().put("latitude", proxLat).put("longitude", proxLng))
                        .put("radius", 50000.0)))
                }
            }
            val resp = postJson(G_AUTOCOMPLETE, body.toString(), BuildConfig.GOOGLE_MAPS_API_KEY)
            val suggestions = JSONObject(resp).optJSONArray("suggestions") ?: return@withContext emptyList()
            (0 until suggestions.length()).mapNotNull { i ->
                val p = suggestions.getJSONObject(i).optJSONObject("placePrediction") ?: return@mapNotNull null
                val id = p.optString("placeId").ifBlank { return@mapNotNull null }
                val main = p.optJSONObject("structuredFormat")?.optJSONObject("mainText")?.optString("text")
                val secondary = p.optJSONObject("structuredFormat")?.optJSONObject("secondaryText")?.optString("text")
                Place(
                    name = main ?: p.optJSONObject("text")?.optString("text") ?: "Place",
                    address = secondary.orEmpty(),
                    googleId = id,
                )
            }
        } catch (e: Exception) {
            DebugLog.w(TAG) { "google suggest failed: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun googleDetails(placeId: String, sessionToken: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$G_PLACE/${URLEncoder.encode(placeId, "UTF-8")}" +
                    "?sessionToken=${URLEncoder.encode(sessionToken, "UTF-8")}"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("X-Goog-Api-Key", BuildConfig.GOOGLE_MAPS_API_KEY)
                    setRequestProperty("X-Goog-FieldMask", "location")
                }
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { conn.disconnect() }
                val loc = JSONObject(body).optJSONObject("location") ?: return@withContext null
                val lat = loc.optDouble("latitude", Double.NaN)
                val lng = loc.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) null else lat to lng
            } catch (e: Exception) {
                DebugLog.w(TAG) { "google details failed: ${e.message}" }
                null
            }
        }

    // ── Mapbox Search Box ──────────────────────────────────────────────────

    private suspend fun mapboxSuggest(
        query: String, proxLat: Double?, proxLng: Double?, sessionToken: String,
    ): List<Place> = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN
        if (token.isBlank()) return@withContext emptyList()
        val proximity = if (proxLat != null && proxLng != null) "&proximity=$proxLng,$proxLat" else ""
        val url = "$MB_SUGGEST?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&language=en&limit=6$proximity" +
            "&session_token=${URLEncoder.encode(sessionToken, "UTF-8")}" +
            "&access_token=${URLEncoder.encode(token, "UTF-8")}"
        try {
            val body = get(url)
            val suggestions = JSONObject(body).optJSONArray("suggestions") ?: return@withContext emptyList()
            (0 until suggestions.length()).mapNotNull { i ->
                val s = suggestions.getJSONObject(i)
                val id = s.optString("mapbox_id").ifBlank { return@mapNotNull null }
                Place(
                    name = s.optString("name").ifBlank { "Place" },
                    address = s.optString("full_address").ifBlank { s.optString("place_formatted") },
                    mapboxId = id,
                )
            }
        } catch (e: Exception) {
            DebugLog.w(TAG) { "suggest failed: ${e.message}" }
            emptyList()
        }
    }

    /** Resolve a Mapbox suggestion's coordinates. Null on error. */
    suspend fun retrieve(mapboxId: String, sessionToken: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            val token = BuildConfig.MAPBOX_ACCESS_TOKEN
            if (token.isBlank()) return@withContext null
            val url = "$MB_RETRIEVE/${URLEncoder.encode(mapboxId, "UTF-8")}" +
                "?session_token=${URLEncoder.encode(sessionToken, "UTF-8")}" +
                "&access_token=${URLEncoder.encode(token, "UTF-8")}"
            try {
                val features = JSONObject(get(url)).optJSONArray("features") ?: return@withContext null
                val coords = features.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: return@withContext null
                val lng = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) null else lat to lng
            } catch (e: Exception) {
                DebugLog.w(TAG) { "retrieve failed: ${e.message}" }
                null
            }
        }

    // ── Offline SQLite fallback ────────────────────────────────────────────

    /**
     * Query the local [offline_place] table that was indexed when the user downloaded a
     * map region. Results carry coordinates directly, so [resolve] needs no network call.
     */
    private fun suggestOffline(query: String, context: Context): List<Place> = try {
        val db = OpenDashDb.get(context)
        db.searchOfflinePlaces(query).map { p ->
            Place(
                name    = p.name,
                address = if (p.address.isNotBlank()) p.address else p.region,
                lat     = p.lat,
                lng     = p.lng,
            )
        }
    } catch (e: Exception) {
        DebugLog.w(TAG) { "offline suggest failed: ${e.message}" }
        emptyList()
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { conn.disconnect() }
    }

    private fun postJson(url: String, json: String, apiKey: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Goog-Api-Key", apiKey)
        }
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { conn.disconnect() }
    }
}
