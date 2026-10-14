package com.agani.syncup.forest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.FlutterDash
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.content.ContextCompat
import com.agani.syncup.browser.ui.StepSlider
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DAY = 86_400_000L
private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Jan–Mar" for a run of months. */
private fun monthsText(months: Collection<Int>): String {
    val m = months.sorted()
    if (m.isEmpty()) return ""
    return if (m.size == 1) MONTHS[m[0] - 1] else "${MONTHS[m.first() - 1]}–${MONTHS[m.last() - 1]}"
}

/** A page with Forest's paper behind it. */
@Composable
private fun Page(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = forestColors()
    BarIcons(light = c.light)
    Column(Modifier.fillMaxSize().background(c.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ForestTopBar(title, onBack, actions = actions)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), content = content)
    }
}

// ------------------------------------------------------------------ one tree
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TreePage(id: String?, onBack: () -> Unit) {
    val c = forestColors()
    val tree = id?.let { ForestStore.tree(it) }
    if (tree == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val now = System.currentTimeMillis()
    val stage = tree.stage(now)
    val resting = ForestStore.resting()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    Page(
        tree.stageName(now), onBack,
        actions = {
            IconButton(onClick = { rename = true }) { Icon(Icons.Rounded.Edit, "Rename", tint = c.ink) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = c.ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove this tree") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; remove = true })
                }
            }
        },
    ) {
        TreeHero(tree, stage, resting)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) {
            TitleText(tree.species.title, 17.sp)
            Spacer(Modifier.width(8.dp))
            TitleText(tree.species.botanical, 13.sp, c.muted, italic = true)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (stage != Stage.GROWN) Pill(onClick = { days = true }) { Text("Grows in", fontSize = 12.5.sp, color = c.ink2); PillText(growDaysText(tree.growDays), "✎") }
            Pill { Text("Planted ${FDays.dateText(tree.plantedMs)}", fontSize = 12.5.sp, color = c.ink2) }
            Pill { PillText(minutesText(tree.care), "care") }
            if (tree.seedsGiven > 0) Pill { PillText("${tree.seedsGiven}", if (tree.seedsGiven == 1) "seed given" else "seeds given") }
        }
        if (resting) Note("Resting — its leaves turned gold after a quiet week. One calm session and it's green again.", Icons.Rounded.Bedtime, Modifier.padding(top = 10.dp))
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in Stage.entries) LifeRow(tree, s, stage, now)
            if (tree.species != Species.BAMBOO) SeasonRows(tree, stage)
        }
    }
    if (rename) {
        var text by remember { mutableStateOf(tree.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Name this tree") },
            text = {
                OutlinedTextField(text, { text = it.take(40) }, singleLine = true, placeholder = { Text(tree.species.title) }, supportingText = { Text("Like \"Amma's mango\" — or leave it as ${tree.species.title.lowercase()}") })
            },
            confirmButton = {
                TextButton(onClick = {
                    ForestStore.rename(tree.id, text)
                    rename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } },
        )
    }
    if (days) {
        var v by remember { mutableIntStateOf(tree.growDays) }
        AlertDialog(
            onDismissRequest = { days = false },
            title = { Text("Grows in") },
            text = {
                Column {
                    TitleText(growDaysText(v), 26.sp, modifier = Modifier.padding(bottom = 8.dp))
                    StepSlider(
                        value = v.toFloat(), range = 1f..14f, step = 1f, label = "days", steps = 12,
                        onChange = { v = it.toInt().coerceIn(1, 14) },
                        colors = SliderDefaults.colors(thumbColor = c.moss, activeTrackColor = c.moss),
                    )
                    Text("Counted from when it was planted. Longer or shorter, it needs about ${minutesText(Tree.needs(Stage.GROWN, v).first)} of calm in all.", fontSize = 12.5.sp, color = c.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ForestStore.changeGrowDays(tree.id, v)
                    days = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { days = false }) { Text("Cancel") } },
        )
    }
    if (remove) {
        AlertDialog(
            onDismissRequest = { remove = false },
            title = { Text("Remove ${tree.stageName(now).lowercase()}?") },
            text = { Text("It leaves your forest${if (ForestSync.active) " on all your phones" else ""}. The calm minutes you gave still count.") },
            confirmButton = {
                TextButton(onClick = {
                    remove = false
                    ForestStore.remove(tree.id)
                    onBack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { remove = false }) { Text("Keep") } },
        )
    }
}

/** The tree on its own patch of meadow, swaying, a butterfly passing. */
@Composable
private fun TreeHero(tree: Tree, stage: Stage, resting: Boolean) {
    val clock = rememberClock()
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(358f / 270f).clip(RoundedCornerShape(24.dp))) {
        val s = maxWidth / 358f
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 358f
            drawRect(Brush.verticalGradient(listOf(Color(0xFFCFE8EE), Color(0xFFF3ECD9))))
            drawCircle(Brush.radialGradient(0f to Color(0xFFFFF6D8), .25f to Color(0xE6FFE7A3), 1f to Color(0x00FFE7A3), center = Offset(70 * u, 60 * u), radius = 90 * u), 90 * u, Offset(70 * u, 60 * u), alpha = .8f)
            cloud(250 * u, 50 * u, .8f * u)
            val hill = Path().apply {
                moveTo(0f, 206 * u); cubicTo(80 * u, 192 * u, 170 * u, 196 * u, 250 * u, 202 * u)
                cubicTo(290 * u, 205 * u, 330 * u, 198 * u, 358 * u, 200 * u); lineTo(358 * u, 270 * u); lineTo(0f, 270 * u); close()
            }
            drawPath(hill, Brush.verticalGradient(listOf(Color(0xFFA4CF74), Color(0xFF6E9F4E)), startY = 196 * u, endY = 270 * u))
        }
        val w = if (stage.ordinal <= Stage.SAPLING.ordinal) 150f else 200f
        val crop = cropFor(stage)
        val box = if (stage.ordinal <= Stage.SAPLING.ordinal) w * crop.height / crop.width else w * 1.2f
        TreePicture(tree.species, stage, tree.variant, Modifier.offset((179 - w / 2) * s, (238 - box + (if (crop.bottom < 240f) 0f else 14 * w / 200)) * s).size(w * s, box * s), resting = resting, sway = true)
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 358f
            meadowBits(u, 214f, 270f, 41)
            val a = clock.value * .6f
            butterfly(1, (264 + 20 * sin(a)) * u, (130 + 14 * sin(a * 1.4f + 1)) * u, 22 * u, clock.value, 0f)
        }
    }
}

