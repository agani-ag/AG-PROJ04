package com.agani.syncup.calendar

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.ui.theme.isLight

@Composable
private fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text(title, fontSize = 22.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                actions()
            }
            content()
        }
    }
}

// ============================================================================ a day, hour by hour
private val HOUR = 56.dp

@Composable
internal fun DayView(day: Int, onDay: (Int) -> Unit, onBack: () -> Unit, onOpen: (Task) -> Unit, onAdd: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val version = CalendarStore.version
    val items = remember(day, version) { CalendarStore.tasks.filter { it.occurrences(day, day).isNotEmpty() } }
    val allDay = items.filter { it.allDay }
    val timed = items.filter { !it.allDay }.sortedBy { it.time }
    val syncup = remember(day, version) { CalendarStore.syncupReminders(day, day) }
    val scroll = rememberScrollState()
    val today = Days.today()
    val hourPx = with(androidx.compose.ui.platform.LocalDensity.current) { HOUR.toPx() }
    LaunchedEffect(day) {
        // Open near now (today) or the first timed to-do (else 8 AM).
        val focusMin = if (day == today) java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) * 60 - 60 else (timed.firstOrNull()?.time ?: 8 * 60) - 30
        scroll.scrollTo((focusMin.coerceAtLeast(0) / 60f * hourPx).toInt())
    }
    Page(Days.friendly(day, today).let { if (it == Days.short(day)) it else "$it · ${Days.short(day)}" }, onBack, actions = {
        BarIcon(Icons.Rounded.ChevronLeft, "Day before", onClick = { onDay(Days.add(day, -1)) })
        BarIcon(Icons.Rounded.ChevronRight, "Day after", onClick = { onDay(Days.add(day, 1)) })
    }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (allDay.isNotEmpty() || syncup.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        allDay.forEach { t ->
                            val c = listColor(CalendarStore.list(t.listId))
                            Text(
                                (if (t.done) "✓ " else "") + t.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(c).clickable { onOpen(t) }.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                        syncup.forEach { r ->
                            Text("🔔 ${r.title}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBE123C), maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(Color(0xFFFFE4E6)).padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                }
                Box(Modifier.weight(1f).verticalScroll(scroll)) {
                    Column(Modifier.padding(start = 58.dp, end = 12.dp)) {
                        for (h in 0 until 24) {
                            Box(Modifier.fillMaxWidth().height(HOUR).clickable { onAdd(h * 60) }) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant.copy(alpha = .6f)))
                            }
                        }
                    }
                    for (h in 0 until 24) {
                        Text(
                            Days.time(h * 60).replace(":00", ""), fontSize = 11.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.offset(x = 6.dp, y = HOUR * h - 7.dp).width(48.dp),
                        )
                    }
                    BoxWithConstraints(Modifier.padding(start = 62.dp, end = 14.dp).fillMaxWidth()) {
                        val lanes = layoutLanes(timed)
                        timed.forEach { t ->
                            val (lane, count) = lanes.getValue(t.id)
                            val end = if (t.endTime > t.time) t.endTime else t.time + 45
                            val w = maxWidth / count
                            val c = listColor(CalendarStore.list(t.listId))
                            Column(
                                Modifier.offset(x = w * lane, y = HOUR * (t.time / 60f)).width(w - 4.dp).height(HOUR * ((end - t.time) / 60f) - 2.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(if (t.done) c.copy(alpha = .35f) else c.copy(alpha = .9f)).clickable { onOpen(t) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Days.time(t.time) + if (t.endTime >= 0) " – ${Days.time(t.endTime)}" else "", fontSize = 11.5.sp, color = Color.White.copy(alpha = .9f), maxLines = 1)
                            }
                        }
                    }
                    if (day == today) {
                        val now = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
                        Box(Modifier.padding(start = 54.dp).offset(y = HOUR * (now / 60f)).fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFFDC2626)))
                            Box(Modifier.offset(x = (-4).dp, y = (-4).dp).size(10.dp).clip(CircleShape).background(Color(0xFFDC2626)))
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = { onAdd(if (day == today) ((java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) + 1) * 60).coerceAtMost(23 * 60) else 9 * 60) },
                containerColor = indigo(), contentColor = if (cs.isLight) Color.White else Color(0xFF1E1B4B),
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) { Icon(Icons.Rounded.Add, "New to-do", modifier = Modifier.size(28.dp)) }
        }
    }
}

