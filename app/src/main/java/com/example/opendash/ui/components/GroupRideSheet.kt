package com.example.opendash.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.opendash.data.GroupRide
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.theme.*

/**
 * Group Ride dialog: start a ride (get a shareable 6-char code), join by code, and see
 * who's riding with you (name, speed, distance from you, staleness). Live positions ride
 * over a Supabase Realtime broadcast channel — see [GroupRide] for the transport.
 */
@Composable
fun GroupRideSheet(
    riderLat: Double?,
    riderLng: Double?,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val state by GroupRide.state.collectAsState()
    var nameField by remember(state.riderName) { mutableStateOf(state.riderName) }
    var codeField by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 18.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(OpenDashIcons.GroupRide, null, tint = Gold, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "Group ride",
                        color = TextHi, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily,
                        modifier = Modifier.weight(1f),
                    )
                    OpenDashIconBtn(OpenDashIcons.X, onClick = onDismiss, size = 32.dp)
                }
                Spacer(Modifier.height(12.dp))

                when {
                    !GroupRide.isConfigured -> Text(
                        "Group rides need a (free) Supabase project. Add SUPABASE_URL and " +
                            "SUPABASE_ANON_KEY to local.properties and rebuild.",
                        color = TextMid, fontSize = 13.sp, fontFamily = GeistFamily,
                    )

                    state.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Gold, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Joining ${state.code}…", color = TextMid, fontSize = 13.5.sp, fontFamily = GeistFamily)
                    }

                    state.active -> ActiveRide(state, riderLat, riderLng) {
                        val code = state.code ?: return@ActiveRide
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join my OpenDash group ride — open the app, tap Group ride and enter code: $code",
                            )
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share ride code"))
                    }

                    else -> {
                        OutlinedTextField(
                            value = nameField,
                            onValueChange = { nameField = it.take(20) },
                            label = { Text("Your name", fontFamily = GeistFamily, fontSize = 12.sp) },
                            singleLine = true,
                            colors = groupRideFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OpenDashBtn(
                            "Start a new ride",
                            onClick = {
                                GroupRide.setRiderName(nameField)
                                GroupRide.createRide()
                            },
                            icon = OpenDashIcons.GroupRide,
                            variant = BtnVariant.Primary,
                            size = BtnSize.Md,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = codeField,
                                onValueChange = { codeField = it.uppercase().take(6) },
                                label = { Text("Ride code", fontFamily = GeistFamily, fontSize = 12.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                colors = groupRideFieldColors(),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = GeistMonoFamily, fontSize = 16.sp, letterSpacing = 3.sp,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            OpenDashBtn(
                                "Join",
                                onClick = {
                                    GroupRide.setRiderName(nameField)
                                    GroupRide.joinRide(codeField)
                                },
                                variant = BtnVariant.Ghost,
                                size = BtnSize.Md,
                            )
                        }
                        state.error?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, color = Warn, fontSize = 12.5.sp, fontFamily = GeistFamily)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveRide(
    state: GroupRide.State,
    riderLat: Double?,
    riderLng: Double?,
    onShare: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Code, big and mono — this is what you read out to friends.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surf1)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ride code", color = TextLo, fontSize = 11.sp, fontFamily = GeistFamily)
                Text(
                    state.code.orEmpty(),
                    color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily, letterSpacing = 5.sp,
                )
            }
            OpenDashIconBtn(OpenDashIcons.Share, onClick = onShare, size = 40.dp, active = true)
        }
        Spacer(Modifier.height(12.dp))

        if (state.peers.isEmpty()) {
            Text(
                "No one else yet — share the code. Riders appear here and on the map.",
                color = TextMid, fontSize = 12.5.sp, fontFamily = GeistFamily,
            )
        } else {
            state.peers.forEach { peer ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                ) {
                    Icon(
                        OpenDashIcons.Person, null,
                        tint = if (peer.isStale) TextLo else Ok,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        peer.name,
                        color = if (peer.isStale) TextLo else TextHi,
                        fontSize = 13.5.sp, fontWeight = FontWeight.Medium, fontFamily = GeistFamily,
                        modifier = Modifier.weight(1f),
                    )
                    val ago = (System.currentTimeMillis() - peer.updatedAtMs) / 1000
                    val status = when {
                        peer.isStale && ago >= 60 -> "seen ${ago / 60}m ago"
                        peer.isStale -> "seen ${ago}s ago"
                        else -> buildString {
                            append("${peer.speedKmh} km/h")
                            if (riderLat != null && riderLng != null) {
                                val d = GeoPoint.distMeters(
                                    GeoPoint(riderLat, riderLng), GeoPoint(peer.lat, peer.lng),
                                )
                                append("  ·  ")
                                append(if (d >= 1000) "%.1f km".format(d / 1000) else "${d.toInt()} m")
                            }
                        }
                    }
                    Text(status, color = TextMid, fontSize = 12.sp, fontFamily = GeistMonoFamily)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OpenDashBtn(
            "Leave ride",
            onClick = { GroupRide.leaveRide() },
            icon = OpenDashIcons.X,
            variant = BtnVariant.Danger,
            size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun groupRideFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextHi,
    unfocusedTextColor = TextHi,
    focusedBorderColor = Gold,
    unfocusedBorderColor = Line2,
    focusedLabelColor = Gold,
    unfocusedLabelColor = TextLo,
    cursorColor = Gold,
)
