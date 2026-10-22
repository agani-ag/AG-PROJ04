package com.agani.syncup.forest

import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/*
 * The Forest (v2) rules: a monthly cycle worked out from the phone's date and time; nothing is saved or synced.
 * A port of docs/mockup/forest-art/forest_v2_page.py (the approved mockup). The seeded picks use Python's own random
 * (MT19937), so a date gets the same season and tree kind as in the mockup.
 *
 * Design space: 360 × 734 with the horizon at y = 318; the scene scales it to the screen.
 * - 100-box sprites ("a-…", "o-…", "p-…"): the box is w × w and its point (50, 96) sits on (x, baseY).
 * - Trees (200 × 240 box, w × 1.2w): the foot (100, 226) sits on (x, baseY). This includes "o-dead-tree".
 */

/** Where a day falls in the month. */
enum class ForestPhase(val title: String) { GREEN("Green"), PICNIC("Picnic day"), DRY("Dry spell"), DESERT("Desert"), STORM("Storm") }

/** Each day's season, picked at random from the date. */
enum class ForestSeason(val title: String, val icon: String) {
    SPRING("Spring", "🌸"), SUMMER("Summer", "☀️"), MONSOON("Monsoon", "🌧"), AUTUMN("Autumn", "🍂"), WINTER("Winter", "🌫"),
}

/** Time of day, by the real sunrise and sunset. */
enum class ForestTime { DAWN, DAY, DUSK, NIGHT }

/** Points on a leafy tree where animals sit: the crown's top, the fork, and the crown's lower edge on each side. */
enum class ForestAnchor { TOP, FORK, LEFT, RIGHT }

/**
 * The tree kinds, in the mockup's order. [widthK] is the grown width next to a Neem. [anch] holds the anchors
 * (x, y pairs in the tree's 200 × 240 box, in [ForestAnchor] order). Only the leafy kinds have them and host animals.
 */
enum class ForestKind(val id: String, val title: String, val species: Species, val widthK: Double, private val anch: DoubleArray?) {
    NEEM("t-neem", "Neem", Species.NEEM, 1.0, doubleArrayOf(98.0, 36.0, 104.0, 146.0, 42.0, 124.0, 164.0, 116.0)),
    MANGO("t-mango", "Mango", Species.MANGO, 1.05, doubleArrayOf(110.0, 44.0, 100.0, 168.0, 32.0, 142.0, 170.0, 142.0)),
    GULMOHAR("t-gulmohar", "Gulmohar", Species.GULMOHAR, 1.1, doubleArrayOf(100.0, 66.0, 100.0, 128.0, 38.0, 124.0, 162.0, 124.0)),
    PEEPAL("t-peepal", "Peepal", Species.PEEPAL, 1.0, doubleArrayOf(100.0, 32.0, 100.0, 166.0, 38.0, 124.0, 164.0, 124.0)),
    COCONUT("t-coconut", "Coconut", Species.COCONUT, .82, null),
    BAMBOO("t-bamboo", "Bamboo", Species.BAMBOO, .7, null),
    JASMINE("t-jasmine", "Jasmine", Species.JASMINE, .72, null),
    BANYAN("t-banyan", "Banyan", Species.BANYAN, 1.25, doubleArrayOf(110.0, 46.0, 100.0, 140.0, 54.0, 140.0, 156.0, 140.0)),
    ;

    /** Animals can sit in it (never coconut, bamboo or jasmine). */
    val leafy: Boolean get() = anch != null

    /** An anchor in the tree's own 200 × 240 box, or null for a kind that hosts nothing. */
    fun anchorBox(a: ForestAnchor): Pair<Double, Double>? = anch?.let { it[a.ordinal * 2] to it[a.ordinal * 2 + 1] }
}

/** A calendar date. [weekday] is ISO: 1 = Monday … 7 = Sunday. */
data class ForestDate(val year: Int, val month: Int, val day: Int) {
    val epochDay: Long get() = ForestRules.epochDay(year, month, day)
    val weekday: Int get() = Math.floorMod(epochDay + 3, 7L).toInt() + 1
    val sunday: Boolean get() = weekday == 7

    /** "Thu 22 Oct". */
    val text: String get() = "${ForestRules.WEEKDAYS[weekday - 1]} $day ${ForestRules.MONTHS[month - 1].take(3)}"

    fun plusDays(n: Int): ForestDate = ForestRules.dateOf(epochDay + n)
}

/** One planted tree. [w] is its drawn width ([dead] trees are drawn at 0.9 of a grown tree). */
data class ForestTree(
    /** The forest-day that planted it (1 … month length − 3). */
    val day: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val kind: ForestKind,
    val stage: Stage,
    /** The mockup's symbol: "t-neem", "t-mango-fruit", "t-gulmohar-green", "t-seed" … or "o-dead-tree". */
    val sprite: String,
    /** The month to pass to TreeImages.get so the look matches [sprite] (fruit, flowers, bloom or plain). */
    val artMonth: Int,
    val variant: Int,
    /** The forest-day's own tree, growing seed → grown through the day. Never hosts animals. */
    val growing: Boolean,
    /** Dry spell: brown leaves (TreeImages resting = true). */
    val dry: Boolean,
    /** Desert / storm: draw the "o-dead-tree" sprite instead of the tree. */
    val dead: Boolean,
)

/** One thing placed in the scene, already in drawing order (back to front). */
data class ForestThing(
    /** The art's id: "a-elephant", "o-pond", "p-man-sit", "o-dead-tree", "t-neem" … */
    val sprite: String,
    val x: Float,
    val baseY: Float,
    val w: Float,
    /** Mirrored around x (faces left). */
    val flip: Boolean,
    /** The depth key it was sorted by (usually where it touches the ground). */
    val key: Float,
    val kind: Kind,
    /** A soft shadow ellipse at (x, baseY − 1) with these radii (0 = none), opacity about 0.18. */
    val shadowRx: Float = 0f,
    val shadowRy: Float = 0f,
    /** Set for [Kind.TREE]: draw it with TreeImages (or the dead-tree sprite). */
    val tree: ForestTree? = null,
) {
    enum class Kind {
        /** Stands on the ground, with a shadow. */
        GROUND,

        /** No shadow: lies flat on the ground (pond, stream, puddles, cracks…), or the two of you sitting on the mat. */
        FLAT,

        /** Sits or hangs in a host tree. */
        IN_TREE,

        /** A planted tree ([tree] is set). */
        TREE,
    }
}

/** The real Moon. [age] in days since new; [lit] 0–1; the next full / new moon as instants and local dates. */
data class ForestMoon(
    val age: Double,
    val lit: Double,
    val name: String,
    val waxing: Boolean,
    val nextFullMs: Long,
    val nextNewMs: Long,
    val nextFull: ForestDate,
    val nextNew: ForestDate,
)

/**
 * The install day's catch-up: forest-day [day] of [of] (today's date), [minutesPerDay] each (at most 60);
 * [dayProgress] 0–1 through this forest-day, [progress] 0–1 overall, all caught up at [endsMinute] (local minutes).
 */
data class ForestCatchUp(
    val day: Int,
    val of: Int,
    val minutesPerDay: Double,
    val dayProgress: Double,
    val progress: Double,
    val endsMinute: Double,
)

/** The two of you on picnic day: walk in at 9 AM, sit on the mat, walk away by 5 PM. */
enum class ForestPicnic { NONE, COMING, ARRIVING, SITTING, LEAVING, LEFT }

