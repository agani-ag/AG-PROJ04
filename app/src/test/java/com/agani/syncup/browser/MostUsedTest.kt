package com.agani.syncup.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MostUsedTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = 1_800_000_000_000L - 1_800_000_000_000L % day

    private fun rank(visits: List<PageVisit>, catalogue: List<CatalogShortcut> = emptyList()) = MostUsed.rank(visits, catalogue, utc)

    /** One sitting a day, for [days] days. */
    private fun daily(url: String, days: Int, title: String = "") = List(days) { PageVisit(url, title, start + it * day) }

    private fun shortcut(title: String, url: String, category: String = "Popular") = CatalogShortcut(title.hashCode().toLong(), title, url, category)

    @Test
    fun pages_in_one_sitting_count_once() {
        val sitting = List(20) { PageVisit("https://cricbuzz.com/page$it", "", start + it * 60_000L) }
        val others = daily("https://cricbuzz.com/", 2).map { PageVisit(it.url, "", it.at + 2 * day) }
        val site = rank(sitting + others).single()
        assertEquals(3, site.visits)
    }

    @Test
    fun a_long_gap_starts_a_new_visit() {
        val visits = listOf(0L, 31 * 60_000L, 62 * 60_000L, day).map { PageVisit("https://cricbuzz.com/", "", start + it) }
        assertEquals(4, rank(visits).single().visits)
    }

    @Test
    fun a_site_must_be_used_on_two_days_at_least_three_times() {
        val sameDay = List(3) { PageVisit("https://oneday.com/", "", start + it * 2 * hour) }
        val twice = daily("https://twice.com/", 2)
        val enough = listOf(0L, 2 * hour, day).map { PageVisit("https://enough.com/", "", start + it) }
        assertEquals(listOf("enough.com"), rank(sameDay + twice + enough).map { it.key })
    }

    @Test
    fun www_and_m_are_one_site() {
        val visits = listOf("https://m.youtube.com/a", "https://www.youtube.com/b", "https://youtube.com/c")
            .mapIndexed { i, u -> PageVisit(u, "", start + i * day) }
        val site = rank(visits).single()
        assertEquals("youtube.com", site.key)
        assertEquals(3, site.visits)
    }

    @Test
    fun different_services_stay_apart() {
        val maps = listOf(shortcut("Google Maps", "https://www.google.com/maps"))
        val visits = daily("https://www.google.com/", 3) + daily("https://www.google.com/maps/place/x", 3) + daily("https://mail.google.com/mail", 3)
        assertEquals(setOf("google.com", "google.com/maps", "mail.google.com"), rank(visits, maps).map { it.key }.toSet())
        assertEquals("Google Maps", rank(daily("https://www.google.com/maps", 3), maps).single().title)
    }

    @Test
    fun the_app_knows_no_shortcuts_of_its_own() {
        // With no catalogue from the backend, Maps is just part of Google, named from its own pages.
        val site = rank(daily("https://www.google.com/maps/place/x", 3, "Chennai - Google Maps")).single()
        assertEquals("google.com", site.key)
        assertEquals("https://www.google.com/", site.url)
    }

    @Test
    fun a_new_user_has_nothing_to_recommend() {
        assertTrue(rank(emptyList()).isEmpty())
        // Plenty of browsing, but nothing they came back to.
        assertTrue(rank(List(40) { PageVisit("https://site$it.com/", "", start + it * hour) }).isEmpty())
    }

    @Test
    fun a_tile_with_its_own_page_counts_on_its_own() {
        val tile = shortcut("Sun TV", "https://www.youtube.com/@SunTV")
        val visits = daily("https://m.youtube.com/@suntv/videos", 3) + daily("https://m.youtube.com/watch?v=1", 3)
        val sites = rank(visits, listOf(tile))
        assertEquals(setOf("youtube.com/@suntv", "youtube.com"), sites.map { it.key }.toSet())
        assertEquals("Sun TV", sites.first { it.key == "youtube.com/@suntv" }.title)
    }

    @Test
    fun searching_doesnt_make_a_site_used() {
        val pages = listOf(
            "https://www.google.com/search?q=a", "https://www.google.co.in/search?q=a", "https://www.google.com/url?q=x",
            "https://www.bing.com/search?q=a", "https://duckduckgo.com/?q=a", "https://search.yahoo.com/search?p=a",
        )
        assertTrue(pages.flatMap { daily(it, 4) }.let { rank(it) }.isEmpty())
        assertEquals("duckduckgo.com", rank(daily("https://duckduckgo.com/", 3)).single().key)
    }

    @Test
    fun sign_in_redirects_and_local_devices_are_left_out() {
        val pages = listOf(
            "https://accounts.google.com/v3/signin", "https://login.live.com/", "https://shop.com/login", "https://t.co/abc", "https://l.facebook.com/l.php",
            "http://192.168.1.20:8765/", "http://[::1]:8000/", "http://localhost:8000/", "http://printer.local/", "http://router/",
        )
        assertTrue(pages.flatMap { daily(it, 4) }.let { rank(it) }.isEmpty())
    }

    @Test
    fun not_web_pages_are_ignored() {
        assertTrue((daily("file:///sdcard/a.html", 4) + daily("data:text/html,x", 4) + daily("about:blank", 4)).let { rank(it) }.isEmpty())
    }

    @Test
    fun the_order_matches_the_numbers_and_recent_wins_a_tie() {
        val visits = daily("https://few.com/", 3) + daily("https://many.com/", 5) + daily("https://tie.com/", 3).map { PageVisit(it.url, "", it.at + hour) }
        assertEquals(listOf("many.com", "tie.com", "few.com"), rank(visits).map { it.key })
    }

    @Test
    fun tapping_opens_the_sites_home_page() {
        assertEquals("https://m.cricbuzz.com/", rank(daily("https://m.cricbuzz.com/live/1", 3)).single().url)
        assertEquals("https://m.youtube.com", rank(daily("https://www.youtube.com/watch", 3), listOf(shortcut("YouTube", "https://m.youtube.com"))).single().url)
        assertEquals("https://www.google.com/maps", rank(daily("https://www.google.com/maps/x", 3), listOf(shortcut("Google Maps", "https://www.google.com/maps"))).single().url)
    }

    @Test
    fun the_catalogue_names_a_site_first() {
        assertEquals("Cricbuzz Live", rank(daily("https://www.cricbuzz.com/", 3, "Whatever"), listOf(shortcut("Cricbuzz Live", "https://cricbuzz.com"))).single().title)
    }

    @Test
    fun a_name_comes_from_what_page_titles_share() {
        assertEquals("Cricbuzz", MostUsed.nameOf("cricbuzz.com", listOf("IPL live score - Cricbuzz", "Scores | Cricbuzz", "Cricbuzz")))
        assertEquals("Gmail", MostUsed.nameOf("mail.google.com", listOf("Inbox (3) - me@a.com - Gmail", "Inbox (1) - me@a.com - Gmail")))
    }

    @Test
    fun a_name_never_comes_from_one_headline_or_an_email() {
        assertEquals("News", MostUsed.nameOf("news.com", listOf("Rain floods Chennai roads", "Rain floods Chennai roads")))
        assertEquals("mail.example.com", MostUsed.nameOf("mail.example.com", listOf("Inbox - me@a.com", "Sent - me@a.com")))
    }

    @Test
    fun a_name_keeps_the_sites_own_spelling() {
        assertEquals("IRCTC", MostUsed.nameOf("irctc.co.in", listOf("IRCTC Next Generation eTicketing System of Indian Railways")))
        assertEquals("Example", MostUsed.nameOf("example.com", emptyList()))
    }

    private val ai = listOf(shortcut("ChatGPT", "https://chatgpt.com", "AI"), shortcut("Gemini", "https://gemini.google.com", "AI"))

    private fun used(key: String, visits: Int, url: String = "https://$key/") = UsedSite(key, key, url, visits, start)

    @Test
    fun a_site_shows_once_on_the_all_page() {
        val left = MostUsed.withoutRecommended(
            listOf(CatalogCategory(1, "AI", ai + shortcut("Perplexity", "https://perplexity.ai", "AI")), CatalogCategory(2, "Only", listOf(shortcut("ChatGPT", "https://chatgpt.com", "Only")))),
            listOf(used("chatgpt.com", 9), used("gemini.google.com", 5)),
        )
        assertEquals(listOf("Perplexity"), left.flatMap { c -> c.items.map { it.title } })
        assertEquals(1, left.size)
    }

    @Test
    fun one_name_gets_one_tile() {
        val visits = daily("https://www.google.com/", 5, "Google") + daily("https://www.google.co.in/", 3, "Google")
        val site = rank(visits).single()
        assertEquals("Google", site.title)
        assertEquals(8, site.visits)
        assertEquals("google.com", site.key)
    }

    @Test
    fun a_shortcut_with_the_same_name_is_the_same_site() {
        val wiki = CatalogCategory(1, "Popular", listOf(shortcut("Wikipedia", "https://www.wikipedia.org"), shortcut("Google", "https://www.google.com")))
        val left = MostUsed.withoutRecommended(listOf(wiki), listOf(UsedSite("en.wikipedia.org", "Wikipedia", "https://en.wikipedia.org/", 3, start)))
        assertEquals(listOf("Google"), left.flatMap { c -> c.items.map { it.title } })
    }

    @Test
    fun with_nothing_recommended_every_card_stays_whole() {
        val all = listOf(CatalogCategory(1, "AI", ai))
        assertEquals(all, MostUsed.withoutRecommended(all, emptyList()))
    }

    @Test
    fun plain_words() {
        assertEquals("1 visit", MostUsed.visitsText(1))
        assertEquals("64 visits", MostUsed.visitsText(64))
        assertEquals("Today", MostUsed.lastSeenText(start + hour, start + 2 * hour, utc))
        assertEquals("Yesterday", MostUsed.lastSeenText(start, start + day, utc))
        assertEquals("3 days ago", MostUsed.lastSeenText(start, start + 3 * day, utc))
    }
}
