package com.example.opendash.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.opendash.dash.nav.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.State
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Device compass azimuth (degrees from north), from the rotation-vector sensor.
 *  Quantized to 5° steps so consumers don't re-render on every sensor tick. Used to spin
 *  the rider marker's arrow while standing still, Google-blue-dot style — never the camera. */
@Composable
fun rememberDeviceAzimuth(): State<Float> {
    val context = LocalContext.current
    val azimuth = remember { mutableStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rot = FloatArray(9)
            private val orient = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                val deg = (Math.toDegrees(orient[0].toDouble()).toFloat() + 360f) % 360f
                azimuth.value = (deg / 5f).toInt() * 5f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return azimuth
}

// Free, keyless, redistributable vector basemap (look-first, per the distribution decision).
// Shared with OfflineMaps so downloaded tile regions match what the live map requests.
private const val STYLE_URL = com.example.opendash.data.OfflineMaps.MAP_STYLE_URL

// Night style: OpenFreeMap "dark" — same planet vector source + Noto Sans glyph server as
// liberty, so offline-downloaded tile regions keep working and symbol text keeps rendering.
private const val NIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** True when the map should render in night colors, per the Settings mode + clock.
 *  AUTO re-evaluates every minute so the 7 pm / 6 am flips happen while the screen is up. */
@Composable
fun rememberMapNight(): Boolean {
    val theme by com.example.opendash.data.NavSettings.mapTheme.collectAsState()
    var night by remember { mutableStateOf(com.example.opendash.data.NavSettings.nightActive()) }
    LaunchedEffect(theme) {
        while (true) {
            night = com.example.opendash.data.NavSettings.nightActive(theme)
            kotlinx.coroutines.delay(60_000)
        }
    }
    return night
}

// Satellite imagery: Esri World Imagery raster tiles — also keyless (attribution required).
// Satellite mode is a hybrid like Google's: the vector style loads as usual, and this
// imagery is inserted *below* its label layers — Esri's own label overlays run out of
// data around z13 outside the US and tile "Map data not available" placeholders.
private const val SATELLITE_SOURCE_ID = "esri-imagery"
private const val SATELLITE_LAYER_ID = "esri-imagery"
private const val SATELLITE_TILE_URL =
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
private const val SATELLITE_ATTRIBUTION = "Esri, Maxar, Earthstar Geographics"
private const val FOLLOW_ZOOM = 15.5
private const val NAV_ZOOM = 17.5
private const val NAV_TILT = 0.0
private const val PREVIEW_TOP_PADDING = 320
private const val PREVIEW_BOTTOM_PADDING = 1500
private const val RIDER_ICON = "rider-bike"
private const val BIKE_BEAM_ICON = "rider-bike-beam"
private const val CHEVRON_ICON = "rider-chevron"
private const val DOT_ICON = "rider-dot"
private const val DOT_BEAM_ICON = "rider-dot-beam"
private const val DEST_ICON = "dest-pin"
private const val STOP_ICON = "stop-pin"
private const val TRAIL_START_ICON = "trail-start-pin"

// Google-style route colors, sampled from the user's Google Maps screenshots: selected =
// vivid indigo fill over a darker casing; alternates = light periwinkle over medium
// periwinkle (never grey — matches Google Maps).
private const val ROUTE_FILL = "#4008F8"
private const val ROUTE_CASING = "#0020D0"
private const val ALT_FILL = "#B8C8F8"
private const val ALT_CASING = "#8088F8"
private const val ROUTE_FILL_W = 4.2f
private const val ROUTE_CASING_W = 6.5f
private const val ALT_FILL_W = 3.5f
private const val ALT_CASING_W = 5.5f

// Group Ride peers: a small palette of colored rider dots (color picked by peer-id hash,
// stable for the whole ride) + a grey one for stale riders.
private const val PEER_ICON_PREFIX = "peer-"
private val PEER_COLORS = intArrayOf(
    0xFFEA4335.toInt(), // red
    0xFF34A853.toInt(), // green
    0xFFA142F4.toInt(), // purple
    0xFFF9AB00.toInt(), // amber
    0xFF24C1E0.toInt(), // cyan
)

/**
 * In-app phone map (MapLibre + OpenFreeMap). Keyless and redistributable — no Google
 * Maps SDK / API key. The physical dash still uses the off-screen power-efficient renderer.
 *
 * Modes: [fitRoute] frames the whole route; [navMode] tilts/zooms/rotates to heading with a
 * rider chevron; default follows the rider north-up.
 */
@Composable
fun OpenDashMap(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    routePoints: List<GeoPoint>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
    fitRoute: Boolean = false,
    navMode: Boolean = false,
    riderBearing: Float = 0f,
    recenterKey: Int = 0,
    /** Alternative routes drawn faded/grey under the selected [routePoints]. */
    alternateRoutes: List<List<GeoPoint>> = emptyList(),
    /** Per-segment traffic level for [routePoints] (0/1/2) — colors the route line. */
    routeCongestion: List<Int> = emptyList(),
    /**
     * All candidate routes for on-map selection (Google-style). When non-empty, every route
     * is drawn with a tappable duration bubble; the [selectedRouteIndex] one is highlighted
     * and tapping another calls [onSelectRoute]. Takes precedence over [alternateRoutes].
     */
    allRoutes: List<List<GeoPoint>> = emptyList(),
    /** Per-segment traffic levels for each of [allRoutes] — colors alternates too. */
    allRouteCongestions: List<List<Int>> = emptyList(),
    routeDurations: List<String> = emptyList(),
    selectedRouteIndex: Int = 0,
    onSelectRoute: (Int) -> Unit = {},
    /** Satellite imagery basemap instead of the vector street style. */
    satellite: Boolean = false,
    /** Night basemap (OpenFreeMap dark) instead of the day (liberty) style. */
    night: Boolean = false,
    compassBottomMarginDp: Int = 16,
    zoom: Double? = null,
    followMode: Boolean = true,
    /** Hide the ⓘ attribution button (dash preview — chrome-free like the real dash). */
    showAttribution: Boolean = true,
    /** Scale factor for the rider chevron/dot marker. */
    riderIconScale: Float = 1f,
    /** Place the rider low in the viewport so more map ahead is visible (nav follow mode). */
    cameraAheadOffset: Boolean = false,
    /** Extra bottom camera inset (dp) — lifts the rider marker above bottom overlays
     *  (e.g. the follow card) so it isn't hidden behind them. */
    cameraBottomPadDp: Float = 0f,
    /** Camera tilt (degrees) in nav follow mode — Google's 3D driving view. 0 = flat top-down. */
    navTiltDeg: Double = 0.0,
    /** On-map maneuver callout at the next turn (Google-style white label + arrow). */
    maneuverPoint: Pair<Double, Double>? = null,
    maneuverType: com.example.opendash.dash.nav.ManeuverType? = null,
    maneuverRoad: String? = null,
    /** Overrides the MARKER arrow direction only (camera keeps [riderBearing]). Pass the
     *  device compass azimuth while stationary for the Google-blue-dot effect. */
    markerBearing: Float? = null,
    /** Nav-mode rider marker style: true = top-down bike, false = classic chevron arrow. */
    bikeMarker: Boolean = false,
    recordedPoints: List<GeoPoint> = emptyList(),
    /** Group Ride: other riders shown as named colored dots (grey when stale). */
    peers: List<com.example.opendash.data.GroupRide.Peer> = emptyList(),
    stops: List<GeoPoint> = emptyList(),
    isCustomTrail: Boolean = false,
    trailStart: Pair<Double, Double>? = null,
    showTravelledGrey: Boolean = false,
    /** Off-screen group-ride peers get an edge-of-screen chip pointing toward them. */
    showPeerEdgeMarkers: Boolean = true,
    /** "Chase a biker": a secondary route to a peer, drawn in a distinct colour. */
    chaseRoute: List<GeoPoint> = emptyList(),
    /** Peer currently being chased — highlights their chip and shows the banner. */
    chasedPeerId: String? = null,
    /** Tap a peer chip/pin → start/stop chasing them. */
    onSelectPeer: (String) -> Unit = {},
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lineMgr by remember { mutableStateOf<LineManager?>(null) }
    // Dedicated line layers below / above the main route for the chase line (see style setup).
    var chaseBelowMgr by remember { mutableStateOf<LineManager?>(null) }
    var chaseAboveMgr by remember { mutableStateOf<LineManager?>(null) }
    // symbolMgr = MAP-aligned (rider marker rotates with the map to show heading).
    // labelMgr  = VIEWPORT-aligned (text callouts stay upright regardless of map rotation).
    var symbolMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var labelMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var destroyed by remember { mutableStateOf(false) }
    // Bumped on every camera move/idle so the off-screen-peer edge overlay recomputes.
    // Kept as a State (not read here) so only the overlay recomposes per camera frame,
    // never the whole map.
    val camTick = remember { mutableStateOf(0) }
    // Latest peer-select callback, read live by the one-time map-click listener.
    val onSelectPeerState = androidx.compose.runtime.rememberUpdatedState(onSelectPeer)

    // Latest routes + select callback, read live by the one-time map-click listener.
    val routesRef = remember { mutableStateOf<List<List<GeoPoint>>>(emptyList()) }
    routesRef.value = allRoutes
    // Latest peers, read live by the one-time map-click listener for pin hit-testing.
    val peersRef = remember { mutableStateOf(peers) }
    peersRef.value = peers
    val selectCb = androidx.compose.runtime.rememberUpdatedState(onSelectRoute)
    val selectedRef = androidx.compose.runtime.rememberUpdatedState(selectedRouteIndex)
    // Duration bubbles (symbol → route index), repositioned on every camera idle so the
    // label stays on the visible distinct stretch of its route — Google-style.
    val bubbleSyms = remember { mutableStateOf<List<Pair<Symbol, Int>>>(emptyList()) }

    // Remembered lists of active map elements to prevent laggy full redraws
    val routeLines = remember { mutableListOf<org.maplibre.android.plugins.annotation.Line>() }
    val chaseLines = remember { mutableListOf<org.maplibre.android.plugins.annotation.Line>() }
    val staticSymbols = remember { mutableListOf<org.maplibre.android.plugins.annotation.Symbol>() }
    val dynamicSymbols = remember { mutableListOf<org.maplibre.android.plugins.annotation.Symbol>() }

    // Update compass margins dynamically when the bottom offset changes.
    LaunchedEffect(compassBottomMarginDp, map) {
        val m = map ?: return@LaunchedEffect
        val d = context.resources.displayMetrics.density
        m.uiSettings.compassGravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        m.uiSettings.setCompassMargins(0, 0, (16 * d).toInt(), (compassBottomMarginDp * d).toInt())
    }

    // Bind the MapView to the composition lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE  -> mapView.onCreate(null)
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            destroyed = true
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            if (destroyed) return@getMapAsync
            m.uiSettings.apply {
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isCompassEnabled = false
                // Compass on the bottom right (END), vertically above floating buttons.
                compassGravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                val d = context.resources.displayMetrics.density
                setCompassMargins(0, 0, (16 * d).toInt(), (compassBottomMarginDp * d).toInt())
                isAttributionEnabled = showAttribution   // OSM/OpenFreeMap/Esri attribution
                isLogoEnabled = false
            }
            // Tap a route line or duration bubble on the map to select it (Google-style).
            m.addOnMapClickListener { point ->
                // Tapping a peer's bike pin follows them (same as tapping their edge chip).
                val tapPx = m.projection.toScreenLocation(point)
                val hitDensity = context.resources.displayMetrics.density
                val nearest = peersRef.value
                    .filter { it.lat != 0.0 || it.lng != 0.0 }
                    .minByOrNull {
                        val sp = m.projection.toScreenLocation(LatLng(it.lat, it.lng))
                        Math.hypot((sp.x - tapPx.x).toDouble(), (sp.y - tapPx.y).toDouble())
                    }
                if (nearest != null) {
                    val sp = m.projection.toScreenLocation(LatLng(nearest.lat, nearest.lng))
                    val d = Math.hypot((sp.x - tapPx.x).toDouble(), (sp.y - tapPx.y).toDouble())
                    if (d < 44.0 * hitDensity) {
                        onSelectPeerState.value(nearest.id)
                        return@addOnMapClickListener true
                    }
                }
                val routes = routesRef.value
                if (routes.size <= 1) return@addOnMapClickListener false
                val tapScreen = m.projection.toScreenLocation(point)
                var best = -1
                var bestD = Double.MAX_VALUE
                val density = context.resources.displayMetrics.density
                
                // 1. Check distance to visible bubbles first (in screen pixels)
                bubbleSyms.value.forEach { (sym, i) ->
                    val pos = sym.latLng
                    val symScreen = m.projection.toScreenLocation(pos)
                    val dx = tapScreen.x - symScreen.x
                    val dy = tapScreen.y - symScreen.y
                    val distPx = Math.hypot(dx.toDouble(), dy.toDouble())
                    val threshold = 36.0 * density // 36 dp tap target radius
                    if (distPx < threshold && distPx < bestD) {
                        best = i
                        bestD = distPx
                    }
                }
                
                // 2. If not on a bubble, check distance to route lines (in screen pixels)
                if (best == -1) {
                    routes.forEachIndexed { i, geo ->
                        val step = (geo.size / 150).coerceAtLeast(1)
                        for (idx in geo.indices step step) {
                            val pt = geo[idx]
                            val ptScreen = m.projection.toScreenLocation(LatLng(pt.lat, pt.lng))
                            val dx = tapScreen.x - ptScreen.x
                            val dy = tapScreen.y - ptScreen.y
                            val distPx = Math.hypot(dx.toDouble(), dy.toDouble())
                            if (distPx < bestD) {
                                bestD = distPx
                                best = i
                            }
                        }
                    }
                }
                
                val finalThreshold = 28.0 * density // tap must be within 28 dp of the route/bubble
                if (best >= 0 && bestD < finalThreshold) {
                    selectCb.value(best)
                    true
                } else {
                    false
                }
            }
            // Keep duration bubbles pinned to the visible distinct part of their route as
            // the user pans/zooms (Google moves its labels with the viewport too).
            m.addOnCameraIdleListener {
                val routes = routesRef.value
                if (routes.size <= 1 || bubbleSyms.value.isEmpty()) return@addOnCameraIdleListener
                val lbl = labelMgr ?: return@addOnCameraIdleListener
                val bounds = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull()
                    ?: return@addOnCameraIdleListener
                val sel = selectedRef.value
                // Reposition each bubble to its route's visible distinct stretch; if two would
                // land on top of each other, nudge the later one along its own route to a free
                // spot so BOTH stay visible (Google-style), instead of overlapping.
                val placed = mutableListOf<GeoPoint>()
                bubbleSyms.value.sortedByDescending { it.second == sel }.forEach { (sym, i) ->
                    val geo = routes.getOrNull(i) ?: return@forEach
                    if (geo.size < 2) return@forEach
                    val other = if (i == sel) routes.firstOrNull { it !== geo && it.size >= 2 }
                        else routes.getOrNull(sel)
                    var anchor = bubbleAnchor(geo, other, bounds) ?: bubbleAnchor(geo, other) ?: geo[geo.size / 2]
                    if (i != sel && placed.any { GeoPoint.distMeters(it, anchor) < 120.0 }) {
                        anchor = freeAnchorAlong(geo, placed, bounds) ?: anchor
                    }
                    placed += anchor
                    sym.latLng = LatLng(anchor.lat, anchor.lng)
                    runCatching { lbl.update(sym) }
                }
            }
            // Drive the off-screen-peer edge overlay: recompute as the camera pans/zooms.
            m.addOnCameraMoveListener { camTick.value++ }
            m.addOnCameraIdleListener { camTick.value++ }
            map = m
        }
    }

    // (Re)apply the basemap style — runs on first map-ready and on satellite/night toggles.
    // Annotation managers are bound to a style, so they're rebuilt on every swap.
    LaunchedEffect(map, satellite, night) {
        val m = map ?: return@LaunchedEffect
        styleReady = false
        // The annotation plugin re-adds each manager's annotations onto every newly
        // loaded style, so onDestroy() alone leaves the old peers/markers/route behind
        // and the fresh render stacks another copy on top — the "+1 every satellite
        // toggle" duplication. Empty the managers first, THEN destroy them, THEN drop the
        // now-stale Symbol/Line references so the render effects repopulate cleanly.
        runCatching { lineMgr?.deleteAll() }
        runCatching { chaseBelowMgr?.deleteAll() }
        runCatching { chaseAboveMgr?.deleteAll() }
        runCatching { symbolMgr?.deleteAll() }
        runCatching { labelMgr?.deleteAll() }
        runCatching { lineMgr?.onDestroy() }
        runCatching { chaseBelowMgr?.onDestroy() }
        runCatching { chaseAboveMgr?.onDestroy() }
        runCatching { symbolMgr?.onDestroy() }
        runCatching { labelMgr?.onDestroy() }
        lineMgr = null; symbolMgr = null; labelMgr = null
        chaseBelowMgr = null; chaseAboveMgr = null
        dynamicSymbols.clear(); staticSymbols.clear(); routeLines.clear(); chaseLines.clear()
        bubbleSyms.value = emptyList()
        m.setStyle(Style.Builder().fromUri(if (night) NIGHT_STYLE_URL else STYLE_URL)) { style ->
            if (destroyed) return@setStyle
            if (satellite) addSatelliteImagery(style)
            disable3dBuildings(style, night)
            style.addImage(RIDER_ICON, bikeMarkerBitmap(context))
            style.addImage(BIKE_BEAM_ICON, bikeBeamBitmap())
            style.addImage(CHEVRON_ICON, chevronBitmap())
            style.addImage(DOT_ICON, riderDotBitmap())
            style.addImage(DOT_BEAM_ICON, riderDotBeamBitmap())
            // Peer pins are added lazily per (color, initial) when peers render.
            style.addImage(DEST_ICON, destPinBitmap())
            style.addImage(STOP_ICON, stopPinBitmap())
            style.addImage(TRAIL_START_ICON, trailStartPinBitmap())
            // The plugin (v3.0.2) has no per-line sort key, so cross-line z-order is fixed
            // by manager creation order. Three line layers, bottom→top: chase-below, the
            // main route, chase-above. The chase route is drawn into whichever matches
            // "shorter route on top", giving a deterministic result regardless of which
            // effect last redrew.
            chaseBelowMgr = LineManager(mapView, m, style).apply {
                lineCap = org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
            }
            lineMgr = LineManager(mapView, m, style).apply {
                // Butt (flat) ends so each congestion run starts/ends in a clean straight
                // cut across the route instead of a rounded semicircle bulge. Round caps
                // made every traffic-run boundary balloon out; a flat cut meets the
                // neighbouring run edge-to-edge and reads like Google's traffic overlay.
                lineCap = org.maplibre.android.style.layers.Property.LINE_CAP_BUTT
            }
            chaseAboveMgr = LineManager(mapView, m, style).apply {
                lineCap = org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
            }
            symbolMgr = SymbolManager(mapView, m, style).apply {
                iconAllowOverlap = true; iconIgnorePlacement = true
                textAllowOverlap = true; textIgnorePlacement = true
                // North-relative icon rotation: marker bearings are absolute degrees, so
                // they stay correct whether the camera is north-up or heading-up.
                iconRotationAlignment = org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP
            }
            // Labels/callouts: viewport-aligned so their text stays upright and readable no
            // matter how the map is rotated (created AFTER symbolMgr so it draws on top).
            labelMgr = SymbolManager(mapView, m, style).apply {
                iconAllowOverlap = true; iconIgnorePlacement = true
                textAllowOverlap = true; textIgnorePlacement = true
                iconRotationAlignment = org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
            }
            styleReady = true
        }
    }

    // Redraw static route + markers only when route configurations or static elements change.
    val routeKey = if (showTravelledGrey) Pair(riderLat, riderLng) else null
    LaunchedEffect(
        styleReady, routePoints, routeCongestion, alternateRoutes, allRoutes,
        allRouteCongestions, routeDurations, selectedRouteIndex, dest, stops,
        recordedPoints, isCustomTrail, trailStart, showTravelledGrey, routeKey
    ) {
        if (destroyed) return@LaunchedEffect
        val style = map?.style ?: return@LaunchedEffect
        val lm = lineMgr ?: return@LaunchedEffect
        val lbl = labelMgr ?: return@LaunchedEffect
        
        runCatching { lm.delete(routeLines.toList()) }
        routeLines.clear()
        runCatching { lbl.delete(staticSymbols.toList()) }
        staticSymbols.clear()
        bubbleSyms.value = emptyList()

        if (isCustomTrail && trailStart != null) {
            if (routePoints.size >= 2) {
                val startGeo = GeoPoint(trailStart.first, trailStart.second)
                val trailStartIdx = closestPointIndex(routePoints, startGeo)
                val riderIdx = if (showTravelledGrey && riderLat != null && riderLng != null) {
                    val riderGeo = GeoPoint(riderLat, riderLng)
                    val idx = closestPointIndex(routePoints, riderGeo)
                    val dist = GeoPoint.distMeters(riderGeo, routePoints[idx])
                    if (dist <= 50.0) idx else -1
                } else -1

                if (riderIdx in routePoints.indices) {
                    if (riderIdx < trailStartIdx) {
                        // Travelled standard route: grey
                        if (riderIdx > 0) {
                            routeLines += lm.create(
                                LineOptions().withLatLngs(routePoints.subList(0, riderIdx + 1).map { LatLng(it.lat, it.lng) })
                                    .withLineColor("#9AA0A6").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                            )
                        }
                        // Custom trail: red
                        routeLines += lm.create(
                            LineOptions().withLatLngs(routePoints.subList(trailStartIdx, routePoints.size).map { LatLng(it.lat, it.lng) })
                                .withLineColor("#E5341F").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                        )
                        // Remaining standard route: blue
                        routeLines += lm.create(
                            LineOptions().withLatLngs(routePoints.subList(riderIdx, trailStartIdx + 1).map { LatLng(it.lat, it.lng) })
                                .withLineColor("#4285F4").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                        )
                    } else {
                        // Rider is already on the trail
                        // Travelled portion (standard route + travelled custom trail): grey
                        if (riderIdx > 0) {
                            routeLines += lm.create(
                                LineOptions().withLatLngs(routePoints.subList(0, riderIdx + 1).map { LatLng(it.lat, it.lng) })
                                    .withLineColor("#9AA0A6").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                            )
                        }
                        // Remaining custom trail: red
                        routeLines += lm.create(
                            LineOptions().withLatLngs(routePoints.subList(riderIdx, routePoints.size).map { LatLng(it.lat, it.lng) })
                                .withLineColor("#E5341F").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                        )
                    }
                } else {
                    // No rider location yet: draw custom trail in red first, then standard route in blue on top
                    routeLines += lm.create(
                        LineOptions().withLatLngs(routePoints.subList(trailStartIdx, routePoints.size).map { LatLng(it.lat, it.lng) })
                            .withLineColor("#E5341F").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                    )
                    if (trailStartIdx > 0) {
                        routeLines += lm.create(
                            LineOptions().withLatLngs(routePoints.subList(0, trailStartIdx + 1).map { LatLng(it.lat, it.lng) })
                                .withLineColor("#4285F4").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                        )
                    }
                }
            }
        } else if (allRoutes.isNotEmpty()) {
            // Multi-route selection mode: light-blue alternates under the deep-blue selected
            // route (Google-style, both with casing), plus duration bubbles.
            allRoutes.forEachIndexed { i, geo ->
                if (i != selectedRouteIndex && geo.size >= 2) {
                    routeLines += drawAlternateRoute(lm, geo, allRouteCongestions.getOrNull(i) ?: emptyList())
                }
            }
            allRoutes.getOrNull(selectedRouteIndex)?.let { sel ->
                // Active navigation: flatten the ridden part to grey — congestion only
                // matters for road still ahead. Ahead keeps its traffic coloring.
                val splitIdx = if (showTravelledGrey && riderLat != null && riderLng != null && sel.size >= 2) {
                    val riderGeo = GeoPoint(riderLat, riderLng)
                    val idx = closestPointIndex(sel, riderGeo)
                    if (idx > 0 && GeoPoint.distMeters(riderGeo, sel[idx]) <= 50.0) idx else 0
                } else 0
                if (splitIdx > 0) {
                    routeLines += lm.create(
                        LineOptions().withLatLngs(sel.subList(0, splitIdx + 1).map { LatLng(it.lat, it.lng) })
                            .withLineColor("#9AA0A6").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
                    )
                    val aheadCongestion =
                        if (routeCongestion.size == sel.size - 1) routeCongestion.subList(splitIdx, routeCongestion.size)
                        else emptyList()
                    routeLines += drawColoredRoute(lm, sel.subList(splitIdx, sel.size), aheadCongestion)
                } else {
                    routeLines += drawColoredRoute(lm, sel, routeCongestion)
                }
            }
            // Duration bubbles pinned to each route's DISTINCT stretch (Google-style) —
            // the plain midpoint usually lands on road shared with the selected route. Place
            // the selected route first, then skip any alternate whose bubble would land on top
            // of an already-placed one (near-identical routes → don't stack two labels).
            val createdBubbles = mutableListOf<Pair<Symbol, Int>>()
            val placedAnchors = mutableListOf<GeoPoint>()
            val order = (0 until allRoutes.size).sortedByDescending { it == selectedRouteIndex }
            for (i in order) {
                val geo = allRoutes[i]
                if (geo.size < 2) continue
                val durationText = routeDurations.getOrNull(i) ?: continue
                val isSelected = (i == selectedRouteIndex)
                val compareTo = if (isSelected)
                    allRoutes.firstOrNull { it !== geo && it.size >= 2 }
                else allRoutes.getOrNull(selectedRouteIndex)
                val viewBounds = runCatching { map?.projection?.visibleRegion?.latLngBounds }.getOrNull()
                var anchor = bubbleAnchor(geo, compareTo, viewBounds)
                    ?: bubbleAnchor(geo, compareTo)
                    ?: geo[geo.size / 2]
                // Alternates: if the bubble collides with one already placed, slide it along
                // its own route to a free spot so both stay visible (never overlap/hide).
                if (!isSelected && placedAnchors.any { GeoPoint.distMeters(it, anchor) < 120.0 }) {
                    anchor = freeAnchorAlong(geo, placedAnchors, viewBounds) ?: anchor
                }
                placedAnchors += anchor
                val bubbleBitmap = durationBubbleBitmap(context, durationText, isSelected)
                val iconId = "bubble_${i}_${isSelected}"
                style.addImage(iconId, bubbleBitmap)
                val sym = lbl.create(
                    SymbolOptions()
                        .withLatLng(LatLng(anchor.lat, anchor.lng))
                        .withIconImage(iconId)
                        .withIconSize(1.0f)
                        // Tail tip sits at the bitmap's bottom-left — anchor there
                        // so the callout points at the route.
                        .withIconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM_LEFT)
                        .withSymbolSortKey(if (isSelected) 10f else 1f)
                )
                staticSymbols += sym
                createdBubbles += sym to i
            }
            bubbleSyms.value = createdBubbles
        } else {
            // Single-route mode (dash view): alternates + selected + traffic coloring.
            alternateRoutes.forEach { alt ->
                if (alt.size >= 2) {
                    routeLines += drawAlternateRoute(lm, alt)
                }
            }
            if (routePoints.size >= 2) {
                // Only split grey if showTravelledGrey is true
                if (showTravelledGrey && riderLat != null && riderLng != null) {
                    val riderGeo = GeoPoint(riderLat, riderLng)
                    val splitIdx = closestPointIndex(routePoints, riderGeo)
                    val dist = GeoPoint.distMeters(riderGeo, routePoints[splitIdx])
                    if (splitIdx > 0 && dist <= 50.0) {
                        val travelled = routePoints.subList(0, splitIdx + 1)
                        routeLines += lm.create(LineOptions().withLatLngs(travelled.map { LatLng(it.lat, it.lng) }).withLineColor("#9AA0A6").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f))
                        val remaining = routePoints.subList(splitIdx, routePoints.size)
                        val aheadCongestion =
                            if (routeCongestion.size == routePoints.size - 1) routeCongestion.subList(splitIdx, routeCongestion.size)
                            else emptyList()
                        routeLines += drawColoredRoute(lm, remaining, aheadCongestion)
                    } else {
                        routeLines += drawColoredRoute(lm, routePoints, routeCongestion)
                    }
                } else {
                    routeLines += drawColoredRoute(lm, routePoints, routeCongestion)
                }
            }
        }
        if (recordedPoints.size >= 2) {
            routeLines += lm.create(
                LineOptions().withLatLngs(recordedPoints.map { LatLng(it.lat, it.lng) })
                    .withLineColor("#E5341F").withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(5.5f)
            )
            // Draw a dedicated start pin at the trail origin so the rider dot
            // icon doesn't appear "stuck" at the starting location.
            val startPt = recordedPoints.first()
            staticSymbols += lbl.create(
                SymbolOptions().withLatLng(LatLng(startPt.lat, startPt.lng))
                    .withIconImage(TRAIL_START_ICON).withIconSize(1.1f)
            )
        }
        if (isCustomTrail && trailStart != null) {
            staticSymbols += lbl.create(
                SymbolOptions().withLatLng(LatLng(trailStart.first, trailStart.second))
                    .withIconImage(TRAIL_START_ICON).withIconSize(1.1f)
            )
        }
        dest?.let { staticSymbols += lbl.create(SymbolOptions().withLatLng(LatLng(it.first, it.second)).withIconImage(DEST_ICON).withIconSize(1.1f)) }
        stops.forEach { pt ->
            staticSymbols += lbl.create(
                SymbolOptions().withLatLng(LatLng(pt.lat, pt.lng))
                    .withIconImage(STOP_ICON).withIconSize(1.1f)
            )
        }
    }

    // Redraw dynamic markers (rider dot, peers, turn-by-turn maneuvers) on every coordinate tick.
    LaunchedEffect(styleReady, riderLat, riderLng, riderBearing, markerBearing, bikeMarker, navMode, peers, maneuverPoint, maneuverRoad, maneuverType) {
        if (destroyed) return@LaunchedEffect
        val style = map?.style ?: return@LaunchedEffect
        val sm = symbolMgr ?: return@LaunchedEffect
        val lbl = labelMgr ?: return@LaunchedEffect

        runCatching { sm.delete(dynamicSymbols.toList()) }
        runCatching { lbl.delete(dynamicSymbols.toList()) }
        dynamicSymbols.clear()

        // 1. On-map maneuver callout at the next turn (Google-style): white arrow + road label.
        if (maneuverPoint != null && !maneuverRoad.isNullOrBlank()) {
            val iconId = "maneuver-${maneuverType?.name}-$maneuverRoad"
            if (style.getImage(iconId) == null) {
                style.addImage(iconId, maneuverLabelBitmap(context, maneuverRoad, maneuverType))
            }
            dynamicSymbols += lbl.create(
                SymbolOptions()
                    .withLatLng(LatLng(maneuverPoint.first, maneuverPoint.second))
                    .withIconImage(iconId)
                    .withIconSize(1.0f)
                    .withIconAnchor("bottom")
                    .withSymbolSortKey(8f),
            )
        }

        // 2. Rider marker: navigation chevron or standard location blue dot
        if (riderLat != null && riderLng != null) {
            if (navMode) {
                if (bikeMarker) {
                    val beamSym = sm.create(
                        SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                            .withIconImage(BIKE_BEAM_ICON)
                            .withIconRotate(markerBearing ?: riderBearing ?: 0f)
                            .withIconSize(riderIconScale)
                            .withIconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER)
                    )
                    val bikeSym = sm.create(
                        SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                            .withIconImage(RIDER_ICON)
                            .withIconRotate(riderBearing ?: 0f)
                            .withIconSize(riderIconScale)
                            .withIconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_TOP)
                    )
                    dynamicSymbols += beamSym
                    dynamicSymbols += bikeSym
                } else {
                    val sym = sm.create(
                        SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                            .withIconImage(CHEVRON_ICON)
                            .withIconRotate(markerBearing ?: riderBearing ?: 0f)
                            .withIconSize(riderIconScale)
                            .withIconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER)
                    )
                    dynamicSymbols += sym
                }
            } else {
                val sym = sm.create(
                    SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                        .withIconImage(if (markerBearing != null) DOT_BEAM_ICON else DOT_ICON)
                        .withIconRotate(markerBearing ?: 0f)
                        .withIconSize(riderIconScale)
                )
                dynamicSymbols += sym
            }
        }

        // 3. Group Ride peers
        peers.filter { it.lat != 0.0 && it.lng != 0.0 }.forEach { peer ->
            val colorIdx = Math.abs(peer.id.hashCode()) % PEER_COLORS.size
            val color = if (peer.isStale) 0xFF9AA0A6.toInt() else PEER_COLORS[colorIdx]
            val initial = peer.name.trim().take(1).uppercase().ifBlank { "?" }
            val face = if (peer.bike.isBlank()) initial else "b${peer.bike.hashCode()}"
            val iconId = "$PEER_ICON_PREFIX$colorIdx-$face-${peer.isStale}"
            if (style.getImage(iconId) == null) {
                style.addImage(iconId, peerPinBitmap(context, color, initial, peer.bike, peer.isStale))
            }
            val label = if (riderLat != null && riderLng != null) {
                val d = GeoPoint.distMeters(GeoPoint(riderLat, riderLng), GeoPoint(peer.lat, peer.lng))
                peer.name + "\n" + (if (d >= 1000) "%.1f km".format(d / 1000) else "${d.toInt()} m")
            } else peer.name
            dynamicSymbols += lbl.create(
                SymbolOptions().withLatLng(LatLng(peer.lat, peer.lng))
                    .withIconImage(iconId)
                    .withIconSize(1.0f)
                    .withIconAnchor("bottom")
                    .withSymbolSortKey(5f)
                    .withTextField(label)
                    .withTextFont(arrayOf("Noto Sans Bold"))
                    .withTextSize(12f)
                    .withTextColor(if (peer.isStale) "#9AA0A6" else "#FFFFFF")
                    .withTextHaloColor("#000000")
                    .withTextHaloWidth(1.6f)
                    .withTextAnchor("top")
                    .withTextOffset(arrayOf(0f, 0.4f))
            )
        }
    }

    // Camera control. In fitRoute (preview) mode, moves are one-shot per target — the
    // rider dot updates every GPS fix and continuously re-centering would fight panning.
    var camKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(styleReady, riderLat, riderLng, riderBearing, navMode, fitRoute, routePoints.size, dest, zoom, followMode, cameraBottomPadDp) {
        if (destroyed) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        when {
            fitRoute && routePoints.size >= 2 -> {
                val key = "route:${routePoints.size}:${routePoints.first().lat}:${routePoints.last().lat}:${riderLat != null && riderLng != null}"
                if (camKey != key) {
                    camKey = key
                    val b = LatLngBounds.Builder()
                    routePoints.forEach { b.include(LatLng(it.lat, it.lng)) }
                    if (riderLat != null && riderLng != null) {
                        b.include(LatLng(riderLat, riderLng))
                    }
                    runCatching {
                        m.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                b.build(),
                                32,
                                PREVIEW_TOP_PADDING,
                                32,
                                PREVIEW_BOTTOM_PADDING,
                            )
                        )
                    }
                }
            }
            fitRoute && dest != null && riderLat != null && riderLng != null -> {
                // Offline fit camera: show both current location and destination pin
                val key = "rider-dest:${riderLat}:${riderLng}:${dest.first}:${dest.second}"
                if (camKey != key) {
                    camKey = key
                    val b = LatLngBounds.Builder()
                        .include(LatLng(riderLat, riderLng))
                        .include(LatLng(dest.first, dest.second))
                    runCatching {
                        m.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                b.build(),
                                64,
                                PREVIEW_TOP_PADDING,
                                64,
                                PREVIEW_BOTTOM_PADDING,
                            )
                        )
                    }
                }
            }
            followMode && !fitRoute && riderLat != null && riderLng != null -> {
                // Follow modes (dash preview / navigation): keep tracking the rider. With
                // cameraAheadOffset the rider sits low in the view (top padding pushes the
                // camera target down) so the road ahead gets most of the screen.
                val target = LatLng(riderLat, riderLng)
                val z = zoom ?: if (navMode) NAV_ZOOM else FOLLOW_ZOOM
                val topPad = if (cameraAheadOffset) mapView.height * 0.55 else 0.0
                val botPad = (cameraBottomPadDp * context.resources.displayMetrics.density).toDouble()
                val pos = if (navMode)
                    CameraPosition.Builder().target(target).zoom(z).tilt(navTiltDeg).bearing(riderBearing.toDouble())
                        .padding(0.0, topPad, 0.0, botPad).build()
                else
                    CameraPosition.Builder().target(target).zoom(z).tilt(0.0).bearing(0.0)
                        .padding(0.0, topPad, 0.0, botPad).build()
                runCatching { m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600) }
            }
            riderLat != null && riderLng != null -> {
                // Preview with no route yet: open on the rider once, then leave the camera alone.
                if (camKey != "rider") {
                    camKey = "rider"
                    val z = zoom ?: FOLLOW_ZOOM
                    runCatching {
                        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(riderLat, riderLng), z))
                    }
                }
            }
            dest != null -> {
                val key = "dest:${dest.first}:${dest.second}"
                if (camKey != key) {
                    camKey = key
                    runCatching { m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(dest.first, dest.second), 13.0)) }
                }
            }
        }
    }

    LaunchedEffect(styleReady, recenterKey, zoom) {
        if (recenterKey == 0 || destroyed) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val lat = riderLat ?: return@LaunchedEffect
        val lng = riderLng ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val target = LatLng(lat, lng)
        val z = zoom ?: if (navMode) NAV_ZOOM else FOLLOW_ZOOM
        val topPad = if (cameraAheadOffset) mapView.height * 0.55 else 0.0
        val botPad = (cameraBottomPadDp * context.resources.displayMetrics.density).toDouble()
        val position = if (navMode) {
            CameraPosition.Builder()
                .target(target)
                .zoom(z)
                .tilt(navTiltDeg)
                .bearing(riderBearing.toDouble())
                .padding(0.0, topPad, 0.0, botPad)
                .build()
        } else {
            CameraPosition.Builder()
                .target(target)
                .zoom(z)
                .tilt(0.0)
                .bearing(0.0)
                .padding(0.0, topPad, 0.0, botPad)
                .build()
        }
        runCatching { m.animateCamera(CameraUpdateFactory.newCameraPosition(position), 500) }
    }

    // Draw the chase route (route to a moving biker) as its own amber line. Whichever
    // route is SHORTER renders on top: the chase goes into the above-layer when it's the
    // shorter path, else the below-layer, so the main route sits on top. It never touches
    // the main route's own line, so primary navigation is undisturbed either way.
    LaunchedEffect(styleReady, chaseRoute, routePoints) {
        if (destroyed) return@LaunchedEffect
        val above = chaseAboveMgr ?: return@LaunchedEffect
        val below = chaseBelowMgr ?: return@LaunchedEffect
        runCatching { above.delete(chaseLines.toList()) }
        runCatching { below.delete(chaseLines.toList()) }
        chaseLines.clear()
        if (chaseRoute.size >= 2) {
            val chaseLen = geomLengthM(chaseRoute)
            val mainLen = if (routePoints.size >= 2) geomLengthM(routePoints) else Double.MAX_VALUE
            val target = if (chaseLen <= mainLen) above else below
            chaseLines += target.create(
                LineOptions()
                    .withLatLngs(chaseRoute.map { LatLng(it.lat, it.lng) })
                    .withLineColor("#FF6D00")   // amber — distinct from the blue main route
                    .withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                    .withLineWidth(5.0f),
            )
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        if (showPeerEdgeMarkers && styleReady) {
            PeerEdgeOverlay(
                peers = peers,
                map = map,
                riderLat = riderLat,
                riderLng = riderLng,
                camTick = camTick,
                followedId = chasedPeerId,
                onTapPeer = { id -> onSelectPeerState.value(id) },
            )   // camTick is a State; only the overlay reads .value → localized recompose
        }
    }
}

