package com.example.opendash.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
private const val CHEVRON_ICON = "rider-chevron"
private const val DOT_ICON = "rider-dot"
private const val DOT_BEAM_ICON = "rider-dot-beam"
private const val DEST_ICON = "dest-pin"
private const val STOP_ICON = "stop-pin"

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
    routeDurations: List<String> = emptyList(),
    selectedRouteIndex: Int = 0,
    onSelectRoute: (Int) -> Unit = {},
    /** Satellite imagery basemap instead of the vector street style. */
    satellite: Boolean = false,
    compassBottomMarginDp: Int = 16,
    zoom: Double? = null,
    followMode: Boolean = true,
    /** Hide the ⓘ attribution button (dash preview — chrome-free like the real dash). */
    showAttribution: Boolean = true,
    /** Scale factor for the rider chevron/dot marker. */
    riderIconScale: Float = 1f,
    /** Place the rider low in the viewport so more map ahead is visible (nav follow mode). */
    cameraAheadOffset: Boolean = false,
    /** Overrides the MARKER arrow direction only (camera keeps [riderBearing]). Pass the
     *  device compass azimuth while stationary for the Google-blue-dot effect. */
    markerBearing: Float? = null,
    /** Nav-mode rider marker style: true = top-down bike, false = classic chevron arrow. */
    bikeMarker: Boolean = true,
    recordedPoints: List<GeoPoint> = emptyList(),
    stops: List<GeoPoint> = emptyList(),
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lineMgr by remember { mutableStateOf<LineManager?>(null) }
    var symbolMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var destroyed by remember { mutableStateOf(false) }

    // Latest routes + select callback, read live by the one-time map-click listener.
    val routesRef = remember { mutableStateOf<List<List<GeoPoint>>>(emptyList()) }
    routesRef.value = allRoutes
    val selectCb = androidx.compose.runtime.rememberUpdatedState(onSelectRoute)

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
                val routes = routesRef.value
                if (routes.size <= 1) return@addOnMapClickListener false
                var best = -1
                var bestD = Double.MAX_VALUE
                val tap = GeoPoint(point.latitude, point.longitude)
                
                // Prioritize checking if the click is near the midpoint (bubble) of any route
                routes.forEachIndexed { i, geo ->
                    if (geo.size >= 2) {
                        val mid = geo[geo.size / 2]
                        val distToMid = GeoPoint.distMeters(tap, mid)
                        if (distToMid < 75.0) { // tapped on or very close to the bubble
                            best = i
                            bestD = distToMid
                        }
                    }
                }
                
                if (best == -1) {
                    // Fallback to checking line distance
                    routes.forEachIndexed { i, geo ->
                        val step = (geo.size / 200).coerceAtLeast(1)
                        for (idx in geo.indices step step) {
                            val dd = GeoPoint.distMeters(tap, geo[idx])
                            if (dd < bestD) { bestD = dd; best = i }
                        }
                    }
                }
                if (best >= 0 && bestD < 500.0) { selectCb.value(best); true } else false
            }
            map = m
        }
    }

    // (Re)apply the basemap style — runs on first map-ready and on satellite toggle.
    // Annotation managers are bound to a style, so they're rebuilt on every swap.
    LaunchedEffect(map, satellite) {
        val m = map ?: return@LaunchedEffect
        styleReady = false
        runCatching { lineMgr?.onDestroy() }
        runCatching { symbolMgr?.onDestroy() }
        lineMgr = null; symbolMgr = null
        m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
            if (destroyed) return@setStyle
            if (satellite) addSatelliteImagery(style)
            disable3dBuildings(style)
            style.addImage(RIDER_ICON, bikeMarkerBitmap(context))
            style.addImage(CHEVRON_ICON, chevronBitmap())
            style.addImage(DOT_ICON, riderDotBitmap())
            style.addImage(DOT_BEAM_ICON, riderDotBeamBitmap())
            style.addImage(DEST_ICON, destPinBitmap())
            style.addImage(STOP_ICON, stopPinBitmap())
            lineMgr = LineManager(mapView, m, style)
            symbolMgr = SymbolManager(mapView, m, style).apply {
                iconAllowOverlap = true; iconIgnorePlacement = true
                // North-relative icon rotation: marker bearings are absolute degrees, so
                // they stay correct whether the camera is north-up or heading-up.
                iconRotationAlignment = org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP
            }
            styleReady = true
        }
    }

    // Redraw route + markers whenever the data or mode changes.
    LaunchedEffect(styleReady, routePoints, routeCongestion, alternateRoutes, allRoutes, routeDurations, selectedRouteIndex, dest, stops, riderLat, riderLng, riderBearing, markerBearing, bikeMarker, navMode, recordedPoints) {
        if (destroyed) return@LaunchedEffect
        val style = map?.style ?: return@LaunchedEffect
        val lm = lineMgr ?: return@LaunchedEffect
        val sm = symbolMgr ?: return@LaunchedEffect
        lm.deleteAll(); sm.deleteAll()

        if (allRoutes.isNotEmpty()) {
            // Multi-route selection mode: grey alternates, colored selected, duration bubbles.
            allRoutes.forEachIndexed { i, geo ->
                if (i != selectedRouteIndex && geo.size >= 2) {
                    lm.create(
                        LineOptions().withLatLngs(geo.map { LatLng(it.lat, it.lng) })
                            .withLineColor("#9AA0A6").withLineWidth(4.0f)
                    )
                }
            }
            allRoutes.getOrNull(selectedRouteIndex)?.let { sel ->
                drawColoredRoute(lm, sel, routeCongestion)
            }
            // Draw duration bubbles at the midpoints of the routes
            allRoutes.forEachIndexed { i, geo ->
                if (geo.size >= 2) {
                    val durationText = routeDurations.getOrNull(i)
                    if (durationText != null) {
                        val midPoint = geo[geo.size / 2]
                        val isSelected = (i == selectedRouteIndex)
                        val bubbleBitmap = durationBubbleBitmap(context, durationText, isSelected)
                        val iconId = "bubble_${i}_${isSelected}"
                        style.addImage(iconId, bubbleBitmap)
                        sm.create(
                            SymbolOptions()
                                .withLatLng(LatLng(midPoint.lat, midPoint.lng))
                                .withIconImage(iconId)
                                .withIconSize(1.0f)
                                .withSymbolSortKey(if (isSelected) 10f else 1f)
                        )
                    }
                }
            }

        } else {
            // Single-route mode (dash view): alternates + selected + traffic coloring.
            alternateRoutes.forEach { alt ->
                if (alt.size >= 2) {
                    lm.create(
                        LineOptions().withLatLngs(alt.map { LatLng(it.lat, it.lng) })
                            .withLineColor("#9AA0A6").withLineWidth(4.0f)
                    )
                }
            }
            if (routePoints.size >= 2) drawColoredRoute(lm, routePoints, routeCongestion)
        }
        if (recordedPoints.size >= 2) {
            lm.create(
                LineOptions().withLatLngs(recordedPoints.map { LatLng(it.lat, it.lng) })
                    .withLineColor("#E5341F").withLineWidth(5.5f)
            )
        }
        dest?.let { sm.create(SymbolOptions().withLatLng(LatLng(it.first, it.second)).withIconImage(DEST_ICON).withIconSize(1.1f)) }
        stops.forEach { pt ->
            sm.create(
                SymbolOptions().withLatLng(LatLng(pt.lat, pt.lng))
                    .withIconImage(STOP_ICON).withIconSize(1.1f)
            )
        }
        if (riderLat != null && riderLng != null) {
            // Nav: heading chevron. Otherwise: Google-style "you are here" blue dot.
            if (navMode) sm.create(
                SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                    .withIconImage(if (bikeMarker) RIDER_ICON else CHEVRON_ICON)
                    .withIconRotate(markerBearing ?: riderBearing)
                    .withIconSize(riderIconScale)
            ) else sm.create(
                SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                    .withIconImage(if (markerBearing != null) DOT_BEAM_ICON else DOT_ICON)
                    .withIconRotate(markerBearing ?: 0f)
                    .withIconSize(riderIconScale)
            )
        }
    }

    // Camera control. In fitRoute (preview) mode, moves are one-shot per target — the
    // rider dot updates every GPS fix and continuously re-centering would fight panning.
    var camKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(styleReady, riderLat, riderLng, riderBearing, navMode, fitRoute, routePoints.size, dest, zoom, followMode) {
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
                val pos = if (navMode)
                    CameraPosition.Builder().target(target).zoom(z).tilt(NAV_TILT).bearing(riderBearing.toDouble())
                        .padding(0.0, topPad, 0.0, 0.0).build()
                else
                    CameraPosition.Builder().target(target).zoom(z).tilt(0.0).bearing(0.0)
                        .padding(0.0, topPad, 0.0, 0.0).build()
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
        val position = if (navMode) {
            CameraPosition.Builder()
                .target(target)
                .zoom(z)
                .tilt(NAV_TILT)
                .bearing(riderBearing.toDouble())
                .padding(0.0, topPad, 0.0, 0.0)
                .build()
        } else {
            CameraPosition.Builder()
                .target(target)
                .zoom(z)
                .tilt(0.0)
                .bearing(0.0)
                .padding(0.0, topPad, 0.0, 0.0)
                .build()
        }
        runCatching { m.animateCamera(CameraUpdateFactory.newCameraPosition(position), 500) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
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

private fun disable3dBuildings(style: Style) {
    val isSatellite = style.getLayer(SATELLITE_LAYER_ID) != null
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
                        PropertyFactory.fillColor(android.graphics.Color.rgb(218, 218, 218)),
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
    else -> "#4285F4"
}

/** Draw a route line, colored by [congestion] runs when present, else solid blue. */
private fun drawColoredRoute(lm: LineManager, points: List<GeoPoint>, congestion: List<Int>) {
    if (points.size < 2) return
    val hasTraffic = congestion.size == points.size - 1 && congestion.any { it > 0 }
    if (hasTraffic) {
        var start = 0
        while (start < congestion.size) {
            var end = start + 1
            while (end < congestion.size && congestion[end] == congestion[start]) end++
            val pts = (start..end).map { LatLng(points[it].lat, points[it].lng) }
            lm.create(LineOptions().withLatLngs(pts).withLineColor(congestionColor(congestion[start])).withLineWidth(6.0f))
            start = end
        }
    } else {
        lm.create(LineOptions().withLatLngs(points.map { LatLng(it.lat, it.lng) }).withLineColor("#4285F4").withLineWidth(5.5f))
    }
}

/** Google-style route time bubble: rounded pill with the duration text. */
private fun durationBubbleBitmap(context: android.content.Context, text: String, selected: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val pad = 9f * d
    val textSize = 13f * d
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val tw = paint.measureText(text)
    val w = (tw + pad * 2).toInt()
    val h = (textSize + pad * 1.5f).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = h / 2f
    // Bubble background.
    paint.color = if (selected) android.graphics.Color.rgb(66, 133, 244) else android.graphics.Color.rgb(48, 49, 52)
    c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, paint)
    // Text.
    paint.color = if (selected) android.graphics.Color.WHITE else android.graphics.Color.rgb(210, 212, 216)
    val fm = paint.fontMetrics
    val ty = h / 2f - (fm.ascent + fm.descent) / 2f
    c.drawText(text, pad, ty, paint)
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
        val h = 120
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
    val w = 64
    val h = 112
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
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
    roundRect(cx - 10f, 64f, cx + 10f, 92f, 7f, 0xFF1C1E20.toInt())
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
