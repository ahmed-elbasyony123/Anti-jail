package com.ghostkey.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StealthBackground = Color(0xFF090C0E)
val StealthSurface = Color(0xFF13181D)
val StealthSurfaceVariant = Color(0xFF1A2229)
val NeonEmerald = Color(0xFF00FF9D)
val NeonCyan = Color(0xFF00E5FF)
val AlertCrimson = Color(0xFFFF3366)
val WarningAmber = Color(0xFFFFB800)
val StealthTextPrimary = Color(0xFFE6EDF3)
val StealthTextSecondary = Color(0xFF8B949E)
val StealthBorder = Color(0xFF30363D)

private val DarkColorScheme = darkColorScheme(
    primary = NeonEmerald,
    secondary = NeonCyan,
    background = StealthBackground,
    surface = StealthSurface,
    surfaceVariant = StealthSurfaceVariant,
    error = AlertCrimson,
    onPrimary = Color(0xFF002914),
    onSecondary = Color(0xFF002B33),
    onBackground = StealthTextPrimary,
    onSurface = StealthTextPrimary,
    onError = Color.White
)

@Composable
fun GhostKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
