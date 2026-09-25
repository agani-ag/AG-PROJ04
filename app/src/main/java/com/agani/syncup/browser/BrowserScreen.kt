package com.agani.syncup.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.agani.syncup.R
import com.agani.syncup.data.AnnouncementDto
import com.agani.syncup.data.UrlItem
import com.agani.syncup.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/** SyncUp account data the browser shows (all empty/null when nobody is logged in). */
data class BrowserAccount(
    val user: User? = null,
    val links: List<UrlItem> = emptyList(),
    val chatEnabled: Boolean = false,
    val chatUnread: Int = 0,
    val radioEnabled: Boolean = false,
    val announcement: AnnouncementDto? = null,
    val refreshing: Boolean = false,
    val loginLoading: Boolean = false,
    val loginError: String? = null,
    val supportEmail: String = "",
    val supportPhone: String = "",
)

enum class LibraryPage { HISTORY, BOOKMARKS, DOWNLOADS }

/** What the browser asks the rest of the app to do. */
class BrowserActions(
    val onSignIn: () -> Unit,
    val onDismissSignIn: () -> Unit,
    val onLogin: (email: String, password: String) -> Unit,
    val onSignOut: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenChat: () -> Unit,
    val onOpenRadio: () -> Unit,
    val onOpenLibrary: (LibraryPage) -> Unit,
    val onRefreshLinks: () -> Unit,
    val onAddLink: suspend (title: String, url: String, description: String) -> Result<Unit>,
    val onRemoveLink: suspend (id: String) -> Result<Unit>,
)

