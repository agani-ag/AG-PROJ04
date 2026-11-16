package com.agani.syncup.decide

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
 * Decide: opens straight on the tool used last (Coin toss the first time), with a switch at the top for the
 * other three — no menu page in between (user, 14 Nov 2026). [onOpenGroup] opens SyncUp Group, for
 * "…for everyone" while this phone isn't in one.
 */
@Composable
internal fun DecideScreen(onBack: () -> Unit, onOpenGroup: () -> Unit) {
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember { context.getSharedPreferences("decide", android.content.Context.MODE_PRIVATE) }
    // Which tool is open; kept across rotation, and remembered for next time.
    var open by rememberSaveable {
        mutableStateOf(prefs.getString("last_tool", null)?.takeIf { n -> DecideTool.entries.any { it.name == n } } ?: DecideTool.COIN.name)
    }
    val tool = DecideTool.entries.first { it.name == open }
    LaunchedEffect(open) { prefs.edit().putString("last_tool", open).apply() }

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

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDecideSwitch provides DecideSwitch(tool.ordinal) { i -> open = DecideTool.entries[i].name },
    ) {
        when (tool) {
            DecideTool.COIN -> CoinScreen(onBack = onBack, onOpenGroup = onOpenGroup)
            DecideTool.WHEEL -> WheelScreen(onBack = onBack, onOpenGroup = onOpenGroup)
            DecideTool.NUMBER -> RandomNumberScreen(onBack = onBack)
            DecideTool.YES_NO -> YesNoScreen(onBack = onBack)
        }
    }
}
