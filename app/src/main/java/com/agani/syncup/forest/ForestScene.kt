package com.agani.syncup.forest

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Forest (v2) scene: the approved mockup (scene() in docs/mockup/forest-art/forest_v2_page.py) drawn live.
 * ForestRules works out what is where; this file draws it, depth-sorted, with gentle motion, and reports taps.
 * The 360 × 734 design space is scaled to the width; the ground sits on the bottom edge, so a taller screen shows
 * more sky and a shorter one cuts the sky's top.
 */

private const val W = ForestRules.W
private const val H = ForestRules.H
private const val TAU = (2 * PI).toFloat()

/** What a tap on the scene hit. */
internal sealed interface ForestTap {
    data class TreeTap(val tree: ForestTree) : ForestTap
    data class ThingTap(val thing: ForestThing) : ForestTap
    data object MoonTap : ForestTap
}

/**
 * The living forest for `now`, filling `modifier`'s size. Taps report what was hit, and where (in this composable's px).
 * A tap on nothing calls nothing. With [animate] off (or Android's Remove animations) it draws a still picture.
 */
@Composable
internal fun ForestScene(now: ForestNow, modifier: Modifier = Modifier, animate: Boolean = animationsOn(), onTap: (ForestTap, Offset) -> Unit = { _, _ -> }) {
    val context = LocalContext.current.applicationContext
    val clock = rememberClock(animate)
    val model = remember(now) { SceneModel(now) }
    var px by remember { mutableStateOf(IntSize.Zero) }
    val scale = px.width / W
    val art = produceState(SceneArt.EMPTY, model, scale) { if (scale > 0f) loadSceneArt(context, model, scale) }
    // ~25 MB of pictures: let them go when the forest closes.
    DisposableEffect(Unit) { onDispose { SpriteImages.clear(); SpriteCrops.clear() } }
    val modelNow by rememberUpdatedState(model)
    val animateNow by rememberUpdatedState(animate)
    val tapNow by rememberUpdatedState(onTap)
    val description = remember(now) { sceneDescription(now) }
    Canvas(
        modifier
            .onSizeChanged { px = it }
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val t = if (animateNow) clock.value else 0f
                    val tol = (6.dp.toPx() * W / size.width).coerceIn(2f, 9f)
                    modelNow.hit(o, size.width.toFloat(), size.height.toFloat(), art.value, t, tol)?.let { tapNow(it, o) }
                }
            },
    ) {
        drawScene(model, art.value, if (animate) clock.value else 0f, animate)
    }
}

/** A small picture of one sprite, fitted into the box (for the This-month page and cards). Trees ("t-neem", "t-seed" …) too. */
@Composable
internal fun SpriteIcon(sprite: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var box by remember { mutableStateOf(IntSize.Zero) }
    val pic by produceState<IconPicture?>(null, sprite, box) {
        if (box.width <= 0 || box.height <= 0) return@produceState
        value = withContext(Dispatchers.Default) { runCatching { iconPicture(context, sprite, box) }.getOrNull() }
    }
    Canvas(modifier.onSizeChanged { box = it }) {
        val p = pic ?: return@Canvas
        val c = p.crop
        if (c.width <= 0 || c.height <= 0) return@Canvas
        val k = min(size.width / c.width, size.height / c.height)
        withTransform({
            translate((size.width - c.width * k) / 2, (size.height - c.height * k) / 2)
            scale(k, k, Offset.Zero)
        }) {
            drawImage(p.img, IntOffset(c.left, c.top), IntSize(c.width, c.height), IntOffset.Zero, IntSize(c.width, c.height), filterQuality = FilterQuality.Medium)
        }
    }
}

/** "Forest, day 22 of 31, 22 trees, evening". */
private fun sceneDescription(now: ForestNow): String {
    val parts = arrayListOf("Forest", "day ${now.day} of ${now.monthLength}")
    if (now.phase != ForestPhase.GREEN) parts += now.phase.title.lowercase()
    if (now.leafy || now.phase == ForestPhase.DRY) parts += now.treeCount.let { if (it == 1) "1 tree" else "$it trees" }
    parts += when (now.time) {
        ForestTime.DAWN -> "dawn"
        ForestTime.DAY -> "day"
        ForestTime.DUSK -> "evening"
        ForestTime.NIGHT -> "night"
    }
    return parts.joinToString(", ")
}

// ------------------------------------------------------------------ what moves, and how
private enum class ItemMotion { NONE, TREE, BREATH, HOP, DUCK, SWING, BEES, WALK, DANCE, TWISTER, LEAP, SURFACE, BOB }

