package com.example.opendash.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            onNavigateToRoute()
        }
    }

    // ── Confirmation: cancel navigation to load custom trail ──
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
                    onDelete = {
                        routeViewModel.deleteSaved(trail)
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun TrailCard(
    trail: SavedLocation,
    onNavigate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = com.example.opendash.ui.theme.Bg1,
            icon = {
                Icon(
                    OpenDashIcons.Trash,
                    contentDescription = null,
                    tint = Danger,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    "Delete trail?",
                    color = TextHi,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily
                )
            },
            text = {
                Text(
                    "\"${trail.name}\" will be permanently deleted. This cannot be undone.",
                    color = TextLo,
                    fontFamily = GeistFamily,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Delete", color = Danger, fontFamily = GeistFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextLo, fontFamily = GeistFamily)
                }
            }
        )
    }

    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surf2),
                ) {
                    Icon(
                        OpenDashIcons.Route,
                        contentDescription = null,
                        tint = Gold,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        trail.name,
                        color = TextHi,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily
                    )
                    Text(
                        trail.note.ifBlank { "Custom recorded path" },
                        color = TextLo,
                        fontSize = 12.sp,
                        fontFamily = GeistFamily
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Navigate/Load
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Surf2)
                        .clickable { onNavigate() },
                ) {
                    Icon(
                        OpenDashIcons.Navi,
                        contentDescription = "Navigate trail",
                        tint = TextHi,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Export/Share
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Surf2)
                        .clickable { onExport() },
                ) {
                    Icon(
                        OpenDashIcons.Share,
                        contentDescription = "Export GPX",
                        tint = TextLo,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Delete
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Surf2)
                        .clickable { showDeleteConfirm = true },
                ) {
                    Icon(
                        OpenDashIcons.Trash,
                        contentDescription = "Delete trail",
                        tint = Danger,
                        modifier = Modifier.size(16.dp)
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