/** Scene touches outside the depth-sorted list (sky, weather, flyers), as the mockup draws them. */
enum class ForestExtra {
    SUN, MOON, STARS, CLOUDS, RAIN_CLOUDS, STORM_CLOUDS, LIGHTNING,
    /** The far tree line with mist (green and picnic days). */
    TREE_LINE,
    DUNES, DESERT_HAZE, GRASS,
    /** Sunday: the dragon circles the sky by day and rests at night. */
    DRAGON_FLYING, DRAGON_RESTING,
    WILDFLOWERS, BUTTERFLIES, DRAGONFLY, BIRDS, FIREFLIES, RAINBOW,
    LIGHT_RAIN, HEAVY_RAIN, AUTUMN_LEAVES, SPRING_PETALS, DRY_LEAVES, WINTER_MIST,

    /** The desert day: a volcano erupting on the far hills. */
    VOLCANO,

    /** The storm day: flood water over the meadow ([ForestNow.flood] says how high). */
    FLOOD,
}

/** A line on a card: an emoji and its text. */
data class ForestLine(val icon: String, val text: String)

/** One day on the This-month page. [also] = bonus arrivals that come on this day in a short month. */
data class ForestMonthDay(
    val day: Int,
    val date: ForestDate,
    val phase: ForestPhase,
    val icon: String,
    val title: String,
    val also: List<String>,
    val sunday: Boolean,
    val today: Boolean,
    val past: Boolean,
)

/** A "Coming up" row: "Day 23" · "A fawn with its mother". */
data class ForestComing(val icon: String, val label: String, val text: String)

/** Everything the scene and the screens need for one moment. */
data class ForestNow(
    val epochMs: Long,
    /** The real date in the zone. */
    val date: ForestDate,
    /** Real minutes after local midnight (the sky always follows real time). */
    val minute: Double,
    /** The forest-day shown: the date, except while catching up on the install day. */
    val day: Int,
    val monthLength: Int,
    val phase: ForestPhase,
    val season: ForestSeason,
    val time: ForestTime,
    /** Minutes after local midnight, at Jolarpettai. */
    val sunrise: Double,
    val sunset: Double,
    val moon: ForestMoon,
    val catchUp: ForestCatchUp?,
    /** The forest-day's tree (null on dry, desert and storm days). */
    val todayTree: ForestTree?,
    /** How far today's tree is through its growing (< 0 before sunrise, ≥ 1 grown). */
    val stageFraction: Double,
    /** Every drawn tree in planting order (fallen ones in the desert are left out). */
    val trees: List<ForestTree>,
    /** Grown leafy trees that host the tree animals, biggest first. */
    val hosts: List<ForestTree>,
    /** The arrival numbers (1–27) in the forest now. */
    val arrived: Set<Int>,
    /** Trees, animals and things, back to front. */
    val things: List<ForestThing>,
    val picnic: ForestPicnic,
    /** The storm's evening: green shoots are up. */
    val eveningGreen: Boolean,
    /** The storm day's flood, 0 (none) → 1 (high water): it rises after sunrise and drains by the evening's green. */
    val flood: Double,
    val extras: Set<ForestExtra>,
) {
    val sunday: Boolean get() = date.sunday
    val catchingUp: Boolean get() = catchUp != null
    val night: Boolean get() = time == ForestTime.NIGHT

    /** Green or picnic day (trees alive, animals around). */
    val leafy: Boolean get() = phase == ForestPhase.GREEN || phase == ForestPhase.PICNIC
    val todayKind: ForestKind? get() = todayTree?.kind
    val todayStage: Stage? get() = todayTree?.stage

    /** When today's tree is grown: half an hour before sunset. */
    val grownBy: Double get() = sunset - 30

    /** The sun's path: 0 at sunrise (left) → 1 at sunset (right). */
    val sunFraction: Double get() = ((minute - sunrise) / (sunset - sunrise)).coerceIn(0.0, 1.0)

    /** Trees in the forest (on dry / desert / storm days, the month's full count). */
    val treeCount: Int get() = if (leafy) min(day, monthLength - 3) else monthLength - 3

    /** The date of the forest-day shown. */
    val forestDate: ForestDate get() = ForestDate(date.year, date.month, day)

    /** Where the flood water starts (the meadow behind it is dry): the back of the meadow at high water. */
    val floodLine: Float get() = ForestRules.floodLine(flood)
}

object ForestRules {
    // ------------------------------------------------------------------ constants
    const val W = 360f
    const val H = 734f
    const val HORIZON = 318f

    /** A grown tree's width at depth 1. */
    const val TREE_W = 120.0

    /** Jolarpettai (pincode 635851). */
    const val LAT = 12.57
    const val LON = 78.58
    val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    const val SYNODIC = 29.530588853

    /** A known new moon: 6 Jan 2000, 18:14 UTC. */
    private val NEW_MOON_MS = epochDay(2000, 1, 6) * DAY_MS + (18 * 60 + 14) * 60_000L

    val MOON_PHASES = listOf("New moon", "Waxing crescent", "First quarter", "Waxing gibbous", "Full moon", "Waning gibbous", "Last quarter", "Waning crescent")
    internal val WEEKDAYS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    internal val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** Picnic day: they walk in at 9 AM and walk away by 5 PM (half an hour of walking at each end). */
    const val PICNIC_FROM = 9 * 60
    const val PICNIC_TO = 17 * 60
    private const val PICNIC_WALK = 30

    /** What each green day brings. Days 25–27 are bonus days (see [arrivalsOn]). */
    val ARRIVALS: Map<Int, String> = mapOf(
        1 to "Fresh green after the storm · a rainbow",
        2 to "Wildflowers and butterflies", 3 to "Sparrows and bulbuls", 4 to "A squirrel", 5 to "Rabbits",
        6 to "Fireflies and an owl at night", 7 to "A pond, with frogs and lotus", 8 to "Dragonflies over the pond",
        9 to "Mushrooms, rocks and a stone path", 10 to "A peacock", 11 to "A bird's nest with eggs", 12 to "Ducks on the pond",
        13 to "The eggs hatch", 14 to "Spotted deer", 15 to "A tortoise by the pond", 16 to "Monkeys in the big tree",
        17 to "A beehive", 18 to "A bench and a swing", 19 to "A kingfisher", 20 to "Parrots", 21 to "A stream and a little bridge",
        22 to "A mongoose", 23 to "A fawn with its mother", 24 to "Elephants — a mother and calf",
        25 to "Hornbills", 26 to "A peahen and her chicks", 27 to "A tiger, far away at dusk",
    )
    val ARRIVAL_ICONS: Map<Int, String> = mapOf(
        1 to "🌈", 2 to "🦋", 3 to "🐦", 4 to "🐿", 5 to "🐇", 6 to "🦉", 7 to "🐸", 8 to "🪲", 9 to "🍄", 10 to "🦚", 11 to "🪺",
        12 to "🦆", 13 to "🐣", 14 to "🦌", 15 to "🐢", 16 to "🐒", 17 to "🐝", 18 to "🪑", 19 to "🐦", 20 to "🦜", 21 to "🌉",
        22 to "🦡", 23 to "🦌", 24 to "🐘", 25 to "🐦", 26 to "🦚", 27 to "🐅",
    )

    /** The short "what came" phrase for the Tools tile and the Home row. */
    private val CAME: Map<Int, String> = mapOf(
        1 to "a rainbow came", 2 to "butterflies came", 3 to "sparrows came", 4 to "a squirrel came", 5 to "rabbits came",
        6 to "an owl came", 7 to "a pond appeared", 8 to "dragonflies came", 9 to "mushrooms came up", 10 to "a peacock came",
        11 to "a bird built a nest", 12 to "ducks came", 13 to "the eggs hatched", 14 to "spotted deer came", 15 to "a tortoise came",
        16 to "monkeys came", 17 to "bees built a hive", 18 to "a swing went up", 19 to "a kingfisher came", 20 to "parrots came",
        21 to "a stream appeared", 22 to "a mongoose came", 23 to "a fawn came", 24 to "elephants came", 25 to "hornbills came",
        26 to "a peahen came", 27 to "a tiger was seen",
    )
    private val PHASE_ICONS = mapOf(ForestPhase.PICNIC to "🧺", ForestPhase.DRY to "🍂", ForestPhase.DESERT to "🏜", ForestPhase.STORM to "⛈")

