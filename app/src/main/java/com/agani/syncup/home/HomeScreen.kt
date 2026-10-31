package com.agani.syncup.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Badge
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BadgeTextStyle
import com.agani.syncup.calendar.CalendarStore
import com.agani.syncup.calendar.Days
import com.agani.syncup.data.PartnerDto
import com.agani.syncup.data.UrlItem
import com.agani.syncup.forest.ForestRules
import com.agani.syncup.forest.rememberForestNow
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.wellbeing.Breaks
import com.agani.syncup.wellbeing.Habits
import com.agani.syncup.wellbeing.Mood
import com.agani.syncup.wellbeing.Sleep
import com.agani.syncup.wellbeing.Steps
import com.agani.syncup.wellbeing.Water
import com.agani.syncup.wellbeing.Wellbeing
import com.agani.syncup.wellbeing.clockText
import com.agani.syncup.wellbeing.hoursText
import com.agani.syncup.wellbeing.timeText
import java.util.Calendar
import kotlinx.coroutines.launch

/** What Home can open; MainActivity knows where each one lives. */
enum class HomeTool { CALENDAR, FOREST, SHARE, WALKIE, MUSIC, VIDEO, RADIO, SENSORS, PARTY, WATCH_PARTY, GROUP, WATER, STEPS, HABITS, SLEEP, BREAKS, MOOD }

// ============================================================================ the bottom bar
/**
 * Home · Browse · Tools · You — on Home, Tools and You (the browser keeps its own bar). Docked along the
 * bottom edge; the open tab is one rounded square holding its icon and its name.
 */
@Composable
fun AppTabBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    NavigationBar(containerColor = cs.surfaceContainer, tonalElevation = 0.dp) {
        for ((tab, label, icons) in listOf(
            Triple(MainTab.HOME, "Home", Icons.Rounded.Home to Icons.Outlined.Home),
            Triple(MainTab.BROWSE, "Browse", Icons.Rounded.Public to Icons.Outlined.Public),
            Triple(MainTab.TOOLS, "Tools", Icons.Rounded.GridView to Icons.Outlined.GridView),
            Triple(MainTab.YOU, "You", Icons.Rounded.Person to Icons.Outlined.Person),
        )) {
            val on = tab == current
            val tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).height(80.dp) // the bar's own height (fillMaxHeight would take the whole page)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.size(width = 76.dp, height = 60.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (on) cs.primaryContainer else Color.Transparent),
                ) {
                    if (tab == MainTab.YOU && com.agani.syncup.notifications.Inbox.newCount > 0) {
                        BadgedBox(badge = { Badge() }) { Icon(if (on) icons.first else icons.second, null, tint = tint) }
                    } else {
                        Icon(if (on) icons.first else icons.second, null, tint = tint)
                    }
                    Text(
                        label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = tint,
                        maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** A tab's page with the bottom bar under it. */
@Composable
fun TabFrame(current: MainTab, onSelect: (MainTab) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.weight(1f)) { content() }
        AppTabBar(current, onSelect)
    }
}

// ============================================================================ Home
/**
 * SyncUp's start: a greeting, search the web straight away, then what matters today — to-dos and
 * the forest's day, what's playing, what's happening nearby, favourite tools, SyncUp links. A card shows
 * only when it has something to say, and each can be hidden (Customise Home / Settings → Home).
 */
