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
import androidx.compose.ui.geometry.Offset
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
private const val RIDER_ICON = "rider-chevron"
private const val DOT_ICON = "rider-dot"
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
    joystickVelocity: Offset = Offset.Zero,
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
                isAttributionEnabled = true   // OSM/OpenFreeMap/Esri attribution (keep — licensing)
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
            style.addImage(RIDER_ICON, chevronBitmap())
            style.addImage(DOT_ICON, riderDotBitmap())
            style.addImage(DEST_ICON, destPinBitmap())
            style.addImage(STOP_ICON, stopPinBitmap())
            lineMgr = LineManager(mapView, m, style)
            symbolMgr = SymbolManager(mapView, m, style).apply {
                iconAllowOverlap = true; iconIgnorePlacement = true
            }
            styleReady = true
        }
    }

    // Redraw route + markers whenever the data or mode changes.
    LaunchedEffect(styleReady, routePoints, routeCongestion, alternateRoutes, allRoutes, routeDurations, selectedRouteIndex, dest, stops, riderLat, riderLng, riderBearing, navMode, recordedPoints) {
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
                    .withIconImage(RIDER_ICON).withIconRotate(riderBearing).withIconSize(1.0f)
            ) else sm.create(
                SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                    .withIconImage(DOT_ICON).withIconSize(1.0f)
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
                // Follow modes (dash preview / navigation): keep tracking the rider.
                val target = LatLng(riderLat, riderLng)
                val z = zoom ?: if (navMode) NAV_ZOOM else FOLLOW_ZOOM
                val pos = if (navMode)
                    CameraPosition.Builder().target(target).zoom(z).tilt(NAV_TILT).bearing(riderBearing.toDouble()).build()
                else
                    CameraPosition.Builder().target(target).zoom(z).tilt(0.0).bearing(0.0).build()
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

    LaunchedEffect(joystickVelocity) {
        val m = map ?: return@LaunchedEffect
        while (joystickVelocity.x != 0f || joystickVelocity.y != 0f) {
            // Scroll the map camera by the velocity amount.
            // Positive joystick x should move camera right (pan map right), positive y moves camera down (pan map down).
            val dx = joystickVelocity.x * 12f
            val dy = joystickVelocity.y * 12f
            runCatching {
                m.scrollBy(dx, dy)
            }
            kotlinx.coroutines.delay(16)
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
        val position = if (navMode) {
            CameraPosition.Builder()
                .target(target)
                .zoom(z)
                .tilt(NAV_TILT)
                .bearing(riderBearing.toDouble())
                .build()
        } else {
            CameraPosition.Builder()
                .target(target)
                .zoom(z)
                .tilt(0.0)
                .bearing(0.0)
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
