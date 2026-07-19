package com.example.opendash.dash.nav

import com.example.opendash.BuildConfig
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Fetches road routes. Called at planning time (destination shared) while the phone still
 * has internet — the result is cached so riding can proceed offline.
 *
 * Two providers, best first:
 *  1. Google Routes API (computeRoutes) — traffic-aware routing, a real TWO_WHEELER
 *     motorcycle profile, and Google's route quality/alternates. Used when
 *     GOOGLE_MAPS_API_KEY is set and the Routes API is enabled on the key.
 *  2. Mapbox Directions (OSRM-shaped) — fallback when Google is absent/unavailable.
 *
 * Both decode to the same [Route] model (geometry polyline + maneuvers + distance/duration),
 * so callers and the dash nav engine don't care which provider answered. Empty list on error
 * or when neither key is configured.
 */
object Router {
    /**
     * Google Routes has a real TWO_WHEELER profile (motorcycle-legal roads, right speeds).
     * Mapbox has none — its car profile with exclude=motorway is the closest approximation
     * (expressways ban two-wheelers; every other road rides like a car). CAR maps to DRIVE.
     */
    enum class TravelMode(val label: String, val googleMode: String, val mapboxExclude: String?) {
        CAR("Car", "DRIVE", null),
        BIKE("Bike", "TWO_WHEELER", "motorway"),
    }

    /** Mode picked on the route screen; dash-side reroutes reuse it via the default arg. */
    @Volatile var currentMode: TravelMode = TravelMode.BIKE

    private const val TAG = "Router"
    private const val MAPBOX_BASE = "https://api.mapbox.com/directions/v5/mapbox"
    private const val GOOGLE_ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val UA = "OpenDash/1.4 (personal motorcycle nav; single user)"

