package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant
)

// ========== 森林 ==========
private val ForestDarkColorScheme = darkColorScheme(
    primary = ForestDarkPrimary,
    onPrimary = ForestDarkOnPrimary,
    background = ForestDarkBackground,
    onBackground = ForestDarkOnBackground,
    surface = ForestDarkSurface,
    onSurface = ForestDarkOnSurface,
    surfaceVariant = ForestDarkSurfaceVariant,
    onSurfaceVariant = ForestDarkOnSurfaceVariant
)

private val ForestLightColorScheme = lightColorScheme(
    primary = ForestLightPrimary,
    onPrimary = ForestLightOnPrimary,
    background = ForestLightBackground,
    onBackground = ForestLightOnBackground,
    surface = ForestLightSurface,
    onSurface = ForestLightOnSurface,
    surfaceVariant = ForestLightSurfaceVariant,
    onSurfaceVariant = ForestLightOnSurfaceVariant
)

// ========== 暖阳 ==========
private val SunsetDarkColorScheme = darkColorScheme(
    primary = SunsetDarkPrimary,
    onPrimary = SunsetDarkOnPrimary,
    background = SunsetDarkBackground,
    onBackground = SunsetDarkOnBackground,
    surface = SunsetDarkSurface,
    onSurface = SunsetDarkOnSurface,
    surfaceVariant = SunsetDarkSurfaceVariant,
    onSurfaceVariant = SunsetDarkOnSurfaceVariant
)

private val SunsetLightColorScheme = lightColorScheme(
    primary = SunsetLightPrimary,
    onPrimary = SunsetLightOnPrimary,
    background = SunsetLightBackground,
    onBackground = SunsetLightOnBackground,
    surface = SunsetLightSurface,
    onSurface = SunsetLightOnSurface,
    surfaceVariant = SunsetLightSurfaceVariant,
    onSurfaceVariant = SunsetLightOnSurfaceVariant
)

@Composable
fun Theme(
    appTheme: Int = 0, // 0: 暗夜, 1: 森林, 2: 暖阳
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        1 -> if (darkTheme) ForestDarkColorScheme else ForestLightColorScheme
        2 -> if (darkTheme) SunsetDarkColorScheme else SunsetLightColorScheme
        else -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
