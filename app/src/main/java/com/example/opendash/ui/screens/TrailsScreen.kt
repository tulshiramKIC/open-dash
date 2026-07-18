package com.example.opendash.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.data.SavedLocation
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.RouteViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrailsScreen(
    routeViewModel: RouteViewModel,
    onNavigateToRoute: () -> Unit,
    onStartRecording: () -> Unit,
    isActiveNavigation: Boolean = false,
    onExitNavigation: () -> Unit = {},
) {
    val context = LocalContext.current
    val savedLocations by routeViewModel.saved.collectAsState()
    val exportMessage by routeViewModel.exportMessage.collectAsState()

    // Toast on export result
    LaunchedEffect(exportMessage) {
        val msg = exportMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        routeViewModel.clearExportMessage()
    }

    // Dialog state
    var pendingTrailForNav by remember { mutableStateOf<com.example.opendash.data.SavedLocation?>(null) }

    // Filter to find custom trails (saved locations that have a route JSON file on disk)
    val customTrails = remember(savedLocations) {
        savedLocations.filter { loc ->
            File(context.filesDir, "route_${loc.sid}.json").exists()
        }
    }

    val gpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            routeViewModel.importGpxFile(context, uri)
        }
    }

    // State to hold the trail currently being edited
    var editingTrail by remember { mutableStateOf<SavedLocation?>(null) }

    editingTrail?.let { trail ->
        EditLocationDialog(
            loc = trail,
            onSave = { name, note ->
                routeViewModel.renameSaved(trail, name, note)
                editingTrail = null
            },
            onDelete = {
                routeViewModel.deleteSaved(trail)
                editingTrail = null
            },
            onDismiss = {
                editingTrail = null
            },
            showNote = false
        )
    }

    // Confirmation: cancel navigation to load custom trail
    pendingTrailForNav?.let { trail ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingTrailForNav = null },
            containerColor = com.example.opendash.ui.theme.Bg1,
            icon = {
                Icon(OpenDashIcons.Route, null, tint = com.example.opendash.ui.theme.Warn, modifier = Modifier.size(28.dp))
            },
            title = {
                Text("Cancel navigation?", color = com.example.opendash.ui.theme.TextHi, fontWeight = FontWeight.Bold, fontFamily = com.example.opendash.ui.theme.GeistFamily)
            },
            text = {
                Text(
                    "You have an active navigation running. Loading \"${trail.name}\" will cancel it.",
                    color = com.example.opendash.ui.theme.TextLo, fontFamily = com.example.opendash.ui.theme.GeistFamily, fontSize = 14.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onExitNavigation()
                    routeViewModel.selectSaved(trail)
                    pendingTrailForNav = null
                    onNavigateToRoute()
                }) {
                    Text("Cancel & Load Trail", color = com.example.opendash.ui.theme.Warn, fontFamily = com.example.opendash.ui.theme.GeistFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingTrailForNav = null }) {
                    Text("Keep navigating", color = com.example.opendash.ui.theme.TextLo, fontFamily = com.example.opendash.ui.theme.GeistFamily)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 100.dp),
    ) {
        ScreenHeader(title = "Custom Trails")

        // Top actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OpenDashBtn(
                label = "Import GPX",
                onClick = { gpxLauncher.launch("*/*") },
                icon = OpenDashIcons.Download,
                variant = BtnVariant.Primary,
                size = BtnSize.Md,
                modifier = Modifier.weight(1f)
            )

            OpenDashBtn(
                label = "Record Trail",
                onClick = onStartRecording,
                icon = OpenDashIcons.Target,
                variant = BtnVariant.Secondary,
                size = BtnSize.Md,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        if (customTrails.isEmpty()) {
            EmptyTrails()
        } else {
            customTrails.forEach { trail ->
                TrailCard(
                    trail = trail,
                    onNavigate = {
                        if (isActiveNavigation) {
                            pendingTrailForNav = trail
                        } else {
                            routeViewModel.selectSaved(trail)
                            onNavigateToRoute()
                        }
                    },
                    onExport = {
                        val cacheFile = File(context.filesDir, "route_${trail.sid}.json")
                        if (cacheFile.exists()) {
                            try {
                                val json = cacheFile.readText()
                                val (routes, idx) = com.example.opendash.dash.nav.Route.routesFromJson(json)
                                val route = routes.getOrNull(idx) ?: routes.firstOrNull()
                                if (route != null) {
                                    routeViewModel.exportGpx(context, route, trail.name)
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    },
                    onLongPress = {
                        editingTrail = trail
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrailCard(
    trail: SavedLocation,
    onNavigate: () -> Unit,
    onExport: () -> Unit,
    onLongPress: () -> Unit,
) {
    OpenDashCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onNavigate,
                onLongClick = onLongPress
            ),
        padding = 10.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            TrailThumbnail(trail.sid, modifier = Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    trail.name,
                    color = TextHi,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Export/Download
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Surf2)
                        .clickable { onExport() },
                ) {
                    Icon(
                        OpenDashIcons.Download,
                        contentDescription = "Export GPX",
                        tint = TextMid,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTrails() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            OpenDashIcons.Route,
            contentDescription = null,
            tint = TextLo,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "No custom trails yet",
            color = TextHi,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistFamily
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Import a GPX file or record your own path.",
            color = TextLo,
            fontSize = 13.sp,
            fontFamily = GeistFamily
        )
    }
}

@Composable
private fun TrailThumbnail(trailId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var points by remember(trailId) { mutableStateOf<List<com.example.opendash.dash.nav.GeoPoint>?>(null) }
    val pathColor = Gold

    LaunchedEffect(trailId) {
        val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "route_${trailId}.json")
                if (file.exists()) {
                    val json = file.readText()
                    val (routes, _) = com.example.opendash.dash.nav.Route.routesFromJson(json)
                    routes.firstOrNull()?.geometry
                } else null
            } catch (e: Exception) {
                null
            }
        }
        points = pts
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surf2)
    ) {
        val pts = points
        if (pts != null && pts.size >= 2) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                var minLat = Double.MAX_VALUE
                var maxLat = -Double.MAX_VALUE
                var minLng = Double.MAX_VALUE
                var maxLng = -Double.MAX_VALUE
                for (p in pts) {
                    if (p.lat < minLat) minLat = p.lat
                    if (p.lat > maxLat) maxLat = p.lat
                    if (p.lng < minLng) minLng = p.lng
                    if (p.lng > maxLng) maxLng = p.lng
                }

                val latRange = maxLat - minLat
                val lngRange = maxLng - minLng
                if (latRange > 0.0 || lngRange > 0.0) {
                    val pad = size.width * 0.15f
                    val drawW = size.width - pad * 2
                    val drawH = size.height - pad * 2

                    val scale = if (latRange == 0.0) {
                        drawW / lngRange
                    } else if (lngRange == 0.0) {
                        drawH / latRange
                    } else {
                        val scaleX = drawW / lngRange
                        val scaleY = drawH / latRange
                        kotlin.math.min(scaleX, scaleY)
                    }

                    val path = androidx.compose.ui.graphics.Path()
                    var startX = 0f
                    var startY = 0f
                    var endX = 0f
                    var endY = 0f
                    pts.forEachIndexed { idx, gp ->
                        val x = pad + (gp.lng - minLng) * scale + (drawW - lngRange * scale) / 2
                        val y = pad + (maxLat - gp.lat) * scale + (drawH - latRange * scale) / 2
                        val xF = x.toFloat()
                        val yF = y.toFloat()
                        if (idx == 0) {
                            path.moveTo(xF, yF)
                            startX = xF
                            startY = yF
                        } else {
                            path.lineTo(xF, yF)
                            if (idx == pts.size - 1) {
                                endX = xF
                                endY = yF
                            }
                        }
                    }

                    drawPath(
                        path = path,
                        color = pathColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2.5.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                        )
                    )

                    // Draw start dot (Green)
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White,
                        radius = 3.5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(startX, startY)
                    )
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                        radius = 2.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(startX, startY)
                    )

                    // Draw end dot (Red)
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White,
                        radius = 3.5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(endX, endY)
                    )
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        radius = 2.2.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(endX, endY)
                    )
                }
            }
        } else {
            Icon(
                OpenDashIcons.Route,
                contentDescription = null,
                tint = pathColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

