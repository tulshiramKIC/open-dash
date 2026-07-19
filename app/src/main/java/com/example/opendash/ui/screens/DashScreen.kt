package com.example.opendash.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.opendash.R
import com.example.opendash.media.IncomingCall
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.DashViewModel
import com.example.opendash.dash.nav.VoiceMode
import kotlinx.coroutines.delay

/** Fraction of the round display (top-down) the streamed video actually covers on the
 *  real Tripper Dash — the 526×300 frame lands in the upper band; below it the cluster
 *  firmware draws the route banner and its own UI. Measured ~58% from hardware photos of
 *  RE-app navigation projection; confirm on-bike. */
private const val VIDEO_BAND_FRACTION = 0.58f

private fun drawManeuverArrow(canvas: android.graphics.Canvas, x: Float, y: Float, size: Float, type: com.example.opendash.dash.nav.ManeuverType?, color: Int, strokeWidth: Float) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val path = android.graphics.Path()
    when (type) {
        com.example.opendash.dash.nav.ManeuverType.TURN_LEFT,
        com.example.opendash.dash.nav.ManeuverType.SHARP_LEFT,
        com.example.opendash.dash.nav.ManeuverType.SLIGHT_LEFT -> {
            path.moveTo(x + size * 0.75f, y + size * 0.75f)
            path.lineTo(x + size * 0.75f, y + size * 0.45f)
            path.lineTo(x + size * 0.25f, y + size * 0.45f)
            path.moveTo(x + size * 0.4f, y + size * 0.3f)
            path.lineTo(x + size * 0.25f, y + size * 0.45f)
            path.lineTo(x + size * 0.4f, y + size * 0.6f)
        }
        com.example.opendash.dash.nav.ManeuverType.TURN_RIGHT,
        com.example.opendash.dash.nav.ManeuverType.SHARP_RIGHT,
        com.example.opendash.dash.nav.ManeuverType.SLIGHT_RIGHT -> {
            path.moveTo(x + size * 0.25f, y + size * 0.75f)
            path.lineTo(x + size * 0.25f, y + size * 0.45f)
            path.lineTo(x + size * 0.75f, y + size * 0.45f)
            path.moveTo(x + size * 0.6f, y + size * 0.3f)
            path.lineTo(x + size * 0.75f, y + size * 0.45f)
            path.lineTo(x + size * 0.6f, y + size * 0.6f)
        }
        com.example.opendash.dash.nav.ManeuverType.UTURN -> {
            path.moveTo(x + size * 0.75f, y + size * 0.75f)
            path.lineTo(x + size * 0.75f, y + size * 0.45f)
            val r = android.graphics.RectF(x + size * 0.25f, y + size * 0.25f, x + size * 0.75f, y + size * 0.65f)
            path.arcTo(r, 0f, -180f, false)
            path.lineTo(x + size * 0.25f, y + size * 0.75f)
            path.moveTo(x + size * 0.1f, y + size * 0.6f)
            path.lineTo(x + size * 0.25f, y + size * 0.75f)
            path.lineTo(x + size * 0.4f, y + size * 0.6f)
        }
        else -> {
            path.moveTo(x + size * 0.5f, y + size * 0.75f)
            path.lineTo(x + size * 0.5f, y + size * 0.25f)
            path.moveTo(x + size * 0.35f, y + size * 0.4f)
            path.lineTo(x + size * 0.5f, y + size * 0.25f)
            path.lineTo(x + size * 0.65f, y + size * 0.4f)
        }
    }
    canvas.drawPath(path, paint)
}

