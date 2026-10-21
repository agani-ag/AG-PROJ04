package com.agani.syncup.wellbeing

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agani.syncup.R
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.forest.FDays
import com.agani.syncup.forest.NaturePlayer
import com.agani.syncup.forest.NatureSound
import com.agani.syncup.forest.SoundBank
import com.agani.syncup.forest.soundIcon
import com.agani.syncup.sync.SyncType
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class SleepSettings(
    val remind: Boolean = false,
    val bedtime: Int = 22 * 60 + 30,
    /** Nights the bedtime reminder comes (bit 0 = Monday night). */
    val days: Int = 0b1111111,
    val wake: Int = 7 * 60,
    val morningAsk: Boolean = true,
    val soundA: String? = "RAIN",
    val soundB: String? = null,
    val volA: Float = .8f,
    val volB: Float = .5f,
    /** Minutes until the sounds fade out; 0 = all night. */
    val timer: Int = 45,
) {
    fun json(): JSONObject = JSONObject().put("remind", remind).put("bedtime", bedtime).put("days", days).put("wake", wake)
        .put("morning_ask", morningAsk).put("sound_a", soundA ?: "").put("sound_b", soundB ?: "")
        .put("vol_a", volA.toDouble()).put("vol_b", volB.toDouble()).put("timer", timer)

    companion object {
        fun of(o: JSONObject): SleepSettings {
            val d = SleepSettings()
            return SleepSettings(
                o.optBoolean("remind", d.remind), o.optInt("bedtime", d.bedtime), o.optInt("days", d.days), o.optInt("wake", d.wake),
                o.optBoolean("morning_ask", d.morningAsk),
                if (o.has("sound_a")) o.optString("sound_a").ifBlank { null } else d.soundA, o.optString("sound_b").ifBlank { null },
                o.optDouble("vol_a", d.volA.toDouble()).toFloat(), o.optDouble("vol_b", d.volB.toDouble()).toFloat(), o.optInt("timer", d.timer),
            )
        }
    }
}

internal data class Night(val key: String, val start: Long, val end: Long, val rating: Int) {
    val hours: Float get() = if (end > start) (end - start) / 3_600_000f else 0f
}

/**
 * Tools → Sleep: a bedtime reminder, sleep sounds (the same real recordings as Forest — mix two, with a
 * fade-out timer), and a simple log — Good night when you go to bed, Good morning when you wake, then a
 * face for how you slept. Nights and settings sync (kind "sleep"). Bedtime also starts the night's
 * quiet time for the other Wellbeing reminders.
 */
object Sleep {
    private const val KIND = "sleep"
    internal val items = WellItems("sleep.json")
    private lateinit var app: Context
    internal val player = NaturePlayer()

    internal val version: Long get() = items.version

