package com.agani.syncup.calendar

import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerColors
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.agani.syncup.browser.ui.BarIcon
import java.util.TimeZone
import java.util.UUID

/** The quick times under the Date & time sheet's clock. */
private val QUICK_TIMES = listOf(9 * 60, 12 * 60, 18 * 60, 21 * 60)

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
    var whenSheet by remember { mutableStateOf(false) }
    var endPicker by remember { mutableStateOf(false) }
    var repeatSheet by remember { mutableStateOf(false) }
    var customRepeat by remember { mutableStateOf(false) }
    var listSheet by remember { mutableStateOf(false) }
    var addReminder by remember { mutableStateOf(false) }
    var customReminder by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A sheet or menu takes the focus off the title, so the keyboard doesn't come back when it closes.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(whenSheet, endPicker, repeatSheet, customRepeat, listSheet, addReminder, customReminder) {
        if (whenSheet || endPicker || repeatSheet || customRepeat || listSheet || addReminder || customReminder) focusManager.clearFocus()
    }
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
            reminders = if (t.hasDate) t.reminders.filter { Reminders.allowed(t, it) }.distinct().sorted() else emptyList(),
            repeat = if (t.hasDate) t.repeat else Repeat(),
            countdown = t.hasDate && t.countdown,
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

    /** From the Date & time sheet: [time] -1 = all day. */
    fun setWhen(day: Int, time: Int) {
        val allDay = time < 0
        val reminders = when {
            // The first date: the usual reminder (10 min before, or 9:00 on the day).
            !t.hasDate && t.reminders.isEmpty() -> listOf(if (allDay) 0 else 10)
            // A time → all day: the same day / days before, at 9:00.
            allDay && !t.allDay -> t.reminders.map { if (it < 24 * 60) 0 else it / (24 * 60) * (24 * 60) }.distinct()
            // All day → a time: ones later on the day than the start become "at the time".
            !allDay && t.allDay -> t.reminders.map { it.coerceAtLeast(0) }.distinct()
            else -> t.reminders
        }
        t = t.copy(day = day, time = time, endTime = if (allDay || t.endTime in 0..time) -1 else t.endTime, reminders = reminders)
    }

    BackHandler { leave() }
    LaunchedEffect(Unit) { if (original == null) runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.Rounded.Close, "Close", onClick = ::leave)
                Text(if (original == null) "New to-do" else "To-do", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
                TextButton(onClick = ::save, enabled = t.title.isNotBlank()) {
                    Text("Save", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (t.title.isNotBlank()) indigo() else cs.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                // The title: big, underlined in Calendar's colour — no box round it.
                BasicTextField(
                    value = t.title, onValueChange = { t = t.copy(title = it.take(300)) },
                    textStyle = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
                    cursorBrush = SolidColor(indigo()),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { field ->
                        Box(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
                            if (t.title.isEmpty()) Text("What to do?", fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                            field()
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(indigo()))
                Spacer(Modifier.height(14.dp))
                EditCard {
                    EditRow(
                        Icons.Rounded.CalendarMonth, if (t.hasDate) t.whenText() else "No date", sub = "Date & time", top = false,
                        dim = !t.hasDate, onClick = { whenSheet = true },
                    ) {
                        if (t.hasDate) BarIcon(Icons.Rounded.Close, "No date", tint = cs.onSurfaceVariant, onClick = { t = t.copy(day = 0, repeat = Repeat(), reminders = emptyList()) })
                    }
                    if (t.hasDate && !t.allDay) {
                        EditRow(Icons.Rounded.Schedule, if (t.endTime >= 0) "Ends ${Days.time(t.endTime)}" else "Add end time", onClick = { endPicker = true }) {
                            if (t.endTime >= 0) BarIcon(Icons.Rounded.Close, "No end time", tint = cs.onSurfaceVariant, onClick = { t = t.copy(endTime = -1) })
                        }
                    }
                    if (t.hasDate) {
                        EditRow(Icons.Rounded.Repeat, "Repeat", onClick = { repeatSheet = true }) {
                            Text(t.repeat.label(t.day), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = indigo(), maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 200.dp).padding(end = 10.dp))
                        }
                    }
                    EditRow(Icons.AutoMirrored.Rounded.Label, "List", onClick = { listSheet = true }) {
                        val l = CalendarStore.list(t.listId)
                        Box(Modifier.size(10.dp).clip(CircleShape).background(listColor(l)))
                        Spacer(Modifier.width(6.dp))
                        Text(l.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = indigo(), modifier = Modifier.padding(end = 10.dp))
                    }
                    // Count down: only with a date (it counts to it).
                    EditRow(
                        Icons.Rounded.HourglassBottom, "Count down", sub = if (t.hasDate) "Days left · on the day, hours & minutes" else "Needs a date",
                        dim = !t.hasDate, enabled = t.hasDate, onClick = { t = t.copy(countdown = !t.countdown) },
                    ) {
                        Switch(
                            checked = t.hasDate && t.countdown, onCheckedChange = { t = t.copy(countdown = it) }, enabled = t.hasDate,
                            colors = SwitchDefaults.colors(checkedTrackColor = indigo()), modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                }
                // Reminders
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp)) {
                    Icon(Icons.Rounded.Notifications, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remind me", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                }
                if (!t.hasDate) {
                    Text("Pick a date to add reminders.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.reminders.sorted().forEach { m ->
                            val custom = Reminders.isCustom(t, m)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(indigoContainer())
                                    .clickable { t = t.copy(reminders = t.reminders - m) }.padding(start = if (custom) 11.dp else 14.dp, end = 10.dp),
                            ) {
                                // A custom one shows a small dial.
                                if (custom) {
                                    Icon(Icons.Rounded.Tune, null, tint = onIndigoContainer(), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(Reminders.label(t, m), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = onIndigoContainer())
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Rounded.Close, "Remove", tint = onIndigoContainer(), modifier = Modifier.size(15.dp))
                            }
                        }
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(cs.surfaceContainerLowest)
                                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(17.dp)).clickable { addReminder = true }.padding(horizontal = 14.dp),
                            ) {
                                Icon(Icons.Rounded.Add, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Add", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                            }
                            DropdownMenu(expanded = addReminder, onDismissRequest = { addReminder = false }, shape = RoundedCornerShape(18.dp), containerColor = cs.surfaceContainerLowest) {
                                val quick = Reminders.presets(t.allDay).filter { it !in t.reminders }
                                quick.forEach { m ->
                                    DropdownMenuItem(text = { Text(Reminders.label(t, m)) }, onClick = {
                                        t = t.copy(reminders = t.reminders + m)
                                        addReminder = false
                                    })
                                }
                                if (quick.isNotEmpty()) HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text("Custom…", color = indigo(), fontWeight = FontWeight.Medium) },
                                    leadingIcon = { Icon(Icons.Rounded.Tune, null, tint = indigo()) },
                                    onClick = {
                                        addReminder = false
                                        customReminder = true
                                    },
                                )
                            }
                        }
                    }
                }
                // Notes and steps
                Spacer(Modifier.height(18.dp))
                EditCard {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) {
                            if (t.notes.isEmpty()) Text("Notes", fontSize = 15.sp, color = cs.onSurfaceVariant)
                            BasicTextField(
                                value = t.notes, onValueChange = { t = t.copy(notes = it.take(5000)) },
                                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface), cursorBrush = SolidColor(indigo()),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.dividerAbove(true, cs.outlineVariant).padding(start = 52.dp, end = 4.dp)) {
                            Box(
                                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (step.done) indigo() else Color.Transparent)
                                    .border(2.dp, if (step.done) indigo() else cs.onSurfaceVariant, RoundedCornerShape(6.dp))
                                    .clickable { t = t.copy(checklist = t.checklist.map { if (it.id == step.id) it.copy(done = !it.done) else it }) },
                                contentAlignment = Alignment.Center,
                            ) { if (step.done) Icon(Icons.Rounded.Check, null, tint = onIndigo(), modifier = Modifier.size(14.dp)) }
                            Spacer(Modifier.width(12.dp))
                            BasicTextField(
                                value = step.text, onValueChange = { v -> t = t.copy(checklist = t.checklist.map { if (it.id == step.id) it.copy(text = v.take(200)) else it }) },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 14.5.sp, color = if (step.done) cs.onSurfaceVariant else cs.onSurface, textDecoration = if (step.done) TextDecoration.LineThrough else null),
                                cursorBrush = SolidColor(indigo()),
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp).focusRequester(stepFocus),
                            )
                            BarIcon(Icons.Rounded.Close, "Remove step", tint = cs.onSurfaceVariant, onClick = { t = t.copy(checklist = t.checklist.filterNot { it.id == step.id }) })
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().dividerAbove(true, cs.outlineVariant).clickable {
                            val step = CheckItem(UUID.randomUUID().toString(), "")
                            t = t.copy(checklist = t.checklist + step)
                            focusStep = step.id
                        }.padding(start = 16.dp, top = 18.dp, bottom = 18.dp),
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = indigo(), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Add a step", fontSize = 15.sp, color = indigo())
                    }
                }
                if (original != null) {
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = { confirmDelete = true }, modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.errorContainer, contentColor = cs.error),
                        ) {
                            Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete", fontSize = 15.sp)
                        }
                        FilledTonalButton(
                            onClick = {
                                if (original.done) CalendarStore.uncomplete(original.id) else {
                                    if (changed()) CalendarStore.save(t)
                                    CalendarStore.complete(original.id)
                                }
                                onClose()
                            },
                            modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = indigoContainer(), contentColor = onIndigoContainer()),
                        ) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (original.done) "Not done" else "Mark done", fontSize = 15.sp)
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (whenSheet) {
        WhenSheet(t, onSet = { day, time ->
            setWhen(day, time)
            whenSheet = false
        }, onDismiss = { whenSheet = false })
    }

    if (endPicker) {
        TimeDialog(
            "Ends at", if (t.endTime >= 0) t.endTime else (t.time.coerceAtLeast(0) + 60).coerceAtMost(23 * 60 + 59),
            onPick = { m ->
                t = t.copy(endTime = if (m > t.time) m else -1)
                endPicker = false
            },
            onDismiss = { endPicker = false },
        )
    }

    if (customReminder) {
        CustomReminderSheet(t, onAdd = { m ->
            if (m !in t.reminders) t = t.copy(reminders = t.reminders + m)
            customReminder = false
        }, onDismiss = { customReminder = false })
    }

    if (repeatSheet) {
        ModalBottomSheet(onDismissRequest = { repeatSheet = false }, containerColor = cs.surfaceContainerLowest) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
                SheetTitle("Repeat")
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
        ModalBottomSheet(onDismissRequest = { listSheet = false }, containerColor = cs.surfaceContainerLowest) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
                SheetTitle("List")
                CalendarStore.lists.forEach { l ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                            t = t.copy(listId = l.id)
                            listSheet = false
                        }.padding(horizontal = 12.dp, vertical = 14.dp),
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
            containerColor = cs.surfaceContainerLowest,
            title = { Text("Save changes?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text("Save", color = indigo()) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Discard", color = indigo()) } },
        )
    }
    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = cs.surfaceContainerLowest,
            title = { Text("Delete this to-do?") },
            text = { Text(if (original.repeat.repeats) "It won't repeat any more." else "It goes from every phone you're signed in to.") },
            confirmButton = { TextButton(onClick = { CalendarStore.delete(original.id); confirmDelete = false; onClose() }) { Text("Delete", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = indigo()) } },
        )
    }
}

