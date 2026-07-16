package com.example.opendash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Legacy palette names, bridged onto Material theme roles so every screen that
 * still references them follows the active light/dark scheme automatically.
 * New code should use MaterialTheme.colorScheme directly; these aliases exist
 * so unmigrated screens restyle for free and can be cleaned up screen-by-screen.
 */

private val scheme
    @Composable get() = MaterialTheme.colorScheme

// Backgrounds and surfaces
val Bg0: Color @Composable get() = scheme.background
val Bg1: Color @Composable get() = scheme.surface
val Surf1: Color @Composable get() = scheme.surfaceContainer
val Surf2: Color @Composable get() = scheme.surfaceContainerHigh
val Surf3: Color @Composable get() = scheme.surfaceContainerHighest
val MapBase: Color @Composable get() = scheme.surfaceContainerLow

// Accent (historically gold; now the theme primary)
val Gold: Color @Composable get() = scheme.primary
val GoldBright: Color @Composable get() = scheme.primary
val GoldDeep: Color @Composable get() = lerp(scheme.primary, scheme.primaryContainer, 0.55f)
val GoldTint: Color @Composable get() = scheme.primaryContainer
val GoldTint2: Color @Composable get() = scheme.primary.copy(alpha = 0.14f)
val GoldGlow: Color @Composable get() = scheme.primary.copy(alpha = 0.30f)
val OnGold: Color @Composable get() = scheme.onPrimary

// Text
val TextHi: Color @Composable get() = scheme.onSurface
val TextMid: Color @Composable get() = scheme.onSurfaceVariant
val TextLo: Color @Composable get() = scheme.outline

// Hairlines / dividers
val Line: Color @Composable get() = scheme.outlineVariant
val Line2: Color @Composable get() = scheme.outlineVariant
val Line3: Color @Composable get() = scheme.outlineVariant.copy(alpha = 0.6f)

// Status
val Offline: Color @Composable get() = scheme.outline
val Alert: Color @Composable get() = scheme.error
val Danger: Color @Composable get() = scheme.error
val Warn: Color @Composable get() = scheme.tertiary
val Ok: Color
    @Composable get() =
        if (scheme.background.luminance() < 0.5f) Color(0xFF6DD58C) else Color(0xFF146C2E)
