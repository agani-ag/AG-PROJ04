package com.agani.syncup.tools

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.alpha
import com.agani.syncup.wellbeing.Breaks
import com.agani.syncup.wellbeing.Habits
import com.agani.syncup.wellbeing.Mood
import com.agani.syncup.wellbeing.Sleep
import com.agani.syncup.wellbeing.Steps
import com.agani.syncup.wellbeing.Water
import com.agani.syncup.wellbeing.Wellbeing
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
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
import com.agani.syncup.home.ToolTint
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTab
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.video.SavedPlaylists
import com.agani.syncup.walkie.Walkie
import com.agani.syncup.walkie.walkieStatus
import com.agani.syncup.watchparty.watchPartyStatus

/** What's open inside Tools. */
enum class ToolsPage { HOME, VIDEOS, CHANNELS, QUICK_SHARE, WALKIE, GROUP, SENSORS, CALENDAR, FOREST, WATER, STEPS, BREAKS, SLEEP, HABITS, MOOD }

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
    val icon: ImageVector, val tint: ToolTint,
    val title: String, val subtitle: String, val status: String?, val onClick: () -> Unit,
    val tab: ToolsTab, val greyed: Boolean = false,
)

/** The four tabs of Tools, each a few related tools. */
enum class ToolsTab(val title: String) { MEDIA("Media"), NEARBY("Nearby"), EVERYDAY("Everyday"), WELLBEING("Wellbeing") }

