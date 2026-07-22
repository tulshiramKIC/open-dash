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
    // MUST be the SAME instance the Dash screen uses, or live nav values (route/ETA) never
    // reach this screen. Passed down from AppNavigation.
    dashViewModel: com.example.opendash.viewmodel.DashViewModel = viewModel(),
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
    // Active-nav camera: false = heading-up follow (driving), true = whole-route overview.
    var navOverview by remember { mutableStateOf(false) }
    // Peers still render on this map; the join/leave controls live on Home.
    val groupRideState by com.example.opendash.data.GroupRide.state.collectAsState()


    // Measured height of the route bottom sheet — the floating map controls sit just
    // above it (a fixed offset overlaps the search card on tall sheets/small screens).
    val density = androidx.compose.ui.platform.LocalDensity.current
    var sheetHeight by remember { mutableStateOf(0.dp) }

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

    // Ensure the turn-by-turn engine is running whenever this screen shows active nav, so
    // the banner has live maneuvers even if the rider never opened the Dash screen.
    LaunchedEffect(isActiveNavigation) {
        if (isActiveNavigation) dashViewModel.startNavEngine()
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
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            // ── Full-screen map ──
            // Blue-dot compass beam: follows the phone's orientation while standing still,
            // hands off to GPS travel bearing once moving (camera never rotates with it).
            val deviceAzimuth by rememberDeviceAzimuth()
            val dotBearing =
                if ((dashUi.speedKmh ?: 0) < 5) deviceAzimuth else dashUi.riderBearing
            // Active navigation drives the camera heading-up and follows the rider (unless the
            // user opened the route overview). Alternates/bubbles are hidden while navigating.
            val navFollow = isActiveNavigation && !navOverview
            OpenDashMap(
                riderLat = riderLoc?.latitude,
                riderLng = riderLoc?.longitude,
                dest = dest?.let { d -> if (d.lat != null && d.lng != null) d.lat to d.lng else null },
                routePoints = routeState.route?.geometry.orEmpty(),
                routeCongestion = routeState.route?.congestion.orEmpty(),
                allRoutes = if (isActiveNavigation) emptyList() else routeState.routes.map { it.geometry },
                allRouteCongestions = if (isActiveNavigation) emptyList() else routeState.routes.map { it.congestion },
                routeDurations = if (isActiveNavigation) emptyList() else routeState.routes.map { routeDurationShort(it.totalSeconds) },
                selectedRouteIndex = routeState.selectedRouteIndex,
                onSelectRoute = { routeViewModel.selectRoute(it) },
                hasLocationPermission = hasLocation,
                fitRoute = if (isActiveNavigation) navOverview else !routeState.isRecordingRoute,
                navMode = navFollow,
                riderBearing = dashUi.riderBearing,
                cameraAheadOffset = navFollow,
                // Full-screen phone nav: a slightly pulled-back, tilted 3D view like Google
                // (the dash's own auto-zoom is tuned for the tiny round cluster, too close here).
                zoom = if (navFollow) 16.5 else null,
                navTiltDeg = if (navFollow) 50.0 else 0.0,
                maneuverPoint = if (navFollow && dashUi.maneuverLat != null && dashUi.maneuverLng != null)
                    dashUi.maneuverLat!! to dashUi.maneuverLng!! else null,
                maneuverType = dashUi.maneuverType,
                maneuverRoad = dashUi.maneuverRoad,
                recenterKey = recenterKey,
                satellite = satellite,
                night = false,
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
                                                color = MaterialTheme.colorScheme.outline, fontSize = 15.sp, fontFamily = GeistFamily,
                                            )
                                        },
                                        singleLine = true,
                                        trailingIcon = {
                                            if (routeState.searchQuery.isNotEmpty()) {
                                                Icon(
                                                    OpenDashIcons.X, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // Google-style green maneuver banner: turn arrow + distance + next road,
                    // plus a "Then <arrow>" tab for the maneuver after it.
                    NavManeuverBanner(
                        maneuverType = dashUi.maneuverType,
                        distanceM = dashUi.nextTurnM,
                        instruction = dashUi.maneuver,
                        secondManeuverType = dashUi.secondManeuverType,
                        destinationName = dashUi.destinationName.orEmpty()
                            .ifBlank { routeState.destination?.name.orEmpty() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    // Speed pill, bottom-left (Google-style), sitting just above the sheet.
                    NavSpeedPill(
                        speedKmh = dashUi.speedKmh,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, bottom = 96.dp + navBarDp),
                    )

                    // Bottom ETA sheet: exit (X) · ETA/dist/arrival · route overview toggle.
                    NavBottomSheet(
                        remainingKm = dashUi.remainingKm ?: routeState.route?.let { it.totalMeters / 1000.0 },
                        etaMinutes = dashUi.etaMinutes ?: routeState.route?.let { (it.totalSeconds / 60).toInt() },
                        overview = navOverview,
                        onExit = {
                            routeViewModel.clear()
                            dashViewModel.exitNavigation()
                            navOverview = false
                        },
                        onToggleOverview = { navOverview = !navOverview },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
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
                        .padding(end = 16.dp, bottom = when {
                            isActiveNavigation -> 96.dp
                            inRoutePreview -> sheetHeight + 16.dp
                            else -> 20.dp
                        }),
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
                    // Group ride + intercom entry points live on Home now.
                }
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
                                            color = MaterialTheme.colorScheme.outline, fontSize = 15.sp, fontFamily = GeistFamily,
                                        )
                                    },
                                    singleLine = true,
                                    trailingIcon = {
                                        if (routeState.searchQuery.isNotEmpty()) {
                                            Icon(
                                                OpenDashIcons.X, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onBackAction() }
                            ) {
                                Icon(OpenDashIcons.Cross, contentDescription = null, tint = Color(0xFF4285F4), modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Your location", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp, fontFamily = GeistFamily)
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
                                        .background(if (isDraggingThis) MaterialTheme.colorScheme.surface.copy(alpha = 0.8f) else Color.Transparent)
                                ) {
                                    Icon(
                                        if (isStop) OpenDashIcons.Circle else OpenDashIcons.LocationPin,
                                        contentDescription = null,
                                        tint = if (isStop) MaterialTheme.colorScheme.outline else Color(0xFFEA4335),
                                        modifier = Modifier.size(if (isStop) 14.dp else 17.dp).padding(horizontal = if (isStop) 1.5.dp else 0.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        displayTitle,
                                        color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp,
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
                                            tint = MaterialTheme.colorScheme.outline,
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
                                            tint = MaterialTheme.colorScheme.outline,
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
                        .background(MaterialTheme.colorScheme.surface)
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
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold,
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
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
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
                            color = if (routeState.routing) MaterialTheme.colorScheme.onSurfaceVariant else Ok,
                            fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily, letterSpacing = (-0.5).sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            listOfNotNull(
                                routeState.distanceText,
                                routeState.etaText?.let { "arrive $it" },
                            ).joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontFamily = GeistFamily,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    if (routeState.routes.size > 1) {
                        Text(
                            "Tap a route on the map to compare",
                            color = MaterialTheme.colorScheme.outline, fontSize = 12.sp, fontFamily = GeistFamily,
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
                            onClick = { navOverview = false; routeViewModel.startNavigation(); sent = true },
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
                                .background(if (voiceMode == VoiceMode.OFF) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer)
                                .clickable { voiceManager.setMode(nextVoice) },
                        ) {
                            Icon(
                                if (voiceMode == VoiceMode.OFF) OpenDashIcons.SpeakerOff else OpenDashIcons.Speaker,
                                contentDescription = "Voice: ${voiceMode.name.lowercase()}",
                                tint = if (voiceMode == VoiceMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
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
                        .background(MaterialTheme.colorScheme.surface)
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
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Line3,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.outline
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
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Trail Start Reached",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily
                )
            },
            text = {
                Text(
                    "You have reached the starting point of your custom trail. Have a safe ride!",
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = GeistFamily,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showStartAlert = false }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary, fontFamily = GeistFamily, fontWeight = FontWeight.Bold)
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
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = GeistFamily, maxLines = 1)
            if (sub.isNotBlank()) {
                Text(sub, color = MaterialTheme.colorScheme.outline, fontSize = 12.5.sp, fontFamily = GeistFamily, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
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
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        title = { Text("Save destination", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text(if (showNote) "Edit destination" else "Edit trail name", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (showNote) {
                    androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
private fun SaveTrailDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text("Save", color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        title = { Text("Save custom trail", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                Text("Enter a name for this recorded trail route:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
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
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Google-Maps-style active-navigation UI (turn banner + ETA sheet + speed pill).
// Driven by the live DashViewModel nav engine (maneuver/distance/ETA/speed).
// ─────────────────────────────────────────────────────────────────────────────

/** Google's teal navigation green for the top maneuver banner. */
private val NavGreen = Color(0xFF177867)

/** Maneuver-type → turn-arrow icon (same vocabulary the dash view uses). */
private fun maneuverArrowIcon(t: com.example.opendash.dash.nav.ManeuverType?) = when (t) {
    com.example.opendash.dash.nav.ManeuverType.TURN_LEFT   -> OpenDashIcons.TurnLeft
    com.example.opendash.dash.nav.ManeuverType.SHARP_LEFT  -> OpenDashIcons.TurnLeft
    com.example.opendash.dash.nav.ManeuverType.SLIGHT_LEFT -> OpenDashIcons.SlightLeft
    com.example.opendash.dash.nav.ManeuverType.TURN_RIGHT  -> OpenDashIcons.TurnRight
    com.example.opendash.dash.nav.ManeuverType.SHARP_RIGHT -> OpenDashIcons.TurnRight
    com.example.opendash.dash.nav.ManeuverType.SLIGHT_RIGHT-> OpenDashIcons.SlightRight
    com.example.opendash.dash.nav.ManeuverType.UTURN       -> OpenDashIcons.UTurn
    com.example.opendash.dash.nav.ManeuverType.ROUNDABOUT  -> OpenDashIcons.Swap
    com.example.opendash.dash.nav.ManeuverType.ARRIVE      -> OpenDashIcons.LocationPin
    else -> OpenDashIcons.ArrowUp
}

/** Distance to the next maneuver, Google-style ("260 m", "1.2 km"). */
private fun navDistanceText(m: Double?): String = when {
    m == null -> ""
    m >= 1000 -> "%.1f km".format(m / 1000.0)
    m >= 100  -> "${(m / 10).toInt() * 10} m"
    else      -> "${(m / 10).toInt() * 10 + 10} m".let { if (m < 10) "10 m" else it }
}

/** Extract the road name from a maneuver instruction ("Turn right onto Baif Rd" → "Baif Rd"). */
private fun navRoadText(instruction: String?, fallback: String): String {
    if (instruction.isNullOrBlank()) return fallback
    for (sep in listOf(" onto ", " on ", " toward ", " towards ")) {
        val i = instruction.indexOf(sep, ignoreCase = true)
        if (i >= 0) return instruction.substring(i + sep.length).trim().ifBlank { fallback }
    }
    return instruction
}

@Composable
private fun NavManeuverBanner(
    maneuverType: com.example.opendash.dash.nav.ManeuverType?,
    distanceM: Double?,
    instruction: String?,
    secondManeuverType: com.example.opendash.dash.nav.ManeuverType?,
    destinationName: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = NavGreen,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(
                    maneuverArrowIcon(maneuverType),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val dist = navDistanceText(distanceM)
                    if (dist.isNotBlank()) {
                        Text(
                            dist,
                            color = Color.White,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GeistFamily,
                            letterSpacing = (-0.5).sp,
                        )
                    }
                    Text(
                        navRoadText(instruction, destinationName.ifBlank { "Continue straight" }),
                        color = Color.White,
                        fontSize = if (dist.isBlank()) 20.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        lineHeight = 24.sp,
                    )
                }
            }
        }
        // "Then <next arrow>" tab, attached under the banner's left edge (Google-style).
        if (secondManeuverType != null) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                color = NavGreen.copy(alpha = 0.92f),
                modifier = Modifier.padding(start = 14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("Then", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, fontFamily = GeistFamily)
                    Spacer(Modifier.width(8.dp))
                    Icon(maneuverArrowIcon(secondManeuverType), contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun NavSpeedPill(speedKmh: Int?, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = modifier.size(64.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                speedKmh?.toString() ?: "--",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
                letterSpacing = (-0.5).sp,
            )
            Text(
                "km/h",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontFamily = GeistFamily,
            )
        }
    }
}

@Composable
private fun NavBottomSheet(
    remainingKm: Double?,
    etaMinutes: Int?,
    overview: Boolean,
    onExit: () -> Unit,
    onToggleOverview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBarDp = with(density) {
        androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp()
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 12.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 16.dp + navBarDp),
        ) {
            // Exit navigation (X).
            Surface(
                onClick = onExit,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(OpenDashIcons.X, contentDescription = "Exit navigation",
                        tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
            }

            // Center: ETA (min) big, then "x km · arrival clock".
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                val mins = etaMinutes
                val etaBig = when {
                    mins == null -> "-- min"
                    mins >= 60   -> "${mins / 60} h ${mins % 60} min"
                    else         -> "$mins min"
                }
                Text(
                    etaBig,
                    color = Ok,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                )
                val distText = remainingKm?.let {
                    if (it >= 10) "%.0f km".format(it) else "%.1f km".format(it)
                } ?: "-- km"
                val arrivalText = mins?.let {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.MINUTE, it)
                    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(cal.time)
                } ?: ""
                Text(
                    if (arrivalText.isNotBlank()) "$distText · $arrivalText" else distText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontFamily = GeistFamily,
                )
            }

            // Route overview toggle (route options button).
            Surface(
                onClick = onToggleOverview,
                shape = CircleShape,
                color = if (overview) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(OpenDashIcons.Swap, contentDescription = "Route overview",
                        tint = if (overview) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
