package com.agani.syncup.calendar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.ReminderStore
import com.agani.syncup.data.SyncChange
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
 * Tools → Calendar's to-dos and lists, kept in one small file on the phone (works offline and
 * signed out). Signed in, [CalendarSync] carries them to the user's other phones. Every change
 * re-sets the reminders ([CalendarReminders]).
 */
object CalendarStore {
    private const val FILE = "calendar.json"
    private const val TOMBSTONE_DAYS = 60L

    private lateinit var app: Context
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Every to-do, deleted ones too (their deletion still has to reach the other phones). */
    private var all: List<Task> = emptyList()
    private var allLists: List<TaskList> = TaskList.defaults

    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set
    var lists by mutableStateOf<List<TaskList>>(TaskList.defaults)
        private set
    /** Bumped on every change, for screens that compute from the lists. */
    var version by mutableLongStateOf(0L)
        private set

    // ---- this phone's settings (not synced)
    var remindersOn by mutableStateOf(true)
        private set
    var snoozeMinutes by mutableIntStateOf(10)
        private set
    var weekStartsMonday by mutableStateOf(true)
        private set

    val ready: Boolean get() = ::app.isInitialized

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = prefs()
        remindersOn = p.getBoolean("reminders_on", true)
        snoozeMinutes = p.getInt("snooze_min", 10)
        weekStartsMonday = p.getBoolean("week_monday", true)
        load()
        CalendarSync.attach(app)
    }

    private fun prefs() = app.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ reading
    fun task(id: String): Task? = tasks.firstOrNull { it.id == id }

    fun list(id: String): TaskList = lists.firstOrNull { it.id == id } ?: lists.firstOrNull() ?: TaskList.defaults.first()

    /** To-dos made while signed out that aren't in the account yet (asked about once signed in). */
    fun waitingLocal(): List<Task> = tasks.filter { it.local && !it.phoneOnly }

    // ------------------------------------------------------------------ changes from the screens
    fun save(task: Task) {
        val now = System.currentTimeMillis()
        update {
            val old = all.firstOrNull { it.id == task.id }
            val local = old?.local ?: !CalendarSync.signedIn
            val start = if (task.repeat.repeats) (task.start.takeIf { it > 0 && old?.day == task.day } ?: task.day) else task.day
            val t = task.copy(updatedMs = now, dirty = true, local = local, phoneOnly = old?.phoneOnly ?: false, start = start)
            all = all.filterNot { it.id == task.id } + t
        }
    }

    fun delete(id: String) {
        val now = System.currentTimeMillis()
        update { all = all.map { if (it.id == id) it.copy(deleted = true, updatedMs = now, dirty = true) else it } }
    }

    /** Back from the bin (Undo). */
    fun restore(task: Task) = save(task.copy(deleted = false))

    /**
     * Ticked. A repeating to-do moves on to its next date (its checklist starts fresh); the last
     * one, or a one-off, is done.
     */
    fun complete(id: String) {
        val t = all.firstOrNull { it.id == id && !it.deleted } ?: return
        val now = System.currentTimeMillis()
        val next = if (t.repeat.repeats && t.hasDate) t.repeat.next(t.day, t.start.takeIf { it > 0 } ?: t.day, t.doneCount) else null
        val updated = if (next != null) {
            t.copy(day = next, doneCount = t.doneCount + 1, checklist = t.checklist.map { it.copy(done = false) })
        } else {
            t.copy(done = true, doneMs = now)
        }
        save(updated)
    }

    fun uncomplete(id: String) {
        val t = all.firstOrNull { it.id == id } ?: return
        save(t.copy(done = false, doneMs = 0))
    }

    fun setStep(taskId: String, stepId: String, done: Boolean) {
        val t = all.firstOrNull { it.id == taskId } ?: return
        save(t.copy(checklist = t.checklist.map { if (it.id == stepId) it.copy(done = done) else it }))
    }

    fun saveList(list: TaskList) {
        val now = System.currentTimeMillis()
        update {
            val old = allLists.firstOrNull { it.id == list.id }
            val l = list.copy(updatedMs = now, dirty = true, local = old?.local ?: !CalendarSync.signedIn)
            allLists = allLists.filterNot { it.id == list.id } + l
        }
    }

    /** A list goes; its to-dos move to Personal. */
    fun deleteList(id: String) {
        if (id == "personal") return
        val now = System.currentTimeMillis()
        update {
            allLists = allLists.map { if (it.id == id) it.copy(deleted = true, updatedMs = now, dirty = true) else it }
            all = all.map { if (it.listId == id && !it.deleted) it.copy(listId = "personal", updatedMs = now, dirty = true) else it }
        }
    }

    /** Done to-dos older than a month leave for good. */
    fun clearOldDone() {
        val cutoff = System.currentTimeMillis() - 30L * 86_400_000
        val now = System.currentTimeMillis()
        update { all = all.map { if (it.done && !it.deleted && it.doneMs in 1 until cutoff) it.copy(deleted = true, updatedMs = now, dirty = true) else it } }
    }

    /** "Put them in my account": the signed-out to-dos join the account and go up. */
    fun moveLocalIntoAccount() {
        update {
            all = all.map { if (it.local && !it.phoneOnly) it.copy(local = false, dirty = true) else it }
            allLists = allLists.map { if (it.local) it.copy(local = false, dirty = true) else it }
        }
    }

    /** "Keep them only on this phone": never synced, never asked again. */
    fun keepLocalOnPhone() {
        update { all = all.map { if (it.local && !it.phoneOnly) it.copy(phoneOnly = true) else it } }
    }

    fun changeRemindersOn(on: Boolean) {
        remindersOn = on
        prefs().edit().putBoolean("reminders_on", on).apply()
        CalendarReminders.reschedule(app)
    }

    fun changeSnoozeMinutes(min: Int) {
        snoozeMinutes = min
        prefs().edit().putInt("snooze_min", min).apply()
    }

    fun changeWeekStartsMonday(on: Boolean) {
        weekStartsMonday = on
        prefs().edit().putBoolean("week_monday", on).apply()
    }

    // ------------------------------------------------------------------ SyncUp's own reminders, shown read-only
    class SyncUpReminder(val id: String, val title: String, val body: String, val linkUrl: String, val day: Int, val time: Int)

    /** Reminders sent by SyncUp (the admin) that fall between [from] and [to]. */
    fun syncupReminders(from: Int, to: Int): List<SyncUpReminder> {
        if (!::app.isInitialized) return emptyList()
        val out = ArrayList<SyncUpReminder>()
        for (r in runCatching { ReminderStore(app).load() }.getOrDefault(emptyList())) {
            if (r.scheduledAtMs <= 0) continue
            val first = Days.of(r.scheduledAtMs)
            val minutes = java.util.Calendar.getInstance().apply { timeInMillis = r.scheduledAtMs }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
            fun add(d: Int, t: Int = minutes) = out.add(SyncUpReminder(r.id, r.title.ifBlank { "Reminder" }, r.body, r.linkUrl, d, t))
            when {
                r.recurrence == "daily" -> {
                    var d = maxOf(first, from)
                    while (d <= to) {
                        add(d)
                        d = Days.add(d, 1)
                    }
                }
                r.repeatIntervalMs > 0 -> {
                    var k = 0L
                    while (k < 400 && (r.repeatCount == 0 || k < r.repeatCount)) {
                        val at = r.scheduledAtMs + k * r.repeatIntervalMs
                        val d = Days.of(at)
                        if (d > to) break
                        if (d >= from) add(d, java.util.Calendar.getInstance().apply { timeInMillis = at }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) })
                        k++
                    }
                }
                first in from..to -> add(first)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ for CalendarSync
    internal fun pendingChanges(limit: Int): List<SyncChange> = synchronized(lock) {
        val lists = allLists.filter { it.dirty && !it.local }.map {
            SyncChange("tasklist", it.id, if (it.deleted) null else JsonParser.parseString(it.syncJson().toString()).asJsonObject, it.updatedMs, it.deleted)
        }
        val tasks = all.filter { it.dirty && !it.local && !it.phoneOnly }.take(limit).map {
            SyncChange("task", it.id, if (it.deleted) null else JsonParser.parseString(it.syncJson().toString()).asJsonObject, it.updatedMs, it.deleted)
        }
        (lists + tasks).take(limit)
    }

    internal fun markUploaded(sent: List<SyncChange>) {
        if (sent.isEmpty()) return
        val done = sent.associate { "${it.kind}/${it.key}" to it.updatedMs }
        update(reschedule = false) {
            all = all.map { if (done["task/${it.id}"] == it.updatedMs) it.copy(dirty = false) else it }
            allLists = allLists.map { if (done["tasklist/${it.id}"] == it.updatedMs) it.copy(dirty = false) else it }
        }
    }

    internal fun applyRemote(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        update(sync = false) {
            val tasks = all.associateBy { it.id }.toMutableMap()
            val lists = allLists.associateBy { it.id }.toMutableMap()
            for (c in changes) {
                val data = c.data?.let { runCatching { JSONObject(it.toString()) }.getOrNull() } ?: JSONObject()
                when (c.kind) {
                    "task" -> {
                        val old = tasks[c.key]
                        if (old != null && old.updatedMs >= c.updatedMs) continue
                        tasks[c.key] = Task.fromJson(c.key, data, c.updatedMs, c.deleted).copy(dirty = false)
                    }
                    "tasklist" -> {
                        val old = lists[c.key]
                        if (old != null && old.updatedMs >= c.updatedMs) continue
                        lists[c.key] = TaskList.fromJson(c.key, data, c.updatedMs, c.deleted)
                    }
                }
            }
            all = tasks.values.toList()
            allLists = lists.values.toList()
        }
    }

    /** A different account signed in: the previous person's synced to-dos leave this phone. */
    internal fun dropAccountItems() {
        update {
            all = all.filter { it.local || it.phoneOnly }
            allLists = TaskList.defaults + allLists.filter { it.local && TaskList.defaults.none { d -> d.id == it.id } }
        }
    }

    internal fun markAllDirty() {
        update(reschedule = false) {
            all = all.map { if (!it.local && !it.phoneOnly) it.copy(dirty = true) else it }
            allLists = allLists.map { if (!it.local && it.updatedMs > 1) it.copy(dirty = true) else it }
        }
    }

    // ------------------------------------------------------------------ storage
    private fun update(reschedule: Boolean = true, sync: Boolean = reschedule, block: () -> Unit) {
        synchronized(lock) {
            block()
            publish()
        }
        scheduleSave()
        if (reschedule) CalendarReminders.reschedule(app)
        if (sync) CalendarSync.requestSync()
    }

    private fun publish() {
        // To-dos in Shopping (once a starting list) on a phone that never had it: the list comes back
        // here so they still show under it. Not uploaded unless the person changes the list.
        val shop = TaskList.oldShopping.id
        if (allLists.none { it.id == shop } && all.any { !it.deleted && it.listId == shop }) allLists = allLists + TaskList.oldShopping
        tasks = all.filter { !it.deleted }
        lists = allLists.filter { !it.deleted }.sortedWith(compareBy({ it.order }, { it.name }))
        version++
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = io.launch {
            delay(250)
            flush()
        }
    }

    /** Write now (alarms and notification buttons call this before the process may go). */
    fun flush() {
        val (t, l) = synchronized(lock) { all to allLists }
        val cutoff = System.currentTimeMillis() - TOMBSTONE_DAYS * 86_400_000
        val json = JSONObject()
            .put("tasks", JSONArray(t.filter { !(it.deleted && !it.dirty && it.updatedMs < cutoff) }.map { it.storeJson() }))
            .put("lists", JSONArray(l.map { it.storeJson() }))
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
        val file = File(app.filesDir, FILE)
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        synchronized(lock) {
            if (json != null) {
                val ts = json.optJSONArray("tasks") ?: JSONArray()
                all = (0 until ts.length()).mapNotNull { runCatching { Task.fromStore(ts.getJSONObject(it)) }.getOrNull() }
                val ls = json.optJSONArray("lists") ?: JSONArray()
                val loaded = (0 until ls.length()).mapNotNull { runCatching { TaskList.fromStore(ls.getJSONObject(it)) }.getOrNull() }
                allLists = TaskList.defaults.filter { d -> loaded.none { it.id == d.id } } + loaded
                // Shopping is its own tool now (Tools → Shopping): the old starting Shopping list goes,
                // as long as it was never changed and has no to-dos (it was never uploaded either).
                val shop = TaskList.oldShopping.id
                allLists = allLists.filterNot { l -> l.id == shop && l.updatedMs <= 1 && all.none { !it.deleted && it.listId == shop } }
            }
            publish()
        }
    }
}

/** Calendar's own sync: to-dos and lists, through the account sync (see [ItemSync]). */
object CalendarSync : ItemSync("calendar", SyncType.CALENDAR, listOf("task", "tasklist")) {
    override suspend fun pending(limit: Int) = CalendarStore.pendingChanges(limit)
    override suspend fun uploaded(sent: List<SyncChange>) = CalendarStore.markUploaded(sent)
    override suspend fun applyRemote(changes: List<SyncChange>) = CalendarStore.applyRemote(changes)
    override suspend fun markAllDirty() = CalendarStore.markAllDirty()
    override suspend fun accountChanged(previous: String?, current: String) {
        if (previous != null) CalendarStore.dropAccountItems()
    }
}