private fun DrawScope.cloud(x: Float, y: Float, s: Float) {
    drawOval(Color.White, Offset(x - 30 * s, y - 9 * s), Size(60 * s, 18 * s), alpha = .8f)
    drawOval(Color.White, Offset(x - 3 * s, y - 18 * s), Size(34 * s, 22 * s), alpha = .8f)
    drawOval(Color.White, Offset(x - 25 * s, y - 12 * s), Size(26 * s, 16 * s), alpha = .8f)
}

/** Grass tufts and tiny wildflowers along the bottom of a picture. */
private fun DrawScope.meadowBits(u: Float, y0: Float, y1: Float, seed: Long) {
    val rnd = Random(seed)
    val cols = listOf(Color(0xFF5F9A45), Color(0xFF6FA84E), Color(0xFF7FB55A), Color(0xFF4E8A3A))
    val paths = List(4) { Path() }
    repeat(60) {
        val x = rnd.nextFloat() * 358 * u
        val y = (y0 + rnd.nextFloat() * (y1 - y0)) * u
        val h = (5 + rnd.nextFloat() * 5) * u
        val p = paths[rnd.nextInt(4)]
        p.moveTo(x, y); p.quadraticTo(x - 1.5f * u, y - h * .6f, x - 3 * u, y - h)
        p.moveTo(x, y); p.quadraticTo(x + .5f * u, y - h * .7f, x + u, y - h * 1.1f)
        p.moveTo(x, y); p.quadraticTo(x + 2 * u, y - h * .5f, x + 4 * u, y - h * .8f)
    }
    paths.forEachIndexed { i, p -> drawPath(p, cols[i], style = Stroke(1.2f * u, cap = StrokeCap.Round)) }
    val fl = listOf(Color.White, Color(0xFFFDE68A), Color(0xFFF9A8D4), Color(0xFFC4B5FD))
    repeat(18) { drawCircle(fl[rnd.nextInt(4)], (1 + rnd.nextFloat()) * u, Offset(rnd.nextFloat() * 358 * u, (y0 + 6 + rnd.nextFloat() * (y1 - y0 - 8)) * u)) }
}

