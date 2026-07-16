package com.example.opendash.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.dash.nav.Router
import com.example.opendash.dash.nav.VoiceMode
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.RouteViewModel

/**
 * Navigate tab, structured like Google Maps:
 *  - Explore: full-screen map, search pill, layers/recenter, directions FAB.
 *  - Directions: full-screen from/to panel with travel mode + saved places.
 *  - Route preview: map + bottom sheet (duration, alternatives, Start).
 */
@Composable
fun RouteScreen(
    onBack: () -> Unit,
    onSentToDash: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
) {
    val routeState by routeViewModel.state.collectAsState()
    val dest       = routeState.destination
    val destName   = dest?.name?.ifBlank { "Shared location" } ?: "Shared location"
    val destSub    = when {
        dest?.lat != null && dest.lng != null ->
            "%.5f, %.5f".format(dest.lat, dest.lng)
        dest?.url != null -> "Maps link"
        else              -> ""
    }

    val savedList by routeViewModel.saved.collectAsState()
    val ctx = LocalContext.current
    val voiceManager = remember { com.example.opendash.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()

    var sent by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.example.opendash.data.SavedLocation?>(null) }
    var satellite by rememberSaveable { mutableStateOf(false) }
    var recenterKey by remember { mutableStateOf(0) }
    // Measured height of the route bottom sheet — the floating map controls sit just
    // above it (a fixed offset overlaps the search card on tall sheets/small screens).
    val density = androidx.compose.ui.platform.LocalDensity.current
    var sheetHeight by remember { mutableStateOf(0.dp) }

    // Live rider position for the "you are here" blue dot (like Google Maps).
    fun locationGranted(): Boolean = listOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    ).any {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val tracker = remember { com.example.opendash.dash.map.LocationTracker(ctx) }
    val riderLoc by tracker.location.collectAsState()
    var hasLocation by remember { mutableStateOf(locationGranted()) }
    val locPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocation = grants.values.any { it }
        if (hasLocation) tracker.start()
    }
    DisposableEffect(Unit) {
        if (hasLocation) tracker.start()
        else locPermLauncher.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
        onDispose { tracker.stop() }
    }

    // Keep search proximity-biased to where the rider actually is right now.
    LaunchedEffect(riderLoc) {
        riderLoc?.let { routeViewModel.updateOrigin(it.latitude, it.longitude) }
    }

    val canStart = dest?.lat != null && dest.lng != null && !routeState.isResolving
    val inRoutePreview = dest != null || routeState.isResolving

    LaunchedEffect(sent) {
        if (sent) {
            kotlinx.coroutines.delay(650)
            onSentToDash(destName)
        }
    }

    fun exitPreview() {
        routeViewModel.clear()
        sent = false
    }

    // In-screen back: route preview → explore.
    BackHandler(enabled = inRoutePreview) { exitPreview() }

    run {
        Box(Modifier.fillMaxSize().background(MapBase)) {
            // ── Full-screen map ──
            OpenDashMap(
                riderLat = riderLoc?.latitude,
                riderLng = riderLoc?.longitude,
                dest = dest?.let { d -> if (d.lat != null && d.lng != null) d.lat to d.lng else null },
                routePoints = routeState.route?.geometry.orEmpty(),
                alternateRoutes = routeState.routes
                    .filterIndexed { i, _ -> i != routeState.selectedRouteIndex }
                    .map { it.geometry },
                hasLocationPermission = hasLocation,
                fitRoute = true,
                recenterKey = recenterKey,
                satellite = satellite,
                modifier = Modifier.fillMaxSize(),
            )

            // ── Right-edge floating controls: layers over recenter, above the sheet ──
            if (routeState.searchResults.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = if (inRoutePreview) sheetHeight + 16.dp else 20.dp),
                ) {
                    Surface(
                        onClick = { satellite = !satellite },
                        shape = CircleShape,
                        color = if (satellite) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                OpenDashIcons.Layers, contentDescription = "Map layers",
                                tint = if (satellite) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    if (riderLoc != null) {
                        Surface(
                            onClick = { recenterKey++ },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    OpenDashIcons.Recenter, contentDescription = "My location",
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (!inRoutePreview) {
                // ── Explore top bar: Google-style search pill ──
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(16.dp))
                            Icon(
                                OpenDashIcons.Search, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp),
                            )
                            androidx.compose.material3.TextField(
                                value = routeState.searchQuery,
                                onValueChange = { routeViewModel.onSearchQueryChange(it) },
                                placeholder = {
                                    Text(
                                        if (routeState.isResolving) "Resolving Maps link…" else "Search here",
                                        color = TextLo, fontSize = 15.sp, fontFamily = GeistFamily,
                                    )
                                },
                                singleLine = true,
                                trailingIcon = {
                                    if (routeState.searchQuery.isNotEmpty()) {
                                        Icon(
                                            OpenDashIcons.X, contentDescription = "Clear", tint = TextMid,
                                            modifier = Modifier.size(20.dp).clickable { routeViewModel.onSearchQueryChange("") },
                                        )
                                    }
                                },
                                colors = searchFieldColors(),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = GeistFamily),
                                modifier = Modifier.weight(1f).height(52.dp),
                            )
                        }
                    }
                    SuggestionList(routeState.searchResults) { routeViewModel.chooseSearchResult(it) }
                }
            } else {
                // ── Route preview top bar: back + from/to + travel mode ──
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OpenDashIconBtn(
                            OpenDashIcons.ChevronLeft,
                            onClick = { exitPreview() },
                            size = 46.dp,
                            modifier = Modifier.background(Bg1.copy(alpha = 0.95f), CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Bg1.copy(alpha = 0.96f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(OpenDashIcons.Cross, contentDescription = null, tint = Color(0xFF4285F4), modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Your location", color = TextHi, fontSize = 13.5.sp, fontFamily = GeistFamily)
                            }
                            OpenDashDivider(Modifier.padding(vertical = 8.dp, horizontal = 2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(OpenDashIcons.LocationPin, contentDescription = null, tint = Color(0xFFEA4335), modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (routeState.isResolving) "Resolving…" else destName,
                                    color = TextHi, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                                    fontFamily = GeistFamily, maxLines = 1,
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 56.dp, top = 8.dp),
                    ) {
                        TravelModeChip(
                            mode = Router.TravelMode.CAR,
                            selected = routeState.travelMode == Router.TravelMode.CAR,
                            onClick = { routeViewModel.selectTravelMode(Router.TravelMode.CAR) },
                        )
                        TravelModeChip(
                            mode = Router.TravelMode.BIKE,
                            selected = routeState.travelMode == Router.TravelMode.BIKE,
                            onClick = { routeViewModel.selectTravelMode(Router.TravelMode.BIKE) },
                        )
                    }
                }

                // ── Bottom sheet: destination, duration, alternatives, Start ──
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { sheetHeight = with(density) { it.size.height.toDp() } }
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Bg1)
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp)
                        .padding(top = 8.dp, bottom = 18.dp),
                ) {
                    Box(
                        Modifier
                            .width(36.dp).height(4.dp)
                            .clip(CircleShape).background(Line3)
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (routeState.isResolving) "Resolving…" else destName,
                                color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily, letterSpacing = (-0.36).sp, maxLines = 1,
                            )
                            if (destSub.isNotBlank()) {
                                Text(destSub, color = TextLo, fontSize = 12.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        if (canStart) {
                            OpenDashIconBtn(
                                OpenDashIcons.Pin,
                                onClick = { showSave = true },
                                size = 40.dp,
                                modifier = Modifier.background(Surf1, CircleShape),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Google-style headline: green duration, then distance · ETA
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            when {
                                routeState.routing -> "Finding route…"
                                else               -> routeState.durationText ?: "—"
                            },
                            color = if (routeState.routing) TextMid else Ok,
                            fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily, letterSpacing = (-0.5).sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            listOfNotNull(
                                routeState.distanceText,
                                routeState.etaText?.let { "arrive $it" },
                            ).joinToString(" · "),
                            color = TextMid, fontSize = 14.sp, fontFamily = GeistFamily,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    // Route alternatives — tap to switch, like Google's "via …" rows.
                    if (routeState.routes.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        routeState.routes.forEachIndexed { i, r ->
                            val selected = i == routeState.selectedRouteIndex
                            val mins = (r.totalSeconds / 60).toInt()
                            val dur = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                            val km = "%.0f km".format(r.totalMeters / 1000.0)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) GoldTint2 else Color.Transparent)
                                    .clickable { routeViewModel.selectRoute(i) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Box(
                                    Modifier.size(9.dp).clip(CircleShape)
                                        .background(if (selected) Gold else Line3)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (i == 0) "Fastest route" else "Alternative $i",
                                    color = if (selected) TextHi else TextMid,
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$dur · $km",
                                    color = if (selected) Gold else TextLo,
                                    fontSize = 12.5.sp, fontFamily = GeistMonoFamily,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Start + voice mode (tap the pill to cycle Off → Chime → Full TTS)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OpenDashBtn(
                            label = when {
                                sent                   -> "Starting navigation…"
                                routeState.isResolving -> "Resolving destination…"
                                routeState.routing     -> "Finding route…"
                                else                   -> "Start"
                            },
                            onClick = { sent = true },
                            icon = if (sent) OpenDashIcons.Check else OpenDashIcons.Navi,
                            variant = if (sent) BtnVariant.Secondary else BtnVariant.Primary,
                            size = BtnSize.Lg,
                            enabled = !sent && canStart,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        val nextVoice = when (voiceMode) {
                            VoiceMode.OFF   -> VoiceMode.CHIME
                            VoiceMode.CHIME -> VoiceMode.FULL
                            VoiceMode.FULL  -> VoiceMode.OFF
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Surf1)
                                .border(1.dp, if (voiceMode == VoiceMode.OFF) Line else Gold, CircleShape)
                                .clickable { voiceManager.setMode(nextVoice) }
                                .padding(horizontal = 16.dp, vertical = 15.dp),
                        ) {
                            Icon(
                                if (voiceMode == VoiceMode.OFF) OpenDashIcons.SpeakerOff else OpenDashIcons.Speaker,
                                contentDescription = "Voice guidance",
                                tint = when (voiceMode) {
                                    VoiceMode.OFF   -> TextLo
                                    VoiceMode.CHIME -> TextMid
                                    VoiceMode.FULL  -> Gold
                                },
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (voiceMode) {
                                    VoiceMode.OFF   -> "Off"
                                    VoiceMode.CHIME -> "Chime"
                                    VoiceMode.FULL  -> "Voice"
                                },
                                color = if (voiceMode == VoiceMode.OFF) TextLo else Gold,
                                fontSize = 12.5.sp, fontFamily = GeistMonoFamily,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSave) SaveLocationDialog(
        defaultName = destName,
        onSave = { name, note -> routeViewModel.saveCurrentDestination(name, note); showSave = false },
        onDismiss = { showSave = false },
    )
    editing?.let { loc ->
        EditLocationDialog(
            loc = loc,
            onSave = { name, note -> routeViewModel.renameSaved(loc, name, note); editing = null },
            onDelete = { routeViewModel.deleteSaved(loc); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** Google-style place list row: round tonal icon, name, address. */
@Composable
private fun PlaceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = GeistFamily, maxLines = 1)
            if (sub.isNotBlank()) {
                Text(sub, color = TextLo, fontSize = 12.5.sp, fontFamily = GeistFamily, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (onEdit != null) {
            Icon(
                OpenDashIcons.Edit, contentDescription = "Edit", tint = TextLo,
                modifier = Modifier.size(18.dp).clickable(onClick = onEdit),
            )
        }
    }
}

/** Suggestion dropdown under the explore search pill. */
@Composable
private fun SuggestionList(
    results: List<com.example.opendash.data.Place>,
    onPick: (com.example.opendash.data.Place) -> Unit,
) {
    if (results.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth(),
    ) {
        Column(
            Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            results.forEach { place ->
                PlaceRow(
                    icon = OpenDashIcons.LocationPin,
                    title = place.name,
                    sub = place.address,
                    onClick = { onPick(place) },
                )
            }
        }
    }
}

@Composable
private fun searchFieldColors() = androidx.compose.material3.TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun TravelModeChip(
    mode: Router.TravelMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Bg1.copy(alpha = 0.94f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            if (mode == Router.TravelMode.CAR) OpenDashIcons.Car else OpenDashIcons.Motor,
            contentDescription = mode.label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else TextMid,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            mode.label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else TextMid,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = GeistFamily,
        )
    }
}

@Composable
private fun SaveLocationDialog(defaultName: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) Gold else TextLo) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Save destination", color = TextHi) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun EditLocationDialog(loc: com.example.opendash.data.SavedLocation, onSave: (String, String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(loc.name) }
    var note by remember { mutableStateOf(loc.note) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) Gold else TextLo) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = Alert) } },
        title = { Text("Edit destination", color = TextHi) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = Surf1,
    )
}
