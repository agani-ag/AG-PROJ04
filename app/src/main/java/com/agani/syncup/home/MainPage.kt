package com.agani.syncup.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.BrowserDb
import com.agani.syncup.browser.BrowserSettings
import com.agani.syncup.browser.HistoryEntry
import com.agani.syncup.browser.LibraryPage
import com.agani.syncup.browser.MostUsed
import com.agani.syncup.browser.RecommendedSites
import com.agani.syncup.browser.ShortcutCatalog
import com.agani.syncup.browser.UrlInput
import com.agani.syncup.browser.UsedSite
import com.agani.syncup.browser.ui.BadgeTextStyle
import com.agani.syncup.calendar.CalendarStore
import com.agani.syncup.calendar.Days
import com.agani.syncup.data.AnnouncementDto
import com.agani.syncup.data.PartnerDto
import com.agani.syncup.data.UrlItem
import com.agani.syncup.forest.ForestRules
import com.agani.syncup.forest.rememberForestNow
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.party.Party
import com.agani.syncup.quickshare.QsRole
import com.agani.syncup.quickshare.QuickShare
import com.agani.syncup.ui.theme.CardShape
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.walkie.Walkie
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the main page can open; MainActivity knows where each one lives. */
enum class HomeTool {
    CALENDAR, FOREST, SHARE, WALKIE, MUSIC, VIDEO, RADIO, SENSORS, PARTY, WATCH_PARTY, GROUP,
    WATER, STEPS, HABITS, SLEEP, BREAKS, MOOD, CHANNELS, DECIDE, SHOPPING,
}

/** The pages behind the account sheet (and a search result that opens one); MainActivity opens them. */
object SettingsPage {
    const val ACCOUNT = "account"
    const val SYNC = "sync"
    const val BROWSER = "browser"
    const val PRIVACY = "privacy"
    const val APPEARANCE = "appearance"
    const val NOTIFICATIONS = "notifications"
    const val ABOUT = "about"
    const val DNS = "dns"
    const val SITE_SETTINGS = "site_settings"
    const val PARTNERS = "partners"
    const val CHAT = "chat"
    const val INBOX = "inbox"
}

/**
 * The main page's own state, kept by MainActivity so coming back from a tool finds the page as it was
 * (scrolled to the same place, the same sections opened).
 */
class MainPageState {
    val list = LazyListState()
    var todayOpen by mutableStateOf(false)
    var linksOpen by mutableStateOf(false)
    var sitesOpen by mutableStateOf(false)
    var toolsOpen by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    /** Websites → Recommended: the most used sites with their visits (7 days, 30 days, all time). */
    var recommendedOpen by mutableStateOf(false)
}

// The "easy for every age" sizes (docs/mockup/v7/simple_ui_page.py, .phone.easy). Since 17 Nov 2026 the whole app is drawn a
// size smaller (AgHubTheme: layout 90%, text 94%), so these come out at normal app sizes.
private val ROW_MIN = 64.dp
private val TITLE_SIZE = 16.sp
private val SUB_SIZE = 13.5.sp
private val SECTION_SIZE = 18.sp

