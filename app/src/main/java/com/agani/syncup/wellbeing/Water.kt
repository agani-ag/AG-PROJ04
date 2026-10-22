package com.agani.syncup.wellbeing

import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.forest.FDays
import com.agani.syncup.sync.SyncType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** One of the user's cups: a name and how much it holds. */
internal data class Cup(val name: String, val ml: Int)

internal data class Drink(val key: String, val ml: Int, val at: Long, val cup: String)

internal data class WaterSettings(
    val setUp: Boolean = false,
    val goal: Int = 2000,
    val cups: List<Cup> = listOf(Cup("Glass", 250), Cup("Bottle", 500), Cup("Cup", 150)),
    val oz: Boolean = false,
    val remind: Boolean = true,
    val start: Int = 8 * 60,
    val end: Int = 22 * 60,
    val every: Int = 90,
    val stopAtGoal: Boolean = true,
    /** No longer used (Forest's calm time is gone); kept so synced settings keep their shape. */
    val quietCalm: Boolean = true,
) {
    fun json(): JSONObject = JSONObject().put("set_up", setUp).put("goal", goal)
        .put("cups", JSONArray(cups.map { JSONObject().put("name", it.name).put("ml", it.ml) }))
        .put("oz", oz).put("remind", remind).put("start", start).put("end", end).put("every", every)
        .put("stop_at_goal", stopAtGoal).put("quiet_calm", quietCalm)

    companion object {
        fun of(o: JSONObject): WaterSettings {
            val d = WaterSettings()
            val cups = o.optJSONArray("cups")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Cup(it.optString("name"), it.optInt("ml")) } } }
            return WaterSettings(
                o.optBoolean("set_up", d.setUp), o.optInt("goal", d.goal), cups?.takeIf { it.size == 3 } ?: d.cups, o.optBoolean("oz", d.oz),
                o.optBoolean("remind", d.remind), o.optInt("start", d.start), o.optInt("end", d.end), o.optInt("every", d.every),
                o.optBoolean("stop_at_goal", d.stopAtGoal), o.optBoolean("quiet_calm", d.quietCalm),
            )
        }
    }
}

/**
 * Tools → Water: drinks logged with one tap, a daily goal, and reminders inside the hours chosen —
 * every N minutes since the last drink, none once the goal is met, none at night.
 * Answer them from the notification (+ a cup, Later). Synced as kind "water".
 */
object Water {
    private const val KIND = "water"
    internal val items = WellItems("water.json")
    private lateinit var app: Context

