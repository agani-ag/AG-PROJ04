package com.agani.syncup.wellbeing

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.forest.Buzz
import com.agani.syncup.forest.FDays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class BreakSettings(
    val setUp: Boolean = false,
    val eyeOn: Boolean = true,
    val eyeEvery: Int = 20,
    val moveOn: Boolean = true,
    val moveEvery: Int = 60,
    /** Bit 0 = Monday … bit 6 = Sunday. Mon–Sat to start. */
    val days: Int = 0b0111111,
    val start: Int = 9 * 60 + 30,
    val end: Int = 18 * 60 + 30,
)

/**
 * Tools → Breaks: an eye break (look far away for 20 s — the 20-20-20 rule) and a move break (2 minutes
 * of simple stretches), each every N minutes inside the days and hours chosen — only while the phone is
 * in use (a phone lying idle means you're already away), never during calm time. Off until set up.
 * It's about this phone's use, so it stays on this phone (not synced).
 */
object Breaks {
    private lateinit var app: Context

    var version by androidx.compose.runtime.mutableLongStateOf(0L)
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
    }

    private fun prefs() = app.getSharedPreferences("breaks", Context.MODE_PRIVATE)

    internal val settings: BreakSettings
        get() = prefs().let {
            BreakSettings(
                it.getBoolean("set_up", false), it.getBoolean("eye_on", true), it.getInt("eye_every", 20),
                it.getBoolean("move_on", true), it.getInt("move_every", 60), it.getInt("days", 0b0111111),
                it.getInt("start", 9 * 60 + 30), it.getInt("end", 18 * 60 + 30),
            )
        }

    internal fun changeSettings(s: BreakSettings) {
        val old = settings
        val now = System.currentTimeMillis()
        // A break switched on counts from now — the first one comes after its full gap.
        prefs().edit().apply {
            if (s.eyeOn && (!old.eyeOn || !old.setUp)) putLong("last_eye", now)
            if (s.moveOn && (!old.moveOn || !old.setUp)) putLong("last_move", now)
        }.apply()
        prefs().edit().putBoolean("set_up", s.setUp).putBoolean("eye_on", s.eyeOn).putInt("eye_every", s.eyeEvery)
            .putBoolean("move_on", s.moveOn).putInt("move_every", s.moveEvery).putInt("days", s.days)
            .putInt("start", s.start).putInt("end", s.end).apply()
        version++
        schedule(app)
    }

    /** Breaks shown and taken today. */
    internal fun shownToday(): Int = counts().first

    internal fun takenToday(): Int = counts().second

    private fun counts(): Pair<Int, Int> {
        val p = prefs()
        return if (p.getInt("day", 0) == FDays.today()) p.getInt("shown", 0) to p.getInt("taken", 0) else 0 to 0
    }

    private fun bump(shown: Int, taken: Int) {
        val (s, t) = counts()
        prefs().edit().putInt("day", FDays.today()).putInt("shown", s + shown).putInt("taken", t + taken).apply()
        version++
    }

    /** A break was done in SyncUp (from the notification's Start, or tried from the page). */
    internal fun taken() {
        bump(0, 1)
        Wellbeing.cancelNotification(app, Wellbeing.ID_BREAK)
    }

    private fun allowedDay(ms: Long, s: BreakSettings) = s.days and (1 shl mondayIndex(FDays.of(ms))) != 0

    /** The next break and its kind ("eye" / "move"), or null when none is on. */
    internal fun next(now: Long = System.currentTimeMillis()): Pair<Long, String>? {
        val s = settings
        if (!s.setUp || (!s.eyeOn && !s.moveOn) || s.days == 0 || s.end <= s.start) return null
        val p = prefs()
        val floor = maxOf(now + 60_000L, p.getLong("paused_until", 0L))
        val candidates = buildList {
            if (s.eyeOn) add(maxOf(p.getLong("last_eye", 0L) + s.eyeEvery * 60_000L, floor) to "eye")
            if (s.moveOn) add(maxOf(p.getLong("last_move", 0L) + s.moveEvery * 60_000L, floor) to "move")
        }
        var (at, kind) = candidates.minBy { it.first }
        // Into the chosen hours and days.
        repeat(8) {
            val m = Wellbeing.minuteNow(at)
            when {
                !allowedDay(at, s) -> at = Wellbeing.atMinute(at + 24 * 3_600_000L, s.start)
                m < s.start -> at = Wellbeing.atMinute(at, s.start)
                m > s.end -> at = Wellbeing.atMinute(at + 24 * 3_600_000L, s.start)
                else -> return at to kind
            }
        }
        return at to kind
    }

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        if (!::app.isInitialized) init(ctx)
        Wellbeing.cancelAlarm(ctx, Wellbeing.A_BREAK, Wellbeing.REQ_BREAK)
        val (at, _) = next() ?: return
        Wellbeing.alarm(ctx, Wellbeing.A_BREAK, Wellbeing.REQ_BREAK, at)
    }

    /** The alarm: a break is due — shown only when the phone is in use and it isn't calm time. */
    internal fun due(ctx: Context) {
        val s = settings
        val now = System.currentTimeMillis()
        val p = prefs()
        val eyeDue = s.eyeOn && now >= p.getLong("last_eye", 0L) + s.eyeEvery * 60_000L - 90_000L
        val moveDue = s.moveOn && now >= p.getLong("last_move", 0L) + s.moveEvery * 60_000L - 90_000L
        val kind = if (moveDue) "move" else if (eyeDue) "eye" else null
        val m = Wellbeing.minuteNow(now)
        val ok = kind != null && s.setUp && allowedDay(now, s) && m in s.start..s.end && now >= p.getLong("paused_until", 0L)
        // Either way the clock starts again from now (an idle phone already had its break).
        p.edit().apply {
            if (moveDue) putLong("last_move", now).putLong("last_eye", now) else if (eyeDue) putLong("last_eye", now)
        }.apply()
        if (ok && Wellbeing.screenOn(ctx) && !Wellbeing.quietNow(ctx, now)) {
            bump(1, 0)
            val eye = kind == "eye"
            Wellbeing.notify(
                ctx, Wellbeing.CH_BREAKS, Wellbeing.ID_BREAK,
                if (eye) "Eye break" else "Move break",
                if (eye) "Look at something far away for 20 seconds" else "Stand up and stretch — 2 minutes",
                Wellbeing.open(ctx, "BREAKS", Wellbeing.ID_BREAK, kind), 0xFF0F766E,
                listOf(
                    "Start" to Wellbeing.open(ctx, "BREAKS", 0x6E2101, kind),
                    "Skip" to Wellbeing.action(ctx, Wellbeing.A_BREAK_SKIP, 0x6E2102),
                    "Pause 1 h" to Wellbeing.action(ctx, Wellbeing.A_BREAK_PAUSE, 0x6E2103),
                ),
            )
        }
        schedule(ctx)
    }

    internal fun skip(ctx: Context) = Wellbeing.cancelNotification(ctx, Wellbeing.ID_BREAK)

    internal fun pause(ctx: Context) {
        prefs().edit().putLong("paused_until", System.currentTimeMillis() + 3_600_000L).apply()
        Wellbeing.cancelNotification(ctx, Wellbeing.ID_BREAK)
        version++
        schedule(ctx)
    }

    internal fun pausedUntil(): Long = prefs().getLong("paused_until", 0L)
}

