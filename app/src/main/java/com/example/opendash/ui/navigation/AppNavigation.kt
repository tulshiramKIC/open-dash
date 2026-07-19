package com.example.opendash.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.screens.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.AppViewModel
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.ConnectionState
import com.example.opendash.viewmodel.DashViewModel
import com.example.opendash.viewmodel.RouteViewModel

sealed class Screen(val route: String) {
    object Home     : Screen("home")
    object Expenses : Screen("expenses")
    object Route    : Screen("route")
    object Trails   : Screen("trails")
    object Dash     : Screen("dash")
    object Garage   : Screen("garage")
    object Rides    : Screen("rides")
    object Settings : Screen("settings")
    object OfflineMaps : Screen("offline_maps")
}

private data class NavTab(
    val screen: Screen,
    val icon: ImageVector?,
    val label: String,
    val activeIconRes: Int? = null,
    val inactiveIconRes: Int? = null,
)

private val bottomTabs = listOf(
    NavTab(Screen.Home,   OpenDashIcons.Home,    "Home"),
    NavTab(Screen.Route,  OpenDashIcons.Navi,    "Navigate"),
    NavTab(Screen.Trails, null,                  "Trails", activeIconRes = com.example.opendash.R.drawable.ic_custom_trail, inactiveIconRes = com.example.opendash.R.drawable.ic_custom_trail_inactive),
    NavTab(Screen.Expenses, OpenDashIcons.Chart, "Expenses"),
    NavTab(Screen.Garage, OpenDashIcons.Motor,  "Garage"),
    NavTab(Screen.Settings, OpenDashIcons.Gear, "More"),
)

private val bottomRoutes = bottomTabs.map { it.screen.route }
private val homeChildRoutes = listOf(Screen.Dash.route, Screen.Rides.route)
private val shellRoutes = bottomRoutes + homeChildRoutes