/**
 * Off-screen group-ride peers, pinned to the screen edge pointing toward their real
 * position (Google-Maps-style / game off-screen markers). A peer inside the current
 * viewport is left to its normal MapLibre pin and gets no chip here; as you zoom out and
 * it enters the view, the chip disappears and the map pin takes over. Direction uses the
 * geographic bearing from the camera centre (minus map rotation), so it stays correct
 * when the map is rotated or tilted.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PeerEdgeOverlay(
    peers: List<com.example.opendash.data.GroupRide.Peer>,
    map: MapLibreMap?,
    riderLat: Double?,
    riderLng: Double?,
    camTick: State<Int>,
    followedId: String?,
    onTapPeer: (String) -> Unit,
) {
    val m = map ?: return
    val density = LocalDensity.current
    val chipPx = with(density) { 32.dp.toPx() }
    val edgeInset = with(density) { 6.dp.toPx() } + chipPx / 2f
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(Modifier.matchParentSize().onSizeChanged { sizePx = it }) {
        val w = sizePx.width.toFloat()
        val h = sizePx.height.toFloat()
        if (w < 1f || h < 1f) return@Box

        val markers = remember(camTick.value, peers, sizePx, riderLat, riderLng) {
            computeEdgeMarkers(m, peers, w, h, edgeInset, riderLat, riderLng)
        }
        markers.forEach { em ->
            val chipDp = with(density) { chipPx.toDp() }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (em.x - chipPx / 2f).roundToInt(),
                            (em.y - chipPx / 2f).roundToInt(),
                        )
                    }
                    .size(chipDp)
                    .clickable { onTapPeer(em.id) }
                    // Directional triangle on the outer side, pointing the way to the peer.
                    .drawBehind {
                        val r = size.minDimension / 2f
                        val dirX = sin(em.angleRad)
                        val dirY = -cos(em.angleRad)
                        val tip = Offset(center.x + dirX * r, center.y + dirY * r)
                        val baseC = Offset(center.x + dirX * (r * 0.55f), center.y + dirY * (r * 0.55f))
                        // Perpendicular for the triangle base.
                        val px = -dirY; val py = dirX
                        val half = r * 0.42f
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(baseC.x + px * half, baseC.y + py * half)
                            lineTo(baseC.x - px * half, baseC.y - py * half)
                            close()
                        }
                        drawPath(p, Color(em.color))
                    },
                contentAlignment = Alignment.Center,
            ) {
                // White ring around every chip; the followed one gets a fatter ring.
                val ringWidth = if (em.id == followedId) 3.dp else 2.dp
                Box(
                    Modifier
                        .size(chipDp * 0.78f)
                        .clip(CircleShape)
                        .background(Color(em.color))
                        .border(BorderStroke(ringWidth, Color.White), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (em.bike.isNotBlank()) {
                        // Same bike avatar as the map pin; the coloured ring stays around it.
                        Image(
                            painter = painterResource(
                                com.example.opendash.ui.screens.getBikeDefaultDrawable(em.bike)
                            ),
                            contentDescription = em.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().clip(CircleShape),
                        )
                    } else {
                        Text(
                            em.name.trim().take(1).uppercase().ifBlank { "?" },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private data class EdgeMarker(
    val id: String,
    val name: String,
    val bike: String,
    val color: Int,
    val x: Float,
    val y: Float,
    /** Screen-space angle to the peer (0 = up), radians; drives the pointer + edge clamp. */
    val angleRad: Float,
)