@Composable
fun HomeScreen(
    signedIn: Boolean,
    userInitial: String?,
    links: List<UrlItem>,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onAccount: () -> Unit,
    onTool: (HomeTool) -> Unit,
    onAllTools: () -> Unit,
    onOpenLink: (UrlItem) -> Unit,
    onAllLinks: () -> Unit,
    onOpenBrowse: () -> Unit,
    /** The signed-in user's name: Home greets them by their first name ("SyncUp" without it). */
    userName: String? = null,
    /** Signed out: the Today card starts with "Sign in to SyncUp". */
    onSignIn: () -> Unit = {},
    /** Partners that added this person and wait for its password: a card each, until "Later". */
    waitingPartners: List<PartnerDto> = emptyList(),
    onEnablePartner: suspend (id: String, password: String) -> Result<PartnerDto?> = { _, _ -> Result.success(null) },
    onPartnerEnabled: (name: String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var customise by remember { mutableStateOf(false) }
    val player = LocalPlayer.current
    val nearby = nearbyRows()
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
    ) {
        item(key = "top") {
            val first = userName?.trim()?.substringBefore(' ')?.takeIf { signedIn && it.isNotBlank() }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(top = 6.dp, bottom = 4.dp)) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    if (first != null) Text(greeting(), fontSize = 13.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
                    Text(
                        first ?: "SyncUp", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Bell(onNotifications)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(start = 4.dp, end = 2.dp).size(36.dp).clip(CircleShape)
                        .background(if (signedIn) cs.primary else cs.surfaceContainerHigh).clickable(onClickLabel = "You", onClick = onAccount),
                ) {
                    if (signedIn && userInitial != null) Text(userInitial, color = cs.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    else Icon(Icons.Rounded.Person, "You", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
        }
        item(key = "search") {
            // A white pill with the card shadow: tapping it opens Browse with the address bar ready.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(50.dp).appCard(RoundedCornerShape(50))
                    .clickable(onClick = onSearch).padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Search or type web address", fontSize = 15.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (HomePrefs.introPending) {
            item(key = "intro") { GrownCard(onOpenBrowse = { HomePrefs.introDone(); onOpenBrowse() }, onDone = { HomePrefs.introDone() }) }
        }
        for (p in waitingPartners.filter { signedIn && it.id !in HomePrefs.laterPartners }) {
            item(key = "waiting-${p.id}") { PartnerWaitingCard(p, onEnablePartner, onPartnerEnabled) }
        }
        if (HomePrefs.shows(HomeCard.TODAY)) {
            item(key = "today") {
                Header("Today · ${todayText()}")
                Card {
                    val rows = todayRows(onTool, signIn = if (signedIn) null else onSignIn)
                    if (rows.isEmpty()) {
                        Text("Nothing to show — turn rows back on in Customise Home", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                    rows.forEachIndexed { i, row ->
                        if (i > 0) Divider()
                        row()
                    }
                }
            }
        }
        if (HomePrefs.shows(HomeCard.PLAYING) && player?.item != null) {
            item(key = "playing") {
                Spacer(Modifier.height(12.dp))
                Card {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onTool(if (player.isRadio) HomeTool.RADIO else HomeTool.MUSIC) }
                            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        com.agani.syncup.music.ArtworkTile(player.artUri, player.isRadio, Modifier.size(46.dp), radius = 14.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(player.title, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                player.subtitle.ifBlank { if (player.isRadio) "Radio" else "Music" },
                                fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(cs.surfaceContainer).clickable(
                                onClickLabel = if (player.isPlaying) "Pause" else "Play",
                            ) { player.toggle() },
                        ) {
                            Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play", tint = cs.onSurface)
                        }
                    }
                }
            }
        }
        if (HomePrefs.shows(HomeCard.NEARBY) && nearby.isNotEmpty()) {
            item(key = "nearby") {
                Header("Nearby")
                Card {
                    nearby.forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        CardRow(r.title, r.subtitle, onClick = { onTool(r.tool) }) { Tile(r.icon, r.tint) }
                    }
                }
            }
        }
        if (HomePrefs.shows(HomeCard.TOOLS)) {
            item(key = "tools") {
                Header("Your tools", action = "All tools", onAction = onAllTools)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    homeTools().chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { t ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onTool(t.tool) }.padding(vertical = 4.dp),
                                ) {
                                    Tile(t.icon, t.tint, size = 60.dp, iconSize = 28.dp)
                                    Text(
                                        t.title, fontSize = 12.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 7.dp),
                                    )
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        val one = HomePrefs.singleLink?.takeIf { signedIn && links.size == 1 }
        if (HomePrefs.shows(HomeCard.LINKS) && one != null) {
            // The usual partner user: one partner, one link — one big button.
            item(key = "one-link") { OneLinkCard(one, onOpen = { onOpenLink(one) }) }
        }
        // Several links: a card per partner with its links as big tiles, then the rest as a list.
        val byPartner = if (one != null) emptyMap() else links
            .filter { it.source == "partner" && !it.sourceName.isNullOrBlank() }
            .groupBy { it.sourceName!!.trim() }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val rest = if (one != null) emptyList() else links.filter { l -> byPartner.values.none { l in it } }
        if (HomePrefs.shows(HomeCard.LINKS)) {
            for ((name, items) in byPartner) {
                item(key = "partner-$name") { PartnerCard(name, items, onOpenLink, onAllLinks) }
            }
        }
        if (HomePrefs.shows(HomeCard.LINKS) && rest.isNotEmpty()) {
            item(key = "links") {
                Header(if (byPartner.isEmpty()) "SyncUp links" else "More links", action = if (rest.size > 4) "All ${rest.size}" else "All", onAction = onAllLinks)
                Card {
                    rest.take(4).forEachIndexed { i, l ->
                        if (i > 0) Divider()
                        val from = l.sourceName?.takeIf { it.isNotBlank() } ?: "SyncUp"
                        CardRow(l.title, if (l.general) "For everyone" else from, onClick = { onOpenLink(l) }, chevron = false) {
                            com.agani.syncup.browser.LinkIcon(l, 34.dp)
                        }
                    }
                }
            }
        }
        item(key = "customise") {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { customise = true }) {
                    Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Customise Home", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    if (customise) CustomiseSheet(onDismiss = { customise = false })
}

/** "Good morning," / "Good afternoon," / "Good evening," for the hour now. */
private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning,"
    in 12..16 -> "Good afternoon,"
    else -> "Good evening,"
}

private fun todayText(): String {
    val c = Calendar.getInstance()
    val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${days[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]}"
}

/** The bell: opens Notifications; the red number is how many are new. */
@Composable
private fun Bell(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val n = com.agani.syncup.notifications.Inbox.newCount
    Box(
        Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = if (n > 0) "Notifications, $n new" else "Notifications" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Notifications, null, tint = cs.onSurface)
        if (n > 0) {
            // The number sits on a ring of the page colour so it stands off the bell.
            val pill = RoundedCornerShape(50)
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 1.dp).clip(pill).background(cs.surface).padding(2.dp)
                    .heightIn(min = 15.dp).widthIn(min = 15.dp).clip(pill).background(cs.error).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (n > 9) "9+" else "$n", style = BadgeTextStyle, fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = cs.onError)
            }
        }
    }
}

/** "Today · Wed 21 Oct", with an optional link on the right ("All tools"). */
@Composable
private fun Header(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 10.dp)) {
        Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().appCard(CardShape)) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A tool's icon on its own soft colour (34 dp in rows, 60 dp in Your tools). */