private val SPRITE_IDS: Map<String, Sprite> = Sprite.entries.associateBy { it.name.lowercase().replace('_', '-') }

/** "a-elephant-calf" → [Sprite.A_ELEPHANT_CALF]. */
private fun forestSprite(id: String): Sprite? = SPRITE_IDS[id]

/** The mockup's dryF filter. SVG runs it in linear light; this is its best fit in sRGB (mean error under 3/255). */
private val DRY_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            .495f, .272f, .050f, 0f, 60.2f,
            .452f, .295f, .051f, 0f, 38.5f,
            .293f, .179f, .128f, 0f, 21.7f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** A soft ground shadow: a unit circle, scaled to the thing's ellipse. */
private val SOFT_SHADOW = Brush.radialGradient(
    0f to Color(0x421A2414), .5f to Color(0x331A2414), .85f to Color(0x141A2414), 1f to Color(0x001A2414),
    center = Offset.Zero, radius = 1f,
)

/** One tree or thing in the drawing list, with how it moves. */
private class SceneItem(val thing: ForestThing) {
    val tree: ForestTree? = thing.tree
    val sprite: Sprite? = forestSprite(thing.sprite)

    /** A living tree, drawn from a TreeArt picture (dead trees use the "o-dead-tree" sprite). */
    val treeKey: String? = tree?.takeIf { !it.dead }?.let { "${it.kind.species}|${it.stage}|${it.variant}|${if (it.stage == Stage.GROWN) it.artMonth else 0}" }
    val filter: ColorFilter? = if (tree?.dry == true) DRY_FILTER else null
    val shadowAlpha = if (thing.sprite == "o-picnic") .85f else 1f

    /** The tree it sits in (it sways with it). */
    var host: SceneItem? = null

    // Where it is now: the wanderers move (see [SceneModel.move]); everything else stays where it was put.
    var x = thing.x
    var y = thing.baseY
    var w = thing.w
    var flip = thing.flip
    var key = thing.key
    var moving = false

    /** How high its steps or hops lift it this frame. */
    var lift = 0f

    /** How deep the flood is where it stands (0 = dry). A sea creature floats on top; anything else stands in it. */
    var wade = 0f
    val swimmer = thing.sprite in ForestRules.SWIMMERS
    var roamer: Roamer? = null
    var gait = Gait.WALK
    var leader: SceneItem? = null
    var lag = 0f

    /** The thing where it stands now (for taps). */
    fun now(): ForestThing = if (roamer == null) thing else thing.copy(x = x, baseY = y, w = w, flip = flip)

    /** Moves a wanderer to where it is at [t]. */
    fun move(t: Float) {
        val r = roamer ?: return
        val l = leader
        if (l != null) l.roamer?.let { r.follow(it, t, lag, thing.x - l.thing.x, thing.baseY - l.thing.baseY) } else r.update(t)
        x = r.x
        y = r.y
        flip = r.flip
        moving = r.moving
        w = thing.w * (ForestRules.depth(y.toDouble()) / ForestRules.depth(thing.baseY.toDouble())).toFloat()
        key = thing.key + (y - thing.baseY)
        lift = if (!moving) 0f else when (gait) {
            Gait.WALK -> .02f * w * abs(sin(PI.toFloat() * r.travelled / max(1f, .22f * w)))
            Gait.HOP -> .25f * w * abs(sin(PI.toFloat() * r.travelled / max(1f, .5f * w)))
            Gait.SWIM -> 0f
        }
    }

    val motion: ItemMotion
    var amp = 0f
    var period = 1f
    val offset: Float = sceneFrac(sin(thing.x * 12.9898f + thing.baseY * 78.233f) * 43758.547f) * 10f

