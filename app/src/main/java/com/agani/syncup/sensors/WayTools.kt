package com.agani.syncup.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.agani.syncup.browser.ui.StepSlider
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.ui.theme.success
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================ location helpers
private val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

internal fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

internal fun locationOn(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

/**
 * Location fixes from [provider] while on screen (and allowed). [oneShot] stops after the first fix —
 * the compass only needs one, for true north.
 */
@SuppressLint("MissingPermission")
@Composable
internal fun rememberFix(allowed: Boolean, provider: String = LocationManager.GPS_PROVIDER, intervalMs: Long = 1_000, oneShot: Boolean = false): State<Location?> {
    val context = LocalContext.current
    val fix = remember { mutableStateOf<Location?>(null) }
    val onScreen = rememberOnScreen()
    DisposableEffect(allowed, provider, onScreen) {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                fix.value = location
                if (oneShot) lm?.removeUpdates(this)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        if (allowed && onScreen && lm != null && hasLocation(context)) {
            if (oneShot && fix.value == null) {
                // A recent fix from any app is good enough for true north.
                fix.value = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
            }
            if (!oneShot || fix.value == null) {
                val use = listOf(provider, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).distinct()
                    .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                if (use != null) runCatching { lm.requestLocationUpdates(use, intervalMs, 0f, listener, Looper.getMainLooper()) }
            }
        }
        onDispose { lm?.removeUpdates(listener) }
    }
    return fix
}

/** "Allow location" / "Turn on Location" — whatever stands between a tool and the GPS. */
@Composable
internal fun LocationGate(why: String, access: Access, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val onScreen = rememberOnScreen()
    var on by remember { mutableStateOf(locationOn(context)) }
    LaunchedEffect(onScreen) { if (onScreen) on = locationOn(context) }
    when {
        !access.granted -> AccessCard("Allow location", why, access, Modifier.padding(16.dp))
        !on -> {
            val cs = MaterialTheme.colorScheme
            Column(
                Modifier.padding(16.dp).fillMaxWidth().appCard(CardShape).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Location is off", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("Turn on Location so the GPS can work.", fontSize = 13.5.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Turn on Location") }
            }
        }
        else -> content()
    }
}

internal fun latLon(value: Double, positive: Char, negative: Char): String =
    "%.4f° %c".format(abs(value), if (value >= 0) positive else negative)

private val WINDS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
internal fun windOf(deg: Float): String = WINDS[(((deg % 360) + 360) % 360 / 22.5f + .5f).toInt() % 16]