    /** Sleep sounds are playing (this phone). */
    var soundsOn by mutableStateOf(false)
        private set
    /** When they fade out (0 = all night). */
    var fadeAt by mutableLongStateOf(0L)
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        SoundBank.init(app)
        items.init(app)
        val s = WellSync("sleep", SyncType.SLEEP, listOf(KIND), items)
        s.attach(app)
        items.sync = s
        items.onRemote = { schedule(app) }
    }

    internal val settings: SleepSettings get() = SleepSettings.of(items.settings(KIND))

    internal fun changeSettings(s: SleepSettings) {
        items.putSettings(KIND, s.json())
        if (soundsOn) applyMix(s)
        schedule(app)
    }

    internal fun nights(): List<Night> = items.items(KIND).map { Night(it.key, it.data.optLong("start"), it.data.optLong("end"), it.data.optInt("rating")) }.sortedByDescending { it.start }

    /** A night being logged (Good night tapped, no Good morning yet) — within the last 20 hours. */
    internal fun current(now: Long = System.currentTimeMillis()): Night? = nights().firstOrNull { it.end == 0L && now - it.start < 20 * 3_600_000L }

    internal fun lastNight(): Night? = nights().firstOrNull { it.end > 0 }

    /** Night time: a night being logged, or between bedtime and wake time when a bedtime is set. */
    fun isNight(now: Long = System.currentTimeMillis()): Boolean {
        if (!::app.isInitialized) return false
        if (current(now) != null) return true
        val s = settings
        if (!s.remind) return false
        val m = Wellbeing.minuteNow(now)
        return if (s.bedtime > s.wake) m >= s.bedtime || m < s.wake else m in s.bedtime until s.wake
    }

    // ------------------------------------------------------------------ the log
    internal fun goodNight(ctx: Context) {
        val now = System.currentTimeMillis()
        current(now)?.let { items.delete(KIND, it.key) }
        items.put(KIND, UUID.randomUUID().toString(), JSONObject().put("start", now).put("end", 0).put("rating", 0), now)
        val s = settings
        if (s.soundA != null || s.soundB != null) playSounds(ctx)
        Wellbeing.cancelNotification(ctx, Wellbeing.ID_SLEEP)
        schedule(ctx)
    }

    /** Ends [night] at [end] with [rating] (0 = not rated yet). */
    internal fun goodMorning(ctx: Context, night: Night, start: Long, end: Long, rating: Int) {
        items.put(KIND, night.key, JSONObject().put("start", start).put("end", end).put("rating", rating))
        stopSounds(ctx)
        Wellbeing.cancelNotification(ctx, Wellbeing.ID_SLEEP)
        schedule(ctx)
    }

    internal fun rate(night: Night, rating: Int) {
        items.put(KIND, night.key, JSONObject().put("start", night.start).put("end", night.end).put("rating", rating))
    }

    // ------------------------------------------------------------------ sounds
    private fun sound(name: String?): NatureSound? = name?.let { n -> runCatching { NatureSound.valueOf(n) }.getOrNull() }

    private fun applyMix(s: SleepSettings) = player.mix(sound(s.soundA), s.volA, sound(s.soundB), s.volB)

    internal fun playSounds(ctx: Context) {
        val s = settings
        if (s.soundA == null && s.soundB == null) return
        player.master = 1f
        applyMix(s)
        soundsOn = true
        fadeAt = if (s.timer > 0) System.currentTimeMillis() + s.timer * 60_000L else 0L
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SleepSoundService::class.java)) }
    }

    internal fun stopSounds(ctx: Context) {
        player.stop()
        soundsOn = false
        fadeAt = 0L
        runCatching { ctx.stopService(Intent(ctx, SleepSoundService::class.java)) }
        Wellbeing.cancelNotification(ctx, Wellbeing.ID_SOUNDS)
    }

    internal fun playFromNotification(ctx: Context) {
        Wellbeing.cancelNotification(ctx, Wellbeing.ID_SLEEP)
        playSounds(ctx)
    }

    internal fun mixTitle(s: SleepSettings = settings): String = listOfNotNull(sound(s.soundA)?.title, sound(s.soundB)?.title).joinToString(" + ").ifBlank { "Sleep sounds" }

    // ------------------------------------------------------------------ reminders
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        if (!::app.isInitialized) init(ctx)
        Wellbeing.cancelAlarm(ctx, Wellbeing.A_BEDTIME, Wellbeing.REQ_BEDTIME)
        Wellbeing.cancelAlarm(ctx, Wellbeing.A_MORNING, Wellbeing.REQ_MORNING)
        val s = settings
        if (s.remind && s.days != 0) {
            val remindAt = (s.bedtime - 30 + 1440) % 1440
            var at = Wellbeing.nextAt(remindAt)
            repeat(7) { if (s.days and (1 shl mondayIndex(FDays.of(at))) == 0) at += 24 * 3_600_000L }
            Wellbeing.alarm(ctx, Wellbeing.A_BEDTIME, Wellbeing.REQ_BEDTIME, at)
        }
        if (s.morningAsk && current() != null) Wellbeing.alarm(ctx, Wellbeing.A_MORNING, Wellbeing.REQ_MORNING, Wellbeing.nextAt(s.wake))
    }

    internal fun bedtime(ctx: Context) {
        val s = settings
        if (s.remind && current() == null) {
            Wellbeing.notify(
                ctx, Wellbeing.CH_SLEEP, Wellbeing.ID_SLEEP, "Bedtime in 30 minutes", "Time to wind down",
                Wellbeing.open(ctx, "SLEEP", Wellbeing.ID_SLEEP), 0xFF4338CA,
                if (s.soundA != null || s.soundB != null) listOf("Play sounds" to Wellbeing.action(ctx, Wellbeing.A_SOUNDS, 0x6E2201)) else emptyList(),
            )
        }
        schedule(ctx)
    }

    internal fun morning(ctx: Context) {
        val n = current()
        if (n != null && settings.morningAsk) {
            val h = (System.currentTimeMillis() - n.start) / 3_600_000f
            Wellbeing.notify(
                ctx, Wellbeing.CH_SLEEP, Wellbeing.ID_SLEEP, "Good morning — how did you sleep?", "You slept about ${h.roundToInt()} h",
                Wellbeing.open(ctx, "SLEEP", Wellbeing.ID_SLEEP, "morning"), 0xFF4338CA,
                listOf("Rate" to Wellbeing.open(ctx, "SLEEP", 0x6E2202, "morning")),
            )
        }
        schedule(ctx)
    }
}

