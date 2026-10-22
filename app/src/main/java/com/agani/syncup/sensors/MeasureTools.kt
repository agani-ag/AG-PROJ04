package com.agani.syncup.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.StepSlider
import com.agani.syncup.ui.theme.appCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private val GOOD = Color(0xFF16A34A)

private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

/** Gravity on the phone's axes, smoothed: the gravity sensor where there is one, else the motion sensor. */
@Composable
private fun rememberGravity(): FloatArray? {
    val context = LocalContext.current
    val hasGravity = remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GRAVITY) != null }
    val g = rememberSensor(if (hasGravity) Sensor.TYPE_GRAVITY else Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
    val smooth = remember { FloatArray(3) }
    var seeded by remember { mutableStateOf(false) }
    val v = g.values ?: return null
    if (!seeded) {
        v.copyInto(smooth, 0, 0, 3)
        seeded = true
    } else {
        for (i in 0..2) smooth[i] += (v[i] - smooth[i]) * .2f
    }
    return smooth.copyOf()
}

// ============================================================================ Level
@Composable
fun LevelTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { SensorPrefs.prefs(context) }
    val beeper = rememberBeeper()
    var mode by remember { mutableIntStateOf(0) } // 0 flat, 1 against an edge
    var beepOn by remember { mutableStateOf(prefs.getBoolean("level_beep", true)) }
    var locked by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    var zeroX by remember { mutableFloatStateOf(prefs.getFloat("level_zx", 0f)) }
    var zeroY by remember { mutableFloatStateOf(prefs.getFloat("level_zy", 0f)) }
    var zeroEdge by remember { mutableFloatStateOf(prefs.getFloat("level_ze", 0f)) }
    var wasLevel by remember { mutableStateOf(false) }
    val g = rememberGravity()

    // Flat: left–right and front–back tilt. Edge: how far the phone's edge is from level (nearest 90°).
    val live = g?.let {
        val roll = deg(atan2(it[0], it[2])) - zeroX
        val pitch = deg(atan2(it[1], it[2])) - zeroY
        val a = deg(atan2(it[0], it[1]))
        val edge = (((a + 45f + 360f) % 90f) - 45f) - zeroEdge
        Triple(roll, pitch, edge)
    }
    val (roll, pitch, edge) = locked ?: live ?: Triple(0f, 0f, 0f)
    val tilt = if (mode == 0) sqrt(roll * roll + pitch * pitch) else abs(edge)
    val level = tilt < .3f
    LaunchedEffect(level, beepOn, locked) {
        if (level && !wasLevel && beepOn && locked == null && live != null) beeper.beep(90, high = true)
        wasLevel = level
    }

    ToolPage("Level", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Choice(listOf("Flat on a surface", "Against an edge"), mode, { mode = it })
            Spacer(Modifier.height(18.dp))
            if (mode == 0) {
                BubbleTarget(roll, pitch, level, Modifier.fillMaxWidth(.78f).aspectRatio(1f))
            } else {
                BubbleTube(edge, level, Modifier.fillMaxWidth().height(90.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("%.1f°".format(tilt), fontSize = 52.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = if (level) GOOD else cs.onSurface)
            Text(
                if (level) "Level ✓" else if (mode == 0) "Tilted — the bubble shows the high side" else "Turn it ${"%.1f".format(abs(edge))}° ${if (edge > 0) "clockwise" else "anticlockwise"}",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (level) GOOD else cs.onSurfaceVariant,
            )
            if (locked != null) Text("Reading locked", fontSize = 13.sp, color = cs.primary)
            Spacer(Modifier.height(16.dp))
            if (mode == 0) {
                Stats("%.1f°".format(abs(roll)) to "Left–right", "%.1f°".format(abs(pitch)) to "Front–back")
            } else {
                Note("Hold the phone's long or short side against the picture, shelf or door frame.")
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Beep when level", fontSize = 15.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = beepOn, onCheckedChange = {
                    beepOn = it
                    prefs.edit().putBoolean("level_beep", it).apply()
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                OutlinedButton(onClick = {
                    val gv = g ?: return@OutlinedButton
                    if (mode == 0) {
                        zeroX = deg(atan2(gv[0], gv[2]))
                        zeroY = deg(atan2(gv[1], gv[2]))
                        prefs.edit().putFloat("level_zx", zeroX).putFloat("level_zy", zeroY).apply()
                    } else {
                        val a = deg(atan2(gv[0], gv[1]))
                        zeroEdge = ((a + 45f + 360f) % 90f) - 45f
                        prefs.edit().putFloat("level_ze", zeroEdge).apply()
                    }
                }, modifier = Modifier.weight(1f)) { Text("Set this as zero") }
                Button(onClick = { locked = if (locked == null) live else null }, modifier = Modifier.weight(1f)) {
                    Text(if (locked == null) "Lock reading" else "Unlock")
                }
            }
            if (zeroX != 0f || zeroY != 0f || zeroEdge != 0f) {
                TextButton(onClick = {
                    zeroX = 0f
                    zeroY = 0f
                    zeroEdge = 0f
                    prefs.edit().remove("level_zx").remove("level_zy").remove("level_ze").apply()
                }) { Text("Clear the zero") }
            }
            Text(
                "\"Set this as zero\" fixes a phone with a camera bump: lay it on something you know is level, then tap it.",
                fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun BubbleTarget(roll: Float, pitch: Float, level: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val ring = cs.outlineVariant
    val bubble = if (level) GOOD else cs.primary
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2
        drawCircle(cs.surfaceContainerLow, r, c)
        drawCircle(ring, r, c, style = Stroke(2.dp.toPx()))
        drawCircle(ring, r / 2, c, style = Stroke(1.5.dp.toPx()))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        // The bubble floats to the high side: 10° tilt puts it at the edge.
        val br = r * .18f
        val scale = (r - br) / 10f
        // Gravity on +x means the right side is higher; the bubble goes up-hill (screen y grows downward).
        var dx = roll * scale
        var dy = -pitch * scale
        val d = sqrt(dx * dx + dy * dy)
        if (d > r - br) {
            dx *= (r - br) / d
            dy *= (r - br) / d
        }
        drawCircle(bubble.copy(alpha = .85f), br, Offset(c.x + dx, c.y + dy))
        drawCircle(Color.White.copy(alpha = .5f), br * .35f, Offset(c.x + dx - br * .3f, c.y + dy - br * .3f))
        drawCircle(cs.onSurface, br * 1.08f, c, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun BubbleTube(angle: Float, level: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val bubble = if (level) GOOD else cs.primary
    Canvas(modifier) {
        val h = size.height * .6f
        val top = (size.height - h) / 2
        drawRoundRect(cs.surfaceContainerLow, Offset(0f, top), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawRoundRect(cs.outlineVariant, Offset(0f, top), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2), style = Stroke(2.dp.toPx()))
        val bw = h * .9f
        val travel = (size.width - bw - h * .2f) / 2
        val x = size.width / 2 + (angle / 10f).coerceIn(-1f, 1f) * travel
        drawRoundRect(bubble.copy(alpha = .85f), Offset(x - bw / 2, top + h * .1f), Size(bw, h * .8f), androidx.compose.ui.geometry.CornerRadius(h * .4f))
        drawLine(cs.onSurface, Offset(size.width / 2 - bw * .6f, top), Offset(size.width / 2 - bw * .6f, top + h), 2.dp.toPx())
        drawLine(cs.onSurface, Offset(size.width / 2 + bw * .6f, top), Offset(size.width / 2 + bw * .6f, top + h), 2.dp.toPx())
    }
}

// ============================================================================ Ruler & angle
@Composable
fun RulerTool(onBack: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    ToolPage("Ruler & angle", onBack) {
        Choice(listOf("Ruler", "Angle", "Tilt"), mode, { mode = it }, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        when (mode) {
            0 -> Ruler()
            1 -> Protractor()
            else -> Tilt()
        }
    }
}

@Composable
private fun Ruler() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { SensorPrefs.prefs(context) }
    val measurer = rememberTextMeasurer()
    var fine by remember { mutableFloatStateOf(prefs.getFloat("ruler_fine", 1f)) }
    var tuning by remember { mutableStateOf(false) }
    // Real size: the screen's own dots per inch, fine-tuned by the person if it's off.
    val pxPerCm = context.resources.displayMetrics.ydpi / 2.54f * fine
    var a by remember { mutableFloatStateOf(24f) }
    var b by remember { mutableFloatStateOf(24f + pxPerCm * 8f) }
    val ink = cs.onSurface
    val sky = cs.primary
    Column(Modifier.fillMaxSize()) {
        val cm = abs(b - a) / pxPerCm
        Text(
            "%.1f cm · %.2f in".format(cm, cm / 2.54f),
            fontSize = 26.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).align(Alignment.End),
        )
        Canvas(
            Modifier.weight(1f).fillMaxWidth().pointerInput(pxPerCm) {
                var dragging = 0
                detectDragGestures(
                    onDragStart = { p -> dragging = if (abs(p.y - a) < abs(p.y - b)) 1 else 2 },
                    onDrag = { change, delta ->
                        change.consume()
                        if (dragging == 1) a = (a + delta.y).coerceIn(0f, size.height.toFloat()) else b = (b + delta.y).coerceIn(0f, size.height.toFloat())
                    },
                )
            },
        ) {
            // Centimetres down the left edge, inches down the right.
            var mm = 0
            while (mm * pxPerCm / 10f < size.height) {
                val y = mm * pxPerCm / 10f
                val len = when {
                    mm % 10 == 0 -> 34.dp.toPx()
                    mm % 5 == 0 -> 22.dp.toPx()
                    else -> 12.dp.toPx()
                }
                drawLine(ink, Offset(0f, y), Offset(len, y), 1.2.dp.toPx())
                if (mm % 10 == 0 && mm > 0) {
                    val t = measurer.measure("${mm / 10}", TextStyle(fontSize = 13.sp, color = ink))
                    drawText(t, topLeft = Offset(38.dp.toPx(), y - t.size.height / 2f))
                }
                mm++
            }
            val pxPerIn = pxPerCm * 2.54f
            var sixteenth = 0
            while (sixteenth * pxPerIn / 16f < size.height) {
                val y = sixteenth * pxPerIn / 16f
                val len = when {
                    sixteenth % 16 == 0 -> 34.dp.toPx()
                    sixteenth % 8 == 0 -> 24.dp.toPx()
                    sixteenth % 4 == 0 -> 16.dp.toPx()
                    else -> 9.dp.toPx()
                }
                drawLine(ink, Offset(size.width - len, y), Offset(size.width, y), 1.2.dp.toPx())
                if (sixteenth % 16 == 0 && sixteenth > 0) {
                    val t = measurer.measure("${sixteenth / 16}\"", TextStyle(fontSize = 13.sp, color = ink))
                    drawText(t, topLeft = Offset(size.width - 38.dp.toPx() - t.size.width, y - t.size.height / 2f))
                }
                sixteenth++
            }
            val top = min(a, b)
            val bottom = max(a, b)
            drawRect(sky.copy(alpha = .12f), Offset(0f, top), Size(size.width, bottom - top))
            listOf(a, b).forEach { y ->
                drawLine(sky, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
                drawCircle(sky, 11.dp.toPx(), Offset(size.width / 2, y))
            }
        }
        if (tuning) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Put a bank card (8.56 cm) between the lines and move this until they match.", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                StepSlider(
                    value = fine, range = .9f..1.1f, step = .002f, label = "size",
                    onChange = { fine = it },
                    onChangeFinished = { prefs.edit().putFloat("ruler_fine", fine).apply() },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "Lay the thing along the left edge; drag the blue lines to its ends.",
                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = {
                tuning = !tuning
                if (tuning) b = a + pxPerCm * 8.56f
            }) { Text(if (tuning) "Done" else "Fine-tune") }
        }
    }
}

@Composable
private fun Protractor() {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    var arm1 by remember { mutableFloatStateOf(0f) } // degrees, 0 = right, counted anticlockwise
    var arm2 by remember { mutableFloatStateOf(60f) }
    val angle = abs(arm2 - arm1)
    val ink = cs.onSurface
    val sky = cs.primary
    Column(Modifier.fillMaxSize()) {
        Text(
            "%.0f°".format(angle), fontSize = 44.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
        Canvas(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp).pointerInput(Unit) {
                var which = 0
                detectDragGestures(
                    onDragStart = { p ->
                        val c = Offset(size.width / 2f, size.height * .8f)
                        val ang = deg(atan2(c.y - p.y, p.x - c.x)).coerceIn(0f, 180f)
                        which = if (abs(ang - arm1) < abs(ang - arm2)) 1 else 2
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val c = Offset(size.width / 2f, size.height * .8f)
                        val p = change.position
                        val ang = deg(atan2(c.y - p.y, p.x - c.x)).let { if (it < -90) 180f else it.coerceIn(0f, 180f) }
                        if (which == 1) arm1 = ang else arm2 = ang
                    },
                )
            },
        ) {
            val c = Offset(size.width / 2f, size.height * .8f)
            val r = min(size.width / 2f, size.height * .75f)
            drawArc(cs.surfaceContainerLow, 180f, 180f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
            drawArc(cs.outlineVariant, 180f, 180f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2.dp.toPx()))
            for (d in 0..180) {
                val rad = Math.toRadians(d.toDouble())
                val len = if (d % 10 == 0) 18.dp.toPx() else if (d % 5 == 0) 12.dp.toPx() else 6.dp.toPx()
                val o = Offset(c.x + r * cos(rad).toFloat(), c.y - r * sin(rad).toFloat())
                val i = Offset(c.x + (r - len) * cos(rad).toFloat(), c.y - (r - len) * sin(rad).toFloat())
                drawLine(ink, i, o, 1.dp.toPx())
                if (d % 30 == 0) {
                    val t = measurer.measure("$d", TextStyle(fontSize = 11.sp, color = ink))
                    val p = Offset(c.x + (r - 32.dp.toPx()) * cos(rad).toFloat(), c.y - (r - 32.dp.toPx()) * sin(rad).toFloat())
                    drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y - t.size.height / 2f))
                }
            }
            drawArc(sky.copy(alpha = .2f), -max(arm1, arm2), angle, true, Offset(c.x - r * .35f, c.y - r * .35f), Size(r * .7f, r * .7f))
            listOf(arm1, arm2).forEach { d ->
                val rad = Math.toRadians(d.toDouble())
                val end = Offset(c.x + r * cos(rad).toFloat(), c.y - r * sin(rad).toFloat())
                drawLine(sky, c, end, 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(sky, 12.dp.toPx(), end)
            }
            drawCircle(ink, 5.dp.toPx(), c)
        }
        Text(
            "Put the corner on the dot and drag the two arms along the sides.",
            fontSize = 12.5.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun Tilt() {
    val cs = MaterialTheme.colorScheme
    val g = rememberGravity()
    var held by remember { mutableStateOf<Float?>(null) }
    val live = g?.let {
        val n = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]).coerceAtLeast(.01f)
        deg(acos((it[2] / n).coerceIn(-1f, 1f)))
    }
    val angle = held ?: live ?: 0f
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text("%.1f°".format(angle), fontSize = 64.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface)
        Text(
            if (angle < 89.5f) "Slope ${"%.1f".format(tan(Math.toRadians(angle.toDouble())) * 100)} %" else "Straight up",
            fontSize = 16.sp, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Canvas(Modifier.fillMaxWidth(.7f).height(120.dp)) {
            val rad = Math.toRadians(angle.toDouble().coerceAtMost(89.0))
            val base = Offset(0f, size.height)
            val end = Offset(size.width, size.height - (size.width * tan(rad)).toFloat().coerceAtMost(size.height))
            drawLine(cs.outlineVariant, base, Offset(size.width, size.height), 3.dp.toPx())
            drawLine(cs.primary, base, end, 5.dp.toPx(), cap = StrokeCap.Round)
        }
        Spacer(Modifier.height(24.dp))
        Note("Lay the phone on the slope, ramp or roof — its back flat on the surface.")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { held = if (held == null) live else null }) { Text(if (held == null) "Hold reading" else "Let go") }
    }
}

// ============================================================================ Light meter
private fun lightWords(lux: Float): String = when {
    lux < 5 -> "Dark — night"
    lux < 50 -> "Dim — a lamp at night"
    lux < 200 -> "Room light — a bit dim to read"
    lux < 700 -> "Office light — fine for reading"
    lux < 5_000 -> "Bright — near a window"
    lux < 25_000 -> "Daylight — in the shade"
    else -> "Sunlight"
}

@Composable
fun LightTool(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Light sensors report only when the light changes, so each reading is shown as it is.
    val light = rememberSensor(Sensor.TYPE_LIGHT)
    var top by remember { mutableFloatStateOf(0f) }
    val raw = light.values?.get(0)
    LaunchedEffect(raw) {
        val v = raw ?: return@LaunchedEffect
        if (v > top) top = v
    }
    val lux = (raw ?: 0f).coerceAtLeast(0f)
    ToolPage("Light meter", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (raw == null) "—" else "%,d".format(lux.roundToInt()), fontSize = 64.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface)
                Text(" lux", fontSize = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(lightWords(lux), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.primary)
            Spacer(Modifier.height(24.dp))
            LogScale(
                value = lux, marks = listOf("Night" to 1f, "Room" to 100f, "Office" to 500f, "Shade" to 10_000f, "Sun" to 100_000f),
                lo = 1f, hi = 100_000f, modifier = Modifier.fillMaxWidth().height(64.dp),
            )
            Spacer(Modifier.height(20.dp))
            Card {
                LightRow("Reading / study", "needs 300–500 lux", lux, 300f, 1_000f)
                LightRow("Computer work", "300–500 lux, no glare", lux, 300f, 750f)
                LightRow("Plants (indoor)", "most need 1,000+ lux", lux, 1_000f, 50_000f)
            }
            Spacer(Modifier.height(14.dp))
            Stats("%,d".format(top.roundToInt()) to "Brightest seen")
            Spacer(Modifier.height(14.dp))
            Note("Point the top of the screen at the light — the sensor is next to the front camera.")
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LightRow(title: String, sub: String, lux: Float, low: Float, high: Float) {
    val cs = MaterialTheme.colorScheme
    val (word, color) = when {
        lux < low -> "Low" to Color(0xFFD97706)
        lux > high -> "Bright" to Color(0xFFD97706)
        else -> "OK" to GOOD
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = cs.onSurface)
            Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Text(word, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** A white rounded card around rows. */
@Composable
internal fun Card(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerLowest)
            .then(Modifier.padding(vertical = 4.dp)),
    ) { content() }
}

/** A bar on a log scale with labelled marks, and where [value] sits. */
@Composable
private fun LogScale(value: Float, marks: List<Pair<String, Float>>, lo: Float, hi: Float, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        fun x(v: Float) = ((log10(v.coerceIn(lo, hi)) - log10(lo)) / (log10(hi) - log10(lo))) * size.width
        val barH = 12.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF64748B), Color(0xFFFDE68A), Color(0xFFFBBF24))),
            Offset(0f, 0f), Size(size.width, barH), androidx.compose.ui.geometry.CornerRadius(barH / 2),
        )
        marks.forEach { (label, v) ->
            val t = measurer.measure("$label\n${if (v >= 1000) "${(v / 1000).roundToInt()}k" else v.roundToInt().toString()}", TextStyle(fontSize = 10.5.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center))
            drawText(t, topLeft = Offset((x(v) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), barH + 6.dp.toPx()))
        }
        val px = x(value.coerceAtLeast(lo))
        drawCircle(cs.primary, 9.dp.toPx(), Offset(px, barH / 2))
        drawCircle(Color.White, 4.dp.toPx(), Offset(px, barH / 2))
    }
}

// ============================================================================ Sound meter
private fun soundWords(db: Float): String = when {
    db < 30 -> "Very quiet"
    db < 45 -> "Quiet room"
    db < 60 -> "Talking"
    db < 70 -> "Busy office"
    db < 80 -> "Busy street"
    db < 90 -> "Loud — heavy traffic"
    db < 100 -> "Very loud"
    else -> "Painful — protect your ears"
}

/**
 * How loud it is around the phone, in dB, ten times a second while [active] (microphone allowed).
 * Nothing is recorded or kept — each tenth of a second is measured and dropped.
 */
@SuppressLint("MissingPermission")
@Composable
internal fun rememberLoudness(active: Boolean, onLevel: (Float) -> Unit) {
    val onScreen = rememberOnScreen()
    val latest = remember { arrayOf(onLevel) }
    latest[0] = onLevel
    LaunchedEffect(active, onScreen) {
        if (!active || !onScreen) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val rate = 44_100
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4_410 * 2)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2) }.getOrNull()
                ?: return@withContext
            try {
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext
                rec.startRecording()
                val buf = ShortArray(4_410)
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / n).coerceAtLeast(1.0)
                    val db = (20 * log10(rms)).toFloat().coerceIn(0f, 120f)
                    withContext(Dispatchers.Main) { latest[0](db) }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }
}

