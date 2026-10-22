package com.agani.syncup.forest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The forest's sky, land and weather: everything except the depth-sorted trees and things. Drawn in the
 * 360 × 734 design space exactly as scene() in docs/mockup/forest-art/forest_v2_page.py draws it. The seeded
 * scatterings (stars, tufts, wildflowers, rain, fireflies, the far tree line) use PyRandom with the mockup's
 * seeds, so they land where the mockup puts them.
 *
 * [top] is the design y at the top of the screen: negative when the screen is taller than the design (the sky
 * then reaches higher), positive when it is shorter (the sky's top is cut off).
 */

private const val W = ForestRules.W
private const val H = ForestRules.H
private const val HORIZON = ForestRules.HORIZON
private const val TAU = (2 * PI).toFloat()

/** The fractional part (0 ≤ f < 1). */
internal fun sceneFrac(v: Float): Float = v - floor(v)

/** 0 → 1 → 0 as [f] goes 0 → 1 (SVG values="a;b;a"). */
private fun sceneTri(f: Float): Float = 1f - abs(2f * f - 1f)

/** An SVG keyframe list (evenly spaced, linear) at [f] 0–1. */
private fun sceneKeyed(v: FloatArray, f: Float): Float {
    val p = f.coerceIn(0f, 1f) * (v.size - 1)
    val i = min(p.toInt(), v.size - 2)
    return v[i] + (v[i + 1] - v[i]) * (p - i)
}

/** A path a moving thing follows at an even speed (SVG animateMotion, "paced"). Cubic segments: x0, y0, then
 *  (c1x, c1y, c2x, c2y, x, y) for each segment. */
internal class SceneTrack(x0: Float, y0: Float, vararg c: Float) {
    private val xs: FloatArray
    private val ys: FloatArray
    private val d: FloatArray

    init {
        val segs = c.size / 6
        val n = segs * STEPS + 1
        xs = FloatArray(n)
        ys = FloatArray(n)
        d = FloatArray(n)
        xs[0] = x0
        ys[0] = y0
        var px = x0
        var py = y0
        var i = 1
        for (s in 0 until segs) {
            val b = s * 6
            for (k in 1..STEPS) {
                val u = k / STEPS.toFloat()
                val v = 1 - u
                val x = v * v * v * px + 3 * v * v * u * c[b] + 3 * v * u * u * c[b + 2] + u * u * u * c[b + 4]
                val y = v * v * v * py + 3 * v * v * u * c[b + 1] + 3 * v * u * u * c[b + 3] + u * u * u * c[b + 5]
                xs[i] = x
                ys[i] = y
                d[i] = d[i - 1] + hypot(x - xs[i - 1], y - ys[i - 1])
                i++
            }
            px = c[b + 4]
            py = c[b + 5]
        }
    }

    /** The point [f] (0–1, wrapping) of the way along. */
    fun at(f: Float): Offset {
        val target = sceneFrac(f) * d[d.size - 1]
        var lo = 0
        var hi = d.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (d[mid] <= target) lo = mid else hi = mid
        }
        val seg = d[hi] - d[lo]
        val u = if (seg > 0f) (target - d[lo]) / seg else 0f
        return Offset(xs[lo] + (xs[hi] - xs[lo]) * u, ys[lo] + (ys[hi] - ys[lo]) * u)
    }

    private companion object {
        const val STEPS = 24
    }
}

/** A leaf or petal drifting down along its wavy path (forest_art.falling_leaf). */
private class SceneLeaf(x: Float, y: Float, val dur: Float, val color: Color) {
    val track = SceneTrack(x, y, x + 10, y + 20, x - 10, y + 40, x + 4, y + 60, x + 16, y + 78, x - 2, y + 96, x + 10, y + 116)
}

/** One cloud (three white ovals, as one shape so they don't double up where they overlap). */
private class SceneCloud(x: Float, y: Float, s: Float, val alpha: Float) {
    val path = Path().apply {
        addOval(Rect(x - 30 * s, y - 9 * s, x + 30 * s, y + 9 * s))
        addOval(Rect(x + 14 * s - 17 * s, y - 7 * s - 11 * s, x + 14 * s + 17 * s, y - 7 * s + 11 * s))
        addOval(Rect(x - 12 * s - 13 * s, y - 4 * s - 8 * s, x - 12 * s + 13 * s, y - 4 * s + 8 * s))
    }
}

/** SceneArtwork that never changes: built once, shared by every scene. */
private object SceneArtwork {
    val hills = Path().apply {
        moveTo(0f, 258f)
        cubicTo(40f, 232f, 76f, 224f, 118f, 236f)
        cubicTo(160f, 248f, 190f, 210f, 236f, 222f)
        cubicTo(282f, 234f, 320f, 202f, 360f, 222f)
        // Down under the meadow's edge, so no sky shows between them.
        lineTo(360f, 340f); lineTo(0f, 340f); close()
    }
    val dunes = Path().apply {
        moveTo(0f, 290f)
        cubicTo(60f, 270f, 120f, 282f, 180f, 276f)
        cubicTo(240f, 270f, 300f, 262f, 360f, 280f)
        lineTo(360f, 330f); lineTo(0f, 330f); close()
    }
    val meadow = Path().apply {
        moveTo(0f, HORIZON - 6)
        cubicTo(70f, HORIZON - 22, 140f, HORIZON - 16, 206f, HORIZON - 8)
        cubicTo(272f, HORIZON, 320f, HORIZON - 18, 360f, HORIZON - 10)
        lineTo(360f, H); lineTo(0f, H); close()
    }

