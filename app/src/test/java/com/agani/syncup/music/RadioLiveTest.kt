package com.agani.syncup.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RadioLiveTest {
    private val stream = "https://calm-river.trycloudflare.com/stream"

    @Test
    fun `opens wss on the same host as the audio`() {
        val ws = "wss://calm-river.trycloudflare.com/meta"
        assertEquals(ws, liveSocketUrl(stream, ws))
        assertEquals(ws, liveSocketUrl(stream, "  $ws "))
    }

    @Test
    fun `stays on the polled list otherwise`() {
        assertNull(liveSocketUrl(stream, null))
        assertNull(liveSocketUrl(stream, ""))
        assertNull("no cleartext", liveSocketUrl(stream, "ws://calm-river.trycloudflare.com/meta"))
        assertNull("another host", liveSocketUrl(stream, "wss://somewhere-else.example.com/meta"))
        assertNull("not an address", liveSocketUrl(stream, "wss://bad host/meta"))
    }

    @Test
    fun `only a test build may use cleartext, and only on this phone`() {
        val local = "http://127.0.0.1:8765/stream"
        val ws = "ws://127.0.0.1:8765/meta"
        assertNull(liveSocketUrl(local, ws, testBuild = false))
        assertEquals(ws, liveSocketUrl(local, ws, testBuild = true))
        assertNull(liveSocketUrl(stream, "ws://calm-river.trycloudflare.com/meta", testBuild = true))
    }

    @Test
    fun `reads AudioSync's message`() {
        val live = parseStationLive(
            """{"type":"now_playing","title":"Golden Hour","listeners":12,"playing":true,"at":"2026-11-01T17:05:00.123456+00:00"}""",
        )
        assertEquals(StationLive("Golden Hour", 12, true), live)
    }

    @Test
    fun `a stopped station has no title`() {
        val live = parseStationLive("""{"type":"now_playing","title":"","listeners":0,"playing":false}""")!!
        assertEquals("", live.title)
        assertFalse(live.playing)
    }

    @Test
    fun `ignores anything else`() {
        assertNull(parseStationLive("""{"type":"something_new","title":"x"}"""))
        assertNull(parseStationLive("not json"))
        assertNull(parseStationLive("""{"type":"now_playing","listeners":"many"}"""))
    }

    @Test
    fun `keeps odd values in range`() {
        val live = parseStationLive("""{"type":"now_playing","title":"${"x".repeat(500)}","listeners":-3}""")!!
        assertEquals(300, live.title.length)
        assertEquals(0, live.listeners)
    }
}