private fun computeEdgeMarkers(
    m: MapLibreMap,
    peers: List<com.example.opendash.data.GroupRide.Peer>,
    w: Float,
    h: Float,
    edgeInset: Float,
    riderLat: Double?,
    riderLng: Double?,
): List<EdgeMarker> {
    val bounds = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull()
    val center = runCatching { m.cameraPosition.target }.getOrNull() ?: return emptyList()
    val mapBearing = runCatching { m.cameraPosition.bearing }.getOrDefault(0.0)
    val cx = w / 2f
    val cy = h / 2f
    val out = ArrayList<EdgeMarker>(peers.size)
    peers.forEach { p ->
        if (p.lat == 0.0 && p.lng == 0.0) return@forEach
        val ll = LatLng(p.lat, p.lng)
        // On-screen peers keep their normal map pin — no edge chip.
        if (bounds?.contains(ll) == true) return@forEach
        val brg = bearingDeg(center.latitude, center.longitude, p.lat, p.lng) - mapBearing
        val rad = Math.toRadians(brg).toFloat()
        val dirX = sin(rad)
        val dirY = -cos(rad)
        // Clamp onto the inset edge rectangle along the direction vector.
        val sx = if (abs(dirX) > 1e-4f) (cx - edgeInset) / abs(dirX) else Float.MAX_VALUE
        val sy = if (abs(dirY) > 1e-4f) (cy - edgeInset) / abs(dirY) else Float.MAX_VALUE
        val s = minOf(sx, sy)
        val colorIdx = Math.abs(p.id.hashCode()) % PEER_COLORS.size
        out += EdgeMarker(
            id = p.id,
            name = p.name,
            bike = p.bike,
            color = if (p.isStale) 0xFF9AA0A6.toInt() else PEER_COLORS[colorIdx],
            x = cx + dirX * s,
            y = cy + dirY * s,
            angleRad = rad,
        )
    }
    return out
}

