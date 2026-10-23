package com.agani.syncup.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.browser.ui.PillButton
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.isLight

// The v7 look of the browser's own pages (docs/mockup/v7/sec_browser.py, tokens in kit.py): white
// paper on a light grey page with one gentle shadow, a hair above black in Dark, and Incognito's own
// dark grey. Shared by the new tab pages, the bars, the sheets and History / Site settings.

/** Dark = pure black (the app's Dark theme); Incognito is its own dark grey and is not. */
private val ColorScheme.pureBlack: Boolean get() = background == Color.Black

/** Cards, bars and sheets: white in Light, a hair above black in Dark, Incognito's raised grey. */
internal val ColorScheme.paper: Color
    get() = if (isLight || pureBlack) surfaceContainerLowest else surfaceContainer

/**
 * The browser's docked bars — address, find, bottom bar, the tab switcher's bar — and the system
 * bars next to them: the same paper as a card, under / over one hairline.
 */
internal val ColorScheme.bar: Color get() = paper

/** A text pill inside a bar, a note, a round quick action: the soft grey one step above the bar. */
internal val ColorScheme.field: Color
    get() = if (isLight || pureBlack) surfaceContainer else surfaceContainerHigh

/** Bottom sheets: white paper in Light (like a card), the usual raised tier in Dark and Incognito. */
internal val ColorScheme.sheet: Color
    get() = when {
        isLight -> surfaceContainerLowest
        pureBlack -> surfaceContainerLow
        else -> surfaceContainer
    }

/** A card in the browser's colours (Incognito keeps its own grey paper). */
@Composable
internal fun Modifier.browserCard(shape: RoundedCornerShape = BrowserCardShape): Modifier =
    appCard(shape, MaterialTheme.colorScheme.paper)

internal val BrowserCardShape = RoundedCornerShape(24.dp)

/**
 * One row of a card that a lazy list draws row by row (History, Bookmarks): the card's paper, its
 * corners on the [first] and [last] row, its edge down the sides and a hairline between rows. No
 * shadow — a long list would draw one per row — so Light gets a faint edge in its place.
 */
@Composable
internal fun Modifier.cardRow(first: Boolean, last: Boolean): Modifier {
    val cs = MaterialTheme.colorScheme
    val edge = if (cs.isLight) Color(0x140F172A) else cs.outlineVariant
    val line = cs.outlineVariant
    val r = 24.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    return this
        .clip(shape)
        .background(cs.paper)
        .drawWithContent {
            drawContent()
            val px = 1.dp.toPx()
            val rad = r.toPx()
            val w = size.width
            val h = size.height
            val half = px / 2
            if (!first) drawLine(line, Offset(0f, half), Offset(w, half), px)
            val top = if (first) rad else 0f
            val bottom = if (last) h - rad else h
            val outline = Path().apply {
                moveTo(half, top)
                lineTo(half, bottom)
                moveTo(w - half, top)
                lineTo(w - half, bottom)
                if (first) {
                    moveTo(half, rad)
                    arcTo(Rect(half, half, rad * 2, rad * 2), 180f, 90f, false)
                    lineTo(w - rad, half)
                    arcTo(Rect(w - rad * 2, half, w - half, rad * 2), 270f, 90f, false)
                }
                if (last) {
                    moveTo(half, h - rad)
                    arcTo(Rect(half, h - rad * 2, rad * 2, h - half), 180f, -90f, false)
                    lineTo(w - rad, h - half)
                    arcTo(Rect(w - rad * 2, h - rad * 2, w - half, h - half), 90f, -90f, false)
                }
            }
            drawPath(outline, edge, style = Stroke(px))
        }
}

