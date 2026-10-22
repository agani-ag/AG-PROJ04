package com.agani.syncup.calendar

import com.agani.syncup.ui.theme.PillTab
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.ui.theme.isLight
import kotlinx.coroutines.launch

/** Calendar's own accent: indigo (the Tools tile's colour). */
@Composable
internal fun indigo(): Color = if (MaterialTheme.colorScheme.isLight) Color(0xFF4338CA) else Color(0xFFA5B4FC)

@Composable
internal fun indigoContainer(): Color = if (MaterialTheme.colorScheme.isLight) Color(0xFFE0E7FF) else Color(0xFF1C1F3D)

@Composable
internal fun onIndigoContainer(): Color = if (MaterialTheme.colorScheme.isLight) Color(0xFF3730A3) else Color(0xFFC7D2FE)

/** Text and icons on [indigo] (the + button, a ticked box). */
@Composable
internal fun onIndigo(): Color = if (MaterialTheme.colorScheme.isLight) Color.White else Color(0xFF1E1B4B)

internal fun listColor(l: TaskList) = Color(l.color.toInt())

private const val PAGES = 1200
private const val CENTER = 600

/**
 * Tools → Calendar: SyncUp's own calendar and to-do list (nothing to do with Google Calendar).
 * To-dos can have a date, time, repeat, list, notes, a checklist and notification reminders.
 * Signed in, it syncs to the user's other phones (Settings → Sync → Calendar).
 */