    init {
        val id = thing.sprite
        motion = when {
            treeKey != null -> ItemMotion.TREE
            id == "a-duck" -> ItemMotion.DUCK
            id == "o-swing" -> ItemMotion.SWING
            id == "a-bees" -> ItemMotion.BEES
            id == "a-peacock-dance" -> ItemMotion.DANCE
            id.endsWith("-walk") -> ItemMotion.WALK
            id == "a-nest-eggs" -> ItemMotion.NONE
            id == "o-tornado" -> ItemMotion.TWISTER
            id == "a-dolphin" || id == "a-fish" || id == "a-fish2" -> ItemMotion.LEAP
            id == "a-whale" -> ItemMotion.SURFACE
            id == "a-octopus" || id == "a-jellyfish" || id == "a-turtle" -> ItemMotion.BOB
            id in HOPPERS -> ItemMotion.HOP
            id.startsWith("a-") || id.startsWith("p-") -> ItemMotion.BREATH
            else -> ItemMotion.NONE
        }
        val w = thing.w
        when (motion) {
            ItemMotion.TREE -> {
                val v = tree!!.variant
                amp = if (tree.stage == Stage.GROWN) .7f else 1.4f
                period = 5.5f + Math.floorMod(v, 5) * .6f
            }
            ItemMotion.BREATH -> {
                amp = (1.1f / max(w, 1f)).coerceAtMost(.018f) // the top moves about one unit
                period = 3.4f + offset % 1.6f
            }
            ItemMotion.HOP -> {
                val rabbit = id.startsWith("a-rabbit")
                amp = min(if (rabbit) 2.2f else 1.8f, w * .14f)
                period = (if (rabbit) 4.5f else 2.6f) + offset % 1.8f
                if (id == "a-nest-chicks") { amp = .5f; period = 1.3f }
            }
            ItemMotion.BEES -> amp = max(.8f, w * .06f)
            ItemMotion.LEAP -> period = (if (id == "a-dolphin") 5f else 3.6f) + offset % 1.4f
            ItemMotion.BOB -> {
                amp = if (id == "a-turtle") .5f else 1.2f
                period = 2.8f + offset % 1.2f
            }
            else -> Unit
        }
    }

    /** The tree's lean in degrees (a slow sway, with a little flutter on top). */
    fun sway(t: Float, wind: Float): Float {
        val a = TAU * (t + offset) / period
        return amp * wind * (sin(a) + .3f * sin(a * 2.3f + 1.7f))
    }

    private companion object {
        val HOPPERS = setOf("a-sparrow", "a-bulbul", "a-parrot", "a-hornbill", "a-rabbit", "a-rabbit2", "a-nest-chicks")
    }
}

/** A quick hop every [period] seconds: up and down in 0.3 s. */
private fun hopAt(t: Float, period: Float): Float {
    val c = sceneFrac(t / period) * period
    return if (c < .3f) sin(PI.toFloat() * c / .3f) else 0f
}

/** Everything for one moment: the sky's look and the depth-sorted list. */
private class SceneModel(now: ForestNow) {
    val look = SkyLook(now)
    val items: List<SceneItem> = now.things.map { SceneItem(it) }

    /** Drawing order, back to front: [items] by their key now (the wanderers' keys change as they move). */
    val order = IntArray(items.size) { it }

    // The storm's flood: the water starts at [floodLine] and is [floodDepth] deep at depth 1 (0 = no flood).
    val floodLine = now.floodLine
    private val floodDepth = if (ForestExtra.FLOOD in now.extras) ForestRules.FLOOD_DEPTH * now.flood.toFloat() else 0f

    /** How deep the water is at ground line [y]: shallow at the water's edge, its full depth 30 units in. */
    fun waterAt(y: Float): Float =
        if (floodDepth <= 0f || y <= floodLine) 0f
        else floodDepth * ForestRules.depth(y.toDouble()).toFloat() * min(1f, (y - floodLine) / 30f)

    /** Living trees by their picture key, the nearest (biggest) first, so the front fills in first. */
    val trees: LinkedHashMap<String, SceneItem> = LinkedHashMap<String, SceneItem>().also { m ->
        for (it in items.filter { it.treeKey != null }.sortedByDescending { it.thing.w }) m.getOrPut(it.treeKey!!) { it }
    }
    val sprites: Set<Sprite> = buildSet {
        for (it in items) if (it.treeKey == null) it.sprite?.let(::add)
        val x = now.extras
        if (ForestExtra.DRAGON_FLYING in x || ForestExtra.DRAGON_RESTING in x) add(Sprite.A_DRAGON)
        if (ForestExtra.DRAGONFLY in x) add(Sprite.A_DRAGONFLY)
        if (ForestExtra.LIGHT_RAIN in x || ForestExtra.RAINBOW in x) add(Sprite.O_PUDDLE)
        if (ForestExtra.RAINBOW in x) add(Sprite.O_SHOOTS)
        if (ForestExtra.VOLCANO in x) add(Sprite.O_VOLCANO)
        if (ForestExtra.FLOOD in x) add(Sprite.O_FLOOD_WAVES)
    }