/** The thin line between rows inside one card. */
@Composable
internal fun CardDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Soft "tool colour" pairs (tile, glyph) from the design kit, for letter marks and icon tiles. */
internal enum class Tint(private val lightTile: Long, private val lightInk: Long, private val darkTile: Long, private val darkInk: Long) {
    BLUE(0xFFDBEAFE, 0xFF1D4ED8, 0xFF16223A, 0xFF93C5FD),
    INDIGO(0xFFE0E7FF, 0xFF4338CA, 0xFF1C1F3D, 0xFFA5B4FC),
    VIOLET(0xFFEDE9FE, 0xFF6D28D9, 0xFF241B3D, 0xFFC4B5FD),
    PINK(0xFFFCE7F3, 0xFFBE185D, 0xFF331526, 0xFFF9A8D4),
    RED(0xFFFEE2E2, 0xFFB91C1C, 0xFF341414, 0xFFFCA5A5),
    ORANGE(0xFFFFEDD5, 0xFFC2410C, 0xFF33200F, 0xFFFDBA74),
    AMBER(0xFFFEF3C7, 0xFFB45309, 0xFF30240C, 0xFFFCD34D),
    LIME(0xFFECFCCB, 0xFF4D7C0F, 0xFF1C2A0D, 0xFFBEF264),
    GREEN(0xFFDCFCE7, 0xFF15803D, 0xFF0F2A18, 0xFF86EFAC),
    CYAN(0xFFCFFAFE, 0xFF0E7490, 0xFF0C2930, 0xFF67E8F9),
    SKY(0xFFE0F2FE, 0xFF0369A1, 0xFF0E2333, 0xFF7DD3FC),
    SLATE(0xFFE2E8F0, 0xFF334155, 0xFF1B1F26, 0xFFCBD5E1);

    fun tile(light: Boolean) = Color(if (light) lightTile else darkTile)
    fun ink(light: Boolean) = Color(if (light) lightInk else darkInk)

    companion object {
        /** A stable colour for a site or name (the same one every time it shows). */
        fun of(key: String): Tint = entries[(key.hashCode() and 0x7fffffff) % entries.size]
    }
}

/** An icon on a soft tool-coloured tile (34 dp in lists, 42 dp for bigger rows). */
@Composable
internal fun TintTile(icon: ImageVector, tint: Tint, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val light = MaterialTheme.colorScheme.isLight
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .32f)).background(tint.tile(light)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint.ink(light), modifier = Modifier.size(if (size >= 40.dp) 22.dp else 18.dp))
    }
}

/** A site's first letter on its soft colour (History, Bookmarks, Site settings). */
@Composable
internal fun LetterMark(key: String, letter: String, size: androidx.compose.ui.unit.Dp = 34.dp, round: Boolean = false) {
    val light = MaterialTheme.colorScheme.isLight
    val tint = Tint.of(key)
    Box(
        Modifier.size(size).clip(if (round) RoundedCornerShape(50) else RoundedCornerShape(size * .32f)).background(tint.tile(light)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.take(1).uppercase().ifEmpty { "?" }, fontSize = (size.value * .41f).sp, fontWeight = FontWeight.Bold, color = tint.ink(light))
    }
}

/** Title (and a line under it) at the top of a browser sheet. */
@Composable
internal fun BrowserSheetTitle(title: String, subtitle: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant)
    }
}

/** A small grey note with an info mark ("Only Normal browsing is saved here…"). */
@Composable
internal fun InfoNote(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Info) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.field).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(15.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
    }
}

/** Back · title · actions — the top of History, Bookmarks and Site settings. */
@Composable
internal fun PageTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp)) {
        BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
        Text(
            title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        actions()
    }
}

/** A day / group heading above a card ("Today", "Sites"). */
@Composable
internal fun CardHeading(text: String, modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 20.dp) {
    Text(
        text, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = top, bottom = 10.dp),
    )
}

/**
 * The search box as a white card pill (50 dp): the new tab pages tap it to start typing; with
 * [content] it can hold a text field instead.
 */
@Composable
internal fun SearchCard(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .height(50.dp)
            .browserCard(shape)
            .then(if (focused) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 5.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        content()
    }
}

/** [SearchCard] with a text field that filters as you type, and × to clear it. */
@Composable
internal fun SearchCardField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    SearchCard(modifier, focused = value.isNotEmpty()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = cs.onSurfaceVariant, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) PillButton(Icons.Rounded.Close, "Clear", size = 40.dp) { onValueChange("") }
        else Spacer(Modifier.width(11.dp))
    }
}
