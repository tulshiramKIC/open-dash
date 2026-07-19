package com.example.opendash.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.opendash.data.GroupRide
import com.example.opendash.data.IntercomEngine
import com.example.opendash.data.NavSettings
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.theme.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay

@Composable
fun IntercomSheet(
    initialCode: String? = null,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val state by GroupRide.state.collectAsState()
    var nameField by remember(state.riderName) { mutableStateOf(state.riderName) }
    var codeField by remember(initialCode) { mutableStateOf(initialCode ?: "") }
    var isNameError by remember { mutableStateOf(false) }
    val boxyShape = RoundedCornerShape(12.dp)

    LaunchedEffect(isNameError) {
        if (isNameError) {
            delay(600)
            isNameError = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val flickerAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Dialog(onDismissRequest = onDismiss) {
        OpenDashCard(
            modifier = Modifier.fillMaxWidth(),
            glow = true,
            padding = 22.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.example.opendash.R.drawable.ic_intercom),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Mesh Intercom",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(20.dp))

                when {
                    !GroupRide.isConfigured -> Text(
                        "Mesh Intercom requires Supabase configuration. Configure SUPABASE_URL and SUPABASE_ANON_KEY in Settings → API Keys.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.5.sp,
                        fontFamily = GeistFamily,
                    )

                    state.connecting -> Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Establishing secure connection to ${state.code}…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.5.sp,
                            fontFamily = GeistFamily,
                            textAlign = TextAlign.Center
                        )
                    }

                    state.active && state.isIntercomActive -> ActiveIntercom(state) {
                        val code = state.code ?: return@ActiveIntercom
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join my OpenDash Mesh Intercom call. Session code: *$code*",
                            )
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share invite link"))
                    }

                    else -> {
                        OutlinedTextField(
                            value = nameField,
                            onValueChange = {
                                nameField = it.take(20)
                                if (isNameError && it.isNotBlank()) isNameError = false
                            },
                            label = { Text("Display Name", fontFamily = GeistFamily, fontSize = 12.sp) },
                            singleLine = true,
                            isError = isNameError,
                            shape = boxyShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = if (isNameError) MaterialTheme.colorScheme.error.copy(alpha = flickerAlpha) else MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = if (isNameError) MaterialTheme.colorScheme.error.copy(alpha = flickerAlpha) else MaterialTheme.colorScheme.outlineVariant,
                                errorBorderColor = MaterialTheme.colorScheme.error.copy(alpha = flickerAlpha),
                                focusedLabelColor = if (isNameError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                unfocusedLabelColor = if (isNameError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(14.dp))
                        OpenDashBtn(
                            "Start",
                            onClick = {
                                if (nameField.isBlank()) {
                                    isNameError = true
                                    return@OpenDashBtn
                                }
                                GroupRide.setRiderName(nameField)
                                GroupRide.createRide(intercomOnly = true)
                            },
                            icon = OpenDashIcons.Mic,
                            variant = BtnVariant.Primary,
                            size = BtnSize.Md,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(20.dp))
                        
                        // Modern visual separator with text
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                            Text(
                                "OR JOIN EXISTING",
                                color = MaterialTheme.colorScheme.outline,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                        }
                        Spacer(Modifier.height(14.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = boxyShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BasicTextField(
                                    value = codeField,
                                    onValueChange = { codeField = it.uppercase().take(6) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = GeistMonoFamily,
                                        fontSize = 15.sp,
                                        letterSpacing = 2.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (codeField.isEmpty()) {
                                                Text(
                                                    "Enter Intercom Code",
                                                    color = MaterialTheme.colorScheme.outline,
                                                    fontSize = 13.5.sp,
                                                    fontFamily = GeistFamily
                                                )
                                            }
                                            innerTextField()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .clickable {
                                            if (nameField.isBlank()) {
                                                isNameError = true
                                                return@clickable
                                            }
                                            GroupRide.setRiderName(nameField)
                                            GroupRide.joinRide(codeField, intercomOnly = true)
                                        }
                                        .padding(horizontal = 22.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Join",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GeistFamily
                                    )
                                }
                            }
                        }
                        state.error?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, fontFamily = GeistFamily)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveIntercom(
    state: GroupRide.State,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val intercomState by IntercomEngine.state.collectAsState()
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            if (intercomState.isMuted) IntercomEngine.toggleMute()
        }
    }
    val boxyShape = RoundedCornerShape(12.dp)

    Column(Modifier.fillMaxWidth()) {
        // Invite card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = boxyShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SESSION INVITE CODE",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        state.code.orEmpty().chunked(3).joinToString(" "),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GeistMonoFamily,
                        letterSpacing = 2.sp,
                    )
                }
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(1000L)
                        copied = false
                    }
                }
                OpenDashIconBtn(
                    if (copied) OpenDashIcons.Check else OpenDashIcons.Copy,
                    onClick = {
                        state.code?.let { code ->
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(code))
                            copied = true
                        }
                    },
                    size = 42.dp,
                    shape = boxyShape,
                    active = true
                )
                Spacer(Modifier.width(8.dp))
                OpenDashIconBtn(
                    OpenDashIcons.Share,
                    onClick = onShare,
                    size = 42.dp,
                    shape = boxyShape,
                    active = true
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // Intercom voice control card (Audio dashboard)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = boxyShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                // Pulse state dot indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (!hasMicPermission) MaterialTheme.colorScheme.outline
                            else if (intercomState.isMuted) MaterialTheme.colorScheme.error
                            else Ok
                        )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Voice Terminal",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                OpenDashIconBtn(
                    icon = if (intercomState.isMuted) OpenDashIcons.MicOff else OpenDashIcons.Mic,
                    onClick = {
                        if (!hasMicPermission) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            IntercomEngine.toggleMute()
                        }
                    },
                    size = 44.dp,
                    shape = boxyShape,
                    active = !intercomState.isMuted,
                    tint = if (intercomState.isMuted) MaterialTheme.colorScheme.error else null
                )
                Spacer(Modifier.width(8.dp))
                OpenDashIconBtn(
                    icon = if (intercomState.isSpeakerMuted) OpenDashIcons.SpeakerOff else OpenDashIcons.Speaker,
                    onClick = { IntercomEngine.toggleSpeakerMute() },
                    size = 44.dp,
                    shape = boxyShape,
                    active = !intercomState.isSpeakerMuted,
                    tint = if (intercomState.isSpeakerMuted) MaterialTheme.colorScheme.error else null
                )
            }
        }

        // Participant Terminal Section
        Spacer(Modifier.height(18.dp))
        Text(
            "ACTIVE CONNECTIONS (${state.peers.size})",
            color = MaterialTheme.colorScheme.outline,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistFamily,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(6.dp))

        if (state.peers.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = boxyShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Waiting for participants to join...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontFamily = GeistFamily
                    )
                }
            }
        } else {
            state.peers.forEach { peer ->
                val isAudioConnected = intercomState.activePeers.contains(peer.id)
                val avatarColorIdx = Math.abs(peer.id.hashCode()) % PEER_COLORS.size
                val avatarColor = if (peer.isStale) MaterialTheme.colorScheme.outline else Color(PEER_COLORS[avatarColorIdx])
                
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = boxyShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        // Boxy Initial Avatar
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(avatarColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                peer.name.trim().take(1).uppercase(),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                peer.name,
                                color = if (peer.isStale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily
                            )
                            Text(
                                if (peer.isStale) "Connection lost"
                                else if (isAudioConnected) "Voice streaming link active"
                                else "Connecting voice stream...",
                                fontSize = 11.sp,
                                color = if (peer.isStale) MaterialTheme.colorScheme.error
                                        else if (isAudioConnected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = GeistFamily
                            )
                        }
                        if (isAudioConnected && !peer.isStale) {
                            Icon(
                                OpenDashIcons.Mic,
                                contentDescription = "Active voice channel",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OpenDashBtn(
            "Exit",
            onClick = { GroupRide.stopIntercomOnly() },
            variant = BtnVariant.Danger,
            size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun intercomFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.primary,
)

private val PEER_COLORS = intArrayOf(
    0xFF1A73E8.toInt(),
    0xFF34A853.toInt(),
    0xFFFBBC05.toInt(),
    0xFFEA4335.toInt(),
    0xFF9C27B0.toInt(),
    0xFFE91E63.toInt(),
    0xFF00ACC1.toInt(),
)