    init {
        // Things up in a tree sway with it: find each one's host (its key is the host's ground y plus a little).
        val living = items.filter { it.treeKey != null }
        for (it in items) {
            if (it.thing.kind != ForestThing.Kind.IN_TREE) continue
            val y = floor(it.thing.key)
            it.host = living.filter { h -> abs(h.thing.baseY - y) < .5f }.minByOrNull { h -> abs(h.thing.x - it.thing.x) }
        }
        // The wanderers: animals on the ground (and the two of you, walking), the ducks in the pond.
        val area = RoamArea.of(now.things)
        val pond = items.firstOrNull { it.thing.sprite == "o-pond" }?.thing
        for (it in items) {
            val th = it.thing
            if (th.kind != ForestThing.Kind.GROUND && !it.swimmer) continue
            val seed = th.sprite.hashCode() * 31 + (th.x * 7f + th.baseY * 13f).toInt()
            val kind = if (th.sprite == "a-duck") ForestRoam.DUCK else ForestRoam.KINDS[th.sprite] ?: continue
            val a = when {
                th.sprite == "a-duck" -> {
                    val p = pond ?: continue
                    val k = p.w / 100f
                    area.withInside(floatArrayOf(p.x, p.baseY - 11f * k, 38f * k, 7f * k))
                }
                it.swimmer -> area.from(floodLine + 18f)
                else -> area
            }
            it.roamer = Roamer(th.x, th.baseY, kind, a, th.flip, seed)
            it.gait = kind.gait
        }
        for (it in items) it.wade = waterAt(it.y)
        for ((i, it) in items.withIndex()) {
            val f = ForestRoam.FOLLOWS[it.thing.sprite] ?: continue
            val lead = items.filter { l -> l.thing.sprite == f.leader && l.roamer != null }
                .minByOrNull { l -> abs(l.thing.x - it.thing.x) + abs(l.thing.baseY - it.thing.baseY) } ?: continue
            it.leader = lead
            it.lag = f.lag * (1f + .4f * (i % 3))
            it.roamer = Roamer(it.thing.x, it.thing.baseY, ForestRoam.KINDS.getValue(f.leader), area, it.thing.flip, 0)
            it.gait = lead.gait
        }
    }

    /** Moves the wanderers to [t] and puts the drawing order right (insertion sort: it is nearly sorted already). */
    fun move(t: Float) {
        for (it in items) {
            if (it.roamer == null) continue
            it.move(t)
            if (floodDepth > 0f) it.wade = waterAt(it.y)
        }
        val o = order
        for (i in 1 until o.size) {
            val v = o[i]
            val k = items[v].key
            var j = i - 1
            while (j >= 0 && (items[o[j]].key > k || (items[o[j]].key == k && o[j] > v))) {
                o[j + 1] = o[j]
                j--
            }
            o[j + 1] = v
        }
    }

    // ------------------------------------------------------------------ taps
    fun hit(o: Offset, wPx: Float, hPx: Float, art: SceneArt, t: Float, tol: Float): ForestTap? {
        val s = wPx / W
        if (s <= 0f) return null
        val x = o.x / s
        val y = (o.y - (hPx - H * s)) / s
        val ex = look.extras
        // Front to back: the dragon and the dragonfly fly over everything.
        if (ForestExtra.DRAGON_FLYING in ex || ForestExtra.DRAGON_RESTING in ex) {
            val th = if (ForestExtra.DRAGON_FLYING in ex) {
                val p = if (t > 0f) look.dragonFlying(t) else Offset(150f, 170f)
                ForestThing("a-dragon", p.x, p.y, 92f, false, H, ForestThing.Kind.GROUND)
            } else ForestThing("a-dragon", 300f, 236f, 70f, true, 236f, ForestThing.Kind.GROUND)
            if (spriteTapped(art.sprites[Sprite.A_DRAGON], Sprite.A_DRAGON, th, x, y, tol)) return ForestTap.ThingTap(th)
        }
        if (ForestExtra.DRAGONFLY in ex) {
            val p = if (t > 0f) look.dragonflyAt(t) else Offset(290f, 556f)
            val th = ForestThing("a-dragonfly", p.x, p.y, 22f, false, H, ForestThing.Kind.GROUND)
            if (spriteTapped(art.sprites[Sprite.A_DRAGONFLY], Sprite.A_DRAGONFLY, th, x, y, tol)) return ForestTap.ThingTap(th)
        }
        if (t > 0f) move(t)
        for (j in order.indices.reversed()) {
            val it = items[order[j]]
            val th = it.now().let { th -> if (it.swimmer && it.wade > 0f) th.copy(baseY = th.baseY - it.wade) else th }
            val tree = it.tree
            if (it.treeKey != null && tree != null) {
                if (treeTapped(it, art.trees[it.treeKey], x, y, tol)) return ForestTap.TreeTap(tree)
            } else {
                val sp = it.sprite ?: continue
                if (spriteTapped(art.sprites[sp], sp, th, x, y, tol)) return if (tree != null) ForestTap.TreeTap(tree) else ForestTap.ThingTap(th)
            }
        }
        if (ForestExtra.VOLCANO in ex) {
            val th = ForestThing("o-volcano", VOLCANO_X, VOLCANO_Y, VOLCANO_W, false, 0f, ForestThing.Kind.FLAT)
            if (spriteTapped(art.sprites[Sprite.O_VOLCANO], Sprite.O_VOLCANO, th, x, y, tol)) return ForestTap.ThingTap(th)
        }
        if (look.moonHit(x, y, tol)) return ForestTap.MoonTap
        return null
    }
}

