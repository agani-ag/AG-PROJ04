package com.agani.syncup.decide

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

// The Wheel spinner's data (docs/mockup/syncup-everyday-tools.html, phones 6–11): saved wheels in
// decide_wheels.json, what a shared spin carries, and the one function that picks where a spin lands.

/** Slice colours, by the option's colour index — an option keeps its colour as others are knocked out. */
internal val WHEEL_COLORS = listOf(
    Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFF10B981),
    Color(0xFF3B82F6), Color(0xFFEC4899), Color(0xFFA16207), Color(0xFFF97316),
    Color(0xFF6366F1), Color(0xFF0EA5E9), Color(0xFFD946EF), Color(0xFF65A30D),
)

internal fun wheelColor(index: Int): Color = WHEEL_COLORS[Math.floorMod(index, WHEEL_COLORS.size)]

internal enum class WheelMode { FIRST, LAST }

/** One thing on the wheel; [color] is its colour index, given when it was added. */
@Immutable
internal data class WheelOption(val name: String, val color: Int)

internal const val WHEEL_MAX_OPTIONS = 100
internal const val WHEEL_NAME_MAX = 40

@Immutable
internal data class Wheel(
    val id: Long,
    val name: String,
    val options: List<WheelOption>,
    val mode: WheelMode = WheelMode.FIRST,
    val auto: Boolean = false,
    /** Last choice: positions in [options] knocked out so far, first out first. */
    val out: List<Int> = emptyList(),
    /** First choice: the last 10 picks, newest first. */
    val recent: List<String> = emptyList(),
) {
    /** Positions in [options] still on the wheel: all of them in First choice. */
    val leftPositions: List<Int>
        get() = if (mode == WheelMode.LAST) options.indices.filter { it !in out } else options.indices.toList()

    /** What the wheel shows now, in order. */
    val left: List<WheelOption> get() = leftPositions.map { options[it] }

    val outOptions: List<WheelOption> get() = out.mapNotNull { options.getOrNull(it) }

    /** Last choice: the round about to be spun. */
    val round: Int get() = out.size + 1

    /** Knocks out the slice at [index] of [left]. */
    fun knockOut(index: Int): Wheel {
        val pos = leftPositions.getOrNull(index) ?: return this
        return copy(out = out + pos)
    }

    /** Takes the option at [pos] off the wheel (out positions follow it down). */
    fun removeAt(pos: Int): Wheel {
        if (pos !in options.indices) return this
        return copy(
            options = options.filterIndexed { i, _ -> i != pos },
            out = out.filter { it != pos }.map { if (it > pos) it - 1 else it },
        )
    }

    /** Puts the options in a new order ([order] = old positions); a Last-choice game carries on. */
    fun reorder(order: List<Int>): Wheel {
        val newPos = IntArray(options.size)
        order.forEachIndexed { new, old -> newPos[old] = new }
        return copy(options = order.map { options[it] }, out = out.map { newPos[it] })
    }

    fun shuffled(): Wheel = reorder(options.indices.shuffled())

    fun sortedAz(): Wheel = reorder(options.indices.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { options[it].name }))

    /** Every option back on the wheel. */
    fun playAgain(): Wheel = copy(out = emptyList())

    /** "8 options", or in Last choice "5 of 8 left". */
    val countText: String
        get() = if (mode == WheelMode.LAST) "${left.size} of ${options.size} left"
        else "${options.size} option${if (options.size == 1) "" else "s"}"

    fun storeJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("options", JSONArray(options.map { JSONObject().put("n", it.name).put("c", it.color) }))
        .put("mode", if (mode == WheelMode.LAST) "last" else "first")
        .put("auto", auto)
        .put("out", JSONArray(out))
        .put("recent", JSONArray(recent))

    companion object {
        fun fromStore(o: JSONObject): Wheel {
            val opts = o.optJSONArray("options") ?: JSONArray()
            val options = (0 until opts.length()).mapNotNull { i ->
                val x = opts.opt(i)
                when (x) {
                    is JSONObject -> WheelOption(x.optString("n"), x.optInt("c", i))
                    is String -> WheelOption(x, i)
                    else -> null
                }
            }.filter { it.name.isNotBlank() }.take(WHEEL_MAX_OPTIONS)
            val outs = o.optJSONArray("out") ?: JSONArray()
            val out = (0 until outs.length()).map { outs.optInt(it, -1) }.filter { it in options.indices }.distinct()
            val rec = o.optJSONArray("recent") ?: JSONArray()
            return Wheel(
                id = o.getLong("id"),
                name = o.optString("name").ifBlank { "Wheel" },
                options = options,
                mode = if (o.optString("mode") == "last") WheelMode.LAST else WheelMode.FIRST,
                auto = o.optBoolean("auto"),
                out = out,
                recent = (0 until rec.length()).map { rec.optString(it) }.filter { it.isNotBlank() }.take(10),
            )
        }
    }
}

/** The lowest colour index no option uses yet. */
internal fun nextWheelColor(used: Collection<Int>): Int {
    var c = 0
    while (c in used) c++
    return c
}

// ---- the shared spin: one pure function, the same on every phone

/** SplitMix64: a fixed mix, so every phone (and every app version) gets the same numbers from a seed. */
private fun mix(x: Long): Long {
    var z = x + 0x9E3779B97F4A7C15uL.toLong()
    z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
    z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
    return z xor (z ushr 31)
}

