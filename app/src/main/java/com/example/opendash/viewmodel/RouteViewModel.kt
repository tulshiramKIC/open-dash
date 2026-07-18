package com.example.opendash.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.SharedLocation
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.Route
import com.example.opendash.dash.nav.Router
import com.example.opendash.dash.nav.Maneuver
import com.example.opendash.dash.nav.ManeuverType
import android.net.Uri
import com.example.opendash.util.LocationParser
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RouteState(
    val destination: SharedLocation? = null,
    val stops: List<SharedLocation> = emptyList(),
    val isResolving: Boolean = false,
    val pendingNavigate: Boolean = false,
    // Real routing results
    val route: Route? = null,           // the selected route (== routes[selectedRouteIndex])
    val routes: List<Route> = emptyList(), // primary + alternatives; [0] is Mapbox's primary
    val selectedRouteIndex: Int = 0,
    val routing: Boolean = false,
    val distanceText: String? = null,   // "218 km"
    val durationText: String? = null,   // "4h 50m"
    val etaText: String? = null,        // "13:32"
    val travelMode: Router.TravelMode = Router.TravelMode.BIKE,
    // In-app destination search
    val searchQuery: String = "",
    val searchResults: List<com.example.opendash.data.Place> = emptyList(),
    val searching: Boolean = false,
    val isRecordingRoute: Boolean = false,
    val recordedPoints: List<GeoPoint> = emptyList(),
    val isPreparingRouteRecording: Boolean = false,
    val recordingTrailName: String = "",
    val navigating: Boolean = false,
    val isCustomTrail: Boolean = false,
    val trailStart: GeoPoint? = null,
)

