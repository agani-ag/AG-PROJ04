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
    val icon: ImageVector, val accent: Color, val tint: Color,
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
    val light = cs.isLight
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
                Icons.Rounded.LibraryMusic, accent(0xFF6D28D9, 0xFFC4B5FD, light), tint(0xFFEDE9FE, 0xFF3B2A63, light),
                "Music", "Songs on your phone",
                status = player?.title?.takeIf { player.isPlaying && !player.isRadio && it.isNotBlank() }?.let { "Playing: $it" },
                onClick = onMusic, tab = ToolsTab.MEDIA,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.SmartDisplay, accent(0xFFBE123C, 0xFFFDA4AF, light), tint(0xFFFFE4E6, 0xFF4C0519, light),
                "Video player", "Videos & Watch Party", status = watchPartyStatus(), onClick = onVideos, tab = ToolsTab.MEDIA,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.LiveTv, accent(0xFF15803D, 0xFF86EFAC, light), tint(0xFFDCFCE7, 0xFF14532D, light),
                "Channels",
                if (savedChannelsCount == 0) "Add an M3U / M3U8 link" else "$savedChannelsCount saved list${if (savedChannelsCount == 1) "" else "s"}",
                status = null, onClick = onChannels, tab = ToolsTab.MEDIA,
            ),
        )
        // Shown to everyone: Party needs no sign-in (Live stations inside keep their own rules).
        add(
            TileSpec(
                Icons.Rounded.Radio, accent(0xFFC2410C, 0xFFFDBA74, light), tint(0xFFFFEDD5, 0xFF431407, light),
                "Radio", if (radioAvailable) "Parties nearby & live stations" else "Parties nearby",
                status = partyStatus()
                    ?: player?.title?.takeIf { player.isPlaying && player.isRadio && it.isNotBlank() }?.let { "On air: $it" },
                onClick = onRadio, tab = ToolsTab.MEDIA,
            ),
        )
        // ---- Nearby
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
                onClick = onQuickShare, tab = ToolsTab.NEARBY,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.RecordVoiceOver, accent(0xFF0F766E, 0xFF5EEAD4, light), tint(0xFFCCFBF1, 0xFF134E4A, light),
                "Walkie Talkie", "Talk to friends nearby",
                status = walkieStatus(),
                onClick = onWalkie, tab = ToolsTab.NEARBY,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.Groups, accent(0xFF4338CA, 0xFFA5B4FC, light), tint(0xFFE0E7FF, 0xFF312E81, light),
                "Group", "Up to 8 phones: music, watch, talk, share",
                status = if (com.agani.syncup.group.GroupLink.active) "In a group · ${com.agani.syncup.group.GroupLink.mode.label}" else null,
                onClick = onGroup, tab = ToolsTab.NEARBY,
            ),
        )
        // ---- Everyday
        add(
            TileSpec(
                Icons.Rounded.CalendarMonth, accent(0xFF4F46E5, 0xFFA5B4FC, light), tint(0xFFE0E7FF, 0xFF312E81, light),
                "Calendar", "To-dos & reminders",
                status = com.agani.syncup.calendar.calendarStatus(),
                onClick = onCalendar, tab = ToolsTab.EVERYDAY,
            ),
        )
        add(
            TileSpec(
                Icons.Rounded.Explore, accent(0xFF0369A1, 0xFF7DD3FC, light), tint(0xFFE0F2FE, 0xFF0C4A6E, light),
                "Sensors", "Compass, level, light & sound meters…",
                status = if (com.agani.syncup.sensors.Torch.on) "Flashlight on" else null,
                onClick = onSensors, tab = ToolsTab.EVERYDAY,
            ),
        )
        // ---- Wellbeing
        add(
            TileSpec(
                Icons.Rounded.Eco, accent(0xFF4D7C0F, 0xFFBEF264, light), tint(0xFFECFCCB, 0xFF1A2E05, light),
                "Forest", "Calm time grows trees",
                status = com.agani.syncup.forest.forestStatus(),
                onClick = onForest, tab = ToolsTab.WELLBEING,
            ),
        )
        val ws = Water.settings
        add(
            TileSpec(
                Icons.Rounded.WaterDrop, accent(0xFF0E7490, 0xFF67E8F9, light), tint(0xFFCFFAFE, 0xFF164E63, light),
                "Water", "Drink & track your water",
                status = if (ws.setUp) "${Water.fmt(Water.today(), ws.oz)} of ${Water.fmt(ws.goal, ws.oz)}" else null,
                onClick = { onTool(ToolsPage.WATER) }, tab = ToolsTab.WELLBEING,
            ),
        )
        val stepsHere = Steps.present(ctx)
        add(
            TileSpec(
                Icons.AutoMirrored.Rounded.DirectionsWalk, accent(0xFFB45309, 0xFFFCD34D, light), tint(0xFFFEF3C7, 0xFF451A03, light),
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
                Icons.Rounded.TaskAlt, accent(0xFFBE185D, 0xFFF9A8D4, light), tint(0xFFFCE7F3, 0xFF500724, light),
                "Habits", "Tick off daily habits",
                status = if (hDue > 0) "$hDone of $hDue today" else null,
                onClick = { onTool(ToolsPage.HABITS) }, tab = ToolsTab.WELLBEING,
            ),
        )
        val night = Sleep.current()
        add(
            TileSpec(
                Icons.Rounded.Bedtime, accent(0xFF4338CA, 0xFFA5B4FC, light), tint(0xFFE0E7FF, 0xFF312E81, light),
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
                Icons.Rounded.Visibility, accent(0xFF0F766E, 0xFF5EEAD4, light), tint(0xFFCCFBF1, 0xFF134E4A, light),
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
                Icons.Rounded.SentimentSatisfied, accent(0xFF4D7C0F, 0xFFBEF264, light), tint(0xFFECFCCB, 0xFF1A2E05, light),
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                if (!inTabs) BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Tools", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = if (inTabs) 16.dp else 4.dp))
            }
            // Tabs: the page's own colours; the chosen one underlined.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                ToolsTab.entries.forEachIndexed { i, t ->
                    val on = pager.currentPage == i
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { scope.launch { pager.animateScrollToPage(i) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(46.dp)) {
                            Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) cs.primary else cs.onSurfaceVariant, maxLines = 1)
                            if (running[t] == true) {
                                Spacer(Modifier.width(5.dp))
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(if (light) 0xFF16A34A else 0xFF4ADE80)))
                            }
                        }
                        Box(Modifier.fillMaxWidth(.66f).height(3.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (on) cs.primary else Color.Transparent))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                val tab = ToolsTab.entries[page]
                val mine = tiles.filter { it.tab == tab }
                LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    // In a group: the way back to it.
                    if (tab == ToolsTab.NEARBY && com.agani.syncup.group.GroupLink.active) {
                        item { GroupBanner(onGroup) }
                    }
                    for (row in mine.chunked(2)) {
                        item {
                            TileRow {
                                row.forEach { t -> Tile(t.icon, t.accent, t.tint, t.title, t.subtitle, status = t.status, greyed = t.greyed, onClick = t.onClick) }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
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
    greyed: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.weight(1f).aspectRatio(1f).alpha(if (greyed) .45f else 1f).clip(shape)
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
