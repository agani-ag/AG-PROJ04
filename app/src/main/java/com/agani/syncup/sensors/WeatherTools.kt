package com.agani.syncup.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val AMBER = Color(0xFFD97706)

// ============================================================================ Weather trend
/** Pressure change over 3 hours, from the kept readings (null until there's a long enough history). */
private fun change3h(history: List<Pair<Long, Float>>, nowHpa: Float): Float? {
    val now = System.currentTimeMillis()
    // The reading nearest to 3 hours ago, if it's between 1 and 4 hours old; scaled to 3 hours.
    val past = history.filter { now - it.first in 3_600_000L..4 * 3_600_000L }
        .minByOrNull { abs(now - it.first - 3 * 3_600_000L) } ?: return null
    val hours = (now - past.first) / 3_600_000f
    return (nowHpa - past.second) * 3f / hours
}

private fun trendWords(d: Float?): Pair<String, Color?> = when {
    d == null -> "Steady so far — the trend shows after a few hours of readings" to null
    d <= -3f -> "↓ Falling fast — rain or wind likely soon" to AMBER
    d <= -1f -> "↓ Falling — the weather may get worse" to AMBER
    d < 1f -> "→ Steady — no big change coming" to null
    d < 3f -> "↑ Rising — clearer weather likely" to Color(0xFF16A34A)
    else -> "↑ Rising fast — clearing, may get windy" to Color(0xFF16A34A)
}

@Composable
fun WeatherTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val pressure = rememberSensor(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL)
    var background by remember { mutableStateOf(SensorLog.weatherInBackground(context)) }
    var hours by remember { mutableIntStateOf(6) }
    var history by remember { mutableStateOf(SensorLog.pressure(context)) }
    var smooth by remember { mutableFloatStateOf(-1f) }
    val raw = pressure.values?.get(0)
    LaunchedEffect(Unit) { SensorLog.weatherUsed(context) }
    LaunchedEffect(raw) {
        val v = raw ?: return@LaunchedEffect
        val first = smooth < 0
        smooth = if (first) v else smooth + (v - smooth) * .2f
        if (first) {
            SensorLog.addPressure(context, v)
            history = SensorLog.pressure(context)
        }
    }
    val now = smooth.takeIf { it > 0 }
    val d3 = now?.let { change3h(history, it) }
    val (words, color) = trendWords(d3)
    val dayAgo = history.firstOrNull { System.currentTimeMillis() - it.first <= 25 * 3_600_000L && System.currentTimeMillis() - it.first >= 23 * 3_600_000L }

    ToolPage("Weather trend", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(now?.let { "%.0f".format(it) } ?: "—", fontSize = 64.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                Text(" hPa", fontSize = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(words, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color ?: cs.primary, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(6, 24, 48).forEach { h ->
                    FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("$h h") })
                }
            }
            Spacer(Modifier.height(8.dp))
            PressureGraph(history, now, hours, Modifier.fillMaxWidth().height(170.dp))
            Spacer(Modifier.height(12.dp))
            Stats(
                (d3?.let { "%+.1f".format(it) } ?: "—") to "hPa in 3 h",
                (dayAgo?.let { "%.0f".format(it.second) } ?: "—") to "A day ago",
                (now?.let { val m = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it); if (SensorPrefs.imperial) "${(m * 3.28084f).roundToInt()} ft" else "${m.roundToInt()} m" } ?: "—") to "Height",
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Check every 30 minutes", fontSize = 15.sp, color = cs.onSurface)
                    Text(
                        if (background) "Keeps a reading in the background for the graph — almost no battery" else "Off — only while this screen is open, so no history",
                        fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                    )
                }
                Switch(checked = background, onCheckedChange = {
                    background = it
                    SensorLog.setWeatherInBackground(context, it)
                })
            }
            Spacer(Modifier.height(12.dp))
            Note("Uses the air-pressure sensor. Falling pressure usually means rain or wind; rising means clearer weather. No internet needed — it's a hint, not a forecast.")
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PressureGraph(history: List<Pair<Long, Float>>, now: Float?, hours: Int, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val line = cs.primary
    val end = System.currentTimeMillis()
    val start = end - hours * 3_600_000L
    val points = history.filter { it.first >= start } + listOfNotNull(now?.let { end to it })
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow).padding(horizontal = 10.dp, vertical = 12.dp)) {
        val labelH = 16.dp.toPx()
        val h = size.height - labelH
        if (points.size >= 2 && points.last().first - points.first().first >= 20 * 60_000L) {
            var lo = points.minOf { it.second }
            var hi = points.maxOf { it.second }
            if (hi - lo < 4f) {
                val mid = (hi + lo) / 2
                lo = mid - 2f
                hi = mid + 2f
            }
            fun x(t: Long) = ((t - start).toFloat() / (end - start)) * size.width
            fun y(v: Float) = h - (v - lo) / (hi - lo) * h
            val path = Path()
            points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
            drawPath(path, line, style = Stroke(3.dp.toPx()))
            listOf("%.0f".format(hi) to 0f, "%.0f".format(lo) to h).forEach { (label, yy) ->
                val t = measurer.measure(label, TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant))
                drawText(t, topLeft = Offset(0f, (yy - t.size.height / 2f).coerceIn(0f, h - t.size.height)))
            }
        } else {
            val t = measurer.measure("The graph fills in over the next hours", TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant))
            drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, (h - t.size.height) / 2f))
        }
        listOf("$hours h ago" to 0f, "now" to 1f).forEach { (label, f) ->
            val t = measurer.measure(label, TextStyle(fontSize = 10.5.sp, color = cs.onSurfaceVariant))
            drawText(t, topLeft = Offset((size.width * f - t.size.width * f), size.height - t.size.height))
        }
    }
}