/** A round new-tab tile: a letter or an icon in the brand colour. */
private class Tile(
    val name: String,
    val letter: String?,
    val icon: ImageVector?,
    val color: Color,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

private data class Shortcut(val name: String, val url: String, val letter: String?, val icon: ImageVector?, val color: Color)

private val SHORTCUTS = listOf(
    Shortcut("Google", "https://www.google.com", "G", null, Color(0xFF4285F4)),
    Shortcut("YouTube", "https://m.youtube.com", null, Icons.Rounded.PlayArrow, Color(0xFFFF0000)),
    Shortcut("WhatsApp", "https://web.whatsapp.com", null, Icons.AutoMirrored.Rounded.Chat, Color(0xFF25D366)),
    Shortcut("LinkedIn", "https://www.linkedin.com", "in", null, Color(0xFF0A66C2)),
    Shortcut("X", "https://x.com", "X", null, Color(0xFF1D9BF0)),
    Shortcut("Instagram", "https://www.instagram.com", null, Icons.Rounded.PhotoCamera, Color(0xFFE4405F)),
    Shortcut("Wikipedia", "https://en.m.wikipedia.org", "W", null, Color(0xFFF59E0B)),
)

// ============================================================================ screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    tabs: TabManager,
    web: WebPlatform,
    db: BrowserDb,
    account: BrowserAccount,
    actions: BrowserActions,
    signInVisible: Boolean,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val section = tabs.section
    val tab = tabs.activeTab(section)
    LaunchedEffect(section, tab == null) { if (tab == null) tabs.ensureTab(section) }

    var editing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(TextFieldValue("")) }
    var showTabs by remember { mutableStateOf(false) }
    var showSections by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showWorkInfo by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }

    // A work section is only for logged-in users; signing out wipes it (MainActivity) — be safe here too.
    LaunchedEffect(account.user == null, section) {
        if (account.user == null && section == Section.WORK) tabs.switchTo(Section.NORMAL)
    }

    // Incognito: block screenshots/recents previews while it's on screen.
    DisposableEffect(section == Section.INCOGNITO) {
        val secure = section == Section.INCOGNITO
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun startEditing(prefill: String) {
        editValue = TextFieldValue(prefill, TextRange(0, prefill.length))
        editing = true
    }

    fun openInCurrent(url: String) {
        val t = tabs.activeTab() ?: tabs.ensureTab(tabs.section)
        if (t.isWork) tabs.newTab(Section.NORMAL, url) else tabs.load(t, url)
    }

    fun openWorkLink(item: UrlItem) {
        val home = tabs.activeTab(Section.WORK)?.takeIf { it.isHome }
        tabs.openWorkLink(item.title, item.url, item.id, item.notifyToken)
        home?.let { tabs.close(it) } // replace the empty Work home tab with the link's tab
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) openInCurrent(UrlInput.toUrl(spoken))
    }
    fun voiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or say a web address")
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Voice search isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = web.fullscreen || editing || finding || showTabs || (tab != null && (tab.canGoBack || !tab.isHome))) {
        when {
            web.fullscreen -> web.exitFullscreen()
            editing -> editing = false
            finding -> {
                finding = false
                tab?.let { tabs.webView(it).clearMatches() }
            }
            showTabs -> showTabs = false
            else -> tabs.back()
        }
    }

    // The app theme's own bar style, restored when the browser leaves the screen.
    val appLightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
    DisposableEffect(Unit) {
        onDispose { setBarIcons(activity, appLightBars) }
    }

    SectionTheme(section) {
        val cs = MaterialTheme.colorScheme
        val onPage = tab != null && !tab.isHome
        // Work pages have no address bar: the page runs up to the status bar (tap the menu for link info).
        val showPageBar = onPage && tab?.isWork == false
        // Status + navigation bars take the page's theme-color (like the old WebView screen);
        // otherwise the address bar's surface on pages, and the plain background on home pages.
        val pageColor = tab?.themeColor?.takeIf { onPage && !editing && !finding }?.let { Color(it) }
        val topColor = pageColor ?: if (onPage || editing || finding) cs.surface else cs.background
        val lightTop = topColor.luminance() > 0.5f
        DisposableEffect(lightTop) {
            setBarIcons(activity, lightTop)
            onDispose { }
        }
        val barBottom = BrowserSettings.addressBarBottom
        val openPageBar = { t: BrowserTab -> if (t.isWork) showWorkInfo = true else startEditing(t.url) }

        Box(
            Modifier
                .fillMaxSize()
                .background(topColor)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(Modifier.fillMaxSize()) {
                when {
                    tab == null -> Unit
                    editing -> EditBar(
                        value = editValue,
                        incognito = section == Section.INCOGNITO,
                        onValueChange = { editValue = it },
                        onSubmit = {
                            val url = UrlInput.toUrl(editValue.text)
                            editing = false
                            if (url.isNotBlank()) openInCurrent(url)
                        },
                        onCancel = { editing = false },
                    )
                    finding -> FindBar(
                        webViewProvider = { tabs.webView(tab) },
                        onClose = {
                            finding = false
                            tabs.webView(tab).clearMatches()
                        },
                    )
                    showPageBar && !barBottom -> {
                        PageBar(tab, db, onTap = { openPageBar(tab) }, onReload = { tabs.reload(tab) }, onStop = { tabs.stop(tab) })
                        ProgressLine(tab)
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().background(cs.background)) {
                    if (tab != null) {
                        if (tab.isHome) {
                            when (section) {
                                Section.NORMAL -> NormalHome(
                                    account = account,
                                    workOpenTabs = tabs.tabsIn(Section.WORK).count { !it.isHome },
                                    onSearch = { startEditing("") },
                                    onVoice = { voiceSearch() },
                                    onOpenUrl = { openInCurrent(it) },
                                    onAvatar = { if (account.user == null) actions.onSignIn() else showAccount = true },
                                    onOpenWork = { tabs.switchTo(Section.WORK) },
                                    onOpenRadio = actions.onOpenRadio,
                                )
                                Section.INCOGNITO -> IncognitoHome(onSearch = { startEditing("") })
                                Section.WORK -> WorkHome(
                                    account = account,
                                    onOpen = { openWorkLink(it) },
                                    onAvatar = { showAccount = true },
                                    onSignIn = actions.onSignIn,
                                    onRefresh = actions.onRefreshLinks,
                                    onAddLink = actions.onAddLink,
                                    onRemoveLink = actions.onRemoveLink,
                                )
                            }
                        } else {
                            key(tab.id) {
                                AndroidView(factory = { tabs.attachable(tab) }, modifier = Modifier.fillMaxSize())
                            }
                            tab.error?.let { msg -> ErrorCard(msg) { tabs.reload(tab) } }
                            if (!showPageBar) {
                                Box(Modifier.align(Alignment.TopStart)) { ProgressLine(tab, track = Color.Transparent) }
                            }
                        }
                    }
                    if (editing && tab != null) {
                        Suggestions(
                            query = editValue.text,
                            section = section,
                            db = db,
                            onPick = { url ->
                                editing = false
                                openInCurrent(url)
                            },
                            onFill = { text -> editValue = TextFieldValue(text, TextRange(text.length)) },
                        )
                    }
                }

                if (tab != null && showPageBar && barBottom && !editing && !finding) {
                    ProgressLine(tab)
                    PageBar(tab, db, onTap = { openPageBar(tab) }, onReload = { tabs.reload(tab) }, onStop = { tabs.stop(tab) })
                }
                if (tab != null && !editing) {
                    BottomBar(
                        tab = tab,
                        section = section,
                        tabCount = tabs.tabsIn(section).size,
                        onBack = { tabs.back() },
                        onForward = { tabs.forward() },
                        onSection = { showSections = true },
                        onTabs = { showTabs = true },
                        onMenu = { showMenu = true },
                    )
                }
            }

            if (showTabs) {
                TabSwitcher(tabs = tabs, signedIn = account.user != null, onSignIn = actions.onSignIn, onClose = { showTabs = false })
            }
        }

        if (showSections) {
            ModalBottomSheet(onDismissRequest = { showSections = false }, containerColor = cs.surface) {
                SectionSheet(
                    current = section,
                    tabs = tabs,
                    signedIn = account.user != null,
                    workCount = account.links.size,
                    onPick = { s ->
                        showSections = false
                        if (s == Section.WORK && account.user == null) actions.onSignIn() else tabs.switchTo(s)
                    },
                    onSignIn = {
                        showSections = false
                        actions.onSignIn()
                    },
                )
            }
        }

        if (showMenu && tab != null) {
            ModalBottomSheet(onDismissRequest = { showMenu = false }, containerColor = cs.surface) {
                MenuSheet(
                    tab = tab,
                    db = db,
                    account = account,
                    onAction = { action ->
                        showMenu = false
                        when (action) {
                            MenuAction.NEW_TAB -> tabs.newTab(if (section == Section.INCOGNITO) Section.INCOGNITO else Section.NORMAL)
                            MenuAction.NEW_INCOGNITO -> tabs.newTab(Section.INCOGNITO)
                            MenuAction.FORWARD -> tabs.forward()
                            MenuAction.RELOAD -> tabs.reload(tab)
                            MenuAction.BOOKMARK -> scope.launch {
                                val added = withContext(Dispatchers.IO) { db.toggleBookmark(tab.url, tab.title) }
                                Toast.makeText(context, if (added) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
                            }
                            MenuAction.SHARE -> runCatching {
                                context.startActivity(
                                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), "Share"),
                                )
                            }
                            MenuAction.FIND -> finding = true
                            MenuAction.CHAT -> actions.onOpenChat()
                            MenuAction.RADIO -> actions.onOpenRadio()
                            MenuAction.WORK -> tabs.switchTo(Section.WORK)
                            MenuAction.WORK_INFO -> showWorkInfo = true
                            MenuAction.BOOKMARKS -> actions.onOpenLibrary(LibraryPage.BOOKMARKS)
                            MenuAction.HISTORY -> actions.onOpenLibrary(LibraryPage.HISTORY)
                            MenuAction.DOWNLOADS -> actions.onOpenLibrary(LibraryPage.DOWNLOADS)
                            MenuAction.SETTINGS -> actions.onOpenSettings()
                            MenuAction.SIGN_IN -> actions.onSignIn()
                        }
                    },
                )
            }
        }

        if (showWorkInfo && tab != null) {
            ModalBottomSheet(onDismissRequest = { showWorkInfo = false }, containerColor = cs.surface) {
                WorkInfoSheet(
                    tab = tab,
                    onReload = {
                        showWorkInfo = false
                        tabs.reload(tab)
                    },
                    onSearchNormal = {
                        showWorkInfo = false
                        tabs.switchTo(Section.NORMAL)
                        startEditing("")
                    },
                )
            }
        }
    }

    // Account + sign-in sheets use the app theme, not the section tint.
    if (showAccount && account.user != null) {
        ModalBottomSheet(onDismissRequest = { showAccount = false }, containerColor = MaterialTheme.colorScheme.surface) {
            AccountSheet(
                account = account,
                onWork = {
                    showAccount = false
                    tabs.switchTo(Section.WORK)
                },
                onChat = {
                    showAccount = false
                    actions.onOpenChat()
                },
                onRadio = {
                    showAccount = false
                    actions.onOpenRadio()
                },
                onSettings = {
                    showAccount = false
                    actions.onOpenSettings()
                },
                onSignOut = {
                    showAccount = false
                    actions.onSignOut()
                },
            )
        }
    }
    if (signInVisible && account.user == null) {
        ModalBottomSheet(onDismissRequest = actions.onDismissSignIn, containerColor = MaterialTheme.colorScheme.surface) {
            SignInSheet(
                loading = account.loginLoading,
                error = account.loginError,
                supportEmail = account.supportEmail,
                supportPhone = account.supportPhone,
                onLogin = actions.onLogin,
            )
        }
    }
}

