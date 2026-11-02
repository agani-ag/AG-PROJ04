package com.agani.syncup.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMathTest {

    @Test
    fun the_middle_reply_ignores_one_slow_one() {
        assertEquals(20L, NetMath.median(listOf(18, 20, 900)))
        assertEquals(19L, NetMath.median(listOf(18, 20)))
        assertEquals(0L, NetMath.median(emptyList()))
    }

    @Test
    fun jitter_is_the_average_gap_between_replies() {
        assertEquals(10L, NetMath.jitter(listOf(20, 30, 20)))
        assertEquals(0L, NetMath.jitter(listOf(20)))
    }

    @Test
    fun the_servers_own_time_comes_off_the_latency() {
        val cloudflare = listOf("cfSpeedEdge;dur=6, cfSpeedWorker;dur=27", "cfL4;desc=\"?proto=TCP&rtt=9379\"")
        assertEquals(33L, NetMath.serverMs(cloudflare))
        assertEquals(1L, NetMath.serverMs(listOf("a;dur=1.5")))
        assertEquals(0L, NetMath.serverMs(emptyList()))
    }

    @Test
    fun health_grades() {
        assertEquals(NetGrade.GREAT, NetMath.grade(86.4, 24.1, 18, 3, 20, 20))
        assertEquals(NetGrade.GOOD, NetMath.grade(42.7, 11.3, 46, 14, 20, 20))
        assertEquals(NetGrade.FAIR, NetMath.grade(3.0, 1.0, 80, 10, 20, 20))
        assertEquals(NetGrade.POOR, NetMath.grade(0.8, 0.2, 400, 60, 12, 20))
    }

    @Test
    fun a_fast_line_that_loses_replies_isnt_great() {
        assertEquals(NetGrade.FAIR, NetMath.grade(200.0, 50.0, 10, 2, 18, 20))
        assertEquals(NetGrade.POOR, NetMath.grade(200.0, 50.0, 10, 2, 10, 20))
    }

    @Test
    fun a_missing_upload_is_judged_on_the_rest() {
        assertEquals(NetGrade.GREAT, NetMath.grade(86.4, null, 18, 3, 20, 20))
        assertTrue(NetMath.uses(86.4, null, 18, 3).first { it.first == "Video calls" }.second)
    }

    @Test
    fun what_a_connection_is_good_for() {
        assertTrue(NetMath.uses(86.4, 24.1, 18, 3).all { it.second })
        val sim = NetMath.uses(42.7, 11.3, 46, 14).toMap()
        assertFalse(sim.getValue("Online games"))
        assertTrue(sim.getValue("HD video"))
        assertTrue(sim.getValue("Video calls"))
        assertTrue(sim.getValue("Big downloads"))
    }

    @Test
    fun signal_words_follow_the_bars() {
        assertEquals(4, NetMath.wifiBars(-52))
        assertEquals(3, NetMath.wifiBars(-65))
        assertEquals(0, NetMath.wifiBars(-90))
        assertEquals("Strong", NetMath.signalWord(4))
        assertEquals("Good", NetMath.signalWord(3))
        assertEquals("Very weak", NetMath.signalWord(0))
    }

    @Test
    fun speeds_and_sizes_read_plainly() {
        assertEquals("86.4", NetMath.mbpsText(86.44))
        assertEquals("120", NetMath.mbpsText(120.4))
        assertEquals("0.05", NetMath.mbpsText(0.05))
        assertEquals("900 KB", NetMath.sizeText(900_000))
        assertEquals("48 MB", NetMath.sizeText(48_000_000))
        assertEquals("1.2 GB", NetMath.sizeText(1_200_000_000))
    }

    @Test
    fun the_slow_start_doesnt_count() {
        // 2 Mbps for the first 2 seconds, then 100 Mbps until 10 seconds.
        val m = SpeedMeter()
        var bytes = 0L
        m.add(0, 0)
        for (t in 1..10) {
            bytes += if (t <= 2) 250_000 else 12_500_000
            m.add(t * 1_000L, bytes)
        }
        assertEquals(100.0, m.result(), 1.0)
    }

    @Test
    fun an_upload_buffer_filling_at_once_doesnt_inflate_the_speed() {
        // 4 MB vanish into the phone's send buffer at once; the network really manages 1 Mbps.
        val m = SpeedMeter()
        m.add(0, 0)
        for (t in 1..8) m.add(t * 1_000L, 4_000_000L + t * 125_000L)
        assertEquals(1.0, m.result(), .01)
    }

    @Test
    fun the_gauge_follows_the_last_second() {
        val m = SpeedMeter()
        m.add(0, 0)
        m.add(1_000, 1_000_000)
        m.add(2_000, 13_500_000)
        assertEquals(100.0, m.live(), .01)
    }

    @Test
    fun a_very_fast_finish_still_gives_a_speed() {
        val m = SpeedMeter()
        m.add(0, 0)
        m.add(100, 35_000_000)
        assertEquals(2_800.0, m.result(), 1.0)
    }

    @Test
    fun a_late_reading_at_the_same_moment_is_kept() {
        val m = SpeedMeter()
        m.add(0, 0)
        m.add(1_000, 1_000_000)
        m.add(1_000, 2_000_000)
        assertEquals(16.0, m.result(), .01)
    }

    @Test
    fun nothing_moved_means_no_speed() {
        assertEquals(0.0, SpeedMeter().result(), 0.0)
        assertEquals(0.0, SpeedMeter().live(), 0.0)
        val still = SpeedMeter()
        still.add(0, 0)
        still.add(1_000, 0)
        assertEquals(0.0, still.result(), 0.0)
    }
}
