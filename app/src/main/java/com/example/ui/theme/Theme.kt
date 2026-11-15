package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.domain.model.AppearancePosture

val LocalNexaColors = staticCompositionLocalOf { ObsidianDarkPalette }

object NexaTheme {
    val colors: NexaColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalNexaColors.current
}

fun resolveNexaPalette(
    posture: AppearancePosture,
    systemIsDark: Boolean
): NexaColorPalette = when (posture) {
    AppearancePosture.SYSTEM -> if (systemIsDark) ObsidianDarkPalette else DaylightLightPalette
    AppearancePosture.OBSIDIAN_DARK -> ObsidianDarkPalette
    AppearancePosture.LIGHT -> DaylightLightPalette
    AppearancePosture.HIGH_CONTRAST_DARK -> HighContrastDarkPalette
}

fun NexaColorPalette.toMaterialColorScheme(): ColorScheme {
    return if (isDark) {
        darkColorScheme(
            primary = orange,
            onPrimary = onOrange,
            primaryContainer = orangeMuted,
            onPrimaryContainer = orange,
            secondary = textSecondary,
            onSecondary = background,
            secondaryContainer = elevatedSurface,
            onSecondaryContainer = textPrimary,
            tertiary = info,
            onTertiary = background,
            tertiaryContainer = infoContainer,
            onTertiaryContainer = info,
            background = background,
            onBackground = textPrimary,
            surface = secondaryBackground,
            onSurface = textPrimary,
            surfaceVariant = elevatedSurface,
            onSurfaceVariant = textSecondary,
            surfaceContainerLowest = background,
            surfaceContainerLow = secondaryBackground,
            surfaceContainer = elevatedSurface,
            surfaceContainerHigh = highestSurface,
            outline = border,
            outlineVariant = borderSubtle,
            error = error,
            onError = background,
            errorContainer = errorContainer,
            onErrorContainer = error
        )
    } else {
        lightColorScheme(
            primary = orange,
            onPrimary = onOrange,
            primaryContainer = orangeMuted,
            onPrimaryContainer = orange,
            secondary = textSecondary,
            onSecondary = secondaryBackground,
            secondaryContainer = elevatedSurface,
            onSecondaryContainer = textPrimary,
            tertiary = info,
            onTertiary = secondaryBackground,
            tertiaryContainer = infoContainer,
            onTertiaryContainer = info,
            background = background,
            onBackground = textPrimary,
            surface = secondaryBackground,
            onSurface = textPrimary,
            surfaceVariant = elevatedSurface,
            onSurfaceVariant = textSecondary,
            surfaceContainerLowest = secondaryBackground,
            surfaceContainerLow = background,
            surfaceContainer = elevatedSurface,
            surfaceContainerHigh = highestSurface,
            outline = border,
            outlineVariant = borderSubtle,
            error = error,
            onError = secondaryBackground,
            errorContainer = errorContainer,
            onErrorContainer = error
        )
    }
}

@Composable
fun NexaTheme(
    appearancePosture: AppearancePosture = AppearancePosture.OBSIDIAN_DARK,
    hapticFeedbackEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val palette = resolveNexaPalette(appearancePosture, systemDark)
    val colorScheme = palette.toMaterialColorScheme()
    val hapticController = rememberNexaHapticController(hapticFeedbackEnabled)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !palette.isDark
            insetsController.isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    CompositionLocalProvider(
        LocalNexaColors provides palette,
        LocalNexaHapticFeedback provides hapticController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexaTypography,
            shapes = NexaShapes,
            content = content
        )
    }
}
