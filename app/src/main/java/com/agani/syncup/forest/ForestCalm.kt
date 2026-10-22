package com.agani.syncup.forest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Calm time's "phone down" screen: a dark clearing, the tree swaying, a gold ring filling. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuietScreen() {
    val ctx = LocalContext.current
    BarIcons(light = false)
    BackHandler { CalmSession.pause(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            if (CalmSession.checkEnd(ctx)) break
            delay(250)
        }
    }
    val left = CalmSession.leftMs(now)
    val total = CalmSession.totalMs.coerceAtLeast(1)
    val tree = CalmSession.treeId?.let { ForestStore.tree(it) }
    var confirmStop by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
            .drawBehind {
                drawRect(Brush.radialGradient(0f to Color(0xFF1F3F2C), .6f to Color(0xFF12291D), 1f to Color(0xFF0B1A12), center = Offset(size.width * .5f, size.height * .35f), radius = size.maxDimension * .75f))
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Text(
            "${CalmSession.mode.title}${tree?.let { " · ${it.stageName()}" } ?: ""}".uppercase(),
            fontSize = 13.5.sp, color = Color(0xFFBFD8B4), letterSpacing = .3.sp, modifier = Modifier.padding(top = 22.dp),
        )
        Spacer(Modifier.weight(.6f))
        Clearing(1f - left.toFloat() / total, tree, Modifier.padding(top = 8.dp).widthIn(max = 320.dp).fillMaxWidth(.82f).aspectRatio(1f))
        Text(clock(left), fontWeight = FontWeight.Light, fontSize = 58.sp, color = Color(0xFFEEF6EA), letterSpacing = 1.sp)
        Text(if (tree != null) "left · it's growing" else "left", fontSize = 14.sp, color = Color(0xFFBFD8B4))
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Ten sounds: one row that scrolls sideways (starting at the one playing), so the clearing keeps its room.
            val playing = remember { NatureSound.entries.indexOf(CalmSession.sound).coerceAtLeast(0) }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(),
                state = rememberLazyListState(initialFirstVisibleItemIndex = (playing - 1).coerceAtLeast(0)),
            ) {
                items(NatureSound.entries) { s ->
                    FChip(s.title, CalmSession.sound == s, icon = soundIcon(s), dark = true) {
                        CalmSession.changeSound(ctx, if (CalmSession.sound == s) null else s)
                    }
                }
            }
            Note("Put the phone down — face down is fine; the screen can sleep. Opening another app only pauses.", Icons.Rounded.PhoneAndroid, Modifier.padding(top = 12.dp), dark = true)
            Text(
                "Stop early", fontSize = 14.sp, color = Color(0xFFBFD8B4),
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { confirmStop = true }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (confirmStop) StopDialog(onDismiss = { confirmStop = false })
}

@Composable
private fun StopDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val min = (CalmSession.elapsedMs() / 60_000).toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop calm time?") },
        text = { Text(if (min > 0) "The $min minute${if (min == 1) "" else "s"} you gave still count." else "Less than a minute so far, so nothing counts yet.") },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                CalmSession.stop(ctx)
            }) { Text("Stop") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep going") } },
    )
}

