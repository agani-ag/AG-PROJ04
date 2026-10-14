package com.agani.syncup.forest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** One tree's place in the scene (scene pixels; the foot of the trunk at [x], [base]). */
internal class Placed(val tree: Tree, val stage: Stage, val x: Float, val base: Float, val w: Float, val row: Int) {
    val top: Float get() = base - w * topOf(stage)
}

private fun topOf(s: Stage) = when (s) {
    Stage.SEED -> .2f
    Stage.SPROUT -> .32f
    Stage.SAPLING -> .5f
    Stage.YOUNG -> .82f
    Stage.GROWN -> .98f
}

private fun speciesScale(s: Species) = when (s) {
    Species.BANYAN -> 1.18f
    Species.GULMOHAR -> 1.12f
    Species.MANGO -> 1.05f
    Species.NEEM -> .98f
    Species.PEEPAL -> .88f
    Species.COCONUT -> .84f
    Species.BAMBOO -> .72f
    Species.JASMINE -> .82f
}

private fun stageScale(s: Stage) = when (s) {
    Stage.SEED -> .42f
    Stage.SPROUT -> .5f
    Stage.SAPLING -> .66f
    Stage.YOUNG -> .82f
    Stage.GROWN -> 1f
}

/** Who visits: butterflies by day near trees in their season, fireflies at night, birds on big trees. */
internal data class Visitors(val butterflies: Int, val fireflies: Int, val birds: Int)

internal fun visitors(trees: List<Tree>, now: Long = System.currentTimeMillis(), month: Int = FDays.month()): Visitors {
    val grown = trees.filter { it.stage(now) == Stage.GROWN }
    val inSeason = grown.count { month in it.species.season }
    val big = grown.count { it.species == Species.BANYAN || it.species == Species.PEEPAL }
    return Visitors(
        butterflies = minOf(6, if (inSeason == 0 && grown.isNotEmpty()) 1 else inSeason),
        fireflies = minOf(20, grown.size),
        birds = if (grown.isEmpty()) 0 else minOf(5, 1 + big * 2),
    )
}

/** Grown trees at the back (fading into mist), growing ones in front; more trees → the forest scrolls sideways. */
private class Geo(val w: Float, val gb: Float, val sceneW: Float, val placed: List<Placed>, val pondX: Float, val pondBase: Float, val pondW: Float, val pond: Boolean)

private val ROW_Y = floatArrayOf(.58f, .70f, .82f, .935f)
private val ROW_SCALE = floatArrayOf(.6f, .74f, .87f, 1f)
private val ROW_CAP = intArrayOf(4, 4, 4, 5)

private fun layout(trees: List<Tree>, now: Long, w: Float, gb: Float, pond: Boolean): Geo {
    val unit = minOf(w, gb * .8f)
    val grown = trees.filter { it.stage(now) == Stage.GROWN }.sortedBy { it.plantedMs }
    val growing = trees.filter { it.stage(now) != Stage.GROWN }.sortedBy { it.plantedMs }
    var pages = 1
    var rows: List<List<Tree>>
    while (true) {
        val r = List(4) { ArrayList<Tree>() }
        val cap = IntArray(4) { ROW_CAP[it] * pages }
        if (pond) cap[3] -= 2
        fun put(t: Tree, order: IntArray): Boolean {
            for (i in order) if (r[i].size < cap[i]) {
                r[i].add(t)
                return true
            }
            return false
        }
        val ok = growing.all { put(it, intArrayOf(3, 2, 1)) } && grown.all { put(it, intArrayOf(1, 0, 2, 3)) }
        if (ok || pages >= 15) {
            rows = r
            break
        }
        pages++
    }
    val sceneW = w * pages
    val placed = ArrayList<Placed>()
    rows.forEachIndexed { ri, list ->
        val usable = if (ri == 3 && pond) sceneW - w * .42f else sceneW
        val n = list.size
        list.forEachIndexed { i, t ->
            val rnd = Random(t.variant.toLong() * 31 + 7)
            val slot = usable / n
            val st = t.stage(now)
            val size = (if (st == Stage.GROWN) unit * .42f * speciesScale(t.species) else unit * .30f * stageScale(st)) * ROW_SCALE[ri]
            val x = (i + .5f) * slot + (rnd.nextFloat() - .5f) * slot * .28f + if (ri % 2 == 1) slot * .06f else 0f
            val base = gb * ROW_Y[ri] + (rnd.nextFloat() - .5f) * gb * .022f
            placed.add(Placed(t, st, x.coerceIn(size * .3f, sceneW - size * .3f), base, size.coerceAtMost(w * .5f), ri))
        }
    }
    placed.sortBy { it.base }
    return Geo(w, gb, sceneW, placed, sceneW - w * .2f, gb * .975f, w * .4f, pond)
}