@Composable
fun CalendarScreen(
    signedIn: Boolean,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    openTaskId: String? = null,
    onOpenTaskUsed: () -> Unit = {},
) {
    val context = LocalContext.current
    remember { CalendarStore.init(context) }
    // Pages inside Calendar: home, edit, day, lists, done, search.
    var route by rememberSaveable { mutableStateOf("home") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableIntStateOf(Days.today()) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var editDay by rememberSaveable { mutableIntStateOf(0) }
    var editTime by rememberSaveable { mutableIntStateOf(-1) }
    var backTo by rememberSaveable { mutableStateOf("home") }
    var shownDay by rememberSaveable { mutableIntStateOf(Days.today()) }

    fun edit(id: String?, day: Int = 0, time: Int = -1) {
        editId = id
        editDay = day
        editTime = time
        backTo = route
        route = "edit"
    }

    LaunchedEffect(openTaskId) {
        if (openTaskId != null) {
            if (CalendarStore.task(openTaskId) != null) edit(openTaskId)
            onOpenTaskUsed()
        }
    }
    LaunchedEffect(signedIn) { if (signedIn) CalendarSync.syncNow() }

    BackHandler(enabled = route != "home") {
        route = when (route) {
            "edit" -> backTo
            "done" -> "lists"
            else -> "home"
        }
    }

    when (route) {
        "edit" -> TaskEditor(
            taskId = editId,
            presetDay = editDay,
            presetTime = editTime,
            onClose = { route = backTo },
        )
        "day" -> DayView(
            day = shownDay,
            onDay = { shownDay = it },
            onBack = { route = "home" },
            onOpen = { edit(it.id) },
            onAdd = { time -> edit(null, shownDay, time) },
        )
        "lists" -> ListsPage(onBack = { route = "home" }, onDone = { route = "done" })
        "done" -> DonePage(onBack = { route = "lists" }, onOpen = { edit(it.id) })
        "search" -> SearchPage(onBack = { route = "home" }, onOpen = { edit(it.id) })
        else -> CalendarHome(
            signedIn = signedIn,
            tab = tab,
            onTab = { tab = it },
            selected = selected,
            onSelect = { selected = it },
            onBack = onBack,
            onSignIn = onSignIn,
            onOpen = { edit(it.id) },
            onAdd = { if (tab == 0) edit(null, selected) else edit(null) },
            onDayView = {
                shownDay = it
                route = "day"
            },
            onLists = { route = "lists" },
            onDone = { route = "done" },
            onSearch = { route = "search" },
        )
    }
}

// ============================================================================ home: Calendar | To-do
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarHome(
    signedIn: Boolean,
    tab: Int,
    onTab: (Int) -> Unit,
    selected: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpen: (Task) -> Unit,
    onAdd: () -> Unit,
    onDayView: (Int) -> Unit,
    onLists: () -> Unit,
    onDone: () -> Unit,
    onSearch: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var mergeDismissed by rememberSaveable { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("calendar", android.content.Context.MODE_PRIVATE) }
    var bannerHidden by remember { mutableStateOf(prefs.getBoolean("signed_out_banner_hidden", false)) }

    fun deleted(t: Task) {
        CalendarStore.delete(t.id)
        scope.launch {
            if (snack.showSnackbar("To-do deleted", actionLabel = "Undo", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                CalendarStore.restore(t)
            }
        }
    }

    fun ticked(t: Task) {
        CalendarStore.complete(t.id)
        val again = CalendarStore.task(t.id)
        scope.launch {
            val msg = if (again != null && !again.done) "Done — next: ${Days.friendly(again.day)}" else "Done"
            if (snack.showSnackbar(msg, actionLabel = "Undo", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                CalendarStore.save(t)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text("Calendar", fontSize = 22.sp, lineHeight = 28.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                BarIcon(Icons.Rounded.Search, "Search", onClick = onSearch)
                Box {
                    BarIcon(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = cs.surfaceContainerLowest) {
                        SyncLine(signedIn)
                        HorizontalDivider(color = cs.outlineVariant)
                        if (signedIn && CalendarSync.active) {
                            DropdownMenuItem(text = { Text("Sync now") }, leadingIcon = { Icon(Icons.Rounded.Sync, null) }, onClick = {
                                menu = false
                                CalendarSync.syncNow()
                            })
                        }
                        DropdownMenuItem(text = { Text("Lists") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.List, null) }, onClick = {
                            menu = false
                            onLists()
                        })
                        DropdownMenuItem(text = { Text("Done to-dos") }, leadingIcon = { Icon(Icons.Rounded.Done, null) }, onClick = {
                            menu = false
                            onDone()
                        })
                        DropdownMenuItem(text = { Text("Reminders & settings") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) }, onClick = {
                            menu = false
                            onLists()
                        })
                    }
                }
            }
            PillTabs(
                listOf(PillTab("Calendar", Icons.Rounded.CalendarMonth), PillTab("To-do", Icons.AutoMirrored.Rounded.List)),
                selected = tab, onSelect = onTab, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (!signedIn && !bannerHidden) {
                SignedOutBanner(onSignIn = onSignIn, onHide = {
                    bannerHidden = true
                    prefs.edit().putBoolean("signed_out_banner_hidden", true).apply()
                })
            }
            if (tab == 0) {
                MonthTab(selected, onSelect, onOpen, onDayView, ::ticked, ::deleted)
            } else {
                TodoTab(onOpen, ::ticked, ::deleted)
            }
        }
        AddButton(onAdd, Modifier.align(Alignment.BottomEnd))
        SnackbarHost(snack, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    // Signed in with to-dos made while signed out: ask once where they go.
    val waiting = CalendarStore.waitingLocal()
    if (signedIn && waiting.isNotEmpty() && !mergeDismissed) {
        MergeSheet(waiting.size, onDone = { mergeDismissed = true })
    }
}

/** The round + in Calendar's colour, bottom right. */
@Composable
internal fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = indigo(),
        contentColor = onIndigo(),
        modifier = modifier.padding(20.dp).size(58.dp),
    ) { Icon(Icons.Rounded.Add, "New to-do", modifier = Modifier.size(28.dp)) }
}

@Composable
private fun SyncLine(signedIn: Boolean) {
    val cs = MaterialTheme.colorScheme
    val (icon, title, sub) = when {
        !signedIn -> Triple(Icons.Rounded.Smartphone, "On this phone only", "Sign in to sync your to-dos")
        !CalendarSync.active -> Triple(Icons.Rounded.CloudOff, "Sync is off", "Settings → Sync → Calendar")
        CalendarSync.syncing -> Triple(Icons.Rounded.Sync, "Syncing…", "Your account")
        CalendarSync.lastError != null -> Triple(Icons.Rounded.CloudOff, CalendarSync.lastError ?: "", "Your account")
        else -> Triple(Icons.Rounded.Cloud, "Synced · ${agoText(CalendarSync.lastSyncMs)}", "Your account · every phone you sign in to")
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).width(250.dp)) {
        Icon(icon, null, tint = if (icon == Icons.Rounded.Cloud) Color(0xFF16A34A) else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, color = cs.onSurface)
            Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}

internal fun agoText(ms: Long): String {
    if (ms <= 0) return "not yet"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

@Composable
private fun SignedOutBanner(onSignIn: () -> Unit, onHide: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Icon(Icons.Rounded.Smartphone, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("On this phone only.", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text("Sign in to keep your to-dos on all your phones — and safe if this one is lost.", fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
            TextButton(onClick = onSignIn, contentPadding = PaddingValues(0.dp)) { Text("Sign in", color = indigo(), fontWeight = FontWeight.Medium) }
        }
        BarIcon(Icons.Rounded.Close, "Hide", onClick = onHide)
    }
}

// ============================================================================ the month
@Composable
private fun MonthTab(selected: Int, onSelect: (Int) -> Unit, onOpen: (Task) -> Unit, onDayView: (Int) -> Unit, onTick: (Task) -> Unit, onDelete: (Task) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val today = Days.today()
    val thisMonth = Days.firstOfMonth(today)
    val pager = rememberPagerState(initialPage = CENTER + monthsBetween(thisMonth, Days.firstOfMonth(selected))) { PAGES }
    val scope = rememberCoroutineScope()
    val version = CalendarStore.version
    val dayTasks = remember(selected, version) {
        CalendarStore.tasks.filter { it.occurrences(selected, selected).isNotEmpty() }
            .sortedWith(compareBy({ it.done }, { if (it.allDay) -1 else it.time }, { it.title.lowercase() }))
    }
    val syncup = remember(selected, version) { CalendarStore.syncupReminders(selected, selected).sortedBy { it.time } }

    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 110.dp)) {
        item(key = "month") {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().appCard().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
                val month = Days.addMonths(thisMonth, pager.currentPage - CENTER)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                    Text("${Days.monthNames[month / 100 % 100 - 1]} ${month / 10000}", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
                    if (month != thisMonth) {
                        TextButton(onClick = {
                            onSelect(today)
                            scope.launch { pager.animateScrollToPage(CENTER) }
                        }) { Text("Today", color = indigo(), fontWeight = FontWeight.Medium) }
                    }
                    BarIcon(Icons.Rounded.ChevronLeft, "Last month", onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } })
                    BarIcon(Icons.Rounded.ChevronRight, "Next month", onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } })
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                    MonthGrid(Days.addMonths(thisMonth, page - CENTER), selected, onSelect, version)
                }
            }
        }
        item(key = "dayhead") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)) {
                Text(if (selected == today) "Today · ${Days.short(selected)}" else Days.short(selected), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Spacer(Modifier.width(8.dp))
                val left = dayTasks.count { !it.done }
                val done = dayTasks.count { it.done }
                Text(
                    when {
                        dayTasks.isEmpty() -> "Nothing planned"
                        done > 0 -> "$left to do · $done done"
                        else -> "$left to do"
                    },
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDayView(selected) }) { Text("Hours", color = indigo(), fontWeight = FontWeight.Medium) }
            }
        }
        itemsIndexed(dayTasks, key = { _, t -> "t" + t.id }) { i, t ->
            SwipeTaskRow(t, onOpen, onTick, onDelete, day = selected, first = i == 0, last = i == dayTasks.lastIndex)
        }
        if (dayTasks.isNotEmpty() && syncup.isNotEmpty()) item(key = "gap") { Spacer(Modifier.height(10.dp)) }
        itemsIndexed(syncup, key = { _, r -> "r" + r.id + r.day + r.time }) { i, r -> SyncUpReminderRow(r, first = i == 0, last = i == syncup.lastIndex) }
    }
}

