package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val NexaDarkColorScheme = darkColorScheme(
    primary = NexaOrange,
    onPrimary = NexaOnOrange,
    primaryContainer = NexaOrangeMuted,
    onPrimaryContainer = NexaOrange,
    secondary = NexaTextSecondary,
    onSecondary = NexaBackground,
    secondaryContainer = NexaElevatedSurface,
    onSecondaryContainer = NexaTextPrimary,
    tertiary = NexaInfo,
    onTertiary = NexaBackground,
    tertiaryContainer = NexaInfoContainer,
    onTertiaryContainer = NexaInfo,
    background = NexaBackground,
    onBackground = NexaTextPrimary,
    surface = NexaSecondaryBackground,
    onSurface = NexaTextPrimary,
    surfaceVariant = NexaElevatedSurface,
    onSurfaceVariant = NexaTextSecondary,
    surfaceContainerLowest = NexaBackground,
    surfaceContainerLow = NexaSecondaryBackground,
    surfaceContainer = NexaElevatedSurface,
    surfaceContainerHigh = NexaHighestSurface,
    outline = NexaBorder,
    outlineVariant = NexaBorderSubtle,
    error = NexaError,
    onError = NexaBackground,
    errorContainer = NexaErrorContainer,
    onErrorContainer = NexaError
)

@Composable
fun NexaTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = NexaDarkColorScheme,
        typography = NexaTypography,
        shapes = NexaShapes,
        content = content
    )
}