    // Tree spots (x, ground y) in the order the days fill them: trees ring an open clearing. 28 spots, one per tree of a
    // 31-day month (the 28th sits in the back rows, clear of the clearing).
    private val SLOTS = arrayOf(
        62 to 432, 300 to 428, 122 to 398, 242 to 396, 20 to 474, 346 to 466, 182 to 382, 18 to 540,
        350 to 532, 88 to 372, 290 to 370, 40 to 392, 330 to 394, 150 to 360, 220 to 358, 12 to 356,
        356 to 354, 112 to 344, 256 to 342, 60 to 340, 310 to 338, 186 to 334, -4 to 606, 140 to 328,
        232 to 326, 30 to 326, 336 to 324, 166 to 352,
    )

    /** How big each thing is next to a grown tree standing at the same distance. */
    private val SIZE: Map<String, Double> = mapOf(
        "a-elephant" to .95, "a-elephant-calf" to .5, "a-deer" to .5, "a-doe" to .44, "a-fawn" to .27, "a-trex" to 1.05, "a-tiger" to .6,
        "a-peacock" to .5, "a-peacock-dance" to .56, "a-peahen" to .3, "a-peachick" to .11, "a-rabbit" to .19, "a-rabbit2" to .18,
        "a-squirrel" to .16, "a-mongoose" to .25, "a-tortoise" to .19, "a-frog" to .11, "a-duck" to .19, "a-sparrow" to .1,
        "a-kingfisher" to .2, "p-person-sit" to .34, "p-person-walk" to .38, "p-man-sit" to .34, "p-woman-sit" to .33,
        "p-man-walk" to .38, "p-woman-walk" to .37, "o-picnic" to .72, "o-bench" to .46, "o-rocks" to .42, "o-mushrooms" to .2,
        "o-dry-bush" to .32, "o-cactus" to .3, "o-puddle" to .55, "o-shoots" to .22, "o-pond" to 1.3, "o-pond-dry" to 1.0,
        "o-stones" to .95, "o-stream" to 1.95, "o-cracks" to 1.9,
        // The month's end: the dry spell's tornado and cattle, the desert's camels and rhinos, the storm's sea creatures,
        // and the rainy picnic's umbrellas.
        "o-tornado" to 1.2, "a-buffalo" to .66, "a-ox" to .6, "a-camel" to .8, "a-rhino" to .64,
        "a-whale" to 1.5, "a-dolphin" to .52, "a-fish" to .34, "a-fish2" to .34, "a-turtle" to .3, "a-octopus" to .3,
        "a-jellyfish" to .22, "a-crab" to .3, "p-man-umbrella" to .44, "p-woman-umbrella" to .43,
    )

    /** Sea creatures: they swim in the flood, so they have no shadow, and the scene sets them on the water. */
    val SWIMMERS = setOf("a-whale", "a-dolphin", "a-fish", "a-fish2", "a-turtle", "a-octopus", "a-jellyfish")
    private val FLAT = setOf("o-puddle", "o-pond", "o-pond-dry", "o-stones", "o-stream", "o-cracks", "o-shoots") + SWIMMERS