/** Side by side when times overlap: to-do id → (lane, lanes in its group). */
private fun layoutLanes(timed: List<Task>): Map<String, Pair<Int, Int>> {
    val out = HashMap<String, Pair<Int, Int>>()
    var group = ArrayList<Pair<Task, Int>>()
    var groupEnd = -1
    fun close() {
        val n = (group.maxOfOrNull { it.second } ?: 0) + 1
        group.forEach { (t, lane) -> out[t.id] = lane to n }
        group = ArrayList()
    }
    for (t in timed) {
        val end = if (t.endTime > t.time) t.endTime else t.time + 45
        if (t.time >= groupEnd) {
            close()
            groupEnd = end
        }
        val used = group.filter { (o, _) -> (if (o.endTime > o.time) o.endTime else o.time + 45) > t.time }.map { it.second }.toSet()
        val lane = (0..group.size).first { it !in used }
        group.add(t to lane)
        groupEnd = maxOf(groupEnd, end)
    }
    close()
    return out
}

// ============================================================================ lists & settings
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListsPage(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<TaskList?>(null) }
    var snoozeMenu by remember { mutableStateOf(false) }
    var weekMenu by remember { mutableStateOf(false) }
    val version = CalendarStore.version
    val counts = remember(version) { CalendarStore.tasks.filter { !it.done }.groupingBy { it.listId }.eachCount() }
    val weekAgo = System.currentTimeMillis() - 7L * 86_400_000
    Page("Lists & settings", onBack) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { Section("Lists") }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))) {
                    Column {
                        CalendarStore.lists.forEach { l ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { editing = l }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Box(Modifier.size(14.dp).clip(CircleShape).background(listColor(l)))
                                Spacer(Modifier.width(14.dp))
                                Text(l.name, fontSize = 15.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                                Text("${counts[l.id] ?: 0}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            editing = TaskList(name = "", color = TaskList.palette[CalendarStore.lists.size % TaskList.palette.size], order = CalendarStore.lists.size)
                        }.padding(horizontal = 14.dp, vertical = 14.dp)) {
                            Icon(Icons.Rounded.Add, null, tint = indigo(), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("New list", fontSize = 15.sp, color = indigo())
                        }
                    }
                }
            }
            item { Section("Done") }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))) {
                    Column {
                        SettingLine(Icons.Rounded.Check, "Done to-dos", "${CalendarStore.tasks.count { it.done && it.doneMs >= weekAgo }} this week — tap to see or undo", onClick = onDone)
                        SettingLine(Icons.Rounded.Delete, "Clear done older than a month", null, onClick = { CalendarStore.clearOldDone() })
                    }
                }
            }
            item { Section("Settings") }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))) {
                    Column {
                        SettingLine(Icons.Rounded.Notifications, "Reminders on this phone", if (CalendarStore.remindersOn) "Notifications for your to-dos" else "Off — this phone stays quiet", onClick = { CalendarStore.changeRemindersOn(!CalendarStore.remindersOn) }) {
                            Switch(checked = CalendarStore.remindersOn, onCheckedChange = { CalendarStore.changeRemindersOn(it) }, colors = SwitchDefaults.colors(checkedTrackColor = indigo()))
                        }
                        SettingLine(Icons.Rounded.MusicNote, "Reminder sound", "Android's notification settings for Calendar", onClick = {
                            CalendarReminders.ensureChannel(context)
                            val i = if (Build.VERSION.SDK_INT >= 26) {
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CalendarReminders.CHANNEL)
                            } else {
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                            }
                            runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                        Box {
                            SettingLine(Icons.Rounded.Snooze, "Snooze for", null, onClick = { snoozeMenu = true }) {
                                Text("${CalendarStore.snoozeMinutes} min", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = indigo())
                            }
                            DropdownMenu(expanded = snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                                listOf(5, 10, 15, 30, 60).forEach { m ->
                                    DropdownMenuItem(text = { Text("$m min") }, onClick = { CalendarStore.changeSnoozeMinutes(m); snoozeMenu = false })
                                }
                            }
                        }
                        Box {
                            SettingLine(Icons.Rounded.CalendarMonth, "Week starts on", null, onClick = { weekMenu = true }) {
                                Text(if (CalendarStore.weekStartsMonday) "Monday" else "Sunday", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = indigo())
                            }
                            DropdownMenu(expanded = weekMenu, onDismissRequest = { weekMenu = false }) {
                                DropdownMenuItem(text = { Text("Monday") }, onClick = { CalendarStore.changeWeekStartsMonday(true); weekMenu = false })
                                DropdownMenuItem(text = { Text("Sunday") }, onClick = { CalendarStore.changeWeekStartsMonday(false); weekMenu = false })
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Reminders are notifications on this phone — on time normally; a phone left asleep for a long while may show one a few minutes late.",
                    fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                )
            }
        }
    }

    editing?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.name) }
        var color by remember(l.id) { mutableStateOf(l.color) }
        val isNew = CalendarStore.lists.none { it.id == l.id }
        val focus = remember { FocusRequester() }
        LaunchedEffect(l.id) { if (isNew) runCatching { focus.requestFocus() } }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "New list" else "List") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TaskList.palette.forEach { c ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(c.toInt())).clickable { color = c }
                                    .then(if (c == color) Modifier.border(3.dp, cs.onSurface, CircleShape) else Modifier),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) CalendarStore.saveList(l.copy(name = name.trim(), color = color))
                    editing = null
                }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (!isNew && l.id != "personal") TextButton(onClick = { CalendarStore.deleteList(l.id); editing = null }) { Text("Delete", color = cs.error) }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text.uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, letterSpacing = .5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun SettingLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
        trailing()
    }
}

