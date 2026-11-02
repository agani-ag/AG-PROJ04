package com.agani.syncup.stationfiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationFilesTest {

    @Test
    fun a_station_file_keeps_the_key_and_path_never_the_address() {
        val uri = stationUri("abc123", "/files/Music/Sunset%20Drive.mp3?dl=1")
        assertEquals("syncup-station://abc123/files/Music/Sunset%20Drive.mp3?dl=1", uri)
        assertEquals("abc123" to "/files/Music/Sunset%20Drive.mp3?dl=1", parseStationUri(uri))
    }

    @Test
    fun other_addresses_are_not_station_files() {
        assertNull(parseStationUri("https://abc.trycloudflare.com/files/x.mp3"))
        assertNull(parseStationUri("syncup-station:///files/x.mp3"))
        assertNull(parseStationUri("content://media/external/audio/1"))
    }

    @Test
    fun files_address_is_https_on_the_same_host_as_the_audio() {
        val stream = "https://abc.trycloudflare.com/stream"
        assertEquals("https://abc.trycloudflare.com", filesBase("https://abc.trycloudflare.com/api/files/", stream, testBuild = false))
        assertNull(filesBase("https://elsewhere.example/api/files/", stream, testBuild = false))
        assertNull(filesBase("http://abc.trycloudflare.com/api/files/", stream, testBuild = false))
        assertNull(filesBase("", stream, testBuild = false))
    }

    @Test
    fun a_test_build_also_takes_http_on_the_phone_itself_only() {
        val stream = "http://127.0.0.1:8766/stream"
        assertEquals("http://127.0.0.1:8766", filesBase("http://127.0.0.1:8766/api/files/", stream, testBuild = true))
        assertNull(filesBase("http://127.0.0.1:8766/api/files/", stream, testBuild = false))
        assertNull(filesBase("http://192.168.1.7:8000/api/files/", "http://192.168.1.7:8000/stream", testBuild = true))
    }

    @Test
    fun wifi_address_is_plain_http_to_a_private_ipv4_address_and_port() {
        assertEquals("http://192.168.1.7:8000", lanBase("http://192.168.1.7:8000/"))
        assertEquals("http://10.0.2.2:8768", lanBase(" http://10.0.2.2:8768/ "))
        assertEquals("http://172.31.0.9:80", lanBase("http://172.31.0.9:80"))
        for (bad in listOf(
            "", "nonsense", "https://192.168.1.7:8000/", "http://192.168.1.7/", "http://8.8.8.8:8000/", "http://172.32.0.1:8000/",
            "http://127.0.0.1:8000/", "http://169.254.169.254:80/", "http://100.64.0.1:8000/", "http://example.com:8000/",
            "http://192.168.1.7.example.com:8000/", "http://user:pw@192.168.1.7:8000/", "http://[fd00::1]:8000/", "http://192.168.1.256:8000/",
        )) {
            assertNull(bad, lanBase(bad))
        }
    }

    @Test
    fun the_phone_only_looks_for_a_pc_on_its_own_network() {
        assertTrue(sameSubnet("192.168.1.7", "192.168.1.40", 24))
        assertFalse(sameSubnet("192.168.2.7", "192.168.1.40", 24))
        assertTrue(sameSubnet("192.168.2.7", "192.168.1.40", 16))
        assertTrue(sameSubnet("10.0.2.2", "10.0.2.16", 24))
        assertFalse(sameSubnet("192.168.1.7", "fe80::1", 64))
        assertFalse(sameSubnet("192.168.1.7", "", 24))
        // A link claiming half the internet isn't "the same Wi-Fi".
        assertFalse(sameSubnet("192.168.1.7", "10.0.0.1", 0))
    }

    @Test
    fun the_wifi_route_never_reuses_a_connection_audiosync_has_closed() {
        assertEquals(mapOf("Authorization" to "Bearer t", "Connection" to "close"), Stations.Resolved("http://192.168.1.7:8000/x", "t", direct = true).headers)
        assertEquals(mapOf("Connection" to "close"), Stations.Resolved("http://192.168.1.7:8000/x", null, direct = true).headers)
        assertEquals(mapOf("Authorization" to "Bearer t"), Stations.Resolved("https://abc.trycloudflare.com/x", "t").headers)
        assertEquals(emptyMap<String, String>(), Stations.Resolved("https://abc.trycloudflare.com/x", null).headers)
    }

    @Test
    fun folder_pages_ask_for_search_and_paging() {
        assertEquals("/api/files/Music/?offset=0&limit=200", listQuery("/api/files/Music/", "", 0, 200))
        assertEquals("/api/files/Music/?offset=200&limit=200&q=sun+set", listQuery("/api/files/Music/", " sun set ", 200, 200))
    }

    @Test
    fun dates_read_without_java_time() {
        assertEquals("1 Nov", shortDate("2026-11-01T10:00:00+00:00"))
        assertEquals("28 Oct", shortDate("2026-10-28T23:59:00+00:00"))
        assertNull(shortDate(null))
        assertNull(shortDate("bad"))
    }
}
