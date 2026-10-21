package com.agani.syncup.wellbeing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.data.TokenStore
import com.agani.syncup.forest.FDays
import com.agani.syncup.sensors.Part
import com.agani.syncup.sensors.SensorLog
import com.agani.syncup.sync.SyncType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class StepsSettings(val setUp: Boolean = false, val goal: Int = 8000, val nudge: Boolean = true) {
    fun json(): JSONObject = JSONObject().put("set_up", setUp).put("goal", goal).put("nudge", nudge)

    companion object {
        fun of(o: JSONObject) = StepsSettings(o.optBoolean("set_up", false), o.optInt("goal", 8000), o.optBoolean("nudge", true))
    }
}

/**
 * Tools → Steps: the phone's own step counter (the same readings as Sensors' pedometer — no GPS,
 * almost no battery) against a daily goal. Each phone keeps its own total per day ("day/phone"); with
 * two phones the higher one counts, never both. One "goal reached" a day, and a 6 PM nudge when behind.
 */
object Steps {
    private const val KIND = "steps"
    private const val STRIDE_M = .75
    internal val items = WellItems("steps.json")
    private lateinit var app: Context

    internal val version: Long get() = items.version

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        items.init(app)
        val s = WellSync("steps", SyncType.STEPS, listOf(KIND), items)
        s.attach(app)
        items.sync = s
        items.onRemote = { schedule(app) }
    }

    internal val settings: StepsSettings get() = StepsSettings.of(items.settings(KIND))

    internal fun changeSettings(s: StepsSettings) {
        items.putSettings(KIND, s.json())
        schedule(app)
    }

    /** This phone has a step counter. */
    fun present(context: Context): Boolean = Part.STEP_COUNTER.present(context)

    fun allowed(context: Context): Boolean = SensorLog.stepsAllowed(context)

    /** Set up, allowed, and the phone can count. */
    fun counting(context: Context): Boolean = settings.setUp && present(context) && allowed(context)

    /** Steps on [day], the highest of the user's phones. */
    internal fun on(day: Int): Int = items.items(KIND).filter { it.data.optInt("day") == day }.maxOfOrNull { it.data.optInt("steps") } ?: 0

    fun today(): Int = if (::app.isInitialized) on(FDays.today()) else 0

    internal fun km(steps: Int): String = "%.1f".format(steps * STRIDE_M / 1000)

    internal fun streak(): Int {
        val goal = settings.goal
        var d = FDays.today()
        if (on(d) < goal) d = FDays.add(d, -1)
        var n = 0
        while (on(d) >= goal && n < 3650) {
            n++
            d = FDays.add(d, -1)
        }
        return n
    }

    /** First switch-on: start keeping the counter's readings (one now, so today counts from here). */
    internal fun start(context: Context, goal: Int, nudge: Boolean) {
        changeSettings(settings.copy(setUp = true, goal = goal, nudge = nudge))
        SensorLog.setStepsOn(context)
        Thread { runBlocking { SensorLog.readOnce(context, Sensor.TYPE_STEP_COUNTER)?.let { SensorLog.addSteps(context, it) } } }.start()
    }

    /** Turn the counter's readings into this phone's daily totals ([live] = a counter value just read). */
    internal fun refresh(context: Context, live: Float? = null) {
        if (!counting(context)) return
        val device = TokenStore(context).deviceId()
        for ((midnight, steps) in SensorLog.stepsByDay(context, 9, live)) {
            if (steps <= 0) continue
            val day = FDays.of(midnight)
            val key = "$day/$device"
            val old = items.item(KIND, key)?.data?.optInt("steps") ?: 0
            if (steps - old >= 10 || (steps > old && day != FDays.today())) items.put(KIND, key, JSONObject().put("day", day).put("steps", steps))
        }
    }

    /** After a background reading: totals, and "goal reached" once a day. */
    fun afterReading(context: Context) {
        init(context)
        refresh(context)
        val s = settings
        val today = FDays.today()
        val p = context.getSharedPreferences("steps_local", Context.MODE_PRIVATE)
        if (counting(context) && on(today) >= s.goal && p.getInt("goal_told", 0) != today) {
            p.edit().putInt("goal_told", today).apply()
            Wellbeing.notify(
                context, Wellbeing.CH_STEPS, Wellbeing.ID_STEPS, "Goal reached — ${on(today).grouped()} steps!",
                "About ${km(on(today))} km today. Nice walking.", Wellbeing.open(context, "STEPS", Wellbeing.ID_STEPS), 0xFFB45309,
            )
        }
    }

    fun schedule(context: Context) {
        val ctx = context.applicationContext
        Wellbeing.cancelAlarm(ctx, Wellbeing.A_STEPS_NUDGE, Wellbeing.REQ_STEPS)
        if (!::app.isInitialized) init(ctx)
        if (counting(ctx)) SensorLog.schedule(ctx)
        if (!counting(ctx) || !settings.nudge) return
        Wellbeing.alarm(ctx, Wellbeing.A_STEPS_NUDGE, Wellbeing.REQ_STEPS, Wellbeing.nextAt(18 * 60))
    }

    /** 6 PM: a fresh reading; one gentle nudge when behind. Runs off the main thread. */
    internal fun eveningNudge(ctx: Context) {
        runBlocking { SensorLog.readOnce(ctx, Sensor.TYPE_STEP_COUNTER)?.let { SensorLog.addSteps(ctx, it) } }
        refresh(ctx)
        val s = settings
        val left = s.goal - today()
        if (counting(ctx) && s.nudge && left > 0 && !Wellbeing.quietNow(ctx)) {
            val minutes = ((left / 100.0).roundToInt()).coerceIn(5, 90)
            Wellbeing.notify(
                ctx, Wellbeing.CH_STEPS, Wellbeing.ID_STEPS, "${left.grouped()} steps to go",
                "A $minutes-minute walk does it", Wellbeing.open(ctx, "STEPS", Wellbeing.ID_STEPS), 0xFFB45309,
            )
        }
        schedule(ctx)
    }
}

