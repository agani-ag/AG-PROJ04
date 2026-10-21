package com.agani.syncup.wellbeing

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.NoFood
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.forest.CalmSession
import com.agani.syncup.forest.FDays
import com.agani.syncup.sync.SyncType
import org.json.JSONObject
import java.util.UUID

internal data class Habit(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val icon: String = "star",
    val color: Long = 0xFFDB2777,
    /** "daily", "days" (only on [days]) or "week" ([perWeek] times a week). */
    val freq: String = "daily",
    val days: Int = 0b1111111,
    val perWeek: Int = 3,
    /** Times a day ("Pray 5 times"). */
    val times: Int = 1,
    /** Reminder minute of day, -1 = none. */
    val remind: Int = -1,
    val paused: Boolean = false,
    val archived: Boolean = false,
    val created: Long = System.currentTimeMillis(),
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("icon", icon).put("color", color).put("freq", freq).put("days", days)
        .put("per_week", perWeek).put("times", times).put("remind", remind).put("paused", paused).put("archived", archived).put("created", created)

    fun dueOn(day: Int): Boolean = !paused && when (freq) {
        "days" -> days and (1 shl mondayIndex(day)) != 0
        else -> true
    }

    fun schedule(): String = buildString {
        append(
            when (freq) {
                "days" -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
                "week" -> "$perWeek times a week"
                else -> if (times > 1) "$times times a day" else "Every day"
            },
        )
        if (freq != "daily" && times > 1) append(" · $times times")
        if (remind >= 0) append(" · ${timeText(remind)}")
    }

    companion object {
        fun of(id: String, o: JSONObject) = Habit(
            id, o.optString("name"), o.optString("icon", "star"), o.optLong("color", 0xFFDB2777), o.optString("freq", "daily"), o.optInt("days", 0b1111111),
            o.optInt("per_week", 3), o.optInt("times", 1).coerceAtLeast(1), o.optInt("remind", -1), o.optBoolean("paused"), o.optBoolean("archived"), o.optLong("created"),
        )
    }
}

internal val HABIT_ICONS: List<Pair<String, ImageVector>> = listOf(
    "walk" to Icons.AutoMirrored.Rounded.DirectionsWalk,
    "read" to Icons.AutoMirrored.Rounded.MenuBook,
    "pray" to Icons.Rounded.SelfImprovement,
    "vitamins" to Icons.Rounded.Medication,
    "nosugar" to Icons.Rounded.NoFood,
    "call" to Icons.Rounded.Call,
    "journal" to Icons.Rounded.EditNote,
    "water" to Icons.Rounded.WaterDrop,
    "run" to Icons.AutoMirrored.Rounded.DirectionsRun,
    "sleep" to Icons.Rounded.Bedtime,
    "heart" to Icons.Rounded.Favorite,
    "star" to Icons.Rounded.Star,
)

internal fun habitIcon(key: String): ImageVector = HABIT_ICONS.firstOrNull { it.first == key }?.second ?: Icons.Rounded.Star

internal val HABIT_COLORS = listOf(0xFFDB2777, 0xFF7C3AED, 0xFF2563EB, 0xFF0D9488, 0xFF65A30D, 0xFFD97706, 0xFFDC2626)

/** Ready-made habits to start from. */
internal val STARTERS = listOf(
    Habit(name = "Walk", icon = "walk", color = 0xFF65A30D),
    Habit(name = "Read", icon = "read", color = 0xFF7C3AED, remind = 21 * 60),
    Habit(name = "Pray", icon = "pray", color = 0xFFD97706, times = 5),
    Habit(name = "Vitamins", icon = "vitamins", color = 0xFFDB2777, remind = 8 * 60),
    Habit(name = "No sugar", icon = "nosugar", color = 0xFFDC2626),
    Habit(name = "Call family", icon = "call", color = 0xFF2563EB, freq = "days", days = 0b1100000),
    Habit(name = "Journal", icon = "journal", color = 0xFF0D9488, remind = 22 * 60),
)

/**
 * Tools → Habits: the user's own daily habits — a tick a day (or a count: "Pray 5 times"), every day,
 * some days, or N times a week; an optional reminder (Done / Later from the notification); streaks;
 * one rest day a week that keeps a streak going. Habits ("habit") and their days ("habitlog") sync.
 */
object Habits {
    private const val KIND = "habit"
    private const val LOG = "habitlog"
    internal val items = WellItems("habits.json")
    private lateinit var app: Context