@Composable
private fun Tile(icon: ImageVector, tint: ToolTint, size: Dp = 34.dp, iconSize: Dp = 18.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(tint.tile),
    ) { Icon(icon, null, tint = tint.icon, modifier = Modifier.size(iconSize)) }
}

/** One row in a Home card: a tile, two lines, then an optional small action and a chevron. */
@Composable
private fun CardRow(
    title: String,
    sub: String?,
    onClick: () -> Unit,
    subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    chevron: Boolean = true,
    end: (@Composable () -> Unit)? = null,
    leading: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) {
                Text(sub, fontSize = 12.5.sp, lineHeight = 17.sp, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (end != null) {
            Spacer(Modifier.width(8.dp))
            end()
        } else if (chevron) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------- partners' links
/** A partner's first letter on a soft colour picked from its name (partners have no logo). */
@Composable
private fun LetterTile(name: String, size: Dp = 38.dp) {
    val tint = ToolTint.entries[Math.floorMod(name.trim().lowercase().hashCode(), ToolTint.entries.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * .32f)).background(tint.tile),
    ) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "•" }, color = tint.icon, fontWeight = FontWeight.Bold,
            fontSize = (size.value * .42f).sp,
        )
    }
}

/**
 * The user's only link as one big card: who it's from, the link, a big Open button, and "Open it as
 * soon as SyncUp starts" (on by default for a partner user — HomePrefs.opensLink).
 */