    // ------------------------------------------------------------------ the moment
    /**
     * The forest at [epochMs]. [installMs] is the app's install time (PackageInfo.firstInstallTime): on that date
     * the month so far plays out from day 1, one forest-day an hour (faster if needed to finish by midnight).
     * [zone] gives the date and the clock; the sun is Jolarpettai's, in that zone's time.
     */
    fun at(epochMs: Long, installMs: Long, zone: TimeZone = IST): ForestNow {
        val off = zone.getOffset(epochMs)
        val local = epochMs + off
        val eDay = Math.floorDiv(local, DAY_MS)
        val date = dateOf(eDay)
        val minute = Math.floorMod(local, DAY_MS) / 60_000.0
        val n = monthLength(date.year, date.month)
        val (rise, set) = sunTimes(date.year, date.month, date.day, off / 3_600_000.0)
        val ready = set - 30

        // The install day's catch-up.
        var day = date.day
        var frac = (minute - rise) / max(1.0, ready - rise)
        var dayMinute = minute
        var catchUp: ForestCatchUp? = null
        val installDay = Math.floorDiv(installMs + zone.getOffset(installMs), DAY_MS)
        if (installDay == eDay && date.day > 1) {
            val midnight = (eDay + 1) * DAY_MS - off
            val perDay = min(HOUR_MS, (midnight - installMs).toDouble() / (date.day - 1))
            val f = max(0L, epochMs - installMs) / perDay
            val shown = 1 + floor(f).toInt()
            if (shown < date.day) {
                val p = f - floor(f)
                day = shown
                frac = p
                dayMinute = rise + p * (ready - rise)
                val ends = installMs + perDay * (date.day - 1)
                catchUp = ForestCatchUp(shown, date.day, perDay / 60_000.0, p, f / (date.day - 1), (ends + off - eDay * DAY_MS) / 60_000.0)
            }
        }

        val phase = phaseOf(day, n)
        val forestDate = ForestDate(date.year, date.month, day)
        val season = seasonOf(forestDate)
        val time = when {
            minute < rise - 40 || minute > set + 40 -> ForestTime.NIGHT
            minute < rise + 50 -> ForestTime.DAWN
            minute > set - 50 -> ForestTime.DUSK
            else -> ForestTime.DAY
        }
        val night = time == ForestTime.NIGHT
        val leafy = phase == ForestPhase.GREEN || phase == ForestPhase.PICNIC
        val have = if (leafy) arrived(day, n) else emptySet()
        val eveningGreen = phase == ForestPhase.STORM && minute > set - 50
        val flood = if (phase == ForestPhase.STORM) floodAt(minute, rise, set) else 0.0
        val picnic = if (phase != ForestPhase.PICNIC) ForestPicnic.NONE else when {
            dayMinute < PICNIC_FROM -> ForestPicnic.COMING
            dayMinute < PICNIC_FROM + PICNIC_WALK -> ForestPicnic.ARRIVING
            dayMinute < PICNIC_TO - PICNIC_WALK -> ForestPicnic.SITTING
            dayMinute < PICNIC_TO -> ForestPicnic.LEAVING
            else -> ForestPicnic.LEFT
        }

        val b = Scene()
        // Cracked soil lies under everything on the dry day (drawn before the list in the mockup).
        if (phase == ForestPhase.DRY) {
            b.raw("o-cracks", 120.0, 600.0, 220.0); b.raw("o-cracks", 280.0, 680.0, 200.0); b.raw("o-cracks", 180.0, 520.0, 200.0)
        }

        // Trees: one per green day; dry → brown, desert → dead (every third has fallen), storm → dead in the rain.
        val last = if (leafy) min(day, n - 3) else n - 3
        val trees = ArrayList<ForestTree>()
        val hosts = ArrayList<ForestTree>()
        var todayTree: ForestTree? = null
        for (k in 1..last) {
            val (sx, sy) = SLOTS[(k - 1) % SLOTS.size]
            val td = ForestDate(date.year, date.month, k)
            val kind = kindOf(td)
            val w = treeWidth(kind, sy.toDouble())
            val variant = daySeed(td).toInt()
            val tree = if (phase == ForestPhase.DESERT || phase == ForestPhase.STORM) {
                if (k % 3 == 0) continue
                ForestTree(k, sx.toFloat(), sy.toFloat(), (w * .9).toFloat(), kind, Stage.GROWN, "o-dead-tree", artMonth(kind, season), variant,
                    growing = false, dry = false, dead = true)
            } else {
                val growing = k == day
                val sprite = treeSprite(kind, season, if (growing) frac else null)
                ForestTree(k, sx.toFloat(), sy.toFloat(), w.toFloat(), kind, stageOf(sprite), sprite, artMonth(kind, season), variant,
                    growing = growing, dry = phase == ForestPhase.DRY, dead = false)
            }
            trees += tree
            if (tree.growing) todayTree = tree
            b.items += ForestThing(tree.sprite, tree.x, tree.y, tree.w, false, tree.y, ForestThing.Kind.TREE, tree = tree)
            if (!tree.growing && !tree.dead && kind.leafy && leafy) hosts += tree
        }
        // The biggest (nearest) leafy trees host the animals that live up in the trees.
        hosts.sortByDescending { it.w }
        b.hosts = hosts

        // ---- ground things (they arrive by the day)
        if (21 in have) b.at("o-stream", 96, 474, key = 400.0)
        if (9 in have) {
            b.at("o-rocks", 112, 646); b.at("o-mushrooms", 36, 500); b.at("o-stones", 190, 700, key = 560.0)
        }
        if (7 in have) {
            b.at("o-pond", 300, 618, key = 560.0)
            b.at("a-frog", 238, 620)
        }
        if (12 in have) {
            b.at("a-duck", 300, 598, key = 598.0); b.at("a-duck", 332, 606, flip = true, key = 606.0)
        }
        if (19 in have) b.at("a-kingfisher", 350, 590, flip = true)
        if (15 in have) b.at("a-tortoise", 226, 632)
        if (3 in have) {
            b.at("a-sparrow", 104, 562)
            b.up(2, ForestAnchor.TOP, "a-bulbul", .12)
        }
        if (4 in have) b.at("a-squirrel", 74, 488)
        if (5 in have) {
            b.at("a-rabbit", 122, 604); b.at("a-rabbit2", 148, 616, flip = true)
        }
        if (10 in have) b.at(if (season == ForestSeason.MONSOON) "a-peacock-dance" else "a-peacock", 248, 540)
        if (26 in have) {
            b.at("a-peahen", 204, 556); b.at("a-peachick", 222, 562); b.at("a-peachick", 232, 566)
        }
        if (14 in have) {
            b.at("a-deer", 130, 494); b.at("a-doe", 172, 488, flip = true)
        }
        if (23 in have) b.at("a-fawn", 190, 500, flip = true)
        if (22 in have) b.at("a-mongoose", 92, 574, flip = true)
        if (24 in have) {
            b.at("a-elephant", 232, 452, flip = true); b.at("a-elephant-calf", 186, 458, flip = true)
        }
        if (27 in have && time == ForestTime.DUSK) b.at("a-tiger", 196, 352)
        if (18 in have) b.at("o-bench", 300, 540)
        // ---- up in the trees
        if (11 in have) b.up(0, ForestAnchor.FORK, if (13 in have) "a-nest-chicks" else "a-nest-eggs", .16, dy = .01)
        if (16 in have) {
            val banyan = hosts.indexOfFirst { it.kind == ForestKind.BANYAN }
            val i = if (banyan >= 0) banyan else 1
            b.up(i, ForestAnchor.LEFT, "a-monkey", .2); b.up(i, ForestAnchor.RIGHT, "a-monkey", .17, flip = true)
        }
        if (17 in have) {
            b.up(2, ForestAnchor.RIGHT, "o-beehive", .13)
            b.host(2)?.let { t ->
                val (x, y) = anchor(t, ForestAnchor.RIGHT)
                val tw = t.w.toDouble()
                b.put("a-bees", x + .08 * tw, y + .2 * tw, .16 * tw, false, t.y + .5f, ForestThing.Kind.IN_TREE)
            }
        }
        if (20 in have) {
            b.up(0, ForestAnchor.TOP, "a-parrot", .13); b.up(1, ForestAnchor.TOP, "a-parrot", .12, flip = true)
        }
        if (25 in have) b.up(1, ForestAnchor.LEFT, "a-hornbill", .2, dy = -.06)
        if (18 in have && hosts.isNotEmpty()) {
            // The nearest tree carries the swing, on the branch that faces the meadow, so it never hangs off-screen.
            val t = hosts.maxByOrNull { it.y }!!
            val (ax, y) = anchor(t, if (t.x > W / 2) ForestAnchor.LEFT else ForestAnchor.RIGHT)
            val x = ax.coerceIn(24.0, 336.0)
            val sw = max(20.0, (t.y - 4 - y) / .96)
            b.put("o-swing", x, y + sw * .96, sw, false, t.y + .02f, ForestThing.Kind.IN_TREE)
        }
        if (night && leafy && 6 in have) b.up(0, ForestAnchor.FORK, "a-owl", .2, dy = -.02)
        // ---- the month's end
        if (phase == ForestPhase.DRY) {
            b.at("o-pond-dry", 300, 618, key = 560.0); b.at("o-dry-bush", 44, 600); b.at("a-deer", 346, 466, flip = true)
            // A tornado spins far out on the dry meadow; a buffalo and an ox come to the last of the pond.
            b.at("o-tornado", 250, 372)
            b.at("a-buffalo", 172, 600); b.at("a-ox", 276, 540, flip = true)
        }
        if (phase == ForestPhase.DESERT) {
            b.at("o-cactus", 72, 600); b.at("o-dry-bush", 296, 612); b.at("o-cactus", 330, 540); b.at("o-dry-bush", 150, 574)
            // Camels cross the sand and rhinos wander between the dead trees (the volcano is in the extras).
            b.at("a-camel", 226, 470, flip = true); b.at("a-camel", 270, 480, flip = true)
            b.at("a-rhino", 118, 528); b.at("a-rhino", 236, 576, flip = true)
        }
        if (phase == ForestPhase.STORM) {
            // The puddles are under the water while it floods.
            if (flood < .2) {
                b.at("o-puddle", 110, 600); b.at("o-puddle", 270, 612); b.at("o-puddle", 200, 552)
            }
            if (flood >= .3) {
                // The flood brings the sea in: each one is there while the water reaches past its spot, so they come in
                // front first as it rises and leave back first (the whale) as it drains.
                val line = floodLine(flood) + 20.0
                fun sea(sprite: String, x: Int, y: Int, flip: Boolean = false) {
                    if (y >= line) b.at(sprite, x, y, flip)
                }
                sea("a-whale", 200, 432); sea("a-dolphin", 92, 508); sea("a-dolphin", 134, 522)
                sea("a-jellyfish", 300, 452); sea("a-jellyfish", 54, 470, flip = true); sea("a-octopus", 318, 532, flip = true)
                sea("a-fish", 246, 556); sea("a-fish2", 64, 584); sea("a-turtle", 186, 600, flip = true)
            }
            // Crabs come up on the wet land as the water goes down past them.
            if (minute >= FLOOD_DRAIN && !night && floodLine(flood) > 506f) {
                b.at("a-crab", 96, 476); b.at("a-crab", 258, 492, flip = true)
            }
            if (eveningGreen) {
                val rnd = PyRandom(31)
                repeat(26) {
                    val x = rnd.uniform(10.0, 350.0)
                    val y = rnd.uniform(380.0, 640.0)
                    b.at("o-shoots", x, y, k = rnd.uniform(.8, 1.3))
                }
            }
        }
        if (phase == ForestPhase.GREEN && day == 1 && (time == ForestTime.DAWN || time == ForestTime.DAY) && catchUp == null) {
            b.at("o-puddle", 262, 606); b.at("o-shoots", 120, 598)
        }
        if (season == ForestSeason.MONSOON && leafy && !night) b.at("o-puddle", 80, 606)
        // ---- every Sunday: a friendly T. rex in the meadow (the dragon is in [extras])
        if (date.sunday) b.at("a-trex", 118, 548)
        // ---- your picnic (the 4th-last day): the two of you on a mat in the meadow
        // A rainy (monsoon) picnic: a big umbrella over the mat, and an umbrella each to walk with.
        val rainy = season == ForestSeason.MONSOON && !night
        when (picnic) {
            ForestPicnic.SITTING -> {
                val w = SIZE.getValue("o-picnic") * TREE_W * depth(552.0) * 1.15
                // The big umbrella, as wide as the mat, stands just behind the two of you.
                if (rainy) b.put("o-umbrella-big", 156 + .02 * w, 552 - .12 * w, w, false, 551.9f, ForestThing.Kind.FLAT)
                b.put("o-picnic", 156.0, 552.0, w, false, 552f, ForestThing.Kind.GROUND, w * .45, w * .05)
                b.put("p-man-sit", 156 - .1 * w, 552 - .1 * w, .48 * w, false, 552f, ForestThing.Kind.FLAT)
                b.put("p-woman-sit", 156 + .14 * w, 552 - .1 * w, .48 * w, false, 552f, ForestThing.Kind.FLAT)
            }
            // Walking in or out: in the open meadow, above where the Today card sits.
            ForestPicnic.ARRIVING, ForestPicnic.LEAVING -> {
                if (rainy) {
                    b.at("p-man-umbrella", 140, 540); b.at("p-woman-umbrella", 166, 546)
                } else {
                    b.at("p-man-walk", 140, 540); b.at("p-woman-walk", 166, 546)
                }
            }
            else -> Unit
        }

        val things = b.sorted()

        val extras = HashSet<ForestExtra>()
        val green = phase == ForestPhase.GREEN
        if (!night && phase != ForestPhase.STORM) extras += ForestExtra.SUN
        if (night) extras += ForestExtra.STARS
        if (night && phase != ForestPhase.STORM) extras += ForestExtra.MOON
        if (phase == ForestPhase.STORM) extras += listOf(ForestExtra.STORM_CLOUDS, ForestExtra.LIGHTNING, ForestExtra.HEAVY_RAIN)
        if (time == ForestTime.DAY && green && season in setOf(ForestSeason.SPRING, ForestSeason.WINTER, ForestSeason.AUTUMN)) extras += ForestExtra.CLOUDS
        if (time == ForestTime.DAY && green && season == ForestSeason.MONSOON) extras += ForestExtra.RAIN_CLOUDS
        if (leafy) extras += listOf(ForestExtra.TREE_LINE, ForestExtra.GRASS)
        if (phase == ForestPhase.DESERT) extras += listOf(ForestExtra.DUNES, ForestExtra.DESERT_HAZE, ForestExtra.VOLCANO)
        if (flood > 0.0) extras += ForestExtra.FLOOD
        if (date.sunday) extras += if (night) ForestExtra.DRAGON_RESTING else ForestExtra.DRAGON_FLYING
        if (leafy) {
            if (2 in have) {
                extras += ForestExtra.WILDFLOWERS
                if (!night) extras += ForestExtra.BUTTERFLIES
            }
            if (8 in have && !night) extras += ForestExtra.DRAGONFLY
            if (season == ForestSeason.AUTUMN) extras += ForestExtra.AUTUMN_LEAVES
            if (season == ForestSeason.SPRING && !night) extras += ForestExtra.SPRING_PETALS
            if (season == ForestSeason.WINTER) extras += ForestExtra.WINTER_MIST
            if (season == ForestSeason.MONSOON && !night) extras += ForestExtra.LIGHT_RAIN
            if (time == ForestTime.DAY && 3 in have) extras += ForestExtra.BIRDS
            if (night && 6 in have) extras += ForestExtra.FIREFLIES
        }
        if (phase == ForestPhase.DRY && !night) extras += ForestExtra.DRY_LEAVES
        if (green && day == 1 && (time == ForestTime.DAWN || time == ForestTime.DAY) && catchUp == null) extras += ForestExtra.RAINBOW

        return ForestNow(
            epochMs = epochMs, date = date, minute = minute, day = day, monthLength = n, phase = phase, season = season, time = time,
            sunrise = rise, sunset = set, moon = moon(epochMs, zone), catchUp = catchUp, todayTree = todayTree, stageFraction = frac,
            trees = trees, hosts = hosts, arrived = have, things = things, picnic = picnic, eveningGreen = eveningGreen, flood = flood,
            extras = extras,
        )
    }