    internal val version: Long get() = items.version

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        items.init(app)
        val s = WellSync("habits", SyncType.HABITS, listOf(KIND, LOG), items)
        s.attach(app)
        items.sync = s
        items.onRemote = { schedule(app) }
    }

    internal fun all(): List<Habit> = items.items(KIND).map { Habit.of(it.key, it.data) }.sortedBy { it.created }

    internal fun active(): List<Habit> = all().filter { !it.archived }

    internal fun habit(id: String): Habit? = items.item(KIND, id)?.let { Habit.of(id, it.data) }

    internal fun save(h: Habit) {
        items.put(KIND, h.id, h.json())
        schedule(app)
    }

    internal fun delete(id: String) {
        items.delete(KIND, id)
        items.items(LOG).filter { it.key.startsWith("$id/") }.forEach { items.delete(LOG, it.key) }
        schedule(app)
    }

    internal fun count(h: Habit, day: Int): Int = items.item(LOG, "${h.id}/$day")?.data?.optInt("count") ?: 0

    internal fun isRest(h: Habit, day: Int): Boolean = items.item(LOG, "${h.id}/$day")?.data?.optBoolean("rest") == true

    internal fun done(h: Habit, day: Int): Boolean = count(h, day) >= h.times

    private fun setLog(h: Habit, day: Int, count: Int, rest: Boolean) {
        items.put(LOG, "${h.id}/$day", JSONObject().put("count", count).put("rest", rest))
    }

    /** One tap: one more (a done habit goes back to not done). */
    internal fun tap(h: Habit, day: Int = FDays.today()) {
        val c = count(h, day)
        setLog(h, day, if (c >= h.times) 0 else c + 1, false)
        if (c + 1 >= h.times) Wellbeing.cancelNotification(app, notificationId(h.id))
    }

    /** Rest days left this week (one a week). */
    internal fun restLeft(h: Habit, day: Int = FDays.today()): Boolean {
        val monday = FDays.add(day, -mondayIndex(day))
        return (0..6).map { FDays.add(monday, it) }.none { isRest(h, it) }
    }

    internal fun rest(h: Habit, day: Int = FDays.today()) {
        if (isRest(h, day)) setLog(h, day, count(h, day), false) else if (restLeft(h, day)) setLog(h, day, count(h, day), true)
    }

    /** Done days this week (Mon–Sun) up to [day]. */
    internal fun weekDone(h: Habit, day: Int = FDays.today()): Int {
        val monday = FDays.add(day, -mondayIndex(day))
        return (0..mondayIndex(day)).count { done(h, FDays.add(monday, it)) }
    }

    /** Days (or weeks, for "N times a week") in a row. */
    internal fun streak(h: Habit, today: Int = FDays.today()): Int {
        if (h.freq == "week") {
            var monday = FDays.add(today, -mondayIndex(today))
            var n = 0
            if (weekDone(h, today) < h.perWeek) monday = FDays.add(monday, -7)
            while (n < 520 && weekDone(h, FDays.add(monday, 6)) >= h.perWeek) {
                n++
                monday = FDays.add(monday, -7)
            }
            return n
        }
        var d = today
        if (h.dueOn(d) && !done(h, d) && !isRest(h, d)) d = FDays.add(d, -1)
        var n = 0
        var guard = 0
        val born = FDays.of(h.created)
        while (guard++ < 3650 && d >= born) {
            if (h.dueOn(d) || h.paused) {
                when {
                    done(h, d) -> n++
                    isRest(h, d) -> Unit
                    else -> break
                }
            }
            d = FDays.add(d, -1)
        }
        return n
    }

    internal fun best(h: Habit): Int {
        if (h.freq == "week") return streak(h)
        var best = 0
        var run = 0
        var d = FDays.of(h.created)
        val today = FDays.today()
        var guard = 0
        while (d <= today && guard++ < 3650) {
            if (h.dueOn(d)) {
                when {
                    done(h, d) -> run++
                    isRest(h, d) -> Unit
                    d != today -> run = 0
                }
                best = maxOf(best, run)
            }
            d = FDays.add(d, 1)
        }
        return best
    }

    /** Today's due habits: how many are done, and of how many. */
    fun todayProgress(): Pair<Int, Int> {
        if (!::app.isInitialized) return 0 to 0
        val today = FDays.today()
        val due = active().filter { it.dueOn(today) && it.freq != "week" }
        return due.count { done(it, today) || isRest(it, today) } to due.size
    }

    // ------------------------------------------------------------------ reminders
    private fun prefs() = app.getSharedPreferences("habits_local", Context.MODE_PRIVATE)

    internal fun notificationId(id: String) = Wellbeing.ID_HABIT + (id.hashCode() and 0xFFF) + 1

    private fun snoozeOf(id: String) = prefs().getLong("snooze_$id", 0L)

    /** Each habit's next reminder (a due day, not done yet). */
    private fun nextFor(h: Habit, now: Long): Long? {
        if (h.remind < 0 || h.archived || h.paused) return null
        val snooze = snoozeOf(h.id)
        if (snooze > now) return snooze
        var at = Wellbeing.nextAt(h.remind, now - 60_000L)
        repeat(8) {
            val d = FDays.of(at)
            if (h.dueOn(d) && !done(h, d) && !isRest(h, d) && at > now - 60_000L) return at
            at = Wellbeing.nextAt(h.remind, at)
        }
        return null
    }

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        if (!::app.isInitialized) init(ctx)
        Wellbeing.cancelAlarm(ctx, Wellbeing.A_HABIT_REMIND, Wellbeing.REQ_HABIT)
        val now = System.currentTimeMillis()
        val at = active().mapNotNull { nextFor(it, now) }.filter { it > now - 60_000L }.minOrNull() ?: return
        Wellbeing.alarm(ctx, Wellbeing.A_HABIT_REMIND, Wellbeing.REQ_HABIT, maxOf(at, now + 5_000L))
    }

    internal fun remind(ctx: Context) {
        val now = System.currentTimeMillis()
        val today = FDays.today()
        CalmSession.init(ctx)
        for (h in active()) {
            val due = nextFor(h, now - 3 * 60_000L) ?: continue
            if (due > now + 60_000L) continue
            prefs().edit().remove("snooze_${h.id}").apply()
            if (done(h, today) || isRest(h, today)) continue
            if (CalmSession.active && !CalmSession.paused) {
                prefs().edit().putLong("snooze_${h.id}", now + 15 * 60_000L).apply()
                continue
            }
            val s = streak(h)
            Wellbeing.notify(
                ctx, Wellbeing.CH_HABITS, notificationId(h.id), "Time to ${h.name.lowercase()}",
                if (s > 1) "$s-day streak — keep it going" else h.schedule(),
                Wellbeing.open(ctx, "HABITS", notificationId(h.id)), h.color,
                listOf(
                    "Done" to Wellbeing.action(ctx, Wellbeing.A_HABIT_DONE, 0x6E3000 + (h.id.hashCode() and 0xFFF), h.id),
                    "Later" to Wellbeing.action(ctx, Wellbeing.A_HABIT_LATER, 0x6E4000 + (h.id.hashCode() and 0xFFF), h.id),
                ),
            )
        }
        // A reminder just sent comes again tomorrow, not now.
        schedule(ctx)
    }

    internal fun doneFromNotification(ctx: Context, id: String) {
        val h = habit(id) ?: return
        val today = FDays.today()
        setLog(h, today, h.times, false)
        Wellbeing.cancelNotification(ctx, notificationId(id))
        schedule(ctx)
    }

    internal fun later(ctx: Context, id: String) {
        prefs().edit().putLong("snooze_$id", System.currentTimeMillis() + 3_600_000L).apply()
        Wellbeing.cancelNotification(ctx, notificationId(id))
        schedule(ctx)
    }
}