// ============================================================================ the page
/**
 * SyncUp's one main page (user, 14 Nov 2026): no tab bar and no Home, Tools or You pages. A greeting with
 * the bell and the account picture; one search for websites, tools, settings and history; chips for tabs,
 * bookmarks, history, downloads and Incognito; a partner user's partners first; Today; what's happening
 * now; every tool under small headings; SyncUp links; websites. Nothing to customise: long sections show
 * their first few and open the rest in place. Pulling the page down fetches new SyncUp links (user, 16 Nov 2026).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPage(
    state: MainPageState,
    signedIn: Boolean,
    userName: String?,
    links: List<UrlItem>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    db: BrowserDb,
    tabCount: Int,
    radioAvailable: Boolean,
    signupEnabled: Boolean,
    announcement: AnnouncementDto?,
    chatUnread: Int,
    waitingPartners: List<PartnerDto>,
    onEnablePartner: suspend (id: String, password: String) -> Result<PartnerDto?>,
    onPartnerEnabled: (name: String) -> Unit,
    onTool: (HomeTool) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onOpenLink: (UrlItem) -> Unit,
    onTabs: () -> Unit,
    onLibrary: (LibraryPage) -> Unit,
    onIncognito: () -> Unit,
    onNotifications: () -> Unit,
    onAvatar: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onSetting: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    remember { Wellbeing.init(context) }
    val tools = mainTools(radioAvailable)
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onOpenUrl(UrlInput.toUrl(spoken))
    }
    fun voiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or say a web address")
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Voice search isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    if (state.searching) {
        SearchScreen(
            state = state,
            signedIn = signedIn,
            tools = tools,
            db = db,
            onClose = {
                state.searching = false
                state.query = ""
            },
            onTool = { t ->
                state.searching = false
                state.query = ""
                onTool(t)
            },
            onOpenUrl = { url ->
                state.searching = false
                state.query = ""
                onOpenUrl(url)
            },
            onSetting = { key ->
                state.searching = false
                state.query = ""
                onSetting(key)
            },
            onVoice = { voiceSearch() },
        )
        return
    }
    if (state.recommendedOpen) {
        // The full ranking from History (user, 16 Nov 2026): how many times each site was used, over 7 days, 30 days or all time.
        Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
            com.agani.syncup.browser.RecommendedPage(
                onBack = { state.recommendedOpen = false },
                onOpen = { url ->
                    state.recommendedOpen = false
                    onOpenUrl(url)
                },
                onOpenInNewTab = onOpenInNewTab,
                onRemove = {
                    BrowserSettings.removeFromRecommended(it.key)
                    Toast.makeText(context, "${it.title} removed from Recommended", Toast.LENGTH_SHORT).show()
                },
            )
        }
        return
    }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val (partnerSection, otherLinks) = remember(links) { mainPageLinks(links) }
    val waiting = waitingPartners.filter { signedIn && it.id !in HomePrefs.laterPartners }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        // The insets sit on the box so the spinner comes down below the status bar.
        modifier = Modifier.fillMaxSize().background(cs.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        LazyColumn(
            state = state.list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp + bottom),
        ) {
            item(key = "top") {
                val first = userName?.trim()?.substringBefore(' ')?.takeIf { signedIn && it.isNotBlank() }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(top = 8.dp, bottom = 6.dp)) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        if (first != null) Text(greeting(), fontSize = 14.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant)
                        Text(
                            first ?: "SyncUp", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Bell(onNotifications)
                    Box(Modifier.padding(start = 4.dp, end = 2.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(42.dp).clip(CircleShape)
                                .background(if (signedIn) cs.primary else cs.surfaceContainerHigh)
                                .clickable(onClickLabel = "Your account and settings", onClick = onAvatar),
                        ) {
                            val initial = userName?.trim()?.take(1)?.uppercase()?.takeIf { signedIn && it.isNotBlank() }
                            if (initial != null) Text(initial, color = cs.onPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            else Icon(Icons.Rounded.Person, "Your account and settings", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        }
                        if (signedIn && chatUnread > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).size(13.dp).clip(CircleShape).background(cs.surface).padding(2.dp)
                                    .clip(CircleShape).background(cs.error),
                            )
                        }
                    }
                }
            }
            item(key = "search") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(56.dp).appCard(RoundedCornerShape(50))
                        .clickable(onClickLabel = "Search") { state.searching = true }.padding(start = 18.dp, end = 6.dp),
                ) {
                    Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Search anything", fontSize = 16.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Voice search") { voiceSearch() },
                    ) { Icon(Icons.Rounded.Mic, "Voice search", tint = cs.onSurfaceVariant) }
                }
            }
            item(key = "chips") {
                Chips(tabCount, onTabs, onLibrary, onIncognito)
            }
            val ann = announcement
            if (signedIn && ann != null && ann.active && !ann.fullscreen && (ann.title.isNotBlank() || ann.message.isNotBlank())) {
                item(key = "announcement") { AnnouncementStrip(ann) }
            }
            item(key = "nudge") { com.agani.syncup.browser.DefaultBrowserNudge(Modifier.padding(top = 14.dp)) }
            if (!signedIn) {
                item(key = "welcome") { WelcomeCard(signupEnabled, onSignIn, onCreateAccount) }
            }
            // A partner user's partners come first, right under the search (user, 14 Nov 2026).
            // A partner waiting for its password always shows here: it needs the person to act.
            if (waiting.isNotEmpty() || partnerSection.isNotEmpty()) {
                item(key = "partners-head") { Header(if (waiting.size + partnerSection.size == 1) "Your partner" else "Your partners") }
                for (p in waiting) {
                    item(key = "waiting-${p.id}") { PartnerWaitingCard(p, onEnablePartner, onPartnerEnabled) }
                }
                for ((name, items) in partnerSection) {
                    item(key = "partner-$name") { PartnerCard(name, items, onOpenLink) }
                }
            }
            item(key = "today") {
                Header("Today · ${todayText()}")
                val rows = todayRows(onTool)
                Card {
                    val shown = if (state.todayOpen || rows.size <= TODAY_FIRST + 1) rows else rows.take(TODAY_FIRST)
                    if (rows.isEmpty()) {
                        Text("Nothing for today yet", fontSize = SUB_SIZE, color = cs.onSurfaceVariant, modifier = Modifier.padding(18.dp))
                    }
                    shown.forEachIndexed { i, row ->
                        if (i > 0) Divider()
                        row()
                    }
                    if (rows.size > TODAY_FIRST + 1) {
                        Divider()
                        MoreLine(
                            if (state.todayOpen) "Show less" else "Show ${rows.size - TODAY_FIRST} more", state.todayOpen,
                        ) { state.todayOpen = !state.todayOpen }
                    }
                }
            }
            item(key = "now") { HappeningNow(onTool) }
            if (otherLinks.isNotEmpty()) {
                item(key = "links") {
                    Header("SyncUp links")
                    Card {
                        val shown = if (state.linksOpen || otherLinks.size <= LINKS_FIRST + 1) otherLinks else otherLinks.take(LINKS_FIRST)
                        shown.forEachIndexed { i, l ->
                            if (i > 0) Divider()
                            // The link's own description, like the SyncUp section (user, 16 Nov 2026); a partner's link
                            // without one says whose it is; otherwise just the name.
                            val sub = l.description?.trim()?.takeIf { it.isNotEmpty() }
                                ?: l.sourceName?.trim()?.takeIf { l.source == "partner" && it.isNotEmpty() }?.let { "From $it" }
                            CardRow(l.title, sub, onClick = { onOpenLink(l) }, subLines = 2) {
                                com.agani.syncup.browser.LinkIcon(l, 40.dp)
                            }
                        }
                        if (otherLinks.size > LINKS_FIRST + 1) {
                            Divider()
                            MoreLine(if (state.linksOpen) "Show less" else "Show all ${otherLinks.size}", state.linksOpen) { state.linksOpen = !state.linksOpen }
                        }
                    }
                }
            }
            // Tools sit below SyncUp links (user, 16 Nov 2026).
            item(key = "tools") {
                Header("Tools")
                ToolGrid(tools, onTool, open = state.toolsOpen)
                // The first two rows; "Show all 18 tools" opens every tool here, under its heading (user, 16 Nov 2026).
                Column(Modifier.padding(top = 14.dp).fillMaxWidth().appCard(CardShape)) {
                    MoreLine(if (state.toolsOpen) "Show less" else "Show all ${tools.size} tools", state.toolsOpen) { state.toolsOpen = !state.toolsOpen }
                }
            }
            item(key = "sites") { Websites(state, onOpenUrl, onOpenInNewTab, onRecommended = { state.recommendedOpen = true }) }
        }
        // The phone's navigation bar (3 buttons or the gesture line) sits on the page's own colour, never over a
        // row that scrolls under it (user, 17 Nov 2026).
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(cs.surface))
    }
}

/**
 * Which links go where on the main page (user, 17 Nov 2026): a partner gets its own section (by partner name) only
 * when partner links are all there is; with SyncUp's own or the general links too, every link is in one list under
 * SyncUp links, in the server's order.
 */
internal fun mainPageLinks(links: List<UrlItem>): Pair<Map<String, List<UrlItem>>, List<UrlItem>> {
    val partnerLinks = links
        .filter { it.source == "partner" && !it.sourceName.isNullOrBlank() }
        .groupBy { it.sourceName!!.trim() }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val partnersOnly = links.all { l -> partnerLinks.values.any { l in it } }
    return if (partnersOnly) partnerLinks to emptyList() else emptyMap<String, List<UrlItem>>() to links
}

