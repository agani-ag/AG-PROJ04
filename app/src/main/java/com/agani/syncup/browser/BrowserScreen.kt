package com.agani.syncup.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.agani.syncup.browser.ui.sectionScheme
import com.agani.syncup.browser.ui.chrome
import com.agani.syncup.browser.ui.setBarIcons
import com.agani.syncup.data.AnnouncementDto
import com.agani.syncup.data.LoginMode
import com.agani.syncup.sync.BrowserSync
import com.agani.syncup.data.UrlItem
import com.agani.syncup.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SyncUp account data the browser shows (all empty/null when nobody is logged in). */
data class BrowserAccount(
    val user: User? = null,
    val links: List<UrlItem> = emptyList(),
    val announcement: AnnouncementDto? = null,
    val refreshing: Boolean = false,
    val loginLoading: Boolean = false,
    val loginError: String? = null,
    val supportEmail: String = "",
    val supportPhone: String = "",
    val signupEnabled: Boolean = false,
    val privacyUrl: String = "",
    val partnersWaiting: Int = 0,
    val hasPartners: Boolean = false,
)

enum class LibraryPage { HISTORY, BOOKMARKS, DOWNLOADS }

/** What the browser asks the rest of the app to do. */
class BrowserActions(
    val onSignIn: () -> Unit,
    val onDismissSignIn: () -> Unit,
    val onLogin: (mode: LoginMode, login: String, password: String) -> Unit,
    val onSignup: (name: String, email: String, phone: String, password: String) -> Unit,
    val onClearAuthError: () -> Unit,
    val onSignOut: () -> Unit,
    val onOpenPartners: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenMusic: () -> Unit,
    val onOpenTools: () -> Unit,
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
    // The section the tab switcher is showing (it can look at another section than the one in use).
    var switcherSection by remember { mutableStateOf(tabs.section) }
    var showSections by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showWorkInfo by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var showDetected by remember { mutableStateOf(false) }
    var previousSection by remember { mutableStateOf(Section.NORMAL) }
    val snackbar = remember { SnackbarHostState() }
    // Sign-in / sign-up typing survives the sheet being closed by accident; cleared once signed in.
    val authForm = remember { AuthFormState() }
    LaunchedEffect(account.user?.id) { if (account.user != null) authForm.clear() }

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

    // A user with exactly one SyncUp link goes straight to it wherever SyncUp is opened (TabManager.directLink).
    val singleLink = account.links.singleOrNull()?.takeIf { signedIn }
    val direct = singleLink?.let { TabManager.DirectLink(it.title, it.url, it.id) }
    LaunchedEffect(direct) {
        tabs.directLink = direct
        // Down to one link while the SyncUp links page is showing: show the website instead.
        if (direct != null && tabs.section == Section.WORK && tabs.activeTab()?.isHome == true) tabs.switchTo(Section.WORK)
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
        // One tab per link: an open link goes back to its page that was last on screen.
        tabs.linkFront(item.id)?.let {
            tabs.select(it)
            return
        }
        val home = tabs.activeTab(Section.WORK)?.takeIf { it.isHome }
        tabs.openWorkLink(item.title, item.url, item.id)
        home?.let { tabs.close(it) }
    }

    fun offerUndo(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
    }

    // A pop-up the user didn't tap for was blocked: offer it, like Chrome ("Allow" also lets this site
    // open pop-ups from now on).
    val blocked = tabs.blockedPopup
    LaunchedEffect(blocked) {
        blocked ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar("Pop-up blocked", actionLabel = "Allow", withDismissAction = true, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) tabs.allowBlockedPopup(blocked) else tabs.dismissBlockedPopup()
    }

    // Text size and website darkening reach pages that are already open.
    LaunchedEffect(BrowserSettings.textZoom, BrowserSettings.darkenWebsites) { tabs.applySettings() }

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

    // Back stays in the app: page history → the tab that opened this one → the tab's home →
    // Normal (from the SyncUp or Incognito home) → on Normal's home, a second Back within 2 s
    // sends the app to the background (tabs are kept, like the system's own Back).
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = true) {
        when {
            web.fullscreen -> web.exitFullscreen()
            editing -> editing = false
            finding -> {
                finding = false
                tab?.let { tabs.webView(it).clearMatches() }
            }
            showTabs -> showTabs = false
            tabs.back() -> Unit
            section != Section.NORMAL -> switchSection(Section.NORMAL)
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2_000) {
                    snackbar.currentSnackbarData?.dismiss()
                    activity?.moveTaskToBack(true)
                } else {
                    lastBackAt = now
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar("Press back again to exit", duration = SnackbarDuration.Short)
                    }
                }
            }
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
        val barBottom = BrowserSettings.addressBarBottom
        // The address bar slides away while the page scrolls down (the bottom bar always stays).
        val barHidden = showPageBar && tab?.barHidden == true && !editing && !finding && !showTabs
        // Each system bar takes the colour of what it touches. The navigation bar continues our bottom
        // bar (never the website's colour). The status bar matches the address / find bar under it; only
        // where the website itself reaches the top (SyncUp pages, or the address bar set to the bottom)
        // does it take the site's theme-color — never in Incognito, which stays one dark frame. With the
        // tab switcher open, both take the switcher's section.
        val pageColor = tab?.themeColor
            ?.takeIf { onPage && !editing && !finding && !showTabs && section != Section.INCOGNITO }
            ?.let { Color(it) }
        val siteAtTop = onPage && !editing && !finding && (!showPageBar || barBottom || barHidden)
        val switcherColors = sectionScheme(switcherSection)
        val topTarget = when {
            showTabs -> switcherColors.surface
            siteAtTop -> pageColor ?: cs.surface
            onPage && !editing -> cs.chrome
            else -> cs.surface
        }
        val navTarget = when {
            showTabs -> switcherColors.surfaceContainer
            editing -> cs.surface
            else -> cs.chrome
        }
        val topColor by animateColorAsState(topTarget, tween(250), label = "statusBar")
        val navColor by animateColorAsState(navTarget, tween(250), label = "navBar")
        val lightTop = topTarget.luminance() > 0.5f
        val lightNav = navTarget.luminance() > 0.5f
        DisposableEffect(lightTop, lightNav) {
            setBarIcons(activity, lightTop, lightNav)
            onDispose { }
        }

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
                    showPageBar && !barBottom -> androidx.compose.animation.AnimatedVisibility(
                        visible = !barHidden,
                        enter = expandVertically(tween(160)),
                        exit = shrinkVertically(tween(160)),
                    ) {
                        Column {
                            PageBar(tab, db, atBottom = false, onTap = { startEditing(tab.url) }, onReload = { tabs.reload(tab) }, onStop = { tabs.stop(tab) })
                            ProgressLine(tab.loading, tab.progress, Modifier.background(cs.chrome))
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().background(cs.surface)) {
                    if (tab != null) {
                        if (tab.isHome) {
                            when (section) {
                                Section.NORMAL -> NormalHome(
                                    account = account,
                                    hasWork = hasWork,
                                    workOpenTabs = singleLink?.let { tabs.linkPageCount(it.id) } ?: tabs.tabsIn(Section.WORK).count { !it.isHome },
                                    singleLink = singleLink,
                                    onSearch = { startEditing("") },
                                    onVoice = { voiceSearch() },
                                    onOpenUrl = { openInCurrent(it) },
                                    onAvatar = { if (!signedIn) actions.onSignIn() else showAccount = true },
                                    onOpenWork = { switchSection(Section.WORK) },
                                    onOpenMusic = actions.onOpenMusic,
                                )
                                Section.INCOGNITO -> IncognitoHome(onSearch = { startEditing("") })
                                Section.WORK -> WorkHome(
                                    account = account,
                                    openCount = { tabs.linkPageCount(it.id) },
                                    onOpen = { openWorkLink(it) },
                                    onAvatar = { showAccount = true },
                                    onRefresh = actions.onRefreshLinks,
                                )
                            }
                        } else if (tab.error != null) {
                            // The WebView is left out while the error shows (it stays alive for Retry):
                            // with nothing to show, some GPUs let its surface paint over the address bar.
                            ErrorPage(tab.error.orEmpty()) { tabs.reload(tab) }
                        } else {
                            key(tab.id) {
                                AndroidView(factory = { tabs.attachable(tab) }, modifier = Modifier.fillMaxSize())
                            }
                            if (!showPageBar || barHidden) ProgressLine(tab.loading, tab.progress, Modifier.align(Alignment.TopStart))
                            PullIndicator(tab, Modifier.align(Alignment.TopCenter))
                            // Videos, music and files on the page that can be downloaded.
                            if (BrowserSettings.mediaDetector && tab.detected.isNotEmpty() && !editing) {
                                DetectorButton(tab.detected.size, onClick = { showDetected = true }, Modifier.align(Alignment.BottomEnd).padding(12.dp))
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

                val bottomPageBar = tab != null && showPageBar && barBottom && !editing && !finding
                if (tab != null && bottomPageBar) {
                    // Address bar at the bottom: it and the bottom bar read as one block under one line.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !barHidden,
                        enter = expandVertically(tween(160)),
                        exit = shrinkVertically(tween(160)),
                    ) {
                        Column {
                            HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
                            ProgressLine(tab.loading, tab.progress, Modifier.background(cs.chrome))
                            PageBar(tab, db, atBottom = true, onTap = { startEditing(tab.url) }, onReload = { tabs.reload(tab) }, onStop = { tabs.stop(tab) })
                        }
                    }
                }
                if (tab != null && !editing) {
                    BottomBar(
                        divider = !bottomPageBar || barHidden,
                        tab = tab,
                        section = section,
                        tabCount = tabs.cardsIn(section).size,
                        onBack = { tabs.back() },
                        onForward = { tabs.forward() },
                        onSection = { showSections = true },
                        onSectionLong = {
                            val target = previousSection.takeIf { it != section && (it != Section.WORK || hasWork) } ?: Section.NORMAL
                            if (target != section) switchSection(target)
                        },
                        onTabs = {
                            tabs.captureActive()
                            switcherSection = section
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
                    shown = switcherSection,
                    onShow = { switcherSection = it },
                    signedIn = signedIn,
                    hasWork = hasWork,
                    // Open Normal tabs on the user's other devices (browser sync).
                    otherDevices = if (signedIn && BrowserSync.enabled && BrowserSync.isOn(com.agani.syncup.sync.SyncType.TABS)) BrowserSync.otherDevices else emptyList(),
                    onOpenOther = { url ->
                        showTabs = false
                        tabs.newTab(Section.NORMAL, url)
                    },
                    onSignIn = actions.onSignIn,
                    onUndo = ::offerUndo,
                    onClose = { showTabs = false },
                )
            }

            // A site asks for the camera, microphone, location or notifications.
            SitePermissions.prompt?.let { p ->
                SitePermissionCard(p, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp))
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
                    dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }

        // A page started a download and "Ask before each download" is on.
        com.agani.syncup.downloads.Downloads.prompt?.let { req ->
            com.agani.syncup.downloads.DownloadPromptSheet(req) { com.agani.syncup.downloads.Downloads.prompt = null }
        }

        // Long-press on a link or an image.
        tabs.pressed?.let { p ->
            val from = tabs.tabs.firstOrNull { it.id == p.tabId }
            if (from == null) {
                tabs.dismissPressed()
            } else {
                PressMenuSheet(p.target, from, onDismiss = { tabs.dismissPressed() }, onAction = { action ->
                    tabs.dismissPressed()
                    val link = p.target.link.orEmpty()
                    val image = p.target.image.orEmpty()
                    val wv = tabs.viewOf(from)
                    when (action) {
                        PressAction.OPEN_NEW, PressAction.OPEN_IMAGE -> {
                            val opened = tabs.openInBackground(from, if (action == PressAction.OPEN_NEW) link else image)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                val r = snackbar.showSnackbar("Opened in a new tab", actionLabel = "Switch", duration = SnackbarDuration.Short)
                                if (r == SnackbarResult.ActionPerformed && tabs.tabs.contains(opened)) tabs.select(opened)
                            }
                        }
                        PressAction.OPEN_INCOGNITO -> tabs.newTab(Section.INCOGNITO, link)
                        PressAction.COPY_LINK, PressAction.COPY_IMAGE_LINK -> {
                            val text = if (action == PressAction.COPY_LINK) link else image
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Link", text))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                        PressAction.SHARE_LINK -> runCatching {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share link"))
                        }
                        PressAction.DOWNLOAD_LINK -> wv?.let { web.downloadUrl(it, from, link, suggestedName = null) }
                        PressAction.DOWNLOAD_IMAGE -> wv?.let { web.downloadUrl(it, from, image) }
                        PressAction.SHARE_IMAGE -> wv?.let { web.shareImage(it, from, image) }
                    }
                })
            }
        }

        // The detector's list: videos, music and files on this page.
        if (showDetected && tab != null && tab.detected.isNotEmpty()) {
            val wv = tabs.viewOf(tab)
            DetectedSheet(
                files = tab.detected.toList(),
                requestFor = { url -> if (wv != null) web.downloadRequest(wv, tab, url) else com.agani.syncup.downloads.DownloadRequest(url) },
                onSave = { f ->
                    wv?.let { web.downloadUrl(it, tab, f.url, f.name, ask = false) }
                    Toast.makeText(context, "Downloading ${f.name}", Toast.LENGTH_SHORT).show()
                },
                onPlay = { f ->
                    // A video found on the page plays in SyncUp's video player (same cookies and referer as the page).
                    val r = if (wv != null) web.downloadRequest(wv, tab, f.url) else com.agani.syncup.downloads.DownloadRequest(f.url)
                    showDetected = false
                    context.startActivity(
                        com.agani.syncup.video.VideoPlayerActivity.intent(context, r.url, f.name, "Video link", r.userAgent, r.referer, r.cookies),
                    )
                },
                onSaveAll = {
                    showDetected = false
                    tab.detected.toList().forEach { f -> wv?.let { web.downloadUrl(it, tab, f.url, f.name, ask = false) } }
                    Toast.makeText(context, "Downloading ${tab.detected.size} files", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showDetected = false },
            )
        } else if (showDetected) {
            showDetected = false
        }

        if (showSections) {
            ModalBottomSheet(onDismissRequest = { showSections = false }) {
                SectionSheet(
                    current = section,
                    tabs = tabs,
                    signedIn = signedIn,
                    hasWork = hasWork,
                    workCount = account.links.size,
                    singleLinkName = singleLink?.title,
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
                            MenuAction.DESKTOP -> tabs.setDesktop(tab, !tab.desktop)
                            MenuAction.ADS -> {
                                AdBlocker.setAllowed(tab.pageHost, !AdBlocker.isAllowed(tab.pageHost))
                                tabs.reload(tab)
                            }
                            MenuAction.OPEN_IN_CHROME -> openInBrowserApp(context, tab.url)
                            MenuAction.MUSIC -> actions.onOpenMusic()
                            MenuAction.TOOLS -> actions.onOpenTools()
                            MenuAction.WORK -> switchSection(Section.WORK)
                            MenuAction.WORK_INFO -> showWorkInfo = true
                            MenuAction.BOOKMARKS -> actions.onOpenLibrary(LibraryPage.BOOKMARKS)
                            MenuAction.HISTORY -> actions.onOpenLibrary(LibraryPage.HISTORY)
                            MenuAction.DOWNLOADS -> actions.onOpenLibrary(LibraryPage.DOWNLOADS)
                            MenuAction.SETTINGS -> actions.onOpenSettings()
                            MenuAction.SIGN_IN -> actions.onSignIn()
                            MenuAction.PARTNERS -> actions.onOpenPartners()
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
                onSettings = {
                    showAccount = false
                    actions.onOpenSettings()
                },
                onPartners = {
                    showAccount = false
                    actions.onOpenPartners()
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
        ModalBottomSheet(
            onDismissRequest = {
                actions.onClearAuthError()
                actions.onDismissSignIn()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SignInSheet(
                form = authForm,
                loading = account.loginLoading,
                error = account.loginError,
                supportEmail = account.supportEmail,
                supportPhone = account.supportPhone,
                signupEnabled = account.signupEnabled,
                privacyUrl = account.privacyUrl,
                onLogin = actions.onLogin,
                onSignup = actions.onSignup,
                onClearError = actions.onClearAuthError,
            )
        }
    }
}

/** Chrome's package: "Open in Chrome" hands it the page (e.g. Google sign-in, which apps can't host). */
internal const val CHROME_PACKAGE = "com.android.chrome"

/** Hand [url] to Chrome, or to another browser app when the phone has no Chrome. */
private fun openInBrowserApp(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val opened = runCatching { context.startActivity(Intent(view).setPackage(CHROME_PACKAGE)) }.isSuccess ||
        runCatching { context.startActivity(Intent.createChooser(view, "Open with")) }.isSuccess
    if (!opened) Toast.makeText(context, "No browser app found", Toast.LENGTH_SHORT).show()
}
