package com.techazsure.deskless.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BrandColors = darkColorScheme(
    primary = SafetyYellow,
    onPrimary = Ink,
    primaryContainer = AmberWash,
    onPrimaryContainer = SafetyYellow,
    secondary = Fog,
    onSecondary = Ink,
    background = Ink,
    onBackground = Chalk,
    surface = Ink,
    onSurface = Chalk,
    surfaceVariant = Steel,
    onSurfaceVariant = Fog,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Coal,
    surfaceContainer = Coal,
    surfaceContainerHigh = Steel,
    surfaceContainerHighest = SteelHigh,
    outline = Line,
    outlineVariant = Line,
    error = SignalRed,
    onError = Ink,
)

/** One look in every light: the brand is dark, and a dark screen is also easier on a site at night. */
@Composable
fun DesklessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BrandColors, typography = Typography, content = content)
}