// ============================================================================ the Date & time sheet
/**
 * The day and the time together: a month to pick the day, then All day or a time right below it
 * (hour : minute, AM / PM, and a few quick times). [onSet] gets the day and the time (-1 = all day).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WhenSheet(t: Task, onSet: (day: Int, time: Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val today = Days.today()
    var day by remember { mutableIntStateOf(if (t.hasDate) t.day else today) }
    var month by remember { mutableIntStateOf(Days.firstOfMonth(day)) }
    var timed by remember { mutableStateOf(!t.allDay) }
    // Minutes after midnight. Our own boxes, not TimeInput: TimeInput grabs focus as it appears and the
    // keyboard covers Set. Tapping a box opens the clock face instead; quick times are one tap.
    var time by remember { mutableIntStateOf(if (t.time >= 0) t.time else 9 * 60) }
    var dial by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLowest) {
        Column(Modifier.animateContentSize(tween(200)).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetTitle("Date & time", modifier = Modifier.padding(horizontal = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp)) {
                Text("${Days.monthNames[month / 100 % 100 - 1]} ${month / 10000}", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
                BarIcon(Icons.Rounded.ChevronLeft, "Last month", onClick = { month = Days.addMonths(month, -1) })
                BarIcon(Icons.Rounded.ChevronRight, "Next month", onClick = { month = Days.addMonths(month, 1) })
            }
            MiniMonth(month, day, today, onPick = { d ->
                day = d
                month = Days.firstOfMonth(d)
            }, modifier = Modifier.padding(horizontal = 14.dp))
            PillTabs(listOf("All day", "Set a time"), if (timed) 1 else 0, { timed = it == 1 }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            if (timed) {
                TimeBoxes(time, onOpen = { dial = true }, onPeriod = { pm -> time = if (pm) time % 720 + 720 else time % 720 },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp, bottom = 14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    QUICK_TIMES.forEach { m ->
                        FilterChipLike(Days.time(m), null, time == m) { time = m }
                    }
                }
            }
            SheetButtons("Set", onCancel = onDismiss, onConfirm = { onSet(day, if (timed) time else -1) })
        }
    }
    if (dial) TimeDialog("Time", time, onPick = { time = it; dial = false }, onDismiss = { dial = false })
}

/** "09 : 30" with AM / PM beside it. The boxes open the clock face; AM / PM switch with a tap. */
@Composable
private fun TimeBoxes(minutes: Int, onOpen: () -> Unit, onPeriod: (pm: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val hour12 = (minutes / 60 % 12).let { if (it == 0) 12 else it }
    val pm = minutes >= 12 * 60
    @Composable
    fun Digits(text: String, label: String) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = 84.dp, height = 68.dp).clip(RoundedCornerShape(18.dp)).background(indigoContainer())
                .clickable(onClickLabel = "Change the $label", onClick = onOpen),
        ) { Text(text, fontSize = 34.sp, fontWeight = FontWeight.Medium, color = onIndigoContainer()) }
    }
    @Composable
    fun Period(text: String, on: Boolean, click: () -> Unit) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) onIndigoContainer() else cs.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) indigoContainer() else cs.surfaceContainer)
                .clickable(onClick = click).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Digits("%02d".format(hour12), "hour")
        Text(":", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
        Digits("%02d".format(minutes % 60), "minute")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp)) {
            Period("AM", !pm) { onPeriod(false) }
            Period("PM", pm) { onPeriod(true) }
        }
    }
}

