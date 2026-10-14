package com.agani.syncup.forest

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.SyncChange
import com.agani.syncup.data.TokenStore
import com.agani.syncup.sync.ItemSync
import com.agani.syncup.sync.SyncType
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tools → Forest's trees and calm minutes, in one small file on the phone. Signed in, [ForestSync]
 * keeps them on the user's other phones too. Seeds and new kinds of trees are worked out from the
 * trees and calm minutes themselves — nothing is bought, nothing to keep in step.
 */
object ForestStore {
    private const val FILE = "forest.json"
    private const val REST_DAYS = 7

    private lateinit var app: Context
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var all: List<Tree> = emptyList()
    private var allCalm: List<CalmDay> = emptyList()

    var trees by mutableStateOf<List<Tree>>(emptyList())
        private set
    var calm by mutableStateOf<List<CalmDay>>(emptyList())
        private set
    var version by mutableLongStateOf(0L)
        private set

    // ---- this phone's settings
    var goalMinutes by mutableIntStateOf(20)
        private set
    var lastGrowDays by mutableIntStateOf(7)
        private set
    var reminderOn by mutableStateOf(false)
        private set
    var reminderMinutes by mutableIntStateOf(21 * 60)
        private set
    var lastSound by mutableStateOf("RAIN")
        private set

