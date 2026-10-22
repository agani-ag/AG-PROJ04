package com.agani.syncup.wellbeing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.forest.FDays
import com.agani.syncup.sync.SyncType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** 5 Great · 4 Good · 3 Okay · 2 Low · 1 Bad. */
internal val MOOD_NAMES = mapOf(5 to "Great", 4 to "Good", 3 to "Okay", 2 to "Low", 1 to "Bad")
internal val MOOD_COLORS = mapOf(5 to 0xFF16A34A, 4 to 0xFF65A30D, 3 to 0xFFD97706, 2 to 0xFFEA580C, 1 to 0xFFE11D48)
private val MOOD_SOFT = mapOf(5 to 0xFFDCFCE7, 4 to 0xFFECFCCB, 3 to 0xFFFEF3C7, 2 to 0xFFFFEDD5, 1 to 0xFFFFE4E6)

// The faces in Dark: a light line on a deep tile, so they sit well on black.
private val MOOD_COLORS_DARK = mapOf(5 to 0xFF86EFAC, 4 to 0xFFBEF264, 3 to 0xFFFCD34D, 2 to 0xFFFDBA74, 1 to 0xFFFDA4AF)
private val MOOD_SOFT_DARK = mapOf(5 to 0xFF0F2A18, 4 to 0xFF1C2A0D, 3 to 0xFF30240C, 2 to 0xFF33200F, 1 to 0xFF34141B)
internal val MOOD_TAGS = listOf("Work", "Family", "Health", "Sleep", "Friends", "Money", "Weather", "Food")

internal data class MoodEntry(val key: String, val at: Long, val mood: Int, val tags: List<String>, val note: String)

internal data class MoodSettings(val remind: Boolean = true, val times: List<Int> = listOf(21 * 60)) {
    fun json(): JSONObject = JSONObject().put("remind", remind).put("times", JSONArray(times))

    companion object {
        fun of(o: JSONObject) = MoodSettings(o.optBoolean("remind", true), o.optJSONArray("times").ints().takeIf { it.isNotEmpty() } ?: listOf(21 * 60))
    }
}

/**
 * Tools → Mood: check in as often as you like — a face, optional tags and a note. Each day is coloured
 * by its average; simple patterns come from the user's own Water / Steps / Sleep / Habits (observations,
 * never advice). After several hard days, a quiet card offers Tele-MANAS (India's free 24×7 helpline).
 * Synced (kind "mood"); the admin never sees anything but a count.
 */
object Mood {
    private const val KIND = "mood"
    internal val items = WellItems("mood.json")
    private lateinit var app: Context