// ------------------------------------------------------------------ light and weather
private class Look(
    val part: DayPart,
    val season: Season,
    val rainy: Boolean,
    val resting: Boolean,
) {
    val night = part == DayPart.NIGHT
    val sky: List<Color>
    val hill: Color
    val treeline: Color
    val mist: Color
    val haze: Color
    val mead: List<Color>

    init {
        fun c(v: Long) = Color(v.toInt())
        when {
            night -> {
                sky = listOf(c(0xFF0B1626), c(0xFF16304A), c(0xFF1E4052)); hill = c(0xFF203C48); treeline = c(0xFF1B3442); mist = c(0xFF9FB8C9); haze = c(0xFF20384A)
            }
            rainy -> {
                sky = listOf(c(0xFF8FA6B2), c(0xFFB9C7CC), c(0xFFDCE2DD)); hill = c(0xFF9CB2AE); treeline = c(0xFF7E9E8C); mist = c(0xFFE3E8E4); haze = c(0xFFD5E0DE)
            }
            part == DayPart.DAWN -> {
                sky = listOf(c(0xFFF2B8A2), c(0xFFF7D9C4), c(0xFFF6EDD6)); hill = c(0xFFB8B9C2); treeline = c(0xFF93A79A); mist = c(0xFFFBE9DC); haze = c(0xFFF1E2DA)
            }
            part == DayPart.EVENING -> {
                sky = listOf(c(0xFFE79A74), c(0xFFF4C89C), c(0xFFF7E5C6)); hill = c(0xFFB2A6A2); treeline = c(0xFF899A82); mist = c(0xFFF7E2C4); haze = c(0xFFF2DCC4)
            }
            else -> {
                sky = listOf(c(0xFF9FD3E6), c(0xFFD7EEE9), c(0xFFF6EDD6)); hill = c(0xFFA9C7BC); treeline = c(0xFF86AE95); mist = c(0xFFF6EDD6); haze = c(0xFFDCEFEA)
            }
        }
        mead = if (night) {
            listOf(c(0xFF1D3B2C), c(0xFF16301F), c(0xFF0E2218))
        } else {
            when (season) {
                Season.SUMMER -> listOf(c(0xFFBCCB74), c(0xFF9FBB5E), c(0xFF6F9447))
                Season.MONSOON -> listOf(c(0xFF8CC867), c(0xFF6CAE52), c(0xFF4A8A3E))
                Season.AUTUMN -> listOf(c(0xFFB9C26A), c(0xFF9BAE58), c(0xFF6F9046))
                Season.WINTER -> listOf(c(0xFFA4CB86), c(0xFF86B46F), c(0xFF5F9455))
            }
        }
    }
}

/** Is it raining in the forest today? Most monsoon days, not all. */
internal fun rainyToday(): Boolean = Season.of(FDays.month()) == Season.MONSOON && (FDays.today() * 7 + 3) % 3 != 0

// ------------------------------------------------------------------ things drawn the same every frame
private class Statics(geo: Geo, vis: Visitors, look: Look) {
    val k = geo.w / 390f
    val hills = Path()
    val treeline = Path()
    val tufts = List(3) { List(4) { Path() } }
    val flowers = List(4) { Path() }
    val meadow = Path()
    val trail = Path()
    class Star(val x: Float, val y: Float, val r: Float, val period: Float, val phase: Float)
    val stars = ArrayList<Star>()
    class Fly(val x: Float, val y: Float, val dx: Float, val dy: Float, val r: Float, val dur: Float, val begin: Float)
    val flies = ArrayList<Fly>()
    class Bfly(val kind: Int, val cx: Float, val cy: Float, val rx: Float, val ry: Float, val speed: Float, val p1: Float, val p2: Float, val p3: Float, val size: Float)
    val bflies = ArrayList<Bfly>()
    class Bird(val y: Float, val speed: Float, val offset: Float, val s: Float)
    val birds = ArrayList<Bird>()
    class Drop(val x: Float, val y: Float, val speed: Float)
    val drops = ArrayList<Drop>()
    class Leaf(val x: Float, val y: Float, val ph: Float, val color: Color)
    val leaves = ArrayList<Leaf>()

