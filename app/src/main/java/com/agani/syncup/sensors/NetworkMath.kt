package com.agani.syncup.sensors

import kotlin.math.abs

internal enum class NetGrade(val word: String) { GREAT("Great"), GOOD("Good"), FAIR("Fair"), POOR("Poor") }

/** The sums and plain words behind Network test. */
internal object NetMath {
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** How much the replies wander: the average gap between one and the next. */
    fun jitter(values: List<Long>): Long {
        if (values.size < 2) return 0
        return values.zipWithNext { a, b -> abs(a - b) }.average().toLong()
    }

    /** The time the test server says it spent on a reply (its `dur=` values), which isn't the network's. */
    fun serverMs(headers: List<String>): Long =
        headers.sumOf { h -> Regex("""\bdur=([\d.]+)""").findAll(h).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 } }.toLong()

    /** [up] is null when the upload couldn't be measured: it's judged on the rest. */
    fun grade(down: Double, up: Double?, latency: Long, jitter: Long, replies: Int, sent: Int): NetGrade {
        val answered = if (sent > 0) replies.toDouble() / sent else 0.0
        return when {
            down < 1.5 || latency > 300 || answered < .8 -> NetGrade.POOR
            down < 5 || (up != null && up < 1) || latency > 150 || jitter > 50 || answered < .95 -> NetGrade.FAIR
            down < 50 || (up != null && up < 10) || latency > 40 || jitter > 15 -> NetGrade.GOOD
            else -> NetGrade.GREAT
        }
    }

    /** What this connection is good for: the name, and whether it should run smoothly. */
    fun uses(down: Double, up: Double?, latency: Long, jitter: Long): List<Pair<String, Boolean>> = listOf(
        "HD video" to (down >= 5),
        "Video calls" to (down >= 1.5 && (up == null || up >= 1.5) && latency < 150 && jitter < 30),
        "Online games" to (latency < 60 && jitter < 10),
        "Big downloads" to (down >= 25),
    )

    /** Wi-Fi signal as 0 to 4 bars, on the same steps as the Wi-Fi finder. */
    fun wifiBars(rssi: Int): Int = when {
        rssi >= -60 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }

    fun signalWord(bars: Int): String = when {
        bars >= 4 -> "Strong"
        bars == 3 -> "Good"
        bars == 2 -> "Okay"
        bars == 1 -> "Weak"
        else -> "Very weak"
    }

    fun mbpsText(mbps: Double): String = when {
        mbps >= 100 -> "%.0f".format(mbps)
        mbps >= 1 -> "%.1f".format(mbps)
        else -> "%.2f".format(mbps)
    }

    fun sizeText(bytes: Long): String = when {
        bytes < 1_000_000 -> "${bytes / 1_000} KB"
        bytes < 1_000_000_000 -> "${bytes / 1_000_000} MB"
        else -> "%.1f GB".format(bytes / 1e9)
    }
}

/**
 * Bytes so far against time. The result leaves out the first quarter: speed is still climbing then, and
 * on an upload the phone's own send buffer fills at once, which would read as speed the network never had.
 */
internal class SpeedMeter {
    private val samples = ArrayList<Pair<Long, Long>>()

    fun add(ms: Long, bytes: Long) {
        val last = samples.lastOrNull()
        if (last != null && ms <= last.first) samples[samples.size - 1] = last.first to maxOf(last.second, bytes)
        else samples += ms to bytes
    }

    /** The speed over about the last second, for the gauge. */
    fun live(): Double {
        val last = samples.lastOrNull() ?: return 0.0
        val from = samples.lastOrNull { last.first - it.first >= 1_000 } ?: samples.first()
        return between(from, last)
    }

    fun result(): Double {
        val last = samples.lastOrNull() ?: return 0.0
        val from = samples.firstOrNull { it.first >= last.first / 4 }?.takeIf { it !== last } ?: samples.first()
        return between(from, last)
    }

    private fun between(a: Pair<Long, Long>, b: Pair<Long, Long>): Double =
        if (b.first <= a.first) 0.0 else (b.second - a.second) * 8.0 / ((b.first - a.first) * 1_000.0)
}
