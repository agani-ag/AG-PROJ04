package com.agani.syncup.calendar

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

// ============================================================================ days
/**
 * A day is an Int like 20261014 (yyyymmdd) — sorts naturally, survives time zones, easy to sync.
 * java.time needs Android 8, so the maths goes through java.util.Calendar.
 */
object Days {
    fun of(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let(::fromCal)

    fun today(): Int = of(System.currentTimeMillis())

    fun fromCal(c: Calendar): Int = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)

    fun cal(day: Int): Calendar = Calendar.getInstance().apply {
        clear()
        set(day / 10000, day / 100 % 100 - 1, day % 100, 0, 0, 0)
    }

    /** Midnight of [day] plus [minutes], in this phone's time zone. */
    fun ms(day: Int, minutes: Int = 0): Long = cal(day).apply { add(Calendar.MINUTE, minutes) }.timeInMillis

    /**
     * [minutes] after midnight of [day] by the clock — below 0 is the day before, past 24 h the day
     * after. By the clock, so a summer-time change doesn't move a 9:00 reminder to 8:00 or 10:00.
     */
    fun at(day: Int, minutes: Int): Long {
        val m = minutes.mod(24 * 60)
        return cal(add(day, minutes.floorDiv(24 * 60))).apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
        }.timeInMillis
    }

    fun add(day: Int, n: Int): Int = fromCal(cal(day).apply { add(Calendar.DAY_OF_YEAR, n) })

    fun addMonths(day: Int, n: Int): Int {
        val c = cal(day)
        val want = day % 100
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, n)
        c.set(Calendar.DAY_OF_MONTH, minOf(want, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return fromCal(c)
    }

    /** 1 = Monday … 7 = Sunday. */
    fun weekday(day: Int): Int = (cal(day).get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    fun daysInMonth(day: Int): Int = cal(day).getActualMaximum(Calendar.DAY_OF_MONTH)

    fun firstOfMonth(day: Int): Int = day / 100 * 100 + 1

    /** Days from [a] to [b] (rounded, so a 23- or 25-hour summer-time day still counts as one). */
    fun between(a: Int, b: Int): Int = Math.round((ms(b, 12 * 60) - ms(a, 12 * 60)) / 86_400_000.0).toInt()

    val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val shortMonths = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val shortWeekdays = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val longWeekdays = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** "Wed 14 Oct" */
    fun short(day: Int): String = "${shortWeekdays[weekday(day) - 1]} ${day % 100} ${shortMonths[day / 100 % 100 - 1]}"

    /** "Today", "Tomorrow", "Yesterday" or "Wed 14 Oct". */
    fun friendly(day: Int, today: Int = today()): String = when (day) {
        today -> "Today"
        add(today, 1) -> "Tomorrow"
        add(today, -1) -> "Yesterday"
        else -> short(day)
    }

    /** "10:00 AM" */
    fun time(minutes: Int): String = "${clock(minutes)} ${if (minutes / 60 < 12) "AM" else "PM"}"

    /** "10:00" — no AM / PM. */
    fun clock(minutes: Int): String {
        val h = minutes / 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d".format(h12, minutes % 60)
    }

    /** "10:00 AM", "10:00 – 11:00 AM" (both before or both after noon), "11:30 AM – 1:00 PM". [end] -1 = none. */
    fun timeRange(start: Int, end: Int): String = when {
        end < 0 -> time(start)
        (start < 12 * 60) == (end < 12 * 60) -> "${clock(start)} – ${time(end)}"
        else -> "${time(start)} – ${time(end)}"
    }
}

// ============================================================================ repeat
/**
 * How a to-do repeats. [kind]: none, daily, weekdays, weekly, monthly, yearly, custom.
 * Custom: every [every] [unit] (day/week/month), on [weekdays] (bit 0 = Monday) for weeks;
 * ends never, on [endDay], or after [endCount] times.
 */
data class Repeat(
    val kind: String = "none",
    val every: Int = 1,
    val unit: String = "day",
    val weekdays: Int = 0,
    val endKind: String = "never",
    val endDay: Int = 0,
    val endCount: Int = 0,
) {
    val repeats: Boolean get() = kind != "none"

    /** The next occurrence after [day], or null when it has ended. [doneCount] = occurrences already done. */
    fun next(day: Int, start: Int, doneCount: Int = 0): Int? {
        if (!repeats) return null
        if (endKind == "count" && endCount > 0 && doneCount + 1 >= endCount) return null
        val n = when (kind) {
            "daily" -> Days.add(day, 1)
            "weekdays" -> {
                var d = Days.add(day, 1)
                while (Days.weekday(d) > 5) d = Days.add(d, 1)
                d
            }
            "weekly" -> Days.add(day, 7)
            "monthly" -> Days.addMonths(start, monthsBetween(start, day) + 1)
            "yearly" -> Days.addMonths(start, (monthsBetween(start, day) / 12 + 1) * 12)
            else -> when (unit) {
                "week" -> nextWeekly(day, start)
                "month" -> Days.addMonths(start, (monthsBetween(start, day) / every + 1) * every)
                else -> Days.add(day, every.coerceAtLeast(1))
            }
        }
        if (endKind == "date" && endDay > 0 && n > endDay) return null
        return n
    }

    private fun nextWeekly(day: Int, start: Int): Int {
        val mask = if (weekdays == 0) 1 shl (Days.weekday(start) - 1) else weekdays
        var d = Days.add(day, 1)
        repeat(7 * every.coerceAtLeast(1) + 7) {
            val weeksFromStart = Days.between(weekStart(start), weekStart(d)) / 7
            if (weeksFromStart % every.coerceAtLeast(1) == 0 && (mask shr (Days.weekday(d) - 1)) and 1 == 1) return d
            d = Days.add(d, 1)
        }
        return d
    }

    private fun weekStart(day: Int) = Days.add(day, -(Days.weekday(day) - 1))

    private fun monthsBetween(a: Int, b: Int): Int = (b / 10000 - a / 10000) * 12 + (b / 100 % 100 - a / 100 % 100)

    /** "Monthly" — for a to-do's row, where space is short. */
    val short: String
        get() = when (kind) {
            "none" -> ""
            "daily" -> "Daily"
            "weekdays" -> "Weekdays"
            "weekly" -> "Weekly"
            "monthly" -> "Monthly"
            "yearly" -> "Yearly"
            else -> if (every == 1) "Every $unit" else "Every $every ${unit}s"
        }

    /** "Every month on the 15th" — worded for [day]. */
    fun label(day: Int): String {
        val wd = if (day > 0) Days.longWeekdays[Days.weekday(day) - 1] else ""
        val base = when (kind) {
            "none" -> "Doesn't repeat"
            "daily" -> "Every day"
            "weekdays" -> "Weekdays"
            "weekly" -> if (day > 0) "Every week on $wd" else "Every week"
            "monthly" -> if (day > 0) "Every month on the ${ordinal(day % 100)}" else "Every month"
            "yearly" -> if (day > 0) "Every year on ${day % 100} ${Days.monthNames[day / 100 % 100 - 1]}" else "Every year"
            else -> {
                val u = if (every == 1) unit else "${unit}s"
                val days = if (unit == "week" && weekdays != 0) " on " + (0..6).filter { (weekdays shr it) and 1 == 1 }.joinToString(", ") { Days.shortWeekdays[it] } else ""
                (if (every == 1) "Every $u" else "Every $every $u") + days
            }
        }
        return base + when (endKind) {
            "date" -> if (endDay > 0) ", until ${Days.short(endDay)}" else ""
            "count" -> if (endCount > 0) ", $endCount times" else ""
            else -> ""
        }
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("every", every).put("unit", unit).put("weekdays", weekdays)
        .put("end", endKind).put("end_day", endDay).put("end_count", endCount)

    companion object {
        fun from(o: JSONObject?): Repeat = if (o == null) Repeat() else Repeat(
            o.optString("kind", "none"), o.optInt("every", 1), o.optString("unit", "day"), o.optInt("weekdays", 0),
            o.optString("end", "never"), o.optInt("end_day", 0), o.optInt("end_count", 0),
        )

        fun ordinal(n: Int): String = n.toString() + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    }
}

// ============================================================================ to-dos and lists
data class CheckItem(val id: String, val text: String, val done: Boolean = false)

/**
 * One to-do. [day] 0 = no date; [time] minutes after midnight, -1 = all day; [endTime] -1 = none.
 * [reminders]: minutes before the time (all-day ones count from 9:00 on the day, and may be below 0 =
 * later that day) — see [Reminders].
 * For a repeating to-do, [day] is the occurrence that's up next; [start] the first one.
 *
 * Not synced, this phone only: [dirty] (waiting to go up), [local] (made while signed out, not in
 * an account yet), [phoneOnly] (the person chose to keep it off their account).
 */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val notes: String = "",
    val listId: String = "personal",
    val day: Int = 0,
    val time: Int = -1,
    val endTime: Int = -1,
    val start: Int = 0,
    val repeat: Repeat = Repeat(),
    val reminders: List<Int> = emptyList(),
    val checklist: List<CheckItem> = emptyList(),
    val done: Boolean = false,
    val doneMs: Long = 0,
    val doneCount: Int = 0,
    val createdMs: Long = System.currentTimeMillis(),
    val updatedMs: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val local: Boolean = false,
    val phoneOnly: Boolean = false,
) {
    val allDay: Boolean get() = time < 0
    val hasDate: Boolean get() = day > 0

    /** When the to-do starts, for reminders (all day = 9:00). */
    fun startMs(onDay: Int = day): Long = Days.at(onDay, if (time >= 0) time else ALL_DAY_REMINDER_AT)

    /**
     * Its day and time in one line, for [onDay] (one of its days): "Today, 10:00 AM",
     * "Today, 10:00 – 11:00 AM", "Mon 2 Nov, 9:30 AM", "Sat 24 Oct · All day". "" without a date.
     */
    fun whenText(onDay: Int = day, today: Int = Days.today()): String {
        if (onDay <= 0) return ""
        val d = Days.friendly(onDay, today)
        return if (allDay) "$d · All day" else "$d, ${Days.timeRange(time, endTime)}"
    }

    /** Every day it falls on between [from] and [to] (inclusive) — repeats included. */
    fun occurrences(from: Int, to: Int, limit: Int = 400): List<Int> {
        if (!hasDate || deleted) return emptyList()
        if (!repeat.repeats) return if (day in from..to) listOf(day) else emptyList()
        val out = ArrayList<Int>()
        var d = day
        var count = doneCount
        var guard = 0
        while (d <= to && guard++ < limit) {
            if (d >= from) out.add(d)
            d = repeat.next(d, start.takeIf { it > 0 } ?: day, count) ?: break
            count++
        }
        return out
    }

    fun syncJson(): JSONObject = JSONObject()
        .put("title", title).put("notes", notes).put("list", listId).put("day", day).put("time", time).put("end_time", endTime)
        .put("start", start).put("repeat", repeat.toJson())
        .put("reminders", JSONArray(reminders))
        .put("checklist", JSONArray(checklist.map { JSONObject().put("id", it.id).put("text", it.text).put("done", it.done) }))
        .put("done", done).put("done_ms", doneMs).put("done_count", doneCount).put("created_ms", createdMs)

    fun storeJson(): JSONObject = syncJson().put("id", id).put("updated_ms", updatedMs).put("deleted", deleted)
        .put("dirty", dirty).put("local", local).put("phone_only", phoneOnly)

    companion object {
        /** All-day to-dos remind at 9:00 on the day (and "1 day before" = 9:00 the day before). */
        const val ALL_DAY_REMINDER_AT = 9 * 60

        fun fromJson(id: String, o: JSONObject, updatedMs: Long, deleted: Boolean): Task {
            val rem = o.optJSONArray("reminders")
            val cl = o.optJSONArray("checklist")
            return Task(
                id = id,
                title = o.optString("title"),
                notes = o.optString("notes"),
                listId = o.optString("list", "personal"),
                day = o.optInt("day", 0),
                time = o.optInt("time", -1),
                endTime = o.optInt("end_time", -1),
                start = o.optInt("start", 0),
                repeat = Repeat.from(o.optJSONObject("repeat")),
                reminders = if (rem == null) emptyList() else (0 until rem.length()).map { rem.optInt(it) },
                checklist = if (cl == null) emptyList() else (0 until cl.length()).mapNotNull { i ->
                    cl.optJSONObject(i)?.let { CheckItem(it.optString("id", UUID.randomUUID().toString()), it.optString("text"), it.optBoolean("done")) }
                },
                done = o.optBoolean("done"),
                doneMs = o.optLong("done_ms"),
                doneCount = o.optInt("done_count"),
                createdMs = o.optLong("created_ms", updatedMs),
                updatedMs = updatedMs,
                deleted = deleted,
            )
        }

        fun fromStore(o: JSONObject): Task = fromJson(o.optString("id"), o, o.optLong("updated_ms"), o.optBoolean("deleted"))
            .copy(dirty = o.optBoolean("dirty"), local = o.optBoolean("local"), phoneOnly = o.optBoolean("phone_only"))
    }
}

// ============================================================================ reminders
/**
 * A to-do's reminders are kept as minutes before it starts — all day: before 9:00 on the day
 * ([Task.ALL_DAY_REMINDER_AT]). So a reminder repeats with its to-do, and older SyncUp versions (and
 * the server, which keeps a to-do as it comes) read every one as it is.
 *
 * The quick choices are [TIMED] / [ALL_DAY]; anything else was set with "Custom…": any number of
 * minutes, hours, days or weeks before, or an exact moment turned into minutes before. Only an
 * all-day to-do's may be below 0 — later on the day than 9:00 (-510 = 5:30 PM).
 */
object Reminders {
    private const val DAY = 24 * 60
    private const val WEEK = 7 * DAY

    val TIMED = listOf(0, 5, 10, 15, 30, 60, 120, DAY, 2 * DAY)
    val ALL_DAY = listOf(0, DAY, 2 * DAY, WEEK)

    fun presets(allDay: Boolean): List<Int> = if (allDay) ALL_DAY else TIMED

    /** Set with "Custom…" (or kept when the to-do changed between all day and a time). */
    fun isCustom(t: Task, m: Int): Boolean = m !in presets(t.allDay)

    /** What reminders count back from: the to-do's time, or 9:00 when it's all day. */
    private fun anchor(t: Task): Int = if (t.time >= 0) t.time else Task.ALL_DAY_REMINDER_AT

    /** The day reminder [m] goes off, and the minute of that day — for the to-do on [onDay]. */
    fun moment(t: Task, m: Int, onDay: Int = t.day): Pair<Int, Int> {
        val total = anchor(t) - m
        return Days.add(onDay, total.floorDiv(DAY)) to total.mod(DAY)
    }

    /** When reminder [m] goes off for the to-do on [onDay] (any [m], below 0 too). */
    fun atMs(t: Task, m: Int, onDay: Int = t.day): Long = Days.at(onDay, anchor(t) - m)

    /** The other way round: a reminder at [minute] on [momentDay], as minutes before the to-do on [onDay]. */
    fun offset(t: Task, momentDay: Int, minute: Int, onDay: Int = t.day): Int =
        anchor(t) - (Days.between(onDay, momentDay) * DAY + minute)

    /** A reminder comes at or before the start — all day: any time up to the end of the day. */
    fun allowed(t: Task, m: Int): Boolean = if (t.allDay) m > Task.ALL_DAY_REMINDER_AT - DAY else m >= 0

    /** "10 min before", "3 hours before", "1 day before", "2 weeks before", "1 h 30 min before" ([m] above 0). */
    fun duration(m: Int): String = when {
        m % WEEK == 0 -> count(m / WEEK, "week") + " before"
        m % DAY == 0 -> count(m / DAY, "day") + " before"
        m % 60 == 0 -> count(m / 60, "hour") + " before"
        m < 60 -> "$m min before"
        else -> "${m / 60} h ${m % 60} min before"
    }

    private fun count(n: Int, unit: String) = "$n $unit" + if (n == 1) "" else "s"

    /** Reads well as an amount of time: under an hour, whole hours up to two days, whole days. */
    private fun plain(t: Task, m: Int): Boolean =
        if (t.allDay) m > 0 && m % DAY == 0 else m in 1..59 || (m > 0 && m % 60 == 0 && (m <= 2 * DAY || m % DAY == 0))

    /** "On the day, 5:30 PM", "1 day before, 8:00 PM", "2 weeks before, 9:00 AM". */
    private fun daysBefore(days: Int, minute: Int): String = when {
        days <= 0 -> "On the day, ${Days.time(minute)}"
        days % 7 == 0 -> count(days / 7, "week") + " before, ${Days.time(minute)}"
        else -> count(days, "day") + " before, ${Days.time(minute)}"
    }

    /**
     * Reminder [m] in words, for the editor. Quick choices as always ("At 9:30 AM", "10 min before",
     * "1 day before, 9:00 AM"); custom ones naturally ("3 hours before", "1 day before, 8:00 PM") — on a
     * to-do that doesn't repeat, a moment on another day reads as itself ("Sun 1 Nov, 8:00 PM").
     */
    fun label(t: Task, m: Int): String {
        val day = t.day.takeIf { it > 0 } ?: Days.today()
        val (momentDay, minute) = moment(t, m, day)
        val days = Days.between(momentDay, day)
        val plain = plain(t, m)
        return when {
            !t.repeat.repeats && t.hasDate && days > 0 && isCustom(t, m) && !plain -> "${Days.friendly(momentDay)}, ${Days.time(minute)}"
            t.allDay -> daysBefore(days, minute)
            m == 0 -> "At ${Days.time(t.time)}"
            plain || days == 0 -> duration(m)
            else -> daysBefore(days, minute)
        }
    }

    /** Reminder [m] on a to-do's row, where space is short: "At the time", "10 min before", "5:30 PM" (all day, on the day). */
    fun short(t: Task, m: Int): String = if (!t.allDay && m == 0) "At the time" else label(t, m).removePrefix("On the day, ")

    /** "The evening before", "3 days before", "1 h 30 min before" — how [m] stands to the to-do, for the custom sheet. */
    fun phrase(t: Task, m: Int): String {
        val (momentDay, minute) = moment(t, m)
        val days = Days.between(momentDay, t.day)
        return when {
            days <= 0 -> if (t.allDay) "On the day" else if (m <= 0) "At the time" else duration(m).replaceFirstChar { it.uppercase() }
            days == 1 -> "The " + (if (minute < 12 * 60) "morning" else if (minute < 17 * 60) "afternoon" else "evening") + " before"
            days % 7 == 0 -> count(days / 7, "week") + " before"
            else -> count(days, "day") + " before"
        }
    }
}

data class TaskList(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Long,
    val order: Int = 0,
    val updatedMs: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val local: Boolean = false,
) {
    fun syncJson(): JSONObject = JSONObject().put("name", name).put("color", color).put("order", order)

    fun storeJson(): JSONObject = syncJson().put("id", id).put("updated_ms", updatedMs).put("deleted", deleted).put("dirty", dirty).put("local", local)

    companion object {
        fun fromJson(id: String, o: JSONObject, updatedMs: Long, deleted: Boolean) =
            TaskList(id, o.optString("name", "List"), o.optLong("color", 0xFF4F46E5), o.optInt("order"), updatedMs, deleted, dirty = false)

        fun fromStore(o: JSONObject) = fromJson(o.optString("id"), o, o.optLong("updated_ms"), o.optBoolean("deleted"))
            .copy(dirty = o.optBoolean("dirty"), local = o.optBoolean("local"))

        /** The three lists everyone starts with. Fixed ids, so two phones never make them twice. */
        val defaults = listOf(
            TaskList("personal", "Personal", 0xFF4F46E5, 0, updatedMs = 1, dirty = false),
            TaskList("work", "Work", 0xFFEA580C, 1, updatedMs = 1, dirty = false),
            TaskList("shopping", "Shopping", 0xFF16A34A, 2, updatedMs = 1, dirty = false),
        )

        /** Colours to pick from for a new list. */
        val palette = listOf(0xFF4F46E5, 0xFFEA580C, 0xFF16A34A, 0xFFDB2777, 0xFF0891B2, 0xFFCA8A04, 0xFF7C3AED, 0xFF64748B)
    }
}