    val ready: Boolean get() = ::app.isInitialized

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = prefs()
        goalMinutes = p.getInt("goal", 20)
        lastGrowDays = p.getInt("grow_days", 7)
        reminderOn = p.getBoolean("reminder_on", false)
        reminderMinutes = p.getInt("reminder_min", 21 * 60)
        lastSound = p.getString("sound", "RAIN") ?: "RAIN"
        load()
        ForestSync.attach(app)
    }

    private fun prefs() = app.getSharedPreferences("forest", Context.MODE_PRIVATE)

    private fun deviceId(): String = TokenStore(app).deviceId()

    // ------------------------------------------------------------------ what's derived
    fun tree(id: String): Tree? = trees.firstOrNull { it.id == id }

    fun growing(now: Long = System.currentTimeMillis()): List<Tree> = trees.filter { it.stage(now) != Stage.GROWN }.sortedBy { it.plantedMs }

    fun grownCount(now: Long = System.currentTimeMillis()): Int = trees.count { it.stage(now) == Stage.GROWN }

    /** Calm minutes per day, all phones together. */
    fun minutesByDay(): Map<Int, Int> = calm.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.minutes } }

    fun minutesOn(day: Int): Int = minutesByDay()[day] ?: 0

    fun totalMinutes(): Int = calm.sumOf { it.minutes }

    fun calmDays(): Int = minutesByDay().count { it.value > 0 }

    /** Calm days in a row up to today (or yesterday, when today has none yet). */
    fun streak(): Int {
        val m = minutesByDay()
        var d = FDays.today()
        if ((m[d] ?: 0) == 0) d = FDays.add(d, -1)
        var n = 0
        while ((m[d] ?: 0) > 0) {
            n++
            d = FDays.add(d, -1)
        }
        return n
    }

    private fun longestStreak(): Int {
        val days = minutesByDay().filter { it.value > 0 }.keys.sorted()
        var best = 0
        var run = 0
        var prev = 0
        for (d in days) {
            run = if (prev != 0 && FDays.add(prev, 1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return best
    }

    /** No calm time for a week: the forest rests (gold leaves) until the next session. */
    fun resting(): Boolean {
        val last = calm.filter { it.minutes > 0 }.maxOfOrNull { it.day } ?: return false
        return last < FDays.add(FDays.today(), -REST_DAYS) && trees.isNotEmpty()
    }

    /** A kind found through calm time gives its first seed. */
    fun found(s: Species): Boolean = when (s) {
        Species.NEEM -> true
        Species.MANGO -> grownCount() >= 1
        Species.GULMOHAR -> grownCount() >= 2
        Species.PEEPAL -> grownCount() >= 3
        Species.COCONUT -> grownCount() >= 5
        Species.JASMINE -> calmDays() >= 7
        Species.BANYAN -> grownCount() >= 10
        Species.BAMBOO -> longestStreak() >= 7
    }

    /** The lotus pond appears after 100 calm hours. */
    fun lotusPond(): Boolean = totalMinutes() >= 100 * 60

    /** Seeds in hand: the first gift + what trees of that kind dropped − what was planted. */
    fun seeds(s: Species): Int {
        val gift = if (!found(s)) 0 else if (s == Species.NEEM) 3 else 1
        val kind = trees.filter { it.species == s }
        return (gift + kind.sumOf { it.seedsGiven } - kind.size).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ changes
    fun plant(species: Species, growDays: Int): Tree? {
        if (seeds(species) <= 0) return null
        val t = Tree(species = species, growDays = growDays.coerceIn(1, 14), local = !ForestSync.signedIn)
        update { all = all + t }
        lastGrowDays = t.growDays
        prefs().edit().putInt("grow_days", t.growDays).apply()
        return t
    }

    /**
     * A calm session ended with [minutes] (whole minutes). They count for today, and as care for
     * [treeId]. Returns the stage before and after, for the "it grew" screen.
     */
    fun addCalm(treeId: String?, minutes: Int): Pair<Stage?, Stage?> {
        if (minutes <= 0) return null to null
        val now = System.currentTimeMillis()
        val key = "${FDays.today()}|${deviceId()}"
        var before: Stage? = null
        var after: Stage? = null
        update {
            val old = allCalm.firstOrNull { it.key == key }
            allCalm = allCalm.filterNot { it.key == key } + CalmDay(key, (old?.minutes ?: 0) + minutes, now, true, old?.local ?: !ForestSync.signedIn)
            all = all.map {
                if (it.id == treeId && !it.deleted) {
                    before = it.stage(now)
                    val t = it.copy(care = it.care + minutes, lastCareMs = now, updatedMs = now, dirty = true)
                    after = t.stage(now)
                    t
                } else {
                    it
                }
            }
        }
        observe()
        return before to after
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim().take(40)) }

    fun changeGrowDays(id: String, days: Int) = edit(id) { it.copy(growDays = days.coerceIn(1, 14)) }

    fun remove(id: String) = edit(id) { it.copy(deleted = true) }

    private fun edit(id: String, change: (Tree) -> Tree) {
        val now = System.currentTimeMillis()
        update { all = all.map { if (it.id == id) change(it).copy(updatedMs = now, dirty = true) else it } }
    }

    /**
     * Notice what time has done: stages reached (their dates are kept), the seed a tree drops
     * when it's grown, and its season's seeds once a year.
     */
    fun observe() {
        val now = System.currentTimeMillis()
        val month = FDays.month()
        val year = FDays.year()
        var changed = false
        val next = synchronized(lock) {
            all.map { t ->
                if (t.deleted) return@map t
                val s = t.stage(now)
                var u = t
                if (Stage.entries.filter { it.ordinal <= s.ordinal }.any { it !in u.stageMs }) {
                    u = u.copy(stageMs = u.stageMs + Stage.entries.filter { it.ordinal <= s.ordinal && it !in u.stageMs }.associateWith { now })
                }
                if (s == Stage.GROWN && u.seedsGiven == 0) u = u.copy(seedsGiven = 1)
                if (s == Stage.GROWN && month in t.species.season && u.seasonYear != year && u.seedsGiven >= 1) {
                    u = u.copy(seedsGiven = u.seedsGiven + 1, seasonYear = year)
                }
                if (u != t) {
                    changed = true
                    u.copy(updatedMs = now, dirty = true)
                } else {
                    t
                }
            }
        }
        if (changed) update { all = next }
    }

    fun changeGoal(min: Int) {
        goalMinutes = min
        prefs().edit().putInt("goal", min).apply()
    }

    fun changeReminder(on: Boolean, minutes: Int = reminderMinutes) {
        reminderOn = on
        reminderMinutes = minutes
        prefs().edit().putBoolean("reminder_on", on).putInt("reminder_min", minutes).apply()
        ForestReminder.schedule(app)
    }

    fun changeSound(name: String) {
        lastSound = name
        prefs().edit().putString("sound", name).apply()
    }

    // ------------------------------------------------------------------ for ForestSync
    // Only asked while signed in: what was grown signed out goes up to this account too (a forest
    // just grows — no question like the Calendar's).
    internal fun pendingChanges(limit: Int): List<SyncChange> = synchronized(lock) {
        val t = all.filter { it.dirty }.map {
            SyncChange("tree", it.id, if (it.deleted) null else JsonParser.parseString(it.syncJson().toString()).asJsonObject, it.updatedMs, it.deleted)
        }
        val c = allCalm.filter { it.dirty }.map {
            SyncChange("calmlog", it.key, JsonParser.parseString(JSONObject().put("min", it.minutes).toString()).asJsonObject, it.updatedMs, false)
        }
        (t + c).take(limit)
    }

    internal fun markUploaded(sent: List<SyncChange>) {
        if (sent.isEmpty()) return
        val done = sent.associate { "${it.kind}/${it.key}" to it.updatedMs }
        update(sync = false) {
            all = all.map { if (done["tree/${it.id}"] == it.updatedMs) it.copy(dirty = false, local = false) else it }
            allCalm = allCalm.map { if (done["calmlog/${it.key}"] == it.updatedMs) it.copy(dirty = false, local = false) else it }
        }
    }

    internal fun applyRemote(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        update(sync = false) {
            val trees = all.associateBy { it.id }.toMutableMap()
            val days = allCalm.associateBy { it.key }.toMutableMap()
            for (c in changes) {
                val data = c.data?.let { runCatching { JSONObject(it.toString()) }.getOrNull() } ?: JSONObject()
                when (c.kind) {
                    "tree" -> {
                        val old = trees[c.key]
                        if (old != null && old.updatedMs >= c.updatedMs) continue
                        trees[c.key] = Tree.fromJson(c.key, data, c.updatedMs, c.deleted)
                    }
                    "calmlog" -> {
                        val old = days[c.key]
                        if (old != null && old.updatedMs >= c.updatedMs) continue
                        days[c.key] = CalmDay(c.key, data.optInt("min"), c.updatedMs, dirty = false)
                    }
                }
            }
            all = trees.values.toList()
            allCalm = days.values.toList()
        }
    }

    /** First sign-in: the forest grown while signed out joins the account. A different account: the previous person's synced trees leave. */
    internal fun accountChanged(previous: String?) {
        update(sync = false) {
            if (previous != null) {
                all = all.filter { it.local }
                allCalm = allCalm.filter { it.local }
            }
            all = all.map { if (it.local) it.copy(local = false, dirty = true) else it }
            allCalm = allCalm.map { if (it.local) it.copy(local = false, dirty = true) else it }
        }
    }

    internal fun markAllDirty() {
        update(sync = false) {
            all = all.map { it.copy(dirty = true) }
            allCalm = allCalm.map { it.copy(dirty = true) }
        }
    }

    // ------------------------------------------------------------------ storage
    private fun update(sync: Boolean = true, block: () -> Unit) {
        synchronized(lock) {
            block()
            trees = all.filter { !it.deleted }
            calm = allCalm
            version++
        }
        saveJob?.cancel()
        saveJob = io.launch {
            delay(250)
            flush()
        }
        if (sync) ForestSync.requestSync()
    }

    fun flush() {
        val (t, c) = synchronized(lock) { all to allCalm }
        val json = JSONObject()
            .put("trees", JSONArray(t.map { it.storeJson() }))
            .put("calm", JSONArray(c.map { JSONObject().put("key", it.key).put("min", it.minutes).put("updated_ms", it.updatedMs).put("dirty", it.dirty).put("local", it.local) }))
        runCatching {
            val file = File(app.filesDir, FILE)
            val tmp = File(app.filesDir, "$FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load() {
        val json = runCatching { JSONObject(File(app.filesDir, FILE).readText()) }.getOrNull()
        synchronized(lock) {
            if (json != null) {
                val ts = json.optJSONArray("trees") ?: JSONArray()
                all = (0 until ts.length()).mapNotNull { runCatching { Tree.fromStore(ts.getJSONObject(it)) }.getOrNull() }
                val cs = json.optJSONArray("calm") ?: JSONArray()
                allCalm = (0 until cs.length()).mapNotNull { i ->
                    runCatching {
                        cs.getJSONObject(i).let { CalmDay(it.getString("key"), it.optInt("min"), it.optLong("updated_ms"), it.optBoolean("dirty"), it.optBoolean("local")) }
                    }.getOrNull()
                }
            }
            trees = all.filter { !it.deleted }
            calm = allCalm
            version++
        }
    }
}

/** Forest's own sync: trees and calm minutes (see [ItemSync]). */
object ForestSync : ItemSync("forest", SyncType.FOREST, listOf("tree", "calmlog")) {
    override suspend fun pending(limit: Int) = ForestStore.pendingChanges(limit)
    override suspend fun uploaded(sent: List<SyncChange>) = ForestStore.markUploaded(sent)
    override suspend fun applyRemote(changes: List<SyncChange>) = ForestStore.applyRemote(changes)
    override suspend fun markAllDirty() = ForestStore.markAllDirty()
    override suspend fun accountChanged(previous: String?, current: String) = ForestStore.accountChanged(previous)
}