/** Keeps sleep sounds playing with the screen off, and fades them out on the timer. */
class SleepSoundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!Sleep.soundsOn) {
                stopSelf()
                return
            }
            val fade = Sleep.fadeAt
            if (fade > 0) {
                val left = fade - System.currentTimeMillis()
                if (left <= 0) {
                    Sleep.stopSounds(this@SleepSoundService)
                    stopSelf()
                    return
                }
                // The last minute: slowly down to nothing.
                Sleep.player.master = (left / 60_000f).coerceIn(0f, 1f)
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Sleep.init(this)
        Wellbeing.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        val ok = runCatching { ServiceCompat.startForeground(this, Wellbeing.ID_SOUNDS, card(), type) }.isSuccess
        if (!ok || !Sleep.soundsOn) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun card(): android.app.Notification {
        val fade = Sleep.fadeAt
        return NotificationCompat.Builder(this, Wellbeing.CH_SOUNDS)
            .setSmallIcon(R.drawable.ic_sync)
            .setColor(0xFF4338CA.toInt())
            .setContentTitle("Sleep sounds · ${Sleep.mixTitle()}")
            .setContentText(if (fade > 0) "Fades out at ${clockText(fade)}" else "Playing all night")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Wellbeing.open(this, "SLEEP", Wellbeing.ID_SOUNDS))
            .addAction(0, "Stop", Wellbeing.action(this, Wellbeing.A_SOUNDS_STOP, 0x6E2203))
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

// ============================================================================ screens
private val NIGHT_BG = Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF1E1B4B), Color(0xFF312E81)))
private val NIGHT_TEXT = Color(0xFFE0E7FF)
private val NIGHT_MUTED = Color(0xFFA5B4FC)

@Composable
fun SleepScreen(onBack: () -> Unit, start: String? = null, onStartUsed: () -> Unit = {}) {
    val ctx = LocalContext.current
    remember { Sleep.init(ctx) }
    @Suppress("UNUSED_VARIABLE") val v = Sleep.version
    var page by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(start) {
        if (start == "morning" && Sleep.current() != null) page = "morning"
        if (start != null) onStartUsed()
    }
    BackHandler(enabled = page != "home") { page = "home" }
    val night = Sleep.current()
    when {
        page == "morning" && night != null -> SleepMorning(night, onDone = { page = "home" })
        night != null -> SleepNight(night, onBack, onMorning = { page = "morning" })
        else -> SleepHome(onBack)
    }
}

@Composable
private fun NightPage(title: String, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    com.agani.syncup.forest.BarIcons(light = false)
    Box(Modifier.fillMaxWidth().background(NIGHT_BG)) {
        WellPage(title, onBack, background = Color.Transparent, contentColor = Color.White, content = content)
    }
}

@Composable
private fun NightCard(content: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .08f))
            .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { content() }
}

