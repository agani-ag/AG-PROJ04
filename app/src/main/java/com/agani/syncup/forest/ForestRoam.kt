package com.agani.syncup.forest

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Animals (and the two of you on picnic day) wander about their spot: rest a while, walk to a new spot nearby, rest
 * again. It is worked out from the clock alone, with nothing saved: leg i goes from spot i to spot i + 1, and the
 * spots are picked from a hash of (animal, i). Spots keep clear of the water, the tree trunks and the big things, and
 * a walk that would cut through one goes back by the home spot instead, so nothing walks onto the pond or into a tree.
 * Ranges and speeds are in design units at depth 1; further back, the steps are smaller.
 */

internal enum class Gait {
    /** Feet on the ground: a small bob each step. */
    WALK,

    /** Rabbits, sparrows, frogs: little hops. */
    HOP,

    /** On or in the water: a glide, no bob. */
    SWIM,
}

/**
 * How one kind wanders: within ±[rx], ±[ry] of its spot, at [speed] units a second, one leg (rest, then walk) every
 * [leg] seconds. [wet] kinds may go into the water (the frog to the pond's edge, the turtle in the flood); a [ghost]
 * goes through everything (the tornado).
 */
internal class RoamKind(
    val rx: Float,
    val ry: Float,
    val speed: Float,
    val leg: Float,
    val gait: Gait,
    val wet: Boolean = false,
    val ghost: Boolean = false,
)

/** Who follows whom (a few steps behind, keeping its place beside them), and how many seconds behind. */
internal class RoamFollow(val leader: String, val lag: Float)

internal object ForestRoam {
    val KINDS: Map<String, RoamKind> = mapOf(
        "a-deer" to RoamKind(36f, 12f, 10f, 9f, Gait.WALK),
        "a-doe" to RoamKind(32f, 10f, 10f, 10f, Gait.WALK),
        "a-elephant" to RoamKind(28f, 8f, 6f, 13f, Gait.WALK),
        "a-tiger" to RoamKind(50f, 6f, 12f, 10f, Gait.WALK),
        "a-trex" to RoamKind(40f, 10f, 9f, 11f, Gait.WALK),
        "a-peacock" to RoamKind(26f, 9f, 7f, 9f, Gait.WALK),
        "a-peahen" to RoamKind(24f, 9f, 7f, 9f, Gait.WALK),
        "a-rabbit" to RoamKind(22f, 10f, 18f, 6f, Gait.HOP),
        "a-rabbit2" to RoamKind(22f, 10f, 18f, 7f, Gait.HOP),
        "a-sparrow" to RoamKind(16f, 6f, 14f, 5f, Gait.HOP),
        "a-squirrel" to RoamKind(20f, 6f, 26f, 5f, Gait.HOP),
        "a-mongoose" to RoamKind(44f, 10f, 30f, 7f, Gait.WALK),
        "a-tortoise" to RoamKind(10f, 4f, 1.5f, 16f, Gait.WALK),
        "a-frog" to RoamKind(10f, 4f, 14f, 7f, Gait.HOP, wet = true),
        "a-camel" to RoamKind(30f, 8f, 7f, 12f, Gait.WALK),
        "a-rhino" to RoamKind(24f, 7f, 6f, 12f, Gait.WALK),
        "a-buffalo" to RoamKind(26f, 8f, 6f, 11f, Gait.WALK),
        "a-ox" to RoamKind(24f, 8f, 6f, 11f, Gait.WALK),
        "p-man-walk" to RoamKind(36f, 8f, 11f, 9f, Gait.WALK),
        "p-person-walk" to RoamKind(36f, 8f, 11f, 9f, Gait.WALK),
        "p-man-umbrella" to RoamKind(30f, 8f, 9f, 10f, Gait.WALK),
        "a-turtle" to RoamKind(40f, 14f, 5f, 10f, Gait.SWIM, wet = true),
        "o-tornado" to RoamKind(130f, 12f, 16f, 9f, Gait.SWIM, ghost = true),
    )
    val FOLLOWS: Map<String, RoamFollow> = mapOf(
        "a-fawn" to RoamFollow("a-doe", .7f),
        "a-elephant-calf" to RoamFollow("a-elephant", 1f),
        "a-peachick" to RoamFollow("a-peahen", .5f),
        "p-woman-walk" to RoamFollow("p-man-walk", .6f),
        "p-woman-umbrella" to RoamFollow("p-man-umbrella", .6f),
    )

    /** Ducks paddle about inside the pond. */
    val DUCK = RoamKind(0f, 0f, 4f, 9f, Gait.SWIM)
}

/**
 * Where things can't stand: ellipses (cx, cy, rx, ry), and the water among them. Also the meadow's edges.
 * [inside], when set, is the one ellipse a swimmer stays in instead.
 */