@Composable
private fun LifeRow(tree: Tree, s: Stage, current: Stage, now: Long) {
    val isNow = s == current
    val past = s.ordinal < current.ordinal
    val (minC, minT) = Tree.needs(s, tree.growDays)
    val date = tree.stageMs[s]?.let { FDays.dateText(it) }
    val text = when {
        s == Stage.SEED && past -> "Planted ${FDays.dateText(tree.plantedMs)}"
        past -> date ?: "reached"
        isNow && s == Stage.GROWN -> "since ${date ?: FDays.dateText(now)} · ${minutesText(tree.care)} of calm in all"
        isNow -> {
            val next = Stage.entries[s.ordinal + 1]
            val nc = Tree.needs(next, tree.growDays).first
            "since ${date ?: FDays.dateText(tree.plantedMs)} · ${tree.care} of $nc minutes"
        }
        s.ordinal == current.ordinal + 1 -> tree.next(now)?.let { (_, m, ms) ->
            listOfNotNull(if (ms > 0) "in ${durationText(ms)}" else null, if (m > 0) "$m more calm minutes" else null).joinToString(" · ").ifBlank { "soon" }
        } ?: ""
        else -> {
            val whenText = if (minT < DAY) "after ${durationText(minT)}" else "from day ${(minT + DAY - 1) / DAY}"
            "$whenText · ${minutesText(minC)} of calm in all"
        }
    }
    StageRowView(tree.species, s, tree.variant, if (isNow) "${stageTitle(s)} · now" else stageTitle(s), text, isNow, future = s.ordinal > current.ordinal)
}

private fun stageTitle(s: Stage) = if (s == Stage.YOUNG) "Young tree" else s.title

@Composable
private fun SeasonRows(tree: Tree, current: Stage) {
    val sp = tree.species
    val month = FDays.month()
    val grown = current == Stage.GROWN
    val flowerMonths = sp.season.filter { !sp.fruitsIn(it) }
    val fruitMonths = sp.season.filter { sp.fruitsIn(it) }
    if (flowerMonths.isNotEmpty()) {
        val now = grown && month in flowerMonths
        StageRowView(sp, Stage.GROWN, tree.variant, if (now) "Flowers · now" else "Flowers", "in its season — ${sp.title.lowercase()} flowers ${monthsText(flowerMonths)}", now, future = !now, month = flowerMonths.first())
    }
    val seedText = if (fruitMonths.isNotEmpty()) "fruits ${monthsText(fruitMonths)} · 1 seed when grown, 1 more each season" else "1 seed when grown · 1 more each flowering season"
    val now = grown && month in fruitMonths
    StageRowView(sp, Stage.GROWN, tree.variant, if (fruitMonths.isNotEmpty()) (if (now) "Fruit & seeds · now" else "Fruit & seeds") else "Seeds", seedText, now, future = !now && !grown, month = fruitMonths.firstOrNull() ?: month)
}

@Composable
private fun StageRowView(species: Species, stage: Stage, variant: Int, title: String, text: String, isNow: Boolean, future: Boolean, month: Int = FDays.month()) {
    val c = forestColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .let { if (isNow) it.background(c.soft).border(1.dp, c.softLine, RoundedCornerShape(14.dp)) else it }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        TreePicture(species, stage, variant, Modifier.size(40.dp, 44.dp), month = month, alpha = if (future) .5f else 1f)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (future) c.ink.copy(alpha = .5f) else c.ink)
            Text(text, fontSize = 12.sp, color = if (future) c.muted.copy(alpha = .6f) else c.muted)
        }
    }
}