    /** The far tree line: a row of soft crowns along the hills (green and picnic days). */
    val treeLine = Path().apply {
        val r = PyRandom(7)
        for (x in 0..360 step 9) {
            val cy = 264 + r.uniform(-4.0, 4.0)
            val rad = r.uniform(6.0, 11.0)
            addOval(Rect(Offset(x.toFloat(), cy.toFloat()), rad.toFloat()))
        }
    }
    val mist = Brush.verticalGradient(0f to Color(0x00F6EDD6), .5f to Color(0xB3F6EDD6), 1f to Color(0x00F6EDD6), startY = 244f, endY = 300f)
    /** The desert's heat haze over the far land: it fades in and out again, so it never ends in a line. */
    val haze = Brush.verticalGradient(
        0f to Color(0x00FFF4D6), .45f to Color(0x73FFF4D6), 1f to Color(0x00FFF4D6), startY = HORIZON - 30, endY = HORIZON + 82,
    )
    val puff = Brush.radialGradient(0f to Color(0xFF5E5650), .55f to Color(0xB35E5650), 1f to Color(0x005E5650), center = Offset.Zero, radius = 1f)

    /** Winter mist: a white band over the far meadow, blurred at its edges (blur6 in the mockup). */
    val winterMist = Brush.verticalGradient(
        0f to Color(0x00FFFFFF), .21f to Color(0x6BFFFFFF), .79f to Color(0x6BFFFFFF), 1f to Color(0x00FFFFFF),
        startY = HORIZON - 52, endY = HORIZON + 62,
    )
    val night = Brush.verticalGradient(0f to Color(0x000A1A2C), 1f to Color(0x800A1A2C), startY = 185f, endY = 215f)
    val landFlash = Brush.verticalGradient(0f to Color(0x00FFFFFF), 1f to Color.White, startY = 200f, endY = 300f)

    val tuftColors = arrayOf(Color(0xFF5F9A45), Color(0xFF6FA84E), Color(0xFF7FB55A), Color(0xFF4E8A3A))
    val backTufts = tufts(60, 0f, HORIZON - 4, W, 380f, 3, 3.0, 6.0)
    val frontTufts = tufts(90, 0f, 470f, W, H, 6, 6.0, 12.0)
    val tuftStroke = Stroke(1.2f, cap = StrokeCap.Round)

    val flowerColors = arrayOf(Color.White, Color(0xFFFDE68A), Color(0xFFF9A8D4), Color(0xFFC4B5FD))
    val flowers = Array(4) { Path() }.also { out ->
        val r = PyRandom(5)
        repeat(46) {
            val cx = r.uniform(0.0, 360.0).toFloat()
            val cy = r.uniform(380.0, 734.0).toFloat()
            val rad = r.uniform(1.0, 2.0).toFloat()
            out[r.below(4)].addOval(Rect(Offset(cx, cy), rad))
        }
    }

    /** Fireflies: x, y, twinkle seconds, begin, drift dx, dy, glow radius. */
    val flies = FloatArray(30 * 7).also { a ->
        val r = PyRandom(23)
        for (i in 0 until 30) {
            a[i * 7] = r.uniform(10.0, 350.0).toFloat()
            a[i * 7 + 1] = r.uniform(340.0, H - 40.0).toFloat()
            a[i * 7 + 2] = r.uniform(2.2, 4.6).toFloat()
            a[i * 7 + 3] = r.uniform(0.0, 3.0).toFloat()
            a[i * 7 + 4] = r.uniform(-14.0, 14.0).toFloat()
            a[i * 7 + 5] = r.uniform(-10.0, 6.0).toFloat()
            a[i * 7 + 6] = r.uniform(7.0, 12.0).toFloat()
        }
    }
    val flyGlow = Brush.radialGradient(0f to Color(0xF2FFF6B0), .35f to Color(0x73FFE47A), 1f to Color(0x00FFE47A), center = Offset.Zero, radius = 1f)
    val flyCore = Color(0xFFFFF8C8)
    private val FLICKER = floatArrayOf(.15f, 1f, .35f, .9f, .15f)
    fun twinkle(t: Float, dur: Float, begin: Float): Float = sceneKeyed(FLICKER, sceneFrac((t - begin) / dur))

    val heavyRain = rainDrops(220, 5, heavy = true)
    val rainColor = Color(0xFFE8F1F6)
    val lightStroke = Stroke(1f, cap = StrokeCap.Round)
    val heavyStroke = Stroke(1.4f, cap = StrokeCap.Round)

    val stormClouds = arrayOf(
        floatArrayOf(60f, 70f, 110f, 46f) to Color(0xFF4A5361), floatArrayOf(230f, 60f, 140f, 52f) to Color(0xFF434B58),
        floatArrayOf(330f, 110f, 90f, 40f) to Color(0xFF525B68), floatArrayOf(140f, 120f, 120f, 44f) to Color(0xFF5B6471),
    )
    val bolt = Path().apply {
        moveTo(214f, 96f); lineTo(196f, 148f); lineTo(210f, 148f); lineTo(198f, 196f); lineTo(232f, 134f); lineTo(217f, 134f); lineTo(229f, 96f); close()
    }
    val BOLT = floatArrayOf(0f, 0f, 1f, 0f, .8f, 0f, 0f)
    val FLASH = floatArrayOf(0f, 0f, .28f, 0f, .18f, 0f, 0f)

    val dayClouds = arrayOf(SceneCloud(230f, 92f, 1f, .9f), SceneCloud(320f, 150f, .7f, .8f), SceneCloud(130f, 60f, .6f, .7f))
    val rainClouds = arrayOf(SceneCloud(90f, 80f, 1.6f, .95f * .9f), SceneCloud(250f, 70f, 1.8f, .95f * .9f), SceneCloud(330f, 120f, 1.2f, .9f * .9f))

    val moonGlow = Brush.radialGradient(0f to Color(0x8CF7F1DC), 1f to Color(0x00F7F1DC), center = MOON_C, radius = 68f)

    // Things that fly along paths (the mockup's animateMotion paths, "s" segments written out).
    val monarch = SceneTrack(98f, 560f, 128f, 530f, 158f, 550f, 138f, 580f, 118f, 610f, 78f, 590f, 98f, 560f)
    val blue = SceneTrack(236f, 500f, 266f, 480f, 286f, 510f, 266f, 530f, 246f, 550f, 216f, 530f, 236f, 500f)
    val dragonfly = SceneTrack(270f, 560f, 290f, 546f, 310f, 564f, 296f, 578f, 282f, 592f, 260f, 582f, 270f, 560f)
    val dragon = SceneTrack(-70f, 190f, 60f, 110f, 220f, 170f, 300f, 110f, 380f, 50f, 420f, 150f, 440f, 130f)