private fun setBarIcons(activity: Activity?, light: Boolean) {
    activity?.window?.let { w ->
        WindowCompat.getInsetsController(w, w.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

// ============================================================================ theming per section
private val IncognitoColors = darkColorScheme(
    primary = Color(0xFFC4B5FD), onPrimary = Color(0xFF2E1065),
    primaryContainer = Color(0xFF3B2A63), onPrimaryContainer = Color(0xFFEDE9FE),
    secondaryContainer = Color(0xFF3C4043), onSecondaryContainer = Color(0xFFE8EAED),
    background = Color(0xFF202124), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF2A2B2F), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF303134), onSurfaceVariant = Color(0xFF9AA0A6),
    outline = Color(0xFF5F6368), outlineVariant = Color(0xFF3C4043),
)

/** Work = teal accent, Incognito = fixed dark violet, Normal = the app theme. */
@Composable
private fun SectionTheme(section: Section, content: @Composable () -> Unit) {
    val base: ColorScheme = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = when (section) {
        Section.NORMAL -> base
        Section.INCOGNITO -> IncognitoColors
        Section.WORK -> if (dark) {
            base.copy(
                primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2E),
                primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFCCFBF1),
                secondaryContainer = Color(0xFF1E3F3B), onSecondaryContainer = Color(0xFFCCFBF1),
            )
        } else {
            base.copy(
                primary = Color(0xFF0F766E), onPrimary = Color.White,
                primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF134E4A),
                secondaryContainer = Color(0xFFD5F5EE), onSecondaryContainer = Color(0xFF134E4A),
            )
        }
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}