@Composable
fun DashScreen(vm: DashViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val nowPlaying by vm.nowPlaying.collectAsState()
    val incomingCall by vm.incomingCall.collectAsState()
    val context = LocalContext.current

    // WiFi network request (13+: NEARBY_WIFI_DEVICES) + GPS for the map
    // Essential perms gate the connection; notifications are requested but optional.
    val essentialPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val requestedPermissions = remember {
        buildList {
            addAll(essentialPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    fun hasEssentialPermissions() = essentialPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val essentialOk = essentialPermissions.all { results[it] == true }
        if (essentialOk) vm.connect()
    }

    // Local preview state (mirrors what the dash shows)
    var satellite by rememberSaveable { mutableStateOf(false) }
    // Rider-marker style, persisted across launches; arrow on a fresh install. (Bike marker
    // stays a dash-view option; the full-screen Navigate nav always uses the arrow.)
    val bikeMarker by com.example.opendash.data.NavSettings.bikeMarker.collectAsState()
    val mapTheme by com.example.opendash.data.NavSettings.mapTheme.collectAsState()
    val mapNight = rememberMapNight()
    var recenterKey by remember { mutableStateOf(0) }

    val voiceManager = remember { com.example.opendash.dash.nav.VoiceManager.get(context) }
    val voiceMode by voiceManager.mode.collectAsState()

    val streaming = ui.stage == ConnStage.STREAMING

    // Auto-connect on opening the Dash screen, so the rider doesn't tap "Connect" every
    // ride — just open the app (or tap Navigate on a route, which lands here). Fires once;
    // if the dash is off it errors out quietly and the rider can retry. On first run the
    // permissions aren't granted yet — request them, and the launcher callback connects.
    // rememberSaveable so a config change (rotation/theme) doesn't reset this and silently
    // reconnect after the rider deliberately disconnected.
    var autoConnectTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoConnectTried && (ui.stage == ConnStage.OFFLINE || ui.stage == ConnStage.ERROR)) {
            autoConnectTried = true
            if (hasEssentialPermissions()) vm.connect()
            else permissionLauncher.launch(requestedPermissions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 100.dp),
    ) {

        ScreenHeader(
            title = "Dash view",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (streaming && ui.thermal != "OK") {
                        OpenDashChip(
                            ui.thermal,
                            if (ui.thermal == "Warm") ChipTone.Warn else ChipTone.Alert,
                        )
                    }
                    when (ui.stage) {
                        ConnStage.STREAMING -> OpenDashChip("Connected", ChipTone.Gold, dot = true, onClick = { vm.disconnect() })
                        ConnStage.WIFI,
                        ConnStage.AUTH      -> OpenDashChip("Connecting…", ChipTone.Neutral, onClick = { vm.disconnect() })
                        ConnStage.ERROR,
                        ConnStage.OFFLINE   -> OpenDashChip(
                            label = "Connect",
                            tone = ChipTone.Alert,
                            icon = OpenDashIcons.Wifi,
                            onClick = {
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
                                if (hasEssentialPermissions()) vm.connect()
                                else permissionLauncher.launch(requestedPermissions)
                            }
                        )
                    }
                }
            },
        )

        // Single connection card (hidden once streaming)
        if (!streaming) {
            ui.pendingPairingSsid?.let { pendingSsid ->
                OpenDashCard(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                "Pair with this dash?",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                pendingSsid,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontFamily = GeistMonoFamily,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            Text(
                                "OpenDash will remember this exact SSID for future reconnects.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpenDashBtn(
                                "Cancel",
                                onClick = { vm.rejectDiscoveredDash() },
                                variant = BtnVariant.Ghost,
                                size = BtnSize.Sm,
                            )
                            OpenDashBtn(
                                "Pair",
                                onClick = { vm.confirmDiscoveredDash() },
                                icon = OpenDashIcons.Check,
                                variant = BtnVariant.Primary,
                                size = BtnSize.Sm,
                            )
                        }
                    }
                }
            }

        }

        // Circular viewport with streaming aura
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            if (streaming) {
                Box(
                    Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Brush.radialGradient(listOf(GoldGlow, Color.Transparent), radius = 200f))
                )
            }
            // Hardware-honest Tripper preview: the streamed video only fills the upper band
            // of the real dash (526×300 ≈ top 54% of the circle); the bottom is the
            // cluster firmware's own UI, mocked below so the preview matches the bike.
            Box(
                modifier = Modifier
                    .size(272.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF07090A))
                    .border(6.dp, Color(0xFF0D0F10), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(VIDEO_BAND_FRACTION)
                        .align(Alignment.TopCenter)
                ) {
                    // Google-blue-dot behavior: while standing still the marker's arrow
                    // follows the phone's compass (map/camera unaffected); once moving,
                    // GPS travel bearing takes over for both.
                    val deviceAzimuth by rememberDeviceAzimuth()
                    val stationary = (ui.speedKmh ?: 0) < 5
                    OpenDashMap(
                        riderLat = ui.riderLat,
                        riderLng = ui.riderLng,
                        dest = ui.destLatLng,
                        routePoints = ui.routePoints,
                        routeCongestion = ui.routeCongestion,
                        hasLocationPermission = hasEssentialPermissions(),
                        navMode = ui.headingUp,
                        riderBearing = ui.riderBearing,
                        zoom = ui.mapZoom.toDouble(),
                        satellite = satellite,
                        night = mapNight,
                        recenterKey = recenterKey,
                        showAttribution = false,
                        riderIconScale = 1.35f,
                        cameraAheadOffset = true,
                        markerBearing = if (stationary) deviceAzimuth else null,
                        bikeMarker = bikeMarker,
                        // Group Ride is deliberately absent here: peers render ONLY on the
                        // Navigate map — the dash stays a clean solo-navigation surface.
                        modifier = Modifier.fillMaxSize(),
                        isCustomTrail = ui.isCustomTrail,
                        trailStart = ui.trailStart,
                        showTravelledGrey = true
                    )

                }



                NativeClusterMock(
                    speedKmh = ui.speedKmh,
                    // Same string the dash banner gets: per-step guidance, else destination.
                    bannerText = ui.maneuver ?: ui.destinationName,
                    remainingKm = ui.remainingKm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(1f - VIDEO_BAND_FRACTION)
                        .align(Alignment.BottomCenter),
                )

                // Overlay Next-turn Arrow icon on top of the Map view, inside the circle
                ui.maneuver?.let { mv ->
                    val arrowIcon = when (ui.maneuverType) {
                        com.example.opendash.dash.nav.ManeuverType.TURN_LEFT,
                        com.example.opendash.dash.nav.ManeuverType.SHARP_LEFT -> OpenDashIcons.TurnLeft
                        com.example.opendash.dash.nav.ManeuverType.TURN_RIGHT,
                        com.example.opendash.dash.nav.ManeuverType.SHARP_RIGHT -> OpenDashIcons.TurnRight
                        com.example.opendash.dash.nav.ManeuverType.SLIGHT_LEFT -> OpenDashIcons.SlightLeft
                        com.example.opendash.dash.nav.ManeuverType.SLIGHT_RIGHT -> OpenDashIcons.SlightRight
                        com.example.opendash.dash.nav.ManeuverType.UTURN -> OpenDashIcons.TurnLeft // fallback TurnLeft
                        com.example.opendash.dash.nav.ManeuverType.ARRIVE -> OpenDashIcons.LocationPin
                        else -> OpenDashIcons.ArrowUp
                    }
                    val nextTurnM = ui.nextTurnM
                    val isCloseToTurn = nextTurnM != null && nextTurnM < 100.0
                    val blackColor = Color(0xFF1E2022)
                    val blinkingTint = if (isCloseToTurn) {
                        val infiniteTransition = rememberInfiniteTransition(label = "blinkingTurnIcon")
                        infiniteTransition.animateColor(
                            initialValue = Color(0xFFFF3B30),
                            targetValue = blackColor,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 400, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "blinkColor"
                        ).value
                    } else {
                        if (ui.offRoute) MaterialTheme.colorScheme.tertiary else blackColor
                    }
                    
                    val distText = nextTurnM?.let {
                        if (it < 1000.0) "${it.toInt()} m" else "%.1f km".format(it / 1000.0)
                    }

                    val hasCall = incomingCall != null
                    val hasMusic = ui.showMediaOverlay && nowPlaying != null && !hasCall
                    val hasNav = ui.maneuver != null
                    val outerOccupied = hasMusic || hasCall

                    if (outerOccupied || hasNav) {
                        val arrowPainter = rememberVectorPainter(image = arrowIcon)
                        
                        Canvas(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val w = size.width
                            val h = size.height
                            val centerX = w / 2f
                            val centerY = h / 2f
                            val R = h / 2f
                            
                            if (outerOccupied) {
                                if (hasMusic) {
                                    // Draw Music Crescent at the outer position (Rc = R - 16.dp)
                                    val track = nowPlaying!!
                                    val title = if (track.title.length > 32) track.title.take(31) + "..." else track.title
                                    val Rc = R - 16.dp.toPx()
                                    val artSize = 18.dp.toPx()
                                    val spacing = 6.dp.toPx()
                                    val edgePadding = 14.dp.toPx()
                                    
                                    drawIntoCanvas { composeCanvas ->
                                        val canvas = composeCanvas.nativeCanvas
                                        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            color = if (mapNight) android.graphics.Color.WHITE else 0xFF1E2022.toInt()
                                            textSize = 10.sp.toPx()
                                            isFakeBoldText = true
                                            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                                        }
                                        val textWidth = textPaint.measureText(title)
                                        val totalLength = edgePadding * 2f + artSize + spacing + textWidth
                                        val sweepAngle = ((totalLength / Rc) * (180f / Math.PI.toFloat())).coerceIn(45f, 130f)
                                        val startAngle = 270f - sweepAngle / 2f
                                        val endAngle = 270f + sweepAngle / 2f
                                        
                                        val arcPath = android.graphics.Path().apply {
                                            val rect = android.graphics.RectF(centerX - Rc, centerY - Rc, centerX + Rc, centerY + Rc)
                                            addArc(rect, startAngle, sweepAngle)
                                        }
                                        
                                        val fStart = startAngle / 360f
                                        val fCenter = 270f / 360f
                                        val fEnd = endAngle / 360f
                                        val positions = floatArrayOf(0.0f, maxOf(0.0f, fStart), fCenter, fEnd, 1.0f)
                                        
                                        val borderColors = if (mapNight) {
                                            intArrayOf(0x00000000, 0x00000000, 0x4D000000, 0x00000000, 0x00000000)
                                        } else {
                                            intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                        }
                                        val borderShader = android.graphics.SweepGradient(centerX, centerY, borderColors, positions)
                                        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeWidth = 27.5.dp.toPx()
                                            strokeCap = android.graphics.Paint.Cap.BUTT
                                            setShader(borderShader)
                                        }
                                        canvas.drawPath(arcPath, borderPaint)
                                        
                                        val bgColors = if (mapNight) {
                                            intArrayOf(0x00000000, 0x00000000, 0xB3000000.toInt(), 0x00000000, 0x00000000)
                                        } else {
                                            intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                        }
                                        val bgShader = android.graphics.SweepGradient(centerX, centerY, bgColors, positions)
                                        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeWidth = 26.dp.toPx()
                                            strokeCap = android.graphics.Paint.Cap.BUTT
                                            setShader(bgShader)
                                        }
                                        canvas.drawPath(arcPath, bgPaint)
                                        
                                        val arcLength = Rc * (sweepAngle * Math.PI / 180.0).toFloat()
                                        val contentWidth = artSize + spacing + textWidth
                                        val startOffset = (arcLength - contentWidth) / 2f
                                        
                                        val artOffset = startOffset + artSize / 2f
                                        val artAngle = startAngle + (artOffset / Rc) * (180f / Math.PI.toFloat())
                                        val thetaRad = artAngle * (Math.PI / 180.0)
                                        val artX = (centerX + Rc * Math.cos(thetaRad)).toFloat()
                                        val artY = (centerY + Rc * Math.sin(thetaRad)).toFloat()
                                        
                                        val baseCirclePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            color = if (mapNight) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                                            style = android.graphics.Paint.Style.FILL
                                        }
                                        canvas.drawCircle(artX, artY, 8.5.dp.toPx(), baseCirclePaint)
                                        
                                        if (track.art != null) {
                                            canvas.save()
                                            val clipPath = android.graphics.Path().apply {
                                                addCircle(artX, artY, 8.dp.toPx(), android.graphics.Path.Direction.CW)
                                            }
                                            canvas.clipPath(clipPath)
                                            canvas.drawBitmap(track.art!!, null, android.graphics.RectF(artX - 8.dp.toPx(), artY - 8.dp.toPx(), artX + 8.dp.toPx(), artY + 8.dp.toPx()), null)
                                            canvas.restore()
                                        }
                                        
                                        val textStart = startOffset + artSize + spacing
                                        textPaint.textAlign = android.graphics.Paint.Align.LEFT
                                        canvas.drawTextOnPath(title, arcPath, textStart, 3.5.dp.toPx(), textPaint)
                                    }
                                }
                                
                                if (hasNav) {
                                    // Draw Concentric TBT Crescent at the inner position (Rc = R - 40.dp)
                                    val RcTbt = R - 40.dp.toPx()
                                    val iconSize = 15.dp.toPx()
                                    val tbtSpacing = 5.dp.toPx()
                                    val tbtEdgePadding = 12.dp.toPx()
                                    
                                    drawIntoCanvas { composeCanvas ->
                                        val canvas = composeCanvas.nativeCanvas
                                         val tbtTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            color = if (mapNight) android.graphics.Color.WHITE else 0xFF1E2022.toInt()
                                            textSize = 8.5.sp.toPx()
                                            isFakeBoldText = true
                                            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                                        }
                                        val tbtTextWidth = tbtTextPaint.measureText(distText ?: "")
                                        val tbtTotalLength = tbtEdgePadding * 2f + iconSize + tbtSpacing + tbtTextWidth
                                        val tbtSweepAngle = ((tbtTotalLength / RcTbt) * (180f / Math.PI.toFloat())).coerceIn(40f, 130f)
                                        val tbtStartAngle = 270f - tbtSweepAngle / 2f
                                        val tbtEndAngle = 270f + tbtSweepAngle / 2f
                                        
                                        val tbtArcPath = android.graphics.Path().apply {
                                            val rect = android.graphics.RectF(centerX - RcTbt, centerY - RcTbt, centerX + RcTbt, centerY + RcTbt)
                                            addArc(rect, tbtStartAngle, tbtSweepAngle)
                                        }
                                        
                                        val tbtFStart = tbtStartAngle / 360f
                                        val tbtFCenter = 270f / 360f
                                        val tbtFEnd = tbtEndAngle / 360f
                                        val tbtPositions = floatArrayOf(0.0f, maxOf(0.0f, tbtFStart), tbtFCenter, tbtFEnd, 1.0f)
                                        
                                        val tbtBorderColors = if (mapNight) {
                                            intArrayOf(0x00000000, 0x00000000, 0x4D000000, 0x00000000, 0x00000000)
                                        } else {
                                            intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                        }
                                        val tbtBorderShader = android.graphics.SweepGradient(centerX, centerY, tbtBorderColors, tbtPositions)
                                        val tbtBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeWidth = 23.5.dp.toPx()
                                            strokeCap = android.graphics.Paint.Cap.BUTT
                                            setShader(tbtBorderShader)
                                        }
                                        canvas.drawPath(tbtArcPath, tbtBorderPaint)
                                        
                                        val tbtBgColors = if (mapNight) {
                                            intArrayOf(0x00000000, 0x00000000, 0xB3000000.toInt(), 0x00000000, 0x00000000)
                                        } else {
                                            intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                        }
                                        val tbtBgShader = android.graphics.SweepGradient(centerX, centerY, tbtBgColors, tbtPositions)
                                        val tbtBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeWidth = 22.dp.toPx()
                                            strokeCap = android.graphics.Paint.Cap.BUTT
                                            setShader(tbtBgShader)
                                        }
                                        canvas.drawPath(tbtArcPath, tbtBgPaint)
                                        
                                        val tbtArcLength = RcTbt * (tbtSweepAngle * Math.PI / 180.0).toFloat()
                                        val tbtContentWidth = iconSize + tbtSpacing + tbtTextWidth
                                        val tbtStartOffset = (tbtArcLength - tbtContentWidth) / 2f
                                        
                                        val iconOffset = tbtStartOffset + iconSize / 2f
                                        val iconAngle = tbtStartAngle + (iconOffset / RcTbt) * (180f / Math.PI.toFloat())
                                        val tbtThetaRad = iconAngle * (Math.PI / 180.0)
                                        val iconX = (centerX + RcTbt * Math.cos(tbtThetaRad)).toFloat()
                                        val iconY = (centerY + RcTbt * Math.sin(tbtThetaRad)).toFloat()
                                        
                                        drawManeuverArrow(canvas, iconX - iconSize/2f, iconY - iconSize/2f, iconSize, ui.maneuverType, if (mapNight) android.graphics.Color.WHITE else blinkingTint.toArgb(), 2.5.dp.toPx())
                                        
                                        val tbtTextStart = tbtStartOffset + iconSize + tbtSpacing
                                        tbtTextPaint.textAlign = android.graphics.Paint.Align.LEFT
                                        canvas.drawTextOnPath(distText ?: "", tbtArcPath, tbtTextStart, 3.0.dp.toPx(), tbtTextPaint)
                                    }
                                }
                            } else {
                                // Draw TBT Crescent at the outer position (Rc = R - 16.dp, gradient glassmorphism)
                                val Rc = R - 16.dp.toPx()
                                val iconSize = 18.dp.toPx()
                                val spacing = 6.dp.toPx()
                                val edgePadding = 14.dp.toPx()
                                
                                drawIntoCanvas { composeCanvas ->
                                    val canvas = composeCanvas.nativeCanvas
                                    val tbtTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        color = if (mapNight) android.graphics.Color.WHITE else 0xFF1E2022.toInt()
                                        textSize = 10.sp.toPx()
                                        isFakeBoldText = true
                                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                                    }
                                    val textWidth = tbtTextPaint.measureText(distText ?: "")
                                    val totalLength = edgePadding * 2f + iconSize + spacing + textWidth
                                    val sweepAngle = ((totalLength / Rc) * (180f / Math.PI.toFloat())).coerceIn(45f, 130f)
                                    val startAngle = 270f - sweepAngle / 2f
                                    val endAngle = 270f + sweepAngle / 2f
                                    
                                    val arcPath = android.graphics.Path().apply {
                                        val rect = android.graphics.RectF(centerX - Rc, centerY - Rc, centerX + Rc, centerY + Rc)
                                        addArc(rect, startAngle, sweepAngle)
                                    }
                                    
                                    val fStart = startAngle / 360f
                                    val fCenter = 270f / 360f
                                    val fEnd = endAngle / 360f
                                    val positions = floatArrayOf(0.0f, maxOf(0.0f, fStart), fCenter, fEnd, 1.0f)
                                    
                                    val borderColors = if (mapNight) {
                                        intArrayOf(0x00000000, 0x00000000, 0x4D000000, 0x00000000, 0x00000000)
                                    } else {
                                        intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                    }
                                    val borderShader = android.graphics.SweepGradient(centerX, centerY, borderColors, positions)
                                    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeWidth = 27.5.dp.toPx()
                                        strokeCap = android.graphics.Paint.Cap.BUTT
                                        setShader(borderShader)
                                    }
                                    canvas.drawPath(arcPath, borderPaint)
                                    
                                    val bgColors = if (mapNight) {
                                        intArrayOf(0x00000000, 0x00000000, 0xB3000000.toInt(), 0x00000000, 0x00000000)
                                    } else {
                                        intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                    }
                                    val bgShader = android.graphics.SweepGradient(centerX, centerY, bgColors, positions)
                                    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeWidth = 26.dp.toPx()
                                        strokeCap = android.graphics.Paint.Cap.BUTT
                                        setShader(bgShader)
                                    }
                                    canvas.drawPath(arcPath, bgPaint)
                                    
                                    val arcLength = Rc * (sweepAngle * Math.PI / 180.0).toFloat()
                                    val contentWidth = iconSize + spacing + textWidth
                                    val startOffset = (arcLength - contentWidth) / 2f
                                    
                                    val iconOffset = startOffset + iconSize / 2f
                                    val iconAngle = startAngle + (iconOffset / Rc) * (180f / Math.PI.toFloat())
                                    val thetaRad = iconAngle * (Math.PI / 180.0)
                                    val iconX = (centerX + Rc * Math.cos(thetaRad)).toFloat()
                                    val iconY = (centerY + Rc * Math.sin(thetaRad)).toFloat()
                                    
                                    drawManeuverArrow(canvas, iconX - iconSize/2f, iconY - iconSize/2f, iconSize, ui.maneuverType, if (mapNight) android.graphics.Color.WHITE else blinkingTint.toArgb(), 3.0.dp.toPx())
                                    
                                    val textStart = startOffset + iconSize + spacing
                                    tbtTextPaint.textAlign = android.graphics.Paint.Align.LEFT
                                    canvas.drawTextOnPath(distText ?: "", arcPath, textStart, 3.5.dp.toPx(), tbtTextPaint)
                                }
                            }
                        }
                    }
                }
                // Incoming/active call — arc overlay on the round dash, matching the dash video frame.
                incomingCall?.let { activeCall -> CallDashArc(activeCall) }
            }

            // The map always follows the rider; recenter is the only manual map control
            // in-app (zoom/pan live on the bike's joystick). Tucked into the blank
            // corner left of the round viewport.
            OpenDashIconBtn(
                OpenDashIcons.Recenter,
                onClick = { vm.recenter(); recenterKey++ },
                size = 44.dp,
                active = true,
                modifier = Modifier.align(Alignment.BottomEnd),
            )

            // Rider-marker style toggle (bike ⇄ arrow) — dash view only. Shows the icon of
            // the OTHER style; tap to switch the marker inside the round preview.
            OpenDashIconBtn(
                if (bikeMarker) OpenDashIcons.Navi else OpenDashIcons.Motor,
                onClick = { com.example.opendash.data.NavSettings.setBikeMarker(context, !bikeMarker) },
                size = 44.dp,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            // Map day/night: cycles Day → Night → Auto (auto = night 7 pm–6 am). Applies
            // to every map — dash preview, Navigate map and the streamed dash frame.
            OpenDashIconBtn(
                when (mapTheme) {
                    com.example.opendash.data.NavSettings.MapTheme.DAY -> OpenDashIcons.Sun
                    com.example.opendash.data.NavSettings.MapTheme.NIGHT -> OpenDashIcons.Moon
                    com.example.opendash.data.NavSettings.MapTheme.AUTO -> OpenDashIcons.ThemeAuto
                },
                onClick = {
                    val next = when (mapTheme) {
                        com.example.opendash.data.NavSettings.MapTheme.DAY -> com.example.opendash.data.NavSettings.MapTheme.NIGHT
                        com.example.opendash.data.NavSettings.MapTheme.NIGHT -> com.example.opendash.data.NavSettings.MapTheme.AUTO
                        com.example.opendash.data.NavSettings.MapTheme.AUTO -> com.example.opendash.data.NavSettings.MapTheme.DAY
                    }
                    com.example.opendash.data.NavSettings.setMapTheme(context, next)
                },
                size = 44.dp,
                modifier = Modifier.align(Alignment.TopStart),
            )

            // Active Group Ride Intercom mic toggle button
            val groupRideState by com.example.opendash.data.GroupRide.state.collectAsState()
            val intercomState by com.example.opendash.data.IntercomEngine.state.collectAsState()
            if (groupRideState.active && groupRideState.isIntercomActive) {
                OpenDashIconBtn(
                    icon = if (intercomState.isMuted) OpenDashIcons.MicOff else OpenDashIcons.Mic,
                    onClick = { com.example.opendash.data.IntercomEngine.toggleMute() },
                    size = 44.dp,
                    active = !intercomState.isMuted,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 52.dp)
                )
            }
        }


        val track = nowPlaying
        if (track != null && incomingCall == null) {
            Spacer(Modifier.height(14.dp))
            NowPlayingCard(
                track = track,
                onPrev = { vm.skipPrevious() },
                onNext = { vm.skipNext() },
                onPlayPause = { vm.playPause() }
            )
        }

        Spacer(Modifier.height(14.dp))

        // Live info strip — real remaining distance, ETA + satellite toggle
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(
                    ui.remainingKm?.let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it) } ?: "—",
                    if (ui.remainingKm != null) "km" else "", "Remaining",
                ),
                ui.etaMinutes.let { mins ->
                    when {
                        mins == null -> Triple("—", "", "ETA")
                        mins >= 60   -> Triple("${mins / 60}h ${mins % 60}m", "", "ETA")
                        else         -> Triple("$mins", "min", "ETA")
                    }
                },
            ).forEach { (v, u, k) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(vertical = 5.dp, horizontal = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                        Text(v, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                        if (u.isNotEmpty()) {
                            Spacer(Modifier.width(3.dp))
                            Text(u, color = MaterialTheme.colorScheme.outline, fontSize = 9.5.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 1.dp))
                        }
                    }
                    Text(k, color = MaterialTheme.colorScheme.outline, fontSize = 9.5.sp, fontFamily = GeistFamily, modifier = Modifier.padding(top = 1.dp))
                }
            }

            // Satellite toggle tile, in the slot the zoom readout used to occupy.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (satellite) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { satellite = !satellite }
                    .padding(vertical = 5.dp, horizontal = 6.dp),
            ) {
                Icon(OpenDashIcons.Layers, null, tint = if (satellite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Text("Satellite", color = if (satellite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontSize = 9.5.sp, fontFamily = GeistFamily, modifier = Modifier.padding(top = 1.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Compact icon-chip controls ──
        val chipMod: @Composable (Boolean, () -> Unit) -> Modifier = { active, onClick ->
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .clickable { onClick() }
                .padding(vertical = 8.dp)
        }

        // Sound selector: 3 icon chips (Off / Chime / Voice)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            val isOff   = voiceMode == VoiceMode.OFF
            val isChime = voiceMode == VoiceMode.CHIME
            val isFull  = voiceMode == VoiceMode.FULL
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(isOff) { voiceManager.setMode(VoiceMode.OFF) }
            ) {
                Icon(OpenDashIcons.SpeakerOff, null, tint = if (isOff) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.height(3.dp))
                Text("Silent", color = if (isOff) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontSize = 10.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(isChime) { voiceManager.setMode(VoiceMode.CHIME) }
            ) {
                Icon(OpenDashIcons.Bell, null, tint = if (isChime) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.height(3.dp))
                Text("Chime", color = if (isChime) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontSize = 10.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(isFull) { voiceManager.setMode(VoiceMode.FULL) }
            ) {
                Icon(OpenDashIcons.Speaker, null, tint = if (isFull) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.height(3.dp))
                Text("Voice", color = if (isFull) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontSize = 10.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
        }

        // Exit navigation & Disconnect buttons
        if (streaming) {
            Spacer(Modifier.height(16.dp))
            if (ui.destinationName != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OpenDashBtn(
                        "Exit navigation",
                        onClick = { vm.exitNavigation() },
                        icon = OpenDashIcons.Navi,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Md,
                        modifier = Modifier.weight(1f),
                    )
                    OpenDashBtn(
                        "Disconnect",
                        onClick = { vm.disconnect() },
                        icon = OpenDashIcons.Power,
                        variant = BtnVariant.Danger,
                        size = BtnSize.Md,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                OpenDashBtn(
                    "Disconnect",
                    onClick = { vm.disconnect() },
                    icon = OpenDashIcons.Power,
                    variant = BtnVariant.Danger,
                    size = BtnSize.Md,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Mock of the cluster firmware's own bottom section so the preview matches what the bike
 *  really shows — our stream never covers this area. During navigation the dash draws an
 *  olive route banner (destination + remaining km) from the protocol's route-card channel,
 *  which mirrors exactly what we send it. Values the phone can't know (gear, fuel) are
 *  illustrative; speed is GPS. */
@Composable
private fun NativeClusterMock(
    speedKmh: Int?,
    bannerText: String?,
    remainingKm: Double?,
    modifier: Modifier = Modifier,
) {
    val amber = Color(0xFFE9B63B)
    val olive = Color(0xFF8F7C2E)
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(30_000) }
    }
    val time = remember(nowMs) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(nowMs))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        // Route banner while navigating (dash renders it from our route-card data);
        // plain amber divider when idle, like the dash's wallpaper mode.
        if (bannerText != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(19.dp)
                    .background(olive)
                    .padding(horizontal = 32.dp),
            ) {
                Text(
                    bannerText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                remainingKm?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (it >= 10) "%.0f km".format(it) else "%.1f km".format(it),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(8.dp).background(amber))
        }
        Spacer(Modifier.height(7.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        ) {
            Text(time, color = amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
            Spacer(Modifier.weight(1f))
            // Gear + speed pill, like the real cluster. Gear is bike-only data → placeholder
            // "N" in the cluster's green. Speed is live GPS, eased between fixes.
            val animatedSpeed by animateIntAsState(
                targetValue = speedKmh ?: 0,
                animationSpec = tween(durationMillis = 800, easing = LinearEasing),
                label = "clusterSpeed",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(1.5.dp, amber, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 3.dp),
            ) {
                Text("N", color = Color(0xFF27C445), fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                Spacer(Modifier.width(12.dp))
                Text("$animatedSpeed", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                Spacer(Modifier.width(4.dp))
                Text("km/h", color = amber, fontSize = 9.5.sp, fontFamily = GeistFamily, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        // Fuel gauge (decorative — the bike renders the real level).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(OpenDashIcons.Fuel, null, tint = amber, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(2.dp))
            repeat(6) { i ->
                Box(
                    Modifier
                        .width(15.dp)
                        .height(5.dp)
                        .background(if (i < 4) Color(0xFFDADDE0) else Color(0xFF2A2E31))
                )
            }
        }
    }
}

/** Call overlay on the round dash preview — same top-arc treatment as the music crescent,
 *  mirroring the dash video frame's drawCallOverlay. Incoming: red decline (left) + caller
 *  + green accept (right). Active: red end (left) + "caller • duration". */
@Composable
private fun CallDashArc(call: IncomingCall) {
    val ctx = LocalContext.current
    val acceptBmp = remember { ContextCompat.getDrawable(ctx, R.drawable.ic_call)!!.toBitmap(28, 28) }
    val endBmp = remember { ContextCompat.getDrawable(ctx, R.drawable.ic_call_end)!!.toBitmap(28, 28) }
    val activeSince = remember(call.incoming, call.dialing) { System.currentTimeMillis() }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.incoming, call.dialing) {
        while (!call.incoming && !call.dialing) { nowMs = System.currentTimeMillis(); delay(1000) }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.height / 2f
        val rc = r - 16.dp.toPx()
        val iconD = 12.dp.toPx()
        val spacing = 7.dp.toPx()
        val edgePadding = 14.dp.toPx()
        drawIntoCanvas { cc ->
            val canvas = cc.nativeCanvas
            val namePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E2022.toInt(); textSize = 11.sp.toPx(); isFakeBoldText = true
            }
            val callingPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E2022.toInt(); textSize = 11.sp.toPx(); isFakeBoldText = false
            }
            val dur = if (!call.incoming && !call.dialing) {
                val s = ((nowMs - activeSince) / 1000).coerceAtLeast(0)
                "  •  %d:%02d".format(s / 60, s % 60)
            } else ""
            val prefix = if (call.dialing) "Calling " else ""
            val label = if (call.dialing) call.caller else (call.caller + dur).let { if (it.length > 20) it.take(19) + "…" else it }
            val prefixWidth = if (call.dialing) callingPaint.measureText(prefix) else 0f
            val nameWidth = namePaint.measureText(label)
            val textWidth = prefixWidth + nameWidth
            val showReject = !call.dialing
            val showCallingIcon = call.dialing
            val showLeftIcon = showReject || showCallingIcon
            val contentWidth = (if (showLeftIcon) iconD + spacing else 0f) + textWidth + (if (call.incoming) spacing + iconD else 0f)
            val totalLength = edgePadding * 2f + contentWidth
            val sweep = ((totalLength / rc) * (180f / Math.PI.toFloat())).coerceIn(45f, 150f)
            val startAngle = 270f - sweep / 2f
            val endAngle = 270f + sweep / 2f
            val rect = android.graphics.RectF(cx - rc, cy - rc, cx + rc, cy + rc)
            val arcPath = android.graphics.Path().apply { addArc(rect, startAngle, sweep) }
            val positions = floatArrayOf(0f, maxOf(0f, startAngle / 360f), 270f / 360f, endAngle / 360f, 1f)
            canvas.drawPath(arcPath, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 30.dp.toPx(); strokeCap = android.graphics.Paint.Cap.BUTT
                setShader(android.graphics.SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF), positions))
            })
            canvas.drawPath(arcPath, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 28.dp.toPx(); strokeCap = android.graphics.Paint.Cap.BUTT
                setShader(android.graphics.SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xE6FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF), positions))
            })
            val arcLength = rc * (sweep * Math.PI / 180.0).toFloat()
            val startOffset = (arcLength - contentWidth) / 2f
            fun place(offset: Float, color: Int, bmp: android.graphics.Bitmap) {
                val ang = startAngle + (offset / rc) * (180f / Math.PI.toFloat())
                val t = ang * (Math.PI / 180.0)
                val x = (cx + rc * Math.cos(t)).toFloat()
                val y = (cy + rc * Math.sin(t)).toFloat()
                canvas.drawCircle(x, y, iconD / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
                val s = iconD * 0.6f
                canvas.drawBitmap(bmp, null, android.graphics.RectF(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f), null)
            }
            if (showReject) {
                place(startOffset + iconD / 2f, 0xFFFF3B30.toInt(), endBmp)
            } else if (showCallingIcon) {
                place(startOffset + iconD / 2f, 0xFF34C759.toInt(), acceptBmp)
            }
            namePaint.textAlign = android.graphics.Paint.Align.LEFT
            val textStartOffset = if (showLeftIcon) startOffset + iconD + spacing else startOffset
            if (call.dialing) {
                callingPaint.textAlign = android.graphics.Paint.Align.LEFT
                canvas.drawTextOnPath(prefix, arcPath, textStartOffset, 4.dp.toPx(), callingPaint)
                canvas.drawTextOnPath(label, arcPath, textStartOffset + prefixWidth, 4.dp.toPx(), namePaint)
            } else {
                canvas.drawTextOnPath(label, arcPath, textStartOffset, 4.dp.toPx(), namePaint)
            }
            if (call.incoming) place(startOffset + contentWidth - iconD / 2f, 0xFF34C759.toInt(), acceptBmp)
        }
    }
}
