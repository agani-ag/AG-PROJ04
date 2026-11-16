package com.agani.syncup.music

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Sort menu's folder order: by name, or by a number from what's inside, the name breaking ties. */
class FolderOrderTest {
    private val name = { path: String -> path.substringAfterLast('/').ifBlank { "Other" } }

    // Each "item" is just a number (its date, length or size).
    private val groups = listOf(
        "Movies/Trip" to listOf(5L, 9L),
        "DCIM/Camera" to listOf(7L),
        "" to listOf(1L, 2L, 3L),
        "Movies" to listOf(7L),
    )

    private fun order(c: Comparator<Pair<String, List<Long>>>) = groups.sortedWith(c).map { name(it.first) }

    @Test fun byNameAToZ_ignoresCaseAndUsesTheFolderName() {
        assertEquals(listOf("Camera", "Movies", "Other", "Trip"), order(folderOrder(name)))
    }

    @Test fun byNameZToA() {
        assertEquals(listOf("Trip", "Other", "Movies", "Camera"), order(folderOrder(name, descending = true)))
    }

    @Test fun newestFirst_byTheNewestItem_nameBreaksTies() {
        // Trip has 9; Camera and Movies both have 7 → A–Z between them; Other's newest is 3.
        assertEquals(listOf("Trip", "Camera", "Movies", "Other"), order(folderOrder(name, { it.max() }, descending = true)))
    }

    @Test fun smallestTotalFirst() {
        // Totals: Trip 14, Camera 7, Other 6, Movies 7.
        assertEquals(listOf("Other", "Camera", "Movies", "Trip"), order(folderOrder(name, { it.sum() })))
    }
}
