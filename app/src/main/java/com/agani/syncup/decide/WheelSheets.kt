package com.agani.syncup.decide

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

// The wheel's sheets: what's on the wheel (one box per option, phone 8), the saved wheels, recent
// picks, and the small name dialog.

/** A sheet's title and the grey line under it, as in the mockup's sheets. */
@Composable
private fun SheetTitle(title: String, sub: String? = null) {
    val cs = MaterialTheme.colorScheme
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
    if (sub != null) Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
}

private data class OptRow(val key: Long, val text: String, val color: Int, val origin: Int?)

/**
 * "What's on the wheel": each option in its own box with its colour and ✕, "Add an option", "Paste a
 * list". [onDone] gets the new options and how old positions moved (so a Last-choice game carries on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WheelOptionsSheet(wheel: Wheel, onDone: (List<WheelOption>, Map<Int, Int>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val tint = DecideTint.WHEEL
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    // Row keys only (not shown), so a plain counter.
    val keys = remember { AtomicLong(1L) }
    val rows = remember {
        mutableStateListOf<OptRow>().apply {
            wheel.options.forEachIndexed { i, o -> add(OptRow(keys.getAndIncrement(), o.name, o.color, i)) }
            // A new wheel starts with two empty boxes.
            while (size < 2) add(OptRow(keys.getAndIncrement(), "", nextWheelColor(map { it.color }), null))
        }
    }
    var focusKey by remember { mutableStateOf(if (wheel.options.isEmpty()) rows.firstOrNull()?.key else null) }
    var focused by remember { mutableStateOf<Long?>(null) }
    val filled = rows.count { it.text.isNotBlank() }

    fun addRow() {
        if (rows.size >= WHEEL_MAX_OPTIONS) return
        val r = OptRow(keys.getAndIncrement(), "", nextWheelColor(rows.map { it.color }), null)
        rows.add(r)
        focusKey = r.key
        scope.launch { list.animateScrollToItem(rows.lastIndex) }
    }

    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull().orEmpty()
        val lines = text.lines().map { it.trim().take(WHEEL_NAME_MAX) }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            Toast.makeText(context, "Nothing to paste — copy a list first", Toast.LENGTH_SHORT).show()
            return
        }
        // Empty boxes make way; the list goes after what's already there.
        rows.removeAll { it.text.isBlank() }
        for (l in lines) {
            if (rows.size >= WHEEL_MAX_OPTIONS) break
            rows.add(OptRow(keys.getAndIncrement(), l, nextWheelColor(rows.map { it.color }), null))
        }
        scope.launch { list.animateScrollToItem((rows.size - 1).coerceAtLeast(0)) }
    }

    fun done() {
        val kept = rows.filter { it.text.isNotBlank() }
        val options = kept.map { WheelOption(it.text.trim(), it.color) }
        // Old position → new, for the options still there unchanged.
        val moved = HashMap<Int, Int>()
        kept.forEachIndexed { i, r ->
            val o = r.origin
            if (o != null && wheel.options.getOrNull(o)?.name == r.text.trim()) moved[o] = i
        }
        onDone(options, moved)
    }

    ModalBottomSheet(onDismissRequest = ::done, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLowest) {
        SheetTitle("What's on the wheel", "Anything — people, food, products. One per box.")
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                OptionBox(
                    row = r,
                    focused = focused == r.key,
                    focusNow = focusKey == r.key,
                    onFocused = { on -> if (on) focused = r.key else if (focused == r.key) focused = null },
                    onFocusDone = { focusKey = null },
                    onText = { t -> rows.indexOfFirst { it.key == r.key }.takeIf { it >= 0 }?.let { rows[it] = rows[it].copy(text = t.take(WHEEL_NAME_MAX)) } },
                    onNext = { if (i == rows.lastIndex) addRow() else focusKey = rows[i + 1].key },
                    onRemove = { rows.removeAll { it.key == r.key } },
                )
            }
            item(key = "add") {
                AddOptionRow(enabled = rows.size < WHEEL_MAX_OPTIONS, onClick = ::addRow)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("$filled option${if (filled == 1) "" else "s"} · up to $WHEEL_MAX_OPTIONS", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                if (filled < 2) Text("Add at least 2 to spin.", fontSize = 12.5.sp, color = tint.icon)
            }
            TextButton(onClick = ::paste) {
                Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste a list", fontSize = 13.5.sp)
            }
        }
        DecideButton("Done", tint, onClick = ::done, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp))
    }
}

@Composable
private fun OptionBox(
    row: OptRow,
    focused: Boolean,
    focusNow: Boolean,
    onFocused: (Boolean) -> Unit,
    onFocusDone: () -> Unit,
    onText: (String) -> Unit,
    onNext: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val fr = remember { FocusRequester() }
    LaunchedEffect(focusNow) {
        if (focusNow) {
            runCatching { fr.requestFocus() }
            onFocusDone()
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(44.dp).clip(shape)
            .background(if (focused) cs.surfaceContainerLowest else cs.surfaceContainer)
            .then(if (focused) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .padding(start = 12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(wheelColor(row.color)))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (row.text.isEmpty()) Text("Option", fontSize = 15.sp, color = cs.onSurfaceVariant.copy(alpha = .7f))
            BasicTextField(
                value = row.text,
                onValueChange = onText,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { onNext() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { onFocused(it.isFocused) },
            )
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Remove", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "Remove ${row.text}", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
    }
}

/** The dashed "Add an option" row. */
@Composable
private fun AddOptionRow(enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = DecideTint.WHEEL.icon
    val line = cs.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(44.dp)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    line, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = tint.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (enabled) "Add an option" else "That's the most ($WHEEL_MAX_OPTIONS)",
            fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint.copy(alpha = if (enabled) 1f else .5f),
        )
    }
}

