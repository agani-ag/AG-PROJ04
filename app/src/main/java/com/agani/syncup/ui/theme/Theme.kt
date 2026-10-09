package com.agani.syncup.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// v2 design ("Calm Material", docs/mockup/v2/mockup.css): hierarchy comes from five tonal surface
// tiers rather than dividers. Material 3 components pick these tiers up by default
// (bottom sheets = containerLow, bars = container, dialogs/menus = containerHigh, …).

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E6EF),
    onSecondaryContainer = Color(0xFF1B2433),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE3E8F0),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEBEFF5),
    surfaceContainerHigh = Color(0xFFE3E8F0),
    surfaceContainerHighest = Color(0xFFDAE0EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDAE0EA),
    outline = Color(0xFFC3CBD7),
    outlineVariant = Color(0xFFE1E6EE),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color(0xFF0F172A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EB8FF),
    onPrimary = Color(0xFF0A1F52),
    primaryContainer = Color(0xFF25345C),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF9AA4B2),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF2A3346),
    onSecondaryContainer = Color(0xFFDCE3F0),
    background = Color(0xFF0F141C),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF0F141C),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF232B3B),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = Color(0xFF161C26),
    surfaceContainer = Color(0xFF1B2230),
    surfaceContainerHigh = Color(0xFF232B3B),
    surfaceContainerHighest = Color(0xFF2C3546),
    surfaceBright = Color(0xFF2C3546),
    surfaceDim = Color(0xFF0F141C),
    outline = Color(0xFF4A556A),
    outlineVariant = Color(0xFF2C3546),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF4A1717),
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)

// AMOLED: true-black base; every tier above it is a raised grey so structure survives on #000.
private val AmoledColors = darkColorScheme(
    primary = Color(0xFF9EB8FF),
    onPrimary = Color(0xFF0A1F52),
    primaryContainer = Color(0xFF1E2A48),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF9AA4B2),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1C2130),
    onSecondaryContainer = Color(0xFFDCE3F0),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1C212B),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0C0F15),
    surfaceContainer = Color(0xFF13171F),
    surfaceContainerHigh = Color(0xFF1C212B),
    surfaceContainerHighest = Color(0xFF262C38),
    surfaceBright = Color(0xFF262C38),
    surfaceDim = Color(0xFF000000),
    outline = Color(0xFF3A4250),
    outlineVariant = Color(0xFF262C38),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF3A1212),
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)

/** Radius scale: 8 chips · 12 tiles · 20 cards · 28 sheets & dialogs (pills use full rounding). */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Dialog surface from the design: white paper in light, a raised tier in dark/black. */
val ColorScheme.dialogSurface: Color
    get() = if (background.luminance() > 0.5f) surfaceContainerLowest else surfaceContainerHigh

val ColorScheme.isLight: Boolean get() = background.luminance() > 0.5f

/** Status colours used by a few rows (secure connection, filed/due). */
val ColorScheme.success: Color get() = if (isLight) Color(0xFF15803D) else Color(0xFF4ADE80)

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

    // Bar icons follow the app theme (dark bg → light icons). The bars themselves are transparent
    // (edge-to-edge, see MainActivity): each screen draws its own colour behind them.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightIcons = !(amoled || darkTheme)
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = lightIcons
            controller.isAppearanceLightNavigationBars = lightIcons
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = AppShapes,
        content = content,
    )
}

// ------------------------------------------------------------------ browser sections
/** Work = the theme's teal accent. Surfaces are shared with Normal; only the accent changes. */
fun workScheme(base: ColorScheme): ColorScheme = if (base.isLight) {
    base.copy(
        primary = Color(0xFF0F766E), onPrimary = Color.White,
        primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF134E4A),
        secondaryContainer = Color(0xFFD5F5EE), onSecondaryContainer = Color(0xFF134E4A),
    )
} else {
    base.copy(
        primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2E),
        primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFCCFBF1),
        secondaryContainer = Color(0xFF1E3F3B), onSecondaryContainer = Color(0xFFCCFBF1),
    )
}

/** [on]'s colours with Normal's blue accent in the same lightness (light or dark/Incognito). */
fun normalAccentOn(on: ColorScheme): ColorScheme {
    val src = if (on.isLight) LightColors else DarkColors
    return on.copy(
        primary = src.primary, onPrimary = src.onPrimary,
        primaryContainer = src.primaryContainer, onPrimaryContainer = src.onPrimaryContainer,
    )
}

/** [on]'s colours with Incognito's violet accent; its tile stays dark violet on a light sheet too. */
fun incognitoAccentOn(on: ColorScheme): ColorScheme = on.copy(
    primary = if (on.isLight) Color(0xFF6D28D9) else IncognitoScheme.primary,
    onPrimary = if (on.isLight) Color.White else IncognitoScheme.onPrimary,
    primaryContainer = IncognitoScheme.primaryContainer, onPrimaryContainer = IncognitoScheme.onPrimaryContainer,
)

/** Incognito = a fixed dark palette with a violet accent; ignores the app theme. */
val IncognitoScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC4B5FD), onPrimary = Color(0xFF2E1065),
    primaryContainer = Color(0xFF3B2A63), onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFF9AA0A6), onSecondary = Color(0xFF202124),
    secondaryContainer = Color(0xFF3C4043), onSecondaryContainer = Color(0xFFE8EAED),
    background = Color(0xFF1B1C1F), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1B1C1F), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF2E2F34), onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceContainerLowest = Color(0xFF141517),
    surfaceContainerLow = Color(0xFF202124),
    surfaceContainer = Color(0xFF26272B),
    surfaceContainerHigh = Color(0xFF2E2F34),
    surfaceContainerHighest = Color(0xFF37383E),
    surfaceBright = Color(0xFF37383E), surfaceDim = Color(0xFF1B1C1F),
    outline = Color(0xFF5F6368), outlineVariant = Color(0xFF37383E),
    error = Color(0xFFFF8A80), onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF4A1717), onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)
