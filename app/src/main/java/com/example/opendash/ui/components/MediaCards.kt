package com.example.opendash.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.media.IncomingCall
import com.example.opendash.media.NowPlaying
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.ui.theme.TextHi
import com.example.opendash.ui.theme.TextLo

@Composable
fun NowPlayingCard(
    track: NowPlaying,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    OpenDashCard(
        modifier = modifier.fillMaxWidth(),
        padding = 12.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Album art
            val art = track.art
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Track info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title.ifBlank { "Unknown Title" },
                    color = TextHi,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist.ifBlank { "Unknown Artist" },
                    color = TextLo,
                    fontSize = 13.sp,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(12.dp))

            // Control buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OpenDashIconBtn(
                    icon = Icons.Outlined.SkipPrevious,
                    onClick = onPrev,
                    size = 38.dp
                )
                OpenDashIconBtn(
                    icon = if (track.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    onClick = onPlayPause,
                    size = 38.dp
                )
                OpenDashIconBtn(
                    icon = Icons.Outlined.SkipNext,
                    onClick = onNext,
                    size = 38.dp
                )
            }
        }
    }
}

@Composable
fun CallerCard(
    call: IncomingCall,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    OpenDashCard(
        modifier = modifier.fillMaxWidth(),
        padding = 14.dp,
        glow = call.incoming // Pulsing glow effect for incoming calls
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (call.incoming) MaterialTheme.colorScheme.primaryContainer 
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Call,
                    contentDescription = null,
                    tint = if (call.incoming) MaterialTheme.colorScheme.primary else TextHi,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // Call status & info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (call.incoming) "INCOMING CALL" else "ACTIVE CALL",
                    color = if (call.incoming) MaterialTheme.colorScheme.primary else TextLo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = call.caller,
                    color = TextHi,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(12.dp))

            // Action buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (call.incoming) {
                    OpenDashIconBtn(
                        icon = Icons.Outlined.Call,
                        onClick = onAnswer,
                        size = 38.dp,
                        tint = Color(0xFF22C55E) // Green for accept
                    )
                }
                OpenDashIconBtn(
                    icon = Icons.Outlined.CallEnd,
                    onClick = onDecline,
                    size = 38.dp,
                    tint = Color(0xFFEF4444) // Red for decline/hangup
                )
            }
        }
    }
}
