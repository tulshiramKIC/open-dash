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
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

// Free, keyless, redistributable vector basemap (look-first, per the distribution decision).
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

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
private const val NAV_TILT = 45.0
private const val PREVIEW_TOP_PADDING = 320
private const val PREVIEW_BOTTOM_PADDING = 1500
private const val RIDER_ICON = "rider-chevron"
private const val DOT_ICON = "rider-dot"
private const val DEST_ICON = "dest-pin"

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
    /** Satellite imagery basemap instead of the vector street style. */
    satellite: Boolean = false,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lineMgr by remember { mutableStateOf<LineManager?>(null) }
    var symbolMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var destroyed by remember { mutableStateOf(false) }

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
                isCompassEnabled = true
                // Clear the floating search/route card that overlays the top of the map.
                val d = context.resources.displayMetrics.density
                setCompassMargins((8 * d).toInt(), (210 * d).toInt(), (12 * d).toInt(), 0)
                isAttributionEnabled = true   // OSM/OpenFreeMap/Esri attribution (keep — licensing)
                isLogoEnabled = false
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
            style.addImage(RIDER_ICON, chevronBitmap())
            style.addImage(DOT_ICON, riderDotBitmap())
            style.addImage(DEST_ICON, destPinBitmap())
            lineMgr = LineManager(mapView, m, style)
            symbolMgr = SymbolManager(mapView, m, style).apply {
                iconAllowOverlap = true; iconIgnorePlacement = true
            }
            styleReady = true
        }
    }

    // Redraw route + markers whenever the data or mode changes.
    LaunchedEffect(styleReady, routePoints, alternateRoutes, dest, riderLat, riderLng, riderBearing, navMode) {
        if (destroyed) return@LaunchedEffect
        val lm = lineMgr ?: return@LaunchedEffect
        val sm = symbolMgr ?: return@LaunchedEffect
        lm.deleteAll(); sm.deleteAll()
        // Alternatives first (grey, under the selected route).
        alternateRoutes.forEach { alt ->
            if (alt.size >= 2) {
                lm.create(
                    LineOptions().withLatLngs(alt.map { LatLng(it.lat, it.lng) })
                        .withLineColor("#9AA0A6").withLineWidth(4.0f)
                )
            }
        }
        if (routePoints.size >= 2) {
            lm.create(
                LineOptions().withLatLngs(routePoints.map { LatLng(it.lat, it.lng) })
                    .withLineColor("#4285F4").withLineWidth(5.5f)
            )
        }
        dest?.let { sm.create(SymbolOptions().withLatLng(LatLng(it.first, it.second)).withIconImage(DEST_ICON).withIconSize(1.1f)) }
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
    LaunchedEffect(styleReady, riderLat, riderLng, riderBearing, navMode, fitRoute, routePoints.size, dest) {
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
            !fitRoute && riderLat != null && riderLng != null -> {
                // Follow modes (dash preview / navigation): keep tracking the rider.
                val target = LatLng(riderLat, riderLng)
                val pos = if (navMode)
                    CameraPosition.Builder().target(target).zoom(NAV_ZOOM).tilt(NAV_TILT).bearing(riderBearing.toDouble()).build()
                else
                    CameraPosition.Builder().target(target).zoom(FOLLOW_ZOOM).tilt(0.0).bearing(0.0).build()
                runCatching { m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600) }
            }
            riderLat != null && riderLng != null -> {
                // Preview with no route yet: open on the rider once, then leave the camera alone.
                if (camKey != "rider") {
                    camKey = "rider"
                    runCatching {
                        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(riderLat, riderLng), FOLLOW_ZOOM))
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

    LaunchedEffect(styleReady, recenterKey) {
        if (recenterKey == 0 || destroyed) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val lat = riderLat ?: return@LaunchedEffect
        val lng = riderLng ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val target = LatLng(lat, lng)
        val position = if (navMode) {
            CameraPosition.Builder()
                .target(target)
                .zoom(NAV_ZOOM)
                .tilt(NAV_TILT)
                .bearing(riderBearing.toDouble())
                .build()
        } else {
            CameraPosition.Builder()
                .target(target)
                .zoom(FOLLOW_ZOOM)
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
            runCatching { l.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility("none")) }
        }
    }
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
