package com.agani.syncup.sensors

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard

// ============================================================================ parts of the phone
/** A part of the phone a tool needs — a sensor, the GPS, the camera… */
enum class Part(val label: String, val short: String) {
    MOTION("Motion sensor (accelerometer)", "motion sensor"),
    GYRO("Rotation sensor (gyroscope)", "rotation sensor"),
    COMPASS("Compass sensor (magnetometer)", "compass sensor"),
    LIGHT("Light sensor", "light sensor"),
    PROXIMITY("Proximity sensor", "proximity sensor"),
    PRESSURE("Air-pressure sensor (barometer)", "air-pressure sensor"),
    THERMOMETER("Thermometer", "thermometer"),
    HUMIDITY("Humidity sensor", "humidity sensor"),
    STEP_COUNTER("Step counter", "step counter"),
    GPS("GPS", "GPS"),
    CAMERA("Camera", "camera"),
    FLASH("Camera flash", "camera flash"),
    MIC("Microphone", "microphone"),
    BLUETOOTH("Bluetooth", "Bluetooth"),
    WIFI("Wi-Fi", "Wi-Fi"),
    ;

    val sensorType: Int?
        get() = when (this) {
            MOTION -> Sensor.TYPE_ACCELEROMETER
            GYRO -> Sensor.TYPE_GYROSCOPE
            COMPASS -> Sensor.TYPE_MAGNETIC_FIELD
            LIGHT -> Sensor.TYPE_LIGHT
            PROXIMITY -> Sensor.TYPE_PROXIMITY
            PRESSURE -> Sensor.TYPE_PRESSURE
            THERMOMETER -> Sensor.TYPE_AMBIENT_TEMPERATURE
            HUMIDITY -> Sensor.TYPE_RELATIVE_HUMIDITY
            STEP_COUNTER -> Sensor.TYPE_STEP_COUNTER
            else -> null
        }

    fun present(context: Context): Boolean {
        sensorType?.let { return context.getSystemService(SensorManager::class.java)?.getDefaultSensor(it) != null }
        val pm = context.packageManager
        return when (this) {
            GPS -> pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
            CAMERA -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            FLASH -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
            MIC -> pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            BLUETOOTH -> pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            WIFI -> pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
            else -> false
        }
    }
}

// ============================================================================ the tools
enum class SensorGroup(val title: String) {
    WAY("Find your way"), MEASURE("Measure"), WEATHER("Weather"), FIND("Find & see"), HANDY("Handy"),
}

/**
 * Every tool on the Sensors page. [needs] must all be in the phone, or — with [anyOf] — at least one.
 * A tool that can't work is still listed, greyed ("Not in this phone"), never hidden.
 */