    init {
        val rnd = Random(7)
        val gb = geo.gb
        val sw = geo.sceneW
        fun Y(y: Float) = y / 560f * gb
        // Far hills (they scroll slower: 0.3) and the tree line (0.5).
        val hw = geo.w + (sw - geo.w) * .3f + 80
        hills.moveTo(-40f, Y(262f))
        var x = -40f
        var y = Y(262f)
        while (x < hw) {
            val nx = x + geo.w / 3.2f
            val ny = Y(220f + rnd.nextFloat() * 36)
            hills.quadraticTo(x + (nx - x) / 2, y + (ny - y) * .2f - Y(14f), nx, ny)
            x = nx
            y = ny
        }
        hills.lineTo(hw, Y(320f)); hills.lineTo(-40f, Y(320f)); hills.close()
        val tw = geo.w + (sw - geo.w) * .5f + 60
        var tx = -20f
        while (tx < tw) {
            val r = (6 + rnd.nextFloat() * 5) * k
            val cy = Y(268f + (rnd.nextFloat() - .5f) * 8)
            treeline.addOval(Rect(tx - r, cy - r, tx + r, cy + r))
            tx += 9 * k
        }
        treeline.addRect(Rect(-20f, Y(268f), tw, Y(300f)))
        // The meadow's soft edge, one wave per screen.
        meadow.moveTo(0f, Y(296f))
        var px = 0f
        while (px < sw) {
            meadow.cubicTo(px + 70 * k, Y(278f), px + 140 * k, Y(284f), px + 206 * k, Y(292f))
            meadow.cubicTo(px + 270 * k, Y(300f), px + 330 * k, Y(282f), px + 390 * k, Y(290f))
            px += 390 * k
        }
        meadow.lineTo(sw, 4000f); meadow.lineTo(0f, 4000f); meadow.close()
        // A path winding into the forest.
        trail.moveTo(168 * k, Y(600f))
        trail.cubicTo(176 * k, Y(470f), 236 * k, Y(430f), 214 * k, Y(380f))
        trail.cubicTo(192 * k, Y(330f), 196 * k, Y(320f), 220 * k, Y(296f))
        trail.lineTo(230 * k, Y(296f))
        trail.cubicTo(214 * k, Y(322f), 236 * k, Y(350f), 236 * k, Y(384f))
        trail.cubicTo(236 * k, Y(418f), 206 * k, Y(470f), 232 * k, Y(600f))
        trail.close()
        // Grass tufts in three bands (behind the middle row, behind the front, in front) and wildflowers.
        val pages = sw / geo.w
        fun tuftBand(band: Int, n: Int, y0: Float, y1: Float, h0: Float, h1: Float) {
            repeat((n * pages).toInt()) {
                val tx2 = rnd.nextFloat() * sw
                val ty = y0 + rnd.nextFloat() * (y1 - y0)
                val depth = (ty - y0) / (y1 - y0).coerceAtLeast(1f)
                val hh = (h0 + rnd.nextFloat() * (h1 - h0)) * (.6f + .8f * depth) * k
                val p = tufts[band][rnd.nextInt(4)]
                p.moveTo(tx2, ty); p.quadraticTo(tx2 - 1.5f * k, ty - hh * .6f, tx2 - 3 * k, ty - hh)
                p.moveTo(tx2, ty); p.quadraticTo(tx2 + .5f * k, ty - hh * .7f, tx2 + 1 * k, ty - hh * 1.1f)
                p.moveTo(tx2, ty); p.quadraticTo(tx2 + 2 * k, ty - hh * .5f, tx2 + 4 * k, ty - hh * .8f)
            }
        }
        tuftBand(0, 70, Y(300f), Y(360f), 3f, 6f)
        tuftBand(1, 90, Y(380f), Y(460f), 4f, 8f)
        tuftBand(2, 70, Y(470f), Y(575f), 6f, 12f)
        repeat((70 * pages).toInt()) {
            val fx = rnd.nextFloat() * sw
            val fy = Y(330f + rnd.nextFloat() * 240)
            val r = (1 + rnd.nextFloat()) * k
            flowers[rnd.nextInt(4)].addOval(Rect(fx - r, fy - r, fx + r, fy + r))
        }
        repeat(46) { stars.add(Star(rnd.nextFloat() * geo.w, rnd.nextFloat() * Y(250f), (.5f + rnd.nextFloat()) * k, 2 + rnd.nextFloat() * 4, rnd.nextFloat() * 6)) }
        repeat(vis.fireflies) {
            flies.add(Fly(rnd.nextFloat() * sw, Y(330f + rnd.nextFloat() * 240), (rnd.nextFloat() - .5f) * 28 * k, (rnd.nextFloat() * 16 - 10) * k, (7 + rnd.nextFloat() * 5) * k, 2.2f + rnd.nextFloat() * 2.4f, rnd.nextFloat() * 3))
        }
        // Butterflies stay near trees in their season (or any grown tree, or the middle of the meadow).
        val month = FDays.month()
        val near = geo.placed.filter { it.stage == Stage.GROWN && month in it.tree.species.season }.ifEmpty { geo.placed.filter { it.stage == Stage.GROWN } }
        repeat(vis.butterflies) { i ->
            val p = near.getOrNull(i % near.size.coerceAtLeast(1))
            val cx = p?.x ?: (geo.w * .5f)
            val cy = p?.let { it.base - it.w * .55f } ?: Y(420f)
            bflies.add(Bfly(i % 3, cx, cy, (24 + rnd.nextFloat() * 24) * k, (14 + rnd.nextFloat() * 12) * k, .5f + rnd.nextFloat() * .3f, rnd.nextFloat() * 6, rnd.nextFloat() * 6, rnd.nextFloat() * 6, (18 + rnd.nextFloat() * 8) * k))
        }
        repeat(if (look.night) 0 else vis.birds.coerceAtLeast(1)) {
            birds.add(Bird(Y(100f + rnd.nextFloat() * 60), (10 + rnd.nextFloat() * 8) * k, rnd.nextFloat() * 1000, .7f + rnd.nextFloat() * .4f))
        }
        if (look.rainy) repeat(90) { drops.add(Drop(rnd.nextFloat() * 2000, rnd.nextFloat() * 3000, (260 + rnd.nextFloat() * 120) * k)) }
        if (look.season == Season.AUTUMN || look.resting) {
            repeat(4) { leaves.add(Leaf(rnd.nextFloat() * geo.w, rnd.nextFloat() * 1000, rnd.nextFloat() * 6, listOf(Color(0xFFD9A441), Color(0xFFC58A2C), Color(0xFFC99B45))[it % 3])) }
        } else if (!look.night) {
            leaves.add(Leaf(geo.w * .5f, rnd.nextFloat() * 1000, 1f, Color(0xFF7DB352)))
        }
    }
}