@Composable
fun SoundTool(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val access = rememberAccess(Manifest.permission.RECORD_AUDIO)
    var paused by remember { mutableStateOf(false) }
    var now by remember { mutableFloatStateOf(0f) }
    val history = remember { mutableStateListOf<Float>() }
    var lo by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var hi by remember { mutableFloatStateOf(0f) }
    var sum by remember { mutableFloatStateOf(0f) }
    var count by remember { mutableIntStateOf(0) }
    rememberLoudness(access.granted && !paused) { db ->
        now = if (count == 0) db else now + (db - now) * .5f
        history.add(db)
        if (history.size > 300) history.removeAt(0)
        lo = min(lo, db)
        hi = max(hi, db)
        sum += db
        count++
    }
    ToolPage("Sound meter", onBack) {
        if (!access.granted) {
            AccessCard("Allow the microphone", "The sound meter listens to how loud it is while this screen is open. Nothing is recorded.", access, Modifier.padding(16.dp))
            return@ToolPage
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (count == 0) "—" else "${now.roundToInt()}", fontSize = 64.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface)
                Text(" dB", fontSize = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(if (count == 0) "Listening…" else soundWords(now), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (now >= 85) Color(0xFFDC2626) else cs.primary)
            Spacer(Modifier.height(16.dp))
            LineGraph(history, 20f, 110f, Modifier.fillMaxWidth().height(150.dp), danger = 85f)
            Text("Last 30 seconds", fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
            Spacer(Modifier.height(10.dp))
            Stats(
                (if (count == 0) "—" else "${lo.roundToInt()}") to "Lowest",
                (if (count == 0) "—" else "${(sum / count).roundToInt()}") to "Average",
                (if (count == 0) "—" else "${hi.roundToInt()}") to "Highest",
            )
            Spacer(Modifier.height(12.dp))
            Card {
                listOf("Quiet room" to 30, "Talking" to 60, "Traffic" to 80, "Concert" to 110).forEach { (what, db) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(what, fontSize = 13.5.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                        Text("$db dB", fontSize = 13.5.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Note("A guide, not a certified sound meter — phone microphones differ. Nothing is recorded.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                OutlinedButton(onClick = {
                    history.clear()
                    lo = Float.MAX_VALUE
                    hi = 0f
                    sum = 0f
                    count = 0
                }, modifier = Modifier.weight(1f)) { Text("Reset") }
                Button(onClick = { paused = !paused }, modifier = Modifier.weight(1f)) { Text(if (paused) "Go on" else "Pause") }
            }
        }
    }
}

/** A simple line graph of [points] between [lo] and [hi]; above [danger] it turns red. */
@Composable
internal fun LineGraph(points: List<Float>, lo: Float, hi: Float, modifier: Modifier, danger: Float? = null, slots: Int = 300) {
    val cs = MaterialTheme.colorScheme
    val line = cs.primary
    Canvas(modifier.appCard(RoundedCornerShape(16.dp))) {
        val step = size.width / (slots - 1).coerceAtLeast(1)
        fun y(v: Float) = size.height - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
        danger?.let { d -> drawLine(Color(0xFFDC2626).copy(alpha = .4f), Offset(0f, y(d)), Offset(size.width, y(d)), 1.dp.toPx()) }
        if (points.size < 2) return@Canvas
        val path = Path()
        val start = slots - points.size
        points.forEachIndexed { i, v ->
            val x = (start + i) * step
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawPath(path, line, style = Stroke(2.dp.toPx()))
    }
}

// ============================================================================ Vibration
private fun shakeWords(ms2: Float): String = when {
    ms2 < .05f -> "Still"
    ms2 < .3f -> "Slight"
    ms2 < 1f -> "Light shaking"
    ms2 < 3f -> "Strong"
    else -> "Very strong"
}

@Composable
fun VibrationTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val hasLinear = remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null }
    var paused by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Float>() }
    var peak by remember { mutableFloatStateOf(0f) }
    var bin by remember { mutableFloatStateOf(0f) }
    var binStart by remember { mutableStateOf(0L) }
    val grav = remember { FloatArray(3) }
    SensorStream(if (hasLinear) Sensor.TYPE_LINEAR_ACCELERATION else Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME) { e ->
        if (paused) return@SensorStream
        val m = if (hasLinear) {
            sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        } else {
            // Take gravity out: what's left is the shaking.
            var s = 0f
            for (i in 0..2) {
                grav[i] = grav[i] * .9f + e.values[i] * .1f
                val d = e.values[i] - grav[i]
                s += d * d
            }
            sqrt(s)
        }
        bin = max(bin, m)
        val t = SystemClock.elapsedRealtime()
        if (t - binStart >= 40) {
            history.add(bin)
            if (history.size > 250) history.removeAt(0)
            if (bin > peak) peak = bin
            bin = 0f
            binStart = t
        }
    }
    val current = history.takeLast(12).maxOrNull() ?: 0f
    ToolPage("Vibration", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.2f".format(current), fontSize = 60.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface)
                Text(" m/s²", fontSize = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(shakeWords(current), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.primary)
            Spacer(Modifier.height(16.dp))
            LineGraph(history, 0f, max(1f, peak * 1.1f), Modifier.fillMaxWidth().height(170.dp), slots = 250)
            Text("Last 10 seconds", fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
            Spacer(Modifier.height(10.dp))
            Stats("%.2f".format(peak) to "Strongest (m/s²)", "%.3f g".format(peak / 9.81f) to "In g")
            Spacer(Modifier.height(12.dp))
            Note("Put the phone on what you want to check — a washing machine, a table, the floor, a car seat. Keep your hands off.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                OutlinedButton(onClick = {
                    history.clear()
                    peak = 0f
                }, modifier = Modifier.weight(1f)) { Text("Reset") }
                Button(onClick = { paused = !paused }, modifier = Modifier.weight(1f)) { Text(if (paused) "Go on" else "Pause") }
            }
        }
    }
}