    internal val version: Long get() = items.version

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        items.init(app)
        val s = WellSync("mood", SyncType.MOOD, listOf(KIND), items)
        s.attach(app)
        items.sync = s
        items.onRemote = { schedule(app) }
    }

    internal val settings: MoodSettings get() = MoodSettings.of(items.settings(KIND))

    internal fun changeSettings(s: MoodSettings) {
        items.putSettings(KIND, s.json())
        schedule(app)
    }

    internal fun entries(): List<MoodEntry> = items.items(KIND).map {
        MoodEntry(it.key, it.data.optLong("at", it.updatedMs), it.data.optInt("mood").coerceIn(1, 5), it.data.optJSONArray("tags").strings(), it.data.optString("note"))
    }.sortedByDescending { it.at }

    internal fun on(day: Int): List<MoodEntry> = entries().filter { FDays.of(it.at) == day }

    /** The day's average, 1..5 (null = no check-in). */
    internal fun average(day: Int): Float? = on(day).takeIf { it.isNotEmpty() }?.map { it.mood }?.average()?.toFloat()

    fun lastToday(): Int? = if (::app.isInitialized) on(FDays.today()).firstOrNull()?.mood else null

    fun countToday(): Int = if (::app.isInitialized) on(FDays.today()).size else 0

    internal fun add(mood: Int, tags: List<String>, note: String) {
        val now = System.currentTimeMillis()
        items.put(KIND, UUID.randomUUID().toString(), JSONObject().put("at", now).put("mood", mood).put("tags", JSONArray(tags)).put("note", note.trim().take(500)), now)
        Wellbeing.cancelNotification(app, Wellbeing.ID_MOOD)
    }

    internal fun remove(key: String) = items.delete(KIND, key)

    /** Several hard days in a row (each one's average Low or Bad), and not waved away this week. */
    internal fun needsCare(): Boolean {
        val today = FDays.today()
        val last3 = (0..2).map { average(FDays.add(today, -it)) }
        val hard = last3.all { it != null && it <= 2.2f }
        val hidden = app.getSharedPreferences("mood_local", Context.MODE_PRIVATE).getLong("care_hidden", 0L)
        return hard && System.currentTimeMillis() - hidden > 7 * 24 * 3_600_000L
    }

    internal fun hideCare() = app.getSharedPreferences("mood_local", Context.MODE_PRIVATE).edit().putLong("care_hidden", System.currentTimeMillis()).apply()

    /** Up to two simple observations from the user's own data (only when there's enough of it). */
    internal fun patterns(): List<Pair<String, String>> {
        val today = FDays.today()
        val days = (0 until 60).map { FDays.add(today, -it) }.mapNotNull { d -> average(d)?.let { d to it } }
        if (days.size < 6) return emptyList()
        fun good(list: List<Pair<Int, Float>>) = list.count { it.second >= 3.5f }
        val out = mutableListOf<Pair<String, String>>()
        fun check(icon: String, yes: (Int) -> Boolean, text: (Int, Int) -> String) {
            val (a, b) = days.partition { yes(it.first) }
            if (a.size < 3 || b.size < 3) return
            val ga = good(a)
            if (ga / a.size.toFloat() >= .6f && ga / a.size.toFloat() - good(b) / b.size.toFloat() >= .2f) out += icon to text(ga, a.size)
        }
        if (Water.settings.setUp) check("water", { Water.goalMetOn(it) }) { g, n -> "On days you met your water goal, you felt Good or Great $g of $n times." }
        if (Steps.settings.setUp) check("steps", { Steps.on(it) >= Steps.settings.goal }) { g, n -> "On days with ${Steps.settings.goal.grouped()}+ steps, you felt Good or Great $g of $n times." }
        val slept = Sleep.nights().filter { it.end > 0 }.groupBy { FDays.of(it.end) }.mapValues { (_, l) -> l.sumOf { it.hours.toDouble() } }
        if (slept.size >= 4) check("sleep", { (slept[it] ?: 0.0) >= 7.0 }) { g, n -> "After 7+ hours of sleep, you felt Good or Great $g of $n times." }
        if (Habits.active().isNotEmpty()) check("habits", { d ->
            val due = Habits.active().filter { it.dueOn(d) && it.freq != "week" }
            due.isNotEmpty() && due.all { Habits.done(it, d) }
        }) { g, n -> "On days you did all your habits, you felt Good or Great $g of $n times." }
        return out.take(2)
    }

    // ------------------------------------------------------------------ reminders
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        if (!::app.isInitialized) init(ctx)
        Wellbeing.cancelAlarm(ctx, Wellbeing.A_MOOD_REMIND, Wellbeing.REQ_MOOD)
        val s = settings
        if (!s.remind || s.times.isEmpty()) return
        Wellbeing.alarm(ctx, Wellbeing.A_MOOD_REMIND, Wellbeing.REQ_MOOD, s.times.minOf { Wellbeing.nextAt(it) })
    }

    internal fun remind(ctx: Context) {
        val now = System.currentTimeMillis()
        val recent = entries().firstOrNull()?.let { now - it.at < 2 * 3_600_000L } == true
        if (settings.remind && !recent && !Wellbeing.quietNow(ctx, now)) {
            val evening = Wellbeing.minuteNow(now) >= 17 * 60
            Wellbeing.notify(
                ctx, Wellbeing.CH_MOOD, Wellbeing.ID_MOOD, if (evening) "How was today?" else "How are you now?", "One tap to note it",
                Wellbeing.open(ctx, "MOOD", Wellbeing.ID_MOOD, "checkin"), 0xFF4D7C0F,
                listOf("Log mood" to Wellbeing.open(ctx, "MOOD", 0x6E2301, "checkin")),
            )
        }
        schedule(ctx)
    }
}