internal class RoamArea(
    private val blocked: FloatArray,
    private val water: BooleanArray,
    val inside: FloatArray? = null,
    private val minY: Float = ForestRules.HORIZON + 14f,
) {
    fun free(x: Float, y: Float, wet: Boolean, ghost: Boolean = false): Boolean {
        inside?.let { e -> return within(e, 0, x, y) }
        if (x < 10f || x > ForestRules.W - 10f || y < minY || y > 700f) return false
        if (ghost) return true
        for (i in water.indices) if (!(wet && water[i]) && within(blocked, i * 4, x, y)) return false
        return true
    }

    /** Clear all the way from (ax, ay) to (bx, by) (checked at a few points along). */
    fun clear(ax: Float, ay: Float, bx: Float, by: Float, wet: Boolean, ghost: Boolean = false): Boolean {
        for (k in 1..4) {
            val f = k / 5f
            if (!free(ax + (bx - ax) * f, ay + (by - ay) * f, wet, ghost)) return false
        }
        return true
    }

    fun withInside(e: FloatArray) = RoamArea(blocked, water, e, minY)

    /** Only in front of [y] (swimmers stay in the flood, which starts there). */
    fun from(y: Float) = RoamArea(blocked, water, inside, max(minY, y))

    private fun within(e: FloatArray, o: Int, x: Float, y: Float): Boolean {
        val dx = (x - e[o]) / e[o + 2]
        val dy = (y - e[o + 1]) / e[o + 3]
        return dx * dx + dy * dy < 1f
    }

    companion object {
        /** The no-go places of a scene: water, trunks, rocks, the mat, the bench … */
        fun of(things: List<ForestThing>): RoamArea {
            val e = ArrayList<Float>()
            val wet = ArrayList<Boolean>()
            fun add(cx: Float, cy: Float, rx: Float, ry: Float, isWater: Boolean = false) {
                e += cx; e += cy; e += max(rx, 1f); e += max(ry, 1f); wet += isWater
            }
            for (th in things) {
                val w = th.w
                val k = w / 100f
                when {
                    th.tree != null -> add(th.x, th.baseY, max(5f, .1f * w), max(3f, .035f * w))
                    th.sprite == "o-pond" || th.sprite == "o-pond-dry" -> add(th.x, th.baseY - 12.6f * k, 52f * k, 17f * k, isWater = true)
                    th.sprite == "o-stream" -> for (s in STREAM) add(th.x + (s[0] - 50f) * k, th.baseY + (s[1] - 96f) * k, s[2] * k, s[3] * k, isWater = true)
                    th.sprite == "o-flood" -> add(th.x, th.baseY - 30f * k, 60f * k, 40f * k, isWater = true)
                    th.sprite == "o-picnic" -> add(th.x, th.baseY - .1f * w, .52f * w, .16f * w)
                    th.sprite == "o-bench" -> add(th.x, th.baseY, .5f * w, .12f * w)
                    th.sprite in SOLID -> add(th.x, th.baseY, .42f * w, .1f * w)
                }
            }
            return RoamArea(e.toFloatArray(), wet.toBooleanArray())
        }

        private val SOLID = setOf("o-rocks", "o-cactus", "o-dry-bush", "o-mushrooms", "o-umbrella-big", "o-volcano")

        /** The stream's water as ellipses in its 100-box (cx, cy, rx, ry), following the creek's bends. */
        private val STREAM = arrayOf(
            floatArrayOf(10f, 84f, 16f, 10f), floatArrayOf(30f, 79f, 14f, 9f), floatArrayOf(50f, 74f, 12f, 8f),
            floatArrayOf(68f, 66f, 11f, 7f), floatArrayOf(84f, 58f, 10f, 6f),
        )
    }
}

/**
 * One wanderer. [update] sets [x], [y], [flip], [moving] and [travelled] for time t. Followers copy their leader's
 * moves [RoamFollow.lag] seconds late, keeping their own place beside it.
 */