/** The clearing: soft glow, the tree on its patch of grass, pollen drifting up, the time as a gold ring. */
@Composable
private fun Clearing(progress: Float, tree: Tree?, modifier: Modifier) {
    val clock = rememberClock()
    BoxWithConstraints(modifier) {
        val s = maxWidth / 320f
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 320f
            val c = Offset(160 * u, 160 * u)
            drawCircle(Brush.radialGradient(0f to Color(0xE63E6B47), .6f to Color(0x80244A33), 1f to Color(0x00173527), center = c, radius = 156 * u), 156 * u, c)
            drawCircle(Color(0x14FFFFFF), 140 * u, c, style = Stroke(3 * u))
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) {
                drawArc(
                    Brush.linearGradient(listOf(Color(0xFFF7E3A1), Color(0xFFD9A441)), start = Offset(20 * u, 0f), end = Offset(300 * u, 0f)),
                    -90f, 360f * p, false, Offset(20 * u, 20 * u), Size(280 * u, 280 * u), style = Stroke(3.5f * u, cap = StrokeCap.Round),
                )
            }
            val a = (-90 + 360 * p) * PI.toFloat() / 180
            drawCircle(Color(0xFFF7E3A1), 5 * u, Offset(c.x + 140 * u * cos(a), c.y + 140 * u * sin(a)))
            drawOval(Color(0xFF2C5A35), Offset(80 * u, 237 * u), Size(160 * u, 30 * u))
            drawOval(Color(0xFF3F7448), Offset(94 * u, 237 * u), Size(132 * u, 22 * u))
        }
        if (tree != null) {
            TreePicture(
                tree.species, tree.stage(), tree.variant,
                Modifier.offset(70 * s, 34 * s).size(180 * s, 216 * s), resting = ForestStore.resting(), sway = true,
            )
        } else {
            Icon(Icons.Rounded.Spa, null, tint = Color(0xFFF3C6D6), modifier = Modifier.offset(124 * s, 160 * s).size(72 * s))
        }
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 320f
            val t = clock.value
            val rnd = Random(5)
            val g = Path()
            repeat(22) {
                val x = (96 + rnd.nextFloat() * 128) * u
                val y = (240 + rnd.nextFloat() * 18) * u
                val h = (4 + rnd.nextFloat() * 5) * u
                g.moveTo(x, y); g.quadraticTo(x - 1.5f * u, y - h * .6f, x - 3 * u, y - h)
                g.moveTo(x, y); g.quadraticTo(x + 2 * u, y - h * .5f, x + 4 * u, y - h * .8f)
            }
            drawPath(g, Color(0xFF6E9E5A), style = Stroke(1.2f * u, cap = StrokeCap.Round))
            repeat(14) {
                val x = (70 + rnd.nextFloat() * 180) * u
                val y0 = (120 + rnd.nextFloat() * 140) * u
                val dur = 6 + rnd.nextFloat() * 5
                val r = (1 + rnd.nextFloat() * 1.2f) * u
                val f = (t / dur + it * .13f) % 1f
                drawCircle(Color(0xFFF3E7B0), r, Offset(x, y0 - f * 90 * u), alpha = .8f * sin(PI.toFloat() * f))
            }
        }
    }
}

