package com.agani.syncup.forest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Forest's trees, drawn by code (the same shapes as docs/mockup/forest-art): every leaf mass is
 * many small wobbly blobs, shaded lighter toward the sun (upper left) and darker away, over a
 * darker back layer; bark has a gradient; each tree stands on a soft shadow with grass tufts.
 * [Tree.variant] seeds the randomness, so no two trees are the same.
 *
 * Everything is in a 200 × 240 box with the trunk's foot at (100, 226). [TreeImages] turns a
 * drawing into a bitmap once; the scene then only moves bitmaps (sway) — cheap every frame.
 */
internal class Part(val path: Path, val color: Color = Color.Unspecified, val brush: Brush? = null, val strokeWidth: Float = 0f, val alpha: Float = 1f)

internal object TreeArt {
    private const val LX = -0.55f
    private const val LY = -0.83f

    private fun c(hex: Long) = Color(hex.toInt())

    private class B(val rnd: Random) {
        val parts = ArrayList<Part>()
        fun add(p: Path, color: Color, alpha: Float = 1f) = parts.add(Part(p, color, alpha = alpha))
        fun add(p: Path, brush: Brush) = parts.add(Part(p, brush = brush))
        fun stroke(p: Path, color: Color, w: Float, alpha: Float = 1f) = parts.add(Part(p, color, strokeWidth = w, alpha = alpha))
        fun u(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    }

    private fun blob(cx: Float, cy: Float, r: Float, rnd: Random, n: Int = 8, wob: Float = .17f, squash: Float = .9f, into: Path = Path()): Path {
        val off = rnd.nextFloat() * 2 * PI.toFloat()
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val a = off + 2 * PI.toFloat() * i / n
            val rr = r * (1 + (rnd.nextFloat() * 2 - 1) * wob)
            xs[i] = cx + rr * cos(a)
            ys[i] = cy + rr * sin(a) * squash
        }
        into.moveTo((xs[n - 1] + xs[0]) / 2, (ys[n - 1] + ys[0]) / 2)
        for (i in 0 until n) {
            val j = (i + 1) % n
            into.quadraticTo(xs[i], ys[i], (xs[i] + xs[j]) / 2, (ys[i] + ys[j]) / 2)
        }
        into.close()
        return into
    }

    /** A leaf mass: back layer, then shadow → highlight (light ones on top). */
    private fun B.canopy(inside: (Float, Float) -> Boolean, x0: Float, y0: Float, x1: Float, y1: Float, n: Int, rmin: Float, rmax: Float, pal: List<Color>, back: Float = .6f, jitter: Float = .28f) {
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val w = (x1 - x0) / 2
        val h = (y1 - y0) / 2
        data class Item(val l: Float, val x: Float, val y: Float, val r: Float)
        val items = ArrayList<Item>()
        var guard = 0
        while (items.size < n && guard++ < n * 200) {
            val x = u(x0, x1)
            val y = u(y0, y1)
            if (!inside(x, y)) continue
            val r = u(rmin, rmax)
            val l = ((x - cx) / w) * LX + ((y - cy) / h) * LY + u(-jitter, jitter)
            items.add(Item(l, x, y, r))
        }
        items.sortBy { it.l }
        val backPath = Path()
        items.forEach { if (rnd.nextFloat() < back) blob(it.x, it.y + it.r * .25f, it.r * 1.12f, rnd, into = backPath) }
        add(backPath, pal[0])
        val layers = List(4) { Path() }
        items.forEach {
            val k = when {
                it.l < -.35f -> 0
                it.l < .1f -> 1
                it.l < .55f -> 2
                else -> 3
            }
            blob(it.x, it.y, it.r, rnd, into = layers[k])
        }
        layers.forEachIndexed { i, p -> add(p, pal[i]) }
    }

