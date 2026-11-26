package com.ghostkey.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Quiet, eye-friendly GhostKey design tokens inspired by Telegram proportions
 * with muted surfaces, black/white high-contrast outgoing bubbles, and soft incoming gray.
 */
@Immutable
data class GhostKeyPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val bubbleOutgoing: Color,
    val bubbleOutgoingText: Color,
    val bubbleOutgoingSubtext: Color,
    val bubbleIncoming: Color,
    val bubbleIncomingText: Color,
    val bubbleIncomingSubtext: Color,
    val accent: Color,
    val accentText: Color,
    val connected: Color,
    val connecting: Color,
    val error: Color,
    val navIsland: Color,
    val navIslandBorder: Color,
    val navActive: Color,
    val navInactive: Color,
    val selectionHighlight: Color
)

val LightGhostPalette = GhostKeyPalette(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F7F8),
    surfaceVariant = Color(0xFFEFEFF1),
    divider = Color(0xFFE5E5EA),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFAEAEB2),
    bubbleOutgoing = Color(0xFF1C1C1E),
    bubbleOutgoingText = Color(0xFFFFFFFF),
    bubbleOutgoingSubtext = Color(0xFFAEAEB2),
    bubbleIncoming = Color(0xFFF2F2F7),
    bubbleIncomingText = Color(0xFF1C1C1E),
    bubbleIncomingSubtext = Color(0xFF8E8E93),
    accent = Color(0xFF2AABEE),
    accentText = Color(0xFFFFFFFF),
    connected = Color(0xFF34C759),
    connecting = Color(0xFFFF9500),
    error = Color(0xFFFF3B30),
    navIsland = Color(0xFFFFFFFF),
    navIslandBorder = Color(0xFFE5E5EA),
    navActive = Color(0xFF1C1C1E),
    navInactive = Color(0xFF8E8E93),
    selectionHighlight = Color(0x142AABEE)
)

val DarkGhostPalette = GhostKeyPalette(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    divider = Color(0xFF2C2C2E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFF636366),
    bubbleOutgoing = Color(0xFFFFFFFF),
    bubbleOutgoingText = Color(0xFF1C1C1E),
    bubbleOutgoingSubtext = Color(0xFF636366),
    bubbleIncoming = Color(0xFF2C2C2E),
    bubbleIncomingText = Color(0xFFFFFFFF),
    bubbleIncomingSubtext = Color(0xFF8E8E93),
    accent = Color(0xFF2AABEE),
    accentText = Color(0xFFFFFFFF),
    connected = Color(0xFF30D158),
    connecting = Color(0xFFFF9F0A),
    error = Color(0xFFFF453A),
    navIsland = Color(0xFF1C1C1E),
    navIslandBorder = Color(0xFF2C2C2E),
    navActive = Color(0xFFFFFFFF),
    navInactive = Color(0xFF8E8E93),
    selectionHighlight = Color(0x262AABEE)
)

/** Hash-seeded quiet Telegram-like avatar colors with pleasant muted saturation. */
val AvatarSwatchColors = listOf(
    Color(0xFF5B8DEF),
    Color(0xFF52A87E),
    Color(0xFFE08B4B),
    Color(0xFF8E74D6),
    Color(0xFFD66B84),
    Color(0xFF4BA3B5),
    Color(0xFF7D8B99),
    Color(0xFFB58D4C)
)

fun avatarColorFor(seed: String): Color {
    if (seed.isEmpty()) return AvatarSwatchColors[0]
    val idx = (seed.hashCode() and Int.MAX_VALUE) % AvatarSwatchColors.size
    return AvatarSwatchColors[idx]
}
