package com.example.opendash.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
    onNavigateToTrails: () -> Unit = {},
    onNavigateToDash: () -> Unit = {},
    routeViewModel: RouteViewModel = viewModel(),
) {
    val routeState by routeViewModel.state.collectAsState()
    val customTrailsEnabled by com.example.opendash.data.NavSettings.customTrailsEnabled.collectAsState()
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
    var searchingForStopIndex by remember { mutableStateOf(-1) }
    var isSearchingDestination by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var activeDragIdx by remember { mutableStateOf(-1) }
    var satellite by rememberSaveable { mutableStateOf(false) }
    var recenterKey by remember { mutableStateOf(0) }
    var showGroupRide by remember { mutableStateOf(false) }
    val groupRideState by com.example.opendash.data.GroupRide.state.collectAsState()
    // Measured height of the route bottom sheet — the floating map controls sit just
    // above it (a fixed offset overlaps the search card on tall sheets/small screens).
    val density = androidx.compose.ui.platform.LocalDensity.current
    var sheetHeight by remember { mutableStateOf(0.dp) }

    val dashViewModel: com.example.opendash.viewmodel.DashViewModel = viewModel()
    val dashUi by dashViewModel.ui.collectAsState()
    val isActiveNavigation = routeState.navigating

    var showStartAlert by remember { mutableStateOf(false) }
    var showedStartAlert by rememberSaveable { mutableStateOf(false) }

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

    LaunchedEffect(isActiveNavigation, riderLoc, routeState.trailStart) {
        if (!isActiveNavigation) {
            showedStartAlert = false
            showStartAlert = false
            return@LaunchedEffect
        }
        val startPt = routeState.trailStart
        val loc = riderLoc
        if (startPt != null && loc != null && !showedStartAlert) {
            val riderGeo = com.example.opendash.dash.nav.GeoPoint(loc.latitude, loc.longitude)
            val dist = com.example.opendash.dash.nav.GeoPoint.distMeters(riderGeo, startPt)
            if (dist <= 10.0) {
                showStartAlert = true
                showedStartAlert = true
            }
        }
    }

    // Keep search proximity-biased to where the rider actually is right now.
    LaunchedEffect(riderLoc) {
        riderLoc?.let { loc ->
            routeViewModel.updateOrigin(loc.latitude, loc.longitude)
        }
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
        searchingForStopIndex = -1
        sent = false
    }

    val onBackAction = {
        if (routeState.isCustomTrail) {
            exitPreview()
            onNavigateToTrails()
        } else {
            exitPreview()
        }
    }

    // In-screen back: route preview → explore.
    // When active navigation is running, back does NOT clear it — only the X button does.
    BackHandler(enabled = !isActiveNavigation && (inRoutePreview || searchingForStopIndex >= 0 || isSearchingDestination)) {
        if (isSearchingDestination) {
            isSearchingDestination = false
        } else if (searchingForStopIndex >= 0) {
            val currentStop = routeState.stops.getOrNull(searchingForStopIndex)
            if (currentStop != null && currentStop.name.isBlank()) {
                routeViewModel.removeStop(searchingForStopIndex)
            }
            searchingForStopIndex = -1
        } else if (routeState.isCustomTrail) {
            exitPreview()
            onNavigateToTrails()
        } else {
            exitPreview()
        }
    }
    // When active navigation is on, back handler just collapses search if open, otherwise no-op.
    BackHandler(enabled = isActiveNavigation && isSearchingDestination) {
        isSearchingDestination = false
    }

    run {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val navBarDp = with(density) {
            androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp()
        }
        Box(Modifier.fillMaxSize().background(MapBase)) {
            // ── Full-screen map ──
            // Blue-dot compass beam: follows the phone's orientation while standing still,
            // hands off to GPS travel bearing once moving (camera never rotates with it).
            val deviceAzimuth by rememberDeviceAzimuth()
            val dotBearing =
                if ((dashUi.speedKmh ?: 0) < 5) deviceAzimuth else dashUi.riderBearing
            OpenDashMap(
                riderLat = riderLoc?.latitude,
                riderLng = riderLoc?.longitude,
                dest = dest?.let { d -> if (d.lat != null && d.lng != null) d.lat to d.lng else null },
                routePoints = routeState.route?.geometry.orEmpty(),
                routeCongestion = routeState.route?.congestion.orEmpty(),
                allRoutes = routeState.routes.map { it.geometry },
                routeDurations = routeState.routes.map { routeDurationShort(it.totalSeconds) },
                selectedRouteIndex = routeState.selectedRouteIndex,
                onSelectRoute = { routeViewModel.selectRoute(it) },
                hasLocationPermission = hasLocation,
                fitRoute = !routeState.isRecordingRoute,
                recenterKey = recenterKey,
                satellite = satellite,
                showAttribution = false,
                markerBearing = dotBearing,
                // Grey the ridden part of the route while actually navigating.
                showTravelledGrey = isActiveNavigation,
                peers = groupRideState.peers,
                modifier = Modifier.fillMaxSize(),
                recordedPoints = routeState.recordedPoints,
                stops = routeState.stops.mapNotNull { if (it.lat != null && it.lng != null) com.example.opendash.dash.nav.GeoPoint(it.lat, it.lng) else null },
                isCustomTrail = routeState.isCustomTrail,
                trailStart = routeState.trailStart?.let { it.lat to it.lng }
            )

            if (isActiveNavigation) {
                // Active nav: floating card OR inline search overlay
                if (isSearchingDestination) {
                    // Inline search overlay for rerouting without leaving navigation
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OpenDashIconBtn(
                                OpenDashIcons.ChevronLeft,
                                onClick = { isSearchingDestination = false },
                                size = 46.dp,
                                modifier = Modifier.background(
                                    MaterialTheme.colorScheme.surface,
                                    CircleShape
                                ),
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 4.dp,
                                modifier = Modifier.weight(1f),
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
                                                "Search new destination",
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
                        }
                        // Search results shown inline
                        SuggestionList(routeState.searchResults) { place ->
                            routeViewModel.chooseSearchResult(place, stopIndex = -1)
                            isSearchingDestination = false
                        }
                    }
                } else {
                    val infiniteTransition = rememberInfiniteTransition(label = "route-pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        1f, 0.4f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                        label = "pulse",
                    )

                    // Floating Active Navigation Card
                    OpenDashCard(
                        glow = true,
                        padding = 14.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .clickable { onNavigateToDash() }
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Header row: pulsing dot + title + close btn
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Ok.copy(alpha = pulseAlpha))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "ACTIVE NAVIGATION",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GeistFamily,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                OpenDashIconBtn(
                                    OpenDashIcons.X,
                                    onClick = {
                                        routeViewModel.clear()
                                        dashViewModel.exitNavigation()
                                    },
                                    size = 32.dp,
                                    modifier = Modifier.background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape
                                    )
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            // Destination name
                            Text(
                                dashUi.destinationName.orEmpty().ifBlank { routeState.destination?.name.orEmpty() },
                                color = TextHi,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(12.dp))
                            // Stats row
                            val liveDistText = dashUi.remainingKm?.let {
                                if (it >= 10) "%.0f km".format(it) else "%.1f km".format(it)
                            } ?: routeState.distanceText
                            val liveDurText = dashUi.etaMinutes?.let {
                                if (it >= 60) "${it / 60}h ${it % 60}m" else "$it min"
                            } ?: routeState.durationText
                            val liveEtaText = dashUi.etaMinutes?.let {
                                val cal = java.util.Calendar.getInstance()
                                cal.add(java.util.Calendar.MINUTE, it)
                                "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                            } ?: routeState.etaText

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                liveDistText?.let { dist ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0x1A8F7C2E))
                                            .border(1.dp, Color(0x338F7C2E), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(OpenDashIcons.Road, null, tint = Gold, modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(dist, color = TextHi, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                        }
                                    }
                                }
                                liveDurText?.let { dur ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0x1A10B981))
                                            .border(1.dp, Color(0x3310B981), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(OpenDashIcons.Clock, null, tint = Ok, modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(dur, color = Ok, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                        }
                                    }
                                }
                                liveEtaText?.let { eta ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0x1A94A3B8))
                                            .border(1.dp, Color(0x3394A3B8), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(OpenDashIcons.Flag, null, tint = TextMid, modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(eta, color = TextHi, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }


            // ── Right-edge floating controls: layers over recenter, above the sheet ──
            if (routeState.searchResults.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
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
                    if (com.example.opendash.data.GroupRide.isConfigured) {
                        Surface(
                            onClick = { showGroupRide = true },
                            shape = CircleShape,
                            color = if (groupRideState.active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    OpenDashIcons.GroupRide, contentDescription = "Group ride",
                                    tint = if (groupRideState.active) MaterialTheme.colorScheme.onPrimaryContainer
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (showGroupRide) {
                GroupRideSheet(
                    riderLat = riderLoc?.latitude,
                    riderLng = riderLoc?.longitude,
                    onDismiss = { showGroupRide = false },
                )
            }

            if (!isActiveNavigation && (!inRoutePreview || searchingForStopIndex >= 0 || isSearchingDestination) && !routeState.isRecordingRoute && !routeState.isPreparingRouteRecording) {
                // ── Explore top bar: back button + search pill ──
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OpenDashIconBtn(
                            OpenDashIcons.ChevronLeft,
                            onClick = {
                                if (isSearchingDestination) {
                                    isSearchingDestination = false
                                } else if (searchingForStopIndex >= 0) {
                                    val currentStop = routeState.stops.getOrNull(searchingForStopIndex)
                                    if (currentStop != null && currentStop.name.isBlank()) {
                                        routeViewModel.removeStop(searchingForStopIndex)
                                    }
                                    searchingForStopIndex = -1
                                } else if (routeState.isCustomTrail) {
                                    exitPreview()
                                    onNavigateToTrails()
                                } else if (!isActiveNavigation) {
                                    onBack()
                                }
                                // If isActiveNavigation: do nothing, nav stays active
                            },
                            size = 46.dp,
                            modifier = Modifier.background(
                                MaterialTheme.colorScheme.surface,
                                CircleShape
                            ),
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier.weight(1f),
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


                    }
                    // ── Saved destinations (non-trail) shown below search bar ──
                    val displayedSaved = remember(savedList) {
                        savedList.filter { loc ->
                            !java.io.File(ctx.filesDir, "route_${loc.sid}.json").exists()
                        }
                    }
                    if (displayedSaved.isNotEmpty() && routeState.searchQuery.isEmpty() && routeState.searchResults.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                displayedSaved.forEach { loc ->
                                    PlaceRow(
                                        icon = OpenDashIcons.LocationPin,
                                        title = loc.name,
                                        sub = loc.note.ifBlank { "%.4f, %.4f".format(loc.lat, loc.lng) },
                                        onClick = {
                                            if (isSearchingDestination || searchingForStopIndex >= 0) {
                                                val place = com.example.opendash.data.Place(name = loc.name, address = loc.note, lat = loc.lat, lng = loc.lng)
                                                if (isSearchingDestination) {
                                                    routeViewModel.chooseSearchResult(place, stopIndex = -1)
                                                    isSearchingDestination = false
                                                } else {
                                                    routeViewModel.chooseSearchResult(place, stopIndex = searchingForStopIndex)
                                                    searchingForStopIndex = -1
                                                }
                                            } else {
                                                routeViewModel.selectSaved(loc)
                                            }
                                        },
                                        onEdit = { editing = loc },
                                    )
                                }
                            }
                        }
                    }
                    SuggestionList(routeState.searchResults) {
                        if (isSearchingDestination) {
                            routeViewModel.chooseSearchResult(it, stopIndex = -1)
                            isSearchingDestination = false
                        } else {
                            routeViewModel.chooseSearchResult(it, stopIndex = searchingForStopIndex)
                            searchingForStopIndex = -1
                        }
                    }
                }
            } else if (!isActiveNavigation && inRoutePreview) {
                // ── Route preview top bar: back + from/to + travel mode ──
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OpenDashIconBtn(
                            OpenDashIcons.ChevronLeft,
                            onClick = onBackAction,
                            size = 46.dp,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .background(Bg1.copy(alpha = 0.95f), CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Bg1.copy(alpha = 0.96f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onBackAction() }
                            ) {
                                Icon(OpenDashIcons.Cross, contentDescription = null, tint = Color(0xFF4285F4), modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Your location", color = TextHi, fontSize = 13.5.sp, fontFamily = GeistFamily)
                            }
                            val waypoints = routeState.stops + listOfNotNull(dest)
                            waypoints.forEachIndexed { idx, wp ->
                                val isStop = idx < routeState.stops.size
                                val displayTitle = if (wp.name.isBlank()) {
                                    if (isStop) "Choose stop…" else "Choose destination…"
                                } else wp.name
                                val isDraggingThis = activeDragIdx == idx
                                OpenDashDivider(Modifier.padding(vertical = 8.dp, horizontal = 2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset {
                                            if (isDraggingThis) IntOffset(0, dragOffsetY.roundToInt()) else IntOffset.Zero
                                        }
                                        .background(if (isDraggingThis) Bg1.copy(alpha = 0.8f) else Color.Transparent)
                                ) {
                                    Icon(
                                        if (isStop) OpenDashIcons.Circle else OpenDashIcons.LocationPin,
                                        contentDescription = null,
                                        tint = if (isStop) TextLo else Color(0xFFEA4335),
                                        modifier = Modifier.size(if (isStop) 14.dp else 17.dp).padding(horizontal = if (isStop) 1.5.dp else 0.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        displayTitle,
                                        color = TextHi, fontSize = 13.5.sp,
                                        fontWeight = if (isStop) FontWeight.Normal else FontWeight.Bold,
                                        fontFamily = GeistFamily,
                                        modifier = Modifier.weight(1f).clickable {
                                            if (isStop) {
                                                searchingForStopIndex = idx
                                                isSearchingDestination = false
                                                routeViewModel.onSearchQueryChange(wp.name)
                                            } else {
                                                searchingForStopIndex = -1
                                                isSearchingDestination = true
                                                routeViewModel.onSearchQueryChange(if (wp.name == "Shared location" || wp.name == "Choose destination…") "" else wp.name)
                                            }
                                        },
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (waypoints.size >= 2) {
                                        val dragThresholdPx = with(density) { 38.dp.toPx() }
                                        Icon(
                                            OpenDashIcons.Reorder,
                                            contentDescription = "Drag to reorder",
                                            tint = TextLo,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .pointerInput(idx, waypoints.size) {
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            dragOffsetY = 0f
                                                            activeDragIdx = idx
                                                        },
                                                        onDragEnd = {
                                                            activeDragIdx = -1
                                                        },
                                                        onDragCancel = {
                                                            activeDragIdx = -1
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            if (activeDragIdx == idx) {
                                                                dragOffsetY += dragAmount.y
                                                                if (dragOffsetY > dragThresholdPx && idx < waypoints.size - 1) {
                                                                    routeViewModel.moveWaypoint(idx, idx + 1)
                                                                    activeDragIdx = idx + 1
                                                                    dragOffsetY = 0f
                                                                } else if (dragOffsetY < -dragThresholdPx && idx > 0) {
                                                                    routeViewModel.moveWaypoint(idx, idx - 1)
                                                                    activeDragIdx = idx - 1
                                                                    dragOffsetY = 0f
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                        )
                                    }
                                    if (isStop) {
                                        Spacer(Modifier.width(12.dp))
                                        Icon(
                                            OpenDashIcons.X,
                                            contentDescription = "Remove stop",
                                            tint = TextLo,
                                            modifier = Modifier.size(18.dp).clickable { routeViewModel.removeStop(idx) }
                                        )
                                    }
                                }
                            }
                        }
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
                        .padding(top = 8.dp, bottom = 18.dp + navBarDp),
                ) {
                    Box(
                        Modifier
                            .width(36.dp).height(4.dp)
                            .clip(CircleShape).background(Line3)
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(12.dp))

                    // Destination + Car/Bike icon toggles + save (icons only, on the right).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (routeState.isResolving) "Resolving…" else destName,
                            color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily, letterSpacing = (-0.36).sp, maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (!routeState.isCustomTrail) {
                            TravelModeIcon(
                                OpenDashIcons.Car,
                                selected = routeState.travelMode == Router.TravelMode.CAR,
                                onClick = { routeViewModel.selectTravelMode(Router.TravelMode.CAR) },
                            )
                            Spacer(Modifier.width(6.dp))
                            TravelModeIcon(
                                OpenDashIcons.Motor,
                                selected = routeState.travelMode == Router.TravelMode.BIKE,
                                onClick = { routeViewModel.selectTravelMode(Router.TravelMode.BIKE) },
                            )
                        }
                        if (canStart && !routeState.isCustomTrail) {
                            Spacer(Modifier.width(6.dp))
                            OpenDashIconBtn(
                                OpenDashIcons.Save,
                                onClick = { showSave = true },
                                size = 40.dp,
                                modifier = Modifier.background(Surf1, CircleShape),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Headline: green duration, then distance · ETA
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
                    if (routeState.routes.size > 1) {
                        Text(
                            "Tap a route on the map to compare",
                            color = TextLo, fontSize = 12.sp, fontFamily = GeistFamily,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Start (fills width) + compact Voice icon.
                    val isAwayFromTrailStart = remember(routeState.isCustomTrail, routeState.trailStart, riderLoc) {
                        if (routeState.isCustomTrail && routeState.trailStart != null && riderLoc != null) {
                            val riderGeo = com.example.opendash.dash.nav.GeoPoint(riderLoc!!.latitude, riderLoc!!.longitude)
                            com.example.opendash.dash.nav.GeoPoint.distMeters(riderGeo, routeState.trailStart!!) > 100.0
                        } else false
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OpenDashBtn(
                            label = when {
                                sent                   -> "Starting…"
                                routeState.isResolving -> "Resolving…"
                                routeState.routing     -> "Finding route…"
                                isAwayFromTrailStart   -> "Head to trail starting point"
                                else                   -> "Start"
                            },
                            onClick = { routeViewModel.startNavigation(); sent = true },
                            icon = if (sent) OpenDashIcons.Check else OpenDashIcons.Navi,
                            variant = if (sent) BtnVariant.Secondary else BtnVariant.Primary,
                            size = BtnSize.Md,
                            enabled = !sent && canStart,
                            modifier = Modifier.weight(1f),
                        )
                        if (canStart && !routeState.isCustomTrail) {
                            Spacer(Modifier.width(8.dp))
                            OpenDashBtn(
                                label = "Add stops",
                                onClick = {
                                    routeViewModel.addBlankStop()
                                    searchingForStopIndex = routeState.stops.size
                                    routeViewModel.onSearchQueryChange("")
                                },
                                icon = OpenDashIcons.Plus,
                                variant = BtnVariant.Secondary,
                                size = BtnSize.Md,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        val nextVoice = when (voiceMode) {
                            VoiceMode.OFF   -> VoiceMode.CHIME
                            VoiceMode.CHIME -> VoiceMode.FULL
                            VoiceMode.FULL  -> VoiceMode.OFF
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(if (voiceMode == VoiceMode.OFF) Surf1 else GoldTint)
                                .clickable { voiceManager.setMode(nextVoice) },
                        ) {
                            Icon(
                                if (voiceMode == VoiceMode.OFF) OpenDashIcons.SpeakerOff else OpenDashIcons.Speaker,
                                contentDescription = "Voice: ${voiceMode.name.lowercase()}",
                                tint = if (voiceMode == VoiceMode.OFF) TextMid else Gold,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            if (routeState.isRecordingRoute) {
                // Recording banner at the top
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                        .fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE5341F))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Recording Trail...",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily
                            )
                        }
                        
                        val distanceMeters = remember(routeState.recordedPoints) {
                            if (routeState.recordedPoints.size < 2) 0.0
                            else {
                                var total = 0.0
                                for (i in 1 until routeState.recordedPoints.size) {
                                    val p1 = routeState.recordedPoints[i - 1]
                                    val p2 = routeState.recordedPoints[i]
                                    val res = FloatArray(1)
                                    android.location.Location.distanceBetween(p1.lat, p1.lng, p2.lat, p2.lng, res)
                                    total += res[0]
                                }
                                total
                            }
                        }
                        Text(
                            "%.2f km".format(distanceMeters / 1000.0),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GeistFamily
                        )
                    }
                }
                
                OpenDashBtn(
                    label = "Stop & Save",
                    onClick = {
                        routeViewModel.stopRecordingRoute()
                        if (routeState.recordedPoints.size >= 2) {
                            routeViewModel.saveRecordedRoute(routeState.recordingTrailName)
                        } else {
                            routeViewModel.clear()
                        }
                        onNavigateToTrails()
                    },
                    icon = OpenDashIcons.X,
                    variant = BtnVariant.Primary,
                    size = BtnSize.Md,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .width(180.dp)
                )
            }

            if (routeState.isPreparingRouteRecording) {
                // Back button at the top left
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    OpenDashIconBtn(
                        OpenDashIcons.ChevronLeft,
                        onClick = { routeViewModel.clear() },
                        size = 46.dp,
                        modifier = Modifier.background(
                            MaterialTheme.colorScheme.surface,
                            CircleShape
                        ),
                    )
                }

                // Pre-recording configuration bottom card
                var customTrailName by remember {
                    mutableStateOf("Trail_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()))
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { sheetHeight = with(density) { it.size.height.toDp() } }
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Bg1)
                        .imePadding()
                        .padding(horizontal = 18.dp)
                        .padding(top = 16.dp, bottom = 24.dp + navBarDp),
                ) {
                    Box(
                        Modifier
                            .width(36.dp).height(4.dp)
                            .clip(CircleShape).background(Line3)
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(18.dp))

                    androidx.compose.material3.OutlinedTextField(
                        value = customTrailName,
                        onValueChange = { customTrailName = it },
                        label = { Text("Trail name") },
                        singleLine = true,
                        placeholder = { Text("e.g. Secret mountain shortcut") },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextHi,
                            unfocusedTextColor = TextHi,
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = Line3,
                            focusedLabelColor = Gold,
                            unfocusedLabelColor = TextLo
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = GeistFamily),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(Modifier.height(20.dp))
                    
                    OpenDashBtn(
                        label = "Start Recording",
                        onClick = { routeViewModel.startRecordingRoute(customTrailName) },
                        icon = OpenDashIcons.Target,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Md,
                        enabled = customTrailName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showStartAlert) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStartAlert = false },
            containerColor = Bg1,
            title = {
                Text(
                    "Trail Start Reached",
                    color = TextHi,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily
                )
            },
            text = {
                Text(
                    "You have reached the starting point of your custom trail. Have a safe ride!",
                    color = TextLo,
                    fontFamily = GeistFamily,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showStartAlert = false }) {
                    Text("OK", color = Gold, fontFamily = GeistFamily, fontWeight = FontWeight.Bold)
                }
            }
        )
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
@OptIn(ExperimentalFoundationApi::class)
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
            .then(
                if (onEdit != null)
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onEdit,
                    )
                else
                    Modifier.clickable(onClick = onClick)
            )
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

/** Icon-only travel mode toggle (Car / Bike) for the destination header row. */
@Composable
private fun TravelModeIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Surf1)
            .clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else TextMid,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Short duration label for map bubbles, e.g. "11 min" / "1 h 5 m". */
private fun routeDurationShort(totalSeconds: Double): String {
    val mins = (totalSeconds / 60).toInt()
    return if (mins >= 60) "${mins / 60} h ${mins % 60} m" else "$mins min"
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
internal fun EditLocationDialog(
    loc: com.example.opendash.data.SavedLocation,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    showNote: Boolean = true
) {
    var name by remember { mutableStateOf(loc.name) }
    var note by remember { mutableStateOf(loc.note) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) Gold else TextLo) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = Alert) } },
        title = { Text(if (showNote) "Edit destination" else "Edit trail name", color = TextHi) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (showNote) {
                    androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun SaveTrailDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text("Save", color = if (name.isNotBlank()) Gold else TextLo)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMid)
            }
        },
        title = { Text("Save custom trail", color = TextHi) },
        text = {
            Column {
                Text("Enter a name for this recorded trail route:", color = TextMid, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Trail name") },
                    singleLine = true,
                    placeholder = { Text("e.g. Secret mountain shortcut") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = Surf1,
    )
}