/** Shortest turn from [from] to [to], −180…180. */
internal fun turn(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f

// ============================================================================ Compass
@Composable
fun CompassTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val access = rememberAccess(*LOCATION)
    var heading by remember { mutableFloatStateOf(0f) }
    var seen by remember { mutableStateOf(false) }
    var trueNorth by remember { mutableStateOf(SensorPrefs.prefs(context).getBoolean("true_north", true)) }
    var hold by remember { mutableStateOf<Float?>(null) }
    val mag = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val fix by rememberFix(access.granted && trueNorth, provider = LocationManager.NETWORK_PROVIDER, intervalMs = 10_000, oneShot = true)
    val declination = fix?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination } ?: 0f
    val useTrue = trueNorth && fix != null

    // Rotation vector where the phone has it (smooth); else the motion + compass sensors together.
    val hasRotation = remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null }
    val r = remember { FloatArray(9) }
    val remapped = remember { FloatArray(9) }
    val o = remember { FloatArray(3) }
    val gravity = remember { FloatArray(3) }
    val field = remember { FloatArray(3) }
    fun update(ok: Boolean) {
        if (!ok) return
        SensorManager.getOrientation(r, o)
        // Held upright (screen facing you), point the top of the phone's back the way you look.
        if (abs(Math.toDegrees(o[1].toDouble())) > 50) {
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            SensorManager.getOrientation(remapped, o)
        }
        val raw = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)
        heading = if (!seen) raw else (heading + turn(heading, raw) * .15f + 360f) % 360f
        seen = true
    }
    if (hasRotation) {
        SensorStream(Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_UI) { e ->
            SensorManager.getRotationMatrixFromVector(r, if (e.values.size > 4) e.values.copyOf(4) else e.values)
            update(true)
        }
    } else {
        SensorStream(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_UI) { e -> e.values.copyInto(gravity, 0, 0, 3) }
        SensorStream(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_UI) { e ->
            e.values.copyInto(field, 0, 0, 3)
            update(SensorManager.getRotationMatrix(r, null, gravity, field))
        }
    }
    val shown = ((heading + if (useTrue) declination else 0f) + 360f) % 360f
    val poor = mag.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW

    ToolPage("Compass", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${shown.roundToInt() % 360}° ${windOf(shown)}", fontSize = 40.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.padding(top = 2.dp),
            )
            hold?.let { h ->
                val d = turn(shown, h)
                Text(
                    when {
                        abs(d) < 5 -> "On course ✓"
                        d > 0 -> "Turn right ${d.roundToInt()}°"
                        else -> "Turn left ${(-d).roundToInt()}°"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (abs(d) < 5) cs.success else cs.primary,
                )
            }
            CompassDial(shown, hold, Modifier.padding(top = 10.dp, bottom = 14.dp).fillMaxWidth(.84f).aspectRatio(1f))
            if (poor) {
                Note("The reading isn't steady — move the phone in a figure 8 a few times, away from metal and magnets.")
                Spacer(Modifier.height(10.dp))
            }
            Stats(
                (fix?.let { latLon(it.latitude, 'N', 'S') } ?: "—") to "Latitude",
                (fix?.let { latLon(it.longitude, 'E', 'W') } ?: "—") to "Longitude",
                (if (poor) "Poor" else if (mag.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) "Fair" else "Good") to "Accuracy",
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().appCard(CardShape).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("True north", fontSize = 15.sp, color = cs.onSurface)
                    Text(
                        when {
                            !trueNorth -> "Off — shows magnetic north"
                            !access.granted -> "Needs your location once"
                            fix == null -> "Finding where you are…"
                            else -> "Corrected by ${"%.1f".format(abs(declination))}° for where you are"
                        },
                        fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                    )
                }
                Switch(checked = trueNorth, onCheckedChange = {
                    trueNorth = it
                    SensorPrefs.prefs(context).edit().putBoolean("true_north", it).apply()
                    if (it && !access.granted) access.ask()
                })
            }
            if (trueNorth && !access.granted) TextButton(onClick = access.ask) { Text("Allow location") }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                if (hold == null) {
                    Button(onClick = { hold = shown }, enabled = seen, modifier = Modifier.weight(1f).height(48.dp)) { Text("Hold this direction", fontSize = 15.sp) }
                } else {
                    OutlinedButton(onClick = { hold = null }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Clear direction", fontSize = 15.sp) }
                }
            }
        }
    }
}

/**
 * The compass card, turned so the heading is at the top: a tick every 5°, numbers every 30°, N in red;
 * the letters stay upright. A blue mark above the card shows where the phone points.
 */