@Composable
fun AppNavigation(
    appViewModel: AppViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
    routeViewModel: RouteViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val customTrailsEnabled by com.example.opendash.data.NavSettings.customTrailsEnabled.collectAsState()
    val activeBottomTabs = remember(customTrailsEnabled) {
        if (customTrailsEnabled) bottomTabs
        else bottomTabs.filter { it.screen != Screen.Trails }
    }

    val showBottomNav = currentRoute in shellRoutes && currentRoute != Screen.Route.route
    // No tab-swipe on the Route tab — horizontal drags there pan the map.
    val canSwipeBottomTabs = currentRoute in bottomRoutes && currentRoute != Screen.Route.route

    val garageTab by appViewModel.garageTab.collectAsState()
    val routeState by routeViewModel.state.collectAsState()

    // Single source of truth for connection status: the real dash stage. Fixes Home
    // claiming "Connected" while the Dash screen says it isn't.
    val dashUi by dashViewModel.ui.collectAsState()
    val conn = when (dashUi.stage) {
        ConnStage.STREAMING -> ConnectionState.Connected
        ConnStage.WIFI, ConnStage.AUTH -> ConnectionState.Searching
        else -> ConnectionState.Offline
    }

    // Prefetch map tiles the moment a destination resolves (internet still reachable here)
    LaunchedEffect(routeState.destination?.lat, routeState.destination?.lng) {
        val d = routeState.destination
        if (d?.lat != null && d.lng != null) dashViewModel.prefetchTiles(d.lat, d.lng)
    }

    // Auto-navigate to Route when a Maps share arrives
    LaunchedEffect(routeState.pendingNavigate, currentRoute) {
        if (routeState.pendingNavigate && currentRoute != null) {
            if (currentRoute != Screen.Route.route) {
                navController.navigate(Screen.Route.route) { launchSingleTop = true }
            }
            routeViewModel.onNavigated()
        }
    }

    fun navigateHome() {
        // Home is the start destination, so it's always below us in the stack — popping
        // back to it is the reliable move. navigate(home) { popUpTo(home) } silently
        // no-ops from some routes, which left the Home tab dead.
        if (!navController.popBackStack(Screen.Home.route, false)) {
            navController.navigate(Screen.Home.route) { launchSingleTop = true }
        }
    }

    fun navigateTopLevel(screen: Screen) {
        if (screen.route == Screen.Home.route) {
            navigateHome()
            return
        }
        navController.navigate(screen.route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BackHandler(enabled = showBottomNav && currentRoute != Screen.Home.route) {
        navigateHome()
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .pointerInput(canSwipeBottomTabs, currentRoute) {
                    if (!canSwipeBottomTabs) return@pointerInput
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onHorizontalDrag = { _, amount -> dragX += amount },
                        onDragEnd = {
                            val activeRoutes = activeBottomTabs.map { it.screen.route }
                            val currentIndex = activeRoutes.indexOf(currentRoute)
                            if (currentIndex == -1 || kotlin.math.abs(dragX) < 80f) return@detectHorizontalDragGestures
                            val nextIndex = if (dragX < 0) currentIndex + 1 else currentIndex - 1
                            val next = activeBottomTabs.getOrNull(nextIndex)?.screen ?: return@detectHorizontalDragGestures
                            navigateTopLevel(next)
                        },
                    )
                }
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                enterTransition = {
                    val direction = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideInHorizontally(tween(350)) { width -> width * direction } + fadeIn(tween(350))
                },
                exitTransition = {
                    val direction = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideOutHorizontally(tween(200)) { width -> -(width / 4) * direction } + fadeOut(tween(200))
                },
                popEnterTransition = {
                    val direction = transitionDirection(targetState.destination.route, initialState.destination.route)
                    slideInHorizontally(tween(350)) { width -> -(width / 4) * direction } + fadeIn(tween(350))
                },
                popExitTransition = {
                    val direction = transitionDirection(targetState.destination.route, initialState.destination.route)
                    slideOutHorizontally(tween(200)) { width -> width * direction } + fadeOut(tween(200))
                },
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        conn = conn,
                        onNavigate = { dest ->
                            when (dest) {
                                "route" -> navController.navigate(Screen.Route.route)
                                "dash" -> navController.navigate(Screen.Dash.route)
                                "rides" -> navController.navigate(Screen.Rides.route)
                                "garage" -> navController.navigate(Screen.Garage.route)
                                "settings" -> navController.navigate(Screen.Settings.route)
                            }
                        },
                        routeViewModel = routeViewModel,
                    )
                }

                composable(Screen.Expenses.route) {
                    ExpensesScreen()
                }

                composable(Screen.Trails.route) {
                    var showRecordingConflictDialog by remember { mutableStateOf(false) }

                    if (showRecordingConflictDialog) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showRecordingConflictDialog = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            icon = {
                                androidx.compose.material3.Icon(
                                    com.example.opendash.ui.OpenDashIcons.Target,
                                    null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = androidx.compose.ui.Modifier.size(28.dp)
                                )
                            },
                            title = {
                                androidx.compose.material3.Text(
                                    "Navigation active",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = com.example.opendash.ui.theme.GeistFamily
                                )
                            },
                            text = {
                                androidx.compose.material3.Text(
                                    "Recording a trail will end your active navigation. Do you want to continue?",
                                    color = MaterialTheme.colorScheme.outline,
                                    fontFamily = com.example.opendash.ui.theme.GeistFamily,
                                    fontSize = 14.sp
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    showRecordingConflictDialog = false
                                    routeViewModel.clear()
                                    dashViewModel.exitNavigation()
                                    routeViewModel.prepareRecordingRoute()
                                    navController.navigate(Screen.Route.route) { launchSingleTop = true }
                                }) {
                                    androidx.compose.material3.Text("End & Record", color = MaterialTheme.colorScheme.tertiary, fontFamily = com.example.opendash.ui.theme.GeistFamily, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { showRecordingConflictDialog = false }) {
                                    androidx.compose.material3.Text("Cancel", color = MaterialTheme.colorScheme.outline, fontFamily = com.example.opendash.ui.theme.GeistFamily)
                                }
                            }
                        )
                    }


                    TrailsScreen(
                        routeViewModel = routeViewModel,
                        onNavigateToRoute = {
                            navController.navigate(Screen.Route.route) { launchSingleTop = true }
                        },
                        onStartRecording = {
                            if (routeState.navigating) {
                                showRecordingConflictDialog = true
                            } else {
                                routeViewModel.prepareRecordingRoute()
                                navController.navigate(Screen.Route.route) { launchSingleTop = true }
                            }
                        },
                        isActiveNavigation = routeState.navigating,
                        onExitNavigation = {
                            routeViewModel.clear()
                            dashViewModel.exitNavigation()
                        },
                    )
                }

                composable(Screen.Route.route) {
                    RouteScreen(
                        routeViewModel = routeViewModel,
                        dashViewModel = dashViewModel,
                        onBack = { navigateHome() },
                        onNavigateToTrails = {
                            navController.navigate(Screen.Trails.route) {
                                launchSingleTop = true
                                popUpTo(Screen.Home.route) { saveState = true }
                            }
                        },
                        onSentToDash = { destName ->
                            dashViewModel.setDestination(
                                name = destName,
                                lat  = routeState.destination?.lat,
                                lng  = routeState.destination?.lng,
                                initialRoute = routeState.route,
                                initialAlternates = routeState.routes,
                                isCustomTrail = routeState.isCustomTrail,
                                trailStart = routeState.trailStart?.let { it.lat to it.lng }
                            )
                            // Start navigation: open the dash view. DashScreen owns the
                            // connect — it requests the runtime permissions first (starting
                            // the location-type FGS without them is a fatal crash on 14+)
                            // and then begins the WiFi → auth → stream flow.
                            navController.navigate(Screen.Dash.route) {
                                popUpTo(Screen.Home.route)
                            }
                        },
                        onNavigateToDash = {
                            navController.navigate(Screen.Dash.route) {
                                popUpTo(Screen.Home.route)
                            }
                        }
                    )
                }

                composable(Screen.Dash.route) {
                    DashScreen(vm = dashViewModel)
                }

                composable(Screen.Garage.route) {
                    GarageScreen(
                        tab = garageTab,
                        onTabChange = { appViewModel.setGarageTab(it) },
                    )
                }

                composable(Screen.Rides.route) {
                    RidesScreen()
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        conn = conn,
                        onConnChange = { appViewModel.setConn(it) },
                        dashViewModel = dashViewModel,
                        onBack = { navController.navigate(Screen.Home.route) { launchSingleTop = true } },
                        onOpenOfflineMaps = { navController.navigate(Screen.OfflineMaps.route) },
                    )
                }

                composable(Screen.OfflineMaps.route) {
                    OfflineMapsScreen(onBack = { navController.popBackStack() })
                }
            }
        }

        if (showBottomNav) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(110.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )

            OpenDashBottomNav(
                currentRoute = activeBottomRoute(currentRoute),
                activeTabs = activeBottomTabs,
                onNavSelect = { screen -> navigateTopLevel(screen) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

private fun activeBottomRoute(route: String?): String? =
    if (route in homeChildRoutes) Screen.Home.route else route

private fun transitionDirection(fromRoute: String?, toRoute: String?): Int =
    if (navOrderIndex(toRoute) >= navOrderIndex(fromRoute)) 1 else -1

private fun navOrderIndex(route: String?): Int = when (route) {
    Screen.Home.route -> 0
    Screen.Rides.route -> 3
    Screen.Route.route -> 10
    Screen.Dash.route -> 11
    Screen.Expenses.route -> 20
    Screen.Garage.route -> 30
    Screen.Settings.route -> 40
    else -> 0
}

@Composable
private fun OpenDashBottomNav(
    currentRoute: String?,
    activeTabs: List<NavTab>,
    onNavSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        activeTabs.forEach { tab ->
            val active = currentRoute == tab.screen.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .clickable { onNavSelect(tab.screen) },
            ) {
                val resId = if (active) tab.activeIconRes else (tab.inactiveIconRes ?: tab.activeIconRes)
                if (resId != null) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(resId),
                        contentDescription = tab.label,
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.size(if (tab.screen == Screen.Trails) 32.dp else 24.dp),
                    )
                } else if (tab.icon != null) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
