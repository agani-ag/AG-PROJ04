package com.agani.syncup.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadLinkTest {
    private fun task(url: String, link: String = url, work: Boolean = false) = DownloadTask(
        id = 1, url = url, fileName = "a.pdf", mime = "application/pdf", total = 10, source = "site.com", work = work,
        incognito = false, userAgent = "", referer = "", createdAt = 0, link = link,
    )

    @Test
    fun the_link_stays_the_one_it_began_from_after_a_redirect() {
        val t = task("https://site.com/get/report.pdf")
        t.url = "https://cdn.site.com/report.pdf?signature=abc&expires=123"
        assertEquals("https://site.com/get/report.pdf", t.link)
        assertEquals("https://site.com/get/report.pdf", t.copyableLink)
    }

    @Test
    fun a_download_that_never_said_otherwise_links_to_its_own_address() {
        val t = DownloadTask(1, "https://site.com/a.pdf", "a.pdf", "", 10, "", false, false, "", "", 0)
        assertEquals("https://site.com/a.pdf", t.copyableLink)
    }

    @Test
    fun a_syncup_links_address_stays_hidden() {
        assertNull(task("https://partner.example/secret.pdf", work = true).copyableLink)
    }

    @Test
    fun the_trail_knows_where_a_redirected_download_began() {
        val trail = LinkTrail()
        trail.requested("https://site.com/get/report")
        trail.redirected("https://go.site.com/report")
        trail.requested("https://go.site.com/report")
        trail.redirected("https://cdn.site.com/report.pdf?signature=abc")
        trail.requested("https://cdn.site.com/report.pdf?signature=abc")
        assertEquals("https://site.com/get/report", trail.originOf("https://cdn.site.com/report.pdf?signature=abc"))
    }

    @Test
    fun a_download_that_did_not_redirect_has_no_other_origin() {
        val trail = LinkTrail()
        trail.requested("https://site.com/report.pdf")
        assertNull(trail.originOf("https://site.com/report.pdf"))
    }

    @Test
    fun a_file_the_page_asks_for_is_not_credited_to_how_the_page_was_reached() {
        val trail = LinkTrail()
        trail.requested("https://short.link/x")
        trail.redirected("https://site.com/page")
        trail.requested("https://site.com/page")
        assertNull(trail.originOf("https://files.site.com/other.zip"))
    }

    @Test
    fun going_somewhere_else_ends_the_trail() {
        val trail = LinkTrail()
        trail.requested("https://short.link/x")
        trail.redirected("https://site.com/page")
        trail.requested("https://site.com/page")
        trail.requested("https://other.com/")
        assertNull(trail.originOf("https://site.com/page"))
        trail.requested("https://other.com/get")
        trail.redirected("https://cdn.other.com/a.zip")
        assertEquals("https://other.com/get", trail.originOf("https://cdn.other.com/a.zip"))
    }

    @Test
    fun only_web_addresses_can_be_copied() {
        assertNull(task("").copyableLink)
        assertNull(task("syncup-station://key/movie.mkv").copyableLink)
        assertNull(task("blob:https://site.com/123").copyableLink)
        assertEquals("http://site.com/a", task("http://site.com/a").copyableLink)
        assertEquals("HTTPS://site.com/a", task("HTTPS://site.com/a").copyableLink)
    }
}
