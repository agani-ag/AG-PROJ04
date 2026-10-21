package com.agani.syncup.home

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.ui.platform.LocalContext
import com.agani.syncup.wellbeing.Breaks
import com.agani.syncup.wellbeing.Habits
import com.agani.syncup.wellbeing.Mood
import com.agani.syncup.wellbeing.Sleep
import com.agani.syncup.wellbeing.Steps
import com.agani.syncup.wellbeing.Water
import com.agani.syncup.wellbeing.Wellbeing
import com.agani.syncup.wellbeing.clockText
import com.agani.syncup.wellbeing.hoursText
import com.agani.syncup.wellbeing.timeText
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.calendar.CalendarStore
import com.agani.syncup.calendar.Days
import com.agani.syncup.data.UrlItem
import com.agani.syncup.forest.CalmSession
import com.agani.syncup.forest.ForestStore
import com.agani.syncup.forest.stageName
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.ui.theme.isLight
import kotlinx.coroutines.delay
import java.util.Calendar

/** What Home can open; MainActivity knows where each one lives. */
enum class HomeTool { CALENDAR, FOREST, SHARE, WALKIE, MUSIC, VIDEO, RADIO, SENSORS, PARTY, WATCH_PARTY, GROUP, WATER, STEPS, HABITS, SLEEP, BREAKS, MOOD }

// ============================================================================ the bottom bar
/** Home · Browse · Tools · You — on Home, Tools and You (the browser keeps its own bar). */
@Composable
fun AppTabBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    NavigationBar(containerColor = cs.surfaceContainer, tonalElevation = 0.dp) {
        for ((tab, label, icons) in listOf(
            Triple(MainTab.HOME, "Home", Icons.Rounded.Home to Icons.Outlined.Home),
            Triple(MainTab.BROWSE, "Browse", Icons.Rounded.Public to Icons.Outlined.Public),
            Triple(MainTab.TOOLS, "Tools", Icons.Rounded.GridView to Icons.Outlined.GridView),
            Triple(MainTab.YOU, "You", Icons.Rounded.Person to Icons.Outlined.Person),
        )) {
            val on = tab == current
            NavigationBarItem(
                selected = on,
                onClick = { onSelect(tab) },
                icon = {
                    if (tab == MainTab.YOU && com.agani.syncup.notifications.Inbox.newCount > 0) {
                        BadgedBox(badge = { Badge() }) { Icon(if (on) icons.first else icons.second, null) }
                    } else {
                        Icon(if (on) icons.first else icons.second, null)
                    }
                },
                label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = cs.primaryContainer, selectedIconColor = cs.onPrimaryContainer),
            )
        }
    }
}

/** A tab's page with the bottom bar under it. */
@Composable
fun TabFrame(current: MainTab, onSelect: (MainTab) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.weight(1f)) { content() }
        AppTabBar(current, onSelect)
    }
}

// ============================================================================ Home
/**
 * SyncUp's start: search the web straight away, then what matters today — to-dos and calm minutes,
 * what's playing, what's happening nearby, favourite tools, SyncUp links. A card shows only when it
 * has something to say, and each can be hidden (Customise Home / Settings → Home).
 */