// ============================================================================ Room climate
private fun dewPoint(t: Float, rh: Float): Float {
    val g = ln((rh / 100f).coerceIn(.01f, 1f)) + 17.62f * t / (243.12f + t)
    return 243.12f * g / (17.62f - g)
}

/** "Feels like": the heat index when it's hot and humid, else the temperature itself. */
private fun feelsLike(t: Float, rh: Float?): Float {
    if (rh == null || t < 27f || rh < 40f) return t
    val f = t * 9f / 5f + 32f
    val hi = -42.379f + 2.04901523f * f + 10.14333127f * rh - .22475541f * f * rh - .00683783f * f * f -
        .05481717f * rh * rh + .00122874f * f * f * rh + .00085282f * f * rh * rh - .00000199f * f * f * rh * rh
    return (hi - 32f) * 5f / 9f
}

@Composable
fun ClimateTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val hasTemp = remember { Part.THERMOMETER.present(context) }
    val hasHumidity = remember { Part.HUMIDITY.present(context) }
    // Some sensors send a junk first value; only believable readings count.
    val temp = rememberSensor(Sensor.TYPE_AMBIENT_TEMPERATURE, SensorManager.SENSOR_DELAY_NORMAL).values?.get(0)?.takeIf { it in -40f..85f }
    val rh = rememberSensor(Sensor.TYPE_RELATIVE_HUMIDITY, SensorManager.SENSOR_DELAY_NORMAL).values?.get(0)?.takeIf { it in 0f..100f }
    val imperial = SensorPrefs.imperial
    var lo by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var hi by remember { mutableFloatStateOf(-Float.MAX_VALUE) }
    LaunchedEffect(temp) {
        temp?.let {
            lo = min(lo, it)
            hi = max(hi, it)
        }
    }
    fun t(c: Float) = if (imperial) "${(c * 9 / 5 + 32).roundToInt()}°" else "${c.roundToInt()}°"
    val dew = if (temp != null && rh != null) dewPoint(temp, rh) else null
    val feels = temp?.let { feelsLike(it, rh) }
    val (comfort, comfortColor) = when {
        dew != null -> when {
            dew < 10 -> "Dry" to AMBER
            dew < 16 -> "Comfortable" to Color(0xFF16A34A)
            dew < 18 -> "A bit humid" to cs.primary
            dew < 21 -> "Humid" to AMBER
            dew < 24 -> "Sticky" to AMBER
            else -> "Very sticky" to Color(0xFFDC2626)
        }
        temp != null -> when {
            temp < 18 -> "Cool" to cs.primary
            temp <= 26 -> "Comfortable" to Color(0xFF16A34A)
            else -> "Warm" to AMBER
        }
        rh != null -> when {
            rh < 30 -> "Dry" to AMBER
            rh <= 60 -> "Comfortable" to Color(0xFF16A34A)
            else -> "Humid" to AMBER
        }
        else -> "—" to cs.onSurfaceVariant
    }
    // The advice follows the comfort rating, so the two never disagree.
    val feelsNote = feels?.takeIf { it - temp!! >= 1 }?.let { " — feels like ${t(it)}" } ?: ""
    val advice = when (comfort) {
        "Humid", "Sticky", "Very sticky" -> "$comfort$feelsNote. A fan or AC will help; clothes dry slowly."
        "A bit humid" -> "A bit humid$feelsNote — fine for most people."
        "Dry" -> "Dry air — drink some water. A bowl of water or a few plants help."
        "Warm" -> "Warm — keep cool and drink water."
        "Cool" -> "Cool — a bit chilly to sit still for long."
        "Comfortable" -> if (temp != null && temp > 30) "Hot — keep cool and drink water." else "Comfortable for most people."
        else -> "Waiting for a reading…"
    }

    ToolPage("Room climate", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                BigReading(Icons.Rounded.Thermostat, Color(0xFFEA580C), if (!hasTemp) "None" else temp?.let { t(it) } ?: "—", if (hasTemp) "Temperature" else "No thermometer", Modifier.weight(1f))
                BigReading(Icons.Rounded.WaterDrop, cs.primary, if (!hasHumidity) "None" else rh?.let { "${it.roundToInt()}%" } ?: "—", if (hasHumidity) "Humidity" else "No humidity sensor", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Note(advice)
            Spacer(Modifier.height(14.dp))
            Card {
                ClimateRow("Comfort", comfort, comfortColor)
                if (dew != null) ClimateRow("Dew point", t(dew), cs.onSurface)
                if (feels != null && abs(feels - temp!!) >= 1f) ClimateRow("Feels like", t(feels), cs.onSurface)
                if (lo != Float.MAX_VALUE) ClimateRow("Since you opened this", "${t(lo)} – ${t(hi)}", cs.onSurface)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !imperial, onClick = { SensorPrefs.setImperial(context, false) }, label = { Text("°C") })
                FilterChip(selected = imperial, onClick = { SensorPrefs.setImperial(context, true) }, label = { Text("°F") })
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Keep the phone out of your hand and away from the sun for a few minutes — its own warmth changes the reading.",
                fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun BigReading(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, value: String, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLow).padding(vertical = 20.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(8.dp))
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
        Text(label, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ClimateRow(label: String, value: String, color: Color) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
    }
}
