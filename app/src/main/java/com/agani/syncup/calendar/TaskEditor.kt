package com.agani.syncup.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.ui.theme.isLight
import java.util.TimeZone
import java.util.UUID

private val TIMED_CHOICES = listOf(0, 5, 10, 15, 30, 60, 120, 24 * 60, 48 * 60)
private val ALL_DAY_CHOICES = listOf(0, 24 * 60, 48 * 60, 7 * 24 * 60)

/** Add or change a to-do. Only the title is needed. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TaskEditor(taskId: String?, presetDay: Int, presetTime: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val original = remember(taskId) { taskId?.let { CalendarStore.task(it) } }
    val start = remember(taskId) {
        original ?: Task(day = presetDay, time = presetTime, reminders = if (presetDay > 0) listOf(if (presetTime >= 0) 10 else 0) else emptyList())
    }
    var t by remember(taskId) { mutableStateOf(start) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf<String?>(null) } // "start" / "end"
    var repeatSheet by remember { mutableStateOf(false) }
    var customRepeat by remember { mutableStateOf(false) }
    var listSheet by remember { mutableStateOf(false) }
    var addReminder by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // A step just added gets the cursor.
    var focusStep by remember { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun changed() = t != start
    fun leave() = if (changed() && t.title.isNotBlank()) confirmLeave = true else onClose()
    fun save() {
        if (t.title.isBlank()) return
        val clean = t.copy(
            title = t.title.trim(),
            checklist = t.checklist.filter { it.text.isNotBlank() },
            reminders = if (t.hasDate) t.reminders.distinct().sorted() else emptyList(),
            repeat = if (t.hasDate) t.repeat else Repeat(),
            endTime = if (t.allDay) -1 else t.endTime,
        )
        CalendarStore.save(clean)
        if (clean.reminders.isNotEmpty() && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onClose()
    }
    BackHandler { leave() }
    LaunchedEffect(Unit) { if (original == null) runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.Rounded.Close, "Close", onClick = ::leave)
                Text(if (original == null) "New to-do" else "To-do", fontSize = 22.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                TextButton(onClick = ::save, enabled = t.title.isNotBlank()) { Text("Save", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (t.title.isNotBlank()) indigo() else cs.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = t.title, onValueChange = { t = t.copy(title = it.take(300)) },
                    placeholder = { Text("What to do?") },
                    textStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                Card {
                    EditRow(Icons.Rounded.CalendarMonth, if (t.hasDate) Days.short(t.day) else "No date", onClick = { datePicker = true },
                        trailing = {
                            if (t.hasDate) {
                                if (!t.allDay) Text(Days.time(t.time), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = indigo(), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { timePicker = "start" }.padding(6.dp))
                                BarIcon(Icons.Rounded.Close, "No date", onClick = { t = t.copy(day = 0, repeat = Repeat(), reminders = emptyList()) })
                            }
                        })
                    if (t.hasDate) {
                        EditRow(Icons.Rounded.WbSunny, "All day", onClick = { t = if (t.allDay) t.copy(time = 9 * 60) else t.copy(time = -1, endTime = -1) }) {
                            Switch(checked = t.allDay, onCheckedChange = { on -> t = if (on) t.copy(time = -1, endTime = -1) else t.copy(time = 9 * 60) }, colors = SwitchDefaults.colors(checkedTrackColor = indigo()))
                        }
                        if (!t.allDay) {
                            EditRow(Icons.Rounded.Schedule, if (t.endTime >= 0) "Ends ${Days.time(t.endTime)}" else "Add end time", onClick = { timePicker = "end" }) {
                                if (t.endTime >= 0) BarIcon(Icons.Rounded.Close, "No end time", onClick = { t = t.copy(endTime = -1) })
                            }
                        }
                        EditRow(Icons.Rounded.Repeat, "Repeat", onClick = { repeatSheet = true }) {
                            Text(t.repeat.label(t.day), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = indigo(), maxLines = 2, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                    EditRow(Icons.AutoMirrored.Rounded.Label, "List", onClick = { listSheet = true }) {
                        val l = CalendarStore.list(t.listId)
                        Box(Modifier.size(10.dp).clip(CircleShape).background(listColor(l)))
                        Spacer(Modifier.width(6.dp))
                        Text(l.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = indigo(), modifier = Modifier.padding(end = 8.dp))
                    }
                }
                // Reminders
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)) {
                    Icon(Icons.Rounded.Notifications, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remind me", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                }
                if (!t.hasDate) {
                    Text("Pick a date to add reminders.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.reminders.sorted().forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(indigoContainer()).clickable { t = t.copy(reminders = t.reminders - m) }.padding(start = 12.dp, end = 8.dp),
                            ) {
                                Text(reminderLabel(m, t), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = onIndigoContainer())
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Close, "Remove", tint = onIndigoContainer(), modifier = Modifier.size(15.dp))
                            }
                        }
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).border(1.dp, cs.outline, RoundedCornerShape(17.dp)).clickable { addReminder = true }.padding(horizontal = 12.dp),
                            ) {
                                Icon(Icons.Rounded.Add, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                            }
                            DropdownMenu(expanded = addReminder, onDismissRequest = { addReminder = false }) {
                                (if (t.allDay) ALL_DAY_CHOICES else TIMED_CHOICES).filter { it !in t.reminders }.forEach { m ->
                                    DropdownMenuItem(text = { Text(reminderLabel(m, t)) }, onClick = {
                                        t = t.copy(reminders = t.reminders + m)
                                        addReminder = false
                                    })
                                }
                            }
                        }
                    }
                }
                // Notes and steps
                Spacer(Modifier.height(18.dp))
                Card {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) {
                            if (t.notes.isEmpty()) Text("Add notes", fontSize = 15.sp, color = cs.onSurfaceVariant)
                            BasicTextField(
                                value = t.notes, onValueChange = { t = t.copy(notes = it.take(5000)) },
                                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface), cursorBrush = SolidColor(indigo()),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
                            )
                        }
                    }
                    t.checklist.forEach { step ->
                        val stepFocus = remember(step.id) { FocusRequester() }
                        LaunchedEffect(focusStep) {
                            if (focusStep == step.id) {
                                runCatching { stepFocus.requestFocus() }
                                focusStep = null
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 50.dp, end = 4.dp)) {
                            Box(
                                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (step.done) indigo() else Color.Transparent)
                                    .border(2.dp, if (step.done) indigo() else cs.outline, RoundedCornerShape(6.dp))
                                    .clickable { t = t.copy(checklist = t.checklist.map { if (it.id == step.id) it.copy(done = !it.done) else it }) },
                                contentAlignment = Alignment.Center,
                            ) { if (step.done) Icon(Icons.Rounded.Check, null, tint = if (cs.isLight) Color.White else Color(0xFF1E1B4B), modifier = Modifier.size(14.dp)) }
                            Spacer(Modifier.width(10.dp))
                            BasicTextField(
                                value = step.text, onValueChange = { v -> t = t.copy(checklist = t.checklist.map { if (it.id == step.id) it.copy(text = v.take(200)) else it }) },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 14.5.sp, color = if (step.done) cs.onSurfaceVariant else cs.onSurface, textDecoration = if (step.done) TextDecoration.LineThrough else null),
                                cursorBrush = SolidColor(indigo()),
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp).focusRequester(stepFocus),
                            )
                            BarIcon(Icons.Rounded.Close, "Remove step", onClick = { t = t.copy(checklist = t.checklist.filterNot { it.id == step.id }) })
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val step = CheckItem(UUID.randomUUID().toString(), "")
                            t = t.copy(checklist = t.checklist + step)
                            focusStep = step.id
                        }.padding(start = 50.dp, top = 10.dp, bottom = 12.dp),
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = indigo(), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add a step", fontSize = 14.5.sp, color = indigo())
                    }
                }
                if (original != null) {
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Delete, null, tint = cs.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete", color = cs.error)
                        }
                        OutlinedButton(onClick = {
                            if (original.done) CalendarStore.uncomplete(original.id) else {
                                if (changed()) CalendarStore.save(t)
                                CalendarStore.complete(original.id)
                            }
                            onClose()
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (original.done) "Not done" else "Mark done")
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (datePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = utcMs(if (t.hasDate) t.day else Days.today()))
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = fromUtc(ms)
                        t = if (!t.hasDate) t.copy(day = d, reminders = if (t.reminders.isEmpty()) listOf(if (t.allDay) 0 else 10) else t.reminders) else t.copy(day = d)
                    }
                    datePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    timePicker?.let { which ->
        val initial = if (which == "end") (if (t.endTime >= 0) t.endTime else (t.time.coerceAtLeast(0) + 60).coerceAtMost(23 * 60 + 59)) else t.time.coerceAtLeast(9 * 60)
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { timePicker = null },
            title = { Text(if (which == "end") "Ends at" else "Time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    t = if (which == "end") t.copy(endTime = m) else t.copy(time = m, endTime = if (t.endTime in 0..m) -1 else t.endTime)
                    timePicker = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { timePicker = null }) { Text("Cancel") } },
        )
    }

    if (repeatSheet) {
        ModalBottomSheet(onDismissRequest = { repeatSheet = false }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
                Text("Repeat", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                listOf("none", "daily", "weekdays", "weekly", "monthly", "yearly").forEach { k ->
                    val r = Repeat(kind = k)
                    RadioRow(r.label(t.day), t.repeat.kind == k) {
                        t = t.copy(repeat = r)
                        repeatSheet = false
                    }
                }
                RadioRow(if (t.repeat.kind == "custom") t.repeat.label(t.day) else "Custom…", t.repeat.kind == "custom", sub = "Every few days or weeks, on chosen days, until a date…") {
                    repeatSheet = false
                    customRepeat = true
                }
            }
        }
    }

    if (customRepeat) CustomRepeatDialog(t.repeat.takeIf { it.kind == "custom" } ?: Repeat(kind = "custom", unit = "week", weekdays = 1 shl (Days.weekday(t.day.coerceAtLeast(Days.today())) - 1)), t.day,
        onDone = {
            t = t.copy(repeat = it)
            customRepeat = false
        },
        onCancel = { customRepeat = false })

    if (listSheet) {
        ModalBottomSheet(onDismissRequest = { listSheet = false }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
                Text("List", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                CalendarStore.lists.forEach { l ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            t = t.copy(listId = l.id)
                            listSheet = false
                        }.padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(listColor(l)))
                        Spacer(Modifier.width(14.dp))
                        Text(l.name, fontSize = 15.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                        if (l.id == t.listId) Icon(Icons.Rounded.Check, null, tint = indigo())
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save changes?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Discard") } },
        )
    }
    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this to-do?") },
            text = { Text(if (original.repeat.repeats) "It won't repeat any more." else "It goes from every phone you're signed in to.") },
            confirmButton = { TextButton(onClick = { CalendarStore.delete(original.id); confirmDelete = false; onClose() }) { Text("Delete", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** "At 10:00", "10 min before", "1 day before (9:00)". */