    internal val version: Long get() = items.version

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        items.init(app)
        val s = WellSync("water", SyncType.WATER, listOf(KIND), items)
        s.attach(app)
        items.sync = s
        items.onRemote = { schedule(app) }
    }

    internal val sync get() = items.sync

    internal val settings: WaterSettings get() = WaterSettings.of(items.settings(KIND))

    internal fun changeSettings(s: WaterSettings) {
        val old = settings
        // Reminders count from when they were switched on, not from long ago.
        if ((s.setUp && !old.setUp) || (s.remind && !old.remind)) prefs().edit().putLong("since", System.currentTimeMillis()).apply()
        items.putSettings(KIND, s.json())
        schedule(app)
    }

    internal fun drinks(): List<Drink> = items.items(KIND).map { Drink(it.key, it.data.optInt("ml"), it.data.optLong("at", it.updatedMs), it.data.optString("cup")) }

    internal fun drinksOn(day: Int): List<Drink> = drinks().filter { FDays.of(it.at) == day }.sortedByDescending { it.at }

    internal fun totalOn(day: Int): Int = drinksOn(day).sumOf { it.ml }

    fun today(): Int = if (::app.isInitialized) totalOn(FDays.today()) else 0

    internal fun goalMetOn(day: Int): Boolean = totalOn(day) >= settings.goal

    /** Days in a row with the goal met, up to today (or yesterday while today is still going). */
    internal fun streak(): Int {
        var d = FDays.today()
        if (!goalMetOn(d)) d = FDays.add(d, -1)
        var n = 0
        while (goalMetOn(d) && n < 3650) {
            n++
            d = FDays.add(d, -1)
        }
        return n
    }

    internal fun add(ml: Int, cup: String) {
        if (ml <= 0) return
        val now = System.currentTimeMillis()
        items.put(KIND, UUID.randomUUID().toString(), JSONObject().put("ml", ml).put("at", now).put("cup", cup), now)
        Wellbeing.cancelNotification(app, Wellbeing.ID_WATER)
        schedule(app)
    }

    internal fun remove(key: String) {
        items.delete(KIND, key)
        schedule(app)
    }

    internal fun fmt(ml: Int, oz: Boolean = settings.oz): String = if (oz) "${(ml / 29.5735).roundToInt()} oz" else "${ml.grouped()} ml"

    // ------------------------------------------------------------------ reminders
    private fun prefs() = app.getSharedPreferences("water_local", Context.MODE_PRIVATE)

    /** When the next reminder is due (null: none). */
    internal fun nextReminder(now: Long = System.currentTimeMillis()): Long? {
        val s = settings
        if (!s.setUp || !s.remind || s.end <= s.start) return null
        val lastDrink = drinks().maxOfOrNull { it.at } ?: 0L
        val base = maxOf(lastDrink, prefs().getLong("last_remind", 0L), prefs().getLong("since", 0L))
        var next = maxOf(base + s.every * 60_000L, now + 60_000L, prefs().getLong("snooze_until", 0L))
        val today = FDays.today()
        if (s.stopAtGoal && totalOn(today) >= s.goal && FDays.of(next) == today) next = Wellbeing.nextAt(s.start, now)
        val m = Wellbeing.minuteNow(next)
        return when {
            m < s.start -> Wellbeing.atMinute(next, s.start)
            m > s.end -> Wellbeing.atMinute(next + 24 * 3_600_000L, s.start)
            else -> next
        }
    }

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        Wellbeing.cancelAlarm(ctx, Wellbeing.A_WATER_REMIND, Wellbeing.REQ_WATER)
        if (!::app.isInitialized) init(ctx)
        val at = nextReminder() ?: return
        Wellbeing.alarm(ctx, Wellbeing.A_WATER_REMIND, Wellbeing.REQ_WATER, at)
    }

    internal fun remind(ctx: Context) {
        val s = settings
        val now = System.currentTimeMillis()
        prefs().edit().putLong("last_remind", now).apply()
        val total = today()
        val inHours = Wellbeing.minuteNow(now) in s.start..s.end
        val quiet = Wellbeing.quietNow(ctx, now)
        if (s.setUp && s.remind && inHours && !quiet && !(s.stopAtGoal && total >= s.goal)) {
            val left = (s.goal - total).coerceAtLeast(0)
            Wellbeing.notify(
                ctx, Wellbeing.CH_WATER, Wellbeing.ID_WATER, "Time for some water",
                "${fmt(total, s.oz)} of ${fmt(s.goal, s.oz)} today · ${fmt(left, s.oz)} to go",
                Wellbeing.open(ctx, "WATER", Wellbeing.ID_WATER), 0xFF0E7490,
                listOf(
                    "+ ${fmt(s.cups[0].ml, s.oz)}" to Wellbeing.action(ctx, Wellbeing.A_WATER_ADD, 0x6E2001, s.cups[0].name, s.cups[0].ml),
                    "+ ${fmt(s.cups[1].ml, s.oz)}" to Wellbeing.action(ctx, Wellbeing.A_WATER_ADD, 0x6E2002, s.cups[1].name, s.cups[1].ml),
                    "Later" to Wellbeing.action(ctx, Wellbeing.A_WATER_LATER, 0x6E2003),
                ),
            )
        }
        schedule(ctx)
    }

    internal fun addFromNotification(ctx: Context, ml: Int, cup: String?) {
        add(ml, cup ?: "Glass")
        val s = settings
        Wellbeing.notify(
            ctx, Wellbeing.CH_WATER, Wellbeing.ID_WATER, "Added ${fmt(ml, s.oz)} — ${fmt(today(), s.oz)} of ${fmt(s.goal, s.oz)}", null,
            Wellbeing.open(ctx, "WATER", Wellbeing.ID_WATER), 0xFF0E7490, silent = true,
        )
    }

    internal fun later(ctx: Context) {
        prefs().edit().putLong("snooze_until", System.currentTimeMillis() + 30 * 60_000L).apply()
        Wellbeing.cancelNotification(ctx, Wellbeing.ID_WATER)
        schedule(ctx)
    }
}