// ============================================================================ screen
@Composable
fun StepsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Steps.init(ctx) }
    @Suppress("UNUSED_VARIABLE") val v = Steps.version
    var allowedNow by remember { mutableStateOf(Steps.allowed(ctx)) }
    when {
        !Steps.present(ctx) -> WellPage("Steps", onBack) {
            Hint("This phone has no step sensor, so Steps can't count here. Everything else in Wellbeing works.", Modifier.padding(top = 30.dp), center = true)
        }
        !Steps.settings.setUp || !allowedNow -> StepsIntro(onBack, onAllowed = { allowedNow = true })
        else -> StepsToday(onBack)
    }
}

@Composable
private fun StepsIntro(onBack: () -> Unit, onAllowed: () -> Unit) {
    val ctx = LocalContext.current
    val t = stepsTint()
    var goal by rememberSaveable { mutableIntStateOf(Steps.settings.goal) }
    var nudge by rememberSaveable { mutableStateOf(Steps.settings.nudge) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            Steps.start(ctx, goal, nudge)
            onAllowed()
        } else {
            refused = true
        }
    }
    WellPage("Steps", onBack) {
        Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TintIcon(Icons.AutoMirrored.Rounded.DirectionsWalk, t, 96.dp)
            Text("Count your steps", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 18.dp))
            Text(
                "SyncUp uses your phone's own step sensor — no GPS, almost no battery. Keep the phone with you, in a pocket or hand.",
                fontSize = 14.5.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            )
        }
        Lab("Your daily goal", t.accent)
        Choices(listOf(5000, 8000, 10000, 12000), goal, { it.grouped() }, t, { goal = it })
        SwitchLine("Nudge me at 6 PM if I'm behind", "One gentle reminder a day", nudge, t.accent, { nudge = it })
        Spacer(Modifier.height(20.dp))
        if (refused) {
            Hint("Steps needs the Physical activity permission. You can allow it in SyncUp's settings.", center = true)
            BigButton("Open settings", t.accent, {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        } else {
            Hint("Android will ask you to allow Physical activity.", center = true)
            BigButton("Allow & start", t.accent, {
                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                    ask.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                } else {
                    Steps.start(ctx, goal, nudge)
                    onAllowed()
                }
            })
        }
    }
}

@Composable
private fun StepsToday(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val t = stepsTint()
    val s = Steps.settings
    // The counter, live while the page is open.
    var live by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                live = e.values[0]
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose {
            sm?.unregisterListener(listener)
            live?.let {
                SensorLog.addSteps(ctx, it)
                Steps.refresh(ctx)
            }
        }
    }
    val today = FDays.today()
    val mine = remember(live, Steps.version) { SensorLog.stepsByDay(ctx, 1, live).lastOrNull()?.second ?: 0 }
    val steps = maxOf(mine, Steps.on(today))
    val days = (6 downTo 0).map { FDays.add(today, -it) }
    val week = days.map { if (it == today) steps else Steps.on(it) }
    val met = steps >= s.goal
    WellPage("Steps", onBack) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Ring(steps / s.goal.toFloat(), Color(0xFFF59E0B), t.container) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(steps.grouped(), fontSize = 40.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text("of ${s.goal.grouped()} steps", fontSize = 14.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        Text(
            if (met) "Goal reached — nice walking!" else "${(s.goal - steps).grouped()} to go · about ${Steps.km(steps)} km walked today",
            fontSize = if (met) 16.sp else 14.sp, fontWeight = if (met) FontWeight.Medium else FontWeight.Normal,
            color = if (met) t.accent else cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Spacer(Modifier.height(12.dp))
        val past = (1..30).map { Steps.on(FDays.add(today, -it)) }.filter { it > 0 }
        Stats(
            "Streak" to "${Steps.streak()} day${if (Steps.streak() == 1) "" else "s"}",
            "Best" to ((past + steps).maxOrNull() ?: 0).grouped(),
            "Average" to if (past.isEmpty()) "—" else past.average().roundToInt().grouped(),
        )
        Lab("Last 7 days")
        Bars(week.map { it.toFloat() }, days.map { weekdayLetter(it) }, s.goal.toFloat(), Color(0xFFF59E0B), Color(if (cs.isLight) 0xFFFDE68A else 0xFF78350F), "Goal ${s.goal.grouped()}", Modifier.padding(horizontal = 20.dp))
        Lab("Settings")
        WCard {
            PlusMinusLine("Daily goal", s.goal.grouped(), { Steps.changeSettings(s.copy(goal = (s.goal - 500).coerceAtLeast(1000))) }, { Steps.changeSettings(s.copy(goal = (s.goal + 500).coerceAtMost(40000))) })
            SwitchLine("6 PM nudge", "Only when you're behind your goal", s.nudge, t.accent, { Steps.changeSettings(s.copy(nudge = it)) }, icon = { TintIcon(Icons.Rounded.Notifications, t) })
        }
        Hint("Counted while the phone is with you. Signed in, your other phones' steps count too — the higher number for each day.")
    }
}