// ------------------------------------------------------------------ how trees grow
@Composable
internal fun HowPage(onBack: () -> Unit) {
    val c = forestColors()
    Page("How trees grow", onBack) {
        Wheel()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
            TableRow("Grows in", "Sapling", "Young", "Grown", "Calm in all", header = true)
            for (d in listOf(1, 3, 7, 14)) {
                fun at(s: Stage): String {
                    val t = Tree.needs(s, d).second
                    return when {
                        t < DAY -> "${t / 3_600_000} h"
                        d == 1 -> "1 day"
                        else -> "day ${(t + DAY - 1) / DAY}"
                    }
                }
                TableRow(growDaysText(d), at(Stage.SAPLING), at(Stage.YOUNG), at(Stage.GROWN), minutesText(Tree.needs(Stage.GROWN, d).first), on = d == ForestStore.lastGrowDays)
            }
        }
        Note("Each stage needs its share of the time and of the calm minutes. Any other time from 1 day to 2 weeks works the same way.", Icons.Rounded.Eco, Modifier.padding(top = 10.dp))
        Note("Nothing dies. A tree left alone for a week rests — its leaves turn gold. One calm session and it's green again.", Icons.Rounded.Bedtime, Modifier.padding(top = 8.dp))
        Note("Every grown tree drops a seed, and more in its season. New kinds of trees come with calm milestones — never bought.", Icons.Rounded.Spa, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TableRow(a: String, b: String, cc: String, d: String, e: String, header: Boolean = false, on: Boolean = false) {
    val c = forestColors()
    if (!header) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    Row(Modifier.fillMaxWidth().let { if (on) it.background(c.soft) else it }.padding(vertical = 8.dp, horizontal = 2.dp)) {
        val col = if (header) c.muted else c.ink2
        Text(a, fontSize = 12.5.sp, color = col, fontWeight = if (header) FontWeight.Normal else FontWeight.Medium, modifier = Modifier.weight(1.1f))
        Text(b, fontSize = 12.5.sp, color = col, modifier = Modifier.weight(1f))
        Text(cc, fontSize = 12.5.sp, color = col, modifier = Modifier.weight(1f))
        Text(d, fontSize = 12.5.sp, color = col, modifier = Modifier.weight(1f))
        Text(e, fontSize = 12.5.sp, color = col, textAlign = TextAlign.End, modifier = Modifier.weight(1.1f))
    }
}

/** Seven stages around a circle: seed to fruit and back to seed. */
@Composable
private fun Wheel() {
    val c = forestColors()
    val stages = listOf(
        Triple(Stage.SEED, "Seed", 0), Triple(Stage.SPROUT, "Sprout", 0), Triple(Stage.SAPLING, "Sapling", 0), Triple(Stage.YOUNG, "Young", 0),
        Triple(Stage.GROWN, "Grown", 9), Triple(Stage.GROWN, "Flowers", 2), Triple(Stage.GROWN, "Fruit & seeds", 5),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(358f / 330f)) {
        val s = maxWidth / 358f
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 358f
            val center = Offset(179 * u, 164 * u)
            drawCircle(Color(0xFFCFC5AC), 108 * u, center, style = Stroke(2 * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * u, 7 * u))))
            for (i in stages.indices) {
                val a = ((-90 + i * 360f / stages.size) * PI / 180).toFloat()
                drawCircle(c.card, 30 * u, Offset(center.x + 108 * u * cos(a), center.y + 108 * u * sin(a)))
                drawCircle(c.line, 30 * u, Offset(center.x + 108 * u * cos(a), center.y + 108 * u * sin(a)), style = Stroke(1 * u))
                val a2 = ((-90 + (i + .5f) * 360f / stages.size) * PI / 180).toFloat()
                val p = Offset(center.x + 108 * u * cos(a2), center.y + 108 * u * sin(a2))
                rotate((a2 * 180 / PI).toFloat() + 90, pivot = p) {
                    val arrow = Path().apply { moveTo(p.x - 4 * u, p.y - 4 * u); lineTo(p.x + 2 * u, p.y); lineTo(p.x - 4 * u, p.y + 4 * u) }
                    drawPath(arrow, Color(0xFFB9AE8E), style = Stroke(2 * u, cap = StrokeCap.Round))
                }
            }
        }
        stages.forEachIndexed { i, (st, name, month) ->
            val a = ((-90 + i * 360f / stages.size) * PI / 180).toFloat()
            val x = 179 + 108 * cos(a)
            val y = 164 + 108 * sin(a)
            TreePicture(if (month == 9) Species.NEEM else Species.MANGO, st, 21, Modifier.offset((x - 24) * s, (y - 27) * s).size(48 * s, 48 * s), month = if (month == 0) 9 else month)
            Text(name, fontSize = 11.5.sp, color = c.ink2, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.offset((x - 45) * s, (y + 32) * s).width(90 * s))
        }
        Column(Modifier.offset(0.dp, 140 * s).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TitleText("A tree's life", 19.sp)
            Text("1 day – 2 weeks, you choose", fontSize = 12.sp, color = c.muted)
        }
    }
}