// ------------------------------------------------------------------ butterflies
private object Wings {
    val upper = Path().apply { moveTo(0f, -1f); cubicTo(4f, -10f, 14f, -15f, 19f, -11f); cubicTo(22f, -7f, 17f, 1f, 2f, 2f); close() }
    val lower = Path().apply { moveTo(1f, 2f); cubicTo(9f, 3f, 15f, 8f, 12f, 13f); cubicTo(9f, 16f, 3f, 12f, 0f, 4f); close() }
    val antennae = Path().apply { moveTo(-.5f, -5f); quadraticTo(-2.5f, -10f, -4.5f, -11f); moveTo(.5f, -5f); quadraticTo(2.5f, -10f, 4.5f, -11f) }
    val kinds = listOf(
        Triple(listOf(Color(0xFFFDBA4D), Color(0xFFE8641F)), Color(0xFF1F1712), Color.White),
        Triple(listOf(Color(0xFF7CC4FF), Color(0xFF2563EB)), Color(0xFF0F1E3D), Color(0xFFDCEBFF)),
        Triple(listOf(Color(0xFFFFF08A), Color(0xFFF2C21D)), Color(0xFF5A4510), Color(0xFFFFF9DB)),
    )
}

/** Wings open, then a quick close and open again — a butterfly's flap. */
private fun flap(t: Float, phase: Float): Float {
    val f = ((t / .7f + phase) % 1f + 1f) % 1f
    return when {
        f < .55f -> 1f
        f < .78f -> 1f - (f - .55f) / .23f * .7f
        else -> .3f + (f - .78f) / .22f * .7f
    }
}

internal fun DrawScope.butterfly(kind: Int, x: Float, y: Float, size: Float, t: Float, phase: Float) {
    val (fill, edge, dot) = Wings.kinds[kind % 3]
    val s = size / 44f
    val f = flap(t, phase)
    val brush = Brush.linearGradient(fill, start = Offset(0f, -15f), end = Offset(19f, 13f))
    translate(x, y) {
        for (side in intArrayOf(1, -1)) {
            scale(s * f * side, s, pivot = Offset.Zero) {
                drawPath(Wings.upper, brush)
                drawPath(Wings.upper, edge, style = Stroke(1f))
                drawPath(Wings.lower, brush)
                drawPath(Wings.lower, edge, style = Stroke(1f))
                drawCircle(dot, .9f, Offset(16f, -9f))
                drawCircle(dot, .8f, Offset(18f, -6f))
                drawCircle(dot, .8f, Offset(11f, 12f))
            }
        }
        scale(s, s, pivot = Offset.Zero) {
            drawOval(edge, Offset(-1.3f, -5f), Size(2.6f, 12f))
            drawPath(Wings.antennae, edge, style = Stroke(.7f))
        }
    }
}