@Composable
private fun NightLab(text: String) = Lab(text, NIGHT_MUTED)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundGrid(s: SleepSettings) {
    val ctx = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3, modifier = Modifier.padding(horizontal = 16.dp)) {
        NatureSound.entries.forEach { snd ->
            val on = s.soundA == snd.name || s.soundB == snd.name
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) Color(0xFFC7D2FE) else Color.White.copy(alpha = .07f))
                    .clickable {
                        // Up to two at once: tap to add, tap again to take out.
                        val next = when {
                            s.soundA == snd.name -> s.copy(soundA = s.soundB, soundB = null, volA = s.volB)
                            s.soundB == snd.name -> s.copy(soundB = null)
                            s.soundA == null -> s.copy(soundA = snd.name)
                            s.soundB == null -> s.copy(soundB = snd.name)
                            else -> s.copy(soundB = snd.name)
                        }
                        Sleep.changeSettings(next)
                        if (!Sleep.soundsOn && (next.soundA != null || next.soundB != null)) Sleep.playSounds(ctx)
                        if (next.soundA == null && next.soundB == null) Sleep.stopSounds(ctx)
                    }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(soundIcon(snd), null, tint = if (on) Color(0xFF1E1B4B) else NIGHT_TEXT, modifier = Modifier.size(24.dp))
                Text(snd.title, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) Color(0xFF1E1B4B) else NIGHT_TEXT, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
        repeat((3 - NatureSound.entries.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun TimerChips(s: SleepSettings) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        listOf(15, 30, 45, 60, 0).forEach { m ->
            val on = s.timer == m
            Box(
                Modifier.weight(if (m == 0) 1.5f else 1f).height(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(if (on) NIGHT_TEXT else Color.Transparent).border(1.dp, if (on) Color.Transparent else Color.White.copy(alpha = .25f), RoundedCornerShape(17.dp))
                    .clickable { Sleep.changeSettings(s.copy(timer = m)) },
                contentAlignment = Alignment.Center,
            ) { Text(if (m == 0) "All night" else if (m == 15) "15 min" else "$m", fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) Color(0xFF1E1B4B) else NIGHT_TEXT) }
        }
    }
}

@Composable
private fun SleepHome(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Sleep.settings
    var time by remember { mutableStateOf<String?>(null) }
    NightPage("Sleep", onBack) {
        NightCard({
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { time = "bed" }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFC7D2FE).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Bedtime, null, tint = Color(0xFFC7D2FE), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bedtime ${timeText(s.bedtime)}", fontSize = 15.sp, color = Color.White)
                        Text(if (s.remind) "Reminder 30 min before · tap to change" else "Reminder off · tap to change the time", fontSize = 12.sp, color = Color(0xFFC7D2FE))
                    }
                    Switch(s.remind, { Sleep.changeSettings(s.copy(remind = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF818CF8)))
                }
                if (s.remind) {
                    Box(Modifier.padding(bottom = 8.dp)) { DaysPick(s.days, WTint(Color(0xFF1E1B4B), Color(0xFFC7D2FE))) { Sleep.changeSettings(s.copy(days = it)) } }
                }
            }
        }, Modifier.padding(top = 4.dp))
        NightLab(if (Sleep.soundsOn) "Sleep sounds · playing" else "Sleep sounds")
        SoundGrid(s)
        TimerChips(s)
        if (Sleep.soundsOn) {
            BigButton("Stop sounds", Color.White.copy(alpha = .12f), { Sleep.stopSounds(ctx) }, textColor = NIGHT_TEXT, icon = Icons.Rounded.Stop)
            Spacer(Modifier.height(10.dp))
        }
        BigButton("Good night", Color(0xFFC7D2FE), { Sleep.goodNight(ctx) }, textColor = Color(0xFF1E1B4B), icon = Icons.Rounded.Bedtime)
        Text(
            "Starts your sleep log${if (s.soundA != null || s.soundB != null) " and the sounds" else ""}. In the morning, tap Good morning.",
            fontSize = 12.5.sp, color = NIGHT_MUTED, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val last = Sleep.lastNight()
        if (last != null) {
            NightLab("Last night")
            NightCard({
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(hoursText(last.hours), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("${clockText(last.start)} – ${clockText(last.end)}", fontSize = 12.5.sp, color = Color(0xFFC7D2FE))
                    }
                    if (last.rating > 0) MoodFace(last.rating, 40.dp)
                }
            })
            SleepWeek(dark = true)
        }
        NightLab("Mornings")
        NightCard({
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { time = "wake" }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Usually up at", fontSize = 15.sp, color = Color.White)
                        Text("Night quiet ends then; the morning question comes then", fontSize = 12.sp, color = Color(0xFFC7D2FE))
                    }
                    Text(timeText(s.wake), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC7D2FE))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Ask how I slept", fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Switch(s.morningAsk, { Sleep.changeSettings(s.copy(morningAsk = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF818CF8)))
                }
            }
        })
    }
    time?.let { which ->
        TimeDialog(if (which == "bed") "Bedtime" else "Usually up at", if (which == "bed") s.bedtime else s.wake, { m ->
            Sleep.changeSettings(if (which == "bed") s.copy(bedtime = m) else s.copy(wake = m))
            time = null
        }, { time = null })
    }
}