/** The saved wheels: pick one, or New wheel / Rename / Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WheelsSheet(onDismiss: () -> Unit, onNew: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val wheels = WheelStore.wheels
    val current = WheelStore.currentId
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerLowest) {
        SheetTitle("Your wheels")
        LazyColumn(Modifier.weight(1f, fill = false)) {
            itemsIndexed(wheels, key = { _, w -> w.id }) { _, w ->
                val on = w.id == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        WheelStore.select(w.id)
                        onDismiss()
                    }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        if (on) Icon(Icons.Rounded.Check, "Picked", tint = DecideTint.WHEEL.icon, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        w.name, fontSize = 15.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(w.countText, fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
        SheetAction(Icons.Rounded.Add, "New wheel", onClick = onNew)
        SheetAction(Icons.Rounded.Edit, "Rename", onClick = onRename)
        SheetAction(Icons.Rounded.DeleteOutline, "Delete", danger = true, enabled = wheels.size > 1, onClick = onDelete)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val col = (if (danger) cs.error else cs.onSurface).copy(alpha = if (enabled) 1f else .38f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Icon(icon, null, tint = col, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.5.sp, color = col)
    }
}

/** Recent picks (First choice), and in Last choice the order so far. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WheelResultsSheet(wheel: Wheel, onClear: (() -> Unit)?, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerLowest) {
        SheetTitle("Recent picks", "First choice · newest first, up to 10.")
        if (wheel.recent.isEmpty()) {
            Text("No picks yet. Spin the wheel!", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        } else {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(wheel.recent) { i, name ->
                    val col = wheel.options.firstOrNull { it.name == name }?.color
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp)) {
                        Text("${i + 1}", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(26.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (col != null) wheelColor(col) else cs.outline))
                        Spacer(Modifier.width(10.dp))
                        Text(name, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (wheel.mode == WheelMode.LAST && wheel.out.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutChips(wheel.outOptions, "Out so far", modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (onClear != null && wheel.recent.isNotEmpty()) {
            TextButton(onClick = onClear, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) { Text("Clear recent picks") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The knock-out order: number badge in the option's colour, then the name (struck through while playing). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OutChips(out: List<WheelOption>, title: String, modifier: Modifier = Modifier, struck: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            out.forEachIndexed { i, o ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(cs.surfaceContainer).padding(start = 4.dp, end = 10.dp),
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(wheelColor(o.color)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        o.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (struck) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (struck) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

/** A small dialog asking for a wheel's name. */
@Composable
internal fun WheelNameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it.take(WHEEL_NAME_MAX) }, singleLine = true, placeholder = { Text("Lunch, Team, Movie night…") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onDone(text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