internal fun DrawScope.firefly(x: Float, y: Float, r: Float, alpha: Float, core: Float) {
    drawCircle(
        Brush.radialGradient(0f to Color(0xF2FFF6B0), .35f to Color(0x73FFE47A), 1f to Color(0x00FFE47A), center = Offset(x, y), radius = r),
        r, Offset(x, y), alpha = alpha.coerceIn(0f, 1f),
    )
    drawCircle(Color(0xFFFFF8C8), core, Offset(x, y))
}

private fun twinkle(t: Float, dur: Float, begin: Float): Float {
    val v = floatArrayOf(.15f, 1f, .35f, .9f, .15f)
    val f = (((t - begin) / dur) % 1f + 1f) % 1f * 4
    val i = f.toInt().coerceIn(0, 3)
    return v[i] + (v[i + 1] - v[i]) * (f - i)
}

// ------------------------------------------------------------------ the scene
private fun DrawScope.drawForest(g: Geo, look: Look, s: Statics, t: Float, scroll: Float, images: Map<String, ImageBitmap>, pond: ImageBitmap?) {
    val w = size.width
    val gb = g.gb
    val k = s.k
    fun Y(y: Float) = y / 560f * gb
    // Sky.
    drawRect(Brush.verticalGradient(0f to look.sky[0], .45f to look.sky[1], 1f to look.sky[2], startY = 0f, endY = Y(340f)))
    if (look.night) {
        for (st in s.stars) drawCircle(Color(0xFFF3F6FF), st.r, Offset(st.x, st.y), alpha = .3f + .7f * (.5f + .5f * sin((t / st.period + st.phase) * 2 * PI.toFloat())))
        val mc = Offset(w * .77f, Y(104f))
        drawCircle(Brush.radialGradient(listOf(Color(0x8CF7F1DC), Color(0x00F7F1DC)), center = mc, radius = 80 * k), 80 * k, mc)
        drawCircle(Color(0xFFF4ECD2), 22 * k, mc)
        drawCircle(Color(0xB3E2D7B6), 4 * k, mc + Offset(-8 * k, -6 * k))
        drawCircle(Color(0x99E2D7B6), 3 * k, mc + Offset(6 * k, 8 * k))
    } else {
        val (sc, glow, core) = when (look.part) {
            DayPart.DAWN -> Triple(Offset(w * .16f, Y(210f)), Color(0xFFFFC48A), Color(0xFFFFE6C8))
            DayPart.EVENING -> Triple(Offset(w * .84f, Y(200f)), Color(0xFFFFB46A), Color(0xFFFFE3B0))
            else -> Triple(Offset(w * .2f, Y(118f)), Color(0xFFFFE7A3), Color(0xFFFFF7DC))
        }
        val a = if (look.rainy) .45f else 1f
        drawCircle(Brush.radialGradient(0f to Color(0xFFFFF6D8), .25f to glow.copy(alpha = .9f), 1f to glow.copy(alpha = 0f), center = sc, radius = 120 * k), 120 * k, sc, alpha = a)
        if (!look.rainy) {
            drawCircle(core, 24 * k, sc)
            if (look.part == DayPart.DAY) {
                for ((ex, ey) in listOf(330f to 40f, 360f to 160f, 250f to 330f)) {
                    val p = Path().apply { moveTo(sc.x, sc.y); lineTo(ex * k, Y(ey)); lineTo(ex * k + 10 * k, Y(ey + 30)); close() }
                    drawPath(p, Color(0xFFFFF7DC), alpha = .08f)
                }
            }
        }
        // Clouds drift a little.
        val cloud = if (look.rainy) Color(0xFFE6EBED) else Color.White
        val clouds = if (look.rainy) listOf(Triple(230f, 92f, 1.3f), Triple(320f, 150f, .9f), Triple(150f, 60f, 1f), Triple(60f, 120f, 1.1f), Triple(380f, 70f, .8f))
        else listOf(Triple(230f, 92f, 1.1f), Triple(320f, 150f, .7f), Triple(150f, 60f, .6f))
        clouds.forEachIndexed { i, (cx, cy, sz) ->
            val dx = 24 * k * (.5f - .5f * kotlin.math.cos(t / 60f * 2 * PI.toFloat() + i)) - scroll * .05f
            val x = cx * k + dx
            val y = Y(cy)
            val sk = sz * k
            val al = if (i == 0) .9f else .75f
            drawOval(cloud, Offset(x - 30 * sk, y - 9 * sk), Size(60 * sk, 18 * sk), alpha = al)
            drawOval(cloud, Offset(x + 14 * sk - 17 * sk, y - 7 * sk - 11 * sk), Size(34 * sk, 22 * sk), alpha = al)
            drawOval(cloud, Offset(x - 12 * sk - 13 * sk, y - 4 * sk - 8 * sk), Size(26 * sk, 16 * sk), alpha = al)
        }
        // Birds crossing the sky, slowly.
        for (b in s.birds) {
            val span = w + 80 * k
            val bx = ((b.offset + t * b.speed) % span) - 40 * k
            val by = b.y + sin(t * .5f + b.offset) * 6 * k
            val wing = 3 * b.s * k * (.4f + .6f * (.5f + .5f * sin(t * 7f + b.offset)))
            val u = 3 * b.s * k
            val p = Path().apply { moveTo(bx, by); quadraticTo(bx + u, by - wing, bx + 2 * u, by); quadraticTo(bx + 3 * u, by - wing, bx + 4 * u, by) }
            drawPath(p, Color(0xFF3B4A3A), style = Stroke(1.3f * k, cap = StrokeCap.Round))
        }
    }
    // Far hills, the tree line, the mist between.
    translate(-scroll * .3f, 0f) { drawPath(s.hills, look.hill) }
    translate(-scroll * .5f, 0f) { drawPath(s.treeline, look.treeline) }
    drawRect(
        Brush.verticalGradient(listOf(look.mist.copy(alpha = 0f), look.mist.copy(alpha = if (look.night) .22f else .75f), look.mist.copy(alpha = 0f)), startY = Y(246f), endY = Y(306f)),
        Offset(0f, Y(246f)), Size(w, Y(60f)),
    )
    translate(-scroll, 0f) {
        drawPath(s.meadow, Brush.verticalGradient(listOf(look.mead[0], look.mead[1], look.mead[2]), startY = Y(290f), endY = size.height))
        drawPath(s.trail, Brush.verticalGradient(listOf(Color(0xFFF2E6C6), Color(0xFFE9D7AE)), startY = Y(296f), endY = Y(560f)), alpha = if (look.night) .35f else 1f)
        for (row in 0..3) {
            if (row == 3 && g.pond && pond != null) {
                drawImage(pond, Offset(g.pondX - pond.width / 2f, g.pondBase - pond.height * 226f / 240f))
            }
            for (p in g.placed) {
                if (p.row != row) continue
                val img = images[p.tree.id] ?: continue
                val v = p.tree.variant
                val amp = if (p.stage == Stage.GROWN) 1f else 1.7f
                val angle = amp * sin(t * 2 * PI.toFloat() / (5.5f + v % 3) + v % 7)
                rotate(angle, pivot = Offset(p.x, p.base)) {
                    drawImage(img, Offset(p.x - img.width / 2f, p.base - img.height * 226f / 240f))
                }
            }
            when (row) {
                0 -> {
                    // The back row sits further away: a little haze and mist over it.
                    drawRect(
                        Brush.verticalGradient(listOf(look.haze.copy(alpha = 0f), look.haze.copy(alpha = if (look.night) .15f else .3f), look.haze.copy(alpha = 0f)), startY = Y(170f), endY = Y(340f)),
                        Offset(scroll - 10, Y(170f)), Size(w + 20, Y(170f)),
                    )
                    drawRect(
                        Brush.verticalGradient(listOf(look.mist.copy(alpha = 0f), look.mist.copy(alpha = if (look.night) .15f else .45f), look.mist.copy(alpha = 0f)), startY = Y(272f), endY = Y(332f)),
                        Offset(scroll - 10, Y(272f)), Size(w + 20, Y(60f)),
                    )
                    s.tufts[0].forEachIndexed { i, p -> drawPath(p, TUFT[i], style = Stroke(1.2f * k, cap = StrokeCap.Round)) }
                }
                1 -> s.tufts[1].forEachIndexed { i, p -> drawPath(p, TUFT[i], style = Stroke(1.2f * k, cap = StrokeCap.Round)) }
                3 -> {
                    s.tufts[2].forEachIndexed { i, p -> drawPath(p, TUFT[i], style = Stroke(1.3f * k, cap = StrokeCap.Round)) }
                    if (!look.night) s.flowers.forEachIndexed { i, p -> drawPath(p, FLOWER[i]) }
                }
            }
        }
        if (!look.night && !look.rainy) {
            for ((i, b) in s.bflies.withIndex()) {
                val a = t * b.speed + b.p1
                val x = b.cx + b.rx * sin(a) + b.rx * .35f * sin(a * 2.1f + b.p2)
                val y = b.cy + b.ry * sin(a * 1.4f + b.p3)
                butterfly(b.kind, x, y, b.size, t, i * .37f)
            }
        }
    }
    if (look.night) {
        drawRect(Color(0xFF0A1A2C), alpha = .42f)
        for ((y0, h) in listOf(300f to 80f, 470f to 70f)) {
            drawRect(
                Brush.verticalGradient(listOf(look.mist.copy(alpha = 0f), look.mist.copy(alpha = .22f), look.mist.copy(alpha = 0f)), startY = Y(y0), endY = Y(y0 + h)),
                Offset(0f, Y(y0)), Size(w, Y(h)),
            )
        }
        translate(-scroll, 0f) {
            for (f in s.flies) {
                val m = .5f - .5f * kotlin.math.cos((t / (f.dur * 2.5f)) * 2 * PI.toFloat())
                firefly(f.x + f.dx * m, f.y + f.dy * m, f.r, twinkle(t, f.dur, f.begin), 1.6f * k)
            }
        }
    }
    // Weather and the light's colour.
    if (look.rainy) {
        for (d in s.drops) {
            val x = ((d.x + t * d.speed * .3f) % (w + 40)) - 20
            val y = ((d.y + t * d.speed) % (size.height + 40)) - 20
            drawLine(Color(0xFFE8F1F6), Offset(x, y), Offset(x - 3 * k, y + 9 * k), strokeWidth = 1f * k, alpha = .6f)
        }
    }
    for (l in s.leaves) {
        val span = gb * .75f
        val y = gb * .28f + ((l.y + t * 16 * k) % span)
        val x = l.x + sin(t * .7f + l.ph) * 14 * k
        rotate((t * 120 + l.ph * 60) % 360, pivot = Offset(x + 4 * k, y)) {
            val p = Path().apply { moveTo(x, y); quadraticTo(x + 4 * k, y - 3 * k, x + 8 * k, y); quadraticTo(x + 4 * k, y + 3 * k, x, y); close() }
            drawPath(p, l.color)
        }
    }
    when {
        look.part == DayPart.EVENING -> drawRect(Color(0xFFF6B25C), alpha = .1f)
        look.part == DayPart.DAWN -> drawRect(Color(0xFFF7B9A0), alpha = .06f)
        look.season == Season.AUTUMN && !look.night -> drawRect(Color(0xFFF6B25C), alpha = .06f)
    }
    if (look.resting && !look.night) drawRect(Color(0xFFD9A441), alpha = .05f)
}

