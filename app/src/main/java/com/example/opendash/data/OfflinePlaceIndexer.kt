package com.example.opendash.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches named places from the Overpass API for a downloaded map region and indexes
 * them into [OpenDashDb] so that [PlaceSearch] can return results while offline.
 *
 * Called once when a MapLibre offline region download completes. This is a one-time,
 * background network call that happens *while the phone is still online* — the result
 * is then available forever with no internet required.
 */
object OfflinePlaceIndexer {

    private const val TAG = "OfflinePlaceIndexer"

    /**
     * Overpass endpoint. The de/api mirror is the most stable public instance; if it is
     * ever unavailable the fallback (kumi.systems) is tried.
     */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.openstreetmap.ru/api/interpreter",
    )

    /**
     * Download and index all named OSM nodes within [bounds] under the region label [name].
     *
     * We query only *nodes* (not ways/relations) to keep response size small.
     * Each node must have a `name` tag. We capture `name`, `addr:city`/`place` as address.
     *
     * Safe to call multiple times — old entries for [name] are replaced atomically.
     */
    suspend fun index(db: OpenDashDb, name: String, bounds: LatLngBounds) =
        withContext(Dispatchers.IO) {
            val s = bounds.latitudeSouth
            val w = bounds.longitudeWest
            val n = bounds.latitudeNorth
            val e = bounds.longitudeEast

            // Overpass QL: fetch key POIs that have a name in the bounding box.
            // Using nwr (node, way, relation) and out center allows us to retrieve
            // buildings, colleges, and areas that are not mapped as simple nodes.
            val query = """
                [out:json][timeout:25];
                (
                  nwr["place"]["name"]($s,$w,$n,$e);
                  nwr["amenity"]["name"]($s,$w,$n,$e);
                  nwr["tourism"]["name"]($s,$w,$n,$e);
                  nwr["shop"]["name"]($s,$w,$n,$e);
                  nwr["building"]["name"]($s,$w,$n,$e);
                  nwr["leisure"]["name"]($s,$w,$n,$e);
                  nwr["office"]["name"]($s,$w,$n,$e);
                  nwr["historic"]["name"]($s,$w,$n,$e);
                  nwr["highway"~"services|rest_area"]["name"]($s,$w,$n,$e);
                  nwr["railway"~"station|halt"]["name"]($s,$w,$n,$e);
                  nwr["public_transport"~"station|stop_position"]["name"]($s,$w,$n,$e);
                );
                out center;
            """.trimIndent()

            val body = fetchOverpass(query) ?: run {
                Log.w(TAG, "Overpass fetch failed for region '$name'")
                return@withContext
            }

            val places = parseOverpassJson(body, name)
            if (places.isEmpty()) {
                Log.i(TAG, "No named nodes found for region '$name'")
                return@withContext
            }

            db.insertOfflinePlaces(places)
            Log.i(TAG, "Indexed ${places.size} places for region '$name'")
        }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private val okHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun fetchOverpass(query: String): String? {
        val payload = "data=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = payload.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        Log.d(TAG, "Sending query:\n$query")
        for (endpoint in ENDPOINTS) {
            try {
                Log.d(TAG, "Attempting Overpass query on endpoint: $endpoint")
                val request = okhttp3.Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "OpenDashNavigationApp/1.3.1 (https://github.com/tulshiram/open-dash; contact@opendash.com)")
                    .header("Accept", "application/json")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val respString = response.body?.string()
                if (response.isSuccessful) {
                    // Overpass sometimes returns 200 OK with an HTML error page when overloaded.
                    if (respString != null && respString.trimStart().startsWith("{")) {
                        Log.d(TAG, "Successful response from $endpoint")
                        return respString
                    } else {
                        Log.w(TAG, "Endpoint $endpoint returned invalid JSON (overloaded?). Response: $respString")
                    }
                } else {
                    Log.w(TAG, "Endpoint $endpoint failed with code: ${response.code}. Response: $respString")
                }
                response.close()
            } catch (e: Exception) {
                Log.w(TAG, "Overpass endpoint $endpoint error: ${e.message}", e)
            }
        }
        return null
    }

    // ── Parser ────────────────────────────────────────────────────────────

    private fun parseOverpassJson(
        json: String,
        region: String,
    ): List<OpenDashDb.OfflinePlace> {
        return try {
            val elements = JSONObject(json).optJSONArray("elements")
                ?: return emptyList()

            val seen = HashSet<String>()   // dedup by "name|lat|lng"
            val places = ArrayList<OpenDashDb.OfflinePlace>(elements.length())

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val tags = el.optJSONObject("tags") ?: continue
                val rawName = tags.optString("name").ifBlank { continue }

                var lat = el.optDouble("lat", Double.NaN)
                var lng = el.optDouble("lon", Double.NaN)

                // For ways and relations, the coordinates are returned inside a "center" object
                if (lat.isNaN() || lng.isNaN()) {
                    val center = el.optJSONObject("center")
                    if (center != null) {
                        lat = center.optDouble("lat", Double.NaN)
                        lng = center.optDouble("lon", Double.NaN)
                    }
                }

                if (lat.isNaN() || lng.isNaN()) continue

                // Build a readable secondary address line.
                val city = tags.optString("addr:city").ifBlank {
                    tags.optString("addr:district").ifBlank {
                        tags.optString("place").ifBlank { "" }
                    }
                }
                val state = tags.optString("addr:state").ifBlank { "" }
                val address = listOf(city, state).filter { it.isNotBlank() }.joinToString(", ")

                val key = "$rawName|${"%.5f".format(lat)}|${"%.5f".format(lng)}"
                if (seen.add(key)) {
                    places += OpenDashDb.OfflinePlace(
                        region  = region,
                        name    = rawName,
                        address = address,
                        lat     = lat,
                        lng     = lng,
                    )
                }
            }
            places
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: ${e.message}", e)
            emptyList()
        }
    }
}