/** On a sprite's picture (its box, then a painted pixel within [tol] design units). */
private fun spriteTapped(img: ImageBitmap?, sp: Sprite, th: ForestThing, x: Float, y: Float, tol: Float): Boolean {
    val k = th.w / (if (sp.tree) 200f else 100f)
    val bx = if (sp.tree) 100f else 50f
    val by = if (sp.tree) 226f else 96f
    val lx = if (th.flip) 2 * th.x - x else x
    val left = th.x + (sp.x0 - bx) * k
    val top = th.baseY + (sp.y0 - by) * k
    val bw = (sp.x1 - sp.x0) * k
    val bh = (sp.y1 - sp.y0) * k
    if (bw <= 0f || bh <= 0f) return false
    if (lx < left - tol || lx > left + bw + tol || y < top - tol || y > top + bh + tol) return false
    if (img == null) return lx in (left + bw * .2f)..(left + bw * .8f) && y in (top + bh * .25f)..(top + bh)
    return paintedNear(img, (lx - left) / bw, (y - top) / bh, tol / bw, tol / bh)
}

/** On a living tree: inside its rough canopy-and-trunk box, and on its picture. */
private fun treeTapped(it: SceneItem, img: ImageBitmap?, x: Float, y: Float, tol: Float): Boolean {
    val th = it.thing
    val w = th.w
    val reach = when (it.tree?.stage) {
        Stage.SEED -> .16f
        Stage.SPROUT -> .3f
        Stage.SAPLING -> .5f
        Stage.YOUNG -> .8f
        else -> .98f
    }
    if (abs(x - th.x) > .46f * w + tol || y < th.baseY - reach * w - tol || y > th.baseY + 2f + tol) return false
    if (img == null) return abs(x - th.x) <= max(.12f, reach * .42f) * w + tol
    val h = w * 1.2f
    return paintedNear(img, (x - (th.x - w / 2)) / w, (y - (th.baseY - h * 226f / 240f)) / h, tol / w, tol / h)
}

/** Is any pixel near (u, v) (0–1 across the picture) painted? Samples a 5 × 5 grid ±(ru, rv). Taps only. */
private fun paintedNear(img: ImageBitmap, u: Float, v: Float, ru: Float, rv: Float): Boolean {
    val bmp = img.asAndroidBitmap()
    for (j in -2..2) for (i in -2..2) {
        val px = ((u + ru * i / 2f) * bmp.width).toInt()
        val py = ((v + rv * j / 2f) * bmp.height).toInt()
        if (px in 0 until bmp.width && py in 0 until bmp.height && (bmp.getPixel(px, py) ushr 24) > 40) return true
    }
    return false
}

// ------------------------------------------------------------------ drawing
private fun DrawScope.drawScene(st: SceneModel, art: SceneArt, t: Float, animate: Boolean) {
    val s = size.width / W
    if (s <= 0f) return
    val offY = size.height - H * s
    val top = -offY / s // the design y at the top edge
    clipRect {
        withTransform({
            translate(0f, offY)
            scale(s, s, Offset.Zero)
        }) {
            val look = st.look
            with(look) { drawBack(t, top, animate, art.sprites) }
            if (animate) st.move(t)
            val items = st.items
            val order = st.order
            var hazed = false
            var flooded = ForestExtra.FLOOD !in look.extras
            for (j in order.indices) {
                val it = items[order[j]]
                // The air between here and the back rows: their feet fade into it, the nearer things stand in front.
                if (!hazed && it.key >= FAR_HAZE_KEY) {
                    with(look) { drawFarHaze() }
                    hazed = true
                }
                // The flood lies over the meadow from its edge on; what stands in it is drawn after, up to the water.
                if (!flooded && it.key >= st.floodLine) {
                    with(look) { drawFlood(t, animate, art.sprites) }
                    flooded = true
                }
                val h = it.host
                if (h != null && animate) rotate(h.sway(t, look.wind), Offset(h.thing.x, h.thing.baseY)) { drawSceneItem(it, art, t, animate, look.wind) }
                else drawSceneItem(it, art, t, animate, look.wind)
            }
            if (!hazed) with(look) { drawFarHaze() }
            if (!flooded) with(look) { drawFlood(t, animate, art.sprites) }
            with(look) { drawFront(t, top, animate, art.sprites) }
        }
    }
}