// ============================================================================ Steps
@Composable
fun StepsTool(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { SensorPrefs.prefs(context) }
    val access = if (Build.VERSION.SDK_INT >= 29) rememberAccess(Manifest.permission.ACTIVITY_RECOGNITION) else remember { Access(true, false) {} }
    val counter = rememberSensor(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL)
    var goal by remember { mutableIntStateOf(prefs.getInt("step_goal", 10_000)) }
    var editGoal by remember { mutableStateOf(false) }
    val live = counter.values?.get(0)
    var firstDay by remember { mutableStateOf(false) }
    LaunchedEffect(access.granted, live != null) {
        if (!access.granted || live == null) return@LaunchedEffect
        // No reading from before today: today counts from now.
        firstDay = (SensorLog.firstStepsAt(context) ?: Long.MAX_VALUE) >= SensorLog.todayStart()
        SensorLog.addSteps(context, live)
        SensorLog.setStepsOn(context)
    }
    DisposableEffect(Unit) {
        onDispose { counter.values?.get(0)?.let { if (SensorLog.stepsAllowed(context)) SensorLog.addSteps(context, it) } }
    }
    val week = remember(live) { SensorLog.stepsByDay(context, 7, live) }
    val today = week.last().second
    val imperial = SensorPrefs.imperial
    val km = today * .75f / 1000f

    ToolPage("Steps", onBack) {
        if (!access.granted) {
            AccessCard("Allow physical activity", "Steps reads the phone's own step counter. Android asks once for \"Physical activity\". Your steps stay on this phone.", access, Modifier.padding(16.dp))
            return@ToolPage
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(.7f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                val ring = cs.primary
                val track = cs.surfaceContainerHigh
                Canvas(Modifier.fillMaxSize()) {
                    val s = 16.dp.toPx()
                    drawArc(track, -90f, 360f, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s))
                    drawArc(ring, -90f, 360f * (today.toFloat() / goal).coerceIn(0f, 1f), false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (live == null) "—" else "%,d".format(today), fontSize = 44.sp, fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"), color = cs.onSurface)
                    Text("of ${"%,d".format(goal)} today", fontSize = 14.sp, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Stats(
                (if (imperial) "%.1f mi".format(km / 1.609f) else "%.1f km".format(km)) to "walked",
                "${(today * 100 / goal.coerceAtLeast(1))}%" to "of your goal",
                "%,d".format(week.map { it.second }.average().roundToInt()) to "a day this week",
            )
            Spacer(Modifier.height(16.dp))
            Text("This week", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            WeekBars(week, goal, Modifier.fillMaxWidth().height(130.dp))
            Spacer(Modifier.height(14.dp))
            if (firstDay) {
                Note("Today's count started when you opened Steps. From tomorrow it's the whole day.")
                Spacer(Modifier.height(8.dp))
            }
            Note("Counted by the phone's own step sensor — even when SyncUp is closed, with no battery cost. Kept only on this phone.")
            OutlinedButton(onClick = { editGoal = true }, modifier = Modifier.padding(vertical = 14.dp)) { Text("Change goal") }
        }
    }

    if (editGoal) {
        var g by remember { mutableFloatStateOf(goal.toFloat()) }
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text("Daily goal") },
            text = {
                Column {
                    Text("%,d steps".format(g.roundToInt()), fontSize = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    StepSlider(value = g, range = 1_000f..30_000f, step = 500f, label = "goal", onChange = { g = (it / 500f).roundToInt() * 500f })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    goal = g.roundToInt()
                    prefs.edit().putInt("step_goal", goal).apply()
                    editGoal = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editGoal = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WeekBars(week: List<Pair<Long, Int>>, goal: Int, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val letters = arrayOf("S", "M", "T", "W", "T", "F", "S")
    Canvas(modifier) {
        val top = max(goal, week.maxOf { it.second }).toFloat().coerceAtLeast(1f)
        val labelH = 18.dp.toPx()
        val barW = size.width / week.size * .55f
        val gy = (size.height - labelH) * (1 - goal / top)
        drawLine(cs.outlineVariant, Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx())
        week.forEachIndexed { i, (day, steps) ->
            val cx = size.width / week.size * (i + .5f)
            val h = (size.height - labelH) * (steps / top)
            drawRoundRect(
                if (i == week.lastIndex) cs.primary else cs.primary.copy(alpha = .45f),
                Offset(cx - barW / 2, size.height - labelH - h), Size(barW, h.coerceAtLeast(2.dp.toPx())),
                androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
            val dow = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK) - 1
            val t = measurer.measure(letters[dow], TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant))
            drawText(t, topLeft = Offset(cx - t.size.width / 2f, size.height - t.size.height))
        }
    }
}
