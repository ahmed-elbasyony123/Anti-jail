package com.ghostkey.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ghostkey.app.net.RelayStatus

/**
 * Anthropic Palette Design Tokens
 *
 * Grounded in warm obsidian (Anthropic Near Black) and parchment/cream,
 * paired with terracotta accent and earthy sage.
 */

// Dark Canvas & Warm Charcoal
val AnthropicDarkBg = Color(0xFF141413)
val AnthropicSurface = Color(0xFF1E1C1A)
val AnthropicSurfaceVariant = Color(0xFF262421)
val AnthropicBorder = Color(0xFF363632)
val AnthropicBorderSubtle = Color(0xFF2B2B27)

// Accents & Functional Status
val AnthropicTerracotta = Color(0xFFD97757)
val AnthropicTerracottaDark = Color(0xFFC96442)
val AnthropicSage = Color(0xFF788C5D)
val AnthropicSlate = Color(0xFF6A9BCC)
val AnthropicCrimson = Color(0xFFC84B4B)

// Typography & Neutrals
val AnthropicCream = Color(0xFFFAF9F5)
val AnthropicParchment = Color(0xFFF5F4ED)
val AnthropicTextMuted = Color(0xFF87867F)
val AnthropicTextMid = Color(0xFFB0AEA5)
val AnthropicDarkNeutral = Color(0xFF3D3D3A)

// Chat Bubbles
val AnthropicBubbleIncoming = Color(0xFF242220)
val AnthropicBubbleOutgoing = Color(0xFF382A22)

internal fun RelayStatus.statusDisplay(): Pair<String, Color> = when (this) {
    RelayStatus.CONNECTED -> "Connected over Tor 🧅" to AnthropicSage
    RelayStatus.BUILDING_CIRCUIT -> "Establishing Tor circuit..." to AnthropicTerracotta
    RelayStatus.ORBOT_NOT_RUNNING -> "Orbot / Tor not running" to AnthropicCrimson
    RelayStatus.TIMED_OUT -> "Connection timed out" to AnthropicCrimson
    RelayStatus.AUTH_FAILED -> "Mailbox auth failed" to AnthropicCrimson
    RelayStatus.DISCONNECTED -> "Disconnected" to AnthropicTextMuted
}

private val DarkColorScheme = darkColorScheme(
    primary = AnthropicTerracotta,
    secondary = AnthropicSlate,
    tertiary = AnthropicSage,
    background = AnthropicDarkBg,
    surface = AnthropicSurface,
    surfaceVariant = AnthropicSurfaceVariant,
    error = AnthropicCrimson,
    onPrimary = Color(0xFF1E1C1A),
    onSecondary = Color(0xFF141413),
    onBackground = AnthropicCream,
    onSurface = AnthropicCream,
    onError = AnthropicCream
)

@Composable
fun GhostKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