/** Things standing further back than this (their key) are seen through the far haze. */
private const val FAR_HAZE_KEY = 350f

private fun DrawScope.drawSceneItem(it: SceneItem, art: SceneArt, t: Float, animate: Boolean, wind: Float) {
    val wade = it.wade
    if (wade > .5f && !it.swimmer) {
        // Standing in the flood: only what's above the water shows, with a ring where it meets the water.
        clipRect(bottom = it.y - wade) { drawSceneItemDry(it, art, t, animate, wind, shadow = false) }
        val r = if (it.tree != null) max(2.5f, it.w * .07f) else it.w * .3f
        drawOval(WATER_RING, Offset(it.x - r, it.y - wade - r * .22f), Size(2 * r, r * .44f), alpha = .55f, style = RING_STROKE)
        return
    }
    drawSceneItemDry(it, art, t, animate, wind, shadow = wade <= .5f)
}

private val WATER_RING = Color(0xFFE6F1F2)
private val RING_STROKE = Stroke(.8f)

private fun DrawScope.drawSceneItemDry(it: SceneItem, art: SceneArt, t: Float, animate: Boolean, wind: Float, shadow: Boolean) {
    val th = it.thing
    val key = it.treeKey
    if (key != null) {
        val img = art.trees[key] ?: return
        val w = th.w
        val h = w * 1.2f
        if (animate) rotate(it.sway(t, wind), Offset(th.x, th.baseY)) { drawTreePicture(img, th.x - w / 2, th.baseY - h * 226f / 240f, w, h, it.filter) }
        else drawTreePicture(img, th.x - w / 2, th.baseY - h * 226f / 240f, w, h, it.filter)
        return
    }
    val x = it.x
    // A sea creature floats with its waterline (its art's base) on the water.
    val y = if (it.swimmer) it.y - it.wade else it.y
    val w = it.w
    val flip = it.flip
    if (shadow && th.shadowRx > 0f) {
        val k = w / th.w
        withTransform({
            translate(x, y - 1f)
            scale(th.shadowRx * k, th.shadowRy * k, Offset.Zero)
        }) { drawCircle(SOFT_SHADOW, 1f, Offset.Zero, alpha = it.shadowAlpha) }
    }
    val sp = it.sprite ?: return
    val img = art.sprites[sp] ?: return
    if (!animate) {
        drawSprite(img, sp, x, y, w, flip)
        return
    }
    val foot = Offset(x, y)
    val a = TAU * (t + it.offset)
    val by = y - it.lift
    when (it.motion) {
        ItemMotion.BREATH -> scale(1f, 1f + it.amp * sin(a / it.period), foot) { drawSprite(img, sp, x, by, w, flip) }
        // Hops on the spot while resting; on the move the hops come from the walk (lift).
        ItemMotion.HOP -> translate(0f, if (it.moving) 0f else -it.amp * hopAt(t + it.offset, it.period)) { drawSprite(img, sp, x, by, w, flip) }
        ItemMotion.DUCK -> withTransform({
            translate(0f, .6f * sin(a / 2.4f))
            rotate(2.2f * sin(a / 3.6f), foot)
        }) { drawSprite(img, sp, x, y, w, flip) }
        // The swing hangs from its branch: it rocks about the ropes' top.
        ItemMotion.SWING -> rotate(4f * sin(a / 3.4f), Offset(x, y - .96f * w)) { drawSprite(img, sp, x, y, w, flip) }
        ItemMotion.BEES -> translate(it.amp * sin(t * 7.3f + it.offset), it.amp * .7f * sin(t * 9.7f + 2 * it.offset)) { drawSprite(img, sp, x, y, w, flip) }
        // People walking: steps while they walk about, still while they stop to look.
        ItemMotion.WALK -> translate(0f, if (it.roamer != null) 0f else -.8f * abs(sin(PI.toFloat() * (t + it.offset) / .6f))) { drawSprite(img, sp, x, by, w, flip) }
        ItemMotion.DANCE -> rotate(1.6f * sin(a / 1.8f), foot) { drawSprite(img, sp, x, y, w, flip) }
        ItemMotion.TWISTER -> drawTwister(img, sp, x, y, w, t + it.offset)
        // Leapers show for their leap (out of the water, along, back in), then swim under for a while.
        ItemMotion.LEAP -> {
            val f = sceneFrac((t + it.offset) / it.period)
            if (f < LEAP) {
                val g = f / LEAP
                val alpha = min(1f, min(g, 1f - g) / .12f)
                translate((g - .5f) * .18f * w * (if (flip) -1f else 1f), -.06f * w * sin(PI.toFloat() * g)) { drawSprite(img, sp, x, y, w, flip, alpha = alpha) }
            }
        }
        ItemMotion.SURFACE -> withTransform({
            translate(0f, 1.6f * sin(a / 6.5f))
            rotate(1.2f * sin(a / 6.5f + 1f), foot)
        }) { drawSprite(img, sp, x, y, w, flip) }
        ItemMotion.BOB -> translate(0f, it.amp * sin(a / it.period)) { drawSprite(img, sp, x, by, w, flip, alpha = if (th.sprite == "a-jellyfish") .92f else 1f) }
        else -> drawSprite(img, sp, x, by, w, flip)
    }
}