/** Total ground length of a polyline in metres. */
private fun geomLengthM(pts: List<GeoPoint>): Double {
    var sum = 0.0
    for (i in 1 until pts.size) sum += GeoPoint.distMeters(pts[i - 1], pts[i])
    return sum
}

/** Initial compass bearing from (lat1,lon1) to (lat2,lon2), degrees clockwise from north. */
private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val la1 = Math.toRadians(lat1)
    val la2 = Math.toRadians(lat2)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Hybrid satellite: slide Esri imagery under the vector style's symbol (label) layers.
 * Everything below the first symbol layer — land, water, road lines — is covered by the
 * imagery; the vector labels and road names stay on top and stay crisp at every zoom.
 */
private fun addSatelliteImagery(style: Style) {
    val tileSet = TileSet("2.2.0", SATELLITE_TILE_URL).apply {
        maxZoom = 19f
        attribution = SATELLITE_ATTRIBUTION
    }
    style.addSource(RasterSource(SATELLITE_SOURCE_ID, tileSet, 256))
    val layer = RasterLayer(SATELLITE_LAYER_ID, SATELLITE_SOURCE_ID)
    val firstSymbolLayerId = style.layers.firstOrNull { it is SymbolLayer }?.id
    if (firstSymbolLayerId != null) style.addLayerBelow(layer, firstSymbolLayerId)
    else style.addLayer(layer)
    // The style interleaves non-label layers (building footprints/3D) among the symbol
    // layers, so they'd draw ON TOP of the imagery as white boxes — hide everything
    // above the raster that isn't a label.
    val layers = style.layers
    val rasterIdx = layers.indexOfFirst { it.id == SATELLITE_LAYER_ID }
    for (i in rasterIdx + 1 until layers.size) {
        val l = layers[i]
        if (l !is SymbolLayer) {
            runCatching { l.setProperties(PropertyFactory.visibility("none")) }
        }
    }
}

