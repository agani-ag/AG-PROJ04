package com.agani.syncup.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HideSource
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.MenuRow
import com.agani.syncup.sync.BrowserSync
import com.agani.syncup.ui.theme.PillTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The ranking behind Recommended, from History on this phone (see [MostUsed]). */
internal object RecommendedSites {
    private const val MONTH_DAYS = 30
    private const val DAY_MS = 86_400_000L

    var used by mutableStateOf<List<UsedSite>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun refresh(context: Context) {
        used = load(context, MONTH_DAYS)
        loaded = true
    }

    /** The sites used in the last [days] days (null: all of History). */
    suspend fun load(context: Context, days: Int?): List<UsedSite> = withContext(Dispatchers.IO) {
        val from = days?.let { System.currentTimeMillis() - it * DAY_MS } ?: 0L
        MostUsed.rank(BrowserDb.get(context).visitsSince(from), ShortcutCatalog.categories.flatMap { it.items })
    }

    /** Another account came in: the last person's sites leave with their History. */
    fun clear() {
        used = emptyList()
        loaded = false
    }
}

private class Period(val label: String, val words: String, val days: Int?)

private val PERIODS = listOf(
    Period("7 days", "the last 7 days", 7),
    Period("30 days", "the last 30 days", 30),
    Period("All time", "all of your History", null),
)

/** Most rows the full ranking shows. */
private const val MAX_RANKED = 50

// ============================================================================ first in Shortcuts
/** Recommended on the Browse page: tiles like every other category, with the usage count under each name. */
@Composable
internal fun RecommendedCard(
    sites: List<UsedSite>,
    usedCount: Int,
    onOpen: (String) -> Unit,
    onLongPress: (UsedSite) -> Unit,
    onShowAll: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 10.dp)) {
        Text("Recommended", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(if (usedCount > sites.size) "$usedCount" else "", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (usedCount > sites.size) {
            Text(
                "Show all ›", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onShowAll).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().browserCard().padding(horizontal = 6.dp, vertical = 18.dp)) {
        TileRows(sites.map { site -> { RecommendedTile(site, onOpen, onLongPress) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.RecommendedTile(site: UsedSite, onOpen: (String) -> Unit, onLongPress: (UsedSite) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onLongClickLabel = "More options", onLongClick = { onLongPress(site) }, onClick = { onOpen(site.url) })
            .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        SiteDisc(site.url, site.title, 52)
        Spacer(Modifier.height(8.dp))
        Text(site.title, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(MostUsed.visitsText(site.visits), fontSize = 10.5.sp, lineHeight = 14.sp, maxLines = 1, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun SiteDisc(url: String, title: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.field), contentAlignment = Alignment.Center) {
        SiteMark(url, title, circle = size.dp, iconSize = (size * .54f).dp)
    }
}

// ============================================================================ Show all
/** The full ranking, opened from Show all or the Recommended chip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecommendedPage(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onRemove: (UsedSite) -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onBack)
    var period by rememberSaveable { mutableIntStateOf(1) }
    var sites by remember { mutableStateOf<List<UsedSite>?>(null) }
    var pressed by remember { mutableStateOf<UsedSite?>(null) }
    val chosen = PERIODS[period]
    LaunchedEffect(period, ShortcutCatalog.categories, BrowserSync.dataVersion) {
        sites = RecommendedSites.load(context, chosen.days)
    }
    val shown = sites?.filterNot { it.key in BrowserSettings.removedSites }
    Column(Modifier.fillMaxSize().background(cs.surface)) {
        PageTopBar("Recommended", onBack)
        PillTabs(PERIODS.map { it.label }, period, { period = it }, Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            when {
                shown == null -> Unit
                shown.isEmpty() -> Column(Modifier.fillMaxWidth().browserCard().padding(horizontal = 22.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing for ${chosen.words}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A site appears here once you've used it a few times, on different days.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
                else -> {
                    Text(
                        "${shown.size} site${if (shown.size == 1) "" else "s"} from ${chosen.words}", fontSize = 13.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    )
                    val most = shown.first().visits.coerceAtLeast(1)
                    val now = System.currentTimeMillis()
                    Column(Modifier.fillMaxWidth().browserCard().padding(vertical = 4.dp)) {
                        shown.take(MAX_RANKED).forEachIndexed { i, site ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onLongClickLabel = "More options",
                                        onLongClick = { pressed = site },
                                        onClick = { onOpen(site.url) },
                                    )
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            ) {
                                Text("${i + 1}", fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(10.dp))
                                SiteDisc(site.url, site.title, 40)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(site.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(site.key, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.field)) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth((site.visits.toFloat() / most).coerceAtLeast(.03f)).clip(RoundedCornerShape(2.dp)).background(cs.primary))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                // A fixed width, so every row's bar is measured on the same track.
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(76.dp)) {
                                    Text(MostUsed.visitsText(site.visits), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1)
                                    Text(MostUsed.lastSeenText(site.lastAt, now), fontSize = 11.5.sp, lineHeight = 15.sp, color = cs.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pressed?.let { site ->
        RecommendedSheet(site, onDismiss = { pressed = null }, onOpen = onOpen, onOpenInNewTab = onOpenInNewTab, onRemove = onRemove)
    }
}

// ============================================================================ long press
/** What a long press on a recommended site offers, including taking it off the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecommendedSheet(
    site: UsedSite,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onRemove: (UsedSite) -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.sheet) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 14.dp)) {
            SiteDisc(site.url, site.title, 44)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(site.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${MostUsed.visitsText(site.visits)} · last ${MostUsed.lastSeenText(site.lastAt, System.currentTimeMillis()).lowercase()}",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = cs.outlineVariant)
        Spacer(Modifier.height(6.dp))
        MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open") {
            onDismiss()
            onOpen(site.url)
        }
        MenuRow(Icons.Rounded.Tab, "Open in new tab") {
            onDismiss()
            onOpenInNewTab(site.url)
        }
        MenuRow(Icons.Rounded.ContentCopy, "Copy link") {
            onDismiss()
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link", site.url))
            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        }
        MenuRow(Icons.Rounded.HideSource, "Remove from Recommended", tint = cs.error, textColor = cs.error) {
            onDismiss()
            onRemove(site)
        }
        Text(
            "This doesn't touch your History. You can bring it back in Settings → Browser.", fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 64.dp, end = 24.dp, top = 2.dp, bottom = 18.dp),
        )
    }
}