    /** Single best route (backwards-compatible with callers that don't need alternatives). */
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        mode: TravelMode = currentMode,
    ): Route? = routes(from, to, alternatives = false, mode = mode).firstOrNull()

    /**
     * Primary route plus alternatives. Element [0] is the recommended route; the rest are
     * alternatives (may be empty). Tries Google, falls back to Mapbox. Empty on total failure.
     */
    suspend fun routes(
        from: GeoPoint,
        to: GeoPoint,
        stops: List<GeoPoint> = emptyList(),
        alternatives: Boolean = true,
        mode: TravelMode = currentMode,
    ): List<Route> = withContext(Dispatchers.IO) {
        if (com.example.opendash.data.ApiKeys.googleMaps.isNotBlank() && stops.isEmpty()) {
            val google = googleRoutes(from, to, stops, alternatives, mode)
            if (google.isNotEmpty()) return@withContext google
            DebugLog.w(TAG) { "Google Routes empty — falling back to Mapbox" }
        }
        mapboxRoutes(from, to, stops, alternatives, mode)
    }

    // ── Google Routes API (computeRoutes) ──────────────────────────────────

    private fun googleRoutes(
        from: GeoPoint, to: GeoPoint, stops: List<GeoPoint>, alternatives: Boolean, mode: TravelMode,
    ): List<Route> {
        val body = JSONObject().apply {
            put("origin", waypoint(from))
            put("destination", waypoint(to))
            if (stops.isNotEmpty()) {
                val intermediates = JSONArray()
                stops.forEach { intermediates.put(waypoint(it)) }
                put("intermediates", intermediates)
            }
            put("travelMode", mode.googleMode)
            put("routingPreference", "TRAFFIC_AWARE_OPTIMAL")
            // Without this, speedReadingIntervals never appear in the response — the
            // field mask alone doesn't turn traffic-on-polyline computation on.
            put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))
            put("computeAlternativeRoutes", if (stops.isNotEmpty()) false else alternatives)
            put("polylineEncoding", "ENCODED_POLYLINE")
            put("languageCode", "en-IN")
            put("units", "METRIC")
        }
        val fieldMask = "routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration," +
            "routes.legs.steps.navigationInstruction,routes.legs.steps.startLocation,routes.legs.steps.distanceMeters," +
            "routes.travelAdvisory.speedReadingIntervals"
        return try {
            val conn = (URL(GOOGLE_ROUTES).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Goog-Api-Key", com.example.opendash.data.ApiKeys.googleMaps)
                setRequestProperty("X-Goog-FieldMask", fieldMask)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                DebugLog.w(TAG) { "Google Routes HTTP $code: ${resp.take(300)}" }
                return emptyList()
            }
            parseGoogleRoutes(resp)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "googleRoutes() failed: ${e.message}" }
            emptyList()
        }
    }

    private fun waypoint(p: GeoPoint): JSONObject =
        JSONObject().put("location", JSONObject().put("latLng",
            JSONObject().put("latitude", p.lat).put("longitude", p.lng)))

    private fun parseGoogleRoutes(json: String): List<Route> {
        val routes = JSONObject(json).optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { i -> parseGoogleRoute(routes.getJSONObject(i)) }
    }

    private fun parseGoogleRoute(r: JSONObject): Route? {
        val encoded = r.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
        val geometry = PolylineCodec.decode(encoded)
        if (geometry.size < 2) return null

        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        val maneuvers = ArrayList<Maneuver>()
        r.optJSONArray("legs")?.let { legs ->
            for (li in 0 until legs.length()) {
                val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.getJSONObject(si)
                    val nav = step.optJSONObject("navigationInstruction")
                    val loc = step.optJSONObject("startLocation")?.optJSONObject("latLng") ?: continue
                    val p = GeoPoint(loc.optDouble("latitude"), loc.optDouble("longitude"))
                    val type = ManeuverType.fromGoogle(nav?.optString("maneuver"))
                    // Google supplies a ready-made instruction string; keep it when present.
                    val instruction = nav?.optString("instructions")?.ifBlank { null }
                        ?: buildInstruction(type, "road")
                    maneuvers.add(
                        Maneuver(type, instruction, p, nearestCumulative(p, geometry, cum))
                    )
                }
            }
        }
        // Google has no explicit arrival step — synthesize one at the destination.
        if (maneuvers.lastOrNull()?.type != ManeuverType.ARRIVE) {
            maneuvers.add(Maneuver(ManeuverType.ARRIVE, "Arrive at destination", geometry.last(), cum.last()))
        }

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = r.optDouble("distanceMeters", cum.last()),
            totalSeconds = parseDurationSeconds(r.optString("duration")),
            cumulative = cum,
            congestion = parseCongestion(r.optJSONObject("travelAdvisory"), geometry.size),
        )
    }

    /**
     * Convert Google's speedReadingIntervals (NORMAL/SLOW/TRAFFIC_JAM over polyline point
     * ranges) into a per-segment level array (0/1/2). Empty when no traffic data is present.
     */
    private fun parseCongestion(advisory: JSONObject?, pointCount: Int): List<Int> {
        val intervals = advisory?.optJSONArray("speedReadingIntervals") ?: return emptyList()
        if (intervals.length() == 0 || pointCount < 2) return emptyList()
        val seg = IntArray(pointCount - 1)
        for (i in 0 until intervals.length()) {
            val iv = intervals.getJSONObject(i)
            val start = iv.optInt("startPolylinePointIndex", 0)
            val end = iv.optInt("endPolylinePointIndex", start)
            val level = when (iv.optString("speed")) {
                "SLOW"        -> 1
                "TRAFFIC_JAM" -> 2
                else          -> 0
            }
            var s = start.coerceAtLeast(0)
            while (s < end && s < seg.size) { seg[s] = level; s++ }
        }
        return seg.toList()
    }

    /** Google durations look like "1234s". */
    private fun parseDurationSeconds(s: String?): Double =
        s?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0

    // ── Mapbox Directions (fallback) ───────────────────────────────────────

    private fun mapboxRoutes(
        from: GeoPoint, to: GeoPoint, stops: List<GeoPoint>, alternatives: Boolean, mode: TravelMode,
    ): List<Route> {
        val token = com.example.opendash.data.ApiKeys.mapbox
        if (token.isBlank()) {
            DebugLog.w(TAG) { "No routing provider — set GOOGLE_MAPS_API_KEY or MAPBOX_ACCESS_TOKEN" }
            return emptyList()
        }
        val coords = mutableListOf(from)
        coords.addAll(stops)
        coords.add(to)
        val coordString = coords.joinToString(";") { "${it.lng},${it.lat}" }
        // driving-traffic: only this profile carries live congestion annotations…
        val routes = mapboxRequest("driving-traffic", coordString, alternatives, mode, token, congestion = true)
            .toMutableList()
        // …but it is stingy with alternatives. When it returns just one route, ask the
        // plain driving profile for alternates and append the genuinely different ones
        // (they carry no congestion — drawn plain blue, which is fine for an alternate).
        if (alternatives && routes.size <= 1) {
            val extra = mapboxRequest("driving", coordString, true, mode, token, congestion = false)
            for (r in extra) {
                if (routes.none { sameRoad(it, r) }) routes.add(r)
            }
            if (routes.isEmpty()) return emptyList()
        }
        return routes
    }

    private fun mapboxRequest(
        profile: String, coordString: String, alternatives: Boolean, mode: TravelMode,
        token: String, congestion: Boolean,
    ): List<Route> {
        val url = "$MAPBOX_BASE/$profile/$coordString" +
            "?alternatives=$alternatives&overview=full&geometries=polyline&steps=true" +
            (if (congestion) "&annotations=congestion" else "") +
            (mode.mapboxExclude?.let { "&exclude=$it" } ?: "") +
            "&access_token=${URLEncoder.encode(token, "UTF-8")}"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parseMapboxRoutes(body)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "mapboxRequest($profile) failed: ${e.message}" }
            emptyList()
        }
    }

    /** True when [b] rides essentially the same roads as [a] (≥80% of sampled points within 40 m). */
    private fun sameRoad(a: Route, b: Route): Boolean {
        val sampleStep = (b.geometry.size / 30).coerceAtLeast(1)
        var near = 0
        var total = 0
        val refStep = (a.geometry.size / 200).coerceAtLeast(1)
        val ref = a.geometry.filterIndexed { i, _ -> i % refStep == 0 }
        for (i in b.geometry.indices step sampleStep) {
            total++
            val p = b.geometry[i]
            if (ref.any { GeoPoint.distMeters(p, it) < 40.0 }) near++
        }
        return total > 0 && near.toDouble() / total >= 0.8
    }

    private fun parseMapboxRoutes(json: String): List<Route> {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            DebugLog.w(TAG) { "Mapbox code=${root.optString("code")}" }
            return emptyList()
        }
        val routes = root.optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { i -> parseMapboxRoute(routes.getJSONObject(i)) }
    }

    private fun parseMapboxRoute(r0: JSONObject): Route? {
        val geometry = PolylineCodec.decode(r0.getString("geometry"))
        if (geometry.size < 2) return null

        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

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

        // Congestion annotation: one level string per geometry segment, per leg.
        val congestion = ArrayList<Int>()
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val arr = legs.getJSONObject(li).optJSONObject("annotation")
                    ?.optJSONArray("congestion") ?: continue
                for (ci in 0 until arr.length()) {
                    congestion.add(
                        when (arr.optString(ci)) {
                            "moderate"        -> 1
                            "heavy", "severe" -> 2
                            else              -> 0
                        }
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
            congestion = fitCongestion(congestion, geometry.size),
        )
    }

    /**
     * Map a congestion array onto exactly [pointCount]-1 segments. The annotation usually
     * matches the full-resolution geometry already; when leg/overview simplification makes
     * the counts drift, resample by index so the coloring still lines up.
     */
    private fun fitCongestion(congestion: List<Int>, pointCount: Int): List<Int> {
        val segments = pointCount - 1
        if (congestion.isEmpty() || segments <= 0) return emptyList()
        if (congestion.size == segments) return congestion
        return List(segments) { i -> congestion[(i.toLong() * congestion.size / segments).toInt().coerceIn(0, congestion.size - 1)] }
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

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
