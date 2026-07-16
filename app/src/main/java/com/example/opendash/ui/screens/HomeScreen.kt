package com.example.opendash.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.ConnectionState
import com.example.opendash.viewmodel.GarageViewModel
import com.example.opendash.viewmodel.RidesViewModel
import com.example.opendash.viewmodel.RouteViewModel

/**
 * Home is a dashboard: compact status header for the bike + dash link, a 2×2 grid
 * of live stat tiles fed from across the app (odometer, fuel economy, last ride,
 * next service), then saved destinations. Each tile deep-links to its screen.
 */
@Composable
fun HomeScreen(
    conn: ConnectionState,
    onNavigate: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
    ridesViewModel: RidesViewModel = viewModel(),
    garageViewModel: GarageViewModel = viewModel(),
) {
    val saved by routeViewModel.saved.collectAsState()
    val rides by ridesViewModel.rides.collectAsState()
    val garage by garageViewModel.ui.collectAsState()

    val (statusText, statusColor) = when (conn) {
        ConnectionState.Connected -> "Streaming to dash" to Ok
        ConnectionState.Searching -> "Looking for dash…" to Warn
        ConnectionState.Offline   -> "Dash not connected" to MaterialTheme.colorScheme.outline
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dot-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        1f, 0.35f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    val lastRide = rides.firstOrNull()
    val nextService = garage.maint.minByOrNull { it.remainingKm }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        ScreenHeader(
            wordmark = true,
            trailing = {
                OpenDashIconBtn(OpenDashIcons.Gear, onClick = { onNavigate("settings") }, size = 42.dp)
            },
        )

        // ── Status header: bike + dash state + primary actions ──
        OpenDashCard(
            glow = conn == ConnectionState.Connected,
            padding = 18.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        garage.activeVehicleName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                        letterSpacing = (-0.3).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(
                                statusColor.copy(alpha = if (conn == ConnectionState.Connected) pulseAlpha else 1f)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.5.sp,
                            fontFamily = GeistFamily,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(
                        OpenDashIcons.Motor, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpenDashBtn(
                    label = if (conn == ConnectionState.Connected) "Dash view" else "Connect dash",
                    icon = if (conn == ConnectionState.Connected) OpenDashIcons.Dash else OpenDashIcons.Wifi,
                    onClick = { onNavigate("dash") },
                    size = BtnSize.Sm,
                    modifier = Modifier.weight(1f),
                )
                OpenDashBtn(
                    label = "Navigate",
                    icon = OpenDashIcons.Navi,
                    variant = BtnVariant.Secondary,
                    onClick = { onNavigate("route") },
                    size = BtnSize.Sm,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Saved destinations ──
        HomeSection("Saved destinations")
        if (saved.isEmpty()) {
            EmptyHint(
                icon = OpenDashIcons.LocationPin,
                title = "Nothing saved yet",
                sub = "Share a place from Google Maps, or search in Navigate",
                onClick = { onNavigate("route") },
            )
        } else {
            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                saved.forEachIndexed { i, loc ->
                    if (i > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                    OpenDashRow(
                        loc.name, icon = OpenDashIcons.LocationPin,
                        sub = loc.note.ifBlank { "%.4f, %.4f".format(loc.lat, loc.lng) },
                        trailingIcon = true,
                        onClick = { routeViewModel.selectSaved(loc); onNavigate("route") },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Recent rides ──
        HomeSection(
            "Recent rides",
            action = if (rides.isNotEmpty()) "View all" else null,
            onAction = { onNavigate("rides") },
        )
        if (rides.isEmpty()) {
            EmptyHint(
                icon = OpenDashIcons.History,
                title = "No rides yet",
                sub = "Rides are recorded automatically while connected",
            )
        } else {
            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                rides.take(3).forEachIndexed { i, ride ->
                    if (i > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                    OpenDashRow(
                        "%.1f km".format(ride.distanceKm),
                        icon = OpenDashIcons.Navi,
                        sub = formatRideDate(ride.startMs),
                        right = formatRideDuration(ride.durationSec),
                        onClick = { onNavigate("rides") },
                    )
                }
            }
        }
    }
}

private fun formatRideDate(timeMs: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timeMs))

private fun formatRideDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** Sentence-case section header with an optional trailing action. */
@Composable
private fun HomeSection(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp, start = 2.dp, end = 2.dp),
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            letterSpacing = (-0.1).sp,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Compact empty state: icon bubble, one-line hint, optional tap-through. */
@Composable
private fun EmptyHint(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: (() -> Unit)? = null,
) {
    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
                Text(
                    sub,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    fontFamily = GeistFamily,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    OpenDashIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