enum class SensorTool(
    val title: String,
    val blurb: String,
    val group: SensorGroup,
    val needs: List<Part>,
    val anyOf: Boolean = false,
    /** Why it can't work, in a sentence — shown on the "Not in this phone" sheet. */
    val missingWhy: String = "",
) {
    COMPASS("Compass", "Which way you're facing", SensorGroup.WAY, listOf(Part.COMPASS, Part.MOTION), missingWhy = "it can't tell which way is north"),
    SPEEDOMETER("Speedometer", "Speed & trip from GPS", SensorGroup.WAY, listOf(Part.GPS), missingWhy = "it can't measure your speed"),
    HEIGHT("Height", "Metres above sea level", SensorGroup.WAY, listOf(Part.PRESSURE, Part.GPS), anyOf = true, missingWhy = "it can't measure height"),
    GPS_STATUS("GPS status", "Satellites above you", SensorGroup.WAY, listOf(Part.GPS), missingWhy = "it can't see the satellites"),
    LEVEL("Level", "Spirit level", SensorGroup.MEASURE, listOf(Part.MOTION), missingWhy = "it can't tell when it's level"),
    RULER("Ruler & angle", "Ruler, protractor, tilt", SensorGroup.MEASURE, emptyList()),
    LIGHT("Light meter", "How bright it is", SensorGroup.MEASURE, listOf(Part.LIGHT), missingWhy = "it can't measure light"),
    SOUND("Sound meter", "How loud it is", SensorGroup.MEASURE, listOf(Part.MIC), missingWhy = "it can't hear how loud it is"),
    VIBRATION("Vibration", "Shakes & tremors", SensorGroup.MEASURE, listOf(Part.MOTION), missingWhy = "it can't feel vibration"),
    STEPS("Steps", "Today's steps", SensorGroup.MEASURE, listOf(Part.STEP_COUNTER), missingWhy = "it can't count steps"),
    WEATHER("Weather trend", "Rain coming?", SensorGroup.WEATHER, listOf(Part.PRESSURE), missingWhy = "it can't tell how the weather is changing. Many phones don't have one"),
    CLIMATE("Room climate", "Room temperature & humidity", SensorGroup.WEATHER, listOf(Part.THERMOMETER, Part.HUMIDITY), anyOf = true, missingWhy = "it can't measure the room. Most phones don't have them — a few do"),
    METAL("Metal finder", "Nails, pipes, wires", SensorGroup.FIND, listOf(Part.COMPASS), missingWhy = "it can't sense metal"),
    MAGNIFIER("Magnifier", "Read small print", SensorGroup.FIND, listOf(Part.CAMERA), missingWhy = "it can't zoom in on things"),
    COLOUR("Colour picker", "Name any colour", SensorGroup.FIND, listOf(Part.CAMERA), missingWhy = "it can't see colours"),
    EARBUDS("Find my earbuds", "Hot / cold finder", SensorGroup.FIND, listOf(Part.BLUETOOTH), missingWhy = "it can't look for Bluetooth things"),
    WIFI("Wi-Fi finder", "Best signal spot", SensorGroup.FIND, listOf(Part.WIFI), missingWhy = "it can't measure Wi-Fi"),
    FLASHLIGHT("Flashlight", "Torch & screen light", SensorGroup.FIND, emptyList()),
    WAVE("Wave to control", "Pause music with a wave", SensorGroup.HANDY, listOf(Part.PROXIMITY), missingWhy = "it can't see your hand"),
    SPEAKER("Speaker clean", "Water out, tones", SensorGroup.HANDY, emptyList()),
    PHONE_CHECK("Phone check", "Test every part", SensorGroup.HANDY, emptyList()),
    ;

    fun missing(context: Context): List<Part> {
        val gone = needs.filterNot { it.present(context) }
        return if (anyOf) (if (gone.size == needs.size) gone else emptyList()) else gone
    }

    fun works(context: Context): Boolean = missing(context).isEmpty()

    /** How it measures when it isn't the usual way ("uses GPS"), else null. */
    fun via(context: Context): String? = when (this) {
        HEIGHT -> if (!Part.PRESSURE.present(context) && Part.GPS.present(context)) "uses GPS" else null
        CLIMATE -> when {
            !Part.THERMOMETER.present(context) && Part.HUMIDITY.present(context) -> "humidity only"
            Part.THERMOMETER.present(context) && !Part.HUMIDITY.present(context) -> "temperature only"
            else -> null
        }
        FLASHLIGHT -> if (!Part.FLASH.present(context)) "screen light" else null
        else -> null
    }
}

// ============================================================================ settings shared by the tools
/** Units and small per-tool settings, on this phone only. */
object SensorPrefs {
    private const val NAME = "sensors"
    private var loaded = false
    /** km/h, metres, °C by default; true = mph, feet / inches, °F. */
    var imperial by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        imperial = prefs(context).getBoolean("imperial", false)
    }

    fun setImperial(context: Context, on: Boolean) {
        imperial = on
        prefs(context).edit().putBoolean("imperial", on).apply()
    }

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

// ============================================================================ live readings
/** True while the app is on screen: every tool stops its sensors when SyncUp goes to the background. */
@Composable
fun rememberOnScreen(): Boolean {
    val owner = LocalLifecycleOwner.current
    var on by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> on = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return on
}

