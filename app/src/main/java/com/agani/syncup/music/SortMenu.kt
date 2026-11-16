package com.agani.syncup.music

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Sort for the Music and Video player lists (user, 16 Nov 2026: "on the Video & Music add sort options
// too"). One small "Sort" button by the list's count opens the choices; the choice is kept per list, and
// folders follow it (newest folder first, biggest first…).

/** One way to order a list; [label] is what the Sort button and its menu show ("Newest first"). */
interface SortOrder {
    val label: String
}

/** The list's sort choice, kept on this device under [key] ("music", "videos"). */
@Composable
fun <T : Enum<T>> rememberSortOrder(key: String, values: Array<T>, default: T): MutableState<T> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sort", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getString(key, null)?.let { saved -> values.firstOrNull { it.name == saved } } ?: default) }
    LaunchedEffect(state.value) { prefs.edit().putString(key, state.value.name).apply() }
    return state
}

/** "⇅ Newest first" by a list's count; tap → the choices, a tick on the one in use. */
@Composable
fun <T : SortOrder> SortButton(options: List<T>, selected: T, onSelect: (T) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = "Change the order") { open = true }
                .heightIn(min = 36.dp).padding(horizontal = 10.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(selected.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.primary, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Sort by", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            )
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label, fontWeight = if (o == selected) FontWeight.Medium else FontWeight.Normal) },
                    trailingIcon = if (o == selected) {
                        { Icon(Icons.Rounded.Check, "In use", tint = cs.primary) }
                    } else null,
                    onClick = {
                        open = false
                        onSelect(o)
                    },
                )
            }
        }
    }
}

/**
 * How folders line up under a sort: by their name (A–Z, or Z–A when [descending]), or — with [number] —
 * by a number worked out from what's inside (the newest item, the total length…), the name breaking ties.
 */
fun <T> folderOrder(
    name: (String) -> String,
    number: ((List<T>) -> Long)? = null,
    descending: Boolean = false,
): Comparator<Pair<String, List<T>>> {
    val byName = compareBy<Pair<String, List<T>>, String>(String.CASE_INSENSITIVE_ORDER) { name(it.first) }
    if (number == null) return if (descending) byName.reversed() else byName
    val byNumber = compareBy<Pair<String, List<T>>> { number(it.second) }
    return (if (descending) byNumber.reversed() else byNumber).then(byName)
}

/** Music's orders. Name A–Z is how the list always came. */
enum class SongSort(override val label: String) : SortOrder {
    NAME_AZ("Name A–Z"),
    NAME_ZA("Name Z–A"),
    ARTIST("Artist A–Z"),
    ALBUM("Album A–Z"),
    NEWEST("Newest added"),
    OLDEST("Oldest added"),
    LONGEST("Longest first"),
    SHORTEST("Shortest first"),
    ;

    fun sort(songs: List<Song>): List<Song> {
        val byName = compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val c: Comparator<Song> = when (this) {
            NAME_AZ -> byName
            NAME_ZA -> byName.reversed()
            // Songs with no artist / album go last, not first under "U" or "".
            ARTIST -> compareBy<Song> { it.artist == LocalSongs.UNKNOWN_ARTIST }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }.then(byName)
            ALBUM -> compareBy<Song> { it.album.isBlank() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }.then(byName)
            NEWEST -> compareByDescending<Song> { it.addedSec }.then(byName)
            OLDEST -> compareBy<Song> { it.addedSec }.then(byName)
            LONGEST -> compareByDescending<Song> { it.durationMs }.then(byName)
            SHORTEST -> compareBy<Song> { it.durationMs }.then(byName)
        }
        return songs.sortedWith(c)
    }

    fun folders(groups: List<Pair<String, List<Song>>>, name: (String) -> String): List<Pair<String, List<Song>>> =
        groups.sortedWith(
            when (this) {
                NAME_AZ, ARTIST, ALBUM -> folderOrder(name)
                NAME_ZA -> folderOrder(name, descending = true)
                NEWEST -> folderOrder(name, { s -> s.maxOf { it.addedSec } }, descending = true)
                OLDEST -> folderOrder(name, { s -> s.minOf { it.addedSec } })
                LONGEST -> folderOrder(name, { s -> s.sumOf { it.durationMs } }, descending = true)
                SHORTEST -> folderOrder(name, { s -> s.sumOf { it.durationMs } })
            },
        )
}
