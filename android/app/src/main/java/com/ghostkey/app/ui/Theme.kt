package com.ghostkey.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Anthropic Official Palette Tokens (Brand Design Tokens)
 *
 * Grounded in warm paper (parchment/cream) and warm obsidian (Anthropic Near Black),
 * paired with Anthropic's signature terracotta accent and earthy sage.
 */

// Dark Canvas & Warm Charcoal
val AnthropicDarkBg = Color(0xFF141413)         // Anthropic Near Black (#141413)
val AnthropicSurface = Color(0xFF1E1C1A)        // Card / TopBar Surface (#1E1C1A)
val AnthropicSurfaceVariant = Color(0xFF262421) // Group / Bubble / List item container (#262421)
val AnthropicBorder = Color(0xFF363632)         // Soft warm border (#363632)
val AnthropicBorderSubtle = Color(0xFF2B2B27)   // Very subtle separator (#2B2B27)

// Anthropic Terracotta & Functional Accents
val AnthropicTerracotta = Color(0xFFD97757)     // Primary Action & Accent (#D97757)
val AnthropicTerracottaDark = Color(0xFFC96442) // Pressed / Dark Terracotta (#C96442)
val AnthropicSage = Color(0xFF788C5D)           // Earthy Green: Tor / Verified status (#788C5D)
val AnthropicSlate = Color(0xFF6A9BCC)          // Secondary Slate Blue (#6A9BCC)
val AnthropicCrimson = Color(0xFFC84B4B)        // Muted Crimson: Panic wipe / Errors (#C84B4B)

// Anthropic Cream Typography & Neutrals
val AnthropicCream = Color(0xFFFAF9F5)          // Paper Cream (Primary Text, #FAF9F5)
val AnthropicParchment = Color(0xFFF5F4ED)      // Soft Parchment (#F5F4ED)
val AnthropicTextMuted = Color(0xFF87867F)      // Secondary Text / Timestamps (#87867F)
val AnthropicTextMid = Color(0xFFB0AEA5)        // Mid Neutral (#B0AEA5)
val AnthropicDarkNeutral = Color(0xFF3D3D3A)    // Inactive elements (#3D3D3A)

// Telegram-Style Chat Bubbles (Anthropic Warm Tone)
val AnthropicBubbleIncoming = Color(0xFF242220) // Deep warm charcoal
val AnthropicBubbleOutgoing = Color(0xFF382A22) // Terracotta-tinted warm espresso

// Backward-compatibility aliases mapped to Anthropic warm tokens
val StealthBackground = AnthropicDarkBg
val StealthSurface = AnthropicSurface
val StealthSurfaceVariant = AnthropicSurfaceVariant
val NeonEmerald = AnthropicTerracotta
val NeonCyan = AnthropicSlate
val AlertCrimson = AnthropicCrimson
val WarningAmber = AnthropicTerracotta
val StealthTextPrimary = AnthropicCream
val StealthTextSecondary = AnthropicTextMuted
val StealthBorder = AnthropicBorder

/**
 * Semantic names used by the security-status and contact-introduction dialogs,
 * mapped onto the Anthropic warm palette defined above.
 */
object GhostKeyColors {
    val SurfacePrimary = AnthropicSurface
    val SurfaceSecondary = AnthropicSurfaceVariant
    val TextPrimary = AnthropicCream
    val TextSecondary = AnthropicTextMuted
    val AccentGold = AnthropicTerracotta
    val StatusGreen = AnthropicSage
    val StatusRed = AnthropicCrimson
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
