package com.agani.syncup.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.party.Party
import com.agani.syncup.party.partyStatus
import com.agani.syncup.quickshare.QsRole
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.video.SavedPlaylists
import com.agani.syncup.walkie.Walkie
import com.agani.syncup.walkie.walkieStatus
import com.agani.syncup.watchparty.watchPartyStatus

/** What's open inside Tools. */
enum class ToolsPage { HOME, VIDEOS, CHANNELS, QUICK_SHARE, WALKIE, GROUP, SENSORS, CALENDAR, FOREST }

/** One line for the ⋮ menu's Tools row: what's going on inside right now (null = nothing). */
@Composable
fun toolsStatus(): String? {
    val player = LocalPlayer.current
    return when {
        QuickShare.busy -> {
            val what = if (QuickShare.textMode) "text" else "files"
            if (QuickShare.role == QsRole.SEND) "Sending $what" else "Receiving $what"
        }
        com.agani.syncup.group.GroupLink.active -> "In a group · ${com.agani.syncup.group.GroupLink.mode.label}"
        Walkie.active -> "Walkie Talkie on"
        player?.isPlaying != true -> null
        player.isRadio -> "On air"
        else -> "Playing music"
    }
}

private class TileSpec(
    val icon: ImageVector, val accent: Color, val tint: Color,
    val title: String, val subtitle: String, val status: String?, val onClick: () -> Unit,
)

/**
 * SyncUp's extra features in one place — Music, Video player, Channels, Radio (Party + live stations), SyncUp Share,
 * Walkie Talkie, Sensors — each
 * tile showing what it's doing right now, laid out as one plain 2-column grid (every row has two
 * tiles; only the last one can have a single tile when the count is odd).
 */
