package com.ghostkey.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ghostkey.app.AppLanguage
import com.ghostkey.app.AppThemeMode
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.strings.AppStrings
import com.ghostkey.app.ui.strings.ArStrings
import com.ghostkey.app.ui.strings.EnStrings

val LocalGhostPalette = staticCompositionLocalOf { LightGhostPalette }
val LocalGhostStrings = staticCompositionLocalOf { EnStrings }

object GhostTheme {
    val colors: GhostKeyPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalGhostPalette.current

    val strings: AppStrings
        @Composable
        @ReadOnlyComposable
        get() = LocalGhostStrings.current
}

fun RelayStatus.statusColor(palette: GhostKeyPalette): Color = when (this) {
    RelayStatus.CONNECTED -> palette.connected
    RelayStatus.BUILDING_CIRCUIT -> palette.connecting
    RelayStatus.ORBOT_NOT_RUNNING,
    RelayStatus.TIMED_OUT,
    RelayStatus.AUTH_FAILED -> palette.error
    RelayStatus.DISCONNECTED -> palette.textSecondary
}

fun RelayStatus.statusLabel(strings: AppStrings): String = when (this) {
    RelayStatus.CONNECTED -> strings.statusConnected
    RelayStatus.BUILDING_CIRCUIT -> strings.statusConnecting
    RelayStatus.ORBOT_NOT_RUNNING -> strings.statusTorNotRunning
    RelayStatus.TIMED_OUT -> strings.statusTimedOut
    RelayStatus.AUTH_FAILED -> strings.statusAuthFailed
    RelayStatus.DISCONNECTED -> strings.statusDisconnected
}

@Composable
fun GhostKeyTheme(
    themeMode: AppThemeMode = AppThemeMode.LIGHT,
    language: AppLanguage = AppLanguage.EN,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (useDark) DarkGhostPalette else LightGhostPalette
    val strings = if (language == AppLanguage.AR) ArStrings else EnStrings
    val layoutDir = if (language == AppLanguage.AR) LayoutDirection.Rtl else LayoutDirection.Ltr

    val materialScheme = if (useDark) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.navActive,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surfaceVariant,
            error = palette.error,
            onPrimary = palette.accentText,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.divider
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.navActive,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surfaceVariant,
            error = palette.error,
            onPrimary = palette.accentText,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.divider
        )
    }

    CompositionLocalProvider(
        LocalGhostPalette provides palette,
        LocalGhostStrings provides strings,
        LocalLayoutDirection provides layoutDir
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = GhostTypography,
            content = content
        )
    }
}