// ------------------------------------------------------------------ Breathe
/** Breathe: a lotus that opens as you breathe in and closes as you breathe out; a soft buzz at each change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BreatheScreen() {
    val ctx = LocalContext.current
    val c = forestColors()
    BarIcons(light = c.light)
    BackHandler { CalmSession.pause(ctx) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var lastPhase = -1
        while (true) {
            now = System.currentTimeMillis()
            if (CalmSession.checkEnd(ctx)) break
            val ph = breath(CalmSession.elapsedMs(now), CalmSession.pattern).phase
            if (lastPhase != -1 && ph != lastPhase) Buzz.tick(ctx)
            lastPhase = ph
            delay(100)
        }
    }
    val b = breath(CalmSession.elapsedMs(now), CalmSession.pattern)
    val rounds = ((CalmSession.totalMs + CalmSession.pattern.cycleMs - 1) / CalmSession.pattern.cycleMs).toInt().coerceIn(1, 12)
    val done = (CalmSession.elapsedMs(now) / CalmSession.pattern.cycleMs).toInt()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(if (c.light) listOf(Color(0xFFEEF5E8), Color(0xFFF6F2E7)) else listOf(Color(0xFF1A2A1E), Color(0xFF141A13))))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ForestTopBar("Breathe", onBack = { CalmSession.pause(ctx) }) {
            Text("${clock(CalmSession.leftMs(now))} left", fontSize = 13.5.sp, color = c.muted, modifier = Modifier.padding(end = 18.dp))
        }
        Spacer(Modifier.weight(.5f))
        Lotus(Modifier.widthIn(max = 300.dp).fillMaxWidth(.78f).aspectRatio(1f), c.light)
        TitleText(b.label, 32.sp, if (c.light) Color(0xFF2F5D3A) else Color(0xFFBFE0B0), FontWeight.Light, Modifier.padding(top = 4.dp), italic = true)
        TitleText("${b.count}", 54.sp, if (c.light) Color(0xFF3F7A4B) else Color(0xFF9CCB8A), FontWeight.Light)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            val scaled = (done * rounds / ((CalmSession.totalMs / CalmSession.pattern.cycleMs).toInt().coerceAtLeast(1))).coerceAtMost(rounds)
            repeat(rounds) { i -> Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < scaled) c.moss else c.line)) }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in BreathPattern.entries) FChip(p.title, CalmSession.pattern == p) { CalmSession.changePattern(p) }
            }
            Note("The lotus opens as you breathe in and closes as you breathe out. A soft buzz marks each change — eyes can stay closed.", Icons.Rounded.Eco, Modifier.padding(top = 12.dp))
            var confirmStop by remember { mutableStateOf(false) }
            Text(
                "Stop early", fontSize = 14.sp, color = c.muted,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { confirmStop = true }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (confirmStop) StopDialog(onDismiss = { confirmStop = false })
        }
    }
}

private class Breath(val phase: Int, val open: Float, val label: String, val count: Int)

private fun breath(elapsed: Long, p: BreathPattern): Breath {
    var e = elapsed % p.cycleMs
    for (i in 0 until 4) {
        val len = p.secs[i] * 1000L
        if (len == 0L) continue
        if (e < len) {
            val x = e.toFloat() / len
            val ease = x * x * (3 - 2 * x)
            val open = when (i) {
                0 -> ease
                1 -> 1f
                2 -> 1f - ease
                else -> 0f
            }
            val label = when (i) {
                0 -> "Breathe in…"
                2 -> "Breathe out…"
                else -> "Hold…"
            }
            return Breath(i, open, label, ((len - e + 999) / 1000).toInt())
        }
        e -= len
    }
    return Breath(0, 0f, "Breathe in…", p.secs[0])
}

private val BackPetal = Path().apply { moveTo(0f, 0f); cubicTo(-14f, -22f, -14f, -58f, 0f, -84f); cubicTo(14f, -58f, 14f, -22f, 0f, 0f); close() }
private val FrontPetal = Path().apply { moveTo(0f, 0f); cubicTo(-18f, -18f, -20f, -50f, 0f, -72f); cubicTo(20f, -50f, 18f, -18f, 0f, 0f); close() }

@Composable
private fun Lotus(modifier: Modifier, light: Boolean) {
    val clock = rememberClock()
    Canvas(modifier) {
        clock.value // redraw every frame
        val open = breath(CalmSession.elapsedMs(), CalmSession.pattern).open
        val u = size.width / 300f
        scale(u, u, pivot = Offset.Zero) { drawLotus(open, light) }
    }
}

private fun DrawScope.drawLotus(open: Float, light: Boolean) {
    val c = Offset(150f, 150f)
    drawCircle(Brush.radialGradient(listOf(if (light) Color(0xE6FFFFFF) else Color(0x332F5D3A), Color(0x00DDEFD5)), center = c, radius = 146f), 146f, c)
    drawCircle(Color(0xFFB9D4AE), 110f + 22f * open, c, alpha = .6f, style = Stroke(1.5f))
    drawCircle(Color(0xFFB9D4AE), 92f + 20f * open, c, alpha = .5f, style = Stroke(1.2f))
    drawOval(Color(0xFF7FB46C), Offset(54f, 192f), Size(192f, 44f), alpha = .55f)
    drawOval(Color(0xFF5E9A54), Offset(72f, 194f), Size(156f, 32f), alpha = .55f)
    val spread = .45f + .55f * open
    val grow = 1f + .06f * open
    scale(grow, grow, pivot = Offset(150f, 196f)) {
        val back = Brush.verticalGradient(listOf(Color(0xFFEE8FB0), Color(0xFFFBE3EA)), startY = -84f, endY = 0f)
        for (a in -80..80 step 20) {
            rotate(a * spread, pivot = Offset(150f, 196f)) {
                translateTo(150f, 196f) { drawPath(BackPetal, back, alpha = .9f) }
            }
        }
        val front = Brush.verticalGradient(listOf(Color(0xFFF4A9C2), Color(0xFFFFF1F5)), startY = -72f, endY = 0f)
        for (a in intArrayOf(-56, -28, 0, 28, 56)) {
            rotate(a * spread, pivot = Offset(150f, 198f)) {
                translateTo(150f, 198f) { drawPath(FrontPetal, front) }
            }
        }
        drawOval(Color(0xFFE4D46A), Offset(136f, 169f), Size(28f, 14f))
        for ((x, y) in listOf(144f to 175f, 150f to 173f, 156f to 176f)) drawCircle(Color(0xFFB7A63A), 1.4f, Offset(x, y))
    }
}

private inline fun DrawScope.translateTo(x: Float, y: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(x, y)
    block()
    drawContext.transform.translate(-x, -y)
}

// ------------------------------------------------------------------ Paused
/** Gentle, not strict: nothing withers while calm time waits. */
@Composable
internal fun PausedScreen(onHide: () -> Unit) {
    val ctx = LocalContext.current
    BarIcons(light = false)
    BackHandler(onBack = onHide)
    val tree = CalmSession.treeId?.let { ForestStore.tree(it) }
    val kept = CalmSession.elapsedMs()
    val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(CalmSession.pausedAtMs))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2C3B55), Color(0xFF5B5470), Color(0xFFC99A7C))))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(Modifier.padding(top = 56.dp).size(66.dp).clip(CircleShape).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Pause, null, tint = Color(0xFFF6EFE6), modifier = Modifier.size(30.dp))
        }
        TitleText("Paused", 28.sp, Color(0xFFF6EFE6), modifier = Modifier.padding(top = 16.dp))
        Text(
            "You left SyncUp at $at.\n${if (tree != null) "Your ${tree.stageName().lowercase()} is waiting" else "Your calm time is waiting"} — no harm done.",
            fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFF2E2D4), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 30.dp, end = 30.dp),
        )
        if (tree != null) {
            TreePicture(tree.species, tree.stage(), tree.variant, Modifier.padding(top = 18.dp).size(170.dp, 204.dp), sway = true)
        } else {
            Icon(Icons.Rounded.Spa, null, tint = Color(0xFFFBE3C8), modifier = Modifier.padding(top = 30.dp).size(110.dp))
        }
        Text(if (kept < 60_000) "${kept / 1000} s kept" else "${kept / 60_000} min ${kept / 1000 % 60} s kept", fontSize = 13.sp, color = Color(0xFFFBE3C8), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f).height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 26.dp, top = 24.dp)) {
            LineButton("End here", Modifier.weight(1f), color = Color(0xFFE7F3E2), border = Color(0x59E7F3E2)) { CalmSession.stop(ctx) }
            MossButton("Go on · ${clock(CalmSession.leftMs())} left", Modifier.weight(1.4f), container = Color(0xFFF6E7C8), content = Color(0xFF3B2F45)) { CalmSession.resume(ctx) }
        }
    }
}