// ------------------------------------------------------------------ trees & seeds
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KindsPage(onBack: () -> Unit) {
    val c = forestColors()
    val now = System.currentTimeMillis()
    Page("Trees & seeds", onBack) {
        for (row in Species.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                for (sp in row) {
                    val found = ForestStore.found(sp)
                    val mine = ForestStore.trees.filter { it.species == sp }
                    val grown = mine.count { it.stage(now) == Stage.GROWN }
                    val seeds = ForestStore.seeds(sp)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
                        TreePicture(sp, Stage.GROWN, 7, Modifier.fillMaxWidth().height(128.dp), crop = Rect(10f, 20f, 190f, 236f), faded = !found, month = sp.season.firstOrNull { !sp.fruitsIn(it) } ?: sp.season.firstOrNull() ?: FDays.month())
                        TitleText(sp.title, 16.sp, if (found) c.ink else Color(0xFFA39A86), FontWeight.Medium)
                        TitleText(sp.botanical, 11.5.sp, if (found) c.muted else Color(0xFFA39A86), italic = true)
                        val tag = when {
                            !found -> sp.findHint.replaceFirstChar { it.uppercase() }
                            mine.isEmpty() -> "$seeds seed${if (seeds == 1) "" else "s"} to plant"
                            else -> buildString {
                                append(if (grown > 0) "$grown grown" else "${mine.size} growing")
                                if (seeds > 0) append(" · $seeds seed${if (seeds == 1) "" else "s"}")
                            }
                        }
                        Text(
                            tag, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (found) c.moss else Color(0xFF8C8371),
                            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(9.dp)).background(if (found) c.soft else c.paper2).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        val hours = ForestStore.totalMinutes() / 60
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FChip(if (ForestStore.lotusPond()) "Lotus pond · in your forest" else "Lotus pond · $hours of 100 calm hours", ForestStore.lotusPond(), enabled = ForestStore.lotusPond()) {}
        }
        Note("Seeds come from your own trees: each grown tree gives one, and more in its season. Kinds you haven't found yet are pencil-faint, with the calm milestone that brings them.", Icons.Rounded.Spa, Modifier.padding(top = 10.dp))
    }
}

// ------------------------------------------------------------------ seasons & visitors
@Composable
internal fun SeasonsPage(onBack: () -> Unit) {
    val c = forestColors()
    val nowSeason = Season.of(FDays.month())
    val vis = visitors(ForestStore.trees)
    Page("Seasons", onBack) {
        for (row in listOf(Season.SUMMER, Season.MONSOON, Season.AUTUMN, Season.WINTER).chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                for (se in row) {
                    Column(Modifier.weight(1f)) {
                        SeasonMini(se, Modifier.fillMaxWidth().aspectRatio(170f / 128f).let { if (se == nowSeason) it.border(2.5.dp, c.moss, RoundedCornerShape(16.dp)).padding(3.dp) else it })
                        TitleText(if (se == nowSeason) "${se.title} · now" else se.title, 15.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                        Text("${se.months} · ${se.note}", fontSize = 11.5.sp, color = c.muted)
                    }
                }
            }
        }
        Label("Visitors")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 2.dp)) {
            VisitorRow({ Canvas(Modifier.size(34.dp, 28.dp)) { butterfly(0, size.width / 2, size.height / 2, size.width * .9f, 0f, 0f) } }, "Butterflies", "by day, around trees in flower or fruit · up to 6", vis.butterflies)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            VisitorRow({ Canvas(Modifier.size(34.dp, 28.dp)) { firefly(size.width / 2, size.height / 2, size.height * .45f, 1f, 2.dp.toPx()) } }, "Fireflies", "after sunset, one for each grown tree · up to 20", vis.fireflies)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            VisitorRow({ Icon(Icons.Rounded.FlutterDash, null, tint = c.ink2, modifier = Modifier.padding(horizontal = 5.dp).size(24.dp)) }, "Birds", "by day, more with each banyan and peepal", vis.birds)
        }
        Note("Your forest follows the real calendar and clock: Gulmohars blaze red in summer, soft rain in the monsoon, golden light in autumn, mist on winter mornings — and night after sunset.", Icons.Rounded.Eco, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun VisitorRow(icon: @Composable () -> Unit, title: String, text: String, count: Int) {
    val c = forestColors()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        icon()
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.ink)
            Text(text, fontSize = 12.sp, color = c.muted)
        }
        Text(if (count == 0) "none yet" else "$count", fontSize = 13.sp, color = if (count == 0) c.muted else c.moss, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SeasonMini(se: Season, modifier: Modifier) {
    val clock = rememberClock()
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp))) {
        val s = maxWidth / 170f
        val (sky, mead) = when (se) {
            Season.SUMMER -> (Color(0xFFFFD9A0) to Color(0xFFFFF3DC)) to (Color(0xFFC9C46C) to Color(0xFFA3A955))
            Season.MONSOON -> (Color(0xFF8FA6B2) to Color(0xFFC9D5DA)) to (Color(0xFF5C9E4A) to Color(0xFF3F7F38))
            Season.AUTUMN -> (Color(0xFFF6C98E) to Color(0xFFFCEBD0)) to (Color(0xFFB9B866) to Color(0xFF8E9A4C))
            Season.WINTER -> (Color(0xFFD6E3E8) to Color(0xFFF1F4F1)) to (Color(0xFF9DBE8C) to Color(0xFF7EA574))
        }
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 170f
            drawRect(Brush.verticalGradient(listOf(sky.first, sky.second)))
            if (se == Season.SUMMER) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF6D8), Color(0x00FFE7A3)), center = Offset(34 * u, 30 * u), radius = 30 * u), 30 * u, Offset(34 * u, 30 * u))
                drawCircle(Color(0xFFFFF3C4), 9 * u, Offset(34 * u, 30 * u))
            }
            val g = Path().apply {
                moveTo(0f, 86 * u); cubicTo(40 * u, 76 * u, 90 * u, 80 * u, 130 * u, 84 * u)
                cubicTo(150 * u, 86 * u, 160 * u, 80 * u, 170 * u, 82 * u); lineTo(170 * u, size.height); lineTo(0f, size.height); close()
            }
            drawPath(g, Brush.verticalGradient(listOf(mead.first, mead.second), startY = 80 * u, endY = size.height))
            if (se == Season.MONSOON) drawOval(Color(0xFF9CC3D2), Offset(108 * u, 108 * u), Size(44 * u, 8 * u), alpha = .8f)
        }
        val (big, bigMonth) = when (se) {
            Season.SUMMER -> Species.GULMOHAR to 5
            Season.MONSOON -> Species.MANGO to 8
            Season.AUTUMN -> Species.NEEM to 10
            Season.WINTER -> Species.PEEPAL to 1
        }
        val full = Rect(0f, 0f, 200f, 240f)
        TreePicture(big, Stage.GROWN, 11, Modifier.offset((92 - 55) * s, (116 - 132 * 226 / 240f) * s).size(110 * s, 132 * s), month = bigMonth, crop = full)
        if (se == Season.SUMMER) {
            TreePicture(Species.JASMINE, Stage.GROWN, 3, Modifier.offset((28 - 23) * s, (120 - 55.2f * 226 / 240) * s).size(46 * s, 55.2f * s), month = 5, crop = full)
        } else {
            TreePicture(Species.NEEM, Stage.YOUNG, 3, Modifier.offset((28 - 23) * s, (120 - 55.2f * 226 / 240) * s).size(46 * s, 55.2f * s), crop = full)
        }
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 170f
            val t = clock.value
            when (se) {
                Season.MONSOON -> {
                    val rnd = Random(3)
                    repeat(60) {
                        val x = ((rnd.nextFloat() * 190 + t * 20) % 190) * u
                        val y = ((rnd.nextFloat() * 140 + t * 80) % 140 - 10) * u
                        drawLine(Color(0xFFE8F1F6), Offset(x, y), Offset(x - 3 * u, y + 9 * u), strokeWidth = u, alpha = .7f)
                    }
                }
                Season.AUTUMN -> {
                    for ((i, x0) in listOf(120f, 60f).withIndex()) {
                        val y = ((t * 12 + i * 50) % 110) * u
                        val x = (x0 + 8 * sin(t + i)) * u
                        rotate(t * 90 + i * 40, pivot = Offset(x, y)) { drawOval(if (i == 0) Color(0xFFD9A441) else Color(0xFFC58A2C), Offset(x - 4 * u, y - 1.6f * u), Size(8 * u, 3.2f * u)) }
                    }
                    drawRect(Color(0xFFF6B25C), alpha = .12f)
                }
                Season.WINTER -> drawRect(Brush.verticalGradient(listOf(Color(0x00FFFFFF), Color(0x80FFFFFF), Color(0x00FFFFFF)), startY = 50 * u, endY = 100 * u), Offset(0f, 50 * u), Size(size.width, 50 * u))
                Season.SUMMER -> butterfly(0, (118 + 10 * sin(t)) * u, (64 + 6 * sin(t * 1.4f)) * u, 12 * u, t, 0f)
            }
        }
    }
}

