package com.agani.syncup.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF5B6472),
    outline = Color(0xFFCED5E0),
    outlineVariant = Color(0xFFE4E9F1),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EB8FF),
    onPrimary = Color(0xFF0A1F52),
    primaryContainer = Color(0xFF25345C),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF9AA4B2),
    onSecondary = Color(0xFF0F172A),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF121826),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1C2434),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF33405A),
    outlineVariant = Color(0xFF232C3D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
)

// AMOLED: true-black background/surfaces to save power on OLED screens.
private val AmoledColors = darkColorScheme(
    primary = Color(0xFF9EB8FF),
    onPrimary = Color(0xFF0A1F52),
    primaryContainer = Color(0xFF1B2540),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF9AA4B2),
    onSecondary = Color(0xFF0F172A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF101216),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF2A2F3A),
    outlineVariant = Color(0xFF17191F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
)

@Composable
fun AgHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        amoled -> AmoledColors
        darkTheme -> DarkColors
        else -> LightColors
    }

    // Make the status/navigation bars match the app theme (dark bg → light icons).
    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightIcons = !(amoled || darkTheme)
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = lightIcons
            controller.isAppearanceLightNavigationBars = lightIcons
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