    /** Collects the placed things in the mockup's order (its keys depend on that order). */
    private class Scene {
        val items = ArrayList<ForestThing>()
        var hosts: List<ForestTree> = emptyList()
        private val under = ArrayList<ForestThing>()

        fun host(i: Int): ForestTree? = if (hosts.isEmpty()) null else hosts[i % hosts.size]

        fun put(sprite: String, x: Double, y: Double, w: Double, flip: Boolean, key: Float, kind: ForestThing.Kind, rx: Double = 0.0, ry: Double = 0.0) {
            items += ForestThing(sprite, x.toFloat(), y.toFloat(), w.toFloat(), flip, key, kind, rx.toFloat(), ry.toFloat())
        }

        /** A thing standing on the ground at (x, y), sized by its depth. */
        fun at(sprite: String, x: Number, y: Number, flip: Boolean = false, k: Double = 1.0, key: Double? = null) {
            val yd = y.toDouble()
            val w = SIZE.getValue(sprite) * TREE_W * depth(yd) * k
            if (sprite in FLAT) put(sprite, x.toDouble(), yd, w, flip, (key ?: yd).toFloat(), ForestThing.Kind.FLAT)
            else put(sprite, x.toDouble(), yd, w, flip, (key ?: yd).toFloat(), ForestThing.Kind.GROUND, w * .3, max(1.5, w * .045))
        }

        /** A thing on host tree [i]'s anchor, [size] × the tree's width. */
        fun up(i: Int, which: ForestAnchor, sprite: String, size: Double, flip: Boolean = false, dy: Double = 0.0) {
            val t = host(i) ?: return
            val (x, y) = anchor(t, which)
            val tw = t.w.toDouble()
            put(sprite, x, y + dy * tw, size * tw, flip, (t.y + .01 * (items.size % 50 + 1)).toFloat(), ForestThing.Kind.IN_TREE)
        }

        /** A decal under everything (key 0). Kept out of [items], whose size sets the in-tree keys as in the mockup. */
        fun raw(sprite: String, x: Double, y: Double, w: Double) {
            under += ForestThing(sprite, x.toFloat(), y.toFloat(), w.toFloat(), false, 0f, ForestThing.Kind.FLAT)
        }

        /** Back to front. The sort is stable: equal keys keep their order, as in Python. */
        fun sorted(): List<ForestThing> = (under + items).sortedBy { it.key }
    }

    // ------------------------------------------------------------------ rules
    fun monthLength(year: Int, month: Int): Int {
        val next = if (month == 12) epochDay(year + 1, 1, 1) else epochDay(year, month + 1, 1)
        return (next - epochDay(year, month, 1)).toInt()
    }