// ------------------------------------------------------------------ your calm week
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WeekPage(onBack: () -> Unit) {
    val c = forestColors()
    val ctx = LocalContext.current
    val today = FDays.today()
    val days = (6 downTo 0).map { FDays.add(today, -it) }
    val mins = days.map { ForestStore.minutesOn(it) }
    val total = mins.sum()
    val now = System.currentTimeMillis()
    val grew = ForestStore.trees.count { (it.stageMs[Stage.GROWN] ?: 0) > now - 7 * DAY }
    var goalDialog by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(false) }
    val notifyAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ForestStore.changeReminder(true) }
    Page("Your calm week", onBack) {
        Text("This week", fontSize = 13.5.sp, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp)) {
            TitleText(if (total == 0) "No calm time yet" else if (total < 60) "$total min" else "${total / 60} h ${total % 60} min", 34.sp)
            if (total > 0) Text("  of calm", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        PlantBars(days, mins, ForestStore.goalMinutes, Modifier.padding(top = 6.dp).fillMaxWidth().aspectRatio(358f / 210f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Stat("${mins.count { it > 0 }}", "calm days", Modifier.weight(1f))
            Stat(if (mins.max() == 0) "—" else minutesText(mins.max()), "best day", Modifier.weight(1f))
            Stat("$grew", if (grew == 1) "tree grew" else "trees grew", Modifier.weight(1f))
        }
        Text(
            "${ForestStore.streak()} calm day${if (ForestStore.streak() == 1) "" else "s"} in a row · ${minutesText(ForestStore.totalMinutes())} in all",
            fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(20.dp))) {
            SettingRow(
                Icons.Rounded.Notifications, "A gentle reminder",
                if (ForestStore.reminderOn) "${timeText(ForestStore.reminderMinutes)} — \"A few calm minutes?\" · not on days the goal is met" else "Off",
                onClick = { if (ForestStore.reminderOn) timeDialog = true },
            ) {
                Switch(
                    checked = ForestStore.reminderOn,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notifyAsk.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ForestStore.changeReminder(on)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.moss, checkedThumbColor = c.onMoss),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            SettingRow(Icons.Rounded.Eco, "Daily goal", "Days that meet it flower", onClick = { goalDialog = true }) {
                Text("${ForestStore.goalMinutes} min", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.moss)
            }
        }
        Text(
            "Each day is a little plant — the more calm minutes, the taller it grows; days that met the goal flower. A missed day is just a seed in the soil.",
            fontSize = 12.5.sp, lineHeight = 17.sp, color = c.muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
        )
    }
    if (goalDialog) {
        AlertDialog(
            onDismissRequest = { goalDialog = false },
            title = { Text("Daily goal") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(5, 10, 15, 20, 30, 45, 60)) FChip("$m min", ForestStore.goalMinutes == m) {
                        ForestStore.changeGoal(m)
                        goalDialog = false
                    }
                }
            },
            confirmButton = { TextButton(onClick = { goalDialog = false }) { Text("Done") } },
        )
    }
    if (timeDialog) {
        val state = rememberTimePickerState(ForestStore.reminderMinutes / 60, ForestStore.reminderMinutes % 60, android.text.format.DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { timeDialog = false },
            title = { Text("Remind me at") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    ForestStore.changeReminder(true, state.hour * 60 + state.minute)
                    timeDialog = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text("Cancel") } },
        )
    }
}

