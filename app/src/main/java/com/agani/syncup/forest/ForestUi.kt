package com.agani.syncup.forest

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.agani.syncup.ui.theme.isLight


/** Paper, moss and sunlight — inside Forest only. Dark theme: the same, by lamplight. */
internal class ForestColors(
    val paper: Color,
    val paper2: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val line: Color,
    val moss: Color,
    val onMoss: Color,
    val soft: Color,
    val softLine: Color,
    val gold: Color,
    val light: Boolean,
)

private val LightForest = ForestColors(
    paper = Color(0xFFF6F2E7), paper2 = Color(0xFFEEE7D6), card = Color(0xFFFFFCF4), ink = Color(0xFF22301F),
    ink2 = Color(0xFF4B5A45), muted = Color(0xFF7A846F), line = Color(0xFFE4DCC7), moss = Color(0xFF2F5D3A),
    onMoss = Color(0xFFF6F2E7), soft = Color(0xFFEAF2E1), softLine = Color(0xFFCBDDB8), gold = Color(0xFFD9A441), light = true,
)

private val DarkForest = ForestColors(
    paper = Color(0xFF141A13), paper2 = Color(0xFF1C2419), card = Color(0xFF1E261C), ink = Color(0xFFE6EEDF),
    ink2 = Color(0xFFC3D1BB), muted = Color(0xFF93A08A), line = Color(0xFF323F2E), moss = Color(0xFF9CCB8A),
    onMoss = Color(0xFF10200F), soft = Color(0xFF24331F), softLine = Color(0xFF3A5233), gold = Color(0xFFE2B65A), light = false,
)

@Composable
internal fun forestColors(): ForestColors = if (MaterialTheme.colorScheme.isLight) LightForest else DarkForest

/** Android's "Remove animations": the forest stands still. */
@Composable
internal fun animationsOn(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
}

/** Seconds since this was first shown, ticking at a gentle 30 frames a second (only while on screen). */
@Composable
internal fun rememberClock(animate: Boolean = animationsOn()): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = androidx.compose.runtime.withFrameNanos { it }
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { n ->
                    if (n - last >= 33_000_000L) {
                        last = n
                        t.floatValue = (n - start) / 1e9f
                    }
                }
            }
        }
    }
    return t
}

/** Status-bar icons for a page drawn under the bar (dark icons on a light sky); back to the app's own after. */
@Composable
internal fun BarIcons(light: Boolean) {
    val view = LocalView.current
    val appLight = MaterialTheme.colorScheme.isLight
    DisposableEffect(light) {
        val w = (view.context as? Activity)?.window
        if (w != null) WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = light
        onDispose {
            if (w != null) WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = appLight
        }
    }
}

@Composable
internal fun ForestTopBar(title: String, onBack: () -> Unit, color: Color = forestColors().ink, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = color) }
        Text(title, fontSize = 22.sp, color = color, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

/**
 * Forest's titles, in the app's own font: big ones light and a little roomy, so the page stays calm.
 * [weight] by default: Light from 28 sp up, Normal below.
 */
@Composable
internal fun TitleText(text: String, size: TextUnit, color: Color = forestColors().ink, weight: FontWeight? = null, modifier: Modifier = Modifier, italic: Boolean = false) {
    Text(
        text, fontSize = size, color = color, fontWeight = weight ?: if (size.value >= 28f) FontWeight.Light else FontWeight.Normal, modifier = modifier,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        letterSpacing = if (size.value >= 20f) (-0.2).sp else 0.sp,
        lineHeight = size * 1.2f,
    )
}

/** A small label above a group ("PLANT A SEED"). */
@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) {
    val c = forestColors()
    Text(text.uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.muted, letterSpacing = .3.sp, modifier = modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
internal fun FChip(text: String, on: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, dark: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = forestColors()
    val bg = when {
        dark && on -> Color(0x38D8ECC8)
        dark -> Color(0x14FFFFFF)
        on -> c.moss
        else -> c.card
    }
    val border = when {
        dark && on -> Color(0x66D8ECC8)
        dark -> Color(0x24FFFFFF)
        on -> c.moss
        else -> c.line
    }
    val fg = when {
        dark && on -> Color.White
        dark -> Color(0xFFDDEBD6)
        on -> c.onMoss
        enabled -> c.ink2
        else -> c.muted.copy(alpha = .6f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

@Composable
internal fun Pill(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val c = forestColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 12.dp),
        content = content,
    )
}

@Composable
internal fun PillText(bold: String, rest: String) {
    val c = forestColors()
    Text(bold, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.moss)
    Text(rest, fontSize = 12.5.sp, color = c.ink2)
}

@Composable
internal fun MossButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, container: Color = forestColors().moss, content: Color = forestColors().onMoss, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.height(50.dp).clip(RoundedCornerShape(25.dp)).background(container).clickable(onClick = onClick).padding(horizontal = 18.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
    }
}

@Composable
internal fun LineButton(text: String, modifier: Modifier = Modifier, color: Color = forestColors().moss, border: Color = Color(0xFFCFC5AC), onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(50.dp).clip(RoundedCornerShape(25.dp)).border(BorderStroke(1.5.dp, border), RoundedCornerShape(25.dp)).clickable(onClick = onClick),
    ) { Text(text, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1) }
}

@Composable
internal fun Note(text: String, icon: ImageVector, modifier: Modifier = Modifier, dark: Boolean = false) {
    val c = forestColors()
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (dark) Color(0x12FFFFFF) else c.paper2).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = if (dark) Color(0xFFCFE3C6) else c.ink2, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = if (dark) Color(0xFFCFE3C6) else c.ink2)
    }
}