private val TUFT = listOf(Color(0xFF5F9A45), Color(0xFF6FA84E), Color(0xFF7FB55A), Color(0xFF4E8A3A))
private val FLOWER = listOf(Color.White, Color(0xFFFDE68A), Color(0xFFF9A8D4), Color(0xFFC4B5FD))

/**
 * The forest: the sky of this hour and season, hills in the mist, a meadow with a path, and every
 * tree — swaying a little, with its visitors. Tap a tree for a small card; tap the card to open it.
 * [groundBottom]: where the front row stands (the home card covers what's below).
 */
@Composable
internal fun ForestScene(
    trees: List<Tree>,
    groundBottom: Dp,
    selected: String?,
    onSelect: (String?) -> Unit,
    onOpenTree: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = animationsOn()
    val clock = rememberClock(animate)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val minute = now / 60_000
    val month = remember(minute) { FDays.month() }
    val part = remember(minute) { DayPart.now() }
    val rainy = remember(minute) { rainyToday() }
    val resting = ForestStore.resting()
    val lotus = ForestStore.lotusPond()
    val look = remember(part, month, rainy, resting) { Look(part, Season.of(month), rainy, resting) }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val gb = with(density) { groundBottom.toPx() }.coerceAtMost(h)
        val geo = remember(trees, w, gb, lotus, minute) { layout(trees, now, w, gb, lotus) }
        val vis = remember(trees, minute) { visitors(trees, now, month) }
        val statics = remember(geo, vis, look) { Statics(geo, vis, look) }
        // Each tree shows as soon as it's drawn (front rows first), not all at once.
        val images by produceState<Map<String, ImageBitmap>>(emptyMap(), geo, month, resting) {
            val done = HashMap(value) // keep what is shown while redrawing (every minute)
            for (p in geo.placed.sortedByDescending { it.row }) {
                done[p.tree.id] = withContext(Dispatchers.Default) { TreeImages.get(p.tree.species, p.stage, p.tree.variant, month, resting, p.w.roundToInt()) }
                if (done.size % 3 == 0 || done.size == geo.placed.size) value = HashMap(done)
            }
        }
        val pond by produceState<ImageBitmap?>(null, geo) {
            value = if (geo.pond) withContext(Dispatchers.Default) { TreeImages.pond(geo.pondW.roundToInt()) } else null
        }
        val scroll = rememberScrollState()
        Canvas(Modifier.fillMaxSize()) { drawForest(geo, look, statics, clock.value, scroll.value.toFloat(), images, pond) }
        Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Spacer(
                Modifier.width(with(density) { geo.sceneW.toDp() }).fillMaxHeight().pointerInput(geo) {
                    detectTapGestures { o ->
                        val min = 22.dp.toPx()
                        val hit = geo.placed.asReversed().firstOrNull { p ->
                            val half = maxOf(p.w * .32f, min)
                            o.x in (p.x - half)..(p.x + half) && o.y in minOf(p.top, p.base - 2 * min)..(p.base + 8.dp.toPx())
                        }
                        onSelect(hit?.tree?.id)
                    }
                },
            )
        }
        val sel = selected?.let { id -> geo.placed.firstOrNull { it.tree.id == id } }
        if (sel != null) {
            LaunchedEffect(sel.tree.id) {
                delay(9_000)
                onSelect(null)
            }
            TreeCard(sel, resting, scroll.value.toFloat(), w, onOpen = { onOpenTree(sel.tree.id) })
        }
    }
}

