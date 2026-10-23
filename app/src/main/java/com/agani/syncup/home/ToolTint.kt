package com.agani.syncup.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.agani.syncup.ui.theme.isLight

/**
 * Each tool's own soft colour (v7 design, docs/mockup/v7/kit.py TINTS): a pale tile and a deeper icon
 * colour, with darker tiles on the black Dark theme. The same tool keeps the same colour everywhere.
 */
enum class ToolTint(private val lightTile: Long, private val lightIcon: Long, private val darkTile: Long, private val darkIcon: Long) {
    BLUE(0xFFDBEAFE, 0xFF1D4ED8, 0xFF16223A, 0xFF93C5FD),
    INDIGO(0xFFE0E7FF, 0xFF4338CA, 0xFF1C1F3D, 0xFFA5B4FC),
    VIOLET(0xFFEDE9FE, 0xFF6D28D9, 0xFF241B3D, 0xFFC4B5FD),
    PINK(0xFFFCE7F3, 0xFFBE185D, 0xFF331526, 0xFFF9A8D4),
    ROSE(0xFFFFE4E6, 0xFFBE123C, 0xFF34141B, 0xFFFDA4AF),
    ORANGE(0xFFFFEDD5, 0xFFC2410C, 0xFF33200F, 0xFFFDBA74),
    AMBER(0xFFFEF3C7, 0xFFB45309, 0xFF30240C, 0xFFFCD34D),
    LIME(0xFFECFCCB, 0xFF4D7C0F, 0xFF1C2A0D, 0xFFBEF264),
    GREEN(0xFFDCFCE7, 0xFF15803D, 0xFF0F2A18, 0xFF86EFAC),
    CYAN(0xFFCFFAFE, 0xFF0E7490, 0xFF0C2930, 0xFF67E8F9),
    SKY(0xFFE0F2FE, 0xFF0369A1, 0xFF0E2333, 0xFF7DD3FC),
    ;

    /** The tile behind the icon. */
    val tile: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (MaterialTheme.colorScheme.isLight) lightTile else darkTile)

    /** The icon (and the tool's dots and small text). */
    val icon: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (MaterialTheme.colorScheme.isLight) lightIcon else darkIcon)
}