class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "RouteViewModel"
    private val _state = MutableStateFlow(RouteState())
    val state = _state.asStateFlow()

    /** One-shot message after a GPX export (file path or error). Cleared after reading. */
    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage = _exportMessage.asStateFlow()
    fun clearExportMessage() { _exportMessage.value = null }

    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val repo = com.example.opendash.data.SyncRepository.get(app)

    private var recordingStartMillis: Long = 0L

    private val gpsListener = android.location.LocationListener { loc ->
        if (loc.hasAccuracy() && loc.accuracy > 10f) return@LocationListener
        addRecordedPoint(loc.latitude, loc.longitude)
    }

    private val _saved = MutableStateFlow<List<com.example.opendash.data.SavedLocation>>(emptyList())
    /** Saved destinations the rider can tap to load + navigate again. */
    val saved = _saved.asStateFlow()

    init {
        reloadSaved()
        // Reflect saves made locally OR synced in from another device.
        viewModelScope.launch { repo.revision.collect { reloadSaved() } }
    }

    private fun reloadSaved() = viewModelScope.launch {
        _saved.value = withContext(Dispatchers.IO) { repo.savedLocations() }
    }

    /** Save the current resolved destination so it can be reused. No-op without coords. */
    fun saveCurrentDestination(name: String, note: String = "") {
        val d = _state.value.destination ?: return
        val lat = d.lat ?: return
        val lng = d.lng ?: return
        val routes = _state.value.routes
        val selectedIdx = _state.value.selectedRouteIndex
        viewModelScope.launch {
            val sid = withContext(Dispatchers.IO) {
                repo.addSaved(name.ifBlank { d.name }, lat, lng, note)
            }
            if (routes.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        val file = java.io.File(getApplication<Application>().filesDir, "route_${sid}.json")
                        file.writeText(com.example.opendash.dash.nav.Route.routesToJson(routes, selectedIdx))
                        DebugLog.i(TAG) { "Saved routes list cache for $sid" }
                    } catch (e: Exception) {
                        DebugLog.w(TAG) { "Failed to save route cache: ${e.message}" }
                    }
                }
            }
        }
    }

    fun renameSaved(loc: com.example.opendash.data.SavedLocation, name: String, note: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.renameSaved(loc, name, note) } }

    fun deleteSaved(loc: com.example.opendash.data.SavedLocation) =
        viewModelScope.launch { 
            withContext(Dispatchers.IO) { 
                repo.deleteSaved(loc)
                try {
                    val file = java.io.File(getApplication<Application>().filesDir, "route_${loc.sid}.json")
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    // Ignore cache delete error
                }
            } 
        }

    /** Load a saved destination into the route preview (compute route; stay on the page). */
    fun selectSaved(loc: com.example.opendash.data.SavedLocation) {
        _state.value = RouteState(
            destination = SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng),
            isResolving = false,
            pendingNavigate = false,
        )
        // Try to load cached route from disk first (useful when offline)
        val cacheFile = java.io.File(getApplication<Application>().filesDir, "route_${loc.sid}.json")
        if (cacheFile.exists()) {
            try {
                val json = cacheFile.readText()
                val (routes, selectedIdx) = com.example.opendash.dash.nav.Route.routesFromJson(json)
                val route = routes.getOrNull(selectedIdx) ?: routes.firstOrNull()
                if (route != null) {
                    _state.value = _state.value.copy(
                        route = route,
                        routes = routes,
                        selectedRouteIndex = selectedIdx,
                        isCustomTrail = true,
                        trailStart = route.geometry.firstOrNull(),
                        distanceText = fmtKm(route.totalMeters),
                        durationText = fmtDuration(route.totalSeconds),
                        etaText = fmtEta(route.totalSeconds),
                    )
                    
                    // Asynchronously calculate a route from current location to the start of the trail
                    calculateRouteToTrailStart(route)
                    DebugLog.i(TAG) { "Loaded route cache with ${routes.size} routes for ${loc.name}" }
                    return
                }
            } catch (e: Exception) {
                DebugLog.w(TAG) { "Failed to load route cache: ${e.message}" }
            }
        }
        _state.value = _state.value.copy(isCustomTrail = false, trailStart = null)
        computeRoute()
    }

    @SuppressLint("MissingPermission")
    private fun calculateRouteToTrailStart(trailRoute: Route) {
        val origin = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return

        val startPt = trailRoute.geometry.firstOrNull() ?: return
        val originGeo = GeoPoint(origin.latitude, origin.longitude)
        
        // If already very close to the start (less than 100 meters), don't calculate route to start
        if (GeoPoint.distMeters(originGeo, startPt) < 100.0) return

        viewModelScope.launch {
            try {
                val toStartRoutes = Router.routes(
                    originGeo,
                    startPt,
                    stops = emptyList(),
                    alternatives = false
                )
                val toStart = toStartRoutes.firstOrNull()
                if (toStart != null) {
                    // Combine routes!
                    val combinedGeom = toStart.geometry.dropLast(1) + trailRoute.geometry
                    val combinedManeuvers = toStart.maneuvers + listOf(
                        Maneuver(
                            type = ManeuverType.DEPART,
                            instruction = "Entering Custom Trail",
                            location = startPt,
                            cumulativeMeters = toStart.totalMeters
                        )
                    ) + trailRoute.maneuvers.map {
                        it.copy(cumulativeMeters = it.cumulativeMeters + toStart.totalMeters)
                    }

                    val combinedMeters = toStart.totalMeters + trailRoute.totalMeters
                    val combinedSeconds = toStart.totalSeconds + trailRoute.totalSeconds

                    val cumulative = DoubleArray(combinedGeom.size)
                    var total = 0.0
                    cumulative[0] = 0.0
                    for (i in 1 until combinedGeom.size) {
                        val p1 = combinedGeom[i - 1]
                        val p2 = combinedGeom[i]
                        val res = FloatArray(1)
                        android.location.Location.distanceBetween(p1.lat, p1.lng, p2.lat, p2.lng, res)
                        total += res[0]
                        cumulative[i] = total
                    }

                    val combinedRoute = Route(
                        geometry = combinedGeom,
                        maneuvers = combinedManeuvers,
                        totalMeters = combinedMeters,
                        totalSeconds = combinedSeconds,
                        cumulative = cumulative
                    )

                    _state.value = _state.value.copy(
                        route = combinedRoute,
                        routes = listOf(combinedRoute),
                        isCustomTrail = true,
                        trailStart = startPt,
                        distanceText = fmtKm(combinedRoute.totalMeters),
                        durationText = fmtDuration(combinedRoute.totalSeconds),
                        etaText = fmtEta(combinedRoute.totalSeconds),
                    )
                }
            } catch (e: Exception) {
                DebugLog.w(TAG) { "Failed to calculate route to trail start: ${e.message}" }
            }
        }
    }

    fun handleSharedText(text: String) {
        val loc = LocationParser.parse(text)
        _state.value = RouteState(
            destination = loc,
            isResolving = loc.needsExpansion,
            pendingNavigate = true,
        )
        if (loc.lat != null && loc.lng != null) {
            computeRoute()
        } else if (loc.url != null) {
            viewModelScope.launch {
                val (urlCoords, resolvedName) = LocationParser.resolve(loc.url)
                val name = when {
                    loc.name.isNotBlank() && loc.name != "Loading…" -> loc.name
                    !resolvedName.isNullOrBlank() -> resolvedName
                    urlCoords != null -> "Dropped pin"
                    else -> "Shared location"
                }
                // Named places (e.g. "Third Wave Coffee, … Hyderabad") have no coords in
                // the URL — geocode the resolved address to get lat/lng.
                val coords = urlCoords ?: resolvedName?.let { geocode(it) }
                val resolved = loc.copy(
                    name = name, lat = coords?.first, lng = coords?.second, needsExpansion = false,
                )
                _state.value = _state.value.copy(destination = resolved, isResolving = false)
                if (coords != null) computeRoute()
                else DebugLog.w(TAG) { "No coords for '$name' (url+geocode both empty)" }
            }
        }
    }

    /** Geocode an address/place name to coordinates via the device's geocoder backend. */
    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) {
                DebugLog.w(TAG) { "No geocoder backend present" }
                return@withContext null
            }
            val results = Geocoder(getApplication(), Locale.getDefault()).getFromLocationName(query, 1)
            val a = results?.firstOrNull() ?: run {
                DebugLog.w(TAG) { "Geocoder: no result for '$query'" }
                return@withContext null
            }
            DebugLog.i(TAG) { "Geocoded '$query' → ${a.latitude},${a.longitude}" }
            a.latitude to a.longitude
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Geocoder failed: ${e.message}" }
            null
        }
    }

    /** Switch Car ⇄ Bike (bike = no motorways) and recompute the current route with it. */
    @SuppressLint("MissingPermission")
    fun selectTravelMode(mode: Router.TravelMode) {
        if (_state.value.travelMode == mode) return
        _state.value = _state.value.copy(travelMode = mode)
        Router.currentMode = mode   // dash-side reroutes pick this up too
        val d = _state.value.destination
        if (d?.lat != null && d.lng != null) computeRoute()
    }

    @SuppressLint("MissingPermission")
    private fun computeRoute() {
        val dest = _state.value.destination ?: return
        val destLat = dest.lat ?: return
        val destLng = dest.lng ?: return
        val origin = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return

        _state.value = _state.value.copy(routing = true)
        val stopsList = _state.value.stops.mapNotNull {
            if (it.lat != null && it.lng != null) GeoPoint(it.lat, it.lng) else null
        }
        viewModelScope.launch {
            val list = Router.routes(
                GeoPoint(origin.latitude, origin.longitude),
                GeoPoint(destLat, destLng),
                stops = stopsList,
                alternatives = stopsList.isEmpty(), // alternatives only when there are no intermediate stops
            )
            val primary = list.firstOrNull()
            if (primary != null) {
                _state.value = _state.value.copy(
                    route = primary,
                    routes = list,
                    selectedRouteIndex = 0,
                    routing = false,
                    distanceText = fmtKm(primary.totalMeters),
                    durationText = fmtDuration(primary.totalSeconds),
                    etaText = fmtEta(primary.totalSeconds),
                )
            } else {
                // Offline fallback: calculate straight-line distance
                val distMeters = GeoPoint.distMeters(
                    GeoPoint(origin.latitude, origin.longitude),
                    GeoPoint(destLat, destLng)
                )
                _state.value = _state.value.copy(
                    route = null,
                    routes = emptyList(),
                    routing = false,
                    distanceText = "~" + fmtKm(distMeters) + " (direct)",
                    durationText = "Offline mode",
                    etaText = null
                )
            }
        }
    }

    /** Pick one of the fetched alternatives as the active route (phone-side selection). */
    fun selectRoute(index: Int) {
        val sel = _state.value.routes.getOrNull(index) ?: return
        _state.value = _state.value.copy(
            selectedRouteIndex = index,
            route = sel,
            distanceText = fmtKm(sel.totalMeters),
            durationText = fmtDuration(sel.totalSeconds),
            etaText = fmtEta(sel.totalSeconds),
        )
    }

    // ── In-app destination search (Google Places → Mapbox → Android Geocoder) ──
    private var searchJob: kotlinx.coroutines.Job? = null

    // Live rider position, pushed from the Navigate screen's GPS tracker. Search results
    // are biased around this; the last-known-location cache alone is often empty/stale,
    // which made "kfc" rank a far-away city above the one 5 km away.
    private var liveOrigin: Pair<Double, Double>? = null
    fun updateOrigin(lat: Double, lng: Double) { liveOrigin = lat to lng }
    // One Search Box session spans the suggests + the retrieve of the chosen result; a new
    // session starts after each pick (Mapbox bills per completed session).
    private var searchSession = java.util.UUID.randomUUID().toString()

    fun onSearchQueryChange(q: String) {
        _state.value = _state.value.copy(searchQuery = q)
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)   // debounce keystrokes
            _state.value = _state.value.copy(searching = true)
            val origin = liveOrigin ?: lastKnownOrigin()
            var results = com.example.opendash.data.PlaceSearch.suggest(q, origin?.first, origin?.second, searchSession, getApplication())
            // Fallback: no Mapbox token / offline → single best match from the device geocoder.
            if (results.isEmpty()) {
                geocode(q)?.let { (lat, lng) ->
                    results = listOf(com.example.opendash.data.Place(name = q, address = "", lat = lat, lng = lng))
                }
            }
            // Ignore stale responses if the query moved on.
            if (_state.value.searchQuery == q) {
                _state.value = _state.value.copy(searchResults = results, searching = false)
            }
        }
    }

    /** Pick a search result as the destination or stop, and compute its route. */
    fun chooseSearchResult(place: com.example.opendash.data.Place, stopIndex: Int = -1) {
        searchJob?.cancel()
        viewModelScope.launch {
            // Suggestions have no coordinates — resolve via their provider; geocoder hits already do.
            val coords = com.example.opendash.data.PlaceSearch.resolve(place, searchSession)
            searchSession = java.util.UUID.randomUUID().toString()  // start a fresh session
            if (coords == null) {
                _state.value = _state.value.copy(searching = false)
                DebugLog.w(TAG) { "retrieve returned no coords for '${place.name}'" }
                return@launch
            }
            val (lat, lng) = coords
            val targetLoc = SharedLocation(name = place.name, lat = lat, lng = lng)
            if (stopIndex >= 0) {
                val currentStops = _state.value.stops.toMutableList()
                if (stopIndex in currentStops.indices) {
                    currentStops[stopIndex] = targetLoc
                } else {
                    currentStops.add(targetLoc)
                }
                _state.value = _state.value.copy(
                    stops = currentStops,
                    searchQuery = "",
                    searchResults = emptyList(),
                    searching = false,
                )
            } else {
                _state.value = _state.value.copy(
                    destination = targetLoc,
                    isResolving = false,
                    searchQuery = "",
                    searchResults = emptyList(),
                    searching = false,
                )
            }
            computeRoute()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownOrigin(): Pair<Double, Double>? = runCatching {
        (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER))
            ?.let { it.latitude to it.longitude }
    }.getOrNull()

    fun onNavigated() { _state.value = _state.value.copy(pendingNavigate = false) }
    fun removeStop(index: Int) {
        val currentStops = _state.value.stops.toMutableList()
        if (index in currentStops.indices) {
            currentStops.removeAt(index)
            _state.value = _state.value.copy(stops = currentStops)
            computeRoute()
        }
    }

    fun addStop(loc: SharedLocation) {
        val currentStops = _state.value.stops.toMutableList()
        currentStops.add(loc)
        _state.value = _state.value.copy(stops = currentStops)
        computeRoute()
    }

    fun addBlankStop() {
        val currentStops = _state.value.stops.toMutableList()
        currentStops.add(SharedLocation(name = ""))
        _state.value = _state.value.copy(stops = currentStops)
    }

    fun moveWaypoint(fromIndex: Int, toIndex: Int) {
        val currentStops = _state.value.stops.toMutableList()
        val dest = _state.value.destination ?: return
        val list = currentStops + listOf(dest)
        if (fromIndex in list.indices && toIndex in list.indices) {
            val newList = list.toMutableList()
            val temp = newList[fromIndex]
            newList[fromIndex] = newList[toIndex]
            newList[toIndex] = temp
            
            // Last item is the new destination, others are stops
            val newDest = newList.last()
            val newStops = newList.dropLast(1)
            _state.value = _state.value.copy(
                destination = newDest,
                stops = newStops
            )
            computeRoute()
        }
    }

    fun startNavigation() {
        _state.value = _state.value.copy(navigating = true)
    }

    fun clear() { _state.value = RouteState() }

    fun prepareRecordingRoute() {
        _state.value = _state.value.copy(isPreparingRouteRecording = true)
    }

    @SuppressLint("MissingPermission")
    fun startRecordingRoute(name: String) {
        _state.value = _state.value.copy(
            isPreparingRouteRecording = false,
            isRecordingRoute = true,
            recordedPoints = emptyList(),
            recordingTrailName = name
        )
        recordingStartMillis = System.currentTimeMillis()
        com.example.opendash.dash.DashKeepAliveService.start(getApplication())
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,   // every 500ms for high-resolution trail
                0f,
                gpsListener,
                android.os.Looper.getMainLooper()
            )
        }
    }

    fun addRecordedPoint(lat: Double, lng: Double) {
        if (!_state.value.isRecordingRoute) return
        val currentPoints = _state.value.recordedPoints
        val newPoint = GeoPoint(lat, lng)
        if (currentPoints.isNotEmpty()) {
            val last = currentPoints.last()
            val results = FloatArray(1)
            android.location.Location.distanceBetween(last.lat, last.lng, lat, lng, results)
            if (results[0] < 0.5) return // Skip if moved less than 0.5 metres (GPS noise when stopped)
        }
        _state.value = _state.value.copy(recordedPoints = currentPoints + newPoint)
    }

    fun stopRecordingRoute() {
        _state.value = _state.value.copy(isRecordingRoute = false)
        runCatching { lm.removeUpdates(gpsListener) }
        com.example.opendash.dash.DashKeepAliveService.stop(getApplication())
    }

    fun saveRecordedRoute(name: String) {
        val points = _state.value.recordedPoints
        if (points.size < 2) return
        _state.value = _state.value.copy(isResolving = true)
        viewModelScope.launch {
            val destinationName = name.ifBlank { "Recorded Trail" }
            val first = points.first()
            val lastPt = points.last()
            
            val sid = withContext(Dispatchers.IO) {
                repo.addSaved(destinationName, lastPt.lat, lastPt.lng, "Recorded custom trail route")
            }
            
            // Build the Route object and write to route_${sid}.json
            withContext(Dispatchers.IO) {
                try {
                    val cumulative = DoubleArray(points.size)
                    var totalMeters = 0.0
                    cumulative[0] = 0.0
                    for (i in 1 until points.size) {
                        val p1 = points[i - 1]
                        val p2 = points[i]
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(p1.lat, p1.lng, p2.lat, p2.lng, results)
                        totalMeters += results[0]
                        cumulative[i] = totalMeters
                    }
                    val elapsedSeconds = if (recordingStartMillis > 0L) {
                        (System.currentTimeMillis() - recordingStartMillis) / 1000.0
                    } else {
                        0.0
                    }
                    val totalSeconds = if (elapsedSeconds > 5.0) elapsedSeconds else (totalMeters / 13.89)

                    val maneuvers = listOf(
                        Maneuver(
                            type = ManeuverType.DEPART,
                            instruction = "Start custom trail: $destinationName",
                            location = first,
                            cumulativeMeters = 0.0
                        ),
                        Maneuver(
                            type = ManeuverType.ARRIVE,
                            instruction = "Arrive at destination",
                            location = lastPt,
                            cumulativeMeters = totalMeters
                        )
                    )

                    val route = Route(
                        geometry = points,
                        maneuvers = maneuvers,
                        totalMeters = totalMeters,
                        totalSeconds = totalSeconds,
                        cumulative = cumulative
                    )
                    
                    val file = java.io.File(getApplication<Application>().filesDir, "route_${sid}.json")
                    file.writeText(com.example.opendash.dash.nav.Route.routesToJson(listOf(route), 0))
                    DebugLog.i(TAG) { "Successfully saved custom recorded route cache for $sid" }
                } catch (e: Exception) {
                    DebugLog.w(TAG) { "Failed to save recorded route cache: ${e.message}" }
                }
            }
            
            // Done saving, reset only recording state and preserve active navigation
            _state.value = _state.value.copy(
                isRecordingRoute = false,
                recordedPoints = emptyList(),
                recordingTrailName = "",
                isResolving = false
            )
        }
    }

    fun importGpxFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val pts = mutableListOf<GeoPoint>()
                        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                        val parser = factory.newPullParser()
                        parser.setInput(stream, "UTF-8")
                        var eventType = parser.eventType
                        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name.lowercase() == "trkpt") {
                                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                val lon = parser.getAttributeValue(null, "lon") ?: parser.getAttributeValue(null, "lng")
                                val lonDouble = lon?.toDoubleOrNull()
                                if (lat != null && lonDouble != null) {
                                    pts.add(GeoPoint(lat, lonDouble))
                                }
                            }
                            eventType = parser.next()
                        }
                        pts
                    } ?: emptyList()
                }

                if (points.isEmpty()) {
                    throw Exception("No trackpoints found in GPX file")
                }

                val routeName = getFileName(context, uri) ?: "Imported GPX"
                
                // Duplicate check by name (case-insensitive)
                val isDuplicate = withContext(Dispatchers.IO) {
                    val existingLocations = repo.savedLocations()
                    existingLocations.any { it.name.trim().equals(routeName.trim(), ignoreCase = true) }
                }

                if (isDuplicate) {
                    _exportMessage.value = "Trail \"$routeName\" is already imported"
                    return@launch
                }

                val lastPt = points.last()
                val sid = withContext(Dispatchers.IO) {
                    repo.addSaved(routeName, lastPt.lat, lastPt.lng, "Imported custom trail route")
                }

                withContext(Dispatchers.IO) {
                    val cumulative = DoubleArray(points.size)
                    var totalMeters = 0.0
                    cumulative[0] = 0.0
                    for (i in 1 until points.size) {
                        val p1 = points[i - 1]
                        val p2 = points[i]
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(p1.lat, p1.lng, p2.lat, p2.lng, results)
                        totalMeters += results[0]
                        cumulative[i] = totalMeters
                    }
                    val averageSpeedMps = 13.89 // ~50 km/h
                    val totalSeconds = totalMeters / averageSpeedMps

                    val maneuvers = listOf(
                        Maneuver(
                            type = ManeuverType.DEPART,
                            instruction = "Start GPX Route: $routeName",
                            location = points.first(),
                            cumulativeMeters = 0.0
                        ),
                        Maneuver(
                            type = ManeuverType.ARRIVE,
                            instruction = "Arrive at destination",
                            location = points.last(),
                            cumulativeMeters = totalMeters
                        )
                    )

                    val route = Route(
                        geometry = points,
                        maneuvers = maneuvers,
                        totalMeters = totalMeters,
                        totalSeconds = totalSeconds,
                        cumulative = cumulative
                    )
                    
                    val file = java.io.File(getApplication<Application>().filesDir, "route_${sid}.json")
                    file.writeText(com.example.opendash.dash.nav.Route.routesToJson(listOf(route), 0))
                }

                _exportMessage.value = "Imported \"$routeName\" successfully"
            } catch (e: Exception) {
                DebugLog.e(TAG, { "GPX import failed" }, e)
                _exportMessage.value = "Import failed: ${e.message}"
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) result = it.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result?.substringBeforeLast(".gpx")
    }

    fun exportGpx(context: Context, route: Route, name: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cleanName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                    val fileName = "${cleanName}.gpx"
                    val gpxContent = buildString {
                        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                        append("<gpx version=\"1.1\" creator=\"OpenDash\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                        append("  <trk>\n")
                        append("    <name>").append(name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</name>\n")
                        append("    <trkseg>\n")
                        route.geometry.forEach { gp ->
                            append("      <trkpt lat=\"").append(gp.lat).append("\" lon=\"").append(gp.lng).append("\" />\n")
                        }
                        append("    </trkseg>\n")
                        append("  </trk>\n")
                        append("</gpx>\n")
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // API 29+: insert into MediaStore Downloads — no permission required.
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/OpenDash")
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw Exception("MediaStore insert failed")
                        resolver.openOutputStream(uri)?.use { it.write(gpxContent.toByteArray()) }
                        values.clear()
                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        "Saved to Downloads/OpenDash/$fileName"
                    } else {
                        // API < 29 fallback: save to app-external files Downloads dir.
                        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                            ?: context.filesDir
                        val subDir = java.io.File(dir, "OpenDash").also { it.mkdirs() }
                        val f = java.io.File(subDir, fileName)
                        f.writeText(gpxContent)
                        "Saved to ${f.absolutePath}"
                    }
                }
            }
            _exportMessage.value = result.getOrElse { e -> "Export failed: ${e.message}" }
        }
    }

    private fun fmtKm(m: Double) = "%.0f km".format(m / 1000.0)
    private fun fmtDuration(sec: Double): String {
        val total = (sec / 60.0).toInt()
        return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
    }
    private fun fmtEta(sec: Double): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))
}