private fun monthsBetween(a: Int, b: Int): Int = (b / 10000 - a / 10000) * 12 + (b / 100 % 100 - a / 100 % 100)

@Composable
private fun MonthGrid(month: Int, selected: Int, onSelect: (Int) -> Unit, version: Long) {
    val cs = MaterialTheme.colorScheme
    val today = Days.today()
    val mondayFirst = CalendarStore.weekStartsMonday
    val lead = if (mondayFirst) Days.weekday(month) - 1 else Days.weekday(month) % 7
    val gridStart = Days.add(month, -lead)
    val gridEnd = Days.add(gridStart, 41)
    val dots = remember(month, version) {
        val map = HashMap<Int, MutableList<Color>>()
        for (t in CalendarStore.tasks) {
            if (t.done) continue
            val c = listColor(CalendarStore.list(t.listId))
            for (d in t.occurrences(gridStart, gridEnd, 60)) {
                val l = map.getOrPut(d) { mutableListOf() }
                if (c !in l && l.size < 3) l.add(c)
            }
        }
        for (r in CalendarStore.syncupReminders(gridStart, gridEnd)) {
            val l = map.getOrPut(r.day) { mutableListOf() }
            if (l.size < 3 && Color(0xFFE11D48) !in l) l.add(Color(0xFFE11D48))
        }
        map
    }
    val names = if (mondayFirst) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("S", "M", "T", "W", "T", "F", "S")
    val accent = indigo()
    val onAccent = onIndigo()
    Column {
        Row(Modifier.fillMaxWidth()) {
            names.forEach { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f).padding(top = 2.dp, bottom = 6.dp), textAlign = TextAlign.Center) }
        }
        for (w in 0 until 6) {
            val rowStart = Days.add(gridStart, w * 7)
            if (w == 5 && rowStart / 100 != month / 100) break
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = Days.add(rowStart, i)
                    val inMonth = d / 100 == month / 100
                    val isToday = d == today
                    Column(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).clickable { onSelect(d) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
                                .background(if (isToday) accent else Color.Transparent)
                                .then(if (d == selected && !isToday) Modifier.border(2.dp, accent, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${d % 100}", fontSize = 14.sp,
                                fontWeight = if (isToday || d == selected) FontWeight.Medium else FontWeight.Normal,
                                color = when {
                                    isToday -> onAccent
                                    !inMonth -> cs.onSurfaceVariant.copy(alpha = .5f)
                                    else -> cs.onSurface
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 3.dp).height(5.dp)) {
                            dots[d].orEmpty().forEach { c -> Box(Modifier.size(5.dp).clip(CircleShape).background(if (inMonth) c else c.copy(alpha = .4f))) }
                        }
                    }
                }
            }
        }
    }
}