private fun disable3dBuildings(style: Style, night: Boolean = false) {
    val isSatellite = style.getLayer(SATELLITE_LAYER_ID) != null
    val flatFill = if (night) android.graphics.Color.rgb(44, 44, 46)
                   else android.graphics.Color.rgb(218, 218, 218)
    val layers = ArrayList(style.layers)
    for (layer in layers) {
        if (layer.javaClass.simpleName == "FillExtrusionLayer" && layer is org.maplibre.android.style.layers.FillExtrusionLayer) {
            val sourceId = layer.sourceId
            val sourceLayer = layer.sourceLayer
            val filter = layer.filter
            
            val flatLayerId = layer.id + "-flat-2d"
            if (style.getLayer(flatLayerId) == null) {
                val flatLayer = FillLayer(flatLayerId, sourceId).apply {
                    withSourceLayer(sourceLayer)
                    if (filter != null) withFilter(filter)
                    setProperties(
                        PropertyFactory.visibility(if (isSatellite) "none" else "visible"),
                        PropertyFactory.fillColor(flatFill),
                        PropertyFactory.fillOpacity(0.8f)
                    )
                }
                style.addLayerBelow(flatLayer, layer.id)
            } else {
                val flatLayer = style.getLayer(flatLayerId)
                flatLayer?.setProperties(PropertyFactory.visibility(if (isSatellite) "none" else "visible"))
            }
            
            runCatching { layer.setProperties(PropertyFactory.visibility("none")) }
        }
    }
}