/** A small month to pick a day in: the picked day filled, today in Calendar's colour. */
@Composable
private fun MiniMonth(month: Int, selected: Int, today: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val mondayFirst = CalendarStore.weekStartsMonday
    val lead = if (mondayFirst) Days.weekday(month) - 1 else Days.weekday(month) % 7
    val gridStart = Days.add(month, -lead)
    val names = if (mondayFirst) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("S", "M", "T", "W", "T", "F", "S")
    val accent = indigo()
    val onAccent = onIndigo()
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            names.forEach { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(bottom = 4.dp)) }
        }
        for (w in 0 until 6) {
            val rowStart = Days.add(gridStart, w * 7)
            if (w > 0 && rowStart / 100 != month / 100) break
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = Days.add(rowStart, i)
                    val inMonth = d / 100 == month / 100
                    val on = d == selected
                    Box(
                        Modifier.weight(1f).height(36.dp).padding(horizontal = 4.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (on) accent else Color.Transparent).clickable { onPick(d) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${d % 100}", fontSize = 13.5.sp,
                            fontWeight = if (on || d == today) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                on -> onAccent
                                d == today -> accent
                                !inMonth -> cs.onSurfaceVariant.copy(alpha = .5f)
                                else -> cs.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================ a custom reminder
private class AmountUnit(val name: String, val minutes: Int, val steps: List<Int>)

/** Minutes go 1–4, then in fives; the rest one by one. */
private val AMOUNT_UNITS = listOf(
    AmountUnit("Minutes", 1, (1..4).toList() + (5..120 step 5).toList()),
    AmountUnit("Hours", 60, (1..48).toList()),
    AmountUnit("Days", 24 * 60, (1..60).toList()),
    AmountUnit("Weeks", 7 * 24 * 60, (1..12).toList()),
)

/**
 * "Custom…" in Remind me: any amount of time before ("Before"), or an exact moment ("On a date &
 * time"). Either way it's kept as minutes before the to-do ([Reminders]) — so it repeats with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomReminderSheet(t: Task, onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableIntStateOf(0) }
    var unit by remember { mutableIntStateOf(if (t.allDay) 2 else 1) }
    var amount by remember { mutableIntStateOf(1) }
    // An exact moment: the evening before, to start with.
    var atDay by remember { mutableIntStateOf(Days.add(t.day, -1)) }
    var atMinute by remember { mutableIntStateOf(20 * 60) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }

    val u = AMOUNT_UNITS[unit]
    val m = if (tab == 0) amount * u.minutes else Reminders.offset(t, atDay, atMinute)
    val ok = Reminders.allowed(t, m)
    val (momentDay, momentMinute) = Reminders.moment(t, m)
    val passed = !t.repeat.repeats && Reminders.atMs(t, m) <= System.currentTimeMillis()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLowest) {
        Column(Modifier.animateContentSize(tween(200)).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetTitle("Custom reminder", if (tab == 0) "Any amount of time before the to-do." else "Pick exactly when to be reminded.", Modifier.padding(horizontal = 8.dp))
            PillTabs(listOf("Before", "On a date & time"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
            if (tab == 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp)) {
                    val i = u.steps.indexOf(amount)
                    StepCircle(Icons.Rounded.Remove, "Less", enabled = i > 0) { amount = u.steps[i - 1] }
                    Text("$amount", fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 22.dp).widthIn(min = 64.dp))
                    StepCircle(Icons.Rounded.Add, "More", enabled = i < u.steps.lastIndex) { amount = u.steps[i + 1] }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    AMOUNT_UNITS.forEachIndexed { k, x ->
                        FilterChipLike(x.name, null, unit == k) {
                            unit = k
                            // Keep the number if this unit has it, else the nearest it has.
                            amount = x.steps.minBy { kotlin.math.abs(it - amount) }
                        }
                    }
                }
                val amountText = Reminders.duration(m)
                Banner(
                    amountText.replaceFirstChar { it.uppercase() },
                    "${Days.short(momentDay)} at ${Days.time(momentMinute)}." +
                        (if (t.allDay) " All-day to-dos count from 9:00 AM." else "") +
                        " If it repeats, $amountText every time.",
                )
            } else {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().appCard(CardShape)) {
                    EditRow(Icons.Rounded.CalendarMonth, Days.short(atDay), sub = "Date", top = false, onClick = { datePicker = true }) { Chevron() }
                    EditRow(Icons.Rounded.Schedule, Days.time(atMinute), sub = "Time", onClick = { timePicker = true }) { Chevron() }
                }
                val phrase = Reminders.phrase(t, m)
                Banner(
                    "${Days.short(momentDay)}, ${Days.time(momentMinute)}",
                    "$phrase. If the to-do repeats, it's ${phrase.replaceFirstChar { it.lowercase() }} each time.",
                )
                if (!ok) {
                    Text(
                        if (t.allDay) "Pick a time on or before the to-do's day." else "Pick a time before the to-do starts (${Days.time(t.time)}).",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.error, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    )
                }
            }
            if (ok && passed) {
                Text("That time has already passed.", fontSize = 13.sp, color = cs.error, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
            }
            SheetButtons("Add", enabled = ok, onCancel = onDismiss, onConfirm = { onAdd(m) })
        }
    }

    if (datePicker) {
        // Any day up to the to-do's own, as far as a year before.
        val minUtc = utcMs(Days.add(t.day, -366))
        val maxUtc = utcMs(t.day)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = utcMs(atDay),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in minUtc..maxUtc
                override fun isSelectableYear(year: Int) = year in (minUtc.let(::fromUtc) / 10000)..(t.day / 10000)
            },
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            colors = DatePickerDefaults.colors(containerColor = cs.surfaceContainerLowest),
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { atDay = fromUtc(it) }
                    datePicker = false
                }) { Text("OK", color = indigo()) }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel", color = indigo()) } },
        ) { DatePicker(state = state, colors = DatePickerDefaults.colors(containerColor = cs.surfaceContainerLowest, selectedDayContainerColor = indigo(), selectedDayContentColor = onIndigo(), todayDateBorderColor = indigo(), todayContentColor = indigo())) }
    }
    if (timePicker) {
        TimeDialog("Remind me at", atMinute, onPick = {
            atMinute = it
            timePicker = false
        }, onDismiss = { timePicker = false })
    }
}

@Composable
private fun StepCircle(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceContainer).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = cs.onSurface.copy(alpha = if (enabled) 1f else .38f)) }
}

/** The soft indigo box saying when a reminder will come. */
@Composable
private fun Banner(title: String, text: String) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(indigoContainer())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(Icons.Rounded.Notifications, null, tint = onIndigoContainer(), modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = onIndigoContainer())
            Text(text, fontSize = 13.5.sp, lineHeight = 19.sp, color = onIndigoContainer(), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp).size(20.dp))

// ============================================================================ small parts
/** "Cancel" and the sheet's own action, side by side as pills. */
@Composable
private fun SheetButtons(confirm: String, enabled: Boolean = true, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
        FilledTonalButton(
            onClick = onCancel, modifier = Modifier.weight(1f).height(46.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = indigoContainer(), contentColor = onIndigoContainer()),
        ) { Text("Cancel", fontSize = 15.sp) }
        Button(
            onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f).height(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = indigo(), contentColor = onIndigo()),
        ) { Text(confirm, fontSize = 15.sp) }
    }
}

