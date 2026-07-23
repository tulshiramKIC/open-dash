package com.example.opendash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.opendash.data.CrashDetector
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.ui.theme.GeistMonoFamily
import kotlinx.coroutines.delay

/**
 * Global crash-SOS overlay: renders on top of everything whenever [CrashDetector]
 * has an active alert. Counting → huge countdown + "I'M OK" cancel; Sent/Failed →
 * outcome + dismiss. Mounted once at the app root (MainActivity).
 */
@Composable
fun CrashAlertOverlay() {
    val alert by CrashDetector.alert.collectAsState()

    when (val a = alert) {
        is CrashDetector.Alert.None -> Unit

        is CrashDetector.Alert.Counting -> Dialog(
            onDismissRequest = { /* only the buttons dismiss — no accidental swipe-away */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            OpenDashCard(modifier = Modifier.fillMaxWidth(), glow = true, padding = 24.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    var secondsLeft by remember {
                        mutableIntStateOf(
                            (((a.deadlineMs - System.currentTimeMillis()) / 1000).toInt()).coerceAtLeast(0)
                        )
                    }
                    LaunchedEffect(a.deadlineMs) {
                        while (true) {
                            secondsLeft =
                                (((a.deadlineMs - System.currentTimeMillis()) / 1000).toInt()).coerceAtLeast(0)
                            delay(250)
                        }
                    }
                    Text(
                        "POSSIBLE CRASH DETECTED",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GeistFamily,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "$secondsLeft",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GeistMonoFamily,
                    )
                    Text(
                        "SOS goes to your emergency contact — and to everyone in your ride group",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontFamily = GeistFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    OpenDashBtn(
                        "I'M OK — CANCEL",
                        onClick = { CrashDetector.cancel() },
                        variant = BtnVariant.Primary,
                        size = BtnSize.Md,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is CrashDetector.Alert.PeerSos -> Dialog(
            // Deliberate dismissal only — an alarm you can swipe away by accident isn't one.
            onDismissRequest = { },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            OpenDashCard(modifier = Modifier.fillMaxWidth(), glow = true, padding = 24.dp) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "${a.name.uppercase()} MAY HAVE CRASHED",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GeistFamily,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (a.hasLocation)
                            "Their OpenDash detected a hard impact with no response. " +
                                "Open their last position and check on them."
                        else
                            "Their OpenDash detected a hard impact with no response. " +
                                "No position was included — try the intercom or call them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.5.sp,
                        fontFamily = GeistFamily,
                        textAlign = TextAlign.Center,
                    )
                    if (a.hasLocation) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        OpenDashBtn(
                            "OPEN LAST POSITION",
                            onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("geo:${a.lat},${a.lng}?q=${a.lat},${a.lng}"),
                                        )
                                    )
                                }
                            },
                            variant = BtnVariant.Primary,
                            size = BtnSize.Md,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OpenDashBtn(
                        "DISMISS",
                        onClick = { CrashDetector.acknowledge() },
                        variant = BtnVariant.Secondary,
                        size = BtnSize.Md,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is CrashDetector.Alert.Sent, is CrashDetector.Alert.Failed -> Dialog(
            onDismissRequest = { CrashDetector.acknowledge() },
        ) {
            OpenDashCard(modifier = Modifier.fillMaxWidth(), glow = true, padding = 24.dp) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val (title, body) = when (a) {
                        is CrashDetector.Alert.Sent ->
                            "SOS SENT" to "Emergency SMS with your location went to ${a.contact}."
                        is CrashDetector.Alert.Failed ->
                            "SOS FAILED" to a.reason
                        else -> "" to ""
                    }
                    Text(
                        title,
                        color = if (a is CrashDetector.Alert.Sent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.5.sp,
                        fontFamily = GeistFamily,
                        textAlign = TextAlign.Center,
                    )
                    OpenDashBtn(
                        "OK",
                        onClick = { CrashDetector.acknowledge() },
                        variant = BtnVariant.Secondary,
                        size = BtnSize.Md,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