/** The part of a leaper's cycle it is out of the water. */
private const val LEAP = .42f

/** The tornado: its funnel twists about its foot, and dust and leaves whirl round it. */
private fun DrawScope.drawTwister(img: ImageBitmap, sp: Sprite, x: Float, y: Float, w: Float, t: Float) {
    val k = w / 100f
    val h = (96f - sp.y0) * k
    withTransform({
        rotate(3f * sin(t * TAU / 1.4f), Offset(x, y))
        scale(1f + .03f * sin(t * TAU / .7f), 1f, Offset(x, y))
    }) { drawSprite(img, sp, x, y, w) }
    for (i in 0 until 10) {
        val hf = .12f + .085f * i
        val ang = t * (2.6f + .25f * i) + i * 1.7f
        val r = (8f + 30f * hf) * k
        val c = kotlin.math.cos(ang)
        val front = sin(ang)
        val px = x + c * r + 3f * hf * sin(t * TAU / 1.4f) * k
        val py = y - hf * h + front * r * .22f
        drawCircle(if (i % 3 == 0) Color(0xFF9A6A28) else Color(0xFF6E5A44), (.9f + .5f * (i % 2)) * k, Offset(px, py), alpha = .55f + .3f * front)
    }
}

/** A tree picture into the box ([left], [top], [w] × [h]) in design units. */
private fun DrawScope.drawTreePicture(img: ImageBitmap, left: Float, top: Float, w: Float, h: Float, filter: ColorFilter?) {
    withTransform({
        translate(left, top)
        scale(w / img.width, h / img.height, Offset.Zero)
    }) {
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height), colorFilter = filter, filterQuality = FilterQuality.Medium)
    }
}

// ------------------------------------------------------------------ pictures
/** The loaded pictures: sprites, and living trees by their key (drawn at about their on-screen size). */
private class SceneArt(val sprites: Map<Sprite, ImageBitmap>, val trees: Map<String, ImageBitmap>) {
    companion object {
        val EMPTY = SceneArt(emptyMap(), emptyMap())
    }
}

/**
 * Loads what [st] needs off the main thread and shows pictures as they arrive: the things (quick to decode)
 * alongside the trees (slower to draw), the nearest trees first. Pictures from the last minute that are still
 * needed are kept, so nothing blinks; a tree is redrawn only when the screen's size changes a lot.
 */
private suspend fun ProduceStateScope<SceneArt>.loadSceneArt(context: Context, st: SceneModel, scale: Float) {
    val old = value
    val sprites = HashMap<Sprite, ImageBitmap>()
    val trees = HashMap<String, ImageBitmap>()
    for (sp in st.sprites) old.sprites[sp]?.let { sprites[sp] = it }
    for (k in st.trees.keys) old.trees[k]?.let { trees[k] = it }
    // The maps are only touched here, between suspensions, on the producer's (main) thread.
    fun publish() {
        value = SceneArt(HashMap(sprites), HashMap(trees))
    }
    publish()
    coroutineScope {
        launch {
            var fresh = 0
            for (sp in st.sprites) {
                if (sp in sprites) continue
                val img = withContext(Dispatchers.IO) {
                    ensureActive()
                    runCatching { SpriteImages.get(context, sp) }.getOrNull()
                } ?: continue
                sprites[sp] = img
                if (++fresh % 6 == 0) publish()
            }
            if (fresh > 0) publish()
        }
        launch {
            var fresh = 0
            for ((key, item) in st.trees) {
                val want = (item.thing.w * scale).roundToInt().coerceIn(16, 900)
                val have = trees[key]
                if (have != null && abs(have.width - want) <= max(6, want / 10)) continue
                val tree = item.tree ?: continue
                trees[key] = withContext(Dispatchers.Default) {
                    ensureActive()
                    renderTree(tree.kind.species, tree.stage, tree.variant, tree.artMonth, want)
                }
                if (++fresh % 3 == 0) publish()
            }
            if (fresh > 0) publish()
        }
    }
}

