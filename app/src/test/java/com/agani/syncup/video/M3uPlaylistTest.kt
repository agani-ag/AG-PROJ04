package com.agani.syncup.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistTest {

    @Test
    fun a_pushed_channel_list_is_told_by_its_address() {
        for (yes in listOf("https://a.example/x.m3u", "https://a.example/x.M3U8", "https://a.example/x.m3u?token=1", "http://a.example/p/x.m3u8#t", " https://a.example/x.m3u ")) {
            assertTrue(yes, M3uPlaylist.isChannelLink(yes))
        }
        for (no in listOf("https://a.example/x.mp4", "https://a.example/m3u", "https://a.example/?f=x.m3u", "https://a.example/#x.m3u", "")) {
            assertFalse(no, M3uPlaylist.isChannelLink(no))
        }
    }

    @Test
    fun one_stream_saves_as_a_list_of_one_under_the_name_given() {
        val stream = M3uPlaylist.channelGroups(M3uResult.SingleStream("https://a.example/live.m3u8"), "My Stream")!!
        assertEquals(1, stream.sumOf { it.channels.size })
        assertEquals("My Stream", stream[0].channels[0].title)
        assertEquals("https://a.example/live.m3u8", stream[0].channels[0].url)
    }

    @Test
    fun a_one_channel_file_plays_its_own_stream_under_its_own_name() {
        val one = M3uPlaylist.channelGroups(M3uResult.SingleChannel("https://a.example/real.m3u8", "Real Name"), "Ignored")!!
        assertEquals("Real Name", one[0].channels[0].title)
        assertEquals("https://a.example/real.m3u8", one[0].channels[0].url)
    }

    @Test
    fun a_list_stays_as_it_is_and_anything_else_is_no_list() {
        val groups = listOf(TvCategoryGroup("News", "News", emptyList()))
        assertEquals(groups, M3uPlaylist.channelGroups(M3uResult.ChannelList(groups), "x"))
        assertNull(M3uPlaylist.channelGroups(M3uResult.NotAPlaylist, "x"))
    }

    @Test
    fun next_and_previous_go_round_the_channels_on_screen() {
        val (a, b, c) = listOf("a", "b", "c").map { TvChannel(it, it, "", "https://$it.example/live.m3u8", "News") }
        ChannelQueue.start(listOf(a, b, c), c)
        assertEquals("3 of 3", ChannelQueue.position)
        assertEquals(a, ChannelQueue.step(1))
        assertEquals("1 of 3", ChannelQueue.position)
        assertEquals(c, ChannelQueue.step(-1))
        assertEquals(b, ChannelQueue.step(-1))
    }

    @Test
    fun one_channel_has_nowhere_to_go() {
        val only = TvChannel("a", "a", "", "https://a.example/live.m3u8", "News")
        ChannelQueue.start(listOf(only), only)
        assertFalse(ChannelQueue.canStep)
        assertNull(ChannelQueue.step(1))
    }

    @Test
    fun a_blank_name_still_gives_the_channel_a_title() {
        assertEquals("Channel", M3uPlaylist.channelGroups(M3uResult.SingleStream("https://a.example/l.m3u8"), " ".trim())!![0].channels[0].title)
    }
}