@Composable
fun HomeScreen(
    signedIn: Boolean,
    userInitial: String?,
    links: List<UrlItem>,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onAccount: () -> Unit,
    onTool: (HomeTool) -> Unit,
    onAllTools: () -> Unit,
    onOpenLink: (UrlItem) -> Unit,
    onAllLinks: () -> Unit,
    onOpenBrowse: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var customise by remember { mutableStateOf(false) }
    val player = LocalPlayer.current
    val nearby = nearbyRows()
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text("SyncUp", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = onNotifications) {
                    val n = com.agani.syncup.notifications.Inbox.newCount
                    if (n > 0) BadgedBox(badge = { Badge { Text(if (n > 9) "9+" else "$n") } }) { Icon(Icons.Rounded.Notifications, "Notifications", tint = cs.onSurface) }
                    else Icon(Icons.Rounded.Notifications, "Notifications", tint = cs.onSurface)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(start = 2.dp, end = 4.dp).size(36.dp).clip(CircleShape)
                        .background(if (signedIn) cs.primary else cs.surfaceContainerHigh).clickable(onClick = onAccount),
                ) {
                    if (signedIn && userInitial != null) Text(userInitial, color = cs.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    else Icon(Icons.Rounded.Person, "You", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh)
                    .clickable(onClick = onSearch).padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Search or type web address", fontSize = 15.sp, color = cs.onSurfaceVariant)
            }
        }
        if (HomePrefs.introPending) {
            item { GrownCard(onOpenBrowse = { HomePrefs.introDone(); onOpenBrowse() }, onDone = { HomePrefs.introDone() }) }
        }
        if (HomePrefs.shows(HomeCard.TODAY)) {
            item {
                Header("Today · ${todayText()}")
                Card {
                    val rows = todayRows(onTool)
                    if (rows.isEmpty()) {
                        Text("Nothing to show — turn rows back on in Customise Home", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(14.dp))
                    }
                    rows.forEachIndexed { i, row ->
                        if (i > 0) Divider()
                        row()
                    }
                }
            }
        }
        if (HomePrefs.shows(HomeCard.PLAYING) && player?.item != null) {
            item {
                Spacer(Modifier.height(12.dp))
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onTool(if (player.isRadio) HomeTool.RADIO else HomeTool.MUSIC) }.padding(10.dp)) {
                        com.agani.syncup.music.ArtworkTile(player.artUri, player.isRadio, Modifier.size(46.dp), radius = 12.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(player.title, fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                player.subtitle.ifBlank { if (player.isRadio) "Radio" else "Music" },
                                fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { player.toggle() }) {
                            Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play", tint = cs.onSurface)
                        }
                    }
                }
            }
        }
        if (HomePrefs.shows(HomeCard.NEARBY) && nearby.isNotEmpty()) {
            item {
                Header("Nearby")
                Card {
                    nearby.forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onTool(r.tool) }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            ToolIcon(r.icon, r.accent, r.tint, 36)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.subtitle, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (HomePrefs.shows(HomeCard.TOOLS)) {
            item {
                Header("Your tools", action = "All tools", onAction = onAllTools)
                val tools = homeTools()
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    tools.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { t ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onTool(t.tool) }.padding(vertical = 4.dp),
                                ) {
                                    ToolIcon(t.icon, t.accent, t.tint, 52)
                                    Text(t.title, fontSize = 12.sp, color = cs.onSurface, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (HomePrefs.shows(HomeCard.LINKS) && links.isNotEmpty()) {
            item {
                Header("SyncUp links", action = if (links.size > 4) "All ${links.size}" else "All", onAction = onAllLinks)
                Card {
                    links.take(4).forEachIndexed { i, l ->
                        if (i > 0) Divider()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onOpenLink(l) }.padding(horizontal = 14.dp, vertical = 11.dp)) {
                            val from = l.sourceName?.takeIf { it.isNotBlank() } ?: "SyncUp"
                            com.agani.syncup.browser.LinkIcon(l, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.title, fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (l.general) "For everyone" else from, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { customise = true }) {
                    Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Customise Home")
                }
            }
        }
    }
    if (customise) CustomiseSheet(onDismiss = { customise = false })
}

private fun todayText(): String {
    val c = Calendar.getInstance()
    val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${days[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]}"
}

@Composable
private fun Header(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 18.dp, bottom = 8.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, letterSpacing = .3.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)))
}

@Composable
private fun ToolIcon(icon: ImageVector, accent: Color, tint: Color, size: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size * .3f).dp)).background(tint),
    ) { Icon(icon, null, tint = accent, modifier = Modifier.size((size * .5f).dp)) }
}