@Composable
private fun OneLinkCard(link: UrlItem, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val partner = link.sourceName?.trim()?.takeIf { link.source == "partner" && it.isNotBlank() }
    Column(Modifier.padding(top = 18.dp).fillMaxWidth().appCard(CardShape).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterTile(partner ?: link.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(partner ?: "SyncUp", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (partner != null) "Your partner on SyncUp" else "Your SyncUp link", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 22.dp, bottom = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceContainer).clickable(onClick = onOpen).padding(16.dp),
        ) {
            com.agani.syncup.browser.LinkIcon(link, 56.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                link.title, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("Open ${link.title}", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(20.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .clickable { HomePrefs.changeStartLink(!HomePrefs.opensLink) }.padding(vertical = 6.dp, horizontal = 2.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Open it as soon as SyncUp starts", fontSize = 14.sp, lineHeight = 19.sp, color = cs.onSurface)
                Text("Skip this page next time", fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = HomePrefs.opensLink, onCheckedChange = { HomePrefs.changeStartLink(it) })
        }
    }
}

/** One partner's links: its name and up to 6 links as big tiles, 3 a row; "All" opens the SyncUp section. */
@Composable
private fun PartnerCard(name: String, items: List<UrlItem>, onOpen: (UrlItem) -> Unit, onAll: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().appCard(CardShape).padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            LetterTile(name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${items.size} link${if (items.size == 1) "" else "s"}", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAll).padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text("All", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            }
        }
        items.take(6).chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { l ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onOpen(l) }.padding(vertical = 8.dp, horizontal = 2.dp),
                    ) {
                        com.agani.syncup.browser.LinkIcon(l, 54.dp)
                        Text(
                            l.title, fontSize = 12.5.sp, lineHeight = 16.sp, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** "Sunrise School added you": that partner's password right on Home (You → Partners does the same). */
@Composable
private fun PartnerWaitingCard(
    partner: PartnerDto,
    onEnable: suspend (id: String, password: String) -> Result<PartnerDto?>,
    onEnabled: (name: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var password by remember(partner.id) { mutableStateOf("") }
    var error by remember(partner.id) { mutableStateOf<String?>(null) }
    var busy by remember(partner.id) { mutableStateOf(false) }
    fun turnOn() {
        if (busy || password.isBlank()) return
        busy = true
        scope.launch {
            onEnable(partner.id, password)
                .onSuccess { onEnabled(partner.partner) }
                .onFailure { error = it.message ?: "Couldn't turn on" }
            busy = false
        }
    }
    Column(
        Modifier.padding(top = 14.dp).fillMaxWidth().appCard(CardShape).border(2.dp, ToolTint.ORANGE.icon, CardShape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterTile(partner.partner)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${partner.partner} added you", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Enter the password ${partner.partner} gave you to see their links.",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        com.agani.syncup.browser.PasswordField(password, { password = it; error = null }, label = "${partner.partner} password", error = error, onDone = { turnOn() })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { turnOn() }, enabled = !busy && password.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary) else Text("Turn on")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { HomePrefs.partnerLater(partner.id) }, enabled = !busy) { Text("Later") }
        }
    }
}

// ---------------------------------------------------------------------------- Today
@Composable
private fun CalendarRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    @Suppress("UNUSED_VARIABLE") val v = CalendarStore.version
    val today = Days.today()
    val due = if (CalendarStore.ready) CalendarStore.tasks.filter { !it.done && it.occurrences(today, today).isNotEmpty() } else emptyList()
    val overdue = if (CalendarStore.ready) CalendarStore.tasks.count { !it.done && it.hasDate && !it.repeat.repeats && it.day < today } else 0
    val c = Calendar.getInstance()
    val nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    val next = due.filter { !it.allDay && it.time >= nowMin }.minByOrNull { it.time } ?: due.firstOrNull { it.allDay } ?: due.maxByOrNull { it.time }
    // Same time wording as the To-do list ("10:00 – 11:00 AM", "All day"); the card already says Today.
    val title = next?.let { "${it.title} · ${if (it.allDay) "All day" else Days.timeRange(it.time, it.endTime)}" } ?: "Nothing due today"
    val sub = buildString {
        append(
            when {
                due.size > 1 -> "and ${due.size - 1} more to-do${if (due.size == 2) "" else "s"} today"
                due.size == 1 -> "Your only to-do today"
                else -> "Tap to plan something"
            },
        )
        if (overdue > 0) append(" · $overdue overdue")
    }
    CardRow(title, sub, onClick = onClick, subColor = if (overdue > 0) cs.error else cs.onSurfaceVariant) {
        Tile(Icons.Rounded.CalendarMonth, ToolTint.INDIGO)
    }
}

/** The forest's day and what came today ("Day 22 · 22 trees" · "A mongoose came today"). */
@Composable
private fun ForestRow(onClick: () -> Unit) {
    val now = rememberForestNow()
    CardRow(ForestRules.homeTitle(now), ForestRules.homeSubtitle(now), onClick = onClick) {
        Tile(Icons.Rounded.Eco, ToolTint.LIME)
    }
}

/**
 * The Today card's rows: signed out, "Sign in to SyncUp" first ([signIn]); then Calendar and Forest, then
 * each Wellbeing tool in use — minus the hidden ones.
 */
@Composable
private fun todayRows(onTool: (HomeTool) -> Unit, signIn: (() -> Unit)? = null): List<@Composable () -> Unit> {
    val ctx = LocalContext.current
    remember { Wellbeing.init(ctx) }
    // Redraw when a Wellbeing tool changes.
    @Suppress("UNUSED_VARIABLE") val v = Water.version + Steps.version + Habits.version + Sleep.version + Mood.version + Breaks.version
    val today = com.agani.syncup.forest.FDays.today()
    return buildList<@Composable () -> Unit> {
        if (signIn != null) {
            add {
                TodayLine(
                    Icons.AutoMirrored.Rounded.Login, ToolTint.BLUE, "Sign in to SyncUp",
                    "Your links and tools on every phone",
                    action = "Sign in" to signIn, onClick = signIn,
                )
            }
        }
        if (HomePrefs.shows(TodayRow.CALENDAR)) add { CalendarRow { onTool(HomeTool.CALENDAR) } }
        if (HomePrefs.shows(TodayRow.FOREST)) add { ForestRow { onTool(HomeTool.FOREST) } }
        val ws = Water.settings
        if (ws.setUp && HomePrefs.shows(TodayRow.WATER)) {
            val total = Water.today()
            val next = Water.nextReminder()
            val cup = ws.cups[0]
            add {
                TodayLine(
                    Icons.Rounded.WaterDrop, ToolTint.CYAN, "${Water.fmt(total)} of ${Water.fmt(ws.goal)} water",
                    when {
                        total >= ws.goal -> "Goal met — well done"
                        ws.remind && next != null && com.agani.syncup.forest.FDays.of(next) == today -> "Next reminder ${clockText(next)}"
                        else -> "${Water.fmt(ws.goal - total)} to go"
                    },
                    action = "+ ${Water.fmt(cup.ml)}" to { Water.add(cup.ml, cup.name) },
                ) { onTool(HomeTool.WATER) }
            }
        }
        if (Steps.counting(ctx) && HomePrefs.shows(TodayRow.STEPS)) {
            val st = Steps.today()
            val goal = Steps.settings.goal
            add {
                TodayLine(
                    Icons.AutoMirrored.Rounded.DirectionsWalk, ToolTint.AMBER, "${"%,d".format(st)} of ${"%,d".format(goal)} steps",
                    if (st >= goal) "Goal reached — nice walking" else "${"%,d".format(goal - st)} to go",
                ) { onTool(HomeTool.STEPS) }
            }
        }
        val habits = Habits.active()
        if (habits.isNotEmpty() && HomePrefs.shows(TodayRow.HABITS)) {
            val (done, due) = Habits.todayProgress()
            val left = habits.filter { it.dueOn(today) && it.freq != "week" && !Habits.done(it, today) && !Habits.isRest(it, today) }.map { it.name }
            add {
                TodayLine(
                    Icons.Rounded.TaskAlt, ToolTint.PINK,
                    if (due == 0) "No habits due today" else "$done of $due habit${if (due == 1) "" else "s"} done",
                    when {
                        due == 0 -> "Tap to see your habits"
                        left.isEmpty() -> "All done today"
                        else -> left.take(2).joinToString(" and ") + if (left.size > 2) " and ${left.size - 2} more left" else " left"
                    },
                ) { onTool(HomeTool.HABITS) }
            }
        }
        val night = Sleep.current()
        val lastNight = Sleep.lastNight()
        if ((night != null || Sleep.settings.remind || lastNight != null) && HomePrefs.shows(TodayRow.SLEEP)) {
            add {
                TodayLine(
                    Icons.Rounded.Bedtime, ToolTint.INDIGO,
                    when {
                        night != null -> "Sleeping since ${clockText(night.start)}"
                        Sleep.settings.remind -> "Bedtime ${timeText(Sleep.settings.bedtime)}"
                        else -> "Sleep"
                    },
                    when {
                        night != null -> "Tap for Good morning"
                        lastNight != null -> "Last night ${hoursText(lastNight.hours)}"
                        else -> "Sounds and your sleep log"
                    },
                ) { onTool(HomeTool.SLEEP) }
            }
        }
        if (Breaks.settings.setUp && HomePrefs.shows(TodayRow.BREAKS)) {
            val shown = Breaks.shownToday()
            val taken = Breaks.takenToday()
            val next = Breaks.next()
            add {
                TodayLine(
                    Icons.Rounded.Visibility, ToolTint.GREEN,
                    if (maxOf(shown, taken) == 0) "No breaks yet today" else "$taken of ${maxOf(shown, taken)} breaks taken",
                    next?.takeIf { com.agani.syncup.forest.FDays.of(it.first) == today }?.let { "Next ${if (it.second == "eye") "eye" else "move"} break ${clockText(it.first)}" } ?: "Done for today",
                ) { onTool(HomeTool.BREAKS) }
            }
        }
        if (Mood.entries().isNotEmpty() && HomePrefs.shows(TodayRow.MOOD)) {
            val n = Mood.countToday()
            val mood = Mood.lastToday()
            add {
                TodayLine(
                    Icons.Rounded.SentimentSatisfied, ToolTint.LIME,
                    if (mood != null) "Feeling ${listOf("bad", "low", "okay", "good", "great")[mood - 1]} · $n check-in${if (n == 1) "" else "s"}" else "How are you today?",
                    if (mood != null) "Tap to check in again" else "One tap to check in",
                ) { onTool(HomeTool.MOOD) }
            }
        }
    }
}

/** One Today row: tile, two lines, an optional small action in the tool's colour, else a chevron. */
@Composable
private fun TodayLine(icon: ImageVector, tint: ToolTint, title: String, sub: String, action: Pair<String, () -> Unit>? = null, onClick: () -> Unit) {
    if (action == null) {
        CardRow(title, sub, onClick = onClick) { Tile(icon, tint) }
    } else {
        CardRow(
            title, sub, onClick = onClick,
            end = {
                Text(
                    action.first, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint.icon, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.tile).clickable(onClick = action.second)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                )
            },
        ) { Tile(icon, tint) }
    }
}

/** Switches for the Today card's rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayRowChips() {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TodayRow.entries.forEach { r ->
            val on = HomePrefs.shows(r)
            val shape = RoundedCornerShape(50)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.height(34.dp).clip(shape).background(if (on) cs.primaryContainer else cs.surfaceContainerLowest)
                    .border(1.dp, if (on) Color.Transparent else cs.outlineVariant, shape)
                    .clickable { HomePrefs.changeRow(r, !on) }.padding(horizontal = 14.dp),
            ) {
                Text(
                    r.title, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) cs.onPrimaryContainer else cs.onSurface,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- tools and nearby
private class ToolSpec(val tool: HomeTool, val icon: ImageVector, val title: String, val tint: ToolTint)

private fun homeTools(): List<ToolSpec> = listOf(
    ToolSpec(HomeTool.CALENDAR, Icons.Rounded.CalendarMonth, "Calendar", ToolTint.INDIGO),
    ToolSpec(HomeTool.WATER, Icons.Rounded.WaterDrop, "Water", ToolTint.CYAN),
    ToolSpec(HomeTool.HABITS, Icons.Rounded.TaskAlt, "Habits", ToolTint.PINK),
    ToolSpec(HomeTool.FOREST, Icons.Rounded.Eco, "Forest", ToolTint.LIME),
    ToolSpec(HomeTool.SHARE, Icons.Rounded.SwapHoriz, "Share", ToolTint.BLUE),
    ToolSpec(HomeTool.WALKIE, Icons.Rounded.RecordVoiceOver, "Walkie", ToolTint.GREEN),
    ToolSpec(HomeTool.MUSIC, Icons.Rounded.LibraryMusic, "Music", ToolTint.VIOLET),
    ToolSpec(HomeTool.VIDEO, Icons.Rounded.SmartDisplay, "Videos", ToolTint.ROSE),
    ToolSpec(HomeTool.RADIO, Icons.Rounded.Radio, "Radio", ToolTint.ORANGE),
    ToolSpec(HomeTool.SENSORS, Icons.Rounded.Explore, "Sensors", ToolTint.SKY),
    ToolSpec(HomeTool.SLEEP, Icons.Rounded.Bedtime, "Sleep", ToolTint.INDIGO),
    ToolSpec(HomeTool.MOOD, Icons.Rounded.SentimentSatisfied, "Mood", ToolTint.LIME),
)

private class NearbyRow(val tool: HomeTool, val icon: ImageVector, val title: String, val subtitle: String, val tint: ToolTint)

/** What's going on with phones nearby right now (none = no card). */
@Composable
private fun nearbyRows(): List<NearbyRow> {
    val rows = ArrayList<NearbyRow>()
    val g = com.agani.syncup.group.GroupLink
    if (g.active) {
        rows.add(
            NearbyRow(
                HomeTool.GROUP, Icons.Rounded.Groups,
                if (g.phase == com.agani.syncup.group.GroupPhase.JOINING) "Joining ${g.groupName}…" else "You're in ${g.groupName}",
                "${g.mode.label} · ${g.people.size} phone${if (g.people.size == 1) "" else "s"}",
                ToolTint.INDIGO,
            ),
        )
    } else {
        com.agani.syncup.party.partyStatus()?.let { rows.add(NearbyRow(HomeTool.PARTY, Icons.Rounded.MusicNote, "Party", it, ToolTint.PINK)) }
        com.agani.syncup.watchparty.watchPartyStatus()?.let { rows.add(NearbyRow(HomeTool.WATCH_PARTY, Icons.Rounded.SmartDisplay, "Watch Party", it, ToolTint.ROSE)) }
        com.agani.syncup.walkie.walkieStatus()?.let { rows.add(NearbyRow(HomeTool.WALKIE, Icons.Rounded.RecordVoiceOver, "Walkie Talkie", it, ToolTint.GREEN)) }
    }
    val qs = com.agani.syncup.quickshare.QuickShare
    if (qs.busy) {
        val total = qs.totalBytes
        val pct = if (total > 0) " · ${qs.doneBytes * 100 / total}%" else ""
        val what = if (qs.textMode) "text" else "files"
        rows.add(
            NearbyRow(
                HomeTool.SHARE, Icons.Rounded.SwapHoriz, "SyncUp Share",
                (if (qs.role == com.agani.syncup.quickshare.QsRole.SEND) "Sending $what" else "Receiving $what") + pct,
                ToolTint.BLUE,
            ),
        )
    }
    return rows
}

// ---------------------------------------------------------------------------- "SyncUp has grown"
@Composable
private fun GrownCard(onOpenBrowse: () -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(CardShape)
            .background(Brush.linearGradient(listOf(cs.primaryContainer, ToolTint.VIOLET.tile)))
            .padding(start = 16.dp, top = 14.dp, end = 6.dp, bottom = 8.dp),
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = ToolTint.VIOLET.icon, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("SyncUp has grown", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(
                "Your browser is now in Browse, with your tabs just where you left them. Calendar, Forest and the other tools live here too.",
                fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp),
            )
            Row(Modifier.padding(top = 6.dp)) {
                TextButton(onClick = onOpenBrowse) { Text("Open Browse", fontWeight = FontWeight.Medium) }
                TextButton(onClick = onDone) { Text("Got it", fontWeight = FontWeight.Medium) }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClickLabel = "Close", onClick = onDone),
        ) { Icon(Icons.Rounded.Close, "Close", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
    }
}

// ---------------------------------------------------------------------------- Customise Home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomiseSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLowest, onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Customise Home", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 4.dp))
            HomeSettings()
        }
    }
}