/** Route-line traffic color by segment level: 0 free-flow (blue), 1 slow (orange), 2 jam (red). */
private fun congestionColor(level: Int): String = when (level) {
    1 -> "#F4A000"
    2 -> "#E5341F"
    else -> ROUTE_FILL
}

private fun drawAlternateRoute(lm: LineManager, points: List<GeoPoint>, congestion: List<Int> = emptyList()): List<org.maplibre.android.plugins.annotation.Line> {
    val created = mutableListOf<org.maplibre.android.plugins.annotation.Line>()
    val latLngs = points.map { LatLng(it.lat, it.lng) }
    created += lm.create(LineOptions().withLatLngs(latLngs).withLineColor(ALT_CASING).withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(ALT_CASING_W))
    created += lm.create(LineOptions().withLatLngs(latLngs).withLineColor(ALT_FILL).withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(ALT_FILL_W))
    if (congestion.size == points.size - 1 && congestion.any { it > 0 }) {
        var start = 0
        while (start < congestion.size) {
            var end = start + 1
            while (end < congestion.size && congestion[end] == congestion[start]) end++
            if (congestion[start] > 0) {
                created += lm.create(
                    LineOptions().withLatLngs((start..end).map { latLngs[it] })
                        .withLineColor(congestionColor(congestion[start]))
                        .withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                        .withLineWidth(ALT_FILL_W)
                )
            }
            start = end
        }
    }
    return created
}

/** Draw the selected route: dark casing under a blue fill, colored by [congestion] runs. */
private fun drawColoredRoute(lm: LineManager, points: List<GeoPoint>, congestion: List<Int>): List<org.maplibre.android.plugins.annotation.Line> {
    val created = mutableListOf<org.maplibre.android.plugins.annotation.Line>()
    if (points.size < 2) return created
    val latLngs = points.map { LatLng(it.lat, it.lng) }
    created += lm.create(LineOptions().withLatLngs(latLngs).withLineColor(ROUTE_CASING).withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(ROUTE_CASING_W))
    val hasTraffic = congestion.size == points.size - 1 && congestion.any { it > 0 }
    if (hasTraffic) {
        var start = 0
        while (start < congestion.size) {
            var end = start + 1
            while (end < congestion.size && congestion[end] == congestion[start]) end++
            val pts = (start..end).map { LatLng(points[it].lat, points[it].lng) }
            created += lm.create(LineOptions().withLatLngs(pts).withLineColor(congestionColor(congestion[start])).withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(ROUTE_FILL_W))
            start = end
        }
    } else {
        created += lm.create(LineOptions().withLatLngs(latLngs).withLineColor(ROUTE_FILL).withLineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND).withLineWidth(ROUTE_FILL_W))
    }
    return created
}

/** Google-style route time callout: rounded rectangle with a pointed tail at the
 *  bottom-left (the tail tip is the map anchor) and a soft drop shadow. Selected =
 *  navy with white text; alternate = white with dark text. */
private fun durationBubbleBitmap(context: android.content.Context, text: String, selected: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val padH = 10f * d
    val padV = 7f * d
    val textSize = 13.5f * d
    val tailH = 7f * d
    val corner = 7f * d
    val shadowPad = 4f * d
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    val tw = paint.measureText(text)
    val rectW = tw + padH * 2
    val rectH = textSize + padV * 2
    val w = (rectW + shadowPad * 2).toInt()
    val h = (rectH + tailH + shadowPad * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val left = shadowPad
    val top = shadowPad

    val bubble = Path().apply {
        addRoundRect(
            android.graphics.RectF(left, top, left + rectW, top + rectH),
            corner, corner, Path.Direction.CW,
        )
        // Pointed tail off the bottom-left corner; its tip is what anchors to the route.
        moveTo(left + 4f * d, top + rectH - 1f * d)
        lineTo(left + 1.5f * d, top + rectH + tailH)
        lineTo(left + 16f * d, top + rectH - 1f * d)
        close()
    }
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) android.graphics.Color.rgb(0, 32, 208) else android.graphics.Color.WHITE
        setShadowLayer(2.5f * d, 0f, 1.2f * d, android.graphics.Color.argb(80, 0, 0, 0))
    }
    c.drawPath(bubble, bg)

    paint.color = if (selected) android.graphics.Color.WHITE else android.graphics.Color.rgb(32, 33, 36)
    val fm = paint.fontMetrics
    val ty = top + rectH / 2f - (fm.ascent + fm.descent) / 2f
    c.drawText(text, left + padH, ty, paint)
    return bmp
}