    private fun ell(cx: Float, cy: Float, rx: Float, ry: Float): (Float, Float) -> Boolean = { x, y -> ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it } <= 1 }

    private fun leafPath(x: Float, y: Float, len: Float, wid: Float, angDeg: Float, into: Path = Path()): Path {
        val a = Math.toRadians(angDeg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        fun px(u: Float, v: Float) = x + u * ca - v * sa
        fun py(u: Float, v: Float) = y + u * sa + v * ca
        into.moveTo(x, y)
        into.quadraticTo(px(len * .42f, wid), py(len * .42f, wid), px(len, 0f), py(len, 0f))
        into.quadraticTo(px(len * .42f, -wid), py(len * .42f, -wid), x, y)
        into.close()
        return into
    }

    private fun B.trunk(xb: Float, wb: Float, xt: Float, wt: Float, yt: Float, yb: Float = 226f, bend: Float = 0f, dark: Boolean = false) {
        val p = Path().apply {
            moveTo(xb - wb / 2, yb)
            quadraticTo((xb + xt) / 2 - wb / 2 + bend, (yb + yt) / 2, xt - wt / 2, yt)
            lineTo(xt + wt / 2, yt)
            quadraticTo((xb + xt) / 2 + wb / 2 + bend, (yb + yt) / 2, xb + wb / 2, yb)
            close()
        }
        add(p, bark(xb - wb, xb + wb, dark))
    }

    private fun bark(x0: Float, x1: Float, dark: Boolean = false) = Brush.horizontalGradient(
        if (dark) listOf(c(0xFF8A6A4A), c(0xFF5E4430), c(0xFF3E2C1E)) else listOf(c(0xFF9A7050), c(0xFF6E4B30), c(0xFF4B3220)),
        startX = x0, endX = x1,
    )

    private fun B.branch(x0: Float, y0: Float, x1: Float, y1: Float, w0: Float, w1: Float = 1.2f, bow: Float = 0f) {
        val ang = atan2(y1 - y0, x1 - x0)
        val nx = -sin(ang)
        val ny = cos(ang)
        val mx = (x0 + x1) / 2 + nx * bow
        val my = (y0 + y1) / 2 + ny * bow
        val p = Path().apply {
            moveTo(x0 + nx * w0 / 2, y0 + ny * w0 / 2)
            quadraticTo(mx, my, x1 + nx * w1 / 2, y1 + ny * w1 / 2)
            lineTo(x1 - nx * w1 / 2, y1 - ny * w1 / 2)
            quadraticTo(mx, my, x0 - nx * w0 / 2, y0 - ny * w0 / 2)
            close()
        }
        add(p, bark(minOf(x0, x1) - 4, maxOf(x0, x1) + 4))
    }

    private fun B.ground(rx: Float, tufts: Int, y: Float = 226f) {
        add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(100 - rx, y - rx * .1f, 100 + rx, y + rx * .16f)) }, c(0xFF1B2A12), alpha = .18f)
        val g = Path()
        repeat(tufts) {
            val x = u(100 - rx * .8f, 100 + rx * .8f)
            val h = u(5f, 10f)
            g.moveTo(x, y + 2); g.quadraticTo(x - 2, y + 2 - h * .6f, x - 4, y + 2 - h)
            g.moveTo(x, y + 2); g.quadraticTo(x, y + 2 - h * .7f, x + 1, y + 2 - h * 1.1f)
            g.moveTo(x, y + 2); g.quadraticTo(x + 2, y + 2 - h * .5f, x + 5, y + 2 - h * .8f)
        }
        stroke(g, c(0xFF5E9A44), 1.3f)
    }

    private fun B.mound(rx: Float, y: Float = 222f) {
        add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(100 - rx - 6, y, 100 + rx + 6, y + 10)) }, c(0xFF1B2A12), alpha = .18f)
        val p = Path().apply {
            moveTo(100 - rx, y + 4)
            cubicTo(100 - rx * .6f, y - 14, 100 + rx * .6f, y - 14, 100 + rx, y + 4)
            close()
        }
        add(p, Brush.radialGradient(listOf(c(0xFF9A6E45), c(0xFF7A5233), c(0xFF5A3B22)), center = Offset(96f, y - 6), radius = rx * 1.1f))
        val pebbles = Path()
        repeat(8) {
            val x = u(100 - rx * .7f, 100 + rx * .7f)
            val yy = y + u(-5f, 2f)
            val r = u(1f, 2.1f)
            pebbles.addOval(androidx.compose.ui.geometry.Rect(x - r, yy - r * .6f, x + r, yy + r * .6f))
        }
        add(pebbles, c(0xFFA07A55))
        ground(rx + 4, 5, y + 3)
    }

    // ------------------------------------------------------------------ species and stages
    fun build(species: Species, stage: Stage, variant: Int, month: Int, resting: Boolean): List<Part> {
        val b = B(Random(variant.toLong() * 7919 + species.ordinal * 104729L + stage.ordinal))
        when (stage) {
            Stage.SEED -> b.seed()
            Stage.SPROUT -> b.sprout()
            Stage.SAPLING -> b.sapling()
            Stage.YOUNG -> b.young(resting)
            Stage.GROWN -> {
                val inSeason = month in species.season
                when (species) {
                    Species.NEEM -> b.neem(resting, flowers = inSeason)
                    Species.MANGO -> b.mango(resting, fruit = inSeason && species.fruitsIn(month), flowers = inSeason && !species.fruitsIn(month))
                    Species.GULMOHAR -> b.gulmohar(resting, bloom = inSeason)
                    Species.BANYAN -> b.banyan(resting)
                    Species.PEEPAL -> b.peepal(resting)
                    Species.COCONUT -> b.coconut()
                    Species.BAMBOO -> b.bamboo()
                    Species.JASMINE -> b.jasmine(flowers = inSeason)
                }
            }
        }
        return b.parts
    }

    private val autumn = listOf(c(0xFF7A5A2A), c(0xFF977236), c(0xFFB48E4A), c(0xFFD2B171))

    private fun B.seed() {
        add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(70f, 176f, 130f, 236f)) }, Brush.radialGradient(listOf(c(0x8CFFE9A8), c(0x00FFE9A8)), center = Offset(100f, 206f), radius = 30f))
        mound(50f)
        val seed = Path().apply { addOval(androidx.compose.ui.geometry.Rect(91f, 205.6f, 109f, 218.4f)) }
        add(seed, Brush.radialGradient(listOf(c(0xFFC08A55), c(0xFF6B4320)), center = Offset(96f, 209f), radius = 10f))
        stroke(Path().apply { moveTo(96f, 208f); quadraticTo(93f, 206f, 94f, 202f) }, c(0xFFF4F0D8), 1.2f)
        add(Path().apply { moveTo(86f, 220f); cubicTo(92f, 214f, 108f, 214f, 114f, 220f); close() }, c(0xFF8C6239))
    }

    private fun B.sprout() {
        mound(48f)
        stroke(Path().apply { moveTo(100f, 218f); cubicTo(99f, 206f, 101f, 196f, 100f, 184f) }, c(0xFF5E8B37), 3f)
        val coty = Brush.verticalGradient(listOf(c(0xFFA6D877), c(0xFF5E9E3C)), startY = 168f, endY = 192f)
        add(Path().apply { moveTo(100f, 186f); cubicTo(92f, 174f, 76f, 172f, 70f, 178f); cubicTo(76f, 190f, 92f, 192f, 100f, 186f); close() }, coty)
        add(Path().apply { moveTo(100f, 184f); cubicTo(108f, 170f, 124f, 168f, 130f, 174f); cubicTo(124f, 186f, 108f, 190f, 100f, 184f); close() }, coty)
        add(leafPath(100f, 184f, 13f, 4f, -88f), c(0xFF5E9E3C))
        add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(121.8f, 170.8f, 126.2f, 175.2f)) }, c(0xFFE6F6FF), alpha = .85f)
    }

    private fun B.sapling() {
        mound(40f)
        stroke(Path().apply { moveTo(120f, 222f); lineTo(120f, 138f) }, c(0xFFCFA971), 2.6f)
        stroke(Path().apply { moveTo(116f, 176f); quadraticTo(121f, 180f, 123f, 176f) }, c(0xFFEADFC6), 1.4f)
        stroke(Path().apply { moveTo(100f, 220f); cubicTo(99f, 196f, 102f, 170f, 100f, 132f) }, c(0xFF5E8B37), 3.2f)
        val leaves = Path()
        val ys = floatArrayOf(204f, 192f, 180f, 168f, 158f, 148f, 140f)
        ys.forEachIndexed { i, y ->
            val side = if (i % 2 == 0) 1 else -1
            leafPath(100f, y, 22f - i * 1.6f, 7.5f - i * .4f, (if (side == 1) -28f else -152f) + u(-8f, 8f), leaves)
        }
        add(leaves, Brush.linearGradient(listOf(c(0xFF8CC560), c(0xFF3F7F2E)), start = Offset(80f, 130f), end = Offset(124f, 210f)))
        add(leafPath(100f, 133f, 11f, 3.6f, -70f), c(0xFF8FC463))
        add(leafPath(100f, 133f, 10f, 3.4f, -112f), c(0xFF7CB656))
    }

    private fun B.young(resting: Boolean) {
        ground(44f, 7)
        stroke(Path().apply { moveTo(118f, 226f); lineTo(118f, 156f) }, c(0xFFC9A36A), 3f)
        trunk(100f, 8f, 101f, 4f, 132f)
        branch(100f, 150f, 84f, 126f, 2.5f, 1f)
        branch(101f, 146f, 116f, 122f, 2.5f, 1f)
        canopy(ell(100f, 108f, 40f, 38f), 58f, 68f, 142f, 148f, 90, 6f, 10f, if (resting) autumn else listOf(c(0xFF25562B), c(0xFF336E36), c(0xFF4D8C47), c(0xFF7DB765)), back = .5f)
    }

    private fun B.neem(resting: Boolean, flowers: Boolean) {
        ground(70f, 8)
        trunk(100f, 15f, 101f, 8f, 140f)
        branch(100f, 160f, 62f, 112f, 7f, 2f); branch(101f, 150f, 140f, 104f, 7f, 2f); branch(100f, 142f, 98f, 74f, 6f, 2f, bow = 4f); branch(99f, 170f, 76f, 140f, 4f, 1.5f)
        val shape = { x: Float, y: Float -> ell(64f, 106f, 40f, 28f)(x, y) || ell(137f, 100f, 40f, 30f)(x, y) || ell(100f, 70f, 46f, 34f)(x, y) || ell(84f, 132f, 26f, 15f)(x, y) || ell(120f, 130f, 28f, 15f)(x, y) }
        canopy(shape, 20f, 30f, 180f, 150f, 210, 5.5f, 10f, if (resting) autumn else listOf(c(0xFF284F27), c(0xFF376B32), c(0xFF4F8A40), c(0xFF7DB35C)), back = .55f)
        if (flowers && !resting) dots(shape, 20f, 30f, 180f, 120f, 70, c(0xFFF8F4E6), 1.2f)
        if (resting) fallen()
    }

    private fun B.mango(resting: Boolean, fruit: Boolean, flowers: Boolean) {
        ground(74f, 8)
        trunk(100f, 18f, 100f, 10f, 160f)
        branch(100f, 168f, 66f, 132f, 7f, 2f); branch(100f, 166f, 136f, 130f, 7f, 2f)
        val pal = if (resting) autumn else listOf(c(0xFF173F25), c(0xFF235A33), c(0xFF327443), c(0xFF55965C))
        add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(26f, 58f, 174f, 166f)) }, pal[0])
        canopy({ x, y -> ell(100f, 108f, 80f, 62f)(x, y) && y < 166 }, 20f, 46f, 180f, 166f, 230, 8f, 13.5f, pal, back = .4f)
        val glint = Path()
        repeat(14) {
            val x = u(48f, 120f)
            val y = u(60f, 120f)
            glint.addOval(androidx.compose.ui.geometry.Rect(x - 3.2f, y - 1.2f, x + 3.2f, y + 1.2f))
        }
        add(glint, Color.White, alpha = .14f)
        if (fruit && !resting) {
            listOf(52f to 150f, 76f to 158f, 100f to 154f, 126f to 160f, 148f to 146f, 88f to 140f, 138f to 132f).forEach { (x, y) ->
                stroke(Path().apply { moveTo(x, y - 8); lineTo(x, y - 4) }, c(0xFF5A3B1E), 1f)
                add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(x - 5.2f, y - 7, x + 5.2f, y + 7)) }, Brush.linearGradient(listOf(c(0xFFFAD25A), c(0xFFF2A43A), c(0xFFDD6A2C)), start = Offset(x - 5, y - 7), end = Offset(x + 5, y + 7)))
            }
        }
        if (flowers && !resting) {
            val f = Path()
            listOf(48f to 82f, 70f to 62f, 96f to 54f, 124f to 58f, 150f to 76f, 82f to 92f, 116f to 88f).forEach { (x, y) ->
                for (i in 0 until 6) {
                    val r = 2.4f - i * .25f
                    val xx = x + u(-3f, 3f)
                    f.addOval(androidx.compose.ui.geometry.Rect(xx - r, y - i * 2.4f - r, xx + r, y - i * 2.4f + r))
                }
            }
            add(f, c(0xFFF3E3B0))
        }
        if (resting) fallen()
    }

    private fun B.gulmohar(resting: Boolean, bloom: Boolean) {
        ground(86f, 9)
        trunk(100f, 16f, 100f, 9f, 150f, bend = -2f)
        branch(100f, 156f, 42f, 108f, 8f, 2f, bow = -6f); branch(100f, 152f, 160f, 104f, 8f, 2f, bow = 6f); branch(100f, 150f, 96f, 96f, 6f, 2f)
        val shape = { x: Float, y: Float -> ell(100f, 100f, 94f, 36f)(x, y) && y < 124 }
        canopy(shape, 6f, 64f, 194f, 124f, 230, 5.5f, 9f, if (resting) autumn else listOf(c(0xFF2F5C24), c(0xFF427A2F), c(0xFF5F9A43), c(0xFF8DC067)), back = .55f)
        if (bloom && !resting) {
            data class F(val l: Float, val x: Float, val y: Float, val r: Float)
            val fl = ArrayList<F>()
            repeat(150) {
                val x = u(10f, 190f)
                val y = u(66f, 116f)
                if (shape(x, y) && !(y > 104 && rnd.nextFloat() < .6f)) {
                    fl.add(F(((x - 100) / 94) * LX + ((y - 90) / 36) * LY + u(-.3f, .3f), x, y, u(2.4f, 4.6f)))
                }
            }
            fl.sortBy { it.l }
            val cols = listOf(c(0xFFB5261A), c(0xFFD63A23), c(0xFFEF5B2E), c(0xFFF8904A))
            val layers = List(4) { Path() }
            fl.forEach { blob(it.x, it.y, it.r, rnd, n = 6, wob = .3f, into = layers[if (it.l < -.3f) 0 else if (it.l < .15f) 1 else if (it.l < .55f) 2 else 3]) }
            layers.forEachIndexed { i, p -> add(p, cols[i]) }
        }
        if (resting) fallen()
    }

    private fun B.banyan(resting: Boolean) {
        ground(96f, 10)
        trunk(100f, 26f, 100f, 16f, 120f)
        trunk(46f, 8f, 50f, 6f, 128f); trunk(156f, 8f, 152f, 6f, 128f)
        branch(100f, 130f, 36f, 112f, 10f, 3f); branch(100f, 128f, 166f, 110f, 10f, 3f)
        repeat(46) {
            val x = u(18f, 182f)
            val y0 = u(118f, 134f)
            val y1 = if (rnd.nextFloat() < .7f) u(150f, 226f) else 226f
            stroke(Path().apply { moveTo(x, y0); quadraticTo(x + u(-2f, 2f), (y0 + y1) / 2, x + u(-1.5f, 1.5f), y1) }, c(0xFF7A5B40), u(.6f, 1.6f), u(.55f, .9f))
        }
        val pal = if (resting) autumn else listOf(c(0xFF1B4A2C), c(0xFF276338), c(0xFF38804A), c(0xFF5FA266))
        add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(10f, 60f, 190f, 136f)) }, pal[0])
        canopy({ x, y -> ell(100f, 92f, 98f, 50f)(x, y) && y < 132 }, 2f, 42f, 198f, 132f, 260, 7.5f, 12.5f, pal, back = .45f)
        if (resting) fallen()
    }

    private fun B.peepal(resting: Boolean) {
        ground(66f, 8)
        trunk(100f, 16f, 100f, 9f, 148f, bend = 2f)
        branch(100f, 156f, 70f, 110f, 6f, 2f); branch(100f, 150f, 134f, 104f, 6f, 2f)
        val pal = if (resting) autumn else listOf(c(0xFF355F27), c(0xFF4B7F33), c(0xFF69A146), c(0xFF98C766))
        canopy(ell(100f, 94f, 66f, 64f), 34f, 30f, 166f, 158f, 170, 7.5f, 12f, pal, back = .5f)
        val hearts = Path()
        repeat(38) {
            val a = if (rnd.nextFloat() < .7f) u(-170f, -10f) else u(-200f, 20f)
            val r = 64 + u(-4f, 4f)
            val x = 100 + r * cos(Math.toRadians(a.toDouble())).toFloat()
            val y = 94 + r * .97f * sin(Math.toRadians(a.toDouble())).toFloat()
            heart(x, y, u(5f, 7f), a + u(-25f, 25f), hearts)
        }
        add(hearts, if (resting) c(0xFFD2B171) else c(0xFF86BC57))
        if (resting) fallen()
    }

    private fun heart(x: Float, y: Float, s: Float, angDeg: Float, into: Path) {
        val a = Math.toRadians(angDeg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        fun px(u: Float, v: Float) = x + u * ca - v * sa
        fun py(u: Float, v: Float) = y + u * sa + v * ca
        into.moveTo(px(0f, 0f), py(0f, 0f))
        into.cubicTo(px(-s * .3f, s * .7f), py(-s * .3f, s * .7f), px(s * .55f, s * .9f), py(s * .55f, s * .9f), px(s, s * .35f), py(s, s * .35f))
        into.quadraticTo(px(s * 1.35f, s * .1f), py(s * 1.35f, s * .1f), px(s * 1.7f, 0f), py(s * 1.7f, 0f))
        into.quadraticTo(px(s * 1.35f, -s * .1f), py(s * 1.35f, -s * .1f), px(s, -s * .35f), py(s, -s * .35f))
        into.cubicTo(px(s * .55f, -s * .9f), py(s * .55f, -s * .9f), px(-s * .3f, -s * .7f), py(-s * .3f, -s * .7f), px(0f, 0f), py(0f, 0f))
        into.close()
    }

    private fun B.coconut() {
        ground(40f, 6)
        val left = ArrayList<Offset>()
        val right = ArrayList<Offset>()
        for (i in 0..20) {
            val t = i / 20f
            val x = (1 - t) * (1 - t) * 100 + 2 * (1 - t) * t * 98 + t * t * 124
            val y = (1 - t) * (1 - t) * 226 + 2 * (1 - t) * t * 140 + t * t * 66
            val w = 9 - 3.2f * t
            left.add(Offset(x - w / 2, y))
            right.add(Offset(x + w / 2, y))
        }
        val trunkPath = Path().apply {
            moveTo(left[0].x, left[0].y)
            left.drop(1).forEach { lineTo(it.x, it.y) }
            right.reversed().forEach { lineTo(it.x, it.y) }
            close()
        }
        add(trunkPath, Brush.horizontalGradient(listOf(c(0xFFB08D63), c(0xFF6E5236)), startX = 94f, endX = 110f))
        val rings = Path()
        for (i in 1 until 20) {
            rings.moveTo(left[i].x, left[i].y)
            rings.quadraticTo((left[i].x + right[i].x) / 2, left[i].y + 2, right[i].x, right[i].y)
        }
        stroke(rings, c(0xFF5B442C), .7f, .55f)
        val cx = 124f
        val cy = 66f
        for (k in 0 until 11) {
            val ang = -180f + k * 18f + u(-6f, 6f)
            val len = u(66f, 84f)
            val rad = Math.toRadians(ang.toDouble())
            val droop = 26 + 18 * abs(cos(rad)).toFloat()
            val ex = cx + len * cos(rad).toFloat()
            val ey = cy + len * sin(rad).toFloat() * .55f + droop
            val mx = cx + len * .5f * cos(rad).toFloat()
            val my = cy + len * .5f * sin(rad).toFloat() * .55f - 8
            stroke(Path().apply { moveTo(cx, cy); quadraticTo(mx, my, ex, ey) }, c(0xFF5E8F3A), 1.6f)
            val leaflets = Path()
            for (i in 2 until 17) {
                val t = i / 17f
                val px = (1 - t) * (1 - t) * cx + 2 * (1 - t) * t * mx + t * t * ex
                val py = (1 - t) * (1 - t) * cy + 2 * (1 - t) * t * my + t * t * ey
                val tx = 2 * (1 - t) * (mx - cx) + 2 * t * (ex - mx)
                val ty = 2 * (1 - t) * (my - cy) + 2 * t * (ey - my)
                val base = Math.toDegrees(atan2(ty, tx).toDouble()).toFloat()
                val lf = 17 * (1 - .55f * t)
                leafPath(px, py, lf, 1.7f, base + 62 + u(-8f, 8f), leaflets)
                leafPath(px, py, lf, 1.7f, base - 62 + u(-8f, 8f), leaflets)
            }
            add(leaflets, listOf(c(0xFF2E7A33), c(0xFF3A8C3B), c(0xFF4E9E46))[rnd.nextInt(3)])
        }
        val nuts = Path()
        listOf(119f to 72f, 127f to 73f, 123f to 78f, 130f to 69f).forEach { (x, y) -> nuts.addOval(androidx.compose.ui.geometry.Rect(x - 4.2f, y - 4.2f, x + 4.2f, y + 4.2f)) }
        add(nuts, Brush.radialGradient(listOf(c(0xFF8FA24A), c(0xFF4E5A22)), center = Offset(122f, 70f), radius = 10f))
    }

    private fun B.bamboo() {
        ground(44f, 7)
        val culms = listOf(Triple(72f, 60f, -3f), Triple(84f, 30f, -1f), Triple(96f, 14f, 0f), Triple(108f, 40f, 1f), Triple(120f, 22f, 2f), Triple(132f, 70f, 4f))
        val leaves = List(3) { Path() }
        for ((x, top, lean) in culms) {
            add(Path().apply { moveTo(x - 3.4f, 226f); lineTo(x + lean - 2.6f, top); lineTo(x + lean + 2.6f, top); lineTo(x + 3.4f, 226f); close() },
                Brush.horizontalGradient(listOf(c(0xFF9CC45C), c(0xFF7AA846), c(0xFF557D2E)), startX = x - 4, endX = x + 4))
            var y = 216f
            while (y > top + 10) {
                val t = (226 - y) / (226 - top)
                val nx = x + lean * t
                stroke(Path().apply { moveTo(nx - 3.4f, y); lineTo(nx + 3.4f, y) }, c(0xFFE4F0C2), 1.2f, .8f)
                if (y < 150 && rnd.nextFloat() < .8f) {
                    repeat(2 + rnd.nextInt(3)) {
                        val side = if (rnd.nextBoolean()) 1 else -1
                        val ang = (if (side == 1) 20f else 160f) + u(-25f, 30f) * side
                        leafPath(nx, y, u(18f, 28f), 3.2f, ang, leaves[rnd.nextInt(3)])
                    }
                }
                y -= u(16f, 22f)
            }
        }
        add(leaves[0], c(0xFF4F8B2F)); add(leaves[1], c(0xFF64A03C)); add(leaves[2], c(0xFF7DB352))
    }

    private fun B.jasmine(flowers: Boolean) {
        ground(74f, 9)
        val pal = listOf(c(0xFF1E4626), c(0xFF2A5C31), c(0xFF3B7640), c(0xFF5A9459))
        add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(36f, 174f, 164f, 226f)) }, pal[0])
        val shape = { x: Float, y: Float -> ell(100f, 194f, 72f, 38f)(x, y) && y < 224 }
        canopy(shape, 28f, 156f, 172f, 224f, 170, 5f, 8f, pal, back = .5f)
        if (flowers) {
            val petals = Path()
            val centers = Path()
            repeat(30) {
                val x = u(36f, 164f)
                val y = u(160f, 214f)
                if (shape(x, y)) {
                    val r = u(3f, 4.2f)
                    val off = u(0f, 72f)
                    for (i in 0 until 5) {
                        val a = Math.toRadians((i * 72 + off).toDouble())
                        val px = x + r * .55f * cos(a).toFloat()
                        val py = y + r * .55f * sin(a).toFloat()
                        petals.addOval(androidx.compose.ui.geometry.Rect(px - r * .45f, py - r * .45f, px + r * .45f, py + r * .45f))
                    }
                    centers.addOval(androidx.compose.ui.geometry.Rect(x - .9f, y - .9f, x + .9f, y + .9f))
                }
            }
            add(petals, Color.White)
            add(centers, c(0xFFF7E08A))
        }
    }

    private fun B.dots(shape: (Float, Float) -> Boolean, x0: Float, y0: Float, x1: Float, y1: Float, n: Int, color: Color, r: Float) {
        val p = Path()
        repeat(n) {
            val x = u(x0, x1)
            val y = u(y0, y1)
            if (shape(x, y)) p.addOval(androidx.compose.ui.geometry.Rect(x - r, y - r, x + r, y + r))
        }
        add(p, color)
    }

    private fun B.fallen() {
        val p = Path()
        repeat(9) { leafPath(u(40f, 160f), 228 + u(-2f, 2f), 6f, 2.4f, u(0f, 360f), p) }
        add(p, c(0xFFC99B45))
    }

    /** The lotus pond (after 100 calm hours): water, pads, two lotuses and a bud. */
    fun pond(): List<Part> {
        val b = B(Random(141))
        b.add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(4f, 152f, 196f, 220f)) }, Brush.verticalGradient(listOf(c(0xFF8ED0E0), c(0xFF4E9DB8)), startY = 152f, endY = 220f))
        b.stroke(Path().apply { addOval(androidx.compose.ui.geometry.Rect(12f, 156f, 188f, 208f)) }, Color.White, 1f, .25f)
        val ripples = Path()
        for (i in 0 until 5) {
            val x = 40f + i * 28
            val y = 176f + (i % 2) * 10
            ripples.moveTo(x, y); ripples.lineTo(x + 12 + i % 3 * 6, y)
        }
        b.stroke(ripples, Color.White, 1.2f, .35f)
        val pads = Path()
        for ((x, y, r) in listOf(Triple(46f, 190f, 13f), Triple(78f, 202f, 11f), Triple(130f, 198f, 12f), Triple(154f, 182f, 10f), Triple(110f, 172f, 9f))) {
            pads.moveTo(x, y)
            pads.arcTo(androidx.compose.ui.geometry.Rect(x - r, y - r * .42f, x + r, y + r * .42f), 12f, 330f, false)
            pads.close()
        }
        b.add(pads, Brush.linearGradient(listOf(c(0xFF6FB25A), c(0xFF2F7A3A)), start = Offset(30f, 160f), end = Offset(170f, 214f)))
        for ((x, y, k) in listOf(Triple(84f, 194f, 1f), Triple(138f, 186f, .8f))) {
            val petals = Path()
            for (a in floatArrayOf(-62f, -32f, 32f, 62f, 0f)) {
                val rad = Math.toRadians((a * k).toDouble())
                val ca = cos(rad).toFloat()
                val sa = sin(rad).toFloat()
                fun px(u: Float, v: Float) = x + u * ca - v * sa
                fun py(u: Float, v: Float) = y + u * sa + v * ca
                petals.moveTo(x, y)
                petals.cubicTo(px(-3f, -6f), py(-3f, -6f), px(-3f, -14f), py(-3f, -14f), px(0f, -18f), py(0f, -18f))
                petals.cubicTo(px(3f, -14f), py(3f, -14f), px(3f, -6f), py(3f, -6f), x, y)
                petals.close()
            }
            b.add(petals, Brush.verticalGradient(listOf(c(0xFFE7709A), c(0xFFF7C6D6)), startY = y - 18, endY = y))
            b.add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(x - 3, y - 3.6f, x + 3, y - .4f)) }, c(0xFFF7D35B))
        }
        b.stroke(Path().apply { moveTo(118f, 178f); quadraticTo(119f, 168f, 121f, 166f) }, c(0xFF4E8A3A), 1.2f)
        b.add(Path().apply { addOval(androidx.compose.ui.geometry.Rect(118.4f, 159f, 123.6f, 169f)) }, c(0xFFF48FB1))
        return b.parts
    }

    /** Draw [parts] (200 × 240 space) into this scope's area. */
    fun DrawScope.drawParts(parts: List<Part>) {
        scale(size.width / 200f, size.height / 240f, pivot = Offset.Zero) {
            for (p in parts) {
                if (p.strokeWidth > 0) {
                    drawPath(p.path, p.color, alpha = p.alpha, style = Stroke(p.strokeWidth, cap = StrokeCap.Round))
                } else if (p.brush != null) {
                    drawPath(p.path, p.brush, alpha = p.alpha)
                } else {
                    drawPath(p.path, p.color, alpha = p.alpha)
                }
            }
        }
    }
}

/** Trees drawn once into bitmaps, kept for reuse (a forest of 40 trees costs 40 drawings, once). */
internal object TreeImages {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 80
    }

    fun get(species: Species, stage: Stage, variant: Int, month: Int, resting: Boolean, widthPx: Int): ImageBitmap {
        val w = widthPx.coerceIn(16, 900)
        val h = (w * 1.2f).toInt()
        val key = "$species|$stage|$variant|${if (stage == Stage.GROWN) month else 0}|$resting|$w"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = ImageBitmap(w, h)
        val parts = TreeArt.build(species, stage, variant, month, resting)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            with(TreeArt) { drawParts(parts) }
        }
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    fun pond(widthPx: Int): ImageBitmap {
        val w = widthPx.coerceIn(16, 900)
        val key = "pond|$w"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = ImageBitmap(w, (w * 1.2f).toInt())
        val parts = TreeArt.pond()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), w * 1.2f)) {
            with(TreeArt) { drawParts(parts) }
        }
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }
}