/** A reminder sent by SyncUp on the chosen day — read-only, on a soft grey card (no shadow). */
@Composable
private fun SyncUpReminderRow(r: CalendarStore.SyncUpReminder, first: Boolean, last: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 60.dp).clip(segmentShape(first, last))
            .background(cs.surfaceContainer).dividerAbove(!first, cs.outlineVariant).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Rounded.Notifications, null, tint = Color(0xFFE11D48), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Days.time(r.time)} · From SyncUp", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
    }
}

// ============================================================================ To-do
@Composable
private fun TodoTab(onOpen: (Task) -> Unit, onTick: (Task) -> Unit, onDelete: (Task) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val version = CalendarStore.version
    val today = Days.today()
    val tomorrow = Days.add(today, 1)
    val open = remember(version, filter) {
        CalendarStore.tasks.filter { !it.done && (filter == null || it.listId == filter) }
            .sortedWith(compareBy({ if (it.day == 0) Int.MAX_VALUE else it.day }, { if (it.allDay) -1 else it.time }, { it.title.lowercase() }))
    }
    val groups = listOf(
        Triple("Overdue", true, open.filter { it.day in 1 until today }),
        Triple("Today", false, open.filter { it.day == today }),
        Triple("Tomorrow", false, open.filter { it.day == tomorrow }),
        Triple("Later", false, open.filter { it.day > tomorrow }),
        Triple("No date", false, open.filter { it.day == 0 }),
    )
    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 110.dp)) {
        item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipLike("All ${CalendarStore.tasks.count { !it.done }}", null, filter == null) { filter = null }
                CalendarStore.lists.forEach { l -> FilterChipLike(l.name, listColor(l), filter == l.id) { filter = if (filter == l.id) null else l.id } }
            }
        }
        if (open.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(indigoContainer()), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = indigo(), modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Nothing to do", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text("Tap + to add a to-do.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        groups.forEach { (name, red, list) ->
            if (list.isNotEmpty()) {
                groupHeader(name, "$name · ${list.size}", red)
                itemsIndexed(list, key = { _, t -> t.id }) { i, t -> SwipeTaskRow(t, onOpen, onTick, onDelete, first = i == 0, last = i == list.lastIndex) }
            }
        }
    }
}

