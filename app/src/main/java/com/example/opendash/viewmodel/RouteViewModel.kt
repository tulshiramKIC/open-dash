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
)

class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "RouteViewModel"
    private val _state = MutableStateFlow(RouteState())
    val state = _state.asStateFlow()

    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val repo = com.example.opendash.data.SyncRepository.get(app)

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
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addSaved(name.ifBlank { d.name }, lat, lng, note) } }
    }

    fun renameSaved(loc: com.example.opendash.data.SavedLocation, name: String, note: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.renameSaved(loc, name, note) } }

    fun deleteSaved(loc: com.example.opendash.data.SavedLocation) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteSaved(loc) } }

    /** Load a saved destination into the route preview (compute route; stay on the page). */
    fun selectSaved(loc: com.example.opendash.data.SavedLocation) {
        _state.value = RouteState(
            destination = SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng),
            isResolving = false,
            pendingNavigate = false,
        )
        computeRoute(loc.lat, loc.lng)
    }

    fun handleSharedText(text: String) {
        val loc = LocationParser.parse(text)
        _state.value = RouteState(
            destination = loc,
            isResolving = loc.needsExpansion,
            pendingNavigate = true,
        )
        if (loc.lat != null && loc.lng != null) {
            computeRoute(loc.lat, loc.lng)
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
                if (coords != null) computeRoute(coords.first, coords.second)
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
        if (d?.lat != null && d.lng != null) computeRoute(d.lat, d.lng)
    }

    @SuppressLint("MissingPermission")
    private fun computeRoute(destLat: Double, destLng: Double) {
        val origin = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return

        _state.value = _state.value.copy(routing = true)
        viewModelScope.launch {
            val list = Router.routes(
                GeoPoint(origin.latitude, origin.longitude),
                GeoPoint(destLat, destLng),
                alternatives = true,
            )
            val primary = list.firstOrNull()
            _state.value = if (primary != null) _state.value.copy(
                route = primary,
                routes = list,
                selectedRouteIndex = 0,
                routing = false,
                distanceText = fmtKm(primary.totalMeters),
                durationText = fmtDuration(primary.totalSeconds),
                etaText = fmtEta(primary.totalSeconds),
            ) else _state.value.copy(routing = false, routes = emptyList())
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
            var results = com.example.opendash.data.PlaceSearch.suggest(q, origin?.first, origin?.second, searchSession)
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

    /** Pick a search result as the destination and compute its route. */
    fun chooseSearchResult(place: com.example.opendash.data.Place) {
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
            _state.value = _state.value.copy(
                destination = SharedLocation(name = place.name, lat = lat, lng = lng),
                isResolving = false,
                searchQuery = "",
                searchResults = emptyList(),
                searching = false,
            )
            computeRoute(lat, lng)
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
    fun clear() { _state.value = RouteState() }

    private fun fmtKm(m: Double) = "%.0f km".format(m / 1000.0)
    private fun fmtDuration(sec: Double): String {
        val total = (sec / 60.0).toInt()
        return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
    }
    private fun fmtEta(sec: Double): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))
}
