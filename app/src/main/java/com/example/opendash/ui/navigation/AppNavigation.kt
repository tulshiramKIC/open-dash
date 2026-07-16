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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
    object Dash     : Screen("dash")
    object Garage   : Screen("garage")
    object Rides    : Screen("rides")
    object Settings : Screen("settings")
}

private data class NavTab(val screen: Screen, val icon: ImageVector, val label: String)

private val bottomTabs = listOf(
    NavTab(Screen.Home,   OpenDashIcons.Home,    "Home"),
    NavTab(Screen.Route,  OpenDashIcons.Navi,    "Navigate"),
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

    val showBottomNav = currentRoute in shellRoutes
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

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            Modifier
                .weight(1f)
                .pointerInput(canSwipeBottomTabs, currentRoute) {
                    if (!canSwipeBottomTabs) return@pointerInput
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onHorizontalDrag = { _, amount -> dragX += amount },
                        onDragEnd = {
                            val currentIndex = bottomRoutes.indexOf(currentRoute)
                            if (currentIndex == -1 || kotlin.math.abs(dragX) < 80f) return@detectHorizontalDragGestures
                            val nextIndex = if (dragX < 0) currentIndex + 1 else currentIndex - 1
                            val next = bottomTabs.getOrNull(nextIndex)?.screen ?: return@detectHorizontalDragGestures
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

                composable(Screen.Route.route) {
                    RouteScreen(
                        routeViewModel = routeViewModel,
                        onBack = { navigateHome() },
                        onSentToDash = { destName ->
                            dashViewModel.setDestination(
                                name = destName,
                                lat  = routeState.destination?.lat,
                                lng  = routeState.destination?.lng,
                            )
                            // Start navigation: open the dash view. DashScreen owns the
                            // connect — it requests the runtime permissions first (starting
                            // the location-type FGS without them is a fatal crash on 14+)
                            // and then begins the WiFi → auth → stream flow.
                            navController.navigate(Screen.Dash.route) {
                                popUpTo(Screen.Home.route)
                            }
                        },
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
                    )
                }
            }
        }

        if (showBottomNav) {
            OpenDashBottomNav(
                currentRoute = activeBottomRoute(currentRoute),
                onNavSelect = { screen -> navigateTopLevel(screen) },
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
    onNavSelect: (Screen) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Bg1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Bg1)
                .border(1.dp, Line2, RoundedCornerShape(21.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomTabs.forEach { tab ->
                val active = currentRoute == tab.screen.route
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) GoldTint else Color.Transparent)
                        .clickable { onNavSelect(tab.screen) },
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) GoldBright else TextLo,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label,
                        color = if (active) GoldBright else TextLo,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        fontFamily = GeistFamily,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
