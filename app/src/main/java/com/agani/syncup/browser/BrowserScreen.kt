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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agani.syncup.browser.ui.ProgressLine
import com.agani.syncup.browser.ui.SectionTheme
import com.agani.syncup.browser.ui.setBarIcons
import com.agani.syncup.data.AnnouncementDto
import com.agani.syncup.data.UrlItem
import com.agani.syncup.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var confirmSignOut by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var previousSection by remember { mutableStateOf(Section.NORMAL) }
    val snackbar = remember { SnackbarHostState() }

    val signedIn = account.user != null
    // Work exists only for signed-in users the admin/partners gave links to (or who still have work tabs open).
    val hasWork = signedIn && (account.links.isNotEmpty() || tabs.tabsIn(Section.WORK).any { !it.isHome })

    LaunchedEffect(signedIn, hasWork, section) {
        if (section == Section.WORK && (!signedIn || !hasWork)) tabs.switchTo(Section.NORMAL)
    }

    // Incognito: block screenshots / recents previews while it's on screen.
    DisposableEffect(section == Section.INCOGNITO) {
        val secure = section == Section.INCOGNITO
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun switchSection(s: Section) {
        if (s != tabs.section) previousSection = tabs.section
        tabs.switchTo(s)
    }

    fun startEditing(prefill: String) {
        editValue = TextFieldValue(prefill, TextRange(0, prefill.length))
        editing = true
    }

    fun openInCurrent(url: String) {
        val t = tabs.activeTab() ?: tabs.ensureTab(tabs.section)
        if (t.isWork) tabs.newTab(Section.NORMAL, url) else tabs.load(t, url)
    }

    fun openTabsFor(item: UrlItem) = tabs.tabsIn(Section.WORK).filter { it.workLinkId == item.id && !it.isHome }

    fun openWorkLink(item: UrlItem) {
        // A link that already has a tab switches to it (the "N open" chip), like "switch to tab".
        openTabsFor(item).lastOrNull()?.let {
            tabs.select(it)
            return
        }
        val home = tabs.activeTab(Section.WORK)?.takeIf { it.isHome }
        tabs.openWorkLink(item.title, item.url, item.id, item.notifyToken)
        home?.let { tabs.close(it) }
    }

    fun offerUndo(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
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
        // Work pages have no address bar: the page runs up to the status bar (link info lives in ⋮).
        val showPageBar = onPage && tab?.isWork == false
        // Status + navigation bars take the page's theme-color; otherwise the chrome's surface.
        val pageColor = tab?.themeColor?.takeIf { onPage && !editing && !finding && !showTabs }?.let { Color(it) }
        val topColor = pageColor ?: cs.surface
        // The navigation-bar strip continues the bottom bar's tone (or the page colour on web pages).
        val navColor = pageColor ?: if (editing) cs.surface else cs.surfaceContainer
        val lightTop = topColor.luminance() > 0.5f
        val lightNav = navColor.luminance() > 0.5f
        DisposableEffect(lightTop, lightNav) {
            setBarIcons(activity, lightTop, lightNav)
            onDispose { }
        }
        val barBottom = BrowserSettings.addressBarBottom

        Column(Modifier.fillMaxSize().background(navColor)) {
        Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(topColor))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
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
                        PageBar(tab, db, atBottom = false, onTap = { startEditing(tab.url) }, onReload = { tabs.reload(tab) }, onStop = { tabs.stop(tab) })
                        ProgressLine(tab.loading, tab.progress, Modifier.background(cs.surface))
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().background(cs.surface)) {
                    if (tab != null) {
                        if (tab.isHome) {
                            when (section) {
                                Section.NORMAL -> NormalHome(
                                    account = account,
                                    hasWork = hasWork,
                                    workOpenTabs = tabs.tabsIn(Section.WORK).count { !it.isHome },
                                    onSearch = { startEditing("") },
                                    onVoice = { voiceSearch() },
                                    onOpenUrl = { openInCurrent(it) },
                                    onAvatar = { if (!signedIn) actions.onSignIn() else showAccount = true },
                                    onOpenWork = { switchSection(Section.WORK) },
                                    onOpenRadio = actions.onOpenRadio,
                                    onUndo = ::offerUndo,
                                )
                                Section.INCOGNITO -> IncognitoHome(onSearch = { startEditing("") })
                                Section.WORK -> WorkHome(
                                    account = account,
                                    openCount = { openTabsFor(it).size },
                                    onOpen = { openWorkLink(it) },
                                    onAvatar = { showAccount = true },
                                    onRefresh = actions.onRefreshLinks,
                                )
                            }
                        } else {
                            key(tab.id) {
                                AndroidView(factory = { tabs.attachable(tab) }, modifier = Modifier.fillMaxSize())
                            }
                            tab.error?.let { msg -> ErrorPage(msg) { tabs.reload(tab) } }
                            if (!showPageBar) ProgressLine(tab.loading, tab.progress, Modifier.align(Alignment.TopStart))
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
                    ProgressLine(tab.loading, tab.progress, Modifier.background(cs.surfaceContainer))
                    PageBar(tab, db, atBottom = true, onTap = { startEditing(tab.url) }, onReload = { tabs.reload(tab) }, onStop = { tabs.stop(tab) })
                }
                if (tab != null && !editing) {
                    BottomBar(
                        tab = tab,
                        section = section,
                        tabCount = tabs.tabsIn(section).size,
                        onBack = { tabs.back() },
                        onForward = { tabs.forward() },
                        onSection = { showSections = true },
                        onSectionLong = {
                            val target = previousSection.takeIf { it != section && (it != Section.WORK || hasWork) } ?: Section.NORMAL
                            if (target != section) switchSection(target)
                        },
                        onTabs = {
                            tabs.captureActive()
                            showTabs = true
                        },
                        onMenu = { showMenu = true },
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showTabs,
                enter = fadeIn(tween(200)) + scaleIn(tween(300), initialScale = .96f),
                exit = fadeOut(tween(150)) + scaleOut(tween(200), targetScale = .98f),
            ) {
                TabSwitcher(
                    tabs = tabs,
                    signedIn = signedIn,
                    hasWork = hasWork,
                    onSignIn = actions.onSignIn,
                    onUndo = ::offerUndo,
                    onClose = { showTabs = false },
                )
            }

            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 80.dp),
            ) { data ->
                Snackbar(
                    data,
                    shape = MaterialTheme.shapes.small,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }
        }
        }

        if (showSections) {
            ModalBottomSheet(onDismissRequest = { showSections = false }) {
                SectionSheet(
                    current = section,
                    tabs = tabs,
                    signedIn = signedIn,
                    hasWork = hasWork,
                    workCount = account.links.size,
                    onPick = { s ->
                        showSections = false
                        switchSection(s)
                    },
                    onSignIn = {
                        showSections = false
                        actions.onSignIn()
                    },
                )
            }
        }

        if (showMenu && tab != null) {
            ModalBottomSheet(onDismissRequest = { showMenu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                MenuSheet(
                    tab = tab,
                    db = db,
                    account = account,
                    hasWork = hasWork,
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
                            MenuAction.WORK -> switchSection(Section.WORK)
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
            ModalBottomSheet(onDismissRequest = { showWorkInfo = false }) {
                WorkInfoSheet(
                    tab = tab,
                    onReload = {
                        showWorkInfo = false
                        tabs.reload(tab)
                    },
                    onSearchNormal = {
                        showWorkInfo = false
                        switchSection(Section.NORMAL)
                        startEditing("")
                    },
                )
            }
        }
    }

    // Account + sign-in sheets use the app theme, not the section tint.
    if (showAccount && account.user != null) {
        ModalBottomSheet(onDismissRequest = { showAccount = false }) {
            AccountSheet(
                account = account,
                hasWork = hasWork,
                onWork = {
                    showAccount = false
                    switchSection(Section.WORK)
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
                    confirmSignOut = true
                },
            )
        }
    }
    if (confirmSignOut) {
        SignOutDialog(
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                actions.onSignOut()
            },
        )
    }
    if (signInVisible && account.user == null) {
        ModalBottomSheet(onDismissRequest = actions.onDismissSignIn) {
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