@Composable
private fun SheetTitle(title: String, sub: String? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        if (sub != null) Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The clock in Calendar's colours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun clockColors(): TimePickerColors {
    val cs = MaterialTheme.colorScheme
    return TimePickerDefaults.colors(
        clockDialColor = cs.surfaceContainer,
        selectorColor = indigo(),
        containerColor = cs.surfaceContainerLowest,
        periodSelectorBorderColor = cs.outlineVariant,
        periodSelectorSelectedContainerColor = indigoContainer(),
        periodSelectorSelectedContentColor = onIndigoContainer(),
        periodSelectorUnselectedContentColor = cs.onSurfaceVariant,
        timeSelectorSelectedContainerColor = indigoContainer(),
        timeSelectorSelectedContentColor = onIndigoContainer(),
        timeSelectorUnselectedContainerColor = cs.surfaceContainer,
        timeSelectorUnselectedContentColor = cs.onSurface,
    )
}

/** A clock face in a dialog (an end time, a custom reminder's time). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerLowest,
        title = { Text(title) },
        text = { TimePicker(state = state, colors = clockColors()) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK", color = indigo()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = indigo()) } },
    )
}

@Composable
private fun EditCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().appCard(CardShape)) { content() }
}

/** A row in the editor's cards: icon, title (and a small line under it), something on the right. [top]: a thin line above it. */
@Composable
private fun EditRow(
    icon: ImageVector, title: String, onClick: () -> Unit, sub: String? = null, top: Boolean = true, dim: Boolean = false,
    enabled: Boolean = true, trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).dividerAbove(top, cs.outlineVariant).clickable(enabled = enabled, onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = if (dim) cs.onSurfaceVariant else cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        }
        trailing()
    }
}

@Composable
internal fun RadioRow(title: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = indigo()))
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
        containerColor = cs.surfaceContainerLowest,
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
                    RadioButton(selected = r.endKind == "count", onClick = { r = r.copy(endKind = "count", endCount = countText.toIntOrNull() ?: 10) }, colors = RadioButtonDefaults.colors(selectedColor = indigo()))
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
        confirmButton = { TextButton(onClick = { onDone(if (r.unit == "week" && r.weekdays == 0) r.copy(weekdays = 1 shl (Days.weekday(day.coerceAtLeast(Days.today())) - 1)) else r) }) { Text("Done", color = indigo()) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = indigo()) } },
    )
    if (endPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = utcMs(if (r.endDay > 0) r.endDay else Days.addMonths(day.coerceAtLeast(Days.today()), 1)))
        DatePickerDialog(
            onDismissRequest = { endPicker = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { r = r.copy(endKind = "date", endDay = fromUtc(it)) }; endPicker = false }) { Text("OK", color = indigo()) } },
            dismissButton = { TextButton(onClick = { endPicker = false }) { Text("Cancel", color = indigo()) } },
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
