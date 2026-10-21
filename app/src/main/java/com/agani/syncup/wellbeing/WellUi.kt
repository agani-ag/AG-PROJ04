package com.agani.syncup.wellbeing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.browser.ui.BarIcon
import java.text.DateFormat
import java.util.Calendar

/** A tool's two colours: its accent and the soft container behind its icons. */
internal class WTint(val accent: Color, val container: Color)

@Composable
internal fun wTint(light: Long, dark: Long, lightC: Long, darkC: Long): WTint {
    val l = MaterialTheme.colorScheme.isLight
    return WTint(Color(if (l) light else dark), Color(if (l) lightC else darkC))
}

@Composable internal fun waterTint() = wTint(0xFF0E7490, 0xFF67E8F9, 0xFFCFFAFE, 0xFF164E63)
@Composable internal fun stepsTint() = wTint(0xFFB45309, 0xFFFCD34D, 0xFFFEF3C7, 0xFF451A03)
@Composable internal fun breaksTint() = wTint(0xFF0F766E, 0xFF5EEAD4, 0xFFCCFBF1, 0xFF134E4A)
@Composable internal fun sleepTint() = wTint(0xFF4338CA, 0xFFA5B4FC, 0xFFE0E7FF, 0xFF312E81)
@Composable internal fun habitsTint() = wTint(0xFFBE185D, 0xFFF9A8D4, 0xFFFCE7F3, 0xFF500724)
@Composable internal fun moodTint() = wTint(0xFF4D7C0F, 0xFFBEF264, 0xFFECFCCB, 0xFF1A2E05)

/** A Wellbeing page: back arrow, title, [actions], then [content] (scrolling unless [scroll] is false). */
@Composable
internal fun WellPage(
    title: String,
    onBack: () -> Unit,
    background: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = contentColor, onClick = onBack)
                Text(title, fontSize = 22.sp, lineHeight = 28.sp, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                actions()
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(bottom = 24.dp),
                content = content,
            )
        }
        overlay()
    }
}

/** A round progress ring with [content] in the middle. */
@Composable
internal fun Ring(progress: Float, color: Color, track: Color, size: Dp = 210.dp, stroke: Dp = 16.dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val d = this.size.minDimension - sw
            drawArc(track, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(d, d), style = Stroke(sw))
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) drawArc(color, -90f, 360f * p, false, Offset(sw / 2, sw / 2), Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

/** The last days as bars, with a dashed goal line (full colour = goal met). The last bar is today. */
@Composable
internal fun Bars(values: List<Float>, labels: List<String>, goal: Float, full: Color, light: Color, goalLabel: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val max = maxOf(values.maxOrNull() ?: 0f, goal) * 1.15f
    Column(modifier.fillMaxWidth()) {
        Text(goalLabel, fontSize = 11.sp, color = full, modifier = Modifier.align(Alignment.End))
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = (slot * .56f).coerceAtMost(30.dp.toPx())
            val gy = size.height - goal / max * size.height
            values.forEachIndexed { i, v ->
                val h = (v / max * size.height).coerceAtLeast(if (v > 0) 4.dp.toPx() else 0f)
                val x = slot * i + (slot - bw) / 2
                drawRoundRect(
                    if (v >= goal && goal > 0) full else light,
                    Offset(x, size.height - h), Size(bw, h),
                    androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
            }
            drawLine(full, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    color = if (i == labels.lastIndex) cs.onSurface else cs.onSurfaceVariant,
                    fontWeight = if (i == labels.lastIndex) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/** A row of small stat tiles ("Streak · 5 days"). */
@Composable
internal fun Stats(vararg items: Pair<String, String>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        items.forEach { (label, value) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerLowest)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(label, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
                Text(value, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A rounded card on the page. */
@Composable
internal fun WCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/** A small capital label above a group ("TODAY"). */
@Composable
internal fun Lab(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, letterSpacing = .4.sp, color = color, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))
}

/** An icon in its soft tile. */
@Composable
internal fun TintIcon(icon: ImageVector, tint: WTint, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .32f)).background(tint.container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint.accent, modifier = Modifier.size(size * .55f))
    }
}

/** A line with a switch. */
@Composable
internal fun SwitchLine(title: String, sub: String?, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit, icon: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = accent))
    }
}

/** A line with − value + (always − on the left, + on the right). */
@Composable
internal fun PlusMinusLine(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, sub: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
        RoundButton("−", "Less", onMinus)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.width(78.dp))
        RoundButton("+", "More", onPlus)
    }
}

@Composable
internal fun RoundButton(text: String, desc: String, onClick: () -> Unit, size: Dp = 38.dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(cs.surfaceContainerHigh).clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 20.sp, color = cs.onSurface) }
}

/** A row of choices, one picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Choices(options: List<T>, picked: T?, label: (T) -> String, tint: WTint, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.padding(horizontal = 16.dp)) {
        options.forEach { o ->
            val on = o == picked
            Box(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (on) tint.container else Color.Transparent)
                    .border(if (on) 1.5.dp else 1.dp, if (on) tint.accent else cs.outline, RoundedCornerShape(12.dp))
                    .clickable { onPick(o) }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(label(o), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) tint.accent else cs.onSurface) }
        }
    }
}

/** Mon … Sun toggles ([mask] bit 0 = Monday). */
@Composable
internal fun DaysPick(mask: Int, tint: WTint, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
            val on = mask and (1 shl i) != 0
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(19.dp)).background(if (on) tint.container else cs.surfaceContainer)
                    .clickable { onChange(mask xor (1 shl i)) },
                contentAlignment = Alignment.Center,
            ) { Text(d, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) tint.accent else cs.onSurfaceVariant) }
        }
    }
}

/** A tappable line showing a time; tapping opens the time picker. */
@Composable
internal fun TimeLine(title: String, minuteOfDay: Int, tint: WTint, onPick: () -> Unit, sub: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
        Text(timeText(minuteOfDay), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint.accent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(title: String, minuteOfDay: Int, onDone: (Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, android.text.format.DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onDone(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A big filled button. */
@Composable
internal fun BigButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, textColor: Color = Color.White, icon: ImageVector? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp).clip(RoundedCornerShape(26.dp)).background(color).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

/** A short hint line. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else TextAlign.Start, modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

/** "8:30 AM" / "20:30" as the phone shows times. */
internal fun timeText(minuteOfDay: Int): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60 % 24)
        set(Calendar.MINUTE, minuteOfDay % 60)
    }.time,
)

/** The time part of [ms] as the phone shows times. */
internal fun clockText(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(ms))

/** Short weekday name of a yyyymmdd day ("Mon"). */
internal fun weekdayShort(day: Int): String = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[com.agani.syncup.forest.FDays.weekday(day) - 1]

/** First letter of the weekday ("M"). */
internal fun weekdayLetter(day: Int): String = weekdayShort(day).take(1)

/** Monday-first index 0..6 of a yyyymmdd day. */
internal fun mondayIndex(day: Int): Int = (com.agani.syncup.forest.FDays.weekday(day) + 5) % 7

/** "1,250". */
internal fun Int.grouped(): String = "%,d".format(this)