@Composable
fun ToolsScreen(
    radioAvailable: Boolean,
    onMusic: () -> Unit,
    onVideos: () -> Unit,
    onRadio: () -> Unit,
    onChannels: () -> Unit,
    onQuickShare: () -> Unit,
    onWalkie: () -> Unit,
    onSensors: () -> Unit,
    onCalendar: () -> Unit = {},
    onForest: () -> Unit = {},
    onGroup: () -> Unit = {},
    onBack: () -> Unit,
    /** Shown as the Tools tab (the bar below it): no back arrow, the bar takes the bottom inset. */
    inTabs: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    val player = LocalPlayer.current
    val savedChannelsCount = SavedPlaylists.all(LocalContext.current).size

    val tiles = buildList {
        add(
            TileSpec(
                Icons.Rounded.LibraryMusic, accent(0xFF6D28D9, 0xFFC4B5FD, light), tint(0xFFEDE9FE, 0xFF3B2A63, light),
                "Music", "Songs on your phone",
                status = player?.title?.takeIf { player.isPlaying && !player.isRadio && it.isNotBlank() }?.let { "Playing: $it" },
                onClick = onMusic,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.SmartDisplay, accent(0xFFBE123C, 0xFFFDA4AF, light), tint(0xFFFFE4E6, 0xFF4C0519, light),
                "Video player", "Videos & Watch Party", status = watchPartyStatus(), onClick = onVideos,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.LiveTv, accent(0xFF15803D, 0xFF86EFAC, light), tint(0xFFDCFCE7, 0xFF14532D, light),
                "Channels",
                if (savedChannelsCount == 0) "Add an M3U / M3U8 link" else "$savedChannelsCount saved list${if (savedChannelsCount == 1) "" else "s"}",
                status = null, onClick = onChannels,
            ),
        )
        // Shown to everyone: Party needs no sign-in (Live stations inside keep their own rules).
        add(
            TileSpec(
                Icons.Rounded.Radio, accent(0xFFC2410C, 0xFFFDBA74, light), tint(0xFFFFEDD5, 0xFF431407, light),
                "Radio", if (radioAvailable) "Parties nearby & live stations" else "Parties nearby",
                status = partyStatus()
                    ?: player?.title?.takeIf { player.isPlaying && player.isRadio && it.isNotBlank() }?.let { "On air: $it" },
                onClick = onRadio,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.SwapHoriz, accent(0xFF1D4ED8, 0xFF93C5FD, light), tint(0xFFDBEAFE, 0xFF1E3A8A, light),
                "SyncUp Share", "Send files & text to a nearby phone",
                status = if (QuickShare.busy) {
                    val total = QuickShare.totalBytes
                    val pct = if (total > 0) " · ${QuickShare.doneBytes * 100 / total}%" else ""
                    (if (QuickShare.role == QsRole.SEND) "Sending" else "Receiving") + pct
                } else {
                    null
                },
                onClick = onQuickShare,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.RecordVoiceOver, accent(0xFF0F766E, 0xFF5EEAD4, light), tint(0xFFCCFBF1, 0xFF134E4A, light),
                "Walkie Talkie", "Talk to friends nearby",
                status = walkieStatus(),
                onClick = onWalkie,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.Explore, accent(0xFF0369A1, 0xFF7DD3FC, light), tint(0xFFE0F2FE, 0xFF0C4A6E, light),
                "Sensors", "Compass, level, light & sound meters…",
                status = if (com.agani.syncup.sensors.Torch.on) "Flashlight on" else null,
                onClick = onSensors,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.CalendarMonth, accent(0xFF4F46E5, 0xFFA5B4FC, light), tint(0xFFE0E7FF, 0xFF312E81, light),
                "Calendar", "To-dos & reminders",
                status = com.agani.syncup.calendar.calendarStatus(),
                onClick = onCalendar,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.Eco, accent(0xFF4D7C0F, 0xFFBEF264, light), tint(0xFFECFCCB, 0xFF1A2E05, light),
                "Forest", "Calm time grows trees",
                status = com.agani.syncup.forest.forestStatus(),
                onClick = onForest,
            ),
        )
    }

    val insets = if (inTabs) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(insets)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                if (!inTabs) BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Tools", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = if (inTabs) 16.dp else 4.dp))
            }
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // In a group: the way back to it from anywhere.
                if (com.agani.syncup.group.GroupLink.active) {
                    item { GroupBanner(onGroup) }
                }
                for (row in tiles.chunked(2)) {
                    item {
                        TileRow {
                            row.forEach { t -> Tile(t.icon, t.accent, t.tint, t.title, t.subtitle, status = t.status, onClick = t.onClick) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun accent(light: Long, dark: Long, isLight: Boolean) = Color(if (isLight) light else dark)
private fun tint(light: Long, dark: Long, isLight: Boolean) = Color(if (isLight) light else dark)

@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(
    icon: ImageVector,
    color: Color,
    container: Color,
    title: String,
    subtitle: String,
    status: String? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.weight(1f).aspectRatio(1f).clip(shape)
            .background(if (highlighted) container.copy(alpha = .45f) else cs.surfaceContainerLowest)
            .border(1.dp, if (highlighted) color.copy(alpha = .5f) else cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(status, fontSize = 12.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** "You're in Ravi's group · Watch together · 4 phones" — Open goes to what the group is doing. */
@Composable
private fun GroupBanner(onOpen: () -> Unit) {
    val g = com.agani.syncup.group.GroupLink
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF4338CA), Color(0xFF6366F1))))
            .clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Rounded.Groups, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (g.phase == com.agani.syncup.group.GroupPhase.JOINING) "Joining ${g.groupName}…" else "You're in ${g.groupName}",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Text(
                "${g.mode.label} · ${g.people.size} phone${if (g.people.size == 1) "" else "s"}",
                color = Color.White.copy(alpha = .88f), fontSize = 12.5.sp, maxLines = 1,
            )
        }
        Text(
            "Open", color = Color(0xFF4338CA), fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(17.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