@Composable
private fun CompassDial(heading: Float, hold: Float?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val ink = cs.onSurface
    val muted = cs.onSurfaceVariant
    val north = cs.error
    val accent = cs.primary
    val card = cs.surfaceContainerLowest
    val rim = cs.outlineVariant
    val light = cs.isLight
    // Measured once; drawn every frame as the dial turns.
    val letters = remember(ink, north) {
        listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).map { (t, deg) ->
            deg to measurer.measure(t, TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = if (deg == 0) north else ink))
        }
    }
    val numbers = remember(muted) {
        (30 until 360 step 30).filter { it % 90 != 0 }.map { deg -> deg to measurer.measure("$deg", TextStyle(fontSize = 10.5.sp, color = muted)) }
    }
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        // Drawn on a 280-unit square: the card's edge at 124, ticks from 112 inward, letters at 84.
        val u = min(size.width, size.height) / 280f
        val radius = 124 * u
        if (light) drawCircle(Color(0x140F172A), radius + 1.5f * u, Offset(c.x, c.y + 1.5f * u))
        drawCircle(card, radius, c)
        drawCircle(rim, radius, c, style = Stroke(1.5f * u))
        rotate(-heading, c) {
            for (deg in 0 until 360 step 5) {
                val long = deg % 30 == 0
                val len = (if (long) 12 else if (deg % 10 == 0) 8 else 4) * u
                val a = Math.toRadians(deg.toDouble() - 90)
                val outer = Offset(c.x + 112 * u * cos(a).toFloat(), c.y + 112 * u * sin(a).toFloat())
                val inner = Offset(c.x + (112 * u - len) * cos(a).toFloat(), c.y + (112 * u - len) * sin(a).toFloat())
                drawLine(if (deg == 0) north else if (long) ink else muted, inner, outer, strokeWidth = (if (long) 2.2f else 1.3f) * u, cap = StrokeCap.Round)
            }
            hold?.let { h ->
                val a = Math.toRadians(h.toDouble() - 90)
                drawLine(accent, c, Offset(c.x + radius * .82f * cos(a).toFloat(), c.y + radius * .82f * sin(a).toFloat()), strokeWidth = 4 * u, cap = StrokeCap.Round)
            }
            // The needle: red to north, grey to south, turning with the dial.
            drawPath(Path().apply { moveTo(c.x, c.y - 64 * u); lineTo(c.x + 9 * u, c.y); lineTo(c.x - 9 * u, c.y); close() }, north)
            drawPath(Path().apply { moveTo(c.x, c.y + 64 * u); lineTo(c.x + 9 * u, c.y); lineTo(c.x - 9 * u, c.y); close() }, muted.copy(alpha = .45f))
        }
        // Letters and numbers go round with the dial but stay upright.
        fun place(deg: Int, r: Float, layout: androidx.compose.ui.text.TextLayoutResult) {
            val a = Math.toRadians(deg - heading - 90.0)
            val p = Offset(c.x + r * u * cos(a).toFloat(), c.y + r * u * sin(a).toFloat())
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
        letters.forEach { (deg, layout) -> place(deg, 84f, layout) }
        numbers.forEach { (deg, layout) -> place(deg, 88f, layout) }
        drawCircle(card, 7 * u, c)
        drawCircle(ink, 7 * u, c, style = Stroke(2 * u))
        // Where the phone points: a fixed mark above the card.
        drawPath(Path().apply { moveTo(c.x, c.y - 122 * u); lineTo(c.x - 9 * u, c.y - 136 * u); lineTo(c.x + 9 * u, c.y - 136 * u); close() }, accent)
    }
}