// ------------------------------------------------------------------ It grew
@Composable
internal fun ResultScreen(r: CalmResult, onAgain: () -> Unit, onDone: () -> Unit) {
    val c = forestColors()
    BarIcons(light = c.light)
    BackHandler(onBack = onDone)
    val tree = r.treeId?.let { ForestStore.tree(it) }
    val grew = r.before != null && r.after != null && r.before != r.after
    val now = System.currentTimeMillis()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(if (c.light) listOf(Color(0xFFFBF4E2), Color(0xFFF6F2E7)) else listOf(Color(0xFF1E2418), Color(0xFF141A13))))
            .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()),
    ) {
        Text("+${r.minutes} calm minute${if (r.minutes == 1) "" else "s"}", fontSize = 13.5.sp, color = c.muted, modifier = Modifier.padding(top = 26.dp))
        TitleText(
            when {
                tree == null -> "Calm time done"
                grew -> "Your ${tree.label.lowercase()} grew"
                else -> "Your ${tree.label.lowercase()} is growing"
            },
            30.sp, modifier = Modifier.padding(top = 4.dp),
        )
        GrewScene(tree, if (grew) r.before else null, r.after, Modifier.padding(top = 10.dp).widthIn(max = 380.dp).fillMaxWidth().aspectRatio(358f / 300f))
        if (tree != null && r.after != null) {
            TitleText(
                if (grew) "${stageWord(r.before!!)} → ${stageWord(r.after)}" else "${stageWord(r.after)} · ${tree.nextText(now).replaceFirstChar { it.lowercase() }}",
                19.sp, c.moss, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Planted ${FDays.dateText(tree.plantedMs)} · grows in ${growDaysText(tree.growDays)} · ${minutesText(tree.care)} of care",
                fontSize = 13.sp, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp, start = 20.dp, end = 20.dp),
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
                for (s in Stage.entries) {
                    val current = s == r.after
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TreePicture(tree.species, s, tree.variant, Modifier.size(46.dp, 50.dp), alpha = if (s.ordinal > r.after.ordinal) .4f else 1f)
                        Text(if (s == Stage.YOUNG) "Young" else if (s == Stage.GROWN) "Grown" else s.title, fontSize = 11.sp, color = if (current) c.moss else c.muted, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        } else {
            val today = ForestStore.minutesOn(FDays.today())
            Text("$today calm minutes today · goal ${ForestStore.goalMinutes}", fontSize = 14.sp, color = c.ink2)
        }
        for (s in r.found) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.soft).padding(10.dp),
            ) {
                TreePicture(s, Stage.GROWN, 7, Modifier.size(40.dp, 44.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("A new seed: ${s.title}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink)
                    Text("Found ${s.findHint} — it's in your seed packets", fontSize = 12.sp, color = c.muted)
                }
            }
        }
        Spacer(Modifier.weight(1f).height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp, top = 20.dp)) {
            LineButton("Calm again", Modifier.weight(1f), onClick = onAgain)
            MossButton("See the forest", Modifier.weight(1f), onClick = onDone)
        }
    }
}