@Composable
private fun SleepNight(night: Night, onBack: () -> Unit, onMorning: () -> Unit) {
    val ctx = LocalContext.current
    val s = Sleep.settings
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    NightPage("Sleeping", onBack) {
        Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(150.dp)) {
                drawCircle(Brush.radialGradient(listOf(Color(0x73C7D2FE), Color(0x00C7D2FE))), size.minDimension / 2)
                val r = size.minDimension * .25f
                val moon = Path().apply { addOval(Rect(Offset(size.width * .5f, size.height * .5f), r)) }
                val cut = Path().apply { addOval(Rect(Offset(size.width * .62f, size.height * .42f), r * .86f)) }
                drawPath(Path.combine(PathOperation.Difference, moon, cut), Color(0xFFE0E7FF))
            }
        }
        Text("Sleep well", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("Since ${clockText(night.start)}", fontSize = 14.sp, color = Color(0xFFC7D2FE), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (Sleep.soundsOn) {
            val fade = Sleep.fadeAt
            NightLab(if (fade > 0) "Now playing · fades out in ${clockLeft(fade - now)}" else "Now playing · all night")
            listOfNotNull(s.soundA?.let { it to true }, s.soundB?.let { it to false }).forEach { (name, first) ->
                val vol = if (first) s.volA else s.volB
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(runCatching { NatureSound.valueOf(name).title }.getOrDefault(name), fontSize = 14.sp, color = NIGHT_TEXT, modifier = Modifier.width(92.dp))
                    NightRound("−") { Sleep.changeSettings(if (first) s.copy(volA = (vol - .1f).coerceAtLeast(.1f)) else s.copy(volB = (vol - .1f).coerceAtLeast(.1f))) }
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .18f))) {
                        Box(Modifier.fillMaxWidth(vol).height(6.dp).clip(RoundedCornerShape(3.dp)).background(NIGHT_MUTED))
                    }
                    NightRound("+") { Sleep.changeSettings(if (first) s.copy(volA = (vol + .1f).coerceAtMost(1f)) else s.copy(volB = (vol + .1f).coerceAtMost(1f))) }
                }
            }
        } else {
            NightLab("Sounds")
            SoundGrid(s)
            TimerChips(s)
        }
        Spacer(Modifier.height(14.dp))
        BigButton("Set wake-up alarm", Color.White.copy(alpha = .1f), {
            runCatching {
                ctx.startActivity(
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, s.wake / 60).putExtra(AlarmClock.EXTRA_MINUTES, s.wake % 60)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Good morning").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, textColor = NIGHT_TEXT, icon = Icons.Rounded.Alarm)
        Spacer(Modifier.height(10.dp))
        BigButton("Good morning", Color(0xFFC7D2FE), onMorning, textColor = Color(0xFF1E1B4B), icon = Icons.Rounded.WbSunny)
    }
}

@Composable
private fun NightRound(text: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Color.White.copy(alpha = .12f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 18.sp, color = Color.White)
    }
}