    /** Green days 1 … n−4; then picnic, dry spell, desert, storm. */
    fun phaseOf(day: Int, n: Int): ForestPhase = when {
        day <= n - 4 -> ForestPhase.GREEN
        day == n - 3 -> ForestPhase.PICNIC
        day == n - 2 -> ForestPhase.DRY
        day == n - 1 -> ForestPhase.DESERT
        else -> ForestPhase.STORM
    }

    /** The arrival numbers in the forest on green day [day] of an [n]-day month. In a short month the bonus days
     *  that don't fit (25–27) come with the last green day. */
    fun arrived(day: Int, n: Int): Set<Int> {
        val lastGreen = n - 4
        val out = (1..min(day, lastGreen)).toMutableSet()
        if (day >= lastGreen) out += (lastGreen + 1)..27
        return out
    }

    /** What arrives on [day]: its own thing, plus the leftover bonus things on a short month's last green day. */
    fun arrivalsOn(day: Int, n: Int): List<Int> {
        val lastGreen = n - 4
        return when {
            day < 1 || day > lastGreen -> emptyList()
            day < lastGreen -> listOf(day)
            else -> (day..27).toList()
        }
    }

    private fun daySeed(d: ForestDate): Long = d.year * 10000L + d.month * 100 + d.day

    fun seasonOf(d: ForestDate): ForestSeason = ForestSeason.entries[PyRandom(daySeed(d) * 31).below(ForestSeason.entries.size)]

    fun kindOf(d: ForestDate): ForestKind = ForestKind.entries[PyRandom(daySeed(d) * 17).below(ForestKind.entries.size)]

    /** Today's tree by how far through its growing it is (sunrise → sunset − 30 min). */
    fun stageAt(frac: Double): Stage = when {
        frac < .18 -> Stage.SEED
        frac < .38 -> Stage.SPROUT
        frac < .6 -> Stage.SAPLING
        frac < .84 -> Stage.YOUNG
        else -> Stage.GROWN
    }

    /** The mockup's symbol for a tree: today's by its stage ([frac] set), or the grown look for the [season]. */
    fun treeSprite(kind: ForestKind, season: ForestSeason, frac: Double?): String {
        if (frac != null) {
            when (stageAt(frac)) {
                Stage.SEED -> return "t-seed"
                Stage.SPROUT -> return "t-sprout"
                Stage.SAPLING -> return "t-sapling"
                Stage.YOUNG -> return "t-young"
                Stage.GROWN -> Unit
            }
        }
        return when {
            kind == ForestKind.MANGO && season == ForestSeason.SUMMER -> "t-mango-fruit"
            kind == ForestKind.MANGO && season == ForestSeason.SPRING -> "t-mango-flower"
            kind == ForestKind.GULMOHAR && season != ForestSeason.SUMMER && season != ForestSeason.SPRING -> "t-gulmohar-green"
            else -> kind.id
        }
    }

    fun stageOf(sprite: String): Stage = when (sprite) {
        "t-seed" -> Stage.SEED
        "t-sprout" -> Stage.SPROUT
        "t-sapling" -> Stage.SAPLING
        "t-young" -> Stage.YOUNG
        else -> Stage.GROWN
    }

    /** Stage names as the mockup says them ("Grown" for a grown tree). */
    fun stageName(stage: Stage): String = if (stage == Stage.GROWN) "Grown" else stage.title

    /** The month that makes TreeImages draw the mockup's look: mango fruit (Summer) / flowers (Spring), gulmohar
     *  bloom (Summer, Spring), jasmine always in flower, neem never. */
    fun artMonth(kind: ForestKind, season: ForestSeason): Int = when (kind) {
        ForestKind.MANGO -> when (season) {
            ForestSeason.SUMMER -> 5
            ForestSeason.SPRING -> 2
            else -> 12
        }
        ForestKind.GULMOHAR -> if (season == ForestSeason.SUMMER || season == ForestSeason.SPRING) 5 else 12
        ForestKind.JASMINE -> 5
        else -> 12
    }

    // ------------------------------------------------------------------ the storm's flood
    /** When the water starts going down (minutes after midnight). */
    const val FLOOD_DRAIN = 14 * 60.0

    /** The flood at high water reaches back to here; the meadow behind stays dry. */
    private const val FLOOD_TOP = 352.0

    /** How deep the water is at high water, at depth 1 (design units). */
    const val FLOOD_DEPTH = 14f

    /** 0 → 1 over the four hours after sunrise, high till 2 PM, back to 0 when the green comes (50 min before sunset). */
    fun floodAt(minute: Double, rise: Double, set: Double): Double {
        val up = rise + 240
        val dry = set - 50
        fun ease(f: Double): Double = f.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
        return when {
            minute <= rise || minute >= dry -> 0.0
            minute < up -> ease((minute - rise) / (up - rise))
            minute <= FLOOD_DRAIN -> 1.0
            else -> 1 - ease((minute - FLOOD_DRAIN) / max(1.0, dry - FLOOD_DRAIN))
        }
    }

    /** Where the water starts for a [flood] level: the front edge at 0, [FLOOD_TOP] at high water. */
    fun floodLine(flood: Double): Float = (H - flood * (H - FLOOD_TOP)).toFloat()

    // ------------------------------------------------------------------ layout
    /** How big things look standing at ground line y: small at the horizon, big at the front. */
    fun depth(y: Double): Double = 0.56 + (y - HORIZON) / (H - HORIZON) * 0.84

    fun treeWidth(kind: ForestKind, y: Double): Double = TREE_W * depth(y) * kind.widthK

    /** An anchor on a (leafy) tree in scene coordinates (1 tree unit = w / 200). */
    fun anchor(t: ForestTree, which: ForestAnchor): Pair<Double, Double> {
        val (ax, ay) = t.kind.anchorBox(which) ?: (100.0 to 120.0)
        val w = t.w.toDouble()
        return (t.x - w / 2 + ax * w / 200) to (t.y - w * 1.13 + ay * w / 200)
    }