// ============================================================================ address bar (web pages)
/** 60dp strip with a 44dp pill: lock · host/path · reload (inside); bookmark outside (Normal only). */
@Composable
private fun PageBar(tab: BrowserTab, db: BrowserDb, onTap: () -> Unit, onReload: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val normal = tab.section == Section.NORMAL
    val work = tab.isWork
    var bookmarked by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url) { bookmarked = normal && withContext(Dispatchers.IO) { db.isBookmarked(tab.url) } }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().height(60.dp).background(cs.surface).padding(start = 10.dp, end = if (normal) 4.dp else 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (work) cs.primaryContainer else cs.surfaceVariant)
                .clickable(onClick = onTap)
                .padding(start = 14.dp, end = 5.dp),
        ) {
            val icon = when {
                work -> Icons.Rounded.Work
                tab.section == Section.INCOGNITO -> Icons.Rounded.VisibilityOff
                UrlInput.isSecure(tab.url) -> Icons.Rounded.Lock
                else -> Icons.Rounded.Info
            }
            Icon(icon, null, tint = if (work) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (work && tab.isMasked) {
                    Text(
                        tab.workName ?: "Work link", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    WorkChip("Work link")
                } else {
                    val (host, path) = UrlInput.hostAndPath(tab.url)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = if (work) cs.onPrimaryContainer else cs.onSurface)) { append(host) }
                            withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(path) }
                        },
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (work) WorkChip("Work")
                }
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = if (tab.loading) onStop else onReload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                    if (tab.loading) "Stop" else "Reload",
                    tint = if (work) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
        }
        if (normal) {
            IconButton(
                onClick = {
                    scope.launch {
                        bookmarked = withContext(Dispatchers.IO) { db.toggleBookmark(tab.url, tab.title) }
                        Toast.makeText(context, if (bookmarked) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (bookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (bookmarked) "Remove bookmark" else "Add bookmark",
                    tint = if (bookmarked) cs.primary else cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** 3dp loading line under (or above, when the bar is at the bottom) the address bar. */
@Composable
private fun ProgressLine(tab: BrowserTab, track: Color = MaterialTheme.colorScheme.surface) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(3.dp).background(track)) {
        if (tab.loading && tab.progress in 1..99) {
            Box(
                Modifier
                    .fillMaxWidth(tab.progress / 100f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(cs.primary),
            )
        }
    }
}

@Composable
private fun WorkChip(text: String) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

// ============================================================================ address bar (typing)
@Composable
private fun EditBar(
    value: TextFieldValue,
    incognito: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(60.dp).background(cs.surface).padding(start = 4.dp, end = 10.dp),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel", tint = cs.onSurface)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(cs.surface)
                .border(2.dp, cs.primary, RoundedCornerShape(22.dp))
                .padding(start = 14.dp, end = 5.dp),
        ) {
            if (incognito) {
                Icon(Icons.Rounded.VisibilityOff, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) Text("Search or type web address", color = cs.onSurfaceVariant, fontSize = 15.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (value.text.isNotEmpty()) {
                Box(Modifier.size(34.dp).clip(CircleShape).clickable { onValueChange(TextFieldValue("")) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Clear", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ============================================================================ find in page
@Composable
private fun FindBar(webViewProvider: () -> android.webkit.WebView, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        webViewProvider().setFindListener { active, count, _ ->
            current = if (count == 0) 0 else active + 1
            total = count
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(60.dp).background(cs.surface).padding(start = 10.dp, end = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(cs.surface)
                .border(2.dp, cs.primary, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    webViewProvider().findAllAsync(it)
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { webViewProvider().findNext(true) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (query.isNotEmpty()) Text("$current/$total", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        IconButton(onClick = { webViewProvider().findNext(false) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { webViewProvider().findNext(true) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Next") }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close find") }
    }
}

// ============================================================================ bottom bar
@Composable
private fun BottomBar(
    tab: BrowserTab,
    section: Section,
    tabCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSection: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.background(cs.surface)) {
        HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
            modifier = Modifier.fillMaxWidth().height(57.dp),
        ) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = tab.canGoBack || !tab.isHome, onClick = onBack)
            BarIcon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", enabled = tab.canGoForward, onClick = onForward)
            Box(
                Modifier
                    .size(width = 46.dp, height = 34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(cs.primaryContainer)
                    .clickable(onClick = onSection),
                contentAlignment = Alignment.Center,
            ) {
                Icon(sectionIcon(section), "Switch section (now ${sectionName(section)})", tint = cs.primary, modifier = Modifier.size(22.dp))
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onTabs), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(22.dp).border(2.dp, cs.onSurface, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (tabCount > 99) "∞" else tabCount.toString(), fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                }
            }
            BarIcon(Icons.Rounded.MoreVert, "Menu", enabled = true, onClick = onMenu)
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = cs.onSurface.copy(alpha = if (enabled) 1f else .35f))
    }
}

private fun sectionIcon(s: Section): ImageVector = when (s) {
    Section.NORMAL -> Icons.Rounded.Public
    Section.WORK -> Icons.Rounded.Work
    Section.INCOGNITO -> Icons.Rounded.VisibilityOff
}

private fun sectionName(s: Section) = when (s) {
    Section.NORMAL -> "Normal"
    Section.WORK -> "Work"
    Section.INCOGNITO -> "Incognito"
}

// ============================================================================ shared home pieces
/** The 52dp search box of the home pages; tap to type, mic for voice. */
@Composable
private fun SearchBig(placeholder: String, onClick: () -> Unit, onVoice: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(1.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 6.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(placeholder, color = cs.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
        if (onVoice != null) {
            IconButton(onClick = onVoice) { Icon(Icons.Rounded.Mic, "Voice search", tint = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Avatar(user: User, unread: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text(user.name.take(1).uppercase(), color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(11.dp)
                    .border(2.dp, cs.background, CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(cs.error),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

// ============================================================================ Normal home (new-tab page)
@Composable
private fun NormalHome(
    account: BrowserAccount,
    workOpenTabs: Int,
    onSearch: () -> Unit,
    onVoice: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onAvatar: () -> Unit,
    onOpenWork: () -> Unit,
    onOpenRadio: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val user = account.user
    var showAddShortcut by remember { mutableStateOf(false) }
    var removeShortcut by remember { mutableStateOf<UserShortcut?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = if (user == null) 6.dp else 14.dp, bottom = if (user != null) 84.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (user == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onAvatar) {
                        Icon(Icons.Rounded.AccountCircle, "Sign in to SyncUp", tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(painterResource(R.drawable.ic_sync), null, colorFilter = ColorFilter.tint(cs.primary), modifier = Modifier.size(56.dp))
                    Text("SyncUp", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append("${greeting()}, ") }
                            withStyle(SpanStyle(color = cs.onBackground, fontWeight = FontWeight.Bold)) { append(user.name.substringBefore(' ')) }
                        },
                        fontSize = 15.sp, modifier = Modifier.weight(1f),
                    )
                    Avatar(user, account.chatUnread, onAvatar)
                }
            }

            SearchBig("Search or type web address", onClick = onSearch, onVoice = onVoice)

            val ann = account.announcement
            if (user != null && ann != null && ann.active && !ann.fullscreen && (ann.title.isNotBlank() || ann.message.isNotBlank())) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Icon(Icons.Rounded.Campaign, null, tint = cs.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        if (ann.title.isNotBlank()) Text(ann.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cs.onPrimaryContainer)
                        if (ann.message.isNotBlank()) Text(ann.message, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onPrimaryContainer)
                    }
                }
            }

            if (user != null) {
                SectionTheme(Section.WORK) {
                    val w = MaterialTheme.colorScheme
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(w.primaryContainer)
                            .clickable(onClick = onOpenWork)
                            .padding(16.dp),
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(w.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Work, null, tint = w.onPrimary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Work", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = w.onPrimaryContainer)
                            Text(
                                "${account.links.size} link${if (account.links.size == 1) "" else "s"} · " +
                                    "$workOpenTabs open tab${if (workOpenTabs == 1) "" else "s"}",
                                fontSize = 12.sp, color = w.onPrimaryContainer,
                            )
                        }
                        Button(onClick = onOpenWork, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(36.dp)) {
                            Text("Open", fontSize = 14.sp)
                        }
                    }
                }
            }

            SectionLabel("SHORTCUTS", Modifier.padding(top = 4.dp))
            val tiles = buildList {
                SHORTCUTS.forEach { s -> add(Tile(s.name, s.letter, s.icon, s.color, onClick = { onOpenUrl(s.url) })) }
                BrowserSettings.shortcuts.forEach { s ->
                    add(Tile(s.name, s.name.take(1).uppercase(), null, cs.primary, onClick = { onOpenUrl(s.url) }, onLongClick = { removeShortcut = s }))
                }
                add(Tile("Add", null, Icons.Rounded.Add, cs.onSurfaceVariant, onClick = { showAddShortcut = true }))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { ShortcutTile(it) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (user != null) {
            RadioMiniPlayer(onOpen = onOpenRadio, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }

    if (showAddShortcut) AddShortcutDialog(onDismiss = { showAddShortcut = false })
    removeShortcut?.let { s ->
        AlertDialog(
            onDismissRequest = { removeShortcut = null },
            title = { Text("Remove shortcut?", fontWeight = FontWeight.Bold) },
            text = { Text("Remove \"${s.name}\" from your shortcuts?") },
            confirmButton = {
                Button(onClick = {
                    BrowserSettings.removeShortcut(s)
                    removeShortcut = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeShortcut = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ShortcutTile(tile: Tile) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = tile.onClick, onLongClick = tile.onLongClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(cs.surface).border(1.dp, cs.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (tile.icon != null) {
                Icon(tile.icon, null, tint = tile.color, modifier = Modifier.size(24.dp))
            } else {
                Text(tile.letter.orEmpty(), color = tile.color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(tile.name, fontSize = 11.sp, lineHeight = 14.sp, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddShortcutDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add shortcut", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    url, { url = it }, label = { Text("Web address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val target = UrlInput.toUrl(url)
                if (name.isBlank() || target.isBlank() || UrlInput.isSearchPage(target)) {
                    error = "Enter a name and a web address (e.g. example.com)"
                } else {
                    BrowserSettings.addShortcut(name.trim(), target)
                    onDismiss()
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ============================================================================ Incognito home
@Composable
private fun IncognitoHome(onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Box(Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VisibilityOff, null, tint = cs.primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("You've gone incognito", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text(
                "Pages you view in incognito tabs won't stay in your history, cookies or site data after you close all incognito tabs. " +
                    "Downloads and bookmarks are kept. Screenshots are blocked.",
                fontSize = 14.sp, lineHeight = 22.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Work links, Chat and Radio are hidden here.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                .height(48.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceVariant)
                .clickable(onClick = onSearch).padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Search privately", fontSize = 15.sp, color = cs.onSurfaceVariant)
        }
    }
}

// ============================================================================ Work home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkHome(
    account: BrowserAccount,
    onOpen: (UrlItem) -> Unit,
    onAvatar: () -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onAddLink: suspend (String, String, String) -> Result<Unit>,
    onRemoveLink: suspend (String) -> Result<Unit>,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = account.user
    if (user == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Work, null, tint = cs.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Sign in to use your work links", fontSize = 16.sp, color = cs.onBackground)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSignIn) { Text("Sign in to SyncUp") }
        }
        return
    }
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<UrlItem?>(null) }
    val filtered = account.links.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
    val provided = filtered.filter { !it.canRemove }
    val mine = filtered.filter { it.canRemove }

    PullToRefreshBox(isRefreshing = account.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Work, null, tint = cs.onPrimary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Work", fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
                    Text(user.name, fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Avatar(user, account.chatUnread, onAvatar)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .shadow(1.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp))
                    .background(cs.surface)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(26.dp))
                    .padding(start = 18.dp, end = 6.dp),
            ) {
                Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search your work links", color = cs.onSurfaceVariant, fontSize = 15.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotEmpty()) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { query = "" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Clear", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (account.links.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Work, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(if (account.refreshing) "Loading your links…" else "No work links yet", color = cs.onSurfaceVariant)
                    Text("Pull down to refresh", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            } else if (filtered.isEmpty()) {
                Text("No links match \"$query\"", color = cs.onSurfaceVariant, fontSize = 14.sp)
            }
            if (provided.isNotEmpty()) {
                SectionLabel("YOUR LINKS")
                LinkGrid(provided, onOpen, onLongPress = null)
            }
            if (mine.isNotEmpty() || user.canManageLinks) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("ADDED BY ME", Modifier.weight(1f))
                    if (user.canManageLinks) {
                        Box(Modifier.size(32.dp).clip(CircleShape).clickable { showAdd = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, "Add link", tint = cs.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (mine.isNotEmpty()) LinkGrid(mine, onOpen, onLongPress = { toRemove = it })
            }
        }
    }

    if (showAdd) AddLinkDialog(onDismiss = { showAdd = false }, onSubmit = onAddLink)
    toRemove?.let { link ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove link?", fontWeight = FontWeight.Bold) },
            text = { Text("Remove \"${link.title}\" from your work links?") },
            confirmButton = {
                Button(
                    onClick = {
                        toRemove = null
                        scope.launch {
                            onRemoveLink(link.id).onFailure {
                                Toast.makeText(context, it.message ?: "Couldn't remove", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkGrid(items: List<UrlItem>, onOpen: (UrlItem) -> Unit, onLongPress: ((UrlItem) -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { onOpen(item) }, onLongClick = onLongPress?.let { { it(item) } })
                            .padding(vertical = 6.dp),
                    ) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(iconFor(item), null, tint = cs.primary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.title, fontSize = 11.sp, lineHeight = 14.sp, color = cs.onBackground, maxLines = 2,
                            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ============================================================================ suggestions
private class Suggestion(val icon: ImageVector, val title: String, val subtitle: String?, val url: String, val fill: String)

/** Live query suggestions from Google's public suggest endpoint (Normal section only). */
private suspend fun remoteSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(SUGGEST_URL + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
        }
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())
}

private const val SUGGEST_URL = "https://suggestqueries.google.com/complete/search?client=firefox&q="

@Composable
private fun Suggestions(query: String, section: Section, db: BrowserDb, onPick: (String) -> Unit, onFill: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var search by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var history by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    LaunchedEffect(query, section) {
        val q = query.trim()
        if (q.isEmpty()) {
            search = emptyList()
            history = emptyList()
            return@LaunchedEffect
        }
        val target = UrlInput.toUrl(q)
        val direct = if (UrlInput.isSearchPage(target) || target.startsWith(BrowserSettings.searchEngine.searchUrl)) {
            Suggestion(Icons.Rounded.Search, q, "${BrowserSettings.searchEngine.label} search", target, q)
        } else {
            Suggestion(Icons.Rounded.Public, UrlInput.display(target), "Go to address", target, q)
        }
        search = listOf(direct)
        if (section != Section.NORMAL) { // nothing typed in Incognito/Work leaves the device or reads Normal history
            history = emptyList()
            return@LaunchedEffect
        }
        history = withContext(Dispatchers.IO) {
            val b = db.bookmarks(q).take(3).map { Suggestion(Icons.Rounded.Star, it.title.ifBlank { UrlInput.display(it.url) }, UrlInput.display(it.url), it.url, it.url) }
            val h = db.history(q, 8).map { Suggestion(Icons.Rounded.History, it.title.ifBlank { UrlInput.display(it.url) }, UrlInput.display(it.url), it.url, it.url) }
            (b + h).distinctBy { it.url }.take(6)
        }
        if (BrowserSettings.searchEngine == SearchEngine.GOOGLE) {
            delay(150) // debounce: a newer keystroke cancels this effect
            val remote = remoteSuggestions(q).filter { !it.equals(q, ignoreCase = true) }.take(5)
            search = listOf(direct) + remote.map { Suggestion(Icons.Rounded.Search, it, null, UrlInput.toUrl(it), it) }
        }
    }
    Surface(color = cs.surface, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            if (search.isNotEmpty()) {
                item { SuggestionHeader("SEARCH") }
                items(search) { s -> SuggestionRow(s, onPick, onFill) }
            }
            if (history.isNotEmpty()) {
                item { SuggestionHeader("HISTORY") }
                items(history) { s -> SuggestionRow(s, onPick, onFill) }
            }
        }
    }
}

@Composable
private fun SuggestionHeader(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SuggestionRow(s: Suggestion, onPick: (String) -> Unit, onFill: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onPick(s.url) }.padding(start = 12.dp, end = 4.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(s.icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
            Text(s.title, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!s.subtitle.isNullOrBlank()) Text(s.subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onFill(s.fill) }) {
            Icon(Icons.Rounded.NorthWest, "Edit this", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// ============================================================================ error card
@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(22.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CloudOff, null, tint = cs.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Can't load this page", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 15.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

// ============================================================================ tab switcher
private val PREVIEW_COLORS = listOf(
    Color(0xFF1B4FD8), Color(0xFF16A085), Color(0xFFDC2626), Color(0xFF7C3AED),
    Color(0xFFEA580C), Color(0xFF0891B2), Color(0xFF4285F4), Color(0xFF374151),
)

/** A stable per-site colour for tab previews (no screenshots needed). */
private fun previewColor(tab: BrowserTab): Color = when {
    tab.isHome -> Color(0xFF94A3B8)
    tab.isWork -> Color(0xFF0F766E)
    else -> PREVIEW_COLORS[(UrlInput.hostAndPath(tab.url).first.hashCode() and 0x7fffffff) % PREVIEW_COLORS.size]
}

@Composable
private fun TabSwitcher(tabs: TabManager, signedIn: Boolean, onSignIn: () -> Unit, onClose: () -> Unit) {
    var shown by remember { mutableStateOf(tabs.section) }
    SectionTheme(shown) {
        val cs = MaterialTheme.colorScheme
        Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(56.dp).background(cs.surface).padding(start = 10.dp, end = 2.dp),
                ) {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(cs.surfaceVariant).padding(3.dp)) {
                        Section.entries.forEach { s ->
                            val sel = s == shown
                            val locked = s == Section.WORK && !signedIn
                            val count = tabs.tabsIn(s).size
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .then(if (sel) Modifier.shadow(1.dp, RoundedCornerShape(17.dp)) else Modifier)
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(if (sel) cs.surface else Color.Transparent)
                                    .clickable { if (locked) onSignIn() else shown = s },
                            ) {
                                Icon(if (locked) Icons.Rounded.Lock else sectionIcon(s), null, modifier = Modifier.size(16.dp), tint = if (sel) cs.primary else cs.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    (if (s == Section.INCOGNITO) "Incog." else sectionName(s)) + if (count > 0 && !locked) " $count" else "",
                                    fontSize = 13.sp, maxLines = 1,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close tab switcher", tint = cs.onSurfaceVariant) }
                }
                val list = tabs.tabsIn(shown)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(list, key = { it.id }) { t ->
                        val current = t.id == tabs.activeTab(shown)?.id && shown == tabs.section
                        TabCard(
                            t, current,
                            onSelect = {
                                tabs.select(t)
                                onClose()
                            },
                            onCloseTab = { tabs.close(t) },
                        )
                    }
                    item(key = "new") {
                        val outline = cs.outline
                        Column(
                            Modifier
                                .height(210.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .drawBehind {
                                    drawRoundRect(
                                        color = outline,
                                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                                        cornerRadius = CornerRadius(18.dp.toPx()),
                                    )
                                }
                                .clickable {
                                    tabs.newTab(shown)
                                    onClose()
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(if (shown == Section.WORK) "Work links" else "New tab", fontSize = 13.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
                Column(Modifier.background(cs.surface)) {
                    HorizontalDivider(color = cs.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp),
                    ) {
                        TextButton(onClick = { tabs.closeAll(shown) }, enabled = list.isNotEmpty()) { Text("Close all") }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer)
                                .clickable {
                                    tabs.newTab(shown)
                                    onClose()
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, "New tab", tint = cs.primary) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            if (shown != tabs.section) tabs.switchTo(shown)
                            onClose()
                        }) { Text("Done", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCard(t: BrowserTab, current: Boolean, onSelect: () -> Unit, onCloseTab: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val color = previewColor(t)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .height(210.dp)
            .shadow(if (current) 0.dp else 1.dp, shape)
            .clip(shape)
            .background(cs.surface)
            .border(if (current) 3.dp else 1.dp, if (current) cs.primary else cs.outlineVariant, shape)
            .clickable(onClick = onSelect),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 10.dp)) {
            if (t.isWork) {
                Icon(Icons.Rounded.Work, null, tint = cs.primary, modifier = Modifier.size(16.dp))
            } else {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color), contentAlignment = Alignment.Center) {
                    Text(t.label.take(1).uppercase(), fontSize = 9.sp, lineHeight = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = cs.onSurface)
            Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onCloseTab), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Close tab", modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }
        // A small page sketch stands in for a screenshot.
        Column(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)) {
            Box(Modifier.fillMaxWidth().height(54.dp).background(color), contentAlignment = Alignment.BottomStart) {
                Text(
                    when {
                        t.isHome -> "New tab"
                        t.isMasked -> t.workName ?: "Work link"
                        else -> UrlInput.hostAndPath(t.url).first
                    },
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp),
                )
            }
            listOf(.85f, .65f, .75f, .5f).forEach { w ->
                Box(Modifier.padding(start = 10.dp, top = 9.dp).fillMaxWidth(w).height(7.dp).clip(RoundedCornerShape(4.dp)).background(cs.outlineVariant))
            }
        }
    }
}

// ============================================================================ sheets
@Composable
private fun SectionSheet(current: Section, tabs: TabManager, signedIn: Boolean, workCount: Int, onPick: (Section) -> Unit, onSignIn: () -> Unit) {
    Column(Modifier.padding(bottom = 16.dp)) {
        SectionLabel("SWITCH SECTION", Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        SectionRow(Section.NORMAL, "Normal", "Your personal browsing · ${tabs.tabsIn(Section.NORMAL).size} tab(s)", current == Section.NORMAL, false) { onPick(Section.NORMAL) }
        SectionRow(
            Section.WORK, "Work",
            if (signedIn) "$workCount link(s) · ${tabs.tabsIn(Section.WORK).count { !it.isHome }} open" else "Sign in to use your work links",
            current == Section.WORK, !signedIn,
        ) { onPick(Section.WORK) }
        SectionRow(Section.INCOGNITO, "Incognito", "Private · nothing saved", current == Section.INCOGNITO, false) { onPick(Section.INCOGNITO) }
        if (!signedIn) {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp).height(48.dp)) {
                Text("Sign in to SyncUp")
            }
        }
    }
}

@Composable
private fun SectionRow(section: Section, title: String, subtitle: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (section) {
        Section.NORMAL -> Color(0xFFDCE7FF) to Color(0xFF2563EB)
        Section.WORK -> Color(0xFFCCFBF1) to Color(0xFF0F766E)
        Section.INCOGNITO -> Color(0xFF3B2A63) to Color(0xFFC4B5FD)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(sectionIcon(section), null, tint = fg)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Icon(
            when {
                locked -> Icons.Rounded.Lock
                selected -> Icons.Rounded.Check
                else -> Icons.Rounded.ChevronRight
            },
            null, tint = if (selected) cs.primary else cs.onSurfaceVariant,
        )
    }
}

private enum class MenuAction {
    NEW_TAB, NEW_INCOGNITO, FORWARD, RELOAD, BOOKMARK, SHARE, FIND,
    CHAT, RADIO, WORK, WORK_INFO, BOOKMARKS, HISTORY, DOWNLOADS, SETTINGS, SIGN_IN,
}

@Composable
private fun MenuSheet(tab: BrowserTab, db: BrowserDb, account: BrowserAccount, onAction: (MenuAction) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val page = !tab.isHome
    val normalPage = page && tab.section == Section.NORMAL
    var bookmarked by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url) { bookmarked = normalPage && withContext(Dispatchers.IO) { db.isBookmarked(tab.url) } }
    val signedIn = account.user != null
    val incognito = tab.section == Section.INCOGNITO

    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp)) {
            QuickAction(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", tab.canGoForward) { onAction(MenuAction.FORWARD) }
            QuickAction(if (bookmarked) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd, "Bookmark", normalPage) { onAction(MenuAction.BOOKMARK) }
            QuickAction(Icons.Rounded.FindInPage, "Find", page) { onAction(MenuAction.FIND) }
            QuickAction(Icons.Rounded.Refresh, "Reload", page) { onAction(MenuAction.RELOAD) }
        }
        HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
        SheetRow(Icons.Rounded.Add, "New tab") { onAction(MenuAction.NEW_TAB) }
        SheetRow(Icons.Rounded.VisibilityOff, "New incognito tab") { onAction(MenuAction.NEW_INCOGNITO) }
        if (signedIn && !incognito) {
            HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
            if (tab.section != Section.WORK) {
                SheetRow(Icons.Rounded.Work, "Work links", "${account.links.size}", iconTint = Color(0xFF0F766E)) { onAction(MenuAction.WORK) }
            }
            if (account.chatEnabled) {
                SheetRow(
                    Icons.Rounded.ChatBubbleOutline, "Chat with admin",
                    if (account.chatUnread > 0) "${account.chatUnread} new" else null, trailingColor = cs.error,
                ) { onAction(MenuAction.CHAT) }
            }
            if (account.radioEnabled) SheetRow(Icons.Rounded.Radio, "Radio") { onAction(MenuAction.RADIO) }
        }
        HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
        SheetRow(Icons.Rounded.Bookmarks, "Bookmarks") { onAction(MenuAction.BOOKMARKS) }
        SheetRow(Icons.Rounded.History, "History") { onAction(MenuAction.HISTORY) }
        SheetRow(Icons.Rounded.DownloadDone, "Downloads") { onAction(MenuAction.DOWNLOADS) }
        if (page) {
            if (tab.isWork) {
                SheetRow(Icons.Rounded.Info, "About this work link", iconTint = Color(0xFF0F766E)) { onAction(MenuAction.WORK_INFO) }
                SheetRow(Icons.Rounded.Share, "Share · Copy link", "Off for work", enabled = false) {}
            } else {
                SheetRow(Icons.Rounded.Share, "Share page") { onAction(MenuAction.SHARE) }
            }
        }
        SheetRow(Icons.Rounded.Settings, "Settings") { onAction(MenuAction.SETTINGS) }
        if (!signedIn) SheetRow(Icons.Rounded.Person, "Sign in to SyncUp", iconTint = cs.primary, textColor = cs.primary) { onAction(MenuAction.SIGN_IN) }
    }
}

@Composable
private fun RowScope.QuickAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = .35f))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = .5f))
    }
}

@Composable
private fun WorkInfoSheet(tab: BrowserTab, onReload: () -> Unit, onSearchNormal: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Work, null, tint = cs.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (tab.isMasked) tab.workName ?: "Work link" else UrlInput.hostAndPath(tab.url).first,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                )
                Text(
                    if (tab.isMasked) "Work link · address hidden" else "Opened from ${tab.workName ?: "a work link"} · Work",
                    fontSize = 12.sp, color = cs.onSurfaceVariant,
                )
            }
        }
        if (tab.isMasked) {
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).padding(14.dp),
            ) {
                Icon(Icons.Rounded.VisibilityOff, null, tint = cs.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "The address of this page is hidden by your organisation. You can use it here, but not copy, share or open it anywhere else.",
                    fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp,
                )
            }
        }
        if (UrlInput.isSecure(tab.url)) SheetRow(Icons.Rounded.Lock, "Connection is secure", iconTint = Color(0xFF16A34A)) {}
        SheetRow(Icons.Rounded.Refresh, "Reload", onClick = onReload)
        SheetRow(Icons.Rounded.Search, "Search the web in Normal", onClick = onSearchNormal)
        SheetRow(Icons.Rounded.ContentCopy, "Copy link · Share", "Not allowed", enabled = false) {}
    }
}