// ============================================================================ faces
/** A round face for a mood (1 Bad … 5 Great). */
@Composable
internal fun MoodFace(mood: Int, size: Dp, ring: Boolean = false) {
    val light = MaterialTheme.colorScheme.isLight
    val col = Color((if (light) MOOD_COLORS else MOOD_COLORS_DARK)[mood] ?: 0xFF65A30D)
    val soft = Color((if (light) MOOD_SOFT else MOOD_SOFT_DARK)[mood] ?: 0xFFECFCCB)
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 56f
        drawCircle(soft, (if (ring) 24f else 26f) * s)
        if (ring) drawCircle(col, 27f * s, style = Stroke(2.5f * s))
        drawCircle(col, 2.8f * s, Offset(21f * s, 23f * s))
        drawCircle(col, 2.8f * s, Offset(35f * s, 23f * s))
        val p = Path()
        when (mood) {
            5 -> { p.moveTo(17f * s, 31f * s); p.quadraticTo(28f * s, 44f * s, 39f * s, 31f * s); p.close() }
            4 -> { p.moveTo(18f * s, 32f * s); p.quadraticTo(28f * s, 40f * s, 38f * s, 32f * s) }
            3 -> { p.moveTo(19f * s, 35f * s); p.lineTo(37f * s, 35f * s) }
            2 -> { p.moveTo(19f * s, 37f * s); p.quadraticTo(28f * s, 32f * s, 37f * s, 37f * s) }
            else -> { p.moveTo(18f * s, 39f * s); p.quadraticTo(28f * s, 30f * s, 38f * s, 39f * s) }
        }
        if (mood == 5) drawPath(p, col)
        drawPath(p, col, style = Stroke(3f * s, cap = StrokeCap.Round))
    }
}

