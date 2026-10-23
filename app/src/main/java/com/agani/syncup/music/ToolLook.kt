package com.agani.syncup.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.ui.theme.isLight

// v7 look for the media and nearby tools (docs/mockup/v7/sec_media.py): Music, Radio, Party, Videos,
// Watch Party, Channels, SyncUp Share, Walkie Talkie and Group. Light grey pages, white cards
// (appCard), rows inside one card, and each tool in its own soft colour.

/**
 * A tool's colour from the design (kit.py TINTS): the soft [tile] behind its icon, and the [icon]
 * colour — also the solid fill for a button in the tool's colour, with [onSolid] on it.
 */
enum class ToolTint(private val lightTile: Long, private val lightIcon: Long, private val darkTile: Long, private val darkIcon: Long) {
    BLUE(0xFFDBEAFE, 0xFF1D4ED8, 0xFF16223A, 0xFF93C5FD), // SyncUp Share
    INDIGO(0xFFE0E7FF, 0xFF4338CA, 0xFF1C1F3D, 0xFFA5B4FC), // Group
    VIOLET(0xFFEDE9FE, 0xFF6D28D9, 0xFF241B3D, 0xFFC4B5FD), // Music
    PINK(0xFFFCE7F3, 0xFFBE185D, 0xFF331526, 0xFFF9A8D4), // Party
    ROSE(0xFFFFE4E6, 0xFFBE123C, 0xFF34141B, 0xFFFDA4AF), // Videos, Watch Party
    RED(0xFFFEE2E2, 0xFFB91C1C, 0xFF341414, 0xFFFCA5A5),
    ORANGE(0xFFFFEDD5, 0xFFC2410C, 0xFF33200F, 0xFFFDBA74), // Radio
    AMBER(0xFFFEF3C7, 0xFFB45309, 0xFF30240C, 0xFFFCD34D), // warnings
    LIME(0xFFECFCCB, 0xFF4D7C0F, 0xFF1C2A0D, 0xFFBEF264),
    GREEN(0xFFDCFCE7, 0xFF15803D, 0xFF0F2A18, 0xFF86EFAC), // Channels, Receive, Walkie Talkie
    CYAN(0xFFCFFAFE, 0xFF0E7490, 0xFF0C2930, 0xFF67E8F9),
    SKY(0xFFE0F2FE, 0xFF0369A1, 0xFF0E2333, 0xFF7DD3FC),
    SLATE(0xFFE2E8F0, 0xFF334155, 0xFF1B1F26, 0xFFCBD5E1),
    ;

    /** The soft tile colour. */
    val tile: Color
        @Composable @ReadOnlyComposable get() = Color(if (MaterialTheme.colorScheme.isLight) lightTile else darkTile)

    /** The icon colour; also the solid fill of a button in this tool's colour. */
    val icon: Color
        @Composable @ReadOnlyComposable get() = Color(if (MaterialTheme.colorScheme.isLight) lightIcon else darkIcon)

    /** Text and icons on [icon] used as a fill: white in Light, the card colour in Dark. */
    val onSolid: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerLowest
}

/** A filled pill button in a tool's colour (Join, Switch, Add to queue). */
@Composable
fun tintButton(tint: ToolTint): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = tint.icon, contentColor = tint.onSolid)

/** The tool's icon on its soft tile: 42 dp, corners a third of the size. */
@Composable
fun ToolTile(icon: ImageVector, tint: ToolTint, size: Dp = 42.dp, iconSize: Dp = size * .52f) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(tint.tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint.icon, modifier = Modifier.size(iconSize))
    }
}

/** Top bar of an inner page: back arrow, a 20 sp title (and an optional line under it), actions on the right. */
@Composable
fun ToolTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp, vertical = 2.dp)) {
        BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** A heading over cards ("Up next", "Groups nearby · 2"): an optional count, and an action on the right. */
@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, count: String? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 30.dp).padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 10.dp),
    ) {
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        if (count != null) Text(" · $count", fontSize = 14.5.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        action?.invoke(this)
    }
}

/** The soft grey note (how it works, tips): an icon and small grey text. */
@Composable
fun NoteBox(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
    }
}

/** A hairline between rows inside one card. */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * One row's slice of a card, for long lazy lists (songs, downloads, files): rows stay lazy, yet read
 * as one card — white, 24 dp corners on the first and last row, a hairline between rows and around
 * the edge. No shadow per row (a shadow on each slice would show as lines between them).
 */
@Composable
fun Modifier.cardSlice(first: Boolean, last: Boolean): Modifier {
    val cs = MaterialTheme.colorScheme
    val bg = cs.surfaceContainerLowest
    val edge = if (cs.isLight) Color(0x1A0F172A) else cs.outlineVariant
    val line = cs.outlineVariant
    val r = 24.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    return this
        .drawWithCache {
            val w = 1.dp.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            val fill = Path().apply { addOutline(outline) }
            // The edge, drawn just inside: open at the top / bottom where the card carries on.
            val inner = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val rim = Path().apply {
                addOutline(
                    RoundedCornerShape(
                        topStart = if (first) r - 0.5.dp else 0.dp, topEnd = if (first) r - 0.5.dp else 0.dp,
                        bottomStart = if (last) r - 0.5.dp else 0.dp, bottomEnd = if (last) r - 0.5.dp else 0.dp,
                    ).createOutline(inner, layoutDirection, this@drawWithCache),
                )
                translate(Offset(w / 2, w / 2))
            }
            onDrawBehind {
                drawPath(fill, bg)
                clipRect(top = if (first) 0f else w, bottom = if (last) size.height else size.height - w) {
                    drawPath(rim, edge, style = Stroke(w))
                }
                if (!first) drawLine(line, Offset(0f, w / 2), Offset(size.width, w / 2), w)
            }
        }
        .clip(shape)
}
