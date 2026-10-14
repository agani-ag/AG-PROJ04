package com.agani.syncup.forest

import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import kotlin.math.max

/**
 * The trees of Tools → Forest. [season] are the months (1–12) when a grown tree flowers or fruits
 * (and gives seeds); [fruit] = its season shows fruit instead of flowers.
 */
enum class Species(val title: String, val botanical: String, val season: Set<Int>, val fruit: Boolean = false) {
    NEEM("Neem", "Azadirachta indica", setOf(3, 4)),
    MANGO("Mango", "Mangifera indica", setOf(1, 2, 3, 4, 5, 6)),
    GULMOHAR("Gulmohar", "Delonix regia", setOf(4, 5, 6)),
    PEEPAL("Peepal", "Ficus religiosa", setOf(4, 5, 6), fruit = true),
    COCONUT("Coconut", "Cocos nucifera", setOf(9, 10, 11), fruit = true),
    JASMINE("Jasmine", "Jasminum sambac", setOf(3, 4, 5, 6, 7, 8, 9, 10)),
    BANYAN("Banyan", "Ficus benghalensis", setOf(5, 6, 7), fruit = true),
    BAMBOO("Bamboo", "Bambusa vulgaris", emptySet()),
    ;

    /** Mango flowers Jan–Mar and fruits Apr–Jun. */
    fun fruitsIn(month: Int): Boolean = fruit || (this == MANGO && month in 4..6)

    /** How a new kind is found (shown on Trees & seeds until it is). */
    val findHint: String
        get() = when (this) {
            NEEM -> "everyone starts with 3"
            MANGO -> "after your first grown tree"
            GULMOHAR -> "after 2 grown trees"
            PEEPAL -> "after 3 grown trees"
            COCONUT -> "after 5 grown trees"
            JASMINE -> "after 7 calm days"
            BANYAN -> "after 10 grown trees"
            BAMBOO -> "7 calm days in a row"
        }
}

enum class Stage(val title: String) { SEED("Seed"), SPROUT("Sprout"), SAPLING("Sapling"), YOUNG("Young tree"), GROWN("Grown tree") }

/**
 * One tree. It grows from [plantedMs] over [growDays] (1–14, chosen at planting) as calm minutes
 * of care ([care]) build up: a stage needs its share of the time AND of the calm.
 *
 * [seedsGiven] — seeds this tree has dropped (1 when grown, more in its seasons); [seasonYear] the
 * last year it gave its season's seeds. [variant] makes every tree a little different.
 */