// ============================================================================ screens
@Composable
fun WaterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Water.init(ctx) }
    @Suppress("UNUSED_VARIABLE") val v = Water.version
    var page by rememberSaveable { mutableStateOf("today") }
    BackHandler(enabled = page != "today") { page = "today" }
    when {
        !Water.settings.setUp -> WaterIntro(onBack)
        page == "history" -> WaterHistory(onBack = { page = "today" })
        else -> WaterToday(onBack, onHistory = { page = "history" })
    }
}

@Composable
private fun WaterIntro(onBack: () -> Unit) {
    val t = waterTint()
    var goal by rememberSaveable { mutableIntStateOf(2000) }
    var remind by rememberSaveable { mutableStateOf(true) }
    WellPage("Water", onBack) {
        Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TintIcon(Icons.Rounded.WaterDrop, t, 96.dp)
            Text("Drink enough water", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 18.dp))
            Text(
                "Set a daily goal — SyncUp fills it up as you log, and reminds you through the day.",
                fontSize = 14.5.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
            )
        }
        Lab("Your daily goal")
        Choices(listOf(1500, 2000, 2500, 3000), goal, { "${it / 1000.0}".removeSuffix(".0") + " L" }, t, { goal = it })
        Hint("About 2 litres is a common goal. Pick what suits you — you can change it any time.")
        WCard(Modifier.padding(top = 8.dp)) {
            SwitchLine("Remind me during the day", "8 AM – 10 PM · every 90 min", remind, { remind = it }, icon = { TintIcon(Icons.Rounded.Notifications, t, 42.dp) })
        }
        Spacer(Modifier.height(24.dp))
        BigButton("Start", { Water.changeSettings(Water.settings.copy(setUp = true, goal = goal, remind = remind)) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaterToday(onBack: () -> Unit, onHistory: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = waterTint()
    val s = Water.settings
    val today = FDays.today()
    val total = Water.totalOn(today)
    val left = (s.goal - total).coerceAtLeast(0)
    var settings by rememberSaveable { mutableStateOf(false) }
    var other by rememberSaveable { mutableStateOf(false) }
    WellPage("Water", onBack, actions = {
        BarIcon(Icons.Rounded.BarChart, "History", onClick = onHistory)
        BarIcon(Icons.Rounded.Settings, "Water settings", onClick = { settings = true })
    }) {
        WCard(Modifier.padding(top = 4.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                WaterGauge(total, s.goal, s.oz, t)
            }
            if (left == 0) {
                Text(
                    "Goal reached — well done!", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.accent, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
                )
            } else {
                val cups = (left + s.cups[0].ml - 1) / s.cups[0].ml
                val name = s.cups[0].name.lowercase()
                Text(
                    "${Water.fmt(left)} to go · about $cups ${if (cups == 1) name else if (name.endsWith("s")) "${name}es" else "${name}s"}",
                    fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
                )
            }
        }
        if (left == 0) {
            Spacer(Modifier.height(12.dp))
            Stats("Streak" to "${Water.streak()} day${if (Water.streak() == 1) "" else "s"}", "This week" to "${(0..6).count { Water.goalMetOn(FDays.add(today, -it)) }} of 7", "Reminders" to "Done")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            s.cups.forEachIndexed { i, c ->
                Column(
                    Modifier.weight(1f).appCard(CardShape).clickable { Water.add(c.ml, c.name) }.padding(top = 12.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TintIcon(cupIcon(i), t, 42.dp)
                    Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(top = 6.dp))
                    Text("+ ${Water.fmt(c.ml)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        Text(
            "Other amount", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 4.dp).clip(CircleShape).clickable { other = true }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        val next = Water.nextReminder()
        WCard(Modifier.padding(top = 4.dp)) {
            SwitchLine(
                if (s.remind && next != null) "Next reminder ${if (FDays.of(next) != today) "tomorrow " else ""}${clockText(next)}" else "Reminders off",
                "Every ${s.every} min · ${timeText(s.start)} – ${timeText(s.end)}",
                s.remind, { Water.changeSettings(s.copy(remind = it)) },
                icon = { TintIcon(Icons.Rounded.Notifications, t, 42.dp) },
            )
        }
        val drinks = Water.drinksOn(today)
        if (drinks.isNotEmpty()) {
            Lab("Today")
            WCard {
                drinks.forEachIndexed { i, d ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp).background(cs.outlineVariant.copy(alpha = .6f)))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                        TintIcon(cupIcon(s.cups.indexOfFirst { it.name == d.cup }), t, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.cup.ifBlank { "Water" }, fontSize = 14.5.sp, color = cs.onSurface)
                            Text(clockText(d.at), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Text(Water.fmt(d.ml), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                        BarIcon(Icons.Rounded.Close, "Undo ${Water.fmt(d.ml)}", tint = cs.onSurfaceVariant, onClick = { Water.remove(d.key) })
                    }
                }
            }
        }
    }
    if (other) OtherAmount(onDone = { ml ->
        other = false
        Water.add(ml, "Other")
    }, onDismiss = { other = false })
    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLowest) {
            WaterSettingsSheet()
        }
    }
}

private fun cupIcon(i: Int): ImageVector = when (i) {
    0 -> Icons.Rounded.LocalDrink
    1 -> Icons.Rounded.SportsBar
    2 -> Icons.Rounded.Coffee
    else -> Icons.Rounded.WaterDrop
}

/** The circle that fills like a glass, with a gentle moving wave on top. */
@Composable
private fun WaterGauge(ml: Int, goal: Int, oz: Boolean, t: WTint) {
    val p by animateFloatAsState((ml / goal.toFloat()).coerceIn(0f, 1f), tween(700), label = "water")
    val wave by rememberInfiniteTransition(label = "wave").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(196.dp)) {
            val r = size.minDimension / 2
            drawCircle(t.accent.copy(alpha = .25f), r - 2.dp.toPx(), style = Stroke(3.5.dp.toPx()))
            val inner = r - 7.dp.toPx()
            drawCircle(t.container.copy(alpha = .55f), inner)
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(size.width / 2 - inner, size.height / 2 - inner), androidx.compose.ui.geometry.Size(inner * 2, inner * 2))) }
            clipPath(circle) {
                val level = size.height * (1 - p) + (if (p >= 1f) -8f else 0f)
                fun wavePath(shift: Float, amp: Float, lift: Float) = Path().apply {
                    moveTo(0f, level + lift)
                    var x = 0f
                    while (x <= size.width) {
                        lineTo(x, level + lift + amp * sin(x / size.width * 2 * PI.toFloat() * 1.5f + shift))
                        x += 4f
                    }
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                if (p > 0f) {
                    drawPath(wavePath(wave + 1.6f, 7f, 5f), Color(0xFF7DD3FC).copy(alpha = .55f))
                    drawPath(wavePath(wave, 8f, 0f), Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0369A1)), startY = level, endY = size.height))
                }
            }
        }
        // White once the water reaches each line (the number sits mid-circle, the small line just below).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (oz) "${(ml / 29.5735).roundToInt()}" else ml.grouped(), fontSize = 42.sp, fontWeight = FontWeight.Medium,
                color = if (p > .58f) Color.White else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "of ${Water.fmt(goal, oz)} · ${(ml * 100f / goal).roundToInt()}%", fontSize = 14.sp,
                color = if (p > .4f) Color.White.copy(alpha = .9f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OtherAmount(onDone: (Int) -> Unit, onDismiss: () -> Unit) {
    var ml by rememberSaveable { mutableIntStateOf(300) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Other amount") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                RoundButton("−", "Less", { ml = (ml - 50).coerceAtLeast(50) })
                Text(Water.fmt(ml), fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(120.dp))
                RoundButton("+", "More", { ml = (ml + 50).coerceAtMost(2000) })
            }
        },
        confirmButton = { TextButton(onClick = { onDone(ml) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WaterSettingsSheet() {
    val t = waterTint()
    val s = Water.settings
    var time by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text("Water settings", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
        SheetLab("Daily goal")
        PlusMinusLine("Goal", Water.fmt(s.goal), { Water.changeSettings(s.copy(goal = (s.goal - 100).coerceAtLeast(500))) }, { Water.changeSettings(s.copy(goal = (s.goal + 100).coerceAtMost(6000))) })
        Choices(listOf(1500, 2000, 2500, 3000), s.goal, { "${it / 1000.0}".removeSuffix(".0") + " L" }, t, { Water.changeSettings(s.copy(goal = it)) }, Modifier.padding(top = 4.dp))
        SheetLab("Units")
        Choices(listOf(false, true), s.oz, { if (it) "oz" else "ml" }, t, { Water.changeSettings(s.copy(oz = it)) })
        SheetLab("Your cups")
        s.cups.forEachIndexed { i, c ->
            PlusMinusLine(c.name, Water.fmt(c.ml), {
                Water.changeSettings(s.copy(cups = s.cups.mapIndexed { j, x -> if (j == i) x.copy(ml = (x.ml - 25).coerceAtLeast(50)) else x }))
            }, {
                Water.changeSettings(s.copy(cups = s.cups.mapIndexed { j, x -> if (j == i) x.copy(ml = (x.ml + 25).coerceAtMost(2000)) else x }))
            })
        }
        SheetLab("Reminders")
        SwitchLine("Remind me to drink", "${timeText(s.start)} – ${timeText(s.end)} · every ${s.every} min", s.remind, { Water.changeSettings(s.copy(remind = it)) })
        if (s.remind) {
            TimeLine("From", s.start, { time = "start" })
            TimeLine("To", s.end, { time = "end" })
            PlusMinusLine("Every", "${s.every} min", { Water.changeSettings(s.copy(every = (s.every - 15).coerceAtLeast(30))) }, { Water.changeSettings(s.copy(every = (s.every + 15).coerceAtMost(240))) })
            SwitchLine("Stop once the goal is reached", null, s.stopAtGoal, { Water.changeSettings(s.copy(stopAtGoal = it)) })
        }
    }
    time?.let { which ->
        TimeDialog(if (which == "start") "Reminders from" else "Reminders until", if (which == "start") s.start else s.end, { m ->
            Water.changeSettings(if (which == "start") s.copy(start = m) else s.copy(end = m))
            time = null
        }, { time = null })
    }
}

@Composable
private fun WaterHistory(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = waterTint()
    val s = Water.settings
    val today = FDays.today()
    val days = (6 downTo 0).map { FDays.add(today, -it) }
    val totals = days.map { Water.totalOn(it) }
    val all = (0 until 60).map { Water.totalOn(FDays.add(today, -it)) }.filter { it > 0 }
    WellPage("History", onBack) {
        Lab("Last 7 days")
        WCard {
            Bars(totals.map { it.toFloat() }, days.map { weekdayLetter(it) }, s.goal.toFloat(), t.accent, t.accent.copy(alpha = .3f), "Goal ${Water.fmt(s.goal)}", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp))
        }
        Spacer(Modifier.height(12.dp))
        Stats(
            "Average" to if (all.isEmpty()) "—" else Water.fmt(all.average().roundToInt()),
            "Streak" to "${Water.streak()} day${if (Water.streak() == 1) "" else "s"}",
            "Best" to if (all.isEmpty()) "—" else Water.fmt(all.max()),
        )
        Lab(monthTitle())
        WCard {
            MonthDots(today, t, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) { d ->
                val tot = Water.totalOn(d)
                when {
                    tot >= s.goal -> t.accent to cs.surfaceContainerLowest
                    tot >= s.goal * .6 -> t.container to t.accent
                    else -> null
                }
            }
        }
        Hint("Full drop = goal met · light = close.", center = true)
    }
}

/** This month as a Monday-first grid of round days; [style] gives a day its fill and text colour (null = plain). */
@Composable
internal fun MonthDots(today: Int, tint: WTint, modifier: Modifier = Modifier.padding(horizontal = 20.dp), style: (Int) -> Pair<Color, Color>?) {
    val cs = MaterialTheme.colorScheme
    val first = today / 100 * 100 + 1
    val lead = mondayIndex(first)
    val cells = (0 until lead).map { 0 } + (first..today).toList()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, fontSize = 11.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                week.forEach { d ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                        if (d != 0) {
                            val st = style(d)
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(st?.first ?: cs.surfaceContainer)
                                    .then(if (d == today) Modifier.border(2.dp, tint.accent, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) { Text("${d % 100}", fontSize = 11.5.sp, color = st?.second ?: cs.onSurfaceVariant) }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun monthTitle(): String = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(java.util.Date())