    val leaf = Path().apply { moveTo(0f, 0f); quadraticTo(4f, -3f, 8f, 0f); quadraticTo(4f, 3f, 0f, 0f); close() }
    val autumnLeaves = arrayOf(SceneLeaf(120f, 340f, 9f, Color(0xFFD9A441)), SceneLeaf(250f, 380f, 11f, Color(0xFFC58A2C)), SceneLeaf(60f, 420f, 10f, Color(0xFFC9902C)))
    val petals = arrayOf(SceneLeaf(140f, 330f, 10f, Color(0xFFF5B6CB)), SceneLeaf(260f, 360f, 12f, Color(0xFFF7C6D6)), SceneLeaf(80f, 400f, 9f, Color(0xFFF5B6CB)))
    val dryLeaves = arrayOf(SceneLeaf(140f, 330f, 8f, Color(0xFFB07A2C)), SceneLeaf(240f, 360f, 10f, Color(0xFF9A6A28)), SceneLeaf(320f, 340f, 9f, Color(0xFFA8742C)))

    /** Sky birds: x, y, size, seconds per crossing. */
    val birds = floatArrayOf(250f, 120f, 1f, 14f, 270f, 106f, .8f, 17f)
    val birdStroke = Stroke(1.3f, cap = StrokeCap.Round)

    val volcanoGlow = Brush.radialGradient(0f to Color(0x99FF7A2E), .5f to Color(0x40FF7A2E), 1f to Color(0x00FF7A2E), center = Offset.Zero, radius = 1f)
    val foamStroke = Stroke(1.1f, cap = StrokeCap.Round)

    val rainbow = arrayOf(Color(0xFFFF6B6B), Color(0xFFFFB86B), Color(0xFFFFE36B), Color(0xFF7BD389), Color(0xFF6BB8FF), Color(0xFF8D7BFF))
    val rainbowPaint = Paint().apply { alpha = .55f }
    val rainbowStroke = Stroke(7f)

    private fun tufts(n: Int, x0: Float, y0: Float, x1: Float, y1: Float, seed: Long, h0: Double, h1: Double): Array<Path> {
        val r = PyRandom(seed)
        val out = Array(4) { Path() }
        repeat(n) {
            val x = r.uniform(x0.toDouble(), x1.toDouble()).toFloat()
            val y = r.uniform(y0.toDouble(), y1.toDouble()).toFloat()
            val k = (y - y0) / max(1f, y1 - y0)
            val hh = (r.uniform(h0, h1) * (.6 + .8 * k)).toFloat()
            val p = out[r.below(4)]
            p.moveTo(x, y); p.quadraticTo(x - 1.5f, y - hh * .6f, x - 3f, y - hh)
            p.moveTo(x, y); p.quadraticTo(x + .5f, y - hh * .7f, x + 1f, y - hh * 1.1f)
            p.moveTo(x, y); p.quadraticTo(x + 2f, y - hh * .5f, x + 4f, y - hh * .8f)
        }
        return out
    }
}

/** Rain: x, y, length, seconds per fall, for each drop (forest_v2_page.rain). */
private fun rainDrops(n: Int, seed: Long, heavy: Boolean): FloatArray {
    val r = PyRandom(seed)
    val a = FloatArray(n * 4)
    for (i in 0 until n) {
        a[i * 4] = r.uniform(0.0, W + 40.0).toFloat()
        a[i * 4 + 1] = r.uniform(-20.0, H.toDouble()).toFloat()
        a[i * 4 + 2] = r.uniform(9.0, if (heavy) 16.0 else 11.0).toFloat()
        a[i * 4 + 3] = r.uniform(.5, .9).toFloat()
    }
    return a
}

private val MOON_C = Offset(268f, 104f)
private const val MOON_R = 20f

/** The desert day's volcano on the far hills: its foot (behind the dunes) and width. */
internal const val VOLCANO_X = 250f
internal const val VOLCANO_Y = 282f
internal const val VOLCANO_W = 100f

/** The volcano's smoke puffs (start delay in seconds, drift) and lava bombs (seconds per throw, sideways speed, height). */
private val PUFFS = floatArrayOf(0f, -.2f, 1.1f, .3f, 2.3f, .1f, 3.2f, .5f, 4.4f, -.1f, 5.3f, .35f)
private val BOMBS = floatArrayOf(1.7f, -9f, 30f, 2.1f, 7f, 38f, 1.9f, 12f, 26f, 2.4f, -4f, 44f, 1.6f, 3f, 34f, 2.2f, -13f, 28f)

/** The Moon as seen from India: lit on the right while waxing, on the left while waning. Null [lit] = new moon. */
private class SceneMoon(litFraction: Double, waxing: Boolean) {
    val glowAlpha = (.25 + .75 * litFraction).toFloat()
    val craters = if (litFraction > .3) (.5 * litFraction).toFloat() else 0f
    val lit: Path? = if (litFraction <= .02) null else Path().apply {
        val cx = MOON_C.x
        val cy = MOON_C.y
        val r = MOON_R
        val rx = r * abs(2 * litFraction.toFloat() - 1)
        val outer = Rect(cx - r, cy - r, cx + r, cy + r)
        val edge = Rect(cx - rx, cy - r, cx + rx, cy + r)
        val gibbous = litFraction > .5
        moveTo(cx, cy - r)
        if (waxing) {
            arcTo(outer, -90f, 180f, false) // the right half, top → bottom
            arcTo(edge, 90f, if (gibbous) 180f else -180f, false) // the terminator, back to the top
        } else {
            arcTo(outer, -90f, -180f, false) // the left half
            arcTo(edge, 90f, if (gibbous) -180f else 180f, false)
        }
        close()
    }
}