private fun LazyListScope.groupHeader(key: String, text: String, red: Boolean) {
    item(key = "h$key") {
        Text(
            text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
            color = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
        )
    }
}

/** A v7 chip: the picked one in Calendar's soft indigo, the rest white with a thin line. */
@Composable
internal fun FilterChipLike(label: String, dot: Color?, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(17.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(34.dp).clip(shape).background(if (on) indigoContainer() else cs.surfaceContainerLowest)
            .then(if (on) Modifier else Modifier.border(1.dp, cs.outlineVariant, shape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) onIndigoContainer() else cs.onSurface)
    }
}

// ============================================================================ grouped cards in long lists
/** A row's corners in its grouped card: round on top for the first, at the bottom for the last. */
internal fun segmentShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp,
    bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp,
)

/** A thin line across the top of a row (between rows of one card). */
internal fun Modifier.dividerAbove(show: Boolean, color: Color): Modifier = if (!show) this else drawWithContent {
    drawContent()
    val w = 1.dp.toPx()
    drawLine(color, Offset(0f, w / 2), Offset(size.width, w / 2), w)
}

/**
 * One row's piece of a grouped card (the To-do groups, Calendar's day): white, the card's corners,
 * a thin line round the card and between its rows. Each row draws its own piece, so the list stays
 * lazy — and no row has a shadow of its own, so long lists scroll smoothly.
 */
@Composable
internal fun Modifier.cardSegment(first: Boolean, last: Boolean): Modifier {
    val cs = MaterialTheme.colorScheme
    val line = cs.outlineVariant
    return this.clip(segmentShape(first, last)).background(cs.surfaceContainerLowest).drawWithContent {
        drawContent()
        val w = 1.dp.toPx()
        val r = 24.dp.toPx()
        // The card's edge: its sides on every row, its top / bottom only on the first / last.
        val top = if (first) w / 2 else -2 * r
        val bottom = if (last) size.height - w / 2 else size.height + 2 * r
        drawRoundRect(line, Offset(w / 2, top), Size(size.width - w, bottom - top), CornerRadius(r - w / 2), style = Stroke(w))
        if (!first) drawLine(line, Offset(0f, w / 2), Offset(size.width, w / 2), w)
    }
}

// ============================================================================ a to-do row
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeTaskRow(
    t: Task, onOpen: (Task) -> Unit, onTick: (Task) -> Unit, onDelete: (Task) -> Unit,
    day: Int = t.day, first: Boolean = true, last: Boolean = true,
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> {
                if (!t.done) onTick(t)
                false
            }
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete(t)
                true
            }
            else -> false
        }
    })
    val red = MaterialTheme.colorScheme.error
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.padding(horizontal = 16.dp),
        backgroundContent = {
            // Only while swiping — at rest it would show as a coloured edge round the card's corners.
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                    modifier = Modifier.fillMaxSize().clip(segmentShape(first, last)).background(if (toEnd) Color(0xFF16A34A) else red).padding(horizontal = 20.dp),
                ) {
                    Icon(if (toEnd) Icons.Rounded.Check else Icons.Rounded.Delete, null, tint = Color.White)
                }
            }
        },
    ) {
        TaskRow(t, onOpen, onTick, day, first, last)
    }
}