// ============================================================================ screens
@Composable
fun BreaksScreen(onBack: () -> Unit, start: String? = null, onStartUsed: () -> Unit = {}) {
    val ctx = LocalContext.current
    remember { Breaks.init(ctx) }
    var now by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(start) {
        if (start == "eye" || start == "move") now = start
        if (start != null) onStartUsed()
    }
    BackHandler(enabled = now != null) { now = null }
    when (now) {
        "eye" -> EyeBreak(onDone = { taken ->
            if (taken) Breaks.taken()
            now = null
        })
        "move" -> MoveBreak(onDone = { taken ->
            if (taken) Breaks.taken()
            now = null
        })
        else -> BreaksHome(onBack, onTry = { now = it })
    }
}

@Composable
private fun BreaksHome(onBack: () -> Unit, onTry: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = breaksTint()
    @Suppress("UNUSED_VARIABLE") val v = Breaks.version
    val s = Breaks.settings
    var time by remember { mutableStateOf<String?>(null) }
    val next = Breaks.next()
    WellPage("Breaks", onBack) {
        WCard(Modifier.padding(top = 4.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                val shown = Breaks.shownToday()
                val taken = Breaks.takenToday()
                Text(if (shown == 0 && taken == 0) "No breaks yet today" else "$taken of ${maxOf(shown, taken)} breaks taken today", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Text(
                    when {
                        !s.setUp -> "Turn on a break below to start"
                        Breaks.pausedUntil() > System.currentTimeMillis() -> "Paused until ${clockText(Breaks.pausedUntil())}"
                        next != null -> "Next: ${if (next.second == "eye") "eye" else "move"} break at ${clockText(next.first)}${if (FDays.of(next.first) != FDays.today()) " · ${weekdayShort(FDays.of(next.first))}" else ""}"
                        else -> "Both breaks are off"
                    },
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                )
                if (maxOf(shown, taken) > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        repeat(maxOf(shown, taken).coerceAtMost(16)) { i ->
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (i < taken) t.accent else t.container))
                        }
                    }
                }
            }
        }
        BreakKind(Icons.Rounded.Visibility, "Eye break", "Look 6 m away for 20 seconds", s.setUp && s.eyeOn, s.eyeEvery, t,
            onSwitch = { Breaks.changeSettings(s.copy(setUp = true, eyeOn = it, moveOn = if (!s.setUp) false else s.moveOn)) },
            onEvery = { Breaks.changeSettings(s.copy(eyeEvery = it.coerceIn(10, 60))) }, step = 5, onTry = { onTry("eye") })
        BreakKind(Icons.Rounded.Accessibility, "Move break", "Stand up & stretch · 2 min", s.setUp && s.moveOn, s.moveEvery, t,
            onSwitch = { Breaks.changeSettings(s.copy(setUp = true, moveOn = it, eyeOn = if (!s.setUp) false else s.eyeOn)) },
            onEvery = { Breaks.changeSettings(s.copy(moveEvery = it.coerceIn(30, 120))) }, step = 15, onTry = { onTry("move") })
        Lab("When", t.accent)
        DaysPick(s.days, t) { Breaks.changeSettings(s.copy(days = it)) }
        TimeLine("From", s.start, t, { time = "start" })
        TimeLine("To", s.end, t, { time = "end" })
        Hint("Only while you're using the phone — never when it's lying idle. Quiet during Forest calm time.")
    }
    time?.let { which ->
        TimeDialog(if (which == "start") "Breaks from" else "Breaks until", if (which == "start") s.start else s.end, { m ->
            Breaks.changeSettings(if (which == "start") s.copy(start = m) else s.copy(end = m))
            time = null
        }, { time = null })
    }
}