/** The little card over a tapped tree: its name, how far it has grown, what's next. */
@Composable
private fun TreeCard(p: Placed, resting: Boolean, scroll: Float, viewW: Float, onOpen: () -> Unit) {
    val c = forestColors()
    val density = LocalDensity.current
    val cardW = with(density) { 200.dp.toPx() }
    val cardH = with(density) { 54.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val x = (p.x - scroll - cardW / 2).coerceIn(gap, (viewW - cardW - gap).coerceAtLeast(gap))
    val above = p.top - cardH - gap
    val y = if (above > with(density) { 90.dp.toPx() }) above else p.base + gap
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.widthIn(max = 200.dp)
            .shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(c.card.copy(alpha = .97f))
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Ring(p.tree.growth(), 26.dp, 3.dp, c.moss, c.line)
        Column {
            Text(p.tree.stageName(), fontSize = 14.sp, color = c.ink, maxLines = 1)
            Text(p.tree.nextText(resting = resting), fontSize = 11.sp, lineHeight = 14.sp, color = c.muted, maxLines = 2)
        }
    }
}

// ------------------------------------------------------------------ one tree, on its own
/** Small stages are cropped closer, so a seed is still a seed at icon size. */
internal fun cropFor(stage: Stage): Rect = when (stage) {
    Stage.SEED -> Rect(40f, 168f, 160f, 234f)
    Stage.SPROUT -> Rect(40f, 150f, 160f, 234f)
    Stage.SAPLING -> Rect(40f, 110f, 160f, 234f)
    else -> Rect(0f, 0f, 200f, 240f)
}

