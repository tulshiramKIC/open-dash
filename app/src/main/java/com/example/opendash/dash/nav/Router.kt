package com.example.opendash.dash.nav

import com.example.opendash.BuildConfig
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Fetches road routes from the Mapbox Directions API. Called at planning time
 * (destination shared) while the phone still has internet — the result is cached
 * so riding can proceed offline. Driving profile suits the Himalayan fine.
 *
 * Mapbox Directions is OSRM-based, so the response shape (routes[].geometry polyline,
 * legs[].steps[].maneuver{type,modifier,location}, distance, duration) matches what the
 * nav model already expects — [ManeuverType.fromOsrm] handles the same type/modifier
 * vocabulary. Requesting `alternatives=true` returns up to ~3 distinct routes.
 *
 * Needs a Mapbox access token (BuildConfig.MAPBOX_ACCESS_TOKEN, bring-your-own). When the
 * token is blank, routing is disabled and callers get an empty result / null.
 */
object Router {
    /**
     * Mapbox has no motorcycle profile, and "cycling" is bicycles — cycle paths and
     * pedal-speed ETAs. Both modes use the car profile; Bike just excludes motorways,
     * since expressways ban two-wheelers while every other road rides like a car.
     */
    enum class TravelMode(val label: String, val exclude: String?) {
        CAR("Car", null),
        BIKE("Bike", "motorway"),
    }

    /** Mode picked on the route screen; dash-side reroutes reuse it via the default arg. */
    @Volatile var currentMode: TravelMode = TravelMode.BIKE

    private const val TAG = "Router"
    private const val BASE = "https://api.mapbox.com/directions/v5/mapbox"
    private const val UA = "OpenDash/1.3 (personal motorcycle nav; single user)"

    /** Single best route (backwards-compatible with callers that don't need alternatives). */
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        mode: TravelMode = currentMode,
    ): Route? = routes(from, to, alternatives = false, mode = mode).firstOrNull()

    /**
     * Primary route plus alternatives. Element [0] is Mapbox's primary/recommended route;
     * the rest are alternatives (may be empty). Empty list on error or missing token.
     */
    suspend fun routes(
        from: GeoPoint,
        to: GeoPoint,
        alternatives: Boolean = true,
        mode: TravelMode = currentMode,
    ): List<Route> =
        withContext(Dispatchers.IO) {
            val token = BuildConfig.MAPBOX_ACCESS_TOKEN
            if (token.isBlank()) {
                DebugLog.w(TAG) { "No Mapbox token — set MAPBOX_ACCESS_TOKEN in local.properties" }
                return@withContext emptyList()
            }
            val url = "$BASE/driving/${from.lng},${from.lat};${to.lng},${to.lat}" +
                "?alternatives=$alternatives&overview=full&geometries=polyline&steps=true" +
                (mode.exclude?.let { "&exclude=$it" } ?: "") +
                "&access_token=${URLEncoder.encode(token, "UTF-8")}"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA)
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                parseRoutes(body)
            } catch (e: Exception) {
                DebugLog.w(TAG) { "routes() failed: ${e.message}" }
                emptyList()
            }
        }

    private fun parseRoutes(json: String): List<Route> {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            DebugLog.w(TAG) { "Mapbox code=${root.optString("code")}" }
            return emptyList()
        }
        val routes = root.optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { i -> parseRoute(routes.getJSONObject(i)) }
    }

    private fun parseRoute(r0: JSONObject): Route? {
        val geometry = PolylineCodec.decode(r0.getString("geometry"))
        if (geometry.size < 2) return null

        // Cumulative distance at each vertex
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        // Maneuvers from steps
        val maneuvers = ArrayList<Maneuver>()
        val legs = r0.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.getJSONObject(si)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location") ?: continue
                    val p = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                    val type = ManeuverType.fromOsrm(man.optString("type"), man.optString("modifier"))
                    val name = step.optString("name").ifBlank { "road" }
                    maneuvers.add(
                        Maneuver(
                            type = type,
                            instruction = buildInstruction(type, name),
                            location = p,
                            cumulativeMeters = nearestCumulative(p, geometry, cum),
                        )
                    )
                }
            }
        }

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = r0.optDouble("distance", cum.last()),
            totalSeconds = r0.optDouble("duration", 0.0),
            cumulative = cum,
        )
    }

    private fun buildInstruction(type: ManeuverType, road: String): String = when (type) {
        ManeuverType.DEPART       -> "Head out on $road"
        ManeuverType.ARRIVE       -> "Arrive at destination"
        ManeuverType.TURN_LEFT    -> "Turn left onto $road"
        ManeuverType.TURN_RIGHT   -> "Turn right onto $road"
        ManeuverType.SLIGHT_LEFT  -> "Slight left onto $road"
        ManeuverType.SLIGHT_RIGHT -> "Slight right onto $road"
        ManeuverType.SHARP_LEFT   -> "Sharp left onto $road"
        ManeuverType.SHARP_RIGHT  -> "Sharp right onto $road"
        ManeuverType.UTURN        -> "Make a U-turn"
        ManeuverType.ROUNDABOUT   -> "At the roundabout, take $road"
        ManeuverType.CONTINUE     -> "Continue on $road"
    }

    /** Cumulative distance of the geometry vertex nearest to a maneuver location. */
    private fun nearestCumulative(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) { bestD = d; best = cum[i] }
        }
        return best
    }
}