/** How the sky, land and weather look for one moment (rebuilt when the minute changes). */
internal class SkyLook(now: ForestNow) {
    val extras = now.extras
    val time = now.time
    val night = now.night
    private val skyColors: LongArray = run {
        var s = when (now.time) {
            ForestTime.DAY -> longArrayOf(0xFF9FD3E6, 0xFFD7EEE9, 0xFFF6EDD6)
            ForestTime.DAWN -> longArrayOf(0xFF8FB3D9, 0xFFF6CFAE, 0xFFFCE9D2)
            ForestTime.DUSK -> longArrayOf(0xFF6E7FB5, 0xFFEFA27F, 0xFFF8D3A6)
            ForestTime.NIGHT -> longArrayOf(0xFF0B1626, 0xFF16304A, 0xFF1E4052)
        }
        if ((now.phase == ForestPhase.DRY || now.phase == ForestPhase.DESERT) && (now.time == ForestTime.DAY || now.time == ForestTime.DAWN)) {
            s = if (now.phase == ForestPhase.DESERT) longArrayOf(0xFFE2CF9E, 0xFFF0E1BC, 0xFFF7ECD3) else longArrayOf(0xFFC9D6CF, 0xFFEADFC0, 0xFFF4E9CF)
        }
        if (now.phase == ForestPhase.STORM) {
            s = if (!now.night) longArrayOf(0xFF3B4452, 0xFF5A6472, 0xFF7C8792) else longArrayOf(0xFF151B24, 0xFF232C38, 0xFF2E3846)
        }
        s
    }
    val sky = Brush.verticalGradient(0f to Color(skyColors[0]), .55f to Color(skyColors[1]), .78f to Color(skyColors[2]), startY = 0f, endY = H)

    // The air between here and the far side, in the sky's own colour at the horizon: thickest where the land meets
    // the hills (so that edge is soft), and again over the back rows' feet (see drawFarHaze).
    private val air = Color(skyColors[2])
    private val airAlpha = when {
        now.phase == ForestPhase.STORM -> .62f
        now.night -> .42f
        else -> .5f
    }
    private val horizonAir = Brush.verticalGradient(
        0f to air.copy(alpha = 0f), .55f to air.copy(alpha = airAlpha), 1f to air.copy(alpha = 0f), startY = 268f, endY = 352f,
    )
    private val farAir = Brush.verticalGradient(
        0f to air.copy(alpha = 0f), .45f to air.copy(alpha = airAlpha * .6f), 1f to air.copy(alpha = 0f), startY = 296f, endY = 374f,
    )
    val hills = Color(
        when (now.phase) {
            ForestPhase.DRY -> 0xFFC8BB93
            ForestPhase.DESERT -> 0xFFE4C68F
            ForestPhase.STORM -> 0xFF6F7B84
            else -> 0xFFA9C7BC
        },
    )
    val meadow: Brush = run {
        val (a, b) = when {
            now.phase == ForestPhase.DRY -> 0xFFCDB57E to 0xFF9C8456
            now.phase == ForestPhase.DESERT -> 0xFFEED39A to 0xFFD6AE68
            now.phase == ForestPhase.STORM && now.eveningGreen -> 0xFF86A55E to 0xFF4F7A3C
            now.phase == ForestPhase.STORM -> 0xFF7E8E5C to 0xFF566540
            else -> when (now.season) {
                ForestSeason.SPRING -> 0xFFA9D47A to 0xFF5F9447
                ForestSeason.SUMMER -> 0xFFC2C66C to 0xFF8E9A4C
                ForestSeason.MONSOON -> 0xFF6FB25A to 0xFF2F7A3A
                ForestSeason.AUTUMN -> 0xFFBDB866 to 0xFF8E8F48
                ForestSeason.WINTER -> 0xFF9DBE8C to 0xFF6F9866
            }
        }
        Brush.verticalGradient(0f to Color(a), 1f to Color(b), startY = HORIZON - 16, endY = H)
    }

    // The sun's arc, sunrise (left) → sunset (right).
    val sun: Offset = now.sunFraction.toFloat().let { f -> Offset(24 + f * (W - 48), 250 - sin(f * PI.toFloat()) * 170) }
    val sunCore = Color(if (now.time == ForestTime.DAWN || now.time == ForestTime.DUSK) 0xFFFFE2B0 else 0xFFFFF7DC)
    val sunGlow = Brush.radialGradient(0f to Color(0xFFFFF6D8), .25f to Color(0xE6FFE7A3), 1f to Color(0x00FFE7A3), center = sun, radius = 110f)

    private val moon: SceneMoon? = if (ForestExtra.MOON in extras) SceneMoon(now.moon.lit, now.moon.waxing) else null

    /** Stars: x, y, radius, twinkle seconds (the mockup's seed: the forest-day). */
    private val stars: FloatArray = if (!now.night) FloatArray(0) else {
        val r = PyRandom(now.day * 3L + 1)
        val n = if (now.phase == ForestPhase.STORM) 6 else 44
        FloatArray(n * 4).also { a ->
            for (i in 0 until n) {
                a[i * 4] = r.uniform(0.0, W.toDouble()).toFloat()
                a[i * 4 + 1] = r.uniform(0.0, 250.0).toFloat()
                a[i * 4 + 2] = r.uniform(.5, 1.4).toFloat()
                a[i * 4 + 3] = r.uniform(2.0, 6.0).toFloat()
            }
        }
    }
    private val lightRain: FloatArray? = if (ForestExtra.LIGHT_RAIN in extras) rainDrops(90, now.day.toLong(), heavy = false) else null

    /** Trees sway a little more in the monsoon's showers. */
    val wind = if (ForestExtra.LIGHT_RAIN in extras) 1.35f else 1f

    // The storm's flood: muddy water from its edge to the front, lighter where it's shallow.
    private val floodLine = now.floodLine
    private val floodWater = Brush.verticalGradient(
        0f to Color(0xFF9DB3AE), .18f to Color(0xFF6E8F92), 1f to Color(0xFF3E6270), startY = floodLine - 3f, endY = H,
    )