// ============================================================================ screens
@Composable
fun HabitsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Habits.init(ctx) }
    @Suppress("UNUSED_VARIABLE") val v = Habits.version
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = detail != null) { detail = null }
    val h = detail?.let { Habits.habit(it) }
    if (h != null) HabitDetail(h, onBack = { detail = null }, onEdit = { editing = h.id })
    else HabitsToday(onBack, onOpen = { detail = it.id }, onNew = { editing = "new" })
    editing?.let { id ->
        HabitSheet(if (id == "new") null else Habits.habit(id), onDone = {
            editing = null
            if (it == "deleted") detail = null
        })
    }
}

@Composable
private fun HabitsToday(onBack: () -> Unit, onOpen: (Habit) -> Unit, onNew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = habitsTint()
    val today = FDays.today()
    val habits = Habits.active()
    val due = habits.filter { it.dueOn(today) }
    val other = habits.filter { !it.dueOn(today) }
    val (doneN, dueN) = Habits.todayProgress()
    WellPage("Habits", onBack, overlay = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).height(56.dp).clip(RoundedCornerShape(18.dp)).background(t.accent).clickable(onClick = onNew).padding(horizontal = 20.dp),
        ) {
            Icon(Icons.Rounded.Add, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("New habit", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }) {
        // The last 7 days: full = every due habit done, ring = some.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            (6 downTo 0).map { FDays.add(today, -it) }.forEach { d ->
                val dd = habits.filter { it.dueOn(d) && it.freq != "week" && FDays.of(it.created) <= d }
                val n = dd.count { Habits.done(it, d) || Habits.isRest(it, d) }
                val full = dd.isNotEmpty() && n == dd.size
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(weekdayLetter(d), fontSize = 11.5.sp, color = if (d == today) cs.onSurface else cs.onSurfaceVariant, fontWeight = if (d == today) FontWeight.Medium else FontWeight.Normal)
                    Box(
                        Modifier.padding(top = 4.dp).size(36.dp).clip(CircleShape).background(if (full) t.accent else Color.Transparent)
                            .border(2.5.dp, if (full) t.accent else if (n > 0) t.accent.copy(alpha = .55f) else cs.outlineVariant, CircleShape)
                            .then(if (d == today) Modifier.border(1.dp, cs.onSurface, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Text("${d % 100}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (full) Color.White else cs.onSurface) }
                }
            }
        }
        if (habits.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TintIcon(Icons.Rounded.TaskAlt, t, 88.dp)
                Text("Start a habit", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(top = 16.dp))
                Text("A small tick each day. Start from one of these, or make your own.", fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Starters(t) { Habits.save(it.copy(id = UUID.randomUUID().toString(), created = System.currentTimeMillis())) }
            return@WellPage
        }
        if (dueN > 0) {
            WCard(Modifier.padding(top = 14.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row {
                        Text("$doneN of $dueN done today", fontSize = 14.5.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                        val left = due.filter { it.freq != "week" && !Habits.done(it, today) && !Habits.isRest(it, today) }.map { it.name }
                        if (left.isNotEmpty()) Text(left.take(2).joinToString(" and ") + if (left.size > 2) " +${left.size - 2}" else " left", fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.container)) {
                        Box(Modifier.fillMaxWidth(doneN / dueN.toFloat()).height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.accent))
                    }
                }
            }
        }
        WCard(Modifier.padding(top = 12.dp)) {
            (due + other).forEachIndexed { i, h ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(cs.outlineVariant.copy(alpha = .6f)))
                HabitRow(h, today, onOpen)
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun HabitRow(h: Habit, day: Int, onOpen: (Habit) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val c = Color(h.color)
    val dueToday = h.dueOn(day)
    val count = Habits.count(h, day)
    val done = count >= h.times
    val rest = Habits.isRest(h, day)
    val streak = Habits.streak(h)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onOpen(h) }.alpha(if (dueToday) 1f else .55f).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(habitIcon(h.icon), null, tint = c, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.name, fontSize = 15.sp, color = cs.onSurface, maxLines = 1)
                if (streak > 0) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color(0xFFEA580C), modifier = Modifier.padding(start = 6.dp).size(14.dp))
                    Text("$streak", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEA580C))
                }
            }
            Text(
                when {
                    !dueToday && h.paused -> "Paused"
                    !dueToday -> "${h.schedule()} · not today"
                    rest -> "Rest day"
                    h.freq == "week" -> "${Habits.weekDone(h, day)} of ${h.perWeek} this week"
                    else -> h.schedule()
                },
                fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1,
            )
        }
        if (dueToday) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (done) c else Color.Transparent).border(2.dp, if (done || count > 0) c else c.copy(alpha = .45f), CircleShape)
                    .clickable { Habits.tap(h, day) },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    done -> Icon(Icons.Rounded.Check, "Done — tap to undo", tint = Color.White, modifier = Modifier.size(22.dp))
                    h.times > 1 -> Text("$count/${h.times}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = c)
                    else -> Icon(Icons.Rounded.Check, "Mark ${h.name} done", tint = c.copy(alpha = .35f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Starters(t: WTint, onPick: (Habit) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        STARTERS.forEach { s ->
            Text(
                s.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = t.accent,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(t.container).clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitSheet(existing: Habit?, onDone: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = habitsTint()
    var h by remember { mutableStateOf(existing ?: Habit()) }
    var time by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onDone("") }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(if (existing == null) "New habit" else "Edit habit", fontSize = 20.sp, color = cs.onSurface, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            if (existing == null) Starters(t) { h = it.copy(id = h.id, created = h.created) }
            OutlinedTextField(
                h.name, { h = h.copy(name = it.take(40)) }, label = { Text("Name") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.accent, focusedLabelColor = t.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                HABIT_ICONS.forEach { (key, icon) ->
                    val on = h.icon == key
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(h.color).copy(alpha = .16f) else cs.surfaceContainer)
                            .border(if (on) 2.dp else 0.dp, if (on) Color(h.color) else Color.Transparent, RoundedCornerShape(12.dp)).clickable { h = h.copy(icon = key) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, key, tint = if (on) Color(h.color) else cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                HABIT_COLORS.forEach { c ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .then(if (h.color == c) Modifier.border(3.dp, cs.onSurface, CircleShape) else Modifier).clickable { h = h.copy(color = c) },
                    )
                }
            }
            Lab("How often", t.accent)
            Choices(listOf("daily", "days", "week"), h.freq, { mapOf("daily" to "Every day", "days" to "Some days", "week" to "Times a week")[it]!! }, t, { h = h.copy(freq = it) })
            Spacer(Modifier.height(8.dp))
            when (h.freq) {
                "days" -> DaysPick(h.days, t) { h = h.copy(days = it) }
                "week" -> PlusMinusLine("Times a week", "${h.perWeek}", { h = h.copy(perWeek = (h.perWeek - 1).coerceAtLeast(1)) }, { h = h.copy(perWeek = (h.perWeek + 1).coerceAtMost(7)) })
            }
            PlusMinusLine("Times a day", "${h.times}", { h = h.copy(times = (h.times - 1).coerceAtLeast(1)) }, { h = h.copy(times = (h.times + 1).coerceAtMost(20)) }, sub = "Tap once each time")
            SwitchLine("Remind me", if (h.remind >= 0) timeText(h.remind) else "Off", h.remind >= 0, t.accent, { on -> h = h.copy(remind = if (on) 9 * 60 else -1) })
            if (h.remind >= 0) TimeLine("At", h.remind, t, { time = true })
            Spacer(Modifier.height(12.dp))
            BigButton("Save", t.accent, {
                if (h.name.isNotBlank()) {
                    Habits.save(h.copy(name = h.name.trim()))
                    onDone("saved")
                }
            })
            if (existing != null) {
                TextButton(onClick = {
                    Habits.delete(existing.id)
                    onDone("deleted")
                }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)) { Text("Delete habit", color = cs.error) }
            }
        }
    }
    if (time) TimeDialog("Remind me at", h.remind.coerceAtLeast(0), { h = h.copy(remind = it); time = false }, { time = false })
}

@Composable
private fun HabitDetail(h: Habit, onBack: () -> Unit, onEdit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = habitsTint()
    val c = Color(h.color)
    val today = FDays.today()
    val streak = Habits.streak(h)
    val unit = if (h.freq == "week") "week" else "day"
    WellPage(h.name, onBack, actions = { TextButton(onClick = onEdit) { Text("Edit", color = t.accent) } }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color(0xFFEA580C), modifier = Modifier.size(30.dp))
            Text(" $streak $unit${if (streak == 1) "" else "s"}", fontSize = 32.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
        }
        Text("Current streak · best ${Habits.best(h)} $unit${if (Habits.best(h) == 1) "" else "s"}", fontSize = 13.5.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        val monthDays = (today / 100 * 100 + 1..today).toList()
        val monthDue = monthDays.filter { h.dueOn(it) && it >= FDays.of(h.created) }
        Stats(
            "This week" to if (h.freq == "week") "${Habits.weekDone(h)} of ${h.perWeek}" else {
                val week = (0..mondayIndex(today)).map { FDays.add(today, -it) }.filter { it >= FDays.of(h.created) }
                "${week.count { Habits.done(h, it) }} of ${week.count { h.dueOn(it) }}"
            },
            "This month" to "${monthDays.count { Habits.done(h, it) }} of ${monthDue.size}",
            "All time" to "${Habits.items.items("habitlog").count { it.key.startsWith("${h.id}/") && it.data.optInt("count") >= h.times }}",
        )
        Lab(monthTitle())
        MonthDots(today, WTint(c, c.copy(alpha = .16f))) { d ->
            when {
                Habits.done(h, d) -> c to Color.White
                Habits.isRest(h, d) -> c.copy(alpha = .18f) to c
                else -> null
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Legend(c, "Done")
            Legend(c.copy(alpha = .18f), "Rest day")
            Legend(cs.surfaceContainer, "Not done")
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            if (h.freq != "week" && h.dueOn(today) && !Habits.done(h, today)) {
                val rest = Habits.isRest(h, today)
                PillButton(if (rest) "Undo rest day" else if (Habits.restLeft(h)) "Rest day today" else "Rest day used", cs.surfaceContainerHigh, cs.onSurface, Modifier.weight(1f)) {
                    Habits.rest(h)
                }
            }
            PillButton(if (h.paused) "Resume" else "Pause", cs.surfaceContainerHigh, cs.onSurface, Modifier.weight(1f)) { Habits.save(h.copy(paused = !h.paused)) }
        }
        Hint("A rest day (one a week) keeps your streak going. Pause a habit for a while — its history stays.", Modifier.padding(top = 10.dp), center = true)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(" $text", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
