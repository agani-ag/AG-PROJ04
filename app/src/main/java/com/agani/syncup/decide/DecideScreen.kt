package com.agani.syncup.decide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.music.ToolTint
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Tools → Decide (docs/mockup/syncup-everyday-tools.html, phone 2): four little helpers for when
// you can't make up your mind. Coin toss and Wheel can also play on every phone of a SyncUp Group.

/** The four Decide tools, in the grid's order. */
private enum class DecideTool(val title: String, val sub: String, val together: Boolean) {
    COIN("Coin toss", "Heads or tails", true),
    WHEEL("Wheel spinner", "Spin to pick", true),
    NUMBER("Random number", "Any number between two", false),
    YES_NO("Yes / No", "Ask, get an answer", false),
    ;

    val tint: ToolTint
        get() = when (this) {
            COIN -> DecideTint.COIN
            WHEEL -> DecideTint.WHEEL
            NUMBER -> DecideTint.NUMBER
            YES_NO -> DecideTint.YES_NO
        }

    val icon: ImageVector
        get() = when (this) {
            COIN -> DecideIcons.Coin
            WHEEL -> DecideIcons.Wheel
            NUMBER -> Icons.Rounded.Tag
            YES_NO -> Icons.AutoMirrored.Rounded.HelpOutline
        }
}

/** A shared play this old (from its start) is history: opening Decide later doesn't jump to it. */
private const val JUMP_LATE_NS = 500_000_000L

/**
 * Tools → Decide: the grid of four tools, or the open one. [onOpenGroup] opens SyncUp Group, for
 * "…for everyone" while this phone isn't in one.
 */
@Composable
internal fun DecideScreen(onBack: () -> Unit, onOpenGroup: () -> Unit) {
    val context = LocalContext.current
    // Which tool is open (null = the grid); kept across rotation.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val tool = open?.let { name -> DecideTool.entries.firstOrNull { it.name == name } }

    // Builds the little sounds now, so the first toss isn't silent or late.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DecideFx.warmUp(context) } }

    // Someone in the group tossed or spun for everyone: go to that tool, so this phone plays it
    // too. Each tool then plays the shared toss / spin itself, at the same moment as the others.
    val latest = Together.latest
    LaunchedEffect(latest?.id) {
        val play = latest ?: return@LaunchedEffect
        if (play.mine || System.nanoTime() - play.startAtNs > JUMP_LATE_NS) return@LaunchedEffect
        val target = when (play.tool) {
            Together.TOOL_COIN -> DecideTool.COIN
            Together.TOOL_WHEEL -> DecideTool.WHEEL
            else -> null
        }
        if (target != null) open = target.name
    }

    BackHandler(enabled = tool != null) { open = null }
    val backToGrid = { open = null }
    when (tool) {
        DecideTool.COIN -> CoinScreen(onBack = backToGrid, onOpenGroup = onOpenGroup)
        DecideTool.WHEEL -> WheelScreen(onBack = backToGrid, onOpenGroup = onOpenGroup)
        DecideTool.NUMBER -> RandomNumberScreen(onBack = backToGrid)
        DecideTool.YES_NO -> YesNoScreen(onBack = backToGrid)
        null -> DecideHome(onBack = onBack, onOpen = { open = it.name })
    }
}

@Composable
private fun DecideHome(onBack: () -> Unit, onOpen: (DecideTool) -> Unit) {
    val cs = MaterialTheme.colorScheme
    DecidePage("Decide", onBack) {
        Text(
            "Can't decide? Let SyncUp pick.", fontSize = 14.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 14.dp),
        )
        DecideTool.entries.chunked(2).forEachIndexed { i, pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 12.dp),
            ) {
                pair.forEach { t -> ToolCard(t, Modifier.weight(1f)) { onOpen(t) } }
            }
        }
        if (Together.available) GroupCard(Together.phones)
    }
}

/** One square tool card: its icon on a 52 dp tile, the name and a short line; "Together" on Coin and Wheel. */
@Composable
private fun ToolCard(tool: DecideTool, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.atLeastSquare().appCard(CardShape).clickable(onClickLabel = tool.title, onClick = onClick).padding(16.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(tool.tint.tile), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, tint = tool.tint.icon, modifier = Modifier.size(28.dp))
            }
            // 14 dp from the card's top and 12 from its right, as in the design.
            if (tool.together) TogetherBadge(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp))
        }
        Spacer(Modifier.weight(1f))
        Text(tool.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tool.sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The small "Together" badge: this tool can play on every phone of a group. */
@Composable
private fun TogetherBadge(modifier: Modifier) {
    val tint = DecideGroupTint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(tint.tile).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(Icons.Rounded.Groups, null, tint = tint.icon, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text("Together", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = tint.icon, maxLines = 1, softWrap = false)
    }
}

/** Shown only while this phone is in a group. */
@Composable
private fun GroupCard(phones: Int) {
    val tint = DecideGroupTint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tint.tile).padding(14.dp),
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Groups, null, tint = tint.icon, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("In a group · ${phonesText(phones)}", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = tint.icon)
            Text(
                "Coin toss and Wheel can play on every phone at once.",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = tint.icon.copy(alpha = .9f),
            )
        }
    }
}

/**
 * Square, but taller if the text needs it (big font sizes): the height is at least the width. The
 * card's weighted spacer then pushes the name to the bottom of the square.
 */
private fun Modifier.atLeastSquare(): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val minH = w.coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minWidth = w, minHeight = minH))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