private fun stageWord(s: Stage) = if (s == Stage.YOUNG) "Young tree" else s.title

/** A warm glow, a few sparkles, butterflies arriving: the old stage faded on the left, the new one swaying. */
@Composable
private fun GrewScene(tree: Tree?, before: Stage?, after: Stage?, modifier: Modifier) {
    val clock = rememberClock()
    BoxWithConstraints(modifier) {
        val s = maxWidth / 358f
        val full = Rect(0f, 0f, 200f, 240f)
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 358f
            val cx = if (before != null) 230 * u else 179 * u
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4D1), Color(0x00FFF4D1)), center = Offset(cx, 150 * u), radius = 150 * u), 150 * u, Offset(cx, 150 * u))
            val isl = Brush.verticalGradient(listOf(Color(0xFF9FCB72), Color(0xFF6E9F4E)), startY = 250 * u, endY = 286 * u)
            if (before != null) {
                drawOval(isl, Offset(34 * u, 250 * u), Size(112 * u, 24 * u), alpha = .7f)
                val arrow = Path().apply {
                    moveTo(140 * u, 190 * u); quadraticTo(154 * u, 182 * u, 168 * u, 190 * u)
                    moveTo(164 * u, 184 * u); lineTo(170 * u, 190 * u); lineTo(162 * u, 193 * u)
                }
                drawPath(arrow, Color(0xFFB9AE8E), style = Stroke(2 * u, cap = StrokeCap.Round))
            }
            drawOval(isl, Offset(cx - 96 * u, 250 * u), Size(192 * u, 36 * u))
        }
        if (tree != null && after != null) {
            if (before != null) {
                val w = 96f
                TreePicture(tree.species, before, tree.variant, Modifier.offset((90 - w / 2) * s, (262 - w * 1.2f * 226 / 240) * s).size(w * s, w * 1.2f * s), crop = full, alpha = .45f)
            }
            val fx = if (before != null) 232f else 179f
            if (after.ordinal <= Stage.SAPLING.ordinal) {
                // A seed, sprout or sapling: closer up, still standing on the island.
                val crop = cropFor(after)
                val w = 170f
                val k = w / crop.width
                TreePicture(tree.species, after, tree.variant, Modifier.offset((fx - w / 2) * s, (268 - (226 - crop.top) * k) * s).size(w * s, crop.height * k * s), sway = true)
            } else {
                val w = 210f
                TreePicture(tree.species, after, tree.variant, Modifier.offset((fx - w / 2) * s, (268 - w * 1.2f * 226 / 240) * s).size(w * s, w * 1.2f * s), crop = full, sway = true)
            }
        } else {
            Icon(Icons.Rounded.Spa, null, tint = Color(0xFFE7709A), modifier = Modifier.offset(139 * s, 150 * s).size(80 * s))
        }
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 358f
            val t = clock.value
            listOf(Triple(178f, 70f, 9f), Triple(296f, 92f, 7f), Triple(262f, 40f, 6f), Triple(318f, 170f, 8f), Triple(160f, 140f, 5f)).forEachIndexed { i, (x, y, k) ->
                val a = (sin(((t - i * .45f) / 2.4f) * PI.toFloat()).let { it * it })
                sparkle(x * u, y * u, k * u, a)
            }
            val a1 = t * .7f
            butterfly(0, (300 + 18 * sin(a1)) * u, (128 + 12 * sin(a1 * 1.4f)) * u, 26 * u, t, 0f)
            butterfly(2, (163 + 14 * sin(a1 * .9f + 2)) * u, (104 + 10 * sin(a1 * 1.3f + 1)) * u, 18 * u, t, .4f)
        }
    }
}

private fun DrawScope.sparkle(x: Float, y: Float, s: Float, alpha: Float) {
    val p = Path().apply {
        moveTo(x, y - s); quadraticTo(x, y, x + s, y); quadraticTo(x, y, x, y + s); quadraticTo(x, y, x - s, y); quadraticTo(x, y, x, y - s); close()
    }
    drawPath(p, Color(0xFFF6D77A), alpha = alpha.coerceIn(0f, 1f))
}