private fun clockLeft(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

internal fun hoursText(h: Float): String {
    val m = (h * 60).roundToInt()
    return "${m / 60} h ${"%02d".format(m % 60)} min"
}

@Composable
private fun SleepMorning(night: Night, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val t = sleepTint()
    var start by rememberSaveable { mutableLongStateOf(night.start) }
    var end by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var time by remember { mutableStateOf<String?>(null) }
    WellPage("Sleep", onDone) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.WbSunny, null, tint = Color(0xFFD97706), modifier = Modifier.size(42.dp))
        }
        Text("Good morning!", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text("You slept ${hoursText((end - start) / 3_600_000f)}", fontSize = 15.sp, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        WCard(Modifier.padding(top = 12.dp)) {
            TimeLine("Went to bed", Wellbeing.minuteNow(start), t, { time = "start" })
            TimeLine("Woke up", Wellbeing.minuteNow(end), t, { time = "end" })
        }
        Lab("How did you sleep?", t.accent, Modifier.fillMaxWidth())
        FacePick(rating) { rating = it }
        Spacer(Modifier.height(22.dp))
        BigButton("Save", t.accent, {
            Sleep.goodMorning(ctx, night, start, end, rating)
            onDone()
        })
        Lab("Last 7 nights")
        SleepWeek(dark = false)
    }
    time?.let { which ->
        val base = if (which == "start") start else end
        TimeDialog(if (which == "start") "Went to bed" else "Woke up", Wellbeing.minuteNow(base), { m ->
            var at = Wellbeing.atMinute(base, m)
            if (which == "start" && at > end) at -= 24 * 3_600_000L
            if (which == "end" && at < start) at += 24 * 3_600_000L
            if (which == "start") start = at else end = at
            time = null
        }, { time = null })
    }
}

/** The last 7 nights' hours, average, how regular bedtime was, and the best night. */
@Composable
private fun SleepWeek(dark: Boolean) {
    val today = FDays.today()
    val days = (6 downTo 0).map { FDays.add(today, -it) }
    // A night belongs to the day it ended.
    val byDay = Sleep.nights().filter { it.end > 0 }.groupBy { FDays.of(it.end) }
    val hours = days.map { d -> byDay[d]?.sumOf { it.hours.toDouble() }?.toFloat() ?: 0f }
    val logged = Sleep.nights().filter { it.end > 0 }.take(14)
    val starts = logged.map { ((Wellbeing.minuteNow(it.start) + 720) % 1440).toDouble() }
    val spread = if (starts.size >= 2) sqrt(starts.map { (it - starts.average()) * (it - starts.average()) }.average()).roundToInt() else null
    Bars(
        hours, days.map { weekdayLetter(it) }, 7f, Color(0xFF6366F1), if (dark) Color(0xFF4338CA).copy(alpha = .45f) else Color(0xFFC7D2FE), "7 h",
        Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
    )
    Spacer(Modifier.height(12.dp))
    if (dark) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(
                "Average" to if (logged.isEmpty()) "—" else hoursText(logged.map { it.hours }.average().toFloat()).replace(" min", ""),
                "Bedtime" to (spread?.let { "± $it min" } ?: "—"),
                "Best" to if (logged.isEmpty()) "—" else hoursText(logged.maxOf { it.hours }).replace(" min", ""),
            ).forEach { (l, v) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(l, fontSize = 12.sp, color = NIGHT_MUTED)
                    Text(v, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
                }
            }
        }
    } else {
        Stats(
            "Average" to if (logged.isEmpty()) "—" else hoursText(logged.map { it.hours }.average().toFloat()).replace(" min", ""),
            "Bedtime" to (spread?.let { "± $it min" } ?: "—"),
            "Best" to if (logged.isEmpty()) "—" else hoursText(logged.maxOf { it.hours }).replace(" min", ""),
        )
    }
}