    // ------------------------------------------------------------------ sun and moon
    /** Sunrise and sunset at Jolarpettai in minutes after midnight, in a zone [tzHours] ahead of UTC (NOAA). */
    fun sunTimes(year: Int, month: Int, day: Int, tzHours: Double = 5.5): Pair<Double, Double> {
        val n = (epochDay(year, month, day) - epochDay(year, 1, 1) + 1).toDouble()
        val g = 2 * PI / 365 * (n - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val dec = (0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g)
            - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g))
        val lat = Math.toRadians(LAT)
        val ha = Math.toDegrees(acos(cos(Math.toRadians(90.833)) / (cos(lat) * cos(dec)) - tan(lat) * tan(dec)))
        val noon = 720 - 4 * LON - eq + tzHours * 60
        return (noon - 4 * ha) to (noon + 4 * ha)
    }

    /** The real Moon at [epochMs]; [zone] gives the dates of the next full and new moon. */
    fun moon(epochMs: Long, zone: TimeZone = IST): ForestMoon {
        val days = (epochMs - NEW_MOON_MS) / 86_400_000.0
        val age = ((days % SYNODIC) + SYNODIC) % SYNODIC
        val lit = (1 - cos(2 * PI * age / SYNODIC)) / 2
        val name = MOON_PHASES[(age / SYNODIC * 8 + .5).toInt() % 8]
        var toFull = SYNODIC / 2 - age
        if (toFull < 0) toFull += SYNODIC
        val fullMs = epochMs + (toFull * 86_400_000).toLong()
        val newMs = epochMs + ((SYNODIC - age) * 86_400_000).toLong()
        return ForestMoon(age, lit, name, age < SYNODIC / 2, fullMs, newMs, localDate(fullMs, zone), localDate(newMs, zone))
    }

    // ------------------------------------------------------------------ text for the screens
    /** "6:06 AM" (rounded like the mockup). */
    fun hm(minutes: Double): String {
        val m = Math.rint(minutes).toInt()
        val h = Math.floorDiv(m, 60)
        val mm = Math.floorMod(m, 60)
        return "${Math.floorMod(h - 1, 12) + 1}:${mm.toString().padStart(2, '0')} ${if (Math.floorMod(h, 24) < 12) "AM" else "PM"}"
    }

    /** The title of what a day brings: the arrival, or the month's end. */
    fun arrivalTitle(day: Int, n: Int): String = when (phaseOf(day, n)) {
        ForestPhase.GREEN -> ARRIVALS[day] ?: ""
        ForestPhase.PICNIC -> "Your picnic"
        ForestPhase.DRY -> "Dry spell · a tornado"
        ForestPhase.DESERT -> "Desert · a volcano"
        ForestPhase.STORM -> "Storm · a flood"
    }

    fun dayIcon(day: Int, n: Int): String = phaseOf(day, n).let { if (it == ForestPhase.GREEN) ARRIVAL_ICONS[day] ?: "" else PHASE_ICONS.getValue(it) }

    /** The top bar's line: "Day 22 of 31 · Thu 22 Oct · 9:30 PM" (with " · picnic day" etc. at the month's end). */
    fun topLine(now: ForestNow): String {
        val ph = when (now.phase) {
            ForestPhase.GREEN -> ""
            ForestPhase.PICNIC -> " · picnic day"
            ForestPhase.DRY -> " · dry spell"
            ForestPhase.DESERT -> " · desert"
            ForestPhase.STORM -> " · storm"
        }
        return "Day ${now.day} of ${now.monthLength}$ph · ${now.date.text} · ${hm(now.minute)}"
    }

    /** The season chip ("🌸 Spring"; the month's end shows the phase instead). */
    fun seasonChip(now: ForestNow): String =
        if (now.leafy) "${now.season.icon} ${now.season.title}" else "${PHASE_ICONS.getValue(now.phase)} ${now.phase.title}"

    /** The trees chip: "22 trees", "28 trees · drying", "Dry trees", "Rain all day". */
    fun treeChip(now: ForestNow): String = when (now.phase) {
        ForestPhase.GREEN, ForestPhase.PICNIC -> trees(now.treeCount)
        ForestPhase.DRY -> "${trees(now.treeCount)} · drying"
        ForestPhase.DESERT -> "Dry trees"
        ForestPhase.STORM -> "Rain all day"
    }

    private fun trees(k: Int) = if (k == 1) "1 tree" else "$k trees"

    /** The Tools tile: "Day 22 · a mongoose came". */
    fun statusLine(now: ForestNow): String {
        now.catchUp?.let { return "Catching up · day ${it.day} of ${it.of}" }
        return "Day ${now.day} · " + when (now.phase) {
            ForestPhase.GREEN -> CAME[now.day] ?: "a new tree"
            ForestPhase.PICNIC -> "your picnic day"
            ForestPhase.DRY -> "the dry spell"
            ForestPhase.DESERT -> "desert today"
            ForestPhase.STORM -> "storm today"
        }
    }

    /** The Home row's title: "Day 22 · 22 trees". */
    fun homeTitle(now: ForestNow): String = "Day ${now.day} · " + when (now.phase) {
        ForestPhase.GREEN, ForestPhase.PICNIC -> trees(now.treeCount)
        ForestPhase.DRY -> "${trees(now.treeCount)} drying"
        ForestPhase.DESERT -> "desert"
        ForestPhase.STORM -> "storm"
    }

    /** The Home row's subtitle: "A mongoose came today", "Your picnic day", "The dry spell", "Desert today", "Storm today". */
    fun homeSubtitle(now: ForestNow): String {
        now.catchUp?.let { return "Catching up · day ${it.day} of ${it.of}" }
        return when (now.phase) {
            ForestPhase.GREEN -> (CAME[now.day] ?: "a new tree came").replaceFirstChar { it.uppercase() } + " today"
            ForestPhase.PICNIC -> "Your picnic day"
            ForestPhase.DRY -> "The dry spell"
            ForestPhase.DESERT -> "Desert today"
            ForestPhase.STORM -> "Storm today"
        }
    }

    /** The Today card's title. */
    fun todayTitle(now: ForestNow): String = when {
        now.sunday -> "Sunday visitors"
        now.phase == ForestPhase.GREEN && now.day == 1 -> "A new month begins"
        now.phase == ForestPhase.GREEN -> ARRIVALS[now.day] ?: ""
        now.phase == ForestPhase.PICNIC -> "Your picnic day"
        now.phase == ForestPhase.DRY -> "The dry spell · a tornado"
        now.phase == ForestPhase.DESERT -> "Desert · the volcano wakes"
        now.eveningGreen -> "Green again"
        now.flood > 0.0 -> "The storm · a flood"
        else -> "The storm"
    }

    /** The Today card's lines. */
    fun todayLines(now: ForestNow): List<ForestLine> {
        val out = ArrayList<ForestLine>()
        if (now.sunday) {
            out += ForestLine("🐉", if (now.night) "A good dragon is resting by the forest" else "A good dragon is flying over the forest")
            out += ForestLine("🦖", "A gentle T. rex came to say hello")
        }
        when (now.phase) {
            ForestPhase.GREEN -> {
                if (now.day == 1) out += ForestLine("🌈", "Fresh green after last night's storm")
                else if (now.sunday) {
                    val bonus = if (now.day >= 25) "Bonus day" else "Day"
                    out += ForestLine(ARRIVAL_ICONS[now.day] ?: "🌱", "$bonus ${now.day}: ${(ARRIVALS[now.day] ?: "").replaceFirstChar { it.lowercase() }}")
                }
                val also = arrivalsOn(now.day, now.monthLength).drop(1)
                if (also.isNotEmpty()) out += ForestLine("🎁", "Also today: " + also.joinToString(", ") { (ARRIVALS[it] ?: "").replaceFirstChar { c -> c.lowercase() } })
                treeLine(now)?.let { out += it }
            }
            ForestPhase.PICNIC -> {
                out += ForestLine("🧺", picnicLine(now))
                if (now.season == ForestSeason.MONSOON && !now.night && now.picnic != ForestPicnic.COMING && now.picnic != ForestPicnic.LEFT) {
                    out += ForestLine("☂️", "A rainy picnic · under a big umbrella")
                }
                treeLine(now)?.let { out += it }
            }
            ForestPhase.DRY -> {
                out += ForestLine("🌪", "A tornado is spinning far out on the meadow")
                out += ForestLine("🐃", "A buffalo and an ox came to the last of the pond")
                out += ForestLine("🍂", "The trees are drying · the animals are leaving")
            }
            ForestPhase.DESERT -> {
                out += ForestLine("🌋", "The volcano on the hills is erupting")
                out += ForestLine("🐪", "Camels and rhinos are crossing the desert")
                out += ForestLine("⛈", "Tomorrow: the storm")
            }
            ForestPhase.STORM -> out += when {
                now.eveningGreen -> ForestLine("🌱", "Tomorrow: a new month, a new forest")
                now.flood >= .3 -> ForestLine("🐋", "The forest is flooded · whales, dolphins and fish swam in")
                now.minute >= FLOOD_DRAIN && now.flood > 0.0 -> ForestLine("🦀", "The water is going down · crabs are out")
                now.flood > 0.0 -> ForestLine("🌊", "The water is rising")
                else -> ForestLine("⛈", "Rain all day · a flood, then green again by evening")
            }
        }
        if (!now.sunday && now.leafy && !now.catchingUp) {
            out += when {
                now.night -> ForestLine("🌙", moonTitle(now))
                now.minute < now.sunrise -> ForestLine("🌅", "Sunrise ${hm(now.sunrise)} · Jolarpettai")
                else -> ForestLine("🌇", "Sunset ${hm(now.sunset)} · Jolarpettai")
            }
        }
        return out
    }

    /** "Today's tree: Neem · sapling · grown by 5:24 PM" (null when no tree grows today). */
    fun treeLine(now: ForestNow): ForestLine? {
        val t = now.todayTree ?: return null
        val grown = t.stage == Stage.GROWN
        val icon = if (grown) "🌳" else "🌱"
        now.catchUp?.let { return ForestLine(icon, "Day ${it.day}'s tree: ${t.kind.title} · ${stageName(t.stage).lowercase()}") }
        return if (grown) ForestLine(icon, "Today's tree: ${t.kind.title} · grown at ${hm(now.grownBy)}")
        else ForestLine(icon, "Today's tree: ${t.kind.title} · ${stageName(t.stage).lowercase()} · grown by ${hm(now.grownBy)}")
    }

    private fun picnicLine(now: ForestNow): String {
        val from = hm(PICNIC_FROM.toDouble())
        val to = hm(PICNIC_TO.toDouble())
        return when (now.picnic) {
            ForestPicnic.COMING -> "The two of you are coming at $from"
            ForestPicnic.LEFT -> "The two of you came at $from and left at $to"
            else -> "The two of you came at $from · leaving around $to"
        }
    }

    /** The Moon's tap card title: "Waxing gibbous · 87% lit". */
    fun moonTitle(now: ForestNow): String = "${now.moon.name} · ${Math.round(now.moon.lit * 100)}% lit"

    /** "Full moon in 4 days · Mon 26 Oct" (or the new moon, whichever comes first). */
    fun moonLine(now: ForestNow): String {
        val m = now.moon
        val full = m.nextFullMs <= m.nextNewMs
        val what = if (full) "Full moon" else "New moon"
        val on = if (full) m.nextFull else m.nextNew
        return when (val days = (on.epochDay - now.date.epochDay).toInt()) {
            0 -> "$what today"
            1 -> "$what tomorrow · ${on.text}"
            else -> "$what in $days days · ${on.text}"
        }
    }

    /** The catch-up banner: "Catching up · day 9 of 22" (null when not catching up). */
    fun catchUpTitle(now: ForestNow): String? = now.catchUp?.let { "Catching up · day ${it.day} of ${it.of}" }

    /** "Your forest is new, so this month so far plays out today — about one day every 49 min. All caught up by midnight." */
    fun catchUpText(now: ForestNow): String? = now.catchUp?.let {
        val pace = if (it.minutesPerDay >= 59.99) "about one day an hour" else "about one day every ${Math.round(it.minutesPerDay)} min"
        val by = if (it.endsMinute >= 24 * 60 - .5) "midnight" else hm(it.endsMinute)
        "Your forest is new, so this month so far plays out today — $pace. All caught up by $by."
    }

    /** The This-month page's subtitle: "October · 31 days". */
    fun monthTitle(now: ForestNow): String = "${MONTHS[now.date.month - 1]} · ${now.monthLength} days"

    /** Every day of the month for the This-month page ([ForestMonthDay.today] = the forest-day shown). */
    fun monthDays(now: ForestNow): List<ForestMonthDay> {
        val n = now.monthLength
        return (1..n).map { k ->
            val d = ForestDate(now.date.year, now.date.month, k)
            ForestMonthDay(
                day = k, date = d, phase = phaseOf(k, n), icon = dayIcon(k, n), title = arrivalTitle(k, n),
                also = arrivalsOn(k, n).drop(1).map { ARRIVALS[it] ?: "" }, sunday = d.sunday, today = k == now.day, past = k < now.day,
            )
        }
    }

    /** "Coming up": the next four green days, then the month's end and the Sunday visitors. */
    fun comingUp(now: ForestNow, greenDays: Int = 4): List<ForestComing> {
        val n = now.monthLength
        val t = now.day
        val out = ArrayList<ForestComing>()
        for (k in (t + 1)..(n - 4)) {
            if (out.size == greenDays) break
            val also = arrivalsOn(k, n).drop(1)
            val text = (ARRIVALS[k] ?: "") + if (also.isEmpty()) "" else " · and " + also.joinToString(", ") { (ARRIVALS[it] ?: "").replaceFirstChar { c -> c.lowercase() } }
            out += ForestComing(ARRIVAL_ICONS[k] ?: "🌱", "Day $k", text)
        }
        if (t < n - 3) out += ForestComing("🧺", "Day ${n - 3}", "Your picnic")
        if (t < n - 2) out += ForestComing("🍂", "Days ${n - 2}–${n - 1}", "A tornado in the dry spell, then a volcano in the desert")
        else if (t < n - 1) out += ForestComing("🏜", "Day ${n - 1}", "Desert · the volcano wakes")
        if (t < n) out += ForestComing("⛈", "Day $n", "Storm and flood — then it all starts again")
        else out += ForestComing("🌈", "Day 1", "A new month begins")
        out += ForestComing("🦖", "Every Sunday", "A friendly dragon and T. rex visit")
        return out
    }

    // ------------------------------------------------------------------ dates (pure arithmetic; java.time needs Android 8)
    /** Days since 1 Jan 1970 for a civil date. */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    fun dateOf(epochDay: Long): ForestDate {
        val z = epochDay + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return ForestDate(y, m, d)
    }

    fun localDate(epochMs: Long, zone: TimeZone = IST): ForestDate = dateOf(Math.floorDiv(epochMs + zone.getOffset(epochMs), DAY_MS))
}

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000.0

