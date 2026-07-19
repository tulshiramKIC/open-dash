package com.example.opendash.ui.screens

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.opendash.data.OfflineMaps
import com.example.opendash.dash.map.LocationTracker
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.OpenDashIconBtn
import com.example.opendash.ui.components.ScreenHeader
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.ui.theme.GeistMonoFamily
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Offline maps — frame an area on the map and download just its tiles for use with no
 * signal. Reuses the same [OfflineMaps.MAP_STYLE_URL] the live map renders, so a
 * downloaded area is served from disk automatically during navigation.
 */
@Composable
fun OfflineMapsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { OfflineMaps.init(ctx); true }
    val areas by OfflineMaps.areas.collectAsState()
    val download by OfflineMaps.download.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    remember { MapLibre.getInstance(ctx) }
    val mapView = remember { MapView(ctx) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var destroyed by remember { mutableStateOf(false) }
    var namingBounds by remember { mutableStateOf<LatLngBounds?>(null) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
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
            m.uiSettings.apply { isLogoEnabled = false; isAttributionEnabled = true }
            m.setStyle(Style.Builder().fromUri(OfflineMaps.MAP_STYLE_URL)) {
                // Open on the rider's last-known position so they frame where they are.
                val last = LocationTracker(ctx).lastKnown()
                val target = if (last != null) LatLng(last.latitude, last.longitude) else LatLng(20.5937, 78.9629)
                val zoom = if (last != null) 11.0 else 4.0
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
            }
            map = m
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp),
    ) {
        ScreenHeader(title = "Offline maps", onBack = onBack)

        Text(
            "Frame the area you need and download just its map tiles. Downloaded areas work with no signal while riding.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Framing map with a fixed selection frame drawn over it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // Selection frame — the inset rectangle whose bounds get downloaded.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(SELECTION_INSET.dp),
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = Color(0xFF4285F4),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    )
                }
            }
            Text(
                "Pan & zoom to frame the area",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GeistFamily,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC303134))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        // Download banner or the download button.
        val dl = download
        if (dl != null) {
            DownloadBanner(dl)
        } else {
            OpenDashBtn(
                "Download this area",
                onClick = {
                    val m = map ?: return@OpenDashBtn
                    namingBounds = selectionBounds(mapView, m)
                },
                icon = OpenDashIcons.Download,
                variant = BtnVariant.Primary,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            "Downloaded areas",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            modifier = Modifier.padding(bottom = 8.dp, start = 2.dp),
        )

        if (areas.isEmpty()) {
            Text(
                "No areas downloaded yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 2.dp, top = 4.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(areas, key = { it.id }) { area ->
                    AreaRow(area)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    namingBounds?.let { bounds ->
        NameAreaDialog(
            defaultName = "Area ${areas.size + 1}",
            onConfirm = { name, satellite ->
                OfflineMaps.startDownload(ctx, name, bounds, includeSatellite = satellite)
                namingBounds = null
            },
            onDismiss = { namingBounds = null },
        )
    }
}

@Composable
private fun DownloadBanner(dl: OfflineMaps.Download) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            dl.error != null -> "Download failed"
                            dl.done -> "\"${dl.name}\" downloaded"
                            else -> "Downloading \"${dl.name}\"…"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        dl.error ?: if (dl.done) OfflineMaps.formatSize(dl.sizeBytes)
                            else "${dl.percent}% · ${OfflineMaps.formatSize(dl.sizeBytes)}",
                        color = if (dl.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = if (dl.error != null) GeistFamily else GeistMonoFamily,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (dl.done || dl.error != null) {
                    OpenDashIconBtn(icon = OpenDashIcons.X, onClick = { OfflineMaps.clearDownloadStatus() }, size = 38.dp)
                } else {
                    TextButton(onClick = { OfflineMaps.cancelDownload() }) { Text("Cancel") }
                }
            }
            if (dl.error == null && !dl.done) {
                Spacer(Modifier.height(10.dp))
                if (dl.percent > 0) {
                    LinearProgressIndicator(
                        progress = { dl.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AreaRow(area: OfflineMaps.Area) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            MapSnapshotImage(
                bounds = area.bounds,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(area.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                val sub = if (area.complete) {
                    OfflineMaps.formatSize(area.sizeBytes)
                } else "Incomplete · ${OfflineMaps.formatSize(area.sizeBytes)}"
                Text(
                    sub,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, fontFamily = GeistMonoFamily,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OpenDashIconBtn(icon = OpenDashIcons.Trash, onClick = { confirmDelete = true }, size = 40.dp)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Delete \"${area.name}\"?",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily
                )
            },
            text = {
                Text(
                    "This removes the downloaded tiles for this area.",
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = GeistFamily,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { OfflineMaps.delete(area); confirmDelete = false }) {
                    Text(
                        "Delete",
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = GeistFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = GeistFamily
                    )
                }
            },
        )
    }
}

@Composable
private fun NameAreaDialog(defaultName: String, onConfirm: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var satellite by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Name this area",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistFamily
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Area name", fontFamily = GeistFamily) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = GeistFamily),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable { satellite = !satellite },
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = satellite,
                        onCheckedChange = { satellite = it },
                    )
                    Column {
                        Text("Include satellite imagery", fontFamily = GeistFamily, fontSize = 14.sp)
                        Text(
                            "Map + satellite in one download · larger",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = GeistFamily,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, satellite) }) {
                Text(
                    "Download",
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = GeistFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Cancel",
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = GeistFamily
                )
            }
        },
    )
}

/** Inset from the map edge to the selection frame, in dp — must match the drawn frame. */
private const val SELECTION_INSET = 16f

/** Bounds of the on-screen selection frame, from its pixel corners. */
private fun selectionBounds(mapView: MapView, map: MapLibreMap): LatLngBounds {
    val density = mapView.resources.displayMetrics.density
    val inset = SELECTION_INSET * density
    val w = mapView.width.toFloat()
    val h = mapView.height.toFloat()
    val proj = map.projection
    val a = proj.fromScreenLocation(PointF(inset, inset))
    val b = proj.fromScreenLocation(PointF(w - inset, h - inset))
    return LatLngBounds.Builder().include(a).include(b).build()
}

@Composable
private fun MapSnapshotImage(
    bounds: LatLngBounds?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(bounds) { mutableStateOf<android.graphics.Bitmap?>(null) }

    DisposableEffect(bounds) {
        if (bounds == null) return@DisposableEffect onDispose {}

        val density = context.resources.displayMetrics.density
        val size = (42 * density).toInt()

        val options = org.maplibre.android.snapshotter.MapSnapshotter.Options(size, size)
            .withStyle(OfflineMaps.MAP_STYLE_URL)
            .withRegion(bounds)

        val snapshotter = org.maplibre.android.snapshotter.MapSnapshotter(context, options)

        snapshotter.start(object : org.maplibre.android.snapshotter.MapSnapshotter.SnapshotReadyCallback {
            override fun onSnapshotReady(snapshot: org.maplibre.android.snapshotter.MapSnapshot) {
                bitmap = snapshot.bitmap
            }
        })

        onDispose {
            snapshotter.cancel()
        }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                OpenDashIcons.Map,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