@Composable
private fun BreakKind(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, on: Boolean, every: Int, t: WTint,
    onSwitch: (Boolean) -> Unit, onEvery: (Int) -> Unit, step: Int, onTry: () -> Unit,
) {
    WCard(Modifier.padding(top = 12.dp)) {
        SwitchLine(title, sub, on, t.accent, onSwitch, icon = { TintIcon(icon, t) })
        if (on) PlusMinusLine("Every", "$every min", { onEvery(every - step) }, { onEvery(every + step) })
        Text(
            "Try one now", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = t.accent,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onTry).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** 20 seconds looking far away. Closes by itself with a soft buzz. */
@Composable
private fun EyeBreak(onDone: (taken: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var start by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var left by remember { mutableIntStateOf(20) }
    var frac by remember { mutableStateOf(1f) }
    LaunchedEffect(start) {
        while (true) {
            withFrameMillis { }
            val gone = System.currentTimeMillis() - start
            frac = (1f - gone / 20_000f).coerceIn(0f, 1f)
            left = ((20_000 - gone + 999) / 1000).toInt().coerceAtLeast(0)
            if (gone >= 20_000) {
                Buzz.end(ctx)
                onDone(true)
                break
            }
        }
    }
    com.agani.syncup.forest.BarIcons(light = false)
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F766E), Color(0xFF115E59), Color(0xFF134E4A))))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.Rounded.Close, "Close", tint = Color.White, onClick = { onDone(false) })
                Text("Eye break", fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.height(36.dp))
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 12.dp.toPx()
                    val d = size.minDimension - sw
                    drawArc(Color.White.copy(alpha = .18f), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(d, d), style = Stroke(sw))
                    drawArc(Color(0xFF5EEAD4), -90f, 360f * frac, false, Offset(sw / 2, sw / 2), Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$left", fontSize = 64.sp, fontWeight = FontWeight.Light, color = Color.White)
                    Text("seconds", fontSize = 14.sp, color = Color(0xFFCCFBF1))
                }
            }
            Text("Look at something far away", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 26.dp))
            Text(
                "About 6 metres — out of a window is perfect. Let your eyes relax and blink slowly.",
                fontSize = 14.5.sp, lineHeight = 21.sp, color = Color(0xFFCCFBF1), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
                PillButton("Skip", Color.White.copy(alpha = .14f), Color.White, Modifier.weight(1f)) { onDone(false) }
                PillButton("Done", Color.White, Color(0xFF0F766E), Modifier.weight(1f)) { onDone(true) }
            }
        }
    }
}