/** Google-style blue chevron-in-a-circle, pointing "up" (rotated to heading by the symbol). */
/** Classic navigation chevron: white arrow in a blue circle. The alternate rider marker. */
private fun chevronBitmap(): Bitmap {
    val s = 84
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.34f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.WHITE
    val cx = s / 2f
    c.drawPath(Path().apply {
        moveTo(cx, s * 0.24f); lineTo(s * 0.72f, s * 0.70f); lineTo(cx, s * 0.58f); lineTo(s * 0.28f, s * 0.70f); close()
    }, p)
    return bmp
}

/** Top-down bike marker rendered from the bundled SVG asset (res/raw/bike_marker.svg,
 *  blue shades recolored to gold). Falls back to the hand-drawn Himalayan if the SVG
 *  fails to parse. Front points up; rotated by bearing. */
private fun bikeMarkerBitmap(context: Context): Bitmap {
    runCatching {
        val svg = com.caverock.androidsvg.SVG.getFromResource(context.resources, com.example.opendash.R.raw.bike_marker)
        val h = 80
        val w = (h * svg.documentViewBox.width() / svg.documentViewBox.height()).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.documentWidth = w.toFloat()
        svg.documentHeight = h.toFloat()
        svg.renderToCanvas(Canvas(bmp))
        return bmp
    }
    return drawnBikeMarkerBitmap()
}

/** Hand-drawn fallback: golden beak fender and tank, windscreen, wide bars with
 *  handguards, black seat and tyres — with a white contrast outline. */
private fun drawnBikeMarkerBitmap(): Bitmap {
    val w = 42
    val h = 74
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(42f / 64f, 74f / 112f)
    val cx = 32f
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    fun drawPathPart(p: Path, color: Int) {
        c.drawPath(p, outline); fill.color = color; c.drawPath(p, fill)
    }
    fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        val rect = android.graphics.RectF(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, outline)
        fill.color = color
        c.drawRoundRect(rect, rad, rad, fill)
    }
    // Front wheel peeking out under the fender.
    roundRect(cx - 5f, 10f, cx + 5f, 32f, 4f, 0xFF17191B.toInt())
    // The Himalayan's pointed beak front fender (gold bodywork).
    drawPathPart(Path().apply {
        moveTo(cx, 2f)
        lineTo(cx + 8f, 15f); lineTo(cx + 7f, 27f)
        lineTo(cx - 7f, 27f); lineTo(cx - 8f, 15f)
        close()
    }, 0xFFC8941F.toInt())
    // Windscreen (light slate, like the tall touring screen).
    drawPathPart(Path().apply {
        moveTo(cx - 10f, 27f); lineTo(cx + 10f, 27f)
        lineTo(cx + 7f, 35f); lineTo(cx - 7f, 35f)
        close()
    }, 0xFF9FB4C4.toInt())
    // Wide adventure handlebar with round handguard knobs.
    roundRect(9f, 33f, 55f, 40f, 3.5f, 0xFF23272B.toInt())
    c.drawCircle(11f, 36.5f, 5.5f, fill.apply { color = 0xFF2E3338.toInt() })
    c.drawCircle(53f, 36.5f, 5.5f, fill)
    // Fuel tank, tapering back to the seat (gold bodywork).
    drawPathPart(Path().apply {
        moveTo(cx - 13f, 41f); lineTo(cx + 13f, 41f)
        quadTo(cx + 11f, 58f, cx + 8f, 66f)
        lineTo(cx - 8f, 66f)
        quadTo(cx - 11f, 58f, cx - 13f, 41f)
        close()
    }, 0xFFE0A62E.toInt())
    // Seat, black, running back from the tank (no rider on the marker).
    roundRect(cx - 10f, 64f, cx + 10f, 92f, 7f, 0xFF000000.toInt())
    // Rear wheel.
    roundRect(cx - 6f, 94f, cx + 6f, 110f, 5f, 0xFF17191B.toInt())
    return bmp
}

/** Blue dot + compass beam, Google-style: a translucent cone fanning out above the dot.
 *  The dot sits at bitmap center, so icon rotation sweeps the beam around it. */
private fun riderDotBeamBitmap(): Bitmap {
    // Canvas is 2× the dot bitmap so the beam can reach twice as far; the dot keeps its
    // original pixel size (radii are absolute, not canvas-relative).
    val s = 192
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = s / 2f
    val cy = s / 2f
    p.shader = android.graphics.LinearGradient(
        cx, cy, cx, s * 0.04f,
        android.graphics.Color.argb(120, 66, 133, 244),
        android.graphics.Color.argb(0, 66, 133, 244),
        android.graphics.Shader.TileMode.CLAMP,
    )
    c.drawPath(Path().apply {
        moveTo(cx, cy)
        lineTo(cx - 50f, s * 0.04f)
        lineTo(cx + 50f, s * 0.04f)
        close()
    }, p)
    p.shader = null
    p.color = android.graphics.Color.argb(48, 66, 133, 244)
    c.drawCircle(cx, cy, 29f, p)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(cx, cy, 16f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(cx, cy, 12f, p)
    return bmp
}

/** Group Ride peer pin: teardrop marker (colored fill, white ring, drop shadow) with the
 *  rider's garage bike photo inside — falls back to the rider's initial when no bike is
 *  shared (older client / no vehicle set up). Delivery-app style, readable at a glance. */
private fun peerPinBitmap(context: Context, color: Int, initial: String, bike: String, stale: Boolean): Bitmap {
    val w = 76
    val h = 92
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val headR = 26f
    val headCy = 6f + headR
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // Soft shadow under the tip.
    p.color = android.graphics.Color.argb(60, 0, 0, 0)
    c.drawOval(android.graphics.RectF(cx - 13f, h - 9f, cx + 13f, h - 1f), p)

    // Teardrop: circle head + tail down to the tip.
    val tipY = h - 6f
    val tail = Path().apply {
        moveTo(cx - headR * 0.62f, headCy + headR * 0.72f)
        lineTo(cx, tipY)
        lineTo(cx + headR * 0.62f, headCy + headR * 0.72f)
        close()
    }
    // White outline pass.
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    c.drawPath(tail, outline)
    c.drawCircle(cx, headCy, headR, outline)
    // Colored fill.
    p.color = color
    c.drawPath(tail, p)
    c.drawCircle(cx, headCy, headR, p)

    // Bike photo, center-cropped into the head circle (white ring stays around it).
    if (bike.isNotBlank()) {
        val photo = android.graphics.BitmapFactory.decodeResource(
            context.resources,
            com.example.opendash.ui.screens.getBikeDefaultDrawable(bike),
        )
        if (photo != null) {
            val save = c.save()
            c.clipPath(Path().apply { addCircle(cx, headCy, headR, Path.Direction.CW) })
            val target = headR * 2f
            val scale = maxOf(target / photo.width, target / photo.height)
            val dw = photo.width * scale
            val dh = photo.height * scale
            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            // Stale peers desaturate, matching the greyed name label.
            if (stale) {
                photoPaint.colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(0f) },
                )
            }
            c.drawBitmap(
                photo, null,
                android.graphics.RectF(cx - dw / 2f, headCy - dh / 2f, cx + dw / 2f, headCy + dh / 2f),
                photoPaint,
            )
            c.restoreToCount(save)
            return bmp
        }
    }

    // Rider initial, white and bold, centered in the head.
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = 30f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    val baseline = headCy - (t.descent() + t.ascent()) / 2f
    c.drawText(initial, cx, baseline, t)
    return bmp
}

/** Google-style on-map maneuver callout: white rounded pill with a turn-arrow glyph and the
 *  road name, a little tail at the bottom pointing to the turn location. */