/** The latest values of one sensor, and how much to trust them (SensorManager.SENSOR_STATUS_*). */
class Reading {
    var values by mutableStateOf<FloatArray?>(null)
        internal set
    var accuracy by mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        internal set
    val x: Float get() = values?.getOrNull(0) ?: 0f
}

/** Listens to sensor [type] while this is on screen and SyncUp is in front. */
@Composable
fun rememberSensor(type: Int, rate: Int = SensorManager.SENSOR_DELAY_UI): Reading {
    val context = LocalContext.current
    val reading = remember(type) { Reading() }
    val onScreen = rememberOnScreen()
    DisposableEffect(type, rate, onScreen) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(type)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                reading.values = e.values.clone()
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
                reading.accuracy = accuracy
            }
        }
        if (onScreen && sensor != null) sm.registerListener(listener, sensor, rate)
        onDispose { sm?.unregisterListener(listener) }
    }
    return reading
}

/** Calls [onEvent] for every reading of [type] (for fast sensors where only a summary goes on screen). */
@Composable
fun SensorStream(type: Int, rate: Int = SensorManager.SENSOR_DELAY_GAME, onEvent: (SensorEvent) -> Unit) {
    val context = LocalContext.current
    val onScreen = rememberOnScreen()
    val latest = remember { arrayOf(onEvent) }
    latest[0] = onEvent
    DisposableEffect(type, rate, onScreen) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(type)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = latest[0](e)
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (onScreen && sensor != null) sm.registerListener(listener, sensor, rate)
        onDispose { sm?.unregisterListener(listener) }
    }
}

/** The screen stays on while this is shown (speedometer, finders). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** A short beep for "level", "over the limit", the finders. Released with the screen. */
@Composable
fun rememberBeeper(): Beeper {
    val beeper = remember { Beeper() }
    DisposableEffect(Unit) { onDispose { beeper.release() } }
    return beeper
}

class Beeper {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    fun beep(ms: Int = 70, high: Boolean = false) {
        runCatching { tone?.startTone(if (high) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, ms) }
    }

    fun release() {
        runCatching { tone?.release() }
    }
}

// ============================================================================ permissions
/** A permission (or a few) a tool needs, asked only from that tool. */
class Access(val granted: Boolean, val denied: Boolean, val ask: () -> Unit)

@Composable
fun rememberAccess(vararg permissions: String): Access {
    val context = LocalContext.current
    fun has() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(has()) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = has()
        denied = !granted
    }
    // Back from Settings with the permission switched on.
    val onScreen = rememberOnScreen()
    if (onScreen && !granted && has()) granted = true
    return remember(granted, denied) { Access(granted, denied) { launcher.launch(arrayOf(*permissions)) } }
}

/** The card a tool shows until it's allowed what it needs. */
@Composable
fun AccessCard(title: String, text: String, access: Access, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().appCard(CardShape).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        if (access.denied) {
            Button(onClick = { openAppSettings(context) }) { Text("Open settings") }
            TextButton(onClick = access.ask) { Text("Ask again") }
        } else {
            Button(onClick = access.ask) { Text("Allow") }
        }
    }
}

fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// ============================================================================ layout bits
/** Sky blue, the Sensors tile's colour. */
object Sky {
    val light = Color(0xFF0369A1)
    val dark = Color(0xFF7DD3FC)
    val containerLight = Color(0xFFE0F2FE)
    val containerDark = Color(0xFF0E2333)
}

/** A tool's page: back arrow + title, then [content]. [dark] for the night-style tools. */
@Composable
fun ToolPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp)) {
                BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
                actions()
            }
            content()
        }
    }
}

/** A soft grey note with an info icon (how to hold the phone, what the number means). */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
    }
}

/** Small numbers side by side, each in its own card: the label, then the value ("Lowest · 48"). */
@Composable
fun Stats(vararg items: Pair<String, String>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.fillMaxWidth()) {
        items.forEach { (value, label) ->
            Column(Modifier.weight(1f).appCard(RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(label, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    value, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** A pill switch between a few choices ("Ruler | Angle | Tilt"). */
@Composable
fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    PillTabs(options, selected, onSelect, modifier)