/**
 * A to-do in a list: its list's colour bar, the tick box, the title, and a line saying when — date
 * and time together, for [day] (one of its days; red when it's past) — then repeat, reminder, steps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskRow(t: Task, onOpen: (Task) -> Unit, onTick: (Task) -> Unit, day: Int = t.day, first: Boolean = true, last: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val today = Days.today()
    val list = CalendarStore.list(t.listId)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).cardSegment(first, last).clickable { onOpen(t) }.padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(listColor(list)))
        Box(
            Modifier.padding(horizontal = 12.dp).size(24.dp).clip(RoundedCornerShape(8.dp))
                .background(if (t.done) indigo() else Color.Transparent)
                .then(if (t.done) Modifier else Modifier.border(2.dp, cs.onSurfaceVariant, RoundedCornerShape(8.dp)))
                .clickable { if (!t.done) onTick(t) else CalendarStore.uncomplete(t.id) },
            contentAlignment = Alignment.Center,
        ) {
            if (t.done) Icon(Icons.Rounded.Check, "Done", tint = onIndigo(), modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                t.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (t.done) cs.onSurfaceVariant else cs.onSurface, textDecoration = if (t.done) TextDecoration.LineThrough else null,
            )
            val overdue = t.hasDate && day < today && !t.done
            val bits = buildList<Triple<ImageVector?, String, Boolean>> {
                if (t.hasDate) add(Triple(if (t.allDay) Icons.Rounded.CalendarMonth else Icons.Rounded.Schedule, t.whenText(day, today), overdue))
                if (t.repeat.repeats) add(Triple(Icons.Rounded.Repeat, t.repeat.short, false))
                if (t.hasDate && t.reminders.isNotEmpty()) add(Triple(Icons.Rounded.Notifications, Reminders.short(t, t.reminders.min()), false))
                if (t.checklist.isNotEmpty()) add(Triple(null, "${t.checklist.count { it.done }} of ${t.checklist.size}", false))
            }
            if (bits.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 3.dp)) {
                    bits.forEach { (icon, text, red) ->
                        val color = if (red) cs.error else cs.onSurfaceVariant
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) {
                                Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                            }
                            Text(text, fontSize = 12.sp, color = color, fontWeight = if (red) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================ the Tools tile's line
/** "3 to do today" — or nothing when the day is clear. */
@Composable
fun calendarStatus(): String? {
    if (!CalendarStore.ready) return null
    @Suppress("UNUSED_VARIABLE") val v = CalendarStore.version
    val today = Days.today()
    val n = CalendarStore.tasks.count { !it.done && it.occurrences(today, today).isNotEmpty() }
    return when (n) {
        0 -> null
        1 -> "1 to do today"
        else -> "$n to do today"
    }
}

// ============================================================================ signed in with signed-out to-dos
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(count: Int, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var intoAccount by remember { mutableStateOf(true) }
    val inAccount = CalendarStore.tasks.count { !it.local && !it.phoneOnly }
    ModalBottomSheet(onDismissRequest = onDone, containerColor = cs.surfaceContainerLowest) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Welcome back", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(
                "This phone has $count to-do${if (count == 1) "" else "s"} made while you were signed out." + if (inAccount > 0) " Your account has $inAccount." else "",
                fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurface, modifier = Modifier.padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { intoAccount = true }.padding(vertical = 6.dp)) {
                RadioButton(selected = intoAccount, onClick = { intoAccount = true })
                Column {
                    Text("Put them in my account", fontSize = 15.sp, color = cs.onSurface)
                    Text("On every phone you sign in to", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { intoAccount = false }.padding(vertical = 6.dp)) {
                RadioButton(selected = !intoAccount, onClick = { intoAccount = false })
                Column {
                    Text("Keep them only on this phone", fontSize = 15.sp, color = cs.onSurface)
                    Text("Your account stays as it is", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
            }
            Button(onClick = {
                if (intoAccount) CalendarStore.moveLocalIntoAccount() else CalendarStore.keepLocalOnPhone()
                onDone()
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp)) { Text("Continue", fontSize = 15.sp) }
        }
    }
}