internal class Roamer(
    private val homeX: Float,
    private val homeY: Float,
    private val kind: RoamKind,
    private val area: RoamArea,
    private val baseFlip: Boolean,
    seed: Int,
) {
    private val seed = seed * 7919 + 17
    private val reach = ForestRules.depth(homeY.toDouble()).toFloat()
    private val rx = kind.rx * reach
    private val ry = kind.ry * reach
    private val speed = kind.speed * reach
    private val leg = kind.leg * (.85f + .3f * roamHash(seed, 0, 99))
    private val start = roamHash(seed, 0, 98) * leg
    private val homeFree = area.free(homeX, homeY, kind.wet, kind.ghost)

    var x = homeX
    var y = homeY
    var flip = baseFlip
    var moving = false

    /** Distance walked on this leg so far (for the steps' bob). */
    var travelled = 0f

    // The three spots around the current leg, cached.
    private var cachedLeg = Int.MIN_VALUE
    private val sx = FloatArray(3)
    private val sy = FloatArray(3)

    fun update(t: Float) {
        if (!homeFree) return
        val tt = t + start
        val i = kotlin.math.floor(tt / leg).toInt()
        val u = tt - i * leg
        spots(i)
        // This leg: rest, then walk spot i → spot i + 1 (straight, or back by home when that's not clear).
        val len = pathLength(1, 2)
        val walk = min(len / max(speed, .01f), leg * .8f)
        val rest = leg - walk
        if (u < rest || len < .5f) {
            x = sx[1]; y = sy[1]; moving = false; travelled = 0f
            flip = facing(0, 1)
        } else {
            val p = smooth((u - rest) / walk)
            val s = p * len
            pointAlong(1, 2, s)
            moving = true
            travelled = s
        }
    }

    /** Copies [leader]'s pose from [lag] seconds ago, shifted by ([dx], [dy]). Leaves the leader at [t]. */
    fun follow(leader: Roamer, t: Float, lag: Float, dx: Float, dy: Float) {
        leader.update(t - lag)
        x = leader.x + dx
        y = leader.y + dy
        flip = leader.flip
        moving = leader.moving
        travelled = leader.travelled
        leader.update(t)
    }

    private fun spots(i: Int) {
        if (i == cachedLeg) return
        cachedLeg = i
        for (k in 0..2) spot(i - 1 + k, k)
    }

    /** Spot [i]: a clear place near home, reachable from home in a straight line (home itself if none turns up). */
    private fun spot(i: Int, into: Int) {
        val inside = area.inside
        for (tryN in 0 until 6) {
            val a = roamHash(seed, i, tryN * 2) * 2f - 1f
            val b = roamHash(seed, i, tryN * 2 + 1) * 2f - 1f
            val cx: Float
            val cy: Float
            if (inside != null) {
                // Anywhere in the pond, a little in from its edge.
                val r = sqrt(abs(a)) * .82f
                val ang = b * Math.PI.toFloat()
                cx = inside[0] + r * inside[2] * kotlin.math.cos(ang)
                cy = inside[1] + r * inside[3] * kotlin.math.sin(ang)
            } else {
                cx = homeX + a * rx
                cy = homeY + b * ry
            }
            if (area.free(cx, cy, kind.wet, kind.ghost) && (inside != null || area.clear(homeX, homeY, cx, cy, kind.wet, kind.ghost))) {
                sx[into] = cx; sy[into] = cy
                return
            }
        }
        sx[into] = homeX; sy[into] = homeY
    }

    private fun straight(a: Int, b: Int): Boolean = area.inside != null || area.clear(sx[a], sy[a], sx[b], sy[b], kind.wet, kind.ghost)

    private fun pathLength(a: Int, b: Int): Float =
        if (straight(a, b)) hypot(sx[b] - sx[a], sy[b] - sy[a])
        else hypot(homeX - sx[a], homeY - sy[a]) + hypot(sx[b] - homeX, sy[b] - homeY)

    /** Sets [x], [y] and [flip] [s] units along the walk from spot [a] to spot [b]. */
    private fun pointAlong(a: Int, b: Int, s: Float) {
        val ax = sx[a]
        val ay = sy[a]
        val bx = sx[b]
        val by = sy[b]
        if (straight(a, b)) {
            val l = max(hypot(bx - ax, by - ay), .01f)
            x = ax + (bx - ax) * s / l
            y = ay + (by - ay) * s / l
            flip = faces(bx - ax, flip)
            return
        }
        val l1 = hypot(homeX - ax, homeY - ay)
        if (s <= l1) {
            val f = if (l1 > 0f) s / l1 else 1f
            x = ax + (homeX - ax) * f
            y = ay + (homeY - ay) * f
            flip = faces(homeX - ax, flip)
        } else {
            val l2 = max(hypot(bx - homeX, by - homeY), .01f)
            val f = min(1f, (s - l1) / l2)
            x = homeX + (bx - homeX) * f
            y = homeY + (by - homeY) * f
            flip = faces(bx - homeX, flip)
        }
    }

    /** Which way it faced at the end of the walk from spot [a] to spot [b] (its own way if that walk was straight up or down). */
    private fun facing(a: Int, b: Int): Boolean {
        val dx = if (straight(a, b)) sx[b] - sx[a] else sx[b] - homeX
        return faces(dx, baseFlip)
    }

    /** The art faces right; a flip faces left. Nearly straight up or down keeps [current]. */
    private fun faces(dx: Float, current: Boolean): Boolean = when {
        dx > 1f -> false
        dx < -1f -> true
        else -> current
    }

    private fun smooth(f: Float): Float {
        val c = f.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}

/** A steady 0 ≤ h < 1 from three ints. */
internal fun roamHash(a: Int, b: Int, c: Int): Float {
    var h = a * 374761393 + b * 668265263 + c * 1274126177
    h = (h xor (h ushr 13)) * 1103515245
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216f
}
