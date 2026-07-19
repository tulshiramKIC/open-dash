package com.example.opendash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Derived theme colors that have no direct Material scheme role. Plain roles are
 * referenced as MaterialTheme.colorScheme.* at call sites; only compound values
 * (lerps, alpha variants, luminance-dependent picks) live here.
 */

private val scheme
    @Composable get() = MaterialTheme.colorScheme

// Accent variants (historically gold; now derived from the theme primary)
val GoldDeep: Color @Composable get() = lerp(scheme.primary, scheme.primaryContainer, 0.55f)
val GoldTint2: Color @Composable get() = scheme.primary.copy(alpha = 0.14f)
val GoldGlow: Color @Composable get() = scheme.primary.copy(alpha = 0.30f)

// Hairlines / dividers
val Line3: Color @Composable get() = scheme.outlineVariant.copy(alpha = 0.6f)

// Status green tuned per scheme brightness (no Material role for "success")
val Ok: Color
    @Composable get() =
        if (scheme.background.luminance() < 0.5f) Color(0xFF6DD58C) else Color(0xFF146C2E)