private const val TODAY_FIRST = 3
private const val LINKS_FIRST = 3
private const val SITES_FIRST = 8

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

// ============================================================================ building blocks
/** The bell: opens Notifications; the red number is how many are new. */
@Composable
private fun Bell(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val n = com.agani.syncup.notifications.Inbox.newCount
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = if (n > 0) "Notifications, $n new" else "Notifications" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Notifications, null, tint = cs.onSurface, modifier = Modifier.size(26.dp))
        if (n > 0) {
            // The number sits on a ring of the page colour so it stands off the bell.
            val pill = RoundedCornerShape(50)
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).clip(pill).background(cs.surface).padding(2.dp)
                    .heightIn(min = 16.dp).widthIn(min = 16.dp).clip(pill).background(cs.error).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (n > 9) "9+" else "$n", style = BadgeTextStyle, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = cs.onError)
            }
        }
    }
}

/** A section's title: big and clear ("Today · Sat 14 Nov", "Tools", "SyncUp links"). */
@Composable
private fun Header(text: String) {
    Text(
        text, fontSize = SECTION_SIZE, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 26.dp, bottom = 12.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    // Show more / Show less grows and shrinks the card smoothly instead of jumping (user, 17 Nov 2026).
    Column(Modifier.fillMaxWidth().animateContentSize(SMOOTH).appCard(CardShape)) { content() }
}

/** How "Show more / Show less" opens and closes: a quick, gentle ease. */
private val SMOOTH = tween<androidx.compose.ui.unit.IntSize>(durationMillis = 280, easing = FastOutSlowInEasing)

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A tool's icon on its own soft colour. */
@Composable
private fun Tile(icon: ImageVector, tint: ToolTint, size: Dp = 40.dp, iconSize: Dp = 22.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(tint.tile),
    ) { Icon(icon, null, tint = tint.icon, modifier = Modifier.size(iconSize)) }
}

/** One row in a card: a tile, two lines, then an optional small action, else a chevron. */
@Composable
private fun CardRow(
    title: String,
    sub: String?,
    onClick: () -> Unit,
    subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subLines: Int = 1,
    chevron: Boolean = true,
    end: (@Composable () -> Unit)? = null,
    leading: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = ROW_MIN).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = TITLE_SIZE, lineHeight = 21.sp, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) {
                Text(sub, fontSize = SUB_SIZE, lineHeight = 18.sp, color = subColor, maxLines = subLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (end != null) {
            Spacer(Modifier.width(8.dp))
            end()
        } else if (chevron) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

/** The line at the bottom of a long section: opens the rest right here ("Show 5 more ▾"), or closes it again. */
@Composable
private fun MoreLine(text: String, open: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).height(54.dp),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.primary)
        Spacer(Modifier.width(4.dp))
        // One arrow that turns over as the section opens or closes.
        val turn by animateFloatAsState(if (open) 180f else 0f, tween(280, easing = FastOutSlowInEasing), label = "more-arrow")
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = cs.primary, modifier = Modifier.size(22.dp).rotate(turn))
    }
}

