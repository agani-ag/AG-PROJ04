package com.agani.syncup.home

import com.agani.syncup.data.UrlItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the main page puts links: a partner section only when partner links are all there is (user, 17 Nov 2026). */
class MainPageLinksTest {
    private fun partner(id: String, partner: String) = UrlItem(id, "P$id", "https://p$id.example", source = "partner", sourceName = partner)
    private fun admin(id: String) = UrlItem(id, "A$id", "https://a$id.example", source = "admin")
    private fun general(id: String) = UrlItem(id, "G$id", "https://g$id.example", general = true)

    @Test fun onlyPartnerLinks_getTheirOwnSection() {
        val (section, list) = mainPageLinks(listOf(partner("1", "GSTSYNC"), partner("2", "GSTSYNC"), partner("3", "Acme")))
        assertEquals(listOf("Acme", "GSTSYNC"), section.keys.toList())
        assertEquals(listOf("2"), section.getValue("GSTSYNC").drop(1).map { it.id })
        assertTrue(list.isEmpty())
    }

    @Test fun partnerAndAdminLinks_areOneList_inServerOrder() {
        val links = listOf(partner("1", "GSTSYNC"), admin("2"), partner("3", "GSTSYNC"))
        val (section, list) = mainPageLinks(links)
        assertTrue(section.isEmpty())
        assertEquals(listOf("1", "2", "3"), list.map { it.id })
    }

    @Test fun partnerAndGeneralLinks_areOneList() {
        val (section, list) = mainPageLinks(listOf(general("1"), partner("2", "GSTSYNC")))
        assertTrue(section.isEmpty())
        assertEquals(listOf("1", "2"), list.map { it.id })
    }

    @Test fun noPartner_isJustTheList() {
        val (section, list) = mainPageLinks(listOf(admin("1"), general("2")))
        assertTrue(section.isEmpty())
        assertEquals(listOf("1", "2"), list.map { it.id })
    }

    @Test fun noLinks_showNothing() {
        val (section, list) = mainPageLinks(emptyList())
        assertTrue(section.isEmpty() && list.isEmpty())
    }
}
