package com.example.opendash.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.opendash.R
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.layout.ContentScale
import com.example.opendash.data.VehicleStore
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedAll by routeViewModel.saved.collectAsState()
    // Exclude custom trail entries (those with a recorded route JSON on disk)
    val saved = remember(savedAll) {
        savedAll.filter { loc -> !java.io.File(context.filesDir, "trail_${loc.sid}.json").exists() }
    }
    val rides by ridesViewModel.rides.collectAsState()
    val garage by garageViewModel.ui.collectAsState()
    val routeState by routeViewModel.state.collectAsState()
    var editingLoc by remember { mutableStateOf<com.example.opendash.data.SavedLocation?>(null) }

    val (statusText, statusColor) = when (conn) {
        ConnectionState.Connected -> "Streaming to dash" to Ok
        ConnectionState.Searching -> "Looking for dash…" to MaterialTheme.colorScheme.tertiary
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

    val dashViewModel: com.example.opendash.viewmodel.DashViewModel = viewModel()
    val dashUi by dashViewModel.ui.collectAsState()
    val nowPlaying by dashViewModel.nowPlaying.collectAsState()
    val incomingCall by dashViewModel.incomingCall.collectAsState()
    val isNavigating = routeState.navigating

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 100.dp),
    ) {

        val vehicles by VehicleStore.vehicles.collectAsState()
        val activeVehicleId by VehicleStore.activeVehicleId.collectAsState()
        val activeVehicle = remember(vehicles, activeVehicleId) {
            VehicleStore.activeVehicle()
        }
        val profileImg = remember(activeVehicle.profileIcon) {
            if (activeVehicle.profileIcon != "default") {
                runCatching { BitmapFactory.decodeFile(activeVehicle.profileIcon)?.asImageBitmap() }.getOrNull()
            } else null
        }

        // ── Status header: bike + dash state + primary actions ──
        OpenDashCard(
            glow = conn == ConnectionState.Connected,
            padding = 18.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (brand, model) = remember(activeVehicle.title) { getBrandAndModel(activeVehicle.title) }
                Column(Modifier.weight(1f)) {
                    if (brand.isNotEmpty()) {
                        Text(
                            brand.uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        model.ifBlank { activeVehicle.title },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                        letterSpacing = (-0.3).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conn != ConnectionState.Offline) {
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
                }
                Spacer(Modifier.width(12.dp))
                val defaultDrawable = remember(activeVehicle.title) { getBikeDefaultDrawable(activeVehicle.title) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    if (profileImg != null) {
                        Image(
                            bitmap = profileImg,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Image(
                            painter = painterResource(id = defaultDrawable),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val context = androidx.compose.ui.platform.LocalContext.current
                OpenDashBtn(
                    label = if (conn == ConnectionState.Connected) "Dash view" else "Connect dash",
                    icon = if (conn == ConnectionState.Connected) OpenDashIcons.Dash else OpenDashIcons.Wifi,
                    onClick = {
                        if (conn == ConnectionState.Connected) {
                            onNavigate("dash")
                        } else {
                            runCatching {
                                val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                if (wifi != null && !wifi.isWifiEnabled) {
                                    @Suppress("DEPRECATION")
                                    wifi.isWifiEnabled = true
                                }
                            }
                            runCatching {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    val intent = android.content.Intent("android.settings.panel.action.WIFI")
                                    context.startActivity(intent)
                                } else {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                    context.startActivity(intent)
                                }
                            }.onFailure {
                                runCatching {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                    context.startActivity(intent)
                                }
                            }
                        }
                    },
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



        val activeDest = if (routeState.navigating) routeState.destination?.name else null
        if (!activeDest.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            OpenDashCard(
                glow = true,
                padding = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate("dash") }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                        Icon(
                            OpenDashIcons.ChevronRight,
                            contentDescription = "Resume navigation",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        activeDest,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))

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
                                    Icon(OpenDashIcons.Route, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(dist, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
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
                                    Icon(OpenDashIcons.Flag, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(eta, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            // ── Saved destinations ──
            HomeSection("Saved destinations")
            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                saved.forEachIndexed { i, loc ->
                    if (i > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.weight(1f)) {
                            OpenDashRow(
                                loc.name, icon = OpenDashIcons.LocationPin,
                                sub = loc.note.ifBlank { "%.4f, %.4f".format(loc.lat, loc.lng) },
                                trailingIcon = false,
                                onClick = { routeViewModel.selectSaved(loc); onNavigate("route") },
                            )
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { editingLoc = loc }
                                .padding(8.dp),
                        ) {
                            Icon(
                                OpenDashIcons.Trash,
                                contentDescription = "Delete ${loc.name}",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }

        if (rides.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            // ── Recent rides ──
            HomeSection(
                "Recent rides",
                action = "View all",
                onAction = { onNavigate("rides") },
            )
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

    editingLoc?.let { loc ->
        EditLocationDialog(
            loc = loc,
            onSave = { name, note -> routeViewModel.renameSaved(loc, name, note); editingLoc = null },
            onDelete = { routeViewModel.deleteSaved(loc); editingLoc = null },
            onDismiss = { editingLoc = null },
        )
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

/** Brand line + model line for the header card. Titles usually carry only the model
 *  ("Himalayan 450"), so unknown-prefix titles are matched against known model names
 *  to infer the brand; the full title stays on the model line. */
private fun getBrandAndModel(title: String): Pair<String, String> {
    val lower = title.lowercase()
    val brandPrefixes = listOf(
        "royal enfield", "ktm", "suzuki", "hero", "tvs", "bmw",
        "honda", "yezdi", "triumph", "yamaha", "bajaj", "kawasaki",
    )
    brandPrefixes.firstOrNull { lower.startsWith(it) }?.let { prefix ->
        val brand = if (prefix == "royal enfield") "Royal Enfield" else title.take(prefix.length)
        val model = title.substring(prefix.length).trim()
        return brand to model.ifBlank { title }
    }
    // Same model vocabulary as getBikeDefaultDrawable.
    val brandByModel = listOf(
        "himalayan" to "Royal Enfield", "bullet" to "Royal Enfield",
        "classic" to "Royal Enfield", "hunter" to "Royal Enfield",
        "meteor" to "Royal Enfield", "guerrilla" to "Royal Enfield",
        "interceptor" to "Royal Enfield", "continental gt" to "Royal Enfield",
        "shotgun" to "Royal Enfield",
        "duke" to "KTM", "adventure" to "KTM",
        "v-strom" to "Suzuki", "xstorm" to "Suzuki",
        "xpulse" to "Hero",
        "rtx" to "TVS", "apache" to "TVS",
        "g 310" to "BMW", "f 450" to "BMW",
        "cb350" to "Honda", "hness" to "Honda", "h'ness" to "Honda", "hiness" to "Honda",
        "scrambler" to "Triumph", "speed 400" to "Triumph",
        "dominar" to "Bajaj", "pulsar" to "Bajaj",
    )
    val brand = brandByModel.firstOrNull { (model, _) -> lower.contains(model) }?.second
    return (brand ?: "") to title
}