// ---------------------------------------------------------------------------- chips
/** Tabs (with the count) · Bookmarks · History · Downloads · Incognito — one tap each, sliding sideways. */
@Composable
private fun Chips(tabCount: Int, onTabs: () -> Unit, onLibrary: (LibraryPage) -> Unit, onIncognito: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        // The chips run to the screen's edges, past the page's side margins.
        modifier = Modifier
            .layout { measurable, constraints ->
                val bleed = 16.dp.roundToPx()
                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .padding(top = 14.dp),
    ) {
        item {
            Chip(null, "Tabs", onTabs) {
                Box(
                    Modifier.size(22.dp).border(2.dp, cs.onSurface, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(if (tabCount > 99) "∞" else "$tabCount", fontSize = 11.5.sp, lineHeight = 11.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurface) }
            }
        }
        item { Chip(Icons.Rounded.Bookmarks, "Bookmarks", { onLibrary(LibraryPage.BOOKMARKS) }) }
        item { Chip(Icons.Rounded.History, "History", { onLibrary(LibraryPage.HISTORY) }) }
        item {
            val running = com.agani.syncup.downloads.Downloads.tasks.count { it.state == com.agani.syncup.downloads.DlState.RUNNING }
            Chip(Icons.Rounded.Download, if (running > 0) "Downloads · $running" else "Downloads", { onLibrary(LibraryPage.DOWNLOADS) })
        }
        item { Chip(Icons.Rounded.VisibilityOff, "Incognito", onIncognito) }
    }
}

@Composable
private fun Chip(icon: ImageVector?, label: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(42.dp).clip(shape).background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(start = 14.dp, end = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

// ---------------------------------------------------------------------------- welcome, announcement
/** Signed out: what signing in adds, and that it's optional. */
@Composable
private fun WelcomeCard(signupEnabled: Boolean, onSignIn: () -> Unit, onCreate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(CardShape).background(cs.primaryContainer).padding(18.dp)) {
        Text("Welcome to SyncUp", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
        Text(
            "Sign in to keep your tabs, to-dos and forest on every phone. It's optional: everything here works without it.",
            fontSize = 14.5.sp, lineHeight = 21.sp, color = cs.onPrimaryContainer.copy(alpha = .9f), modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSignIn, modifier = Modifier.height(46.dp)) { Text("Sign in", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            if (signupEnabled) {
                OutlinedButton(onClick = onCreate, modifier = Modifier.height(46.dp)) { Text("Create account", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun AnnouncementStrip(ann: AnnouncementDto) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(CardShape).background(cs.primaryContainer).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(Icons.Rounded.Campaign, null, tint = cs.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (ann.title.isNotBlank()) Text(ann.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer)
            if (ann.message.isNotBlank()) Text(ann.message, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onPrimaryContainer.copy(alpha = .9f))
        }
    }
}

// ---------------------------------------------------------------------------- partners
/** A partner's first letter on a soft colour picked from its name (partners have no logo). */
@Composable
private fun LetterTile(name: String, size: Dp = 42.dp) {
    val tint = ToolTint.entries[Math.floorMod(name.trim().lowercase().hashCode(), ToolTint.entries.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * .32f)).background(tint.tile),
    ) {
        Text(name.trim().take(1).uppercase().ifBlank { "•" }, color = tint.icon, fontWeight = FontWeight.Bold, fontSize = (size.value * .42f).sp)
    }
}

/** One partner: its name, then every one of its links as a big tile, 3 a row. */
@Composable
private fun PartnerCard(name: String, items: List<UrlItem>, onOpen: (UrlItem) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().appCard(CardShape).padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            LetterTile(name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Your partner on SyncUp", fontSize = SUB_SIZE, color = cs.onSurfaceVariant)
            }
        }
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { l ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onOpen(l) }.padding(vertical = 10.dp, horizontal = 2.dp),
                    ) {
                        com.agani.syncup.browser.LinkIcon(l, 56.dp)
                        Text(
                            l.title, fontSize = 14.sp, lineHeight = 18.sp, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** "Sunrise School added you": that partner's password right here (the account sheet's Partners does the same). */
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
                .onFailure { error = it.message ?: "Couldn't enable" }
            busy = false
        }
    }
    Column(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().appCard(CardShape).border(2.dp, ToolTint.ORANGE.icon, CardShape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterTile(partner.partner)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${partner.partner} added you", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Enter the password ${partner.partner} gave you to see their links.",
                    fontSize = SUB_SIZE, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        com.agani.syncup.browser.PasswordField(password, { password = it; error = null }, label = "${partner.partner} password", error = error, onDone = { turnOn() })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { turnOn() }, enabled = !busy && password.isNotBlank(), modifier = Modifier.height(46.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary) else Text("Enable", fontSize = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { HomePrefs.partnerLater(partner.id) }, enabled = !busy) { Text("Later", fontSize = 15.sp) }
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
 * Today's rows: Calendar and Forest, then each Wellbeing tool in use. Nothing to switch on or off: a
 * Wellbeing row simply appears once that tool is used.
 */
@Composable
private fun todayRows(onTool: (HomeTool) -> Unit): List<@Composable () -> Unit> {
    val ctx = LocalContext.current
    // Redraw when a Wellbeing tool changes.
    @Suppress("UNUSED_VARIABLE") val v = Water.version + Steps.version + Habits.version + Sleep.version + Mood.version + Breaks.version
    val today = com.agani.syncup.forest.FDays.today()
    return buildList<@Composable () -> Unit> {
        add { CalendarRow { onTool(HomeTool.CALENDAR) } }
        add { ForestRow { onTool(HomeTool.FOREST) } }
        val ws = Water.settings
        if (ws.setUp) {
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
        if (Steps.counting(ctx)) {
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
        if (habits.isNotEmpty()) {
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
        if (night != null || Sleep.settings.remind || lastNight != null) {
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
        if (Breaks.settings.setUp) {
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
        if (Mood.entries().isNotEmpty()) {
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
                    action.first, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = tint.icon, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.tile).clickable(onClick = action.second)
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                )
            },
        ) { Tile(icon, tint) }
    }
}

// ---------------------------------------------------------------------------- happening now
private class NowRow(val tool: HomeTool, val icon: ImageVector, val title: String, val subtitle: String, val tint: ToolTint)

/** What's going on with phones nearby, and other things running right now (none = no rows). */
@Composable
private fun nowRows(): List<NowRow> {
    val rows = ArrayList<NowRow>()
    val g = com.agani.syncup.group.GroupLink
    if (g.active) {
        rows.add(
            NowRow(
                HomeTool.GROUP, Icons.Rounded.Groups,
                if (g.phase == com.agani.syncup.group.GroupPhase.JOINING) "Joining ${g.groupName}…" else "You're in ${g.groupName}",
                "${g.mode.label} · ${g.people.size} phone${if (g.people.size == 1) "" else "s"}",
                ToolTint.INDIGO,
            ),
        )
    } else {
        com.agani.syncup.party.partyStatus()?.let { rows.add(NowRow(HomeTool.PARTY, Icons.Rounded.MusicNote, "Party", it, ToolTint.PINK)) }
        com.agani.syncup.watchparty.watchPartyStatus()?.let { rows.add(NowRow(HomeTool.WATCH_PARTY, Icons.Rounded.SmartDisplay, "Watch Party", it, ToolTint.ROSE)) }
        com.agani.syncup.walkie.walkieStatus()?.let { rows.add(NowRow(HomeTool.WALKIE, Icons.Rounded.RecordVoiceOver, "Walkie Talkie", it, ToolTint.GREEN)) }
    }
    val qs = QuickShare
    if (qs.busy) {
        val total = qs.totalBytes
        val pct = if (total > 0) " · ${qs.doneBytes * 100 / total}%" else ""
        val what = if (qs.textMode) "text" else "files"
        rows.add(
            NowRow(
                HomeTool.SHARE, Icons.Rounded.SwapHoriz, "SyncUp Share",
                (if (qs.role == QsRole.SEND) "Sending $what" else "Receiving $what") + pct,
                ToolTint.BLUE,
            ),
        )
    }
    if (Sleep.soundsOn) rows.add(NowRow(HomeTool.SLEEP, Icons.Rounded.Bedtime, "Sleep sounds", "Playing", ToolTint.INDIGO))
    if (com.agani.syncup.sensors.Torch.on) rows.add(NowRow(HomeTool.SENSORS, Icons.Rounded.FlashlightOn, "Flashlight", "On", ToolTint.SKY))
    return rows
}

/** Everything playing or going on right now, in one card (user, 16 Nov 2026). Hidden when nothing is on. */
@Composable
private fun HappeningNow(onTool: (HomeTool) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val player = LocalPlayer.current
    val rows = nowRows()
    val playing = player?.item != null
    if (!playing && rows.isEmpty()) return
    Column {
        Header("Happening now")
        Card {
            if (player != null && playing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onTool(if (player.isRadio) HomeTool.RADIO else HomeTool.MUSIC) }
                        .heightIn(min = ROW_MIN).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    com.agani.syncup.music.ArtworkTile(player.artUri, player.isRadio, Modifier.size(40.dp), radius = 13.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(player.title, fontSize = TITLE_SIZE, lineHeight = 21.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (player.isRadio) "Radio" else "Music") + (if (player.isPlaying) " · playing" else " · paused"),
                            fontSize = SUB_SIZE, lineHeight = 18.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(46.dp).clip(CircleShape).background(cs.surfaceContainer).clickable(
                            onClickLabel = if (player.isPlaying) "Pause" else "Play",
                        ) { player.toggle() },
                    ) {
                        Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play", tint = cs.onSurface)
                    }
                }
            }
            rows.forEachIndexed { i, r ->
                if (i > 0 || playing) Divider()
                CardRow(r.title, r.subtitle, onClick = { onTool(r.tool) }) { Tile(r.icon, r.tint) }
            }
        }
    }
}

// ---------------------------------------------------------------------------- tools
private class ToolDef(
    val tool: HomeTool,
    val icon: ImageVector,
    /** Under its tile. */
    val short: String,
    /** In search results. */
    val title: String,
    val tint: ToolTint,
    val group: String,
    val about: String,
    /** Other words people search for it by. */
    val words: String,
    val running: Boolean = false,
    val status: String? = null,
    val greyed: Boolean = false,
) {
    fun matches(q: String): Boolean {
        val s = q.lowercase()
        return short.lowercase().contains(s) || title.lowercase().contains(s) ||
            words.split(' ').any { it.startsWith(s) } || about.lowercase().split(' ', ',').any { it.startsWith(s) }
    }
}

private val GROUPS = listOf("Media", "Nearby", "Everyday", "Wellbeing")

/** Every tool, in group order, with what it's doing right now. */
@Composable
private fun mainTools(radioAvailable: Boolean): List<ToolDef> {
    val ctx = LocalContext.current
    val player = LocalPlayer.current
    @Suppress("UNUSED_VARIABLE") val wv = Water.version + Steps.version + Habits.version + Sleep.version + Mood.version + Breaks.version
    val musicOn = player?.isPlaying == true && !player.isRadio
    val radioOn = Party.active || (player?.isPlaying == true && player.isRadio)
    val watch = com.agani.syncup.watchparty.watchPartyStatus()
    val stepsHere = Steps.present(ctx)
    val ws = Water.settings
    val (hDone, hDue) = Habits.todayProgress()
    val night = Sleep.current()
    val g = com.agani.syncup.group.GroupLink
    return listOf(
        ToolDef(HomeTool.MUSIC, Icons.Rounded.LibraryMusic, "Music", "Music", ToolTint.VIOLET, "Media", "Songs on your phone", "songs player audio mp3",
            running = musicOn, status = player?.title?.takeIf { musicOn && it.isNotBlank() }?.let { "Playing: $it" }),
        ToolDef(HomeTool.VIDEO, Icons.Rounded.SmartDisplay, "Videos", "Video player", ToolTint.ROSE, "Media", "Videos & Watch Party", "video movies watch party player",
            running = watch != null, status = watch),
        ToolDef(HomeTool.CHANNELS, Icons.Rounded.LiveTv, "Channels", "Channels", ToolTint.GREEN, "Media", "TV channels from an M3U link", "tv iptv m3u live"),
        ToolDef(HomeTool.RADIO, Icons.Rounded.Radio, "Radio", "Radio", ToolTint.ORANGE, "Media",
            if (radioAvailable) "Parties nearby & live stations" else "Parties nearby", "party stations live fm",
            running = radioOn, status = com.agani.syncup.party.partyStatus()),
        ToolDef(HomeTool.SHARE, Icons.Rounded.SwapHoriz, "Share", "SyncUp Share", ToolTint.BLUE, "Nearby", "Send files & text to a nearby phone", "send files transfer text nearby",
            running = QuickShare.busy),
        ToolDef(HomeTool.WALKIE, Icons.Rounded.RecordVoiceOver, "Walkie", "Walkie Talkie", ToolTint.GREEN, "Nearby", "Talk to friends nearby", "talk voice push",
            running = Walkie.active, status = com.agani.syncup.walkie.walkieStatus()),
        ToolDef(HomeTool.GROUP, Icons.Rounded.Groups, "Group", "Group", ToolTint.INDIGO, "Nearby", "Up to 8 phones: music, watch, talk, share, photos", "together friends family photos",
            running = g.active, status = if (g.active) "In a group · ${g.mode.label}" else null),
        ToolDef(HomeTool.CALENDAR, Icons.Rounded.CalendarMonth, "Calendar", "Calendar", ToolTint.INDIGO, "Everyday", "To-dos & reminders", "todo tasks reminders countdown dates",
            status = com.agani.syncup.calendar.calendarStatus()),
        ToolDef(HomeTool.SENSORS, Icons.Rounded.Explore, "Sensors", "Sensors", ToolTint.SKY, "Everyday", "Compass, level, light & sound meters…",
            "compass level flashlight torch magnifier speed network meter light sound",
            running = com.agani.syncup.sensors.Torch.on, status = if (com.agani.syncup.sensors.Torch.on) "Flashlight on" else null),
        ToolDef(HomeTool.DECIDE, com.agani.syncup.decide.DecideIcons.Dice, "Decide", "Decide", ToolTint.AMBER, "Everyday", "Coin, wheel, number, yes / no", "coin toss flip wheel spin random number dice yes no"),
        ToolDef(HomeTool.SHOPPING, Icons.Rounded.ShoppingCart, "Shopping", "Shopping", ToolTint.GREEN, "Everyday", "Lists & split a bill", "list groceries split bill",
            status = com.agani.syncup.shopping.shoppingStatus()),
        ToolDef(HomeTool.FOREST, Icons.Rounded.Eco, "Forest", "Forest", ToolTint.LIME, "Wellbeing", "A new forest every month", "trees focus calm"),
        ToolDef(HomeTool.WATER, Icons.Rounded.WaterDrop, "Water", "Water", ToolTint.CYAN, "Wellbeing", "Drink & track your water", "drink hydrate",
            status = if (ws.setUp) "${Water.fmt(Water.today(), ws.oz)} today" else null),
        ToolDef(HomeTool.STEPS, Icons.AutoMirrored.Rounded.DirectionsWalk, "Steps", "Steps", ToolTint.AMBER, "Wellbeing",
            if (stepsHere) "Your steps & daily goal" else "No step sensor on this phone", "walk walking pedometer",
            status = if (stepsHere && Steps.counting(ctx)) "${"%,d".format(Steps.today())} steps today" else null, greyed = !stepsHere),
        ToolDef(HomeTool.HABITS, Icons.Rounded.TaskAlt, "Habits", "Habits", ToolTint.PINK, "Wellbeing", "Tick off daily habits", "routine daily",
            status = if (hDue > 0) "$hDone of $hDue today" else null),
        ToolDef(HomeTool.SLEEP, Icons.Rounded.Bedtime, "Sleep", "Sleep", ToolTint.INDIGO, "Wellbeing", "Bedtime & sleep sounds", "bedtime night sounds rain",
            running = Sleep.soundsOn || night != null,
            status = when {
                night != null -> "Sleeping since ${clockText(night.start)}"
                Sleep.soundsOn -> "Sounds playing"
                else -> null
            }),
        ToolDef(HomeTool.BREAKS, Icons.Rounded.Visibility, "Breaks", "Breaks", ToolTint.GREEN, "Wellbeing", "Eye & stretch breaks", "eyes stretch rest"),
        ToolDef(HomeTool.MOOD, Icons.Rounded.SentimentSatisfied, "Mood", "Mood", ToolTint.LIME, "Wellbeing", "How are you today?", "feeling check in",
            status = Mood.lastToday()?.let { "Today: ${listOf("Bad", "Low", "Okay", "Good", "Great")[it - 1]}" }),
    )
}

/** How many tools show before "Show all tools": two full rows. */
private const val TOOLS_FIRST = 8

/**
 * The tools, four to a row: the first two rows, or ([open]) every tool under small group headings. A green
 * dot: that tool is running.
 */
@Composable
private fun ToolGrid(tools: List<ToolDef>, onTool: (HomeTool) -> Unit, open: Boolean) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val sections = if (open) GROUPS.map { g -> g to tools.filter { it.group == g } } else listOf(null to tools.take(TOOLS_FIRST))
    Column(Modifier.animateContentSize(SMOOTH)) {
        sections.forEachIndexed { i, (group, items) ->
            if (group != null) {
                Text(
                    group, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = if (i == 0) 0.dp else 14.dp, bottom = 8.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { t ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = t.title) {
                                    if (t.greyed) Toast.makeText(ctx, "This phone has no step sensor, so Steps can't count here.", Toast.LENGTH_SHORT).show()
                                    else onTool(t.tool)
                                }.padding(vertical = 4.dp).alpha(if (t.greyed) .45f else 1f),
                            ) {
                                Box {
                                    Tile(t.icon, t.tint, size = 62.dp, iconSize = 30.dp)
                                    if (t.running) {
                                        Box(
                                            Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(15.dp).clip(CircleShape)
                                                .background(cs.surface).padding(2.5.dp).clip(CircleShape).background(ToolTint.GREEN.icon),
                                        )
                                    }
                                }
                                Text(
                                    t.short, fontSize = 13.5.sp, lineHeight = 17.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
}

// ---------------------------------------------------------------------------- websites
private class Site(val title: String, val url: String, val used: UsedSite? = null)

/**
 * Websites: the sites this person uses most (worked out on the phone from History), then the admin's
 * catalogue — all from the backend, nothing built in. The first eight; "Show more websites" opens the rest
 * here, under their categories. Long-press a most-used site to take it off.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Websites(state: MainPageState, onOpen: (String) -> Unit, onOpenInNewTab: (String) -> Unit, onRecommended: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cats = ShortcutCatalog.categories
    val showRecommended = BrowserSettings.recommendedSites
    LaunchedEffect(Unit) { ShortcutCatalog.refresh() }
    LaunchedEffect(showRecommended, cats, com.agani.syncup.sync.BrowserSync.dataVersion) {
        if (showRecommended) RecommendedSites.refresh(context)
    }
    // A first start offline has no websites yet: keep trying while the page shows.
    LaunchedEffect(ShortcutCatalog.unreachable) {
        while (ShortcutCatalog.unreachable) {
            delay(15_000)
            ShortcutCatalog.refresh()
        }
    }
    val used = if (showRecommended) RecommendedSites.used.filterNot { it.key in BrowserSettings.removedSites } else emptyList()
    val recommended = used.take(MostUsed.PLACES)
    val rest = MostUsed.withoutRecommended(cats, recommended)
    val groups = buildList {
        if (recommended.isNotEmpty()) add("Recommended" to recommended.map { Site(it.title, it.url, it) })
        rest.forEach { c -> if (c.items.isNotEmpty()) add(c.name to c.items.map { Site(it.title, it.url) }) }
    }
    val all = groups.flatMap { it.second }
    var pressed by remember { mutableStateOf<UsedSite?>(null) }
    if (all.isEmpty()) {
        if (ShortcutCatalog.unreachable) {
            Header("Websites")
            Text("Websites will appear here once you're online.", fontSize = SUB_SIZE, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
        return
    }
    @Composable
    fun grid(sites: List<Site>) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            sites.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { s ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .combinedClickable(onClick = { onOpen(s.url) }, onLongClick = s.used?.let { u -> { pressed = u } })
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                        ) {
                            Box(Modifier.size(54.dp).clip(CircleShape).background(cs.surfaceContainer), contentAlignment = Alignment.Center) {
                                com.agani.syncup.browser.SiteMark(s.url, s.title, circle = 54.dp, iconSize = 30.dp)
                            }
                            Text(
                                s.title, fontSize = 13.sp, lineHeight = 17.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Column {
        Header("Websites")
        Card {
            // Recommended: the sites used most, with how many times over 7 days, 30 days or all time (user, 16 Nov 2026).
            if (used.isNotEmpty()) {
                CardRow("Recommended", "Your most used sites · how often, last 7 or 30 days", onClick = onRecommended) {
                    Tile(Icons.Rounded.Insights, ToolTint.BLUE)
                }
                Divider()
            }
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = if (all.size > SITES_FIRST) 4.dp else 18.dp)) {
                if (state.sitesOpen && all.size > SITES_FIRST) {
                    groups.forEachIndexed { i, (name, sites) ->
                        Text(
                            name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = if (i == 0) 0.dp else 18.dp, bottom = 10.dp),
                        )
                        grid(sites)
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    grid(all.take(SITES_FIRST))
                    if (all.size > SITES_FIRST) Spacer(Modifier.height(10.dp))
                }
            }
            if (all.size > SITES_FIRST) {
                Divider()
                MoreLine(if (state.sitesOpen) "Show less" else "Show more websites", state.sitesOpen) { state.sitesOpen = !state.sitesOpen }
            }
        }
    }
    pressed?.let { site ->
        com.agani.syncup.browser.RecommendedSheet(
            site, onDismiss = { pressed = null }, onOpen = onOpen, onOpenInNewTab = onOpenInNewTab,
            onRemove = {
                BrowserSettings.removeFromRecommended(it.key)
                Toast.makeText(context, "${it.title} removed from your most used", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

// ============================================================================ search
/** One setting the search can find: a switch that works right in the results, or the page it's on. */
private class SettingHit(
    val title: String,
    val where: String,
    val icon: ImageVector,
    val words: String,
    val page: String,
    val checked: Boolean? = null,
    val onToggle: ((Boolean) -> Unit)? = null,
    val value: String? = null,
) {
    fun matches(q: String): Boolean {
        val s = q.lowercase()
        return title.lowercase().contains(s) || words.split(' ').any { it.startsWith(s) } ||
            title.lowercase().split(' ', '-', '&').any { it.startsWith(s) }
    }
}

@Composable
private fun settingsIndex(signedIn: Boolean): List<SettingHit> {
    val context = LocalContext.current
    var updatesOn by remember { mutableStateOf(com.agani.syncup.push.DeviceRegistrar.updatesEnabled(context)) }
    var shareUsage by remember { mutableStateOf(com.agani.syncup.usage.UsageTime.enabled(context)) }
    val sync = com.agani.syncup.sync.BrowserSync
    val dark = !MaterialTheme.colorScheme.isLight
    val b = "Setting · Browser"
    return buildList {
        if (signedIn) {
            add(SettingHit("Email", "Setting · Account", Icons.Rounded.Person, "mail address sign in", SettingsPage.ACCOUNT))
            add(SettingHit("Mobile number", "Setting · Account", Icons.Rounded.Person, "phone sign in", SettingsPage.ACCOUNT))
            add(SettingHit("Username", "Setting · Account", Icons.Rounded.Person, "name sign in", SettingsPage.ACCOUNT))
            add(SettingHit("Change password", "Setting · Account", Icons.Rounded.Person, "password", SettingsPage.ACCOUNT))
            add(SettingHit("Delete account", "Setting · Account", Icons.Rounded.Person, "remove erase", SettingsPage.ACCOUNT))
            add(SettingHit("Sync on this phone", "Setting · Sync", Icons.Rounded.History, "sync backup devices", SettingsPage.SYNC,
                checked = sync.enabled, onToggle = { sync.setSyncEnabled(it) }))
            add(SettingHit("Partners", "Your partners", Icons.Rounded.Groups, "partner school password", SettingsPage.PARTNERS))
            add(SettingHit("Chat with admin", "Help from the SyncUp admin", Icons.Rounded.Groups, "chat support help message admin feedback", SettingsPage.CHAT))
        }
        add(SettingHit("Default browser", b, Icons.Rounded.Language, "links open default", SettingsPage.BROWSER))
        add(SettingHit("Search engine", b, Icons.Rounded.Search, "google bing duckduckgo", SettingsPage.BROWSER, value = BrowserSettings.searchEngine.label))
        add(SettingHit("DNS", b, Icons.Rounded.Language, "private secure family block", SettingsPage.DNS, value = com.agani.syncup.dns.DnsServices.summary))
        add(SettingHit("DNS in Incognito tabs", "Setting · Browser · DNS", Icons.Rounded.VisibilityOff, "dns incognito private", SettingsPage.DNS,
            checked = com.agani.syncup.dns.DnsServices.incognito, onToggle = { com.agani.syncup.dns.DnsServices.changeIncognito(it) }))
        add(SettingHit("Block pop-ups", b, Icons.Rounded.Language, "popups popup windows", SettingsPage.BROWSER,
            checked = BrowserSettings.blockPopups, onToggle = { BrowserSettings.updateBlockPopups(it) }))
        add(SettingHit("Block ads", b, Icons.Rounded.Language, "adblock ad blocker trackers advert", SettingsPage.BROWSER,
            checked = BrowserSettings.blockAds, onToggle = { BrowserSettings.updateBlockAds(it) }))
        add(SettingHit("Media & file detector", b, Icons.Rounded.Download, "download videos music files", SettingsPage.BROWSER,
            checked = BrowserSettings.mediaDetector, onToggle = { BrowserSettings.updateMediaDetector(it) }))
        add(SettingHit("Recommended sites", b, Icons.Rounded.Language, "most used websites shortcuts", SettingsPage.BROWSER,
            checked = BrowserSettings.recommendedSites, onToggle = { BrowserSettings.updateRecommendedSites(it) }))
        add(SettingHit("Text size", b, Icons.Rounded.Language, "font zoom bigger larger", SettingsPage.BROWSER, value = "${BrowserSettings.textZoom}%"))
        add(SettingHit("Dark mode for websites", b, Icons.Rounded.Bedtime, "dark night black", SettingsPage.BROWSER,
            checked = BrowserSettings.darkenWebsites, onToggle = { BrowserSettings.updateDarkenWebsites(it) }))
        add(SettingHit("Address bar at bottom", b, Icons.Rounded.Language, "address bar bottom top", SettingsPage.BROWSER,
            checked = BrowserSettings.addressBarBottom, onToggle = { BrowserSettings.updateAddressBarBottom(it) }))
        add(SettingHit("Downloads", b, Icons.Rounded.Download, "parts wifi ask download at once", SettingsPage.BROWSER))
        add(SettingHit("App lock (PIN)", "Setting · Privacy & security", Icons.Rounded.VisibilityOff, "pin lock security fingerprint biometric", SettingsPage.PRIVACY))
        add(SettingHit("Share usage time", "Setting · Privacy & security", Icons.Rounded.History, "usage time analytics tracking privacy", SettingsPage.PRIVACY,
            checked = shareUsage, onToggle = { on -> shareUsage = on; com.agani.syncup.usage.UsageTime.setEnabled(context, on) }))
        add(SettingHit("Clear browsing data", "Setting · Privacy & security", Icons.Rounded.History, "history cookies cache delete", SettingsPage.PRIVACY))
        add(SettingHit("Site settings", "Setting · Privacy & security", Icons.Rounded.Language, "camera mic location notifications permissions", SettingsPage.SITE_SETTINGS))
        add(SettingHit("Theme", "Setting · Appearance", Icons.Rounded.Bedtime, "dark light black appearance colour color", SettingsPage.APPEARANCE,
            value = if (dark) "Dark" else "Light"))
        add(SettingHit("SyncUp updates", "Setting · Notifications", Icons.Outlined.Notifications, "news announcements notifications", SettingsPage.NOTIFICATIONS,
            checked = updatesOn, onToggle = {
                updatesOn = it
                com.agani.syncup.push.DeviceRegistrar.setUpdatesEnabled(context, it)
            }))
        add(SettingHit("Notification settings", "Setting · Notifications", Icons.Outlined.Notifications, "allow sounds alerts", SettingsPage.NOTIFICATIONS))
        add(SettingHit("Notifications", "Messages for you", Icons.Outlined.Notifications, "inbox bell messages", SettingsPage.INBOX))
        add(SettingHit("Help & support", "Setting · About", Icons.Rounded.Person, "contact help forgot", SettingsPage.ABOUT))
        add(SettingHit("Privacy policy", "Setting · About", Icons.Rounded.Person, "privacy data", SettingsPage.ABOUT))
    }
}

/** The main page's search: tools, settings (switches work right here), history, and the web — together. */
@Composable
private fun SearchScreen(
    state: MainPageState,
    signedIn: Boolean,
    tools: List<ToolDef>,
    db: BrowserDb,
    onClose: () -> Unit,
    onTool: (HomeTool) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSetting: (String) -> Unit,
    onVoice: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val q = state.query.trim()
    val settings = settingsIndex(signedIn)
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    // New words, new results: always from the top (a list keeps its place on the first row it showed,
    // which would hide matching tools added above it).
    val results = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(q) {
        results.scrollToItem(0)
        delay(120)
        history = withContext(Dispatchers.IO) { runCatching { db.history(q, if (q.isEmpty()) 6 else 12) }.getOrDefault(emptyList()) }
            .distinctBy { it.url }.take(if (q.isEmpty()) 5 else 4)
    }
    // Names that start with what's typed come first ("wa": Walkie Talkie and Water before Video player).
    fun rank(name: String): Int = when {
        name.lowercase().startsWith(q.lowercase()) -> 0
        name.lowercase().split(' ').any { it.startsWith(q.lowercase()) } -> 1
        else -> 2
    }
    val toolHits = if (q.isEmpty()) emptyList() else tools.filter { it.matches(q) }.sortedBy { minOf(rank(it.short), rank(it.title)) }.take(6)
    val settingHits = if (q.isEmpty()) emptyList() else settings.filter { it.matches(q) }.sortedBy { rank(it.title) }.take(6)
    val siteHits = if (q.isEmpty()) emptyList() else ShortcutCatalog.search(q, 3).filter { s -> history.none { it.url == s.url } }
    val webUrl = UrlInput.toUrl(q)
    val isSearch = webUrl.startsWith(BrowserSettings.searchEngine.searchUrl)

    Column(
        Modifier.fillMaxSize().background(cs.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 6.dp).fillMaxWidth().height(56.dp)
                .appCard(RoundedCornerShape(50)).padding(start = 4.dp, end = 4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(46.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onClose),
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = cs.onSurface) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) Text("Search anything", fontSize = 16.sp, color = cs.onSurfaceVariant, maxLines = 1)
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (webUrl.isNotBlank()) onOpenUrl(webUrl) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(46.dp).clip(CircleShape).clickable(onClickLabel = if (state.query.isEmpty()) "Voice search" else "Clear") {
                    if (state.query.isEmpty()) onVoice() else state.query = ""
                },
            ) { Icon(if (state.query.isEmpty()) Icons.Rounded.Mic else Icons.Rounded.Close, null, tint = cs.onSurfaceVariant) }
        }
        LazyColumn(state = results, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), modifier = Modifier.weight(1f)) {
            if (toolHits.isNotEmpty()) {
                item(key = "tools") {
                    Label("Tools")
                    Card {
                        toolHits.forEachIndexed { i, t ->
                            if (i > 0) Divider()
                            CardRow(t.title, "Tool · ${t.status ?: t.about}", onClick = { onTool(t.tool) }) { Tile(t.icon, t.tint) }
                        }
                    }
                }
            }
            if (settingHits.isNotEmpty()) {
                item(key = "settings") {
                    Label("Settings")
                    Card {
                        settingHits.forEachIndexed { i, s ->
                            if (i > 0) Divider()
                            val toggle = s.onToggle
                            if (s.checked != null && toggle != null) {
                                CardRow(s.title, s.where, onClick = { toggle(!s.checked) },
                                    end = { Switch(checked = s.checked, onCheckedChange = toggle) }) { SettingIcon(s.icon) }
                            } else {
                                CardRow(s.title, s.where, onClick = { onSetting(s.page) },
                                    end = s.value?.let { v ->
                                        {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(v, fontSize = 14.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                            }
                                        }
                                    }) { SettingIcon(s.icon) }
                            }
                        }
                    }
                }
            }
            if (history.isNotEmpty() || siteHits.isNotEmpty()) {
                item(key = "history") {
                    Label(if (q.isEmpty()) "Recent" else "History")
                    Card {
                        history.forEachIndexed { i, h ->
                            if (i > 0) Divider()
                            CardRow(h.title.ifBlank { UrlInput.display(h.url) }, UrlInput.display(h.url), onClick = { onOpenUrl(h.url) }, chevron = false) {
                                Tile(Icons.Rounded.History, ToolTint.SKY)
                            }
                        }
                        siteHits.forEachIndexed { i, s ->
                            if (i > 0 || history.isNotEmpty()) Divider()
                            CardRow(s.title, UrlInput.display(s.url), onClick = { onOpenUrl(s.url) }, chevron = false) {
                                com.agani.syncup.browser.SiteMark(s.url, s.title, circle = 40.dp, iconSize = 22.dp)
                            }
                        }
                    }
                }
            }
            if (q.isNotEmpty()) {
                item(key = "web") {
                    Label("The web")
                    Card {
                        CardRow(
                            if (isSearch) "Search ${BrowserSettings.searchEngine.label} for “$q”" else "Go to $q",
                            null, onClick = { onOpenUrl(webUrl) }, chevron = false,
                        ) { Tile(if (isSearch) Icons.Rounded.Search else Icons.Rounded.Language, ToolTint.BLUE) }
                    }
                }
                if (toolHits.isEmpty() && settingHits.isEmpty()) {
                    item(key = "hint") {
                        Text(
                            "No tools or settings match “$q”.", fontSize = SUB_SIZE, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, top = 14.dp),
                        )
                    }
                }
            } else {
                item(key = "empty-hint") {
                    Text(
                        "Type a website, a tool like “water”, or a setting like “dark”.", fontSize = SUB_SIZE, lineHeight = 19.sp,
                        color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingIcon(icon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(cs.surfaceContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}