/** A progress ring (today's minutes, a tree's growth). */
@Composable
internal fun Ring(progress: Float, size: Dp, stroke: Dp, color: Color, track: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val sw = stroke.toPx()
        val d = this.size.minDimension - sw
        val tl = Offset(sw / 2, sw / 2)
        drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
        if (progress > 0f) drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

// ------------------------------------------------------------------ words
internal fun durationText(ms: Long): String {
    val min = (ms + 59_999) / 60_000
    return when {
        min < 60 -> "$min min"
        min < 24 * 60 -> "${(min + 59) / 60} h"
        else -> (min + 24 * 60 - 1).div(24 * 60).let { "$it day${if (it == 1L) "" else "s"}" }
    }
}

internal fun minutesText(min: Int): String = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} m"
}

internal fun growDaysText(days: Int): String = when (days) {
    7 -> "1 week"
    14 -> "2 weeks"
    1 -> "1 day"
    else -> "$days days"
}

/** "Mango sapling" — what the scene's card and the lists call a tree. */
internal fun Tree.stageName(now: Long = System.currentTimeMillis()): String {
    val s = stage(now)
    return when {
        name.isNotBlank() -> name
        s == Stage.GROWN -> species.title
        s == Stage.YOUNG -> "Young ${species.title.lowercase()}"
        else -> "${species.title} ${s.title.lowercase()}"
    }
}

/** What comes next for a tree, in a few words. */
internal fun Tree.nextText(now: Long = System.currentTimeMillis(), resting: Boolean = false): String {
    if (resting) return "Resting — one calm session and it's green"
    val n = next(now)
    if (n == null) {
        val m = FDays.month()
        return when {
            m in species.season && species.fruitsIn(m) -> "Fruiting now"
            m in species.season -> "In flower now"
            else -> "Grown · gave $seedsGiven seed${if (seedsGiven == 1) "" else "s"}"
        }
    }
    val (st, mins, ms) = n
    val title = if (st == Stage.YOUNG) "Young tree" else st.title
    return when {
        mins > 0 && ms > 0 -> "$title in ${durationText(ms)} · $mins more calm min"
        mins > 0 -> "$title after $mins more calm min"
        ms > 0 -> "$title in ${durationText(ms)}"
        else -> "$title soon"
    }
}

/** How far the whole tree has grown, 0–1 (its time and its calm, whichever is behind). */
internal fun Tree.growth(now: Long = System.currentTimeMillis()): Float {
    val t = ((now - plantedMs).toFloat() / (growDays * 86_400_000f)).coerceIn(0f, 1f)
    val c = (care.toFloat() / calmNeeded).coerceIn(0f, 1f)
    return minOf(t, c)
}