/** Five faces in a row with their names; [picked] 0 = none. */
@Composable
internal fun FacePick(picked: Int, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        (5 downTo 1).forEach { m ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPick(m) }.padding(4.dp),
            ) {
                MoodFace(m, 52.dp, ring = picked == m)
                Text(MOOD_NAMES[m]!!, fontSize = 12.5.sp, fontWeight = if (picked == m) FontWeight.Medium else FontWeight.Normal, color = if (picked == m) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ============================================================================ screens
@Composable
fun MoodScreen(onBack: () -> Unit, start: String? = null, onStartUsed: () -> Unit = {}) {
    val ctx = LocalContext.current
    remember { Mood.init(ctx) }
    @Suppress("UNUSED_VARIABLE") val v = Mood.version
    var page by rememberSaveable { mutableStateOf("today") }
    var checkIn by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(start) {
        if (start == "checkin") checkIn = 0
        if (start != null) onStartUsed()
    }
    BackHandler(enabled = page != "today") { page = "today" }
    if (page == "month") MoodMonth(onBack = { page = "today" }) else MoodToday(onBack, onMonth = { page = "month" }, onCheckIn = { checkIn = it })
    if (checkIn >= 0) CheckInSheet(checkIn, onDone = { checkIn = -1 })
}

@Composable
private fun MoodToday(onBack: () -> Unit, onMonth: () -> Unit, onCheckIn: (Int) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val t = moodTint()
    val today = FDays.today()
    val entries = Mood.on(today)
    var time by remember { mutableStateOf<Int?>(null) }
    WellPage("Mood", onBack, actions = { TextButton(onClick = onMonth) { Text("Month") } }) {
        if (Mood.needsCare()) CareCard(onCall = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:14416")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, onHide = { Mood.hideCare() })
        WCard(Modifier.padding(top = if (Mood.needsCare()) 12.dp else 4.dp)) {
            Text(
                if (entries.isEmpty()) "How are you today?" else "How are you now?", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
            )
            Box(Modifier.padding(bottom = 12.dp)) { FacePick(0) { onCheckIn(it) } }
        }
        if (entries.isNotEmpty()) {
            Lab("Today · ${entries.size} check-in${if (entries.size == 1) "" else "s"}")
            WCard {
                entries.forEachIndexed { i, e ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp).background(cs.outlineVariant.copy(alpha = .6f)))
                    EntryRow(e)
                }
            }
        }
        val week = (6 downTo 0).map { FDays.add(today, -it) }
        Lab("This week")
        WCard {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                week.forEach { d ->
                    val avg = Mood.average(d)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (avg != null) MoodFace(avg.roundToInt().coerceIn(1, 5), 36.dp) else Box(Modifier.size(36.dp).clip(CircleShape).background(cs.surfaceContainer))
                        Text(
                            weekdayLetter(d), fontSize = 11.5.sp, fontWeight = if (d == today) FontWeight.Medium else FontWeight.Normal,
                            color = if (d == today) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
        }
        val patterns = Mood.patterns()
        if (patterns.isNotEmpty()) {
            Lab("Patterns")
            patterns.forEach { (_, text) ->
                WCard(Modifier.padding(bottom = 8.dp)) { Text(text, fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurface, modifier = Modifier.padding(14.dp)) }
            }
            Hint("Patterns from your own SyncUp data — not advice.", center = true)
        }
        Lab("Reminders")
        val s = Mood.settings
        WCard {
            SwitchLine("Remind me to check in", s.times.joinToString(" · ") { timeText(it) }, s.remind, { Mood.changeSettings(s.copy(remind = it)) }, icon = { TintIcon(Icons.Rounded.Notifications, t, 42.dp) })
            if (s.remind) {
                s.times.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { TimeLine("Check-in ${i + 1}", m, { time = i }) }
                        if (s.times.size > 1) TextButton(onClick = { Mood.changeSettings(s.copy(times = s.times.filterIndexed { j, _ -> j != i })) }) { Text("Remove", color = cs.onSurfaceVariant) }
                    }
                }
                if (s.times.size < 3) {
                    TextButton(onClick = { Mood.changeSettings(s.copy(times = (s.times + 13 * 60).distinct().sorted())) }, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) { Text("Add a time") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(" Private — the admin never sees your moods", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
        }
    }
    time?.let { i ->
        val s = Mood.settings
        TimeDialog("Check-in ${i + 1}", s.times[i], { m ->
            Mood.changeSettings(s.copy(times = s.times.mapIndexed { j, x -> if (j == i) m else x }.distinct().sorted()))
            time = null
        }, { time = null })
    }
}

@Composable
private fun EntryRow(e: MoodEntry) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        MoodFace(e.mood, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${MOOD_NAMES[e.mood]} · ${clockText(e.at)}", fontSize = 14.5.sp, color = cs.onSurface)
            val sub = listOfNotNull(e.tags.takeIf { it.isNotEmpty() }?.joinToString(", "), e.note.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 2)
        }
        BarIcon(Icons.Rounded.Close, "Remove", tint = cs.onSurfaceVariant, onClick = { Mood.remove(e.key) })
    }
}

@Composable
private fun CareCard(onCall: () -> Unit, onHide: () -> Unit) {
    val light = MaterialTheme.colorScheme.isLight
    val ink = Color(if (light) 0xFF4C0519 else 0xFFFFE4E6)
    val rose = Color(if (light) 0xFFE11D48 else 0xFFFDA4AF)
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp).clip(CardShape).background(Color(if (light) 0xFFFFF1F2 else 0xFF34141B))
            .border(1.dp, Color(if (light) 0xFFFECDD3 else 0xFF4C1D26), CardShape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, null, tint = rose, modifier = Modifier.size(18.dp))
            Text("  Rough few days?", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink)
        }
        Text("Talking to someone can help. Tele-MANAS is free, private and open 24×7.", fontSize = 14.sp, lineHeight = 20.sp, color = ink, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(38.dp).clip(CircleShape).background(rose).clickable(onClick = onCall).padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Rounded.Call, null, tint = if (light) Color.White else Color(0xFF4C0519), modifier = Modifier.size(16.dp))
                Text("  Call 14416", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = if (light) Color.White else Color(0xFF4C0519))
            }
            Box(Modifier.height(38.dp).clip(CircleShape).clickable(onClick = onHide).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("Not now", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Color(if (light) 0xFF9F1239 else 0xFFFDA4AF))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CheckInSheet(preset: Int, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = moodTint()
    var mood by remember { mutableIntStateOf(preset) }
    var tags by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("How are you now?", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
            FacePick(mood) { mood = it }
            SheetLab("What's behind it? (optional)", Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
                MOOD_TAGS.forEach { tag ->
                    val on = tag in tags
                    Box(
                        Modifier.height(34.dp).clip(CircleShape).background(if (on) t.container else cs.surfaceContainerLowest)
                            .border(1.dp, if (on) Color.Transparent else cs.outlineVariant, CircleShape)
                            .clickable { tags = if (on) tags - tag else tags + tag }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(tag, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) t.accent else cs.onSurface) }
                }
            }
            OutlinedTextField(
                note, { note = it.take(500) }, label = { Text("A note (optional)") }, minLines = 2, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp),
            )
            BigButton("Save", enabled = mood > 0, onClick = {
                if (mood > 0) {
                    Mood.add(mood, tags.toList(), note)
                    onDone()
                }
            })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Icon(Icons.Rounded.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(" Private — the admin never sees your moods", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MoodMonth(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = moodTint()
    val today = FDays.today()
    var shown by rememberSaveable { mutableIntStateOf(today) }
    val avgs = (today / 100 * 100 + 1..today).mapNotNull { d -> Mood.average(d)?.let { d to it.roundToInt().coerceIn(1, 5) } }
    val most = avgs.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key
    WellPage("Your moods", onBack) {
        Lab(monthTitle() + (most?.let { " · most often: ${MOOD_NAMES[it]}" } ?: ""))
        WCard {
            Box(Modifier.padding(vertical = 10.dp)) {
                MonthDotsTap(today, shown, t, { d -> Mood.average(d)?.let { Color(MOOD_COLORS[it.roundToInt().coerceIn(1, 5)]!!) to Color.White } }) { shown = it }
            }
        }
        val list = Mood.on(shown)
        Lab(if (shown == today) "Today" else "${weekdayShort(shown)} ${shown % 100}")
        if (list.isEmpty()) Hint("No check-ins this day.")
        else WCard { list.forEach { EntryRow(it) } }
        val patterns = Mood.patterns()
        if (patterns.isNotEmpty()) {
            Lab("Patterns")
            patterns.forEach { (_, text) -> WCard(Modifier.padding(bottom = 8.dp)) { Text(text, fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurface, modifier = Modifier.padding(14.dp)) } }
            Hint("Patterns from your own SyncUp data — not advice.", center = true)
        }
    }
}

/** The month grid, each day tappable. */
@Composable
private fun MonthDotsTap(today: Int, selected: Int, tint: WTint, style: (Int) -> Pair<Color, Color>?, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val first = today / 100 * 100 + 1
    val cells = (0 until mondayIndex(first)).map { 0 } + (first..today).toList()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, fontSize = 11.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                week.forEach { d ->
                    Box(Modifier.weight(1f).height(44.dp).padding(3.dp), contentAlignment = Alignment.Center) {
                        if (d != 0) {
                            val st = style(d)
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(st?.first ?: cs.surfaceContainer)
                                    .then(if (d == selected) Modifier.border(2.dp, tint.accent, CircleShape) else Modifier).clickable { onPick(d) },
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