/** "Open SyncUp to" and the cards' switches — in Customise Home and in Settings → Home. */
@Composable
fun HomeSettings(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Label("Open SyncUp to", Modifier.padding(top = 6.dp, bottom = 8.dp))
        // A one-link user can also open straight into that link (the same switch as on its Home card).
        val one = HomePrefs.singleLink
        PillTabs(
            listOfNotNull("Home", "Browse", one?.title),
            selected = if (HomePrefs.opensLink) 2 else if (HomePrefs.startOnHome) 0 else 1,
            onSelect = {
                if (it == 2) {
                    HomePrefs.changeStartLink(true)
                } else {
                    if (one != null) HomePrefs.changeStartLink(false)
                    HomePrefs.changeStartOnHome(it == 0)
                }
            },
        )
        Label("Cards on Home", Modifier.padding(top = 16.dp, bottom = 2.dp))
        for (card in HomeCard.entries) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { HomePrefs.changeCard(card, !HomePrefs.shows(card)) }.heightIn(min = 56.dp).padding(vertical = 8.dp),
            ) {
                Icon(cardIcon(card), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.title, fontSize = 15.sp, lineHeight = 20.sp, color = cs.onSurface)
                    Text(card.hint, fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = HomePrefs.shows(card), onCheckedChange = { HomePrefs.changeCard(card, it) })
            }
        }
        if (HomePrefs.shows(HomeCard.TODAY)) {
            Label("Rows in Today", Modifier.padding(top = 16.dp, bottom = 8.dp))
            TodayRowChips()
        }
        Text(
            "A card shows only when it has something to say; a Wellbeing row once you use that tool.",
            fontSize = 12.5.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

private fun cardIcon(card: HomeCard): ImageVector = when (card) {
    HomeCard.TODAY -> Icons.Rounded.Today
    HomeCard.PLAYING -> Icons.Rounded.MusicNote
    HomeCard.NEARBY -> Icons.Rounded.Groups
    HomeCard.TOOLS -> Icons.Rounded.GridView
    HomeCard.LINKS -> Icons.Rounded.Link
}