@Composable
internal fun PillButton(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(50.dp).clip(RoundedCornerShape(25.dp)).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

private class Move(val title: String, val how: String, val pose: Int)

private val MOVES = listOf(
    Move("Neck rolls", "Let your head roll slowly in a big circle — 5 times one way, 5 the other.", 0),
    Move("Shoulder rolls", "Roll both shoulders back slowly, in big circles — 10 times. Breathe out as they drop.", 1),
    Move("Wrist stretch", "Arm out, palm up. Gently pull your fingers back with the other hand for 10 seconds. Swap hands.", 2),
    Move("Stand & reach", "Stand up, reach both arms high, then lean gently to each side. Breathe.", 3),
)

/** Two minutes, four moves of 30 seconds, each with a drawn figure. */
@Composable
private fun MoveBreak(onDone: (taken: Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = breaksTint()
    var i by rememberSaveable { mutableIntStateOf(0) }
    var start by remember(i) { mutableLongStateOf(System.currentTimeMillis()) }
    var frac by remember(i) { mutableStateOf(0f) }
    LaunchedEffect(i) {
        start = System.currentTimeMillis()
        while (true) {
            withFrameMillis { }
            frac = ((System.currentTimeMillis() - start) / 30_000f).coerceIn(0f, 1f)
            if (frac >= 1f) {
                if (i < MOVES.lastIndex) i++ else onDone(true)
                break
            }
        }
    }
    val m = MOVES[i]
    val swing by rememberInfiniteTransition(label = "move").animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart), label = "swing")
    WellPage("Move break", { onDone(false) }, actions = {
        Text("${i + 1} of ${MOVES.size}", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 14.dp))
    }) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(250.dp).clip(RoundedCornerShape(24.dp)).background(t.container.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(220.dp)) { figure(m.pose, swing, t.accent, Color(0xFF14B8A6)) }
        }
        Text(m.title, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        Text(m.how, fontSize = 14.5.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 6.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.container)) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.accent))
        }
        Text("${((1 - frac) * 30).toInt()} s left", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            MOVES.indices.forEach { k -> Box(Modifier.height(8.dp).width(if (k == i) 22.dp else 8.dp).clip(CircleShape).background(if (k == i) t.accent else t.container)) }
        }
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            PillButton("Skip", cs.surfaceContainerHigh, cs.onSurface, Modifier.weight(1f)) { onDone(false) }
            PillButton(if (i < MOVES.lastIndex) "Next" else "Done", t.accent, Color.White, Modifier.weight(1f)) { if (i < MOVES.lastIndex) i++ else onDone(true) }
        }
    }
}

/** A simple stick figure doing [pose], moving with [p] (0..1 loop). */
private fun DrawScope.figure(pose: Int, p: Float, body: Color, hint: Color) {
    val w = size.width
    val s = w / 220f
    val sw = 7f * s
    val a = (p * 2 * PI).toFloat()
    val head = when (pose) {
        0 -> Offset(110 * s + cos(a) * 9 * s, 46 * s + sin(a) * 5 * s)
        else -> Offset(110 * s, 46 * s)
    }
    val neck = Offset(110 * s, 66 * s)
    val hip = Offset(110 * s, 140 * s)
    val shY = 84 * s + if (pose == 1) sin(a) * 6 * s else 0f
    val shL = Offset(88 * s, shY)
    val shR = Offset(132 * s, shY)
    fun line(x: Offset, y: Offset, c: Color = body) = drawLine(c, x, y, sw, StrokeCap.Round)
    drawCircle(body, 20 * s, head, style = Stroke(sw))
    line(neck, hip)
    line(shL, shR)
    line(hip, Offset(88 * s, 204 * s))
    line(hip, Offset(132 * s, 204 * s))
    when (pose) {
        0 -> { // neck rolls: arms down, a circle around the head
            line(shL, Offset(80 * s, 140 * s)); line(shR, Offset(140 * s, 140 * s))
            drawCircle(hint, 32 * s, Offset(110 * s, 46 * s), style = Stroke(3 * s))
        }
        1 -> { // shoulder rolls: arms down, little circles at the shoulders
            line(shL, Offset(82 * s, 142 * s)); line(shR, Offset(138 * s, 142 * s))
            drawCircle(hint, 13 * s, Offset(72 * s, 80 * s), style = Stroke(3 * s))
            drawCircle(hint, 13 * s, Offset(148 * s, 80 * s), style = Stroke(3 * s))
        }
        2 -> { // wrist stretch: one arm out, the other hand pulling the fingers back
            val reach = Offset(186 * s, 86 * s)
            line(shR, reach)
            line(reach, Offset(194 * s, 66 * s + sin(a) * 4 * s))
            line(shL, Offset(150 * s, 100 * s)); line(Offset(150 * s, 100 * s), Offset(186 * s, 72 * s))
        }
        else -> { // stand & reach: arms high, leaning side to side
            val lean = sin(a) * 18 * s
            line(shL, Offset(70 * s + lean, 12 * s)); line(shR, Offset(150 * s + lean, 12 * s))
        }
    }
}