    /** Glints on the water: x, y, length, seconds per drift (seeded; only the ones on the water are drawn). */
    private val glints: FloatArray = if (ForestExtra.FLOOD !in extras) FloatArray(0) else {
        val r = PyRandom(41)
        FloatArray(46 * 4).also { a ->
            for (i in 0 until 46) {
                a[i * 4] = r.uniform(-10.0, 370.0).toFloat()
                a[i * 4 + 1] = r.uniform(352.0, H - 10.0).toFloat()
                a[i * 4 + 2] = r.uniform(5.0, 12.0).toFloat()
                a[i * 4 + 3] = r.uniform(3.0, 6.0).toFloat()
            }
        }
    }

    private val scratch = Path() // rain and birds, rebuilt every frame without allocating

    // ------------------------------------------------------------------ positions (also used for taps)
    fun dragonFlying(t: Float): Offset {
        val p = SceneArtwork.dragon.at((t + 6f) / 16f)
        return Offset(p.x, p.y + 40f + 2.5f * sin(t * 2.6f))
    }

    fun dragonflyAt(t: Float): Offset {
        val p = SceneArtwork.dragonfly.at(t / 7f)
        return Offset(p.x + .6f * sin(t * 31f), p.y + 10f + .5f * sin(t * 23f))
    }

    fun moonHit(x: Float, y: Float, tol: Float): Boolean = moon != null && hypot(x - MOON_C.x, y - MOON_C.y) <= MOON_R + 2 * tol

    // ------------------------------------------------------------------ the back: sky → meadow
    fun DrawScope.drawBack(t: Float, top: Float, animate: Boolean, sprites: Map<Sprite, ImageBitmap>) {
        val x = extras
        drawRect(sky, Offset(0f, top), Size(W, H - top))
        if (ForestExtra.SUN in x) {
            drawCircle(sunGlow, 110f, sun)
            drawCircle(sunCore, 21f, sun)
        }
        if (stars.isNotEmpty()) {
            val starColor = Color(0xFFF3F6FF)
            for (i in 0 until stars.size / 4) {
                var sy = stars[i * 4 + 1]
                if (top < 0f) sy = top + sy * (250f - top) / 250f // a taller screen: the stars fill the taller sky
                val a = if (animate) .3f + .7f * abs(2f * sceneFrac(t / stars[i * 4 + 3]) - 1f) else 1f
                drawCircle(starColor, stars[i * 4 + 2], Offset(stars[i * 4], sy), alpha = a)
            }
        }
        moon?.let { m ->
            drawCircle(SceneArtwork.moonGlow, 68f, MOON_C, alpha = m.glowAlpha)
            drawCircle(Color(0xFF2A3446), MOON_R, MOON_C, alpha = .55f)
            m.lit?.let { lit ->
                drawPath(lit, Color(0xFFF4ECD2))
                if (m.craters > 0f) clipPath(lit) {
                    drawCircle(Color(0xFFE0D4AF), MOON_R * .17f, Offset(MOON_C.x - MOON_R * .3f, MOON_C.y - MOON_R * .2f), alpha = m.craters)
                    drawCircle(Color(0xFFE0D4AF), MOON_R * .13f, Offset(MOON_C.x + MOON_R * .28f, MOON_C.y + MOON_R * .3f), alpha = m.craters)
                }
            }
        }
        if (ForestExtra.STORM_CLOUDS in x) {
            for ((e, col) in SceneArtwork.stormClouds) drawOval(col, Offset(e[0] - e[2], e[1] - e[3]), Size(e[2] * 2, e[3] * 2), alpha = .95f)
            if (ForestExtra.LIGHTNING in x) {
                val f = sceneFrac((t + 1.5f) / 4.5f)
                val b = sceneKeyed(SceneArtwork.BOLT, f)
                if (b > 0f) drawPath(SceneArtwork.bolt, Color(0xFFFFF6C8), alpha = b)
                val fl = if (animate) sceneKeyed(SceneArtwork.FLASH, f) else 0f
                if (fl > 0f) drawRect(Color.White, Offset(0f, top), Size(W, H - top), alpha = fl)
            }
        } else {
            val clouds = when {
                ForestExtra.CLOUDS in x -> SceneArtwork.dayClouds
                ForestExtra.RAIN_CLOUDS in x -> SceneArtwork.rainClouds
                else -> null
            }
            if (clouds != null) {
                val dx = if (animate) 24f * sceneTri(sceneFrac(t / 60f)) else 0f
                translate(dx, 0f) { for (c in clouds) drawPath(c.path, Color.White, alpha = c.alpha) }
            }
        }
        drawPath(SceneArtwork.hills, hills)
        if (ForestExtra.TREE_LINE in x) {
            drawPath(SceneArtwork.treeLine, Color(0xFF86AE95))
            drawRect(SceneArtwork.mist, Offset(0f, 244f), Size(W, 56f))
        }
        if (ForestExtra.VOLCANO in x) sprites[Sprite.O_VOLCANO]?.let { volcano(it, t, animate) }
        if (ForestExtra.DUNES in x) drawPath(SceneArtwork.dunes, Color(0xFFE9CB90))
        // The meadow's far edge feathers into the hills over a few units, not a hard line.
        for (k in 3 downTo 1) translate(0f, -1.3f * k) { drawPath(SceneArtwork.meadow, meadow, alpha = .5f - .12f * k) }
        drawPath(SceneArtwork.meadow, meadow)
        if (ForestExtra.GRASS in x) for (i in 0..3) drawPath(SceneArtwork.backTufts[i], SceneArtwork.tuftColors[i], style = SceneArtwork.tuftStroke)
        drawRect(horizonAir, Offset(0f, 268f), Size(W, 84f))
    }