private fun maneuverLabelBitmap(
    context: Context,
    road: String,
    type: com.example.opendash.dash.nav.ManeuverType?,
): Bitmap {
    val d = context.resources.displayMetrics.density
    val padH = 12f * d; val padV = 8f * d; val arrow = 22f * d; val gap = 7f * d
    val tail = 9f * d; val corner = 12f * d; val pad = 5f * d
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(32, 33, 36)
        textSize = 15f * d
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    val maxText = 190f * d
    val label = run {
        if (text.measureText(road) <= maxText) road else {
            var s = road
            while (s.length > 4 && text.measureText("$s…") > maxText) s = s.dropLast(1)
            "$s…"
        }
    }
    val tw = text.measureText(label)
    val rectW = padH + arrow + gap + tw + padH
    val rectH = maxOf(arrow, text.textSize) + padV * 2
    val w = (rectW + pad * 2).toInt()
    val h = (rectH + tail + pad * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val left = pad; val top = pad
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(3f * d, 0f, 1.5f * d, android.graphics.Color.argb(90, 0, 0, 0))
    }
    val body = Path().apply {
        addRoundRect(android.graphics.RectF(left, top, left + rectW, top + rectH), corner, corner, Path.Direction.CW)
        val cx = left + rectW / 2f
        moveTo(cx - 8f * d, top + rectH - 1f)
        lineTo(cx, top + rectH + tail)
        lineTo(cx + 8f * d, top + rectH - 1f)
        close()
    }
    c.drawPath(body, bg)
    // Turn-arrow glyph in Google blue.
    val ax = left + padH + arrow / 2f
    val ay = top + rectH / 2f
    drawTurnGlyph(c, ax, ay, arrow, type, android.graphics.Color.rgb(26, 115, 232), 3f * d)
    // Road text.
    val fm = text.fontMetrics
    c.drawText(label, left + padH + arrow + gap, ay - (fm.ascent + fm.descent) / 2f, text)
    return bmp
}

/** Minimal turn arrow centered at (cx,cy) within [size], matching maneuver type. */
private fun drawTurnGlyph(
    c: Canvas, cx: Float, cy: Float, size: Float,
    type: com.example.opendash.dash.nav.ManeuverType?, color: Int, stroke: Float,
) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val s = size * 0.42f
    val path = Path()
    when (type) {
        com.example.opendash.dash.nav.ManeuverType.TURN_LEFT,
        com.example.opendash.dash.nav.ManeuverType.SHARP_LEFT,
        com.example.opendash.dash.nav.ManeuverType.SLIGHT_LEFT -> {
            path.moveTo(cx + s * 0.5f, cy + s); path.lineTo(cx + s * 0.5f, cy - s * 0.1f)
            path.lineTo(cx - s * 0.6f, cy - s * 0.1f)
            path.moveTo(cx - s * 0.2f, cy - s * 0.6f); path.lineTo(cx - s * 0.6f, cy - s * 0.1f); path.lineTo(cx - s * 0.2f, cy + s * 0.4f)
        }
        com.example.opendash.dash.nav.ManeuverType.TURN_RIGHT,
        com.example.opendash.dash.nav.ManeuverType.SHARP_RIGHT,
        com.example.opendash.dash.nav.ManeuverType.SLIGHT_RIGHT -> {
            path.moveTo(cx - s * 0.5f, cy + s); path.lineTo(cx - s * 0.5f, cy - s * 0.1f)
            path.lineTo(cx + s * 0.6f, cy - s * 0.1f)
            path.moveTo(cx + s * 0.2f, cy - s * 0.6f); path.lineTo(cx + s * 0.6f, cy - s * 0.1f); path.lineTo(cx + s * 0.2f, cy + s * 0.4f)
        }
        com.example.opendash.dash.nav.ManeuverType.UTURN -> {
            path.moveTo(cx + s * 0.5f, cy + s); path.lineTo(cx + s * 0.5f, cy - s * 0.2f)
            path.addArc(android.graphics.RectF(cx - s * 0.5f, cy - s * 0.8f, cx + s * 0.5f, cy + s * 0.0f), 0f, -180f)
            path.moveTo(cx - s * 0.5f, cy + s * 0.2f); path.lineTo(cx - s * 0.5f, cy - s * 0.2f)
            path.moveTo(cx - s * 0.8f, cy + s * 0.0f); path.lineTo(cx - s * 0.5f, cy + s * 0.3f); path.lineTo(cx - s * 0.2f, cy + s * 0.0f)
        }
        else -> {
            path.moveTo(cx, cy + s); path.lineTo(cx, cy - s)
            path.moveTo(cx - s * 0.5f, cy - s * 0.4f); path.lineTo(cx, cy - s); path.lineTo(cx + s * 0.5f, cy - s * 0.4f)
        }
    }
    c.drawPath(path, p)
}

/** Google-style current-location blue dot: soft halo + white ring + blue fill. */
private fun riderDotBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.argb(48, 66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.48f, p)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.22f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.17f, p)
    return bmp
}

/** Simple red destination pin (white ring + red fill). */
private fun destPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(234, 67, 53); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}

/** Google-style intermediate stop pin (white ring + blue fill). */
private fun stopPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}

/** Green pin for the trail recording start point. White ring + green fill + white dot. */
private fun trailStartPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(52, 168, 83); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}

/**
 * A point along [route] (preferring visible, well-spread fractions) that is at least 120 m
 * from every [placed] bubble anchor — used to slide a colliding alternate's bubble to a free
 * spot instead of stacking two labels. Null if nothing clear is found.
 */
private fun freeAnchorAlong(
    route: List<GeoPoint>,
    placed: List<GeoPoint>,
    bounds: LatLngBounds?,
): GeoPoint? {
    val fractions = listOf(0.5, 0.62, 0.38, 0.72, 0.28, 0.82, 0.18)
    val inView: (GeoPoint) -> Boolean =
        if (bounds == null) ({ true }) else ({ bounds.contains(LatLng(it.lat, it.lng)) })
    // Prefer candidates on screen; fall back to any clear candidate.
    for (requireView in listOf(true, false)) {
        for (f in fractions) {
            val p = route[(f * (route.size - 1)).toInt().coerceIn(0, route.size - 1)]
            if (requireView && !inView(p)) continue
            if (placed.none { GeoPoint.distMeters(it, p) < 120.0 }) return p
        }
    }
    return null
}

/**
 * Where a route's duration bubble goes — Google-style: early (~a third) into the longest
 * stretch that is unique to this route versus [other], and, when [bounds] is given, also
 * inside the current viewport so the label follows panning/zooming. Falls back to any
 * visible stretch, then the route midpoint; null when nothing qualifies (leave the bubble
 * where it was).
 */
private fun bubbleAnchor(
    route: List<GeoPoint>,
    other: List<GeoPoint>?,
    bounds: LatLngBounds? = null,
): GeoPoint? {
    if (route.size < 2) return null
    val inView: (GeoPoint) -> Boolean =
        if (bounds == null) ({ true }) else ({ bounds.contains(LatLng(it.lat, it.lng)) })
    val distinct: (GeoPoint) -> Boolean =
        if (other == null || other.size < 2) ({ true })
        else {
            // Sample the comparison route so the distance checks stay cheap on long routes.
            val step = (other.size / 150).coerceAtLeast(1)
            val sampled = ArrayList<GeoPoint>(other.size / step + 1)
            for (i in other.indices step step) sampled.add(other[i])
            ({ p -> sampled.all { GeoPoint.distMeters(p, it) > 40.0 } })
        }

    fun longestRun(ok: (GeoPoint) -> Boolean): Pair<Int, Int>? {
        var bestStart = -1; var bestLen = 0; var curStart = -1
        for (i in route.indices) {
            if (ok(route[i])) {
                if (curStart < 0) curStart = i
                val len = i - curStart + 1
                if (len > bestLen) { bestLen = len; bestStart = curStart }
            } else curStart = -1
        }
        return if (bestStart >= 0) bestStart to bestLen else null
    }

    // Unique-to-this-route AND on screen: label lands just past the divergence.
    longestRun { distinct(it) && inView(it) }?.let { (s, l) -> return route[s + (l * 3) / 10] }
    // Whole distinct stretch is off screen: keep the label on whatever part is visible.
    if (bounds != null) {
        longestRun(inView)?.let { (s, l) -> return route[s + l / 2] }
        return null // route fully off screen — don't move the bubble
    }
    return route[route.size / 2]
}

/**
 * Returns the index in [points] of the point closest to [rider].
 * Used to split the route into a grey "already travelled" prefix and a blue remaining suffix.
 */
private fun closestPointIndex(points: List<GeoPoint>, rider: GeoPoint): Int {
    var bestIdx = 0
    var bestDist = Double.MAX_VALUE
    points.forEachIndexed { i, pt ->
        val d = GeoPoint.distMeters(rider, pt)
        if (d < bestDist) { bestDist = d; bestIdx = i }
    }
    return bestIdx
}

/** Pure translucent blue beam to show bike direction without rotating the bike marker. */
private fun bikeBeamBitmap(): Bitmap {
    val s = 192
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = s / 2f
    val cy = s / 2f
    p.shader = android.graphics.LinearGradient(
        cx, cy, cx, cy - 45f,
        android.graphics.Color.argb(210, 66, 133, 244),
        android.graphics.Color.argb(0, 66, 133, 244),
        android.graphics.Shader.TileMode.CLAMP,
    )
    c.drawPath(Path().apply {
        moveTo(cx, cy)
        lineTo(cx - 30f, cy - 45f)
        lineTo(cx + 30f, cy - 45f)
        close()
    }, p)
    return bmp
}