// ============================================================================ Speedometer
@Composable
fun SpeedometerTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val access = rememberAccess(*LOCATION)
    val beeper = rememberBeeper()
    val imperial = SensorPrefs.imperial
    var running by remember { mutableStateOf(true) }
    var windshield by remember { mutableStateOf(false) }
    var tripMeters by remember { mutableFloatStateOf(0f) }
    var topMps by remember { mutableFloatStateOf(0f) }
    var startedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var pausedFor by remember { mutableLongStateOf(0L) }
    var pausedAt by remember { mutableLongStateOf(0L) }
    var last by remember { mutableStateOf<Location?>(null) }
    var speed by remember { mutableFloatStateOf(0f) }
    var limitOn by remember { mutableStateOf(SensorPrefs.prefs(context).getBoolean("limit_on", false)) }
    var limit by remember { mutableFloatStateOf(SensorPrefs.prefs(context).getFloat("limit", 60f)) }
    var over by remember { mutableStateOf(false) }
    val fix by rememberFix(access.granted && running)
    KeepScreenOn()

    LaunchedEffect(fix) {
        val l = fix ?: return@LaunchedEffect
        val prev = last
        // The GPS's own speed; else (some phones say 0) from the distance — but only a move bigger than the GPS error.
        val moved = prev?.distanceTo(l) ?: 0f
        val mps = when {
            l.hasSpeed() && l.speed > 0f -> l.speed
            prev != null && l.time > prev.time && moved > maxOf(l.accuracy, prev.accuracy, 3f) -> moved / ((l.time - prev.time) / 1000f)
            else -> 0f
        }
        speed = if (mps < .5f) 0f else mps
        if (speed > topMps) topMps = speed
        if (prev != null && speed > 0f && l.accuracy <= 25f) tripMeters += prev.distanceTo(l)
        last = l
        val shown = if (imperial) speed * 2.23694f else speed * 3.6f
        if (limitOn && shown > limit && !over) {
            over = true
            beeper.beep(150, high = true)
            delay(250)
            beeper.beep(150, high = true)
        } else if (shown < limit - 3) {
            over = false
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val unit = if (imperial) "mph" else "km/h"
    val shownSpeed = if (imperial) speed * 2.23694f else speed * 3.6f
    val trip = if (imperial) tripMeters / 1609.344f else tripMeters / 1000f
    val top = if (imperial) topMps * 2.23694f else topMps * 3.6f
    val elapsed = ((if (running) now else pausedAt) - startedAt - pausedFor).coerceAtLeast(0) / 1000

    if (windshield) {
        BackHandler { windshield = false }
        // Mirrored, big and bright, to read in the car's glass at night.
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { windshield = false }, contentAlignment = Alignment.Center) {
            Column(Modifier.scale(scaleX = -1f, scaleY = 1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${shownSpeed.roundToInt()}", fontSize = 160.sp, fontWeight = FontWeight.Bold, style = TextStyle(fontFeatureSettings = "tnum"), color = Color(0xFF4ADE80))
                Text(unit, fontSize = 36.sp, color = Color(0xFF4ADE80))
            }
            Text("Tap to go back", fontSize = 13.sp, color = Color.White.copy(alpha = .5f), modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
        return
    }

    ToolPage("Speedometer", onBack) {
        LocationGate("The speedometer uses GPS while this screen is open — nothing else.", access) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SpeedGauge(shownSpeed, if (imperial) 100f else 160f, unit, over, Modifier.padding(top = 4.dp).fillMaxWidth(.85f).aspectRatio(1.2f))
                if (fix == null && running) {
                    Text("Waiting for GPS… go outside or near a window.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                }
                Stats(
                    "%.1f".format(trip) to (if (imperial) "miles this trip" else "km this trip"),
                    "${top.roundToInt()}" to "Top $unit",
                    "%d:%02d".format(elapsed / 60, elapsed % 60) to "Time",
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Speed limit alert", fontSize = 15.sp, color = cs.onSurface)
                        Text("Beeps once when you go over ${limit.roundToInt()} $unit", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = limitOn, onCheckedChange = {
                        limitOn = it
                        SensorPrefs.prefs(context).edit().putBoolean("limit_on", it).apply()
                    })
                }
                if (limitOn) {
                    StepSlider(
                        value = limit, range = 10f..150f, step = 5f, label = "limit",
                        onChange = { limit = (it / 5f).roundToInt() * 5f },
                        onChangeFinished = { SensorPrefs.prefs(context).edit().putFloat("limit", limit).apply() },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = !imperial, onClick = { SensorPrefs.setImperial(context, false) }, label = { Text("km/h") })
                    FilterChip(selected = imperial, onClick = { SensorPrefs.setImperial(context, true) }, label = { Text("mph") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { windshield = true }) {
                        Icon(Icons.Rounded.Flip, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Windshield view")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    OutlinedButton(onClick = {
                        tripMeters = 0f
                        topMps = 0f
                        startedAt = SystemClock.elapsedRealtime()
                        pausedFor = 0
                        pausedAt = startedAt
                        now = startedAt
                        last = null
                    }, modifier = Modifier.weight(1f)) { Text("Reset trip") }
                    Button(onClick = {
                        if (running) {
                            pausedAt = SystemClock.elapsedRealtime()
                            speed = 0f
                        } else {
                            pausedFor += SystemClock.elapsedRealtime() - pausedAt
                            last = null
                        }
                        running = !running
                    }, modifier = Modifier.weight(1f)) { Text(if (running) "Stop" else "Start") }
                }
                Text(
                    "GPS speed — keep your eyes on the road. Location is used only while this screen is open; the screen stays on.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeedGauge(value: Float, max: Float, unit: String, over: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val track = cs.surfaceContainerHigh
    val fill = if (over) Color(0xFFDC2626) else cs.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val d = min(size.width, size.height * 1.15f) - stroke
            val topLeft = Offset((size.width - d) / 2, stroke / 2 + 4.dp.toPx())
            drawArc(track, 135f, 270f, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(fill, 135f, 270f * (value / max).coerceIn(0f, 1f), false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${value.roundToInt()}", fontSize = 72.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = if (over) Color(0xFFDC2626) else cs.onSurface)
            Text(unit, fontSize = 16.sp, color = cs.onSurfaceVariant)
        }
    }
}

// ============================================================================ Height
@Composable
fun HeightTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val hasPressure = remember { Part.PRESSURE.present(context) }
    val access = rememberAccess(*LOCATION)
    val pressure = rememberSensor(Sensor.TYPE_PRESSURE)
    val fix by rememberFix(!hasPressure && access.granted, intervalMs = 2_000)
    val imperial = SensorPrefs.imperial
    var zero by remember { mutableStateOf<Float?>(null) }

    val metres: Float? = if (hasPressure) {
        pressure.values?.let { SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it[0]) }
    } else {
        fix?.takeIf { it.hasAltitude() }?.let {
            if (Build.VERSION.SDK_INT >= 34 && it.hasMslAltitude()) it.mslAltitudeMeters.toFloat() else it.altitude.toFloat()
        }
    }
    fun len(m: Float) = if (imperial) "${(m * 3.28084f).roundToInt()} ft" else "${m.roundToInt()} m"

    ToolPage("Height", onBack) {
        val body: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(28.dp))
                Text(metres?.let { len(it) } ?: "—", fontSize = 64.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface)
                Text("above sea level", fontSize = 16.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (hasPressure) "from the air-pressure sensor" else "from GPS",
                    fontSize = 13.sp, color = cs.primary,
                )
                if (metres == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(if (hasPressure) "Reading…" else "Waiting for GPS… go outside or near a window.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                val z = zero
                if (z != null && metres != null) {
                    val d = metres - z
                    Text(
                        "${if (d >= 0) "+" else "−"}${len(abs(d))} since you set zero" + if (hasPressure) " · about ${"%.1f".format(abs(d) / 3f)} floors" else "",
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Stats(
                    *buildList {
                        if (hasPressure) add((pressure.values?.let { "%.1f".format(it[0]) } ?: "—") to "Air pressure (hPa)")
                        if (!hasPressure) add((fix?.let { "±${it.accuracy.roundToInt()} m" } ?: "—") to "GPS accuracy")
                        add((if (hasPressure) "±10 m" else "±20 m") to "Usually within")
                    }.toTypedArray(),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { zero = null }, enabled = zero != null, modifier = Modifier.weight(1f)) { Text("Clear zero") }
                    Button(onClick = { zero = metres }, enabled = metres != null, modifier = Modifier.weight(1f)) { Text("Set this as zero") }
                }
                Spacer(Modifier.height(14.dp))
                Note(
                    if (hasPressure) "Air pressure changes with the weather, so the height can drift by a few metres over the day. Set zero at the bottom, then climb — it's good for counting floors."
                    else "This phone has no air-pressure sensor, so height comes from GPS: outdoors only, and it's rough (±20 m).",
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        if (hasPressure) body() else LocationGate("Without an air-pressure sensor, height comes from GPS. Location is used only while this screen is open.", access, body)
    }
}

// ============================================================================ GPS status
private class Sat(val system: Int, val svid: Int, val cn0: Float, val elevation: Float, val azimuth: Float, val used: Boolean)

private fun systemName(c: Int): String = when (c) {
    GnssStatus.CONSTELLATION_GPS -> "GPS (US)"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS (Japan)"
    7 -> "NavIC (India)" // CONSTELLATION_IRNSS
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    else -> "Other"
}

private fun systemLetter(c: Int): String = when (c) {
    GnssStatus.CONSTELLATION_GPS -> "G"
    GnssStatus.CONSTELLATION_GLONASS -> "R"
    GnssStatus.CONSTELLATION_GALILEO -> "E"
    GnssStatus.CONSTELLATION_BEIDOU -> "B"
    GnssStatus.CONSTELLATION_QZSS -> "Q"
    7 -> "N"
    GnssStatus.CONSTELLATION_SBAS -> "S"
    else -> "?"
}

private fun systemColor(c: Int): Color = when (c) {
    GnssStatus.CONSTELLATION_GPS -> Color(0xFF38BDF8)
    GnssStatus.CONSTELLATION_GLONASS -> Color(0xFF34D399)
    GnssStatus.CONSTELLATION_GALILEO -> Color(0xFFA78BFA)
    GnssStatus.CONSTELLATION_BEIDOU -> Color(0xFFF472B6)
    GnssStatus.CONSTELLATION_QZSS -> Color(0xFF2DD4BF)
    7 -> Color(0xFFF59E0B)
    else -> Color(0xFF94A3B8)
}

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("MissingPermission")
@Composable
fun GpsStatusTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val access = rememberAccess(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val fix by rememberFix(access.granted)
    var sats by remember { mutableStateOf<List<Sat>>(emptyList()) }
    var firstFixMs by remember { mutableStateOf<Int?>(null) }
    val onScreen = rememberOnScreen()
    val fine = access.granted && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    DisposableEffect(fine, onScreen) {
        val lm = context.getSystemService(LocationManager::class.java)
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                sats = (0 until status.satelliteCount).map {
                    Sat(status.getConstellationType(it), status.getSvid(it), status.getCn0DbHz(it), status.getElevationDegrees(it), status.getAzimuthDegrees(it), status.usedInFix(it))
                }
            }

            override fun onFirstFix(ttffMillis: Int) {
                firstFixMs = ttffMillis
            }
        }
        if (fine && onScreen && lm != null) runCatching { lm.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { lm?.unregisterGnssStatusCallback(callback) } }
    }
    KeepScreenOn()

    ToolPage("GPS status", onBack) {
        LocationGate("GPS status shows the satellites above you. It needs precise location while this screen is open.", access) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SkyPlot(sats, Modifier.fillMaxWidth(.88f).aspectRatio(1f))
                Spacer(Modifier.height(10.dp))
                val used = sats.count { it.used }
                Stats(
                    (fix?.let { "±${it.accuracy.roundToInt()} m" } ?: "—") to "Accuracy",
                    "$used / ${sats.size}" to "Used / in view",
                    (fix?.takeIf { it.hasAltitude() }?.let { if (SensorPrefs.imperial) "${(it.altitude * 3.28084).roundToInt()} ft" else "${it.altitude.roundToInt()} m" } ?: "—") to "Altitude",
                )
                Spacer(Modifier.height(12.dp))
                val systems = sats.groupBy { it.system }.toSortedMap()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    systems.forEach { (system, list) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(systemColor(system)))
                            Spacer(Modifier.width(6.dp))
                            Text("${systemName(system)} · ${list.count { it.used }}/${list.size}", fontSize = 12.5.sp, color = cs.onSurface)
                        }
                    }
                }
                if (sats.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Looking for satellites… they're easiest to see outdoors.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                firstFixMs?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Found your position in ${"%.1f".format(it / 1000f)} s", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                }
                fix?.let { l ->
                    Spacer(Modifier.height(10.dp))
                    Text("${latLon(l.latitude, 'N', 'S')}, ${latLon(l.longitude, 'E', 'W')}", fontSize = 14.sp, color = cs.onSurface)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    OutlinedButton(onClick = {
                        val l = fix ?: return@OutlinedButton
                        val text = "%.6f, %.6f".format(l.latitude, l.longitude)
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Location", text))
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }, enabled = fix != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy location")
                    }
                    Button(onClick = {
                        val l = fix ?: return@Button
                        val link = "https://maps.google.com/?q=%.6f,%.6f".format(l.latitude, l.longitude)
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "I'm here: $link"), null)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }, enabled = fix != null, modifier = Modifier.weight(1f)) { Text("Share location") }
                }
            }
        }
    }
}

@Composable
private fun SkyPlot(sats: List<Sat>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 - 4.dp.toPx()
        drawCircle(Color(0xFF0F172A), r, c)
        drawCircle(Color(0xFF334155), r, c, style = Stroke(2.dp.toPx()))
        drawCircle(Color(0xFF1E293B), r * 2 / 3, c, style = Stroke(1.5.dp.toPx()))
        drawCircle(Color(0xFF1E293B), r / 3, c, style = Stroke(1.5.dp.toPx()))
        drawLine(Color(0xFF1E293B), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        drawLine(Color(0xFF1E293B), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        val n = measurer.measure("N", TextStyle(fontSize = 13.sp, color = Color(0xFF64748B)))
        drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - r + 6.dp.toPx()))
        val dot = 11.dp.toPx()
        sats.sortedBy { it.used }.forEach { s ->
            // Overhead in the middle, the horizon at the edge; north up.
            val dist = r * (1f - (s.elevation.coerceIn(0f, 90f) / 90f)) * .92f
            val a = Math.toRadians(s.azimuth.toDouble() - 90)
            val p = Offset(c.x + dist * cos(a).toFloat(), c.y + dist * sin(a).toFloat())
            val color = if (s.used) systemColor(s.system) else Color(0xFF475569)
            drawCircle(color, dot, p)
            val t = measurer.measure(systemLetter(s.system), TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = if (s.used) Color(0xFF0F172A) else Color(0xFFCBD5E1)))
            drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y - t.size.height / 2f))
        }
    }
}