    /** The volcano erupting: a glow behind, smoke puffs rising and drifting, lava thrown up and falling back. */
    private fun DrawScope.volcano(img: ImageBitmap, t: Float, animate: Boolean) {
        val sp = Sprite.O_VOLCANO
        val k = VOLCANO_W / 100f
        // The crater, at (50, 38) in the art's box (its painted plume rises from there).
        val cx = VOLCANO_X
        val cy = VOLCANO_Y - 58f * k
        val pulse = if (animate) .75f + .25f * sin(t * TAU / 2.3f) else .9f
        withTransform({ translate(cx, cy); scale(90f, 70f, Offset.Zero) }) { drawCircle(SceneArtwork.volcanoGlow, 1f, Offset.Zero, alpha = pulse) }
        drawSprite(img, sp, VOLCANO_X, VOLCANO_Y, VOLCANO_W)
        // Lava bombs: thrown up from the crater, falling back onto the slopes.
        for (i in 0 until BOMBS.size / 3) {
            val dur = BOMBS[i * 3]
            val f = if (animate) sceneFrac((t + i * .37f) / dur) else .3f + .1f * i
            val bx = cx + BOMBS[i * 3 + 1] * f * 1.6f
            val by = cy - BOMBS[i * 3 + 2] * 4f * f * (1f - f) + 10f * f * f
            drawCircle(Color(0xFFFF8A3D), 1.5f, Offset(bx, by), alpha = 1f - .5f * f)
            drawCircle(Color(0xFFFFD27A), .7f, Offset(bx, by), alpha = 1f - .5f * f)
        }
        // Smoke: puffs keep rising out of the painted plume, growing and drifting off with the wind, fading as they go.
        if (animate) for (i in 0 until PUFFS.size / 2) {
            val f = sceneFrac((t + PUFFS[i * 2]) / 6.6f)
            val px = cx + (PUFFS[i * 2 + 1] * 30f + 34f * f * f) * (1f + f)
            val py = cy - 10f - 110f * f
            val r = 7f + 20f * f
            withTransform({ translate(px, py); scale(r, r, Offset.Zero) }) {
                drawCircle(SceneArtwork.puff, 1f, Offset.Zero, alpha = .55f * (1f - f) * min(1f, f * 6f))
            }
        }
        // The crater's fire.
        drawCircle(Color(0xFFFFB347), 3.2f * k, Offset(cx, cy + 1f), alpha = pulse)
    }

    /** The flood: water from its edge (lapping) to the front, with glints, rain rings and foam along its edge. */
    fun DrawScope.drawFlood(t: Float, animate: Boolean, sprites: Map<Sprite, ImageBitmap>) {
        val y0 = floodLine
        val p = scratch
        p.reset()
        val lap = if (animate) t * .9f else 0f
        p.moveTo(-2f, H + 2f)
        var px = -2f
        while (px <= W + 2f) {
            p.lineTo(px, y0 + 1.6f * sin(px / 19f + lap) + .8f * sin(px / 7f - lap * 1.7f))
            px += 6f
        }
        p.lineTo(W + 2f, H + 2f)
        p.close()
        drawPath(p, floodWater)
        // Foam along the edge.
        p.reset()
        px = -2f
        p.moveTo(px, y0 + 1.6f * sin(px / 19f + lap) + .8f * sin(px / 7f - lap * 1.7f))
        while (px <= W + 2f) {
            px += 6f
            p.lineTo(px, y0 + 1.6f * sin(px / 19f + lap) + .8f * sin(px / 7f - lap * 1.7f))
        }
        drawPath(p, Color.White, alpha = .5f, style = SceneArtwork.foamStroke)
        // Glints drifting on the surface (only on the water, bigger nearer).
        val g = glints
        for (i in 0 until g.size / 4) {
            val gy = g[i * 4 + 1]
            if (gy < y0 + 6f) continue
            val d = 0.56f + (gy - HORIZON) / (H - HORIZON) * 0.84f
            val f = if (animate) sceneFrac(t / g[i * 4 + 3] + i * .13f) else .5f
            val gx = g[i * 4] + 10f * f * d
            val len = g[i * 4 + 2] * d
            drawLine(Color.White, Offset(gx, gy), Offset(gx + len, gy), strokeWidth = .7f * d, alpha = .45f * sceneTri(f))
        }
        // Wave crests drifting across, fading in and out (nearer ones bigger).
        sprites[Sprite.O_FLOOD_WAVES]?.let { img ->
            for (i in 0 until 7) {
                val wy = y0 + 14f + sceneFrac(i * .43f + .2f) * (H - y0 - 40f)
                val d = 0.56f + (wy - HORIZON) / (H - HORIZON) * 0.84f
                val f = if (animate) sceneFrac(t / (9f + i) + i * .31f) else .5f
                val wx = -30f + sceneFrac(i * .618f + f * .35f) * (W + 60f)
                drawSprite(img, Sprite.O_FLOOD_WAVES, wx, wy, 46f * d, alpha = .9f * sceneTri(f))
            }
        }
        // Rain landing: little rings that open and fade.
        if (animate) for (i in 0 until 22) {
            val f = sceneFrac(t / (.8f + .05f * (i % 7)) + i * .29f)
            val rx = 7f + 346f * sceneFrac(i * .618f)
            val ry = y0 + 8f + sceneFrac(i * .382f + .1f) * (H - y0 - 20f)
            val d = 0.56f + (ry - HORIZON) / (H - HORIZON) * 0.84f
            val r = (1f + 5f * f) * d
            drawOval(Color.White, Offset(rx - r, ry - r * .3f), Size(2 * r, r * .6f), alpha = .4f * (1f - f), style = SceneArtwork.lightStroke)
        }
    }

    /** The haze over the back rows, drawn between them and the nearer things. */
    fun DrawScope.drawFarHaze() {
        drawRect(farAir, Offset(0f, 296f), Size(W, 78f))
    }