/**
 * SyncUp's extra features in four tabs — Media (Music, Video player, Channels, Radio), Nearby (SyncUp
 * Share, Walkie Talkie, Group), Everyday (Calendar, Sensors) and Wellbeing (Forest, Water, Steps,
 * Habits, Sleep, Breaks, Mood). Swipe or tap a tab; Tools opens on the last one used. Each tile shows
 * what it's doing right now; a dot on a tab means something in it is running. Tiles stay square, two
 * to a row.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /** Tools → Wellbeing's own pages (Water, Steps, …). */
    onTool: (ToolsPage) -> Unit = {},
    onBack: () -> Unit,
    /** Shown as the Tools tab (the bar below it): no back arrow, the bar takes the bottom inset. */
    inTabs: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val player = LocalPlayer.current
    val savedChannelsCount = SavedPlaylists.all(ctx).size
    remember { Wellbeing.init(ctx) }
    // Redraw when a Wellbeing tool changes.
    @Suppress("UNUSED_VARIABLE") val wv = Water.version + Steps.version + Habits.version + Sleep.version + Mood.version + Breaks.version

    val tiles = buildList {
        // ---- Media
        add(
            TileSpec(
                Icons.Rounded.LibraryMusic, ToolTint.VIOLET,
                "Music", "Songs on your phone",
                status = player?.title?.takeIf { player.isPlaying && !player.isRadio && it.isNotBlank() }?.let { "Playing: $it" },
                onClick = onMusic, tab = ToolsTab.MEDIA,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.SmartDisplay, ToolTint.ROSE,
                "Video player", "Videos & Watch Party", status = watchPartyStatus(), onClick = onVideos, tab = ToolsTab.MEDIA,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.LiveTv, ToolTint.GREEN,
                "Channels",
                if (savedChannelsCount == 0) "Add an M3U / M3U8 link" else "$savedChannelsCount saved list${if (savedChannelsCount == 1) "" else "s"}",
                status = null, onClick = onChannels, tab = ToolsTab.MEDIA,
            ),
        )
        // Shown to everyone: Party needs no sign-in (Live stations inside keep their own rules).
        add(
            TileSpec(
                Icons.Rounded.Radio, ToolTint.ORANGE,
                "Radio", if (radioAvailable) "Parties nearby & live stations" else "Parties nearby",
                status = partyStatus()
                    ?: player?.title?.takeIf { player.isPlaying && player.isRadio && it.isNotBlank() }?.let { "On air: $it" },
                onClick = onRadio, tab = ToolsTab.MEDIA,
            ),
        )
        // ---- Nearby
        add(
            TileSpec(
                Icons.Rounded.SwapHoriz, ToolTint.BLUE,
                "SyncUp Share", "Send files & text to a nearby phone",
                status = if (QuickShare.busy) {
                    val total = QuickShare.totalBytes
                    val pct = if (total > 0) " · ${QuickShare.doneBytes * 100 / total}%" else ""
                    (if (QuickShare.role == QsRole.SEND) "Sending" else "Receiving") + pct
                } else {
                    null
                },
                onClick = onQuickShare, tab = ToolsTab.NEARBY,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.RecordVoiceOver, ToolTint.TEAL,
                "Walkie Talkie", "Talk to friends nearby",
                status = walkieStatus(),
                onClick = onWalkie, tab = ToolsTab.NEARBY,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.Groups, ToolTint.INDIGO,
                "Group", "Up to 8 phones: music, watch, talk, share",
                status = if (com.agani.syncup.group.GroupLink.active) "In a group · ${com.agani.syncup.group.GroupLink.mode.label}" else null,
                onClick = onGroup, tab = ToolsTab.NEARBY,
            ),
        )
        // ---- Everyday
        add(
            TileSpec(
                Icons.Rounded.CalendarMonth, ToolTint.INDIGO,
                "Calendar", "To-dos & reminders",
                status = com.agani.syncup.calendar.calendarStatus(),
                onClick = onCalendar, tab = ToolsTab.EVERYDAY,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.Explore, ToolTint.SKY,
                "Sensors", "Compass, level, light & sound meters…",
                status = if (com.agani.syncup.sensors.Torch.on) "Flashlight on" else null,
                onClick = onSensors, tab = ToolsTab.EVERYDAY,
            ),
        )
        // ---- Wellbeing
        add(
            TileSpec(
                Icons.Rounded.Eco, ToolTint.LIME,
                "Forest", "Calm time grows trees",
                status = com.agani.syncup.forest.forestStatus(),
                onClick = onForest, tab = ToolsTab.WELLBEING,
            ),
        )
        val ws = Water.settings
        add(
            TileSpec(
                Icons.Rounded.WaterDrop, ToolTint.CYAN,
                "Water", "Drink & track your water",
                status = if (ws.setUp) "${Water.fmt(Water.today(), ws.oz)} of ${Water.fmt(ws.goal, ws.oz)}" else null,
                onClick = { onTool(ToolsPage.WATER) }, tab = ToolsTab.WELLBEING,
            ),
        )
        val stepsHere = Steps.present(ctx)
        add(
            TileSpec(
                Icons.AutoMirrored.Rounded.DirectionsWalk, ToolTint.AMBER,
                "Steps", if (stepsHere) "Your steps & daily goal" else "No step sensor on this phone",
                status = if (stepsHere && Steps.counting(ctx)) "${"%,d".format(Steps.today())} of ${"%,d".format(Steps.settings.goal)}" else null,
                onClick = {
                    if (stepsHere) onTool(ToolsPage.STEPS)
                    else Toast.makeText(ctx, "This phone has no step sensor, so Steps can't count here.", Toast.LENGTH_SHORT).show()
                },
                tab = ToolsTab.WELLBEING, greyed = !stepsHere,
            ),
        )
        val (hDone, hDue) = Habits.todayProgress()
        add(
            TileSpec(
                Icons.Rounded.TaskAlt, ToolTint.PINK,
                "Habits", "Tick off daily habits",
                status = if (hDue > 0) "$hDone of $hDue today" else null,
                onClick = { onTool(ToolsPage.HABITS) }, tab = ToolsTab.WELLBEING,
            ),
        )
        val night = Sleep.current()
        add(
            TileSpec(
                Icons.Rounded.Bedtime, ToolTint.INDIGO,
                "Sleep", "Bedtime & sleep sounds",
                status = when {
                    night != null -> "Sleeping since ${com.agani.syncup.wellbeing.clockText(night.start)}"
                    Sleep.soundsOn -> "Sounds playing"
                    Sleep.settings.remind -> "Bedtime ${com.agani.syncup.wellbeing.timeText(Sleep.settings.bedtime)}"
                    else -> null
                },
                onClick = { onTool(ToolsPage.SLEEP) }, tab = ToolsTab.WELLBEING,
            ),
        )
        val bs = Breaks.settings
        val nextBreak = Breaks.next()
        add(
            TileSpec(
                Icons.Rounded.Visibility, ToolTint.TEAL,
                "Breaks", "Eye & stretch breaks",
                status = when {
                    !bs.setUp -> null
                    Breaks.pausedUntil() > System.currentTimeMillis() -> "Paused"
                    nextBreak != null && com.agani.syncup.forest.FDays.of(nextBreak.first) == com.agani.syncup.forest.FDays.today() ->
                        "Next break ${com.agani.syncup.wellbeing.clockText(nextBreak.first)}"
                    else -> null
                },
                onClick = { onTool(ToolsPage.BREAKS) }, tab = ToolsTab.WELLBEING,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.SentimentSatisfied, ToolTint.LIME,
                "Mood", "How are you today?",
                status = Mood.lastToday()?.let { "Today: ${listOf("Bad", "Low", "Okay", "Good", "Great")[it - 1]}" },
                onClick = { onTool(ToolsPage.MOOD) }, tab = ToolsTab.WELLBEING,
            ),
        )
    }

    // Something running in a tab puts a dot on it.
    val running = mapOf(
        ToolsTab.MEDIA to (player?.isPlaying == true || Party.active || watchPartyStatus() != null),
        ToolsTab.NEARBY to (QuickShare.busy || Walkie.active || com.agani.syncup.group.GroupLink.active),
        ToolsTab.EVERYDAY to com.agani.syncup.sensors.Torch.on,
        ToolsTab.WELLBEING to (com.agani.syncup.forest.CalmSession.active || Sleep.soundsOn || Sleep.current() != null),
    )
    val prefs = remember { ctx.getSharedPreferences("tools", android.content.Context.MODE_PRIVATE) }
    val pager = rememberPagerState(initialPage = prefs.getInt("tab", 0).coerceIn(0, ToolsTab.entries.lastIndex)) { ToolsTab.entries.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { prefs.edit().putInt("tab", it).apply() }
    }

    val insets = if (inTabs) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(insets)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = if (inTabs) 0.dp else 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
            ) {
                if (!inTabs) BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text(
                    "Tools", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                    modifier = Modifier.weight(1f).padding(start = if (inTabs) 20.dp else 4.dp),
                )
            }
            // Tabs: a pill switch; a green dot means something in that tab is running.
            PillTabs(
                ToolsTab.entries.map { PillTab(it.title, dot = running[it] == true) },
                selected = pager.currentPage,
                onSelect = { i -> scope.launch { pager.animateScrollToPage(i, animationSpec = tween(250)) } },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                val tab = ToolsTab.entries[page]
                val mine = tiles.filter { it.tab == tab }
                LazyColumn(contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    // In a group: the way back to it.
                    if (tab == ToolsTab.NEARBY && com.agani.syncup.group.GroupLink.active) {
                        item(key = "group") { GroupBanner(onGroup) }
                    }
                    for (row in mine.chunked(2)) {
                        item(key = row.first().title) {
                            TileRow {
                                row.forEach { t -> Tile(t.icon, t.tint, t.title, t.subtitle, status = t.status, greyed = t.greyed, onClick = t.onClick) }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), content = content)
}

/** A square tool card: its icon on its own colour, the name, what it's doing now, and what it's for. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(
    icon: ImageVector,
    tint: ToolTint,
    title: String,
    subtitle: String,
    status: String? = null,
    greyed: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).aspectRatio(1f).alpha(if (greyed) .45f else 1f).appCard(CardShape)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(tint.tile), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint.icon, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tint.icon))
                Spacer(Modifier.width(6.dp))
                Text(status, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** "You're in Ravi's group · Watch together · 4 phones" — Open goes to what the group is doing. */
@Composable
private fun GroupBanner(onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val g = com.agani.syncup.group.GroupLink
    val tint = ToolTint.INDIGO
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(tint.tile)
            .clickable(onClick = onOpen).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Icon(Icons.Rounded.Groups, null, tint = tint.icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (g.phase == com.agani.syncup.group.GroupPhase.JOINING) "Joining ${g.groupName}…" else "You're in ${g.groupName}",
                color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${g.mode.label} · ${g.people.size} phone${if (g.people.size == 1) "" else "s"}",
                color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Open", color = tint.tile, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.icon).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