/** Where a spin lands, from its seed: the slice (0 until [n]) and the spot inside it (0.2–0.8, away from the edges). */
internal fun wheelTarget(seed: Long, n: Int): Pair<Int, Float> {
    val index = ((mix(seed) ushr 1) % n.coerceAtLeast(1)).toInt()
    val unit = (mix(seed + 1) ushr 11).toDouble() / (1L shl 53).toDouble()
    return index to (0.2 + 0.6 * unit).toFloat()
}

/** Whole extra turns before landing (4–6), from the same seed. */
internal fun wheelTurns(seed: Long): Int = 4 + ((mix(seed + 2) ushr 1) % 3).toInt()

/** A shared spin's data: the wheel as it is now (what's left, in order, with colours), mode and the out list. */
internal fun Wheel.toPayload(spinMs: Int): String {
    val outs = if (mode == WheelMode.LAST) outOptions else emptyList()
    return JSONObject()
        .put("name", name)
        .put("options", JSONArray(left.map { JSONObject().put("n", it.name).put("c", it.color) }))
        .put("mode", if (mode == WheelMode.LAST) "last" else "first")
        .put("auto", auto)
        .put("round", outs.size + 1)
        .put("out", JSONArray(outs.map { JSONObject().put("n", it.name).put("c", it.color) }))
        .put("ms", spinMs)
        .toString()
}

/** A shared spin's wheel, as a temporary wheel (never saved). Null when the payload isn't a wheel. */
internal fun wheelFromPayload(payload: String): Pair<Wheel, Int>? = runCatching {
    val o = JSONObject(payload)
    fun list(key: String): List<WheelOption> {
        val a = o.optJSONArray(key) ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { WheelOption(it.optString("n"), it.optInt("c")) }
    }
    val left = list("options")
    val outs = list("out")
    if (left.isEmpty()) return@runCatching null
    val wheel = Wheel(
        id = SHARED_WHEEL_ID,
        name = o.optString("name").ifBlank { "Wheel" },
        options = left + outs,
        mode = if (o.optString("mode") == "last") WheelMode.LAST else WheelMode.FIRST,
        auto = o.optBoolean("auto"),
        out = outs.indices.map { left.size + it },
    )
    wheel to o.optInt("ms", WheelStore.DEFAULT_SPIN_S * 1000).coerceIn(1_000, 15_000)
}.getOrNull()

internal const val SHARED_WHEEL_ID = -1L

/** The saved wheels (this phone only) and the spin-time setting. */
internal object WheelStore {
    private const val FILE = "decide_wheels.json"
    private const val PREF_SPIN = "wheel_spin_s"
    const val DEFAULT_SPIN_S = 5
    val SPIN_RANGE = 3..10

    private var app: Context? = null
    private val writer = Executors.newSingleThreadExecutor()

    var wheels by mutableStateOf<List<Wheel>>(emptyList())
        private set
    var currentId by mutableLongStateOf(0L)
        private set

    val current: Wheel get() = wheels.firstOrNull { it.id == currentId } ?: wheels.first()

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        load()
    }

    fun update(w: Wheel) {
        if (w.id == SHARED_WHEEL_ID) return
        wheels = wheels.map { if (it.id == w.id) w else it }
        save()
    }

    fun select(id: Long) {
        if (wheels.any { it.id == id }) {
            currentId = id
            save()
        }
    }

    /** A new empty wheel, now the current one. */
    fun add(name: String): Wheel {
        val w = Wheel(id = newId(), name = name.trim().take(WHEEL_NAME_MAX).ifBlank { "Wheel" }, options = emptyList())
        wheels = wheels + w
        currentId = w.id
        save()
        return w
    }

    fun delete(id: Long) {
        if (wheels.size <= 1) return
        val i = wheels.indexOfFirst { it.id == id }
        wheels = wheels.filter { it.id != id }
        if (currentId == id) currentId = wheels[(i - 1).coerceIn(0, wheels.lastIndex)].id
        save()
    }

    fun spinSeconds(context: Context): Int =
        context.getSharedPreferences(DECIDE_PREFS, Context.MODE_PRIVATE).getInt(PREF_SPIN, DEFAULT_SPIN_S).coerceIn(SPIN_RANGE)

    fun setSpinSeconds(context: Context, s: Int) {
        context.getSharedPreferences(DECIDE_PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_SPIN, s.coerceIn(SPIN_RANGE)).apply()
    }

    private fun newId(): Long {
        var id = System.currentTimeMillis()
        while (wheels.any { it.id == id }) id++
        return id
    }

    private fun firstRun(): Wheel = Wheel(
        id = 1L, name = "Lunch",
        options = listOf("Biryani", "Dosa", "Pizza", "Noodles", "Thali", "Parotta").mapIndexed { i, s -> WheelOption(s, i) },
    )

    private fun load() {
        val ctx = app ?: return
        val json = runCatching { JSONObject(File(ctx.filesDir, FILE).readText()) }.getOrNull()
        val list = json?.optJSONArray("wheels")?.let { a ->
            (0 until a.length()).mapNotNull { runCatching { Wheel.fromStore(a.getJSONObject(it)) }.getOrNull() }
        }.orEmpty().distinctBy { it.id }
        wheels = list.ifEmpty { listOf(firstRun()) }
        val cur = json?.optLong("current") ?: 0L
        currentId = if (wheels.any { it.id == cur }) cur else wheels.first().id
    }

    private fun save() {
        val ctx = app ?: return
        // Built here (on the main thread, from the current state); only the write is moved off it.
        val text = JSONObject().put("current", currentId).put("wheels", JSONArray(wheels.map { it.storeJson() })).toString()
        writer.execute {
            runCatching {
                val file = File(ctx.filesDir, FILE)
                val tmp = File(ctx.filesDir, "$FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }
}