private val Faded = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * A drawn tree that fits its box, standing on the box's bottom edge. [faded] = not found yet
 * (pencil-grey). [sway] moves it gently.
 */
@Composable
internal fun TreePicture(
    species: Species,
    stage: Stage,
    variant: Int,
    modifier: Modifier = Modifier,
    month: Int = FDays.month(),
    resting: Boolean = false,
    crop: Rect = cropFor(stage),
    faded: Boolean = false,
    alpha: Float = 1f,
    sway: Boolean = false,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val bmp by produceState<ImageBitmap?>(null, box, species, stage, variant, month, resting) {
        if (box.width == 0 || box.height == 0) return@produceState
        val scale = minOf(box.width / crop.width, box.height / crop.height)
        value = withContext(Dispatchers.Default) { TreeImages.get(species, stage, variant, month, resting, (200 * scale).roundToInt()) }
    }
    val clock = if (sway) rememberClock() else null
    Canvas(modifier.onSizeChanged { box = it }) {
        val b = bmp ?: return@Canvas
        val sc = b.width / 200f
        val sx = (crop.left * sc).roundToInt().coerceIn(0, b.width - 1)
        val sy = (crop.top * sc).roundToInt().coerceIn(0, b.height - 1)
        val sw = (crop.width * sc).roundToInt().coerceAtMost(b.width - sx)
        val sh = (crop.height * sc).roundToInt().coerceAtMost(b.height - sy)
        val dx = ((size.width - sw) / 2).roundToInt()
        val dy = (size.height - sh).roundToInt()
        val angle = clock?.let { 1.6f * sin(it.value * 2 * PI.toFloat() / 6f) } ?: 0f
        rotate(angle, pivot = Offset(size.width / 2, dy + (226f - crop.top) * sc)) {
            drawImage(
                b, IntOffset(sx, sy), IntSize(sw, sh), IntOffset(dx, dy), IntSize(sw, sh),
                alpha = if (faded) .42f * alpha else alpha, colorFilter = if (faded) Faded else null,
            )
        }
    }
}
