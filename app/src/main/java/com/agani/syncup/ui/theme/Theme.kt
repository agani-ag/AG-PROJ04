package com.agani.syncup.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// v7 design (docs/mockup/syncup-v7-screens.html): look "A · Clean" — a light grey page, white cards
// with one gentle shadow, a blue accent, each tool its own soft colour — with pill buttons, 24 dp
// corners and roomy spacing. Two themes only: Light, and Dark = pure black (cards a hair lighter
// with thin lines, so they still show; on OLED phones black pixels are off).

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1B2433),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFEEF2F7),
    surfaceContainerHigh = Color(0xFFE6EAF0),
    surfaceContainerHighest = Color(0xFFDCE2EB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCE2EB),
    outline = Color(0xFFC3CBD7),
    outlineVariant = Color(0xFFE6EAF0),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color(0xFF0F172A),
)

// Dark is pure black: a #000 page; every tier above it is a slightly raised grey.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B1A3A),
    primaryContainer = Color(0xFF1A2540),
    onPrimaryContainer = Color(0xFFC7DAFF),
    secondary = Color(0xFF98A2B3),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1C2130),
    onSecondaryContainer = Color(0xFFDCE3F0),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF1E232C),
    onSurfaceVariant = Color(0xFF98A2B3),
    surfaceContainerLowest = Color(0xFF0F1115),
    surfaceContainerLow = Color(0xFF121419),
    surfaceContainer = Color(0xFF161A21),
    surfaceContainerHigh = Color(0xFF1E232C),
    surfaceContainerHighest = Color(0xFF262C38),
    surfaceBright = Color(0xFF262C38),
    surfaceDim = Color(0xFF000000),
    outline = Color(0xFF3A4250),
    outlineVariant = Color(0xFF1E232C),
    error = Color(0xFFF87171),
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF3B1515),
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)

/** Radius scale: 8 chips · 16 tiles and fields · 24 cards · 28 sheets & dialogs (buttons are pills). */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** A card's corners (24 dp) — use with [appCard]. */
val CardShape = RoundedCornerShape(24.dp)

/** Dialog surface from the design: white paper in light, a raised tier in dark. */
val ColorScheme.dialogSurface: Color
    get() = if (background.luminance() > 0.5f) surfaceContainerLowest else surfaceContainerHigh

val ColorScheme.isLight: Boolean get() = background.luminance() > 0.5f

/** Status colours used by a few rows (secure connection, filed/due). */
val ColorScheme.success: Color get() = if (isLight) Color(0xFF15803D) else Color(0xFF4ADE80)

/**
 * The v7 card: white (Dark: a hair above black) with one gentle shadow in Light and a thin line in
 * Dark — shadows don't show on black. Clips the content to [shape]. Put padding / clickable after it.
 */
@Composable
fun Modifier.appCard(shape: Shape = CardShape, color: Color = Color.Unspecified): Modifier {
    val cs = MaterialTheme.colorScheme
    val bg = if (color == Color.Unspecified) cs.surfaceContainerLowest else color
    return if (cs.isLight) {
        this.shadow(2.dp, shape, clip = true, ambientColor = Color(0x330F172A), spotColor = Color(0x260F172A)).background(bg, shape)
    } else {
        this.clip(shape).background(bg, shape).border(1.dp, cs.outlineVariant, shape)
    }
}

@Composable
fun AgHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (amoled || darkTheme) DarkColors else LightColors

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
/** Work (the SyncUp section) = the theme's green accent. Surfaces are shared with Normal; only the accent changes. */
fun workScheme(base: ColorScheme): ColorScheme = if (base.isLight) {
    base.copy(
        primary = Color(0xFF15803D), onPrimary = Color.White,
        primaryContainer = Color(0xFFDCFCE7), onPrimaryContainer = Color(0xFF14532D),
        secondaryContainer = Color(0xFFD9F5E1), onSecondaryContainer = Color(0xFF14532D),
    )
} else {
    base.copy(
        primary = Color(0xFF86EFAC), onPrimary = Color(0xFF052E16),
        primaryContainer = Color(0xFF14532D), onPrimaryContainer = Color(0xFFDCFCE7),
        secondaryContainer = Color(0xFF1C3D27), onSecondaryContainer = Color(0xFFDCFCE7),
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