/**
 * Python's random.Random (MT19937 with Python's seeding, choice and uniform), so a date picks the same season and
 * tree kind as the mockup and the storm's shoots land in the same places.
 */
internal class PyRandom(seed: Long) {
    private val mt = IntArray(N)
    private var mti = N + 1

    init {
        var s = Math.abs(seed)
        val key = ArrayList<Int>()
        if (s == 0L) key += 0
        while (s != 0L) {
            key += (s and 0xFFFFFFFFL).toInt()
            s = s ushr 32
        }
        initByArray(key.toIntArray())
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        mti = N
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = max(N, key.size)
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
            i++; j++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            k--
        }
        mt[0] = UPPER
    }

    private fun twist(a: Int, b: Int, c: Int): Int {
        val y = (a and UPPER) or (b and LOWER)
        return c xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
    }

    fun nextInt32(): Int {
        if (mti >= N) {
            var kk = 0
            while (kk < N - M) { mt[kk] = twist(mt[kk], mt[kk + 1], mt[kk + M]); kk++ }
            while (kk < N - 1) { mt[kk] = twist(mt[kk], mt[kk + 1], mt[kk + (M - N)]); kk++ }
            mt[N - 1] = twist(mt[N - 1], mt[0], mt[M - 1])
            mti = 0
        }
        var y = mt[mti++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y
    }

    /** random.getrandbits(k), 1 ≤ k ≤ 31. */
    private fun bits(k: Int): Int = nextInt32() ushr (32 - k)

    /** random.randrange(n) / the index random.choice uses. */
    fun below(n: Int): Int {
        val k = 32 - Integer.numberOfLeadingZeros(n)
        var r = bits(k)
        while (r >= n) r = bits(k)
        return r
    }

    fun random(): Double {
        val a = nextInt32() ushr 5
        val b = nextInt32() ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    fun uniform(a: Double, b: Double): Double = a + (b - a) * random()

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX_A = 0x9908b0df.toInt()
        const val UPPER = 0x80000000.toInt()
        const val LOWER = 0x7fffffff
    }
}