    // ------------------------------------------------------------------ the front: weather, light, season
    /** Everything drawn over the trees and things. [sprites] holds the pictures loaded so far. */
    fun DrawScope.drawFront(t: Float, top: Float, animate: Boolean, sprites: Map<Sprite, ImageBitmap>) {
        val x = extras
        if (ForestExtra.DESERT_HAZE in x) drawRect(SceneArtwork.haze, Offset(0f, HORIZON - 30), Size(W, 112f))
        // The rainbow sits in the sky, behind the dragon (it's drawn first here so nothing flies "behind" it).
        if (ForestExtra.RAINBOW in x) {
            drawIntoCanvas { it.saveLayer(Rect(-30f, 90f, 390f, 350f), SceneArtwork.rainbowPaint) }
            for (i in 0..5) drawArc(SceneArtwork.rainbow[i], 180f, 180f, false, Offset(-20f, 100f + 7f * i), Size(400f, 400f), style = SceneArtwork.rainbowStroke)
            drawIntoCanvas { it.restore() }
            sprites[Sprite.O_PUDDLE]?.let { drawSprite(it, Sprite.O_PUDDLE, 262f, 604f, 70f) }
            sprites[Sprite.O_SHOOTS]?.let { drawSprite(it, Sprite.O_SHOOTS, 120f, 596f, 30f) }
        }
        // Sundays: the good dragon flies a slow loop over the forest by day and rests on the hills at night.
        if (ForestExtra.DRAGON_FLYING in x) sprites[Sprite.A_DRAGON]?.let { img ->
            val p = if (animate) dragonFlying(t) else Offset(150f, 170f)
            drawSprite(img, Sprite.A_DRAGON, p.x, p.y, 92f)
        }
        if (ForestExtra.DRAGON_RESTING in x) sprites[Sprite.A_DRAGON]?.let { img ->
            val breath = if (animate) 1f + .014f * sin(t * TAU / 4.2f) else 1f
            scale(1f, breath, Offset(300f, 236f)) { drawSprite(img, Sprite.A_DRAGON, 300f, 236f, 70f, flip = true) }
        }
        if (ForestExtra.GRASS in x) for (i in 0..3) drawPath(SceneArtwork.frontTufts[i], SceneArtwork.tuftColors[i], style = SceneArtwork.tuftStroke)
        if (ForestExtra.WILDFLOWERS in x) for (i in 0..3) drawPath(SceneArtwork.flowers[i], SceneArtwork.flowerColors[i])
        if (ForestExtra.BUTTERFLIES in x) {
            val a = SceneArtwork.monarch.at(if (animate) t / 9f else .2f)
            drawButterfly(0, a.x, a.y, 15f, t, 0f, animate)
            val b = SceneArtwork.blue.at(if (animate) (t - 1f) / 11f else .6f)
            drawButterfly(1, b.x, b.y, 13f, t, .4f, animate)
        }
        if (ForestExtra.DRAGONFLY in x) sprites[Sprite.A_DRAGONFLY]?.let { img ->
            val p = if (animate) dragonflyAt(t) else Offset(290f, 556f)
            drawSprite(img, Sprite.A_DRAGONFLY, p.x, p.y, 22f)
        }
        if (ForestExtra.AUTUMN_LEAVES in x) {
            fallingLeaves(SceneArtwork.autumnLeaves, t, animate)
            drawRect(Color(0xFFF6B25C), Offset(0f, top), Size(W, H - top), alpha = .08f)
        }
        if (ForestExtra.SPRING_PETALS in x) fallingLeaves(SceneArtwork.petals, t, animate)
        if (ForestExtra.WINTER_MIST in x) drawRect(SceneArtwork.winterMist, Offset(0f, HORIZON - 52), Size(W, 114f))
        lightRain?.let { d ->
            rain(d, heavy = false, t, top, animate)
            sprites[Sprite.O_PUDDLE]?.let { drawSprite(it, Sprite.O_PUDDLE, 80f, 604f, 60f) }
        }
        if (ForestExtra.BIRDS in x) birds(t, animate)
        if (ForestExtra.HEAVY_RAIN in x) rain(SceneArtwork.heavyRain, heavy = true, t, top, animate)
        if (ForestExtra.DRY_LEAVES in x) fallingLeaves(SceneArtwork.dryLeaves, t, animate)
        // Night darkens the land; fireflies glow over it. Dawn and dusk tint everything.
        when {
            night -> {
                drawRect(SceneArtwork.night, Offset(0f, 185f), Size(W, H - 185f))
                if (ForestExtra.FIREFLIES in x) fireflies(t, animate)
            }
            time == ForestTime.DUSK -> drawRect(Color(0xFFF08A5D), Offset(0f, top), Size(W, H - top), alpha = .12f)
            time == ForestTime.DAWN -> drawRect(Color(0xFFFFD2A0), Offset(0f, top), Size(W, H - top), alpha = .1f)
        }
        // The lightning lights up the land too (the sky's own flash is behind the hills).
        if (ForestExtra.LIGHTNING in x && animate) {
            val fl = sceneKeyed(SceneArtwork.FLASH, sceneFrac((t + 1.5f) / 4.5f)) * .45f
            if (fl > 0f) drawRect(SceneArtwork.landFlash, Offset(0f, 200f), Size(W, H - 200f), alpha = fl)
        }
    }

    private fun DrawScope.rain(d: FloatArray, heavy: Boolean, t: Float, top: Float, animate: Boolean) {
        val p = scratch
        p.reset()
        val span = H + 20f - top
        for (i in 0 until d.size / 4) {
            var y = d[i * 4 + 1]
            if (top < 0f) y = top - 20f + (y + 20f) / (H + 20f) * span
            val len = d[i * 4 + 2]
            val f = if (animate) sceneFrac(t / d[i * 4 + 3]) else sceneFrac(i * .37f)
            val x0 = d[i * 4] - 9f * f
            val y0 = y - 30f + 60f * f
            p.moveTo(x0, y0)
            p.lineTo(x0 - .3f * len, y0 + len)
        }
        drawPath(p, SceneArtwork.rainColor, alpha = if (heavy) .75f else .55f, style = if (heavy) SceneArtwork.heavyStroke else SceneArtwork.lightStroke)
    }