data class Tree(
    val id: String = UUID.randomUUID().toString(),
    val species: Species,
    val name: String = "",
    val plantedMs: Long = System.currentTimeMillis(),
    val growDays: Int = 7,
    val care: Int = 0,
    val lastCareMs: Long = 0,
    val stageMs: Map<Stage, Long> = emptyMap(),
    val seedsGiven: Int = 0,
    val seasonYear: Int = 0,
    val variant: Int = (Math.random() * 100000).toInt(),
    val updatedMs: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val local: Boolean = false,
) {
    val label: String get() = name.ifBlank { species.title }

    /** Calm minutes the whole tree needs: about 20 per day of growing, at least 40. */
    val calmNeeded: Int get() = max(40, 20 * growDays)

    fun stage(now: Long = System.currentTimeMillis()): Stage = stageFor(care, now - plantedMs, growDays)

    /** What the next stage needs: (stage, minutes still to give, ms still to wait), or null when grown. */
    fun next(now: Long = System.currentTimeMillis()): Triple<Stage, Int, Long>? {
        val s = stage(now)
        if (s == Stage.GROWN) return null
        val n = Stage.entries[s.ordinal + 1]
        val (minCare, minTime) = needs(n, growDays)
        return Triple(n, (minCare - care).coerceAtLeast(0), (plantedMs + minTime - now).coerceAtLeast(0))
    }

    fun syncJson(): JSONObject = JSONObject()
        .put("species", species.name).put("name", name).put("planted_ms", plantedMs).put("grow_days", growDays)
        .put("care", care).put("last_care_ms", lastCareMs).put("seeds_given", seedsGiven).put("season_year", seasonYear)
        .put("variant", variant).put("stages", JSONObject().apply { stageMs.forEach { (k, v) -> put(k.name, v) } })

    fun storeJson(): JSONObject = syncJson().put("id", id).put("updated_ms", updatedMs).put("deleted", deleted).put("dirty", dirty).put("local", local)

    companion object {
        /** Minimum (calm minutes, ms since planting) for [stage]. */
        fun needs(stage: Stage, growDays: Int): Pair<Int, Long> {
            val c = max(40, 20 * growDays)
            val t = growDays * 86_400_000L
            return when (stage) {
                Stage.SEED -> 0 to 0L
                Stage.SPROUT -> 10 to 0L
                Stage.SAPLING -> (10 + (c - 10) * 25 / 100) to t / 4
                Stage.YOUNG -> (10 + (c - 10) * 60 / 100) to t / 2
                Stage.GROWN -> c to t
            }
        }

        fun stageFor(care: Int, elapsed: Long, growDays: Int): Stage =
            Stage.entries.last { s -> needs(s, growDays).let { (c, t) -> care >= c && elapsed >= t } }

        fun fromJson(id: String, o: JSONObject, updatedMs: Long, deleted: Boolean): Tree {
            val st = o.optJSONObject("stages")
            return Tree(
                id = id,
                species = runCatching { Species.valueOf(o.optString("species")) }.getOrDefault(Species.NEEM),
                name = o.optString("name"),
                plantedMs = o.optLong("planted_ms", updatedMs),
                growDays = o.optInt("grow_days", 7).coerceIn(1, 14),
                care = o.optInt("care"),
                lastCareMs = o.optLong("last_care_ms"),
                stageMs = Stage.entries.mapNotNull { s -> st?.optLong(s.name, 0)?.takeIf { it > 0 }?.let { s to it } }.toMap(),
                seedsGiven = o.optInt("seeds_given"),
                seasonYear = o.optInt("season_year"),
                variant = o.optInt("variant", id.hashCode()),
                updatedMs = updatedMs,
                deleted = deleted,
                dirty = false,
            )
        }

        fun fromStore(o: JSONObject): Tree = fromJson(o.optString("id"), o, o.optLong("updated_ms"), o.optBoolean("deleted"))
            .copy(dirty = o.optBoolean("dirty"), local = o.optBoolean("local"))
    }
}

/** Calm minutes on one day on one phone ("20261014|phone-id"). Days add up across phones. */
data class CalmDay(
    val key: String,
    val minutes: Int,
    val updatedMs: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val local: Boolean = false,
) {
    val day: Int get() = key.substringBefore('|').toIntOrNull() ?: 0
}

/** Days as yyyymmdd ints (java.time needs Android 8). */
internal object FDays {
    fun of(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH) }
    fun today(): Int = of(System.currentTimeMillis())
    fun add(day: Int, n: Int): Int = Calendar.getInstance().apply {
        clear()
        set(day / 10000, day / 100 % 100 - 1, day % 100)
        add(Calendar.DAY_OF_YEAR, n)
    }.let { it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH) }
    fun weekday(day: Int): Int = Calendar.getInstance().apply {
        clear()
        set(day / 10000, day / 100 % 100 - 1, day % 100)
    }.get(Calendar.DAY_OF_WEEK) // 1 = Sunday
    fun month(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    fun year(): Int = Calendar.getInstance().get(Calendar.YEAR)
    fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    fun dateText(ms: Long): String = Calendar.getInstance().apply { timeInMillis = ms }.let {
        "${it.get(Calendar.DAY_OF_MONTH)} ${arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[it.get(Calendar.MONTH)]}"
    }
}

/** India's seasons, from the calendar. */
enum class Season(val title: String, val months: String, val note: String) {
    WINTER("Winter", "Dec – Feb", "morning mist"),
    SUMMER("Summer", "Mar – May", "Gulmohar in bloom"),
    MONSOON("Monsoon", "Jun – Sep", "soft rain, deep green"),
    AUTUMN("Autumn", "Oct – Nov", "golden evenings"),
    ;

    companion object {
        fun of(month: Int): Season = when (month) {
            12, 1, 2 -> WINTER
            3, 4, 5 -> SUMMER
            6, 7, 8, 9 -> MONSOON
            else -> AUTUMN
        }
    }
}

/** Time of day for the sky. */
enum class DayPart { DAWN, DAY, EVENING, NIGHT;
    companion object {
        fun now(hour: Int = FDays.hour()): DayPart = when (hour) {
            in 5..6 -> DAWN
            in 7..16 -> DAY
            in 17..18 -> EVENING
            else -> NIGHT
        }
    }
}