internal fun reminderLabel(m: Int, t: Task): String = when {
    t.allDay && m == 0 -> "On the day, 9:00"
    t.allDay -> reminderShort(m, true) + ", 9:00"
    m == 0 -> "At ${Days.time(t.time.coerceAtLeast(0))}"
    else -> reminderShort(m, false)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))) { content() }
}

@Composable
private fun EditRow(icon: ImageVector, title: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(start = 14.dp, end = 6.dp)) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 15.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun RadioRow(title: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomRepeatDialog(initial: Repeat, day: Int, onDone: (Repeat) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var r by remember { mutableStateOf(initial.copy(kind = "custom")) }
    var endPicker by remember { mutableStateOf(false) }
    var countText by remember { mutableStateOf(if (r.endCount > 0) r.endCount.toString() else "10") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Custom repeat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", fontSize = 15.sp, color = cs.onSurface)
                    Spacer(Modifier.width(10.dp))
                    BarIcon(Icons.Rounded.Remove, "Less", enabled = r.every > 1, onClick = { r = r.copy(every = r.every - 1) })
                    Text("${r.every}", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(horizontal = 6.dp))
                    BarIcon(Icons.Rounded.Add, "More", enabled = r.every < 99, onClick = { r = r.copy(every = r.every + 1) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf("day" to "Days", "week" to "Weeks", "month" to "Months").forEach { (u, label) ->
                        FilterChipLike(label, null, r.unit == u) { r = r.copy(unit = u) }
                    }
                }
                if (r.unit == "week") {
                    Text("On", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Days.shortWeekdays.forEachIndexed { i, name ->
                            val on = (r.weekdays shr i) and 1 == 1
                            FilterChipLike(name.take(2), null, on) { r = r.copy(weekdays = r.weekdays xor (1 shl i)) }
                        }
                    }
                }
                Text("Ends", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                RadioRow("Never", r.endKind == "never") { r = r.copy(endKind = "never") }
                RadioRow(if (r.endKind == "date" && r.endDay > 0) "On ${Days.short(r.endDay)}" else "On a date…", r.endKind == "date") {
                    r = r.copy(endKind = "date")
                    endPicker = true
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = r.endKind == "count", onClick = { r = r.copy(endKind = "count", endCount = countText.toIntOrNull() ?: 10) })
                    Text("After", fontSize = 15.sp, color = cs.onSurface)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = countText, onValueChange = { v -> countText = v.filter { it.isDigit() }.take(3); r = r.copy(endKind = "count", endCount = countText.toIntOrNull() ?: 0) },
                        singleLine = true, modifier = Modifier.width(76.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("times", fontSize = 15.sp, color = cs.onSurface)
                }
                Text(r.label(day), fontSize = 13.sp, color = indigo(), modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onDone(if (r.unit == "week" && r.weekdays == 0) r.copy(weekdays = 1 shl (Days.weekday(day.coerceAtLeast(Days.today())) - 1)) else r) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
    if (endPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = utcMs(if (r.endDay > 0) r.endDay else Days.addMonths(day.coerceAtLeast(Days.today()), 1)))
        DatePickerDialog(
            onDismissRequest = { endPicker = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { r = r.copy(endKind = "date", endDay = fromUtc(it)) }; endPicker = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { endPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** The date picker works in UTC midnights. */
private fun utcMs(day: Int): Long = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(day / 10000, day / 100 % 100 - 1, day % 100)
}.timeInMillis

private fun fromUtc(ms: Long): Int = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }.let {
    it.get(java.util.Calendar.YEAR) * 10000 + (it.get(java.util.Calendar.MONTH) + 1) * 100 + it.get(java.util.Calendar.DAY_OF_MONTH)
}