    private fun DrawScope.birds(t: Float, animate: Boolean) {
        val b = SceneArtwork.birds
        for (i in 0 until b.size / 4) {
            val s = b[i * 4 + 2]
            val f = if (animate) sceneFrac(t / b[i * 4 + 3]) else .3f
            val ox = if (f < .5f) 80f * f else 40f + 80f * (f - .5f)
            val oy = if (f < .5f) -12f * f else -6f + 12f * (f - .5f)
            val x0 = b[i * 4] + ox
            val y0 = b[i * 4 + 1] + oy
            val lift = 3f * s * (if (animate) .55f + .45f * sin(t * 6f + i * 2f) else 1f)
            val p = scratch
            p.reset()
            p.moveTo(x0, y0)
            p.quadraticTo(x0 + 3 * s, y0 - lift, x0 + 6 * s, y0)
            p.quadraticTo(x0 + 9 * s, y0 - lift, x0 + 12 * s, y0)
            val a = min(1f, min(f, 1f - f) / .08f)
            drawPath(p, Color(0xFF3B4A3A), alpha = a, style = SceneArtwork.birdStroke)
        }
    }

    private fun DrawScope.fireflies(t: Float, animate: Boolean) {
        val a = SceneArtwork.flies
        for (i in 0 until a.size / 7) {
            val b = i * 7
            val m = if (animate) sceneTri(sceneFrac(t / (a[b + 2] * 2.5f))) else .5f
            val cx = a[b] + a[b + 4] * m
            val cy = a[b + 1] + a[b + 5] * m
            val r = a[b + 6]
            val glow = if (animate) SceneArtwork.twinkle(t, a[b + 2], a[b + 3]) else .8f
            withTransform({ translate(cx, cy); scale(r, r, Offset.Zero) }) { drawCircle(SceneArtwork.flyGlow, 1f, Offset.Zero, alpha = glow) }
            drawCircle(SceneArtwork.flyCore, 1.6f, Offset(cx, cy))
        }
    }

    private fun DrawScope.fallingLeaves(set: Array<SceneLeaf>, t: Float, animate: Boolean) {
        for (i in set.indices) {
            val l = set[i]
            val f = if (animate) sceneFrac(t / l.dur) else .2f + .25f * i
            val p = l.track.at(f)
            val a = when {
                f < .06f -> f / .06f
                f > .9f -> (1f - f) / .1f
                else -> 1f
            }
            val angle = if (animate) sceneFrac(t / 3f) * 360f else 40f + 70f * i
            withTransform({ translate(p.x, p.y); rotate(angle, Offset.Zero) }) { drawPath(SceneArtwork.leaf, l.color, alpha = a) }
        }
    }
}

// ------------------------------------------------------------------ butterflies (forest_art.butterfly)
private object SceneWings {
    val upper = Path().apply { moveTo(0f, -1f); cubicTo(4f, -10f, 14f, -15f, 19f, -11f); cubicTo(22f, -7f, 17f, 1f, 2f, 2f); close() }
    val lower = Path().apply { moveTo(1f, 2f); cubicTo(9f, 3f, 15f, 8f, 12f, 13f); cubicTo(9f, 16f, 3f, 12f, 0f, 4f); close() }
    val antennae = Path().apply { moveTo(-.5f, -5f); quadraticTo(-2.5f, -10f, -4.5f, -11f); moveTo(.5f, -5f); quadraticTo(2.5f, -10f, 4.5f, -11f) }
    val fills = arrayOf(
        Brush.linearGradient(listOf(Color(0xFFFDBA4D), Color(0xFFE8641F)), start = Offset(0f, -15f), end = Offset(19f, 13f)),
        Brush.linearGradient(listOf(Color(0xFF7CC4FF), Color(0xFF2563EB)), start = Offset(0f, -15f), end = Offset(19f, 13f)),
        Brush.linearGradient(listOf(Color(0xFFFFF08A), Color(0xFFF2C21D)), start = Offset(0f, -15f), end = Offset(19f, 13f)),
    )
    val edges = arrayOf(Color(0xFF1F1712), Color(0xFF0F1E3D), Color(0xFF5A4510))
    val dots = arrayOf(Color.White, Color(0xFFDCEBFF), Color(0xFFFFF9DB))
    val edge = Stroke(1f)
    val thin = Stroke(.7f)
}

/** SceneWings open, then a quick close and open again: a butterfly's flap (0.7 s). */
private fun wingFlap(t: Float, phase: Float): Float {
    val f = sceneFrac(t / .7f + phase)
    return when {
        f < .55f -> 1f
        f < .78f -> 1f - (f - .55f) / .23f * .7f
        else -> .3f + (f - .78f) / .22f * .7f
    }
}

/** A butterfly ([kind] 0 monarch, 1 blue, 2 grass yellow) centred on ([x], [y]), [size] wide. */
private fun DrawScope.drawButterfly(kind: Int, x: Float, y: Float, size: Float, t: Float, phase: Float, animate: Boolean) {
    val s = size / 44f
    val f = if (animate) wingFlap(t, phase) else 1f
    val fill = SceneWings.fills[kind % 3]
    val edge = SceneWings.edges[kind % 3]
    val dot = SceneWings.dots[kind % 3]
    translate(x, y) {
        for (side in 0..1) {
            scale(s * f * (if (side == 0) 1f else -1f), s, pivot = Offset.Zero) {
                drawPath(SceneWings.upper, fill)
                drawPath(SceneWings.upper, edge, style = SceneWings.edge)
                drawPath(SceneWings.lower, fill)
                drawPath(SceneWings.lower, edge, style = SceneWings.edge)
                drawCircle(dot, .9f, Offset(16f, -9f))
                drawCircle(dot, .8f, Offset(18f, -6f))
                drawCircle(dot, .8f, Offset(11f, 12f))
            }
        }
        scale(s, s, pivot = Offset.Zero) {
            drawOval(edge, Offset(-1.3f, -5f), Size(2.6f, 12f))
            drawPath(SceneWings.antennae, edge, style = SceneWings.thin)
        }
    }
}