// ---------------------------------------------------------------------------- Today
@Composable
private fun CalendarRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    @Suppress("UNUSED_VARIABLE") val v = CalendarStore.version
    val today = Days.today()
    val due = if (CalendarStore.ready) CalendarStore.tasks.filter { !it.done && it.occurrences(today, today).isNotEmpty() } else emptyList()
    val overdue = if (CalendarStore.ready) CalendarStore.tasks.count { !it.done && it.hasDate && !it.repeat.repeats && it.day < today } else 0
    val c = Calendar.getInstance()
    val nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    val next = due.filter { !it.allDay && it.time >= nowMin }.minByOrNull { it.time } ?: due.firstOrNull { it.allDay } ?: due.maxByOrNull { it.time }
    val title = next?.let { "${it.title} · ${if (it.allDay) "Today" else Days.time(it.time)}" } ?: "Nothing due today"
    val sub = buildString {
        append(
            when {
                due.size > 1 -> "and ${due.size - 1} more to-do${if (due.size == 2) "" else "s"} today"
                due.size == 1 -> "Your only to-do today"
                else -> "Tap to plan something"
            },
        )
        if (overdue > 0) append(" · $overdue overdue")
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        ToolIcon(Icons.Rounded.CalendarMonth, Color(if (light) 0xFF4F46E5 else 0xFFA5B4FC), Color(if (light) 0xFFE0E7FF else 0xFF312E81), 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.5.sp, color = if (overdue > 0) cs.error else cs.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}

@Composable
private fun ForestRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    val moss = Color(if (light) 0xFF2F5D3A else 0xFF9CCB8A)
    val calm = CalmSession.active
    var left by remember { mutableLongStateOf(0L) }
    if (calm) {
        LaunchedEffect(Unit) {
            while (true) {
                left = CalmSession.leftMs()
                delay(1_000)
            }
        }
    }
    val goal = if (ForestStore.ready) ForestStore.goalMinutes else 20
    val today = if (ForestStore.ready) ForestStore.minutesOn(com.agani.syncup.forest.FDays.today()) else 0
    val tree = if (ForestStore.ready) ForestStore.growing().maxByOrNull { it.lastCareMs } else null
    val title = when {
        calm && CalmSession.paused -> "Calm time paused"
        calm -> "Calm time · ${com.agani.syncup.forest.clock(left)} left"
        today >= goal -> "Goal met · $today calm minutes"
        else -> "$today of $goal calm minutes"
    }
    val sub = when {
        calm -> "Tap to go back to it"
        tree != null && today < goal -> "Your ${tree.stageName().lowercase()} would like ${goal - today} more"
        ForestStore.ready && ForestStore.trees.isEmpty() -> "Plant a seed with a few calm minutes"
        today < goal -> "A few calm minutes grow your forest"
        else -> "Your forest is growing"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(34.dp)) {
                val sw = 4.dp.toPx()
                val d = size.minDimension - sw
                drawArc(Color(if (light) 0xFFE4DCC7 else 0xFF323F2E), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(d, d), style = Stroke(sw))
                val p = (today / goal.toFloat()).coerceIn(0f, 1f)
                if (p > 0) drawArc(moss, -90f, 360f * p, false, Offset(sw / 2, sw / 2), Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            }
            Icon(Icons.Rounded.Eco, null, tint = moss, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1)
            Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}

/** The Today card's rows: Calendar and Forest, then each Wellbeing tool in use — minus the hidden ones. */
@Composable
private fun todayRows(onTool: (HomeTool) -> Unit): List<@Composable () -> Unit> {
    val ctx = LocalContext.current
    remember { Wellbeing.init(ctx) }
    // Redraw when a Wellbeing tool changes.
    @Suppress("UNUSED_VARIABLE") val v = Water.version + Steps.version + Habits.version + Sleep.version + Mood.version + Breaks.version
    val l = MaterialTheme.colorScheme.isLight
    fun c(light: Long, dark: Long) = Color(if (l) light else dark)
    val today = com.agani.syncup.forest.FDays.today()
    return buildList<@Composable () -> Unit> {
        if (HomePrefs.shows(TodayRow.CALENDAR)) add { CalendarRow { onTool(HomeTool.CALENDAR) } }
        if (HomePrefs.shows(TodayRow.FOREST)) add { ForestRow { onTool(HomeTool.FOREST) } }
        val ws = Water.settings
        if (ws.setUp && HomePrefs.shows(TodayRow.WATER)) {
            val total = Water.today()
            val next = Water.nextReminder()
            val cup = ws.cups[0]
            add {
                TodayLine(
                    Icons.Rounded.WaterDrop, c(0xFF0E7490, 0xFF67E8F9), c(0xFFCFFAFE, 0xFF164E63), "${Water.fmt(total)} of ${Water.fmt(ws.goal)} water",
                    when {
                        total >= ws.goal -> "Goal met — well done"
                        ws.remind && next != null && com.agani.syncup.forest.FDays.of(next) == today -> "Next reminder ${clockText(next)}"
                        else -> "${Water.fmt(ws.goal - total)} to go"
                    },
                    action = "+ ${Water.fmt(cup.ml)}" to { Water.add(cup.ml, cup.name) },
                ) { onTool(HomeTool.WATER) }
            }
        }
        if (Steps.counting(ctx) && HomePrefs.shows(TodayRow.STEPS)) {
            val st = Steps.today()
            val goal = Steps.settings.goal
            add {
                TodayLine(
                    Icons.AutoMirrored.Rounded.DirectionsWalk, c(0xFFB45309, 0xFFFCD34D), c(0xFFFEF3C7, 0xFF451A03), "${"%,d".format(st)} of ${"%,d".format(goal)} steps",
                    if (st >= goal) "Goal reached — nice walking" else "${"%,d".format(goal - st)} to go",
                ) { onTool(HomeTool.STEPS) }
            }
        }
        val habits = Habits.active()
        if (habits.isNotEmpty() && HomePrefs.shows(TodayRow.HABITS)) {
            val (done, due) = Habits.todayProgress()
            val left = habits.filter { it.dueOn(today) && it.freq != "week" && !Habits.done(it, today) && !Habits.isRest(it, today) }.map { it.name }
            add {
                TodayLine(
                    Icons.Rounded.TaskAlt, c(0xFFBE185D, 0xFFF9A8D4), c(0xFFFCE7F3, 0xFF500724),
                    if (due == 0) "No habits due today" else "$done of $due habit${if (due == 1) "" else "s"} done",
                    when {
                        due == 0 -> "Tap to see your habits"
                        left.isEmpty() -> "All done today"
                        else -> left.take(2).joinToString(" and ") + if (left.size > 2) " and ${left.size - 2} more left" else " left"
                    },
                ) { onTool(HomeTool.HABITS) }
            }
        }
        val night = Sleep.current()
        val lastNight = Sleep.lastNight()
        if ((night != null || Sleep.settings.remind || lastNight != null) && HomePrefs.shows(TodayRow.SLEEP)) {
            add {
                TodayLine(
                    Icons.Rounded.Bedtime, c(0xFF4338CA, 0xFFA5B4FC), c(0xFFE0E7FF, 0xFF312E81),
                    when {
                        night != null -> "Sleeping since ${clockText(night.start)}"
                        Sleep.settings.remind -> "Bedtime ${timeText(Sleep.settings.bedtime)}"
                        else -> "Sleep"
                    },
                    when {
                        night != null -> "Tap for Good morning"
                        lastNight != null -> "Last night ${hoursText(lastNight.hours)}"
                        else -> "Sounds and your sleep log"
                    },
                ) { onTool(HomeTool.SLEEP) }
            }
        }
        if (Breaks.settings.setUp && HomePrefs.shows(TodayRow.BREAKS)) {
            val shown = Breaks.shownToday()
            val taken = Breaks.takenToday()
            val next = Breaks.next()
            add {
                TodayLine(
                    Icons.Rounded.Visibility, c(0xFF0F766E, 0xFF5EEAD4), c(0xFFCCFBF1, 0xFF134E4A),
                    if (maxOf(shown, taken) == 0) "No breaks yet today" else "$taken of ${maxOf(shown, taken)} breaks taken",
                    next?.takeIf { com.agani.syncup.forest.FDays.of(it.first) == today }?.let { "Next ${if (it.second == "eye") "eye" else "move"} break ${clockText(it.first)}" } ?: "Done for today",
                ) { onTool(HomeTool.BREAKS) }
            }
        }
        if (Mood.entries().isNotEmpty() && HomePrefs.shows(TodayRow.MOOD)) {
            val n = Mood.countToday()
            val mood = Mood.lastToday()
            add {
                TodayLine(
                    Icons.Rounded.SentimentSatisfied, c(0xFF4D7C0F, 0xFFBEF264), c(0xFFECFCCB, 0xFF1A2E05),
                    if (mood != null) "Feeling ${listOf("bad", "low", "okay", "good", "great")[mood - 1]} · $n check-in${if (n == 1) "" else "s"}" else "How are you today?",
                    if (mood != null) "Tap to check in again" else "One tap to check in",
                ) { onTool(HomeTool.MOOD) }
            }
        }
    }
}

/** One Today row: icon, two lines, an optional small action, else a chevron. */
@Composable
private fun TodayLine(icon: ImageVector, accent: Color, tint: Color, title: String, sub: String, action: Pair<String, () -> Unit>? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        ToolIcon(icon, accent, tint, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) {
            Text(
                action.first, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(tint).clickable(onClick = action.second).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

/** Switches for the Today card's rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayRowChips() {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TodayRow.entries.forEach { r ->
            val on = HomePrefs.shows(r)
            Text(
                r.title, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) cs.primaryContainer else Color.Transparent)
                    .border(1.dp, if (on) Color.Transparent else cs.outline, RoundedCornerShape(16.dp))
                    .clickable { HomePrefs.changeRow(r, !on) }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- tools and nearby
private class ToolSpec(val tool: HomeTool, val icon: ImageVector, val title: String, val accent: Color, val tint: Color)

@Composable
private fun homeTools(): List<ToolSpec> {
    val l = MaterialTheme.colorScheme.isLight
    fun c(light: Long, dark: Long) = Color(if (l) light else dark)
    return listOf(
        ToolSpec(HomeTool.CALENDAR, Icons.Rounded.CalendarMonth, "Calendar", c(0xFF4F46E5, 0xFFA5B4FC), c(0xFFE0E7FF, 0xFF312E81)),
        ToolSpec(HomeTool.WATER, Icons.Rounded.WaterDrop, "Water", c(0xFF0E7490, 0xFF67E8F9), c(0xFFCFFAFE, 0xFF164E63)),
        ToolSpec(HomeTool.HABITS, Icons.Rounded.TaskAlt, "Habits", c(0xFFBE185D, 0xFFF9A8D4), c(0xFFFCE7F3, 0xFF500724)),
        ToolSpec(HomeTool.FOREST, Icons.Rounded.Eco, "Forest", c(0xFF4D7C0F, 0xFFBEF264), c(0xFFECFCCB, 0xFF1A2E05)),
        ToolSpec(HomeTool.SHARE, Icons.Rounded.SwapHoriz, "Share", c(0xFF1D4ED8, 0xFF93C5FD), c(0xFFDBEAFE, 0xFF1E3A8A)),
        ToolSpec(HomeTool.WALKIE, Icons.Rounded.RecordVoiceOver, "Walkie", c(0xFF0F766E, 0xFF5EEAD4), c(0xFFCCFBF1, 0xFF134E4A)),
        ToolSpec(HomeTool.MUSIC, Icons.Rounded.LibraryMusic, "Music", c(0xFF6D28D9, 0xFFC4B5FD), c(0xFFEDE9FE, 0xFF3B2A63)),
        ToolSpec(HomeTool.VIDEO, Icons.Rounded.SmartDisplay, "Videos", c(0xFFBE123C, 0xFFFDA4AF), c(0xFFFFE4E6, 0xFF4C0519)),
        ToolSpec(HomeTool.RADIO, Icons.Rounded.Radio, "Radio", c(0xFFC2410C, 0xFFFDBA74), c(0xFFFFEDD5, 0xFF431407)),
        ToolSpec(HomeTool.SENSORS, Icons.Rounded.Explore, "Sensors", c(0xFF0369A1, 0xFF7DD3FC), c(0xFFE0F2FE, 0xFF0C4A6E)),
        ToolSpec(HomeTool.SLEEP, Icons.Rounded.Bedtime, "Sleep", c(0xFF4338CA, 0xFFA5B4FC), c(0xFFE0E7FF, 0xFF312E81)),
        ToolSpec(HomeTool.MOOD, Icons.Rounded.SentimentSatisfied, "Mood", c(0xFF4D7C0F, 0xFFBEF264), c(0xFFECFCCB, 0xFF1A2E05)),
    )
}

private class NearbyRow(val tool: HomeTool, val icon: ImageVector, val title: String, val subtitle: String, val accent: Color, val tint: Color)

/** What's going on with phones nearby right now (none = no card). */
@Composable
private fun nearbyRows(): List<NearbyRow> {
    val l = MaterialTheme.colorScheme.isLight
    fun c(light: Long, dark: Long) = Color(if (l) light else dark)
    val rows = ArrayList<NearbyRow>()
    val g = com.agani.syncup.group.GroupLink
    if (g.active) {
        rows.add(
            NearbyRow(
                HomeTool.GROUP, Icons.Rounded.Groups,
                if (g.phase == com.agani.syncup.group.GroupPhase.JOINING) "Joining ${g.groupName}…" else "You're in ${g.groupName}",
                "${g.mode.label} · ${g.people.size} phone${if (g.people.size == 1) "" else "s"}",
                c(0xFF4338CA, 0xFFA5B4FC), c(0xFFE0E7FF, 0xFF312E81),
            ),
        )
    } else {
        com.agani.syncup.party.partyStatus()?.let { rows.add(NearbyRow(HomeTool.PARTY, Icons.Rounded.MusicNote, "Party", it, c(0xFFC2410C, 0xFFFDBA74), c(0xFFFFEDD5, 0xFF431407))) }
        com.agani.syncup.watchparty.watchPartyStatus()?.let { rows.add(NearbyRow(HomeTool.WATCH_PARTY, Icons.Rounded.SmartDisplay, "Watch Party", it, c(0xFFBE123C, 0xFFFDA4AF), c(0xFFFFE4E6, 0xFF4C0519))) }
        com.agani.syncup.walkie.walkieStatus()?.let { rows.add(NearbyRow(HomeTool.WALKIE, Icons.Rounded.RecordVoiceOver, "Walkie Talkie", it, c(0xFF0F766E, 0xFF5EEAD4), c(0xFFCCFBF1, 0xFF134E4A))) }
    }
    val qs = com.agani.syncup.quickshare.QuickShare
    if (qs.busy) {
        val total = qs.totalBytes
        val pct = if (total > 0) " · ${qs.doneBytes * 100 / total}%" else ""
        val what = if (qs.textMode) "text" else "files"
        rows.add(
            NearbyRow(
                HomeTool.SHARE, Icons.Rounded.SwapHoriz, "SyncUp Share",
                (if (qs.role == com.agani.syncup.quickshare.QsRole.SEND) "Sending $what" else "Receiving $what") + pct,
                c(0xFF1D4ED8, 0xFF93C5FD), c(0xFFDBEAFE, 0xFF1E3A8A),
            ),
        )
    }
    return rows
}

// ---------------------------------------------------------------------------- "SyncUp has grown"
@Composable
private fun GrownCard(onOpenBrowse: () -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val light = cs.isLight
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(if (light) listOf(Color(0xFFDCE7FF), Color(0xFFEDE9FE)) else listOf(Color(0xFF1E2A4A), Color(0xFF2E2350))))
            .padding(start = 16.dp, top = 14.dp, end = 6.dp, bottom = 10.dp),
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = if (light) Color(0xFF6D28D9) else Color(0xFFC4B5FD), modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("SyncUp has grown", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(
                "Your browser is now in Browse, with your tabs just where you left them. Calendar, Forest and the other tools live here too.",
                fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onOpenBrowse) { Text("Open Browse") }
                TextButton(onClick = onDone) { Text("Got it") }
            }
        }
        IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
    }
}

// ---------------------------------------------------------------------------- Customise Home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomiseSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Customise Home", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            HomeSettings(Modifier.padding(top = 12.dp))
        }
    }
}