// ============================================================================ done
@Composable
internal fun DonePage(onBack: () -> Unit, onOpen: (Task) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val version = CalendarStore.version
    val done = remember(version) { CalendarStore.tasks.filter { it.done }.sortedByDescending { it.doneMs } }
    val weekAgo = System.currentTimeMillis() - 7L * 86_400_000
    Page("Done", onBack) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            if (done.isEmpty()) {
                item { Text("Nothing done yet — ticked to-dos stay here for a month, so you can undo.", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
            }
            val recent = done.filter { it.doneMs >= weekAgo }
            val older = done.filter { it.doneMs < weekAgo }
            if (recent.isNotEmpty()) item { Text("THIS WEEK", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp)) }
            items(recent, key = { it.id }) { t -> TaskRowWithPadding(t, onOpen) }
            if (older.isNotEmpty()) item { Text("EARLIER", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)) }
            items(older, key = { it.id }) { t -> TaskRowWithPadding(t, onOpen) }
        }
    }
}

@Composable
private fun TaskRowWithPadding(t: Task, onOpen: (Task) -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { TaskRow(t, onOpen, onTick = {}, showDate = true) }
}

// ============================================================================ search
@Composable
internal fun SearchPage(onBack: () -> Unit, onOpen: (Task) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val version = CalendarStore.version
    val found = remember(q, version) {
        val s = q.trim().lowercase()
        if (s.isEmpty()) emptyList() else CalendarStore.tasks.filter { it.title.lowercase().contains(s) || it.notes.lowercase().contains(s) || it.checklist.any { c -> c.text.lowercase().contains(s) } }
            .sortedWith(compareBy({ it.done }, { if (it.day == 0) Int.MAX_VALUE else it.day }))
    }
    Page("Search", onBack) {
        OutlinedTextField(
            value = q, onValueChange = { q = it.take(100) }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Search your to-dos") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
        )
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
            if (q.isNotBlank() && found.isEmpty()) item { Text("Nothing found", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
            items(found, key = { it.id }) { t -> TaskRowWithPadding(t, onOpen) }
        }
    }
}