private fun timeText(min: Int): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, min / 60)
        set(java.util.Calendar.MINUTE, min % 60)
    }
    return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(cal.time)
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val c = forestColors()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(10.dp),
    ) {
        TitleText(value, 20.sp, weight = FontWeight.Medium)
        Text(label, fontSize = 11.5.sp, color = c.muted)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, end: @Composable () -> Unit) {
    val c = forestColors()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(title, fontSize = 14.sp, color = c.ink)
            Text(sub, fontSize = 12.sp, color = c.muted)
        }
        end()
    }
}

/** A week of little plants: taller with more calm minutes, a flower on days that met the goal. */
@Composable
private fun PlantBars(days: List<Int>, mins: List<Int>, goal: Int, modifier: Modifier) {
    val c = forestColors()
    val letters = arrayOf("S", "M", "T", "W", "T", "F", "S")
    Canvas(modifier) {
        val u = size.width / 358f
        val base = 182 * u
        val per = minOf(3f, 150f / maxOf(goal, mins.maxOrNull() ?: 0, 1)) * u
        drawLine(Color(0xFFB9AE8E), Offset(0f, base), Offset(size.width, base), strokeWidth = 1.5f * u)
        val gy = base - goal * per
        drawLine(Color(0xFF9DBF86), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.6f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * u, 5 * u)))
        val rnd = Random(3)
        val label = android.graphics.Paint().apply { color = c.muted.toArgb(); textSize = 10 * u; isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT }
        val lw = label.measureText("$goal min goal")
        drawRoundRect(c.paper, Offset(size.width - lw - 10 * u, gy - 19 * u), Size(lw + 8 * u, 15 * u), androidx.compose.ui.geometry.CornerRadius(4 * u))
        drawContext.canvas.nativeCanvas.drawText("$goal min goal", size.width - 6 * u, gy - 8 * u, label)
        days.forEachIndexed { i, d ->
            val x = (26 + i * 51) * u
            val m = mins[i]
            val today = i == days.lastIndex
            val col = if (today) Color(0xFF2F5D3A) else Color(0xFF5E9A54)
            if (m > 0) {
                val top = base - maxOf(m * per, 16 * u)
                drawPath(Path().apply { moveTo(x, base); cubicTo(x - 2 * u, (base + top) / 2, x + 2 * u, (base + top) / 2, x, top) }, col, style = Stroke(3 * u, cap = StrokeCap.Round))
                var y = base - 10 * u
                var k = 0
                val leaves = Path()
                while (y > top + 6 * u) {
                    val ang = (if (k % 2 == 0) -30f else -150f) + (rnd.nextFloat() - .5f) * 12
                    val a = Math.toRadians(ang.toDouble())
                    val ca = cos(a).toFloat()
                    val sa = sin(a).toFloat()
                    val len = 13 * u
                    val wid = 4.5f * u
                    leaves.moveTo(x, y)
                    leaves.quadraticTo(x + len * .42f * ca - wid * sa, y + len * .42f * sa + wid * ca, x + len * ca, y + len * sa)
                    leaves.quadraticTo(x + len * .42f * ca + wid * sa, y + len * .42f * sa - wid * ca, x, y)
                    y -= 12 * u
                    k++
                }
                drawPath(leaves, if (today) Color(0xFF4E8F45) else Color(0xFF7DB55A))
                if (m >= goal) {
                    drawCircle(if (today) Color(0xFFE8B04B) else Color(0xFFF2A7B8), 5.5f * u, Offset(x, top - 2 * u))
                    drawCircle(Color(0xFFFFF3C4), 2 * u, Offset(x, top - 2 * u))
                }
            } else {
                drawOval(Color(0xFF8C6239), Offset(x - 5 * u, base - 4.4f * u), Size(10 * u, 4.8f * u))
            }
            drawContext.canvas.nativeCanvas.drawText(
                letters[FDays.weekday(d) - 1], x, 200 * u + 4 * u,
                android.graphics.Paint().apply {
                    color = (if (today) Color(0xFF2F5D3A) else c.muted).toArgb()
                    textSize = 11.5f * u
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    isFakeBoldText = today
                },
            )
        }
    }
}

