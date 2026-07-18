package com.example.opendash.dash.nav

/** Maneuver glyphs the dash understands. Only CONTINUE (0x0B) is hardware-verified;
 *  the rest are best-effort guesses and must be checked on fw 11.63. Until then
 *  [Maneuver.dashCode] falls back to CONTINUE so the dash never shows a wrong arrow. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE;

    companion object {
        /** Map an OSRM step maneuver (type + modifier) to our enum. */
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart"   -> DEPART
            "arrive"   -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            "fork", "end of road", "turn", "new name", "continue", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left"         -> TURN_LEFT
                    "right"        -> TURN_RIGHT
                    "slight left"  -> SLIGHT_LEFT
                    "slight right" -> SLIGHT_RIGHT
                    "sharp left"   -> SHARP_LEFT
                    "sharp right"  -> SHARP_RIGHT
                    "uturn"        -> UTURN
                    else           -> CONTINUE
                }
            else -> CONTINUE
        }

        /** Map a Google Routes navigationInstruction.maneuver enum to our enum. */
        fun fromGoogle(maneuver: String?): ManeuverType = when (maneuver) {
            "DEPART"                              -> DEPART
            "DESTINATION", "DESTINATION_LEFT", "DESTINATION_RIGHT" -> ARRIVE
            "TURN_LEFT"                           -> TURN_LEFT
            "TURN_RIGHT"                          -> TURN_RIGHT
            "TURN_SLIGHT_LEFT", "FORK_LEFT", "RAMP_LEFT"   -> SLIGHT_LEFT
            "TURN_SLIGHT_RIGHT", "FORK_RIGHT", "RAMP_RIGHT" -> SLIGHT_RIGHT
            "TURN_SHARP_LEFT"                     -> SHARP_LEFT
            "TURN_SHARP_RIGHT"                    -> SHARP_RIGHT
            "UTURN_LEFT", "UTURN_RIGHT"           -> UTURN
            "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> ROUNDABOUT
            else                                  -> CONTINUE   // STRAIGHT, MERGE, NAME_CHANGE…
        }
    }
}

/** One routing instruction located at a point along the geometry. */
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: GeoPoint,
    /** Cumulative distance (m) from the route start to this maneuver's location. */
    val cumulativeMeters: Double,
) {
    /** Dash maneuver glyph byte. CONTINUE (0x0B) is the only verified value. */
    val dashCode: Int get() = 0x0B // TODO: verify other glyph codes on fw 11.63
}

/** A computed road route from origin to destination. */
data class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    /** Cumulative distance (m) at each geometry vertex — same length as [geometry]. */
    val cumulative: DoubleArray,
    /**
     * Live traffic per geometry segment (size = geometry.size - 1): 0 = normal/free-flow,
     * 1 = slow, 2 = jam. Empty when the provider returned no traffic data. Used to color
     * the route line like an in-navigation traffic view.
     */
    val congestion: List<Int> = emptyList(),
) {
    val destination: GeoPoint? get() = geometry.lastOrNull()

    fun toJson(): String {
        val obj = org.json.JSONObject()
        
        // Geometry
        val geomArr = org.json.JSONArray()
        geometry.forEach { gp ->
            geomArr.put(org.json.JSONArray().put(gp.lat).put(gp.lng))
        }
        obj.put("geometry", geomArr)
        
        // Maneuvers
        val manArr = org.json.JSONArray()
        maneuvers.forEach { m ->
            val mObj = org.json.JSONObject()
            mObj.put("type", m.type.name)
            mObj.put("instruction", m.instruction)
            mObj.put("lat", m.location.lat)
            mObj.put("lng", m.location.lng)
            mObj.put("cumulative", m.cumulativeMeters)
            manArr.put(mObj)
        }
        obj.put("maneuvers", manArr)
        
        obj.put("totalMeters", totalMeters)
        obj.put("totalSeconds", totalSeconds)
        
        // Cumulative
        val cumArr = org.json.JSONArray()
        cumulative.forEach { cumArr.put(it) }
        obj.put("cumulative", cumArr)
        
        // Congestion
        val congArr = org.json.JSONArray()
        congestion.forEach { congArr.put(it) }
        obj.put("congestion", congArr)
        
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): Route {
            val obj = org.json.JSONObject(jsonStr)
            
            // Geometry
            val geomArr = obj.getJSONArray("geometry")
            val geometry = mutableListOf<GeoPoint>()
            for (i in 0 until geomArr.length()) {
                val pt = geomArr.getJSONArray(i)
                geometry.add(GeoPoint(pt.getDouble(0), pt.getDouble(1)))
            }
            
            // Maneuvers
            val manArr = obj.getJSONArray("maneuvers")
            val maneuvers = mutableListOf<Maneuver>()
            for (i in 0 until manArr.length()) {
                val mObj = manArr.getJSONObject(i)
                maneuvers.add(Maneuver(
                    type = ManeuverType.valueOf(mObj.getString("type")),
                    instruction = mObj.getString("instruction"),
                    location = GeoPoint(mObj.getDouble("lat"), mObj.getDouble("lng")),
                    cumulativeMeters = mObj.getDouble("cumulative")
                ))
            }
            
            val totalMeters = obj.getDouble("totalMeters")
            val totalSeconds = obj.getDouble("totalSeconds")
            
            // Cumulative
            val cumArr = obj.getJSONArray("cumulative")
            val cumulative = DoubleArray(cumArr.length())
            for (i in 0 until cumArr.length()) {
                cumulative[i] = cumArr.getDouble(i)
            }
            
            // Congestion (optional — recorded trails don't have congestion data)
            val congestion = mutableListOf<Int>()
            if (obj.has("congestion")) {
                val congArr = obj.getJSONArray("congestion")
                for (i in 0 until congArr.length()) {
                    congestion.add(congArr.getInt(i))
                }
            }
            
            return Route(
                geometry = geometry,
                maneuvers = maneuvers,
                totalMeters = totalMeters,
                totalSeconds = totalSeconds,
                cumulative = cumulative,
                congestion = congestion
            )
        }

        fun routesToJson(routes: List<Route>, selectedIndex: Int): String {
            val obj = org.json.JSONObject()
            obj.put("selectedRouteIndex", selectedIndex)
            val arr = org.json.JSONArray()
            routes.forEach { arr.put(org.json.JSONObject(it.toJson())) }
            obj.put("routes", arr)
            return obj.toString()
        }

        fun routesFromJson(jsonStr: String): Pair<List<Route>, Int> {
            val obj = org.json.JSONObject(jsonStr)
            val selected = obj.optInt("selectedRouteIndex", 0)
            val arr = obj.getJSONArray("routes")
            val list = mutableListOf<Route>()
            for (i in 0 until arr.length()) {
                list.add(fromJson(arr.getJSONObject(i).toString()))
            }
            return list to selected
        }
    }
}
