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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.DashViewModel
import com.example.opendash.dash.nav.VoiceMode
import kotlinx.coroutines.delay

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
    var pan by remember { mutableStateOf(Offset.Zero) }
    var adjustMode by remember { mutableStateOf(true) }
    var joystickVelocity by remember { mutableStateOf(Offset.Zero) }
    var satellite by rememberSaveable { mutableStateOf(false) }

    val voiceManager = remember { com.example.opendash.dash.nav.VoiceManager.get(context) }
    val voiceMode by voiceManager.mode.collectAsState()

    // Physical joystick button → preview-only nudge (real map pan happens in the ViewModel)
    LaunchedEffect(ui.lastButton) {
        val b = ui.lastButton ?: return@LaunchedEffect
        when {
            b.startsWith("→") -> pan = Offset((pan.x - 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("←") -> pan = Offset((pan.x + 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("↓") -> pan = Offset(pan.x, (pan.y - 12f).coerceIn(-46f, 46f))
            b.startsWith("↑") -> pan = Offset(pan.x, (pan.y + 12f).coerceIn(-46f, 46f))
            b.startsWith("●") -> pan = Offset.Zero
        }
    }

    LaunchedEffect(joystickVelocity, adjustMode) {
        while (adjustMode && (joystickVelocity.x != 0f || joystickVelocity.y != 0f)) {
            pan = Offset(
                (pan.x - joystickVelocity.x * 2.4f).coerceIn(-46f, 46f),
                (pan.y - joystickVelocity.y * 2.4f).coerceIn(-46f, 46f),
            )
            vm.panBy(joystickVelocity.x * 4f, joystickVelocity.y * 4f)
            delay(16)
        }
    }

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
        val call = incomingCall
        if (call != null) {
            CallerCard(
                call = call,
                onAnswer = { vm.answerCall(call) },
                onDecline = { vm.endCall(call) }
            )
            Spacer(Modifier.height(16.dp))
        }

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
                                color = TextHi,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                pendingSsid,
                                color = Gold,
                                fontSize = 12.sp,
                                fontFamily = GeistMonoFamily,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            Text(
                                "OpenDash will remember this exact SSID for future reconnects.",
                                color = TextMid,
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
            // Real Google Maps, clipped to the round Tripper shape.
            Box(
                modifier = Modifier
                    .size(272.dp)
                    .clip(CircleShape)
                    .border(6.dp, Color(0xFF0D0F10), CircleShape)
                    .border(2.dp, Line2, CircleShape),
            ) {
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
                    followMode = ui.followMode,
                    joystickVelocity = joystickVelocity,
                    satellite = satellite,
                    modifier = Modifier.fillMaxSize(),
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
                        if (ui.offRoute) Warn else blackColor
                    }
                    
                    val distText = nextTurnM?.let {
                        if (it < 1000.0) "${it.toInt()} m" else "%.1f km".format(it / 1000.0)
                    }

                    val hasMusic = ui.showMediaOverlay && nowPlaying != null
                    val hasNav = ui.maneuver != null

                    if (hasMusic || hasNav) {
                        val arrowPainter = rememberVectorPainter(image = arrowIcon)
                        
                        Canvas(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val w = size.width
                            val h = size.height
                            val centerX = w / 2f
                            val centerY = h / 2f
                            val R = h / 2f
                            
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
                                        color = 0xFF1E2022.toInt()
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
                                    
                                    val borderColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                    val borderShader = android.graphics.SweepGradient(centerX, centerY, borderColors, positions)
                                    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeWidth = 27.5.dp.toPx()
                                        strokeCap = android.graphics.Paint.Cap.BUTT
                                        setShader(borderShader)
                                    }
                                    canvas.drawPath(arcPath, borderPaint)
                                    
                                    val bgColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
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
                                    
                                    val whitePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        color = android.graphics.Color.WHITE
                                        style = android.graphics.Paint.Style.FILL
                                    }
                                    canvas.drawCircle(artX, artY, 9.5.dp.toPx(), whitePaint)
                                    
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
                                
                                if (hasNav) {
                                    // Draw Concentric TBT Crescent at the inner position (Rc = R - 40.dp)
                                    val RcTbt = R - 40.dp.toPx()
                                    val iconSize = 15.dp.toPx()
                                    val tbtSpacing = 5.dp.toPx()
                                    val tbtEdgePadding = 12.dp.toPx()
                                    
                                    drawIntoCanvas { composeCanvas ->
                                        val canvas = composeCanvas.nativeCanvas
                                         val tbtTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            color = 0xFF1E2022.toInt()
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
                                        
                                        val tbtBorderColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                        val tbtBorderShader = android.graphics.SweepGradient(centerX, centerY, tbtBorderColors, tbtPositions)
                                        val tbtBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeWidth = 23.5.dp.toPx()
                                            strokeCap = android.graphics.Paint.Cap.BUTT
                                            setShader(tbtBorderShader)
                                        }
                                        canvas.drawPath(tbtArcPath, tbtBorderPaint)
                                        
                                        val tbtBgColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
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
                                        
                                        drawManeuverArrow(canvas, iconX - iconSize/2f, iconY - iconSize/2f, iconSize, ui.maneuverType, blinkingTint.toArgb(), 2.5.dp.toPx())
                                        
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
                                        color = 0xFF1E2022.toInt()
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
                                    
                                    val borderColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                                    val borderShader = android.graphics.SweepGradient(centerX, centerY, borderColors, positions)
                                    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeWidth = 27.5.dp.toPx()
                                        strokeCap = android.graphics.Paint.Cap.BUTT
                                        setShader(borderShader)
                                    }
                                    canvas.drawPath(arcPath, borderPaint)
                                    
                                    val bgColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
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
                                    
                                    drawManeuverArrow(canvas, iconX - iconSize/2f, iconY - iconSize/2f, iconSize, ui.maneuverType, blinkingTint.toArgb(), 3.0.dp.toPx())
                                    
                                    val textStart = startOffset + iconSize + spacing
                                    tbtTextPaint.textAlign = android.graphics.Paint.Align.LEFT
                                    canvas.drawTextOnPath(distText ?: "", arcPath, textStart, 3.5.dp.toPx(), tbtTextPaint)
                                }
                            }
                        }
                    }
                }
            }
        }

        val track = nowPlaying
        if (track != null) {
            Spacer(Modifier.height(14.dp))
            NowPlayingCard(
                track = track,
                onPrev = { vm.skipPrevious() },
                onNext = { vm.skipNext() },
                onPlayPause = { vm.playPause() }
            )
        }

        Spacer(Modifier.height(14.dp))

        // Live info strip — real remaining distance, ETA, zoom
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(
                    ui.remainingKm?.let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it) } ?: "—",
                    if (ui.remainingKm != null) "km" else "", "Remaining",
                ),
                Triple(ui.etaMinutes?.toString() ?: "—", if (ui.etaMinutes != null) "min" else "", "ETA"),
                Triple("z${ui.mapZoom}", "", "Zoom"),
            ).forEach { (v, u, k) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surf1)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                        Text(v, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                        if (u.isNotEmpty()) {
                            Spacer(Modifier.width(3.dp))
                            Text(u, color = TextLo, fontSize = 10.5.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                    Text(k, color = TextLo, fontSize = 11.5.sp, fontFamily = GeistFamily, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Compact icon-chip controls ──
        val chipMod: @Composable (Boolean, () -> Unit) -> Modifier = { active, onClick ->
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (active) GoldTint else Surf1)
                .clickable { onClick() }
                .padding(vertical = 14.dp)
        }

        // Row 1: Map Adjust + Heading-up
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(adjustMode) { adjustMode = !adjustMode }
            ) {
                Icon(OpenDashIcons.Cross, null, tint = if (adjustMode) Gold else TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text("Adjust", color = if (adjustMode) Gold else TextLo, fontSize = 11.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(ui.headingUp) { vm.toggleHeadingUp() }
            ) {
                Icon(OpenDashIcons.Navi, null, tint = if (ui.headingUp) Gold else TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text("Heading-up", color = if (ui.headingUp) Gold else TextLo, fontSize = 11.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(satellite) { satellite = !satellite }
            ) {
                Icon(OpenDashIcons.Layers, null, tint = if (satellite) Gold else TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text("Satellite", color = if (satellite) Gold else TextLo, fontSize = 11.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Sound selector: 3 icon chips (Off / Chime / Voice)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            val isOff   = voiceMode == VoiceMode.OFF
            val isChime = voiceMode == VoiceMode.CHIME
            val isFull  = voiceMode == VoiceMode.FULL
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(isOff) { voiceManager.setMode(VoiceMode.OFF) }
            ) {
                Icon(OpenDashIcons.SpeakerOff, null, tint = if (isOff) Gold else TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text("Silent", color = if (isOff) Gold else TextLo, fontSize = 11.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(isChime) { voiceManager.setMode(VoiceMode.CHIME) }
            ) {
                Icon(OpenDashIcons.Bell, null, tint = if (isChime) Gold else TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text("Chime", color = if (isChime) Gold else TextLo, fontSize = 11.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = chipMod(isFull) { voiceManager.setMode(VoiceMode.FULL) }
            ) {
                Icon(OpenDashIcons.Speaker, null, tint = if (isFull) Gold else TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text("Voice", color = if (isFull) Gold else TextLo, fontSize = 11.sp, fontFamily = GeistFamily, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Controls: joystick + zoom (drive the actual dash map)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Surf1)
                    .padding(vertical = 16.dp, horizontal = 12.dp),
            ) {
                Joystick(
                    size = 128.dp,
                    onMove = { v -> joystickVelocity = if (adjustMode) v else Offset.Zero },
                )
                Spacer(Modifier.height(9.dp))
                Text("Pan", color = TextLo, fontSize = 11.5.sp, fontFamily = GeistFamily)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpenDashIconBtn(OpenDashIcons.Plus,  onClick = { vm.zoomIn() },  size = 52.dp)
                Text("z${ui.mapZoom}", color = Gold, fontSize = 12.sp, fontFamily = GeistMonoFamily, fontWeight = FontWeight.SemiBold)
                OpenDashIconBtn(OpenDashIcons.Minus, onClick = { vm.zoomOut() }, size = 52.dp)
                OpenDashIconBtn(OpenDashIcons.Recenter, onClick = { vm.recenter(); pan = Offset.Zero }, size = 52.dp, active = true)
            }
        }

        // Exit navigation → free roam (keeps streaming, just drops the route)
        if (streaming && ui.destinationName != null) {
            Spacer(Modifier.height(16.dp))
            OpenDashBtn(
                "Exit navigation",
                onClick = { vm.exitNavigation() },
                icon = OpenDashIcons.Navi,
                variant = BtnVariant.Ghost,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Disconnect button when streaming
        if (streaming) {
            Spacer(Modifier.height(20.dp))
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