/** "Open SyncUp to" and the cards' switches — in Customise Home and in Settings → Home. */
@Composable
fun HomeSettings(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text("Open SyncUp to", fontSize = 14.sp, color = cs.onSurface)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
            SegmentedButton(selected = HomePrefs.startOnHome, onClick = { HomePrefs.changeStartOnHome(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Home") }
            SegmentedButton(selected = !HomePrefs.startOnHome, onClick = { HomePrefs.changeStartOnHome(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Browse") }
        }
        Text("Cards on Home", fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        for (card in HomeCard.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { HomePrefs.changeCard(card, !HomePrefs.shows(card)) }.padding(vertical = 8.dp)) {
                Icon(cardIcon(card), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.title, fontSize = 15.sp, color = cs.onSurface)
                    Text(card.hint, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = HomePrefs.shows(card), onCheckedChange = { HomePrefs.changeCard(card, it) })
            }
        }
        if (HomePrefs.shows(HomeCard.TODAY)) {
            Text("Rows in Today", fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            TodayRowChips()
        }
        Text("A card shows only when it has something to say; a Wellbeing row once you use that tool.", fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun cardIcon(card: HomeCard): ImageVector = when (card) {
    HomeCard.TODAY -> Icons.Rounded.Today
    HomeCard.PLAYING -> Icons.Rounded.MusicNote
    HomeCard.NEARBY -> Icons.Rounded.Groups
    HomeCard.TOOLS -> Icons.Rounded.GridView
    HomeCard.LINKS -> Icons.Rounded.Link
}
