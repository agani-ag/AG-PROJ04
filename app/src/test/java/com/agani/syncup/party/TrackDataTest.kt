package com.agani.syncup.party

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** The piece store behind Party and Watch together: pieces in, reads out, and the rolling cache for big videos. */
class TrackDataTest {
    private lateinit var dir: File
    private val piece = 1024
    private val seg = TrackData.SEG_PIECES * piece // 16 KB segment files in these tests

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("trackdata").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun bytes(n: Int) = Random(7).nextBytes(n)

    private fun fill(d: TrackData, all: ByteArray, from: Int = 0, to: Int = d.pieceCount) {
        for (i in from until to) {
            val off = i * piece
            d.write(off.toLong(), all, off, minOf(piece, all.size - off))
        }
    }

    private fun readAll(d: TrackData, from: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = d.read(from + got, out, got, len - got)
            check(n > 0)
            got += n
        }
        return out
    }

    @Test
    fun party_songs_read_back_exactly_as_before() {
        val all = bytes(10 * piece + 300)
        val d = TrackData.incomingIn(dir, 1, all.size.toLong(), piece, rolling = false)
        fill(d, all)
        assertArrayEquals(all, readAll(d, 0, all.size))
        assertTrue(d.complete)
        d.close()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun a_big_video_keeps_only_whats_around_the_reading_point() {
        val all = bytes(20 * seg) // 20 segment files' worth
        val d = TrackData.incomingIn(dir, 2, all.size.toLong(), piece, rolling = true, keepBehind = 2L * seg)
        fill(d, all)
        assertEquals(20, dir.listFiles()!!.size)
        // Playing on to segment 10: segments well behind it go, and their pieces count as missing again.
        assertArrayEquals(all.copyOfRange(10 * seg, 10 * seg + 500), readAll(d, 10L * seg, 500))
        assertFalse(d.hasPiece(0))
        assertFalse(d.hasPiece(7 * TrackData.SEG_PIECES))
        assertTrue(d.hasPiece(8 * TrackData.SEG_PIECES)) // kept: within keepBehind
        assertTrue(d.hasPiece(19 * TrackData.SEG_PIECES))
        assertEquals(12, dir.listFiles()!!.size)
        assertFalse(d.pieces().get(0))
        d.close()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun reading_the_index_at_the_end_drops_nothing() {
        val all = bytes(20 * seg)
        val d = TrackData.incomingIn(dir, 3, all.size.toLong(), piece, rolling = true, keepBehind = 2L * seg)
        fill(d, all)
        // A video's index is often at the end; the player reads it before starting from the beginning.
        readAll(d, all.size - 300L, 300)
        assertTrue(d.hasPiece(0))
        assertEquals(20, dir.listFiles()!!.size)
        d.close()
    }

    @Test
    fun a_seek_back_asks_for_the_dropped_piece_and_reads_it_once_it_comes_again() {
        val all = bytes(20 * seg)
        val d = TrackData.incomingIn(dir, 4, all.size.toLong(), piece, rolling = true, keepBehind = 2L * seg)
        fill(d, all)
        readAll(d, 12L * seg, 10)
        assertFalse(d.hasPiece(0))
        val asked = mutableListOf<Int>()
        d.onMissing = { p -> synchronized(asked) { asked.add(p) } }
        var back: ByteArray? = null
        val reader = Thread { back = readAll(d, 0, 100) }
        reader.start()
        Thread.sleep(300)
        assertEquals(listOf(0), synchronized(asked) { asked.toList() })
        d.write(0, all, 0, piece) // the host sends it again
        reader.join(5_000)
        assertArrayEquals(all.copyOfRange(0, 100), back)
        assertTrue(d.lastRead < 100) // the reading point the guest reports to the host is back near the start
        d.close()
    }

    @Test
    fun pieces_that_dont_line_up_are_ignored() {
        val all = bytes(4 * piece)
        val d = TrackData.incomingIn(dir, 5, all.size.toLong(), piece, rolling = true)
        d.write(100, all, 0, piece)
        assertFalse(d.hasPiece(0))
        d.close()
    }
}