/** One tree drawn by TreeArt into a picture [widthPx] wide (its 200 × 240 box). Kept only by the scene that asked. */
private fun renderTree(species: Species, stage: Stage, variant: Int, month: Int, widthPx: Int): ImageBitmap {
    val w = widthPx.coerceIn(16, 900)
    val h = (w * 1.2f).roundToInt()
    val bmp = ImageBitmap(w, h)
    val parts = TreeArt.build(species, stage, variant, month, false)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
        with(TreeArt) { drawParts(parts) }
    }
    return bmp
}

// ------------------------------------------------------------------ icons
private class IconPicture(val img: ImageBitmap, val crop: IntRect)

/** Where each picture's painted part is (so a small sparrow fills its icon too). */
private object SpriteCrops {
    private val map = HashMap<String, IntRect>()

    fun of(key: String, img: ImageBitmap): IntRect {
        synchronized(map) { map[key]?.let { return it } }
        val r = painted(img)
        synchronized(map) { map[key] = r }
        return r
    }

    fun clear() = synchronized(map) { map.clear() }

    private fun painted(img: ImageBitmap): IntRect {
        val bmp = img.asAndroidBitmap()
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 12) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < x0) return IntRect(0, 0, w, h)
        return IntRect(max(0, x0 - 1), max(0, y0 - 1), min(w, x1 + 2), min(h, y1 + 2))
    }
}

private fun iconPicture(context: Context, id: String, box: IntSize): IconPicture? {
    forestSprite(id)?.let { sp ->
        val img = SpriteImages.get(context, sp)
        return IconPicture(img, SpriteCrops.of(sp.name, img))
    }
    if (!id.startsWith("t-")) return null
    val (species, stage, month) = treeIconLook(id) ?: return null
    // A small picture finds where the tree is in its box; then one drawn big enough to fill the icon.
    val probeW = 120
    val probe = TreeImages.get(species, stage, 7, month, false, probeW)
    val c = SpriteCrops.of("$id|probe", probe)
    val k = min(box.width / c.width.toFloat(), box.height / c.height.toFloat())
    val wPx = (probeW * k).roundToInt().coerceIn(16, 900)
    val img = TreeImages.get(species, stage, 7, month, false, wPx)
    val f = img.width / probeW.toFloat()
    val crop = IntRect(
        (c.left * f).toInt().coerceIn(0, img.width - 1), (c.top * f).toInt().coerceIn(0, img.height - 1),
        (c.right * f).roundToInt().coerceIn(1, img.width), (c.bottom * f).roundToInt().coerceIn(1, img.height),
    )
    return IconPicture(img, if (crop.width > 0 && crop.height > 0) crop else IntRect(0, 0, img.width, img.height))
}

/** A tree symbol's species, stage and the month that gives its look ("t-mango-fruit" → Mango, grown, May). */
private fun treeIconLook(id: String): Triple<Species, Stage, Int>? {
    when (id) {
        "t-seed" -> return Triple(Species.NEEM, Stage.SEED, 12)
        "t-sprout" -> return Triple(Species.NEEM, Stage.SPROUT, 12)
        "t-sapling" -> return Triple(Species.NEEM, Stage.SAPLING, 12)
        "t-young" -> return Triple(Species.NEEM, Stage.YOUNG, 12)
    }
    val base = id.removeSuffix("-fruit").removeSuffix("-flower").removeSuffix("-green")
    val kind = ForestKind.entries.firstOrNull { it.id == base } ?: return null
    val month = when {
        id.endsWith("-fruit") -> 5
        id.endsWith("-flower") -> 2
        id.endsWith("-green") -> 12
        kind == ForestKind.GULMOHAR || kind == ForestKind.JASMINE -> 5
        else -> 12
    }
    return Triple(kind.species, Stage.GROWN, month)
}
