package com.agani.syncup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agani.syncup.auth.AuthViewModel
import com.agani.syncup.data.AppPrefs
import com.agani.syncup.data.SecurityStore
import com.agani.syncup.data.ThemeMode
import com.agani.syncup.push.AppBootstrap
import com.agani.syncup.push.DeviceRegistrar
import com.agani.syncup.reminders.ReminderContract
import com.agani.syncup.reminders.ReminderSync
import com.agani.syncup.reminders.ReminderSyncWorker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.agani.syncup.ui.AnnouncementScreen
import com.agani.syncup.ui.ConnectivityBanner
import com.agani.syncup.ui.ForceUpdateScreen
import com.agani.syncup.ui.LockScreen
import com.agani.syncup.ui.ProfileScreen
import com.agani.syncup.ui.SplashScreen
import com.agani.syncup.ui.theme.AgHubTheme
import com.agani.syncup.web.WebViewActivity
import com.agani.syncup.browser.BrowserAccount
import com.agani.syncup.browser.BrowserActions
import com.agani.syncup.browser.BrowserDb
import com.agani.syncup.browser.BrowserScreen
import com.agani.syncup.browser.LibraryPage
import com.agani.syncup.browser.LibraryScreen
import com.agani.syncup.browser.Section
import com.agani.syncup.browser.TabManager
import com.agani.syncup.browser.WebPlatform
import com.agani.syncup.data.SyncTab
import com.agani.syncup.music.LocalPlayer
import com.agani.syncup.music.MusicScreen
import com.agani.syncup.music.rememberPlayerHandle
import com.agani.syncup.sync.BrowserSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class AppScreen { Splash, ForceUpdate, Announcement, Browser, Profile, Music, Library, Partners }

class MainActivity : FragmentActivity() {

    private val security by lazy { SecurityStore(this) }
    private val appPrefs by lazy { AppPrefs(this) }
    private val lockedState = mutableStateOf(false)

    // The browser: web-platform bridge (registers result launchers → must be a property
    // initializer), on-device history/bookmarks/downloads, and the tabs of all three sections.
    private val webPlatform = WebPlatform(this)
    private val browserDb by lazy { BrowserDb.get(this) }
    private lateinit var tabManager: TabManager

    // A link a notification/reminder tap wants opened — honored once unlocked (and, for a Work
    // link, logged in). Broadcast links open in a Normal tab, signed in or not.
    private data class PendingLink(val url: String, val title: String, val normal: Boolean = false)
    private val pendingLink = mutableStateOf<PendingLink?>(null)

    // Set when a chat entry point (button / bubble / push tap) asks us to open the chat screen.
    private val openChatRequest = mutableStateOf(false)

    // Set when the media notification is tapped — open the Music player.
    private val openMusicRequest = mutableStateOf(false)

    // Id of a partner verification prompt to open (from an action push tap).
    private val pendingActionId = mutableStateOf<String?>(null)

    // Set when a "partner added you" push is tapped — open the Partners page.
    private val openPartnersRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status and navigation bars on every Android version (15+ already does),
        // so each screen paints its own bars — Incognito dark, a page's theme colour, and so on —
        // instead of the window's fixed colour. No grey contrast scrim over the button nav bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        lockedState.value = security.hasPin() // lock on cold start if a PIN is set
        com.agani.syncup.browser.BrowserSettings.init(this)
        tabManager = TabManager(this, webPlatform, browserDb).also { it.restore() }
        // Browser sync: publishes the open Normal tabs and uploads changes shortly after they happen.
        BrowserSync.init(this, browserDb)
        BrowserSync.tabsProvider = {
            tabManager.tabsIn(Section.NORMAL).filter { !it.isHome }.map { SyncTab(it.title, it.url) }
        }
        tabManager.onNormalTabsChanged = { BrowserSync.requestSync() }
        readDeepLink(intent)
        ReminderSyncWorker.schedulePeriodic(this) // daily safety-net reminder sync

        setContent {
            var themeMode by remember { mutableStateOf(appPrefs.themeMode()) }
            // A theme chosen on the user's other device (browser sync) applies live.
            DisposableEffect(Unit) {
                BrowserSync.onRemoteTheme = { themeMode = it }
                onDispose { BrowserSync.onRemoteTheme = null }
            }
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.BLACK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            AgHubTheme(darkTheme = dark, amoled = themeMode == ThemeMode.BLACK) {
                // Verification prompts (OTP / code / number) are handled at the top level so they
                // can appear ON TOP of the lock screen — the app PIN is skipped for these only,
                // and the app stays locked underneath once the prompt is closed.
                val actionVm: AuthViewModel = viewModel()
                var activeAction by remember { mutableStateOf<com.agani.syncup.data.ActionDto?>(null) }
                LaunchedEffect(pendingActionId.value, actionVm.state.isLoggedIn) {
                    val aid = pendingActionId.value
                    if (aid != null && actionVm.state.isLoggedIn) {
                        val result = actionVm.fetchAction(aid)
                        result.onSuccess { activeAction = it }
                            .onFailure {
                                android.widget.Toast.makeText(
                                    this@MainActivity, it.message ?: "This verification is no longer available.",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        pendingActionId.value = null
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // Crash trace is always saved silently; only devs see the raw-trace dialog.
                    if (com.agani.syncup.BuildConfig.DEBUG) {
                        com.agani.syncup.ui.CrashReportDialog()
                    }
                    if (lockedState.value) {
                        LockScreen(
                            biometricEnabled = appPrefs.biometricEnabled(),
                            onCheckPin = { security.checkPin(it) },
                            onUnlock = { lockedState.value = false },
                            onBiometric = { promptBiometric { lockedState.value = false } },
                            onForgotPin = {
                                // No admin PIN recovery — clear the on-device PIN + session and restart to Login.
                                security.clearPin()
                                appPrefs.setBiometricEnabled(false)
                                com.agani.syncup.data.TokenStore(this@MainActivity).clear()
                                BrowserSync.onSignedOut()
                                tabManager.wipeWork()
                                lockedState.value = false
                                recreate()
                            },
                        )
                    } else {
                        AppContent(
                            themeMode = themeMode,
                            onThemeChange = {
                                appPrefs.setThemeMode(it)
                                themeMode = it
                                BrowserSync.themeChanged()
                            },
                        )
                    }
                    // Live "you're offline" strip across all app screens.
                    ConnectivityBanner(Modifier.align(Alignment.BottomCenter))

                    // Verification prompt — drawn last so it overlays everything, including the
                    // lock screen (so the user isn't blocked by the app PIN for a time-sensitive code).
                    activeAction?.let { act ->
                        com.agani.syncup.ui.ActionScreen(
                            action = act,
                            onSubmit = { value -> actionVm.respondAction(act.id, value) },
                            onClose = { activeAction = null },
                            // A notice's optional CTA opens in the in-app browser.
                            onOpenLink = { url ->
                                startActivity(WebViewActivity.intent(this@MainActivity, url, act.title))
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()  // runs ProcessLifecycleOwner.onStart → AppLock.onForeground() first
        // Re-lock only when the app was away longer than the grace (AppLock decides), never on
        // rotation or a quick round-trip to a picker/camera/external link.
        if (AppLock.pendingLock) {
            AppLock.pendingLock = false
            if (security.hasPin()) lockedState.value = true
        }
    }

    override fun onPause() {
        tabManager.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabManager.onResume()
    }

    override fun onDestroy() {
        // The sync engine outlives the Activity; don't let it hold on to this one.
        BrowserSync.tabsProvider = null
        tabManager.onNormalTabsChanged = null
        tabManager.destroyAll()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLink(intent)
    }

    /** Pull a link (from a reminder/push tap) off the launch intent so we can open it after unlock. */
    private fun readDeepLink(intent: Intent?) {
        val url = intent?.getStringExtra(ReminderContract.EXTRA_LINK_URL)
        if (!url.isNullOrBlank()) {
            // "open_in" = our own extra, or the FCM data key when the system tray showed the push.
            val normal = intent.getStringExtra(EXTRA_OPEN_IN) == "normal"
            pendingLink.value = PendingLink(url, intent.getStringExtra(ReminderContract.EXTRA_LINK_TITLE) ?: "", normal)
            // Consume it so a config change / re-onStart doesn't reopen the link.
            intent.removeExtra(ReminderContract.EXTRA_LINK_URL)
            intent.removeExtra(ReminderContract.EXTRA_LINK_TITLE)
            intent.removeExtra(EXTRA_OPEN_IN)
        }
        // A partner added this user: our foreground extra, or the tray's data payload (type=partners).
        if (intent?.getBooleanExtra(EXTRA_OPEN_PARTNERS, false) == true || intent?.getStringExtra("type") == "partners") {
            openPartnersRequest.value = true
            intent.removeExtra(EXTRA_OPEN_PARTNERS)
            intent.removeExtra("type")
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_MUSIC, false) == true) {
            openMusicRequest.value = true
            intent.removeExtra(EXTRA_OPEN_MUSIC)
        }
        // EXTRA_OPEN_CHAT = our own foreground path (SyncUpMessagingService / bubble).
        // "type" == "chat" = the FCM data payload delivered by the system tray when the app was
        // backgrounded (onMessageReceived isn't called then, so our extra isn't set).
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true || intent?.getStringExtra("type") == "chat") {
            openChatRequest.value = true
            intent.removeExtra(EXTRA_OPEN_CHAT)
            intent.removeExtra("type")
        }
        // A verification prompt: our foreground extra, or the system-tray data payload when the
        // app was backgrounded (type=action + action_id).
        val actionId = intent?.getStringExtra(EXTRA_ACTION_ID)
            ?: intent?.takeIf { it.getStringExtra("type") == "action" }?.getStringExtra("action_id")
        if (!actionId.isNullOrBlank()) {
            pendingActionId.value = actionId
            intent?.removeExtra(EXTRA_ACTION_ID)
            intent?.removeExtra("type")
            intent?.removeExtra("action_id")
        }
    }

    @Composable
    private fun AppContent(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
        val vm: AuthViewModel = viewModel()
        // The Music player's state (songs + Radio), shared by every screen through LocalPlayer.
        val player = rememberPlayerHandle()

        var booted by remember { mutableStateOf(false) }
        var forceUpdate by remember { mutableStateOf(false) }
        var showProfile by remember { mutableStateOf(false) }
        var showMusic by remember { mutableStateOf(false) }
        var showLogin by remember { mutableStateOf(false) }
        var showPartners by remember { mutableStateOf(false) }
        var signupEnabled by remember { mutableStateOf(false) }
        var libraryPage by remember { mutableStateOf<LibraryPage?>(null) }
        var announcement by remember { mutableStateOf<com.agani.syncup.data.AnnouncementDto?>(null) }
        var supportEmail by remember { mutableStateOf("") }
        var supportPhone by remember { mutableStateOf("") }
        var privacyUrl by remember { mutableStateOf("") }
        var chatEnabled by remember { mutableStateOf(true) }
        var radioEnabled by remember { mutableStateOf(false) }

        // Apply a freshly-fetched server config to the screen state (force-update, announcement,
        // support contacts, privacy URL, chat toggle).
        fun applyConfig(cfg: com.agani.syncup.data.ConfigResponse) {
            forceUpdate = cfg.minSupportedVersion > BuildConfig.VERSION_CODE
            announcement = cfg.announcement
            supportEmail = cfg.supportEmail
            supportPhone = cfg.supportPhone
            privacyUrl = cfg.privacyPolicyUrl
            chatEnabled = cfg.chatEnabled
            radioEnabled = cfg.radioEnabled
            signupEnabled = cfg.signupEnabled
        }

        // One refresh that pulls everything the server can change. Two calls total: a combined
        // GET /sync (user details + links + chat badge + config) and the separate reminders sync
        // (it has its own scheduling/ack flow). Used by pull-to-refresh + foreground return.
        fun runFullRefresh(silent: Boolean) {
            vm.syncAll(silent = silent) { cfg -> applyConfig(cfg) }                          // GET /sync
            lifecycleScope.launch(Dispatchers.IO) { ReminderSync.sync(applicationContext) }  // GET /reminders
            BrowserSync.syncNow()                                                            // POST /browser/sync
        }

        LaunchedEffect(Unit) {
            val started = SystemClock.elapsedRealtime()
            // Base URL is needed before any API call — bound it so startup never hangs.
            withTimeoutOrNull(2500) { withContext(Dispatchers.IO) { AppBootstrap.applyBaseUrl() } }
            // Every install checks in (signed in or not) so SyncUp can reach it.
            DeviceRegistrar.hello(applicationContext)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed < 600) delay(600 - elapsed)
            booted = true
            // Server config (announcement + force-update + support contacts) — after the splash, non-blocking.
            val cfg = withContext(Dispatchers.IO) { AppBootstrap.fetchConfig() }
            if (cfg != null) applyConfig(cfg)
        }

        val state = vm.state

        // Full-screen announcement: shown when the server marks it full-screen. A blocking one has
        // no dismiss and reappears every launch; a normal one shows once per unique content
        // (identified by a content hash stored in AppPrefs).
        var announcementDismissed by remember { mutableStateOf(false) }
        val ann = announcement
        val annHash = if (ann != null) "${ann.title}\n${ann.message}".hashCode().toString() else ""
        val showAnnouncement = ann != null && ann.active && ann.fullscreen &&
            !announcementDismissed &&
            (ann.blocking || annHash != appPrefs.acknowledgedAnnouncement())

        // Once booted (base URL applied) and logged in: pull fresh links + sync reminders.
        // This makes newly added/removed links appear on app open without tapping Refresh.
        LaunchedEffect(booted, state.isLoggedIn) {
            if (booted && state.isLoggedIn) {
                // The combined /sync: links, chat badge, partners badge and THIS user's config
                // (chat is per-user; the config fetched at launch is the global one).
                vm.syncAll(silent = true) { cfg -> applyConfig(cfg) }
                withContext(Dispatchers.IO) { ReminderSync.sync(applicationContext) }
            }
        }

        // Signed in (fresh sign-in or restored session): start browser sync. A different account than
        // the last one on this phone gets a clean Normal section (never merged with the previous
        // person's data).
        LaunchedEffect(booted, state.user?.id) {
            val id = state.user?.id
            if (booted && id != null) {
                if (BrowserSync.onSignedIn(id)) tabManager.resetNormalForNewAccount()
                BrowserSync.syncNow()
            }
        }

        // Also refresh links every time the app returns to the foreground (not just cold start),
        // so a link added while the app was backgrounded shows up when the user comes back.
        val lifecycleOwner = LocalLifecycleOwner.current
        val bootedNow by rememberUpdatedState(booted)
        val loggedInNow by rememberUpdatedState(state.isLoggedIn)
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START && bootedNow && loggedInNow) {
                    runFullRefresh(silent = true)
                }
                // Leaving the app: publish the open tabs to the user's other devices now.
                if (event == Lifecycle.Event.ON_STOP && loggedInNow) BrowserSync.requestSync(0)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // A reminder/push tap wants a link opened — do it once unlocked (this branch only
        // composes when unlocked) and logged in.
        LaunchedEffect(pendingLink.value, state.isLoggedIn, state.user) {
            val link = pendingLink.value
            if (link != null && link.normal) {
                // A broadcast link: a Normal tab, for everyone.
                pendingLink.value = null
                showProfile = false
                showMusic = false
                showPartners = false
                libraryPage = null
                tabManager.newTab(Section.NORMAL, link.url)
            } else if (link != null && state.isLoggedIn && state.user != null) {
                pendingLink.value = null
                // A link from a SyncUp push/reminder is a work link: open it as a Work tab (address hidden)
                // — the link's own tab when it's one of the user's links and already open.
                showProfile = false
                showMusic = false
                libraryPage = null
                val item = state.urls.firstOrNull { it.url == link.url }
                tabManager.openWorkLink(item?.title ?: link.title, link.url, item?.id, item?.notifyToken.orEmpty())
            }
        }

        // Login is optional: close the sign-in screen once it succeeds.
        LaunchedEffect(state.isLoggedIn) { if (state.isLoggedIn) showLogin = false }

        // Signed out (logout, account deleted, or the session ended server-side) → close every Work
        // tab and wipe the Work session. Normal browsing is kept.
        LaunchedEffect(state.isLoggedIn) { if (!state.isLoggedIn) tabManager.wipeWork() }

        // A failed links refresh (e.g. Work pull-to-refresh while offline) — tell the user once.
        LaunchedEffect(state.message) {
            state.message?.let {
                android.widget.Toast.makeText(this@MainActivity, it, android.widget.Toast.LENGTH_SHORT).show()
                vm.clearMessage()
            }
        }

        // A "partner added you" push was tapped: open the Partners page (once signed in).
        LaunchedEffect(openPartnersRequest.value, state.isLoggedIn) {
            if (openPartnersRequest.value && state.isLoggedIn) {
                openPartnersRequest.value = false
                showMusic = false
                showPartners = true
            }
        }

        // The media-notification tap wants the Music player (open to everyone, signed in or not).
        LaunchedEffect(openMusicRequest.value) {
            if (openMusicRequest.value) {
                openMusicRequest.value = false
                showMusic = true
            }
        }

        // System back: step Music -> Partners -> Settings instead of exiting the app.
        androidx.activity.compose.BackHandler(enabled = showMusic) { showMusic = false }
        androidx.activity.compose.BackHandler(enabled = showPartners && !showMusic) { showPartners = false }
        androidx.activity.compose.BackHandler(enabled = showProfile && !showMusic && !showPartners) { showProfile = false }
        androidx.activity.compose.BackHandler(enabled = libraryPage != null && !showProfile) { libraryPage = null }

        // A chat entry point (button / bubble / push tap) wants the chat screen: fetch the one-time
        // chat URL and open it in the WebView (no Home, no nested Chat action).
        // NOTE: do the suspend call FIRST and reset the flag AFTER — resetting first would change
        // this effect's key and cancel the coroutine mid-request.
        LaunchedEffect(openChatRequest.value, state.isLoggedIn, state.user) {
            if (openChatRequest.value && state.isLoggedIn && state.user != null) {
                val result = vm.chatSessionUrl()
                openChatRequest.value = false
                result.onSuccess { url ->
                    if (url.isNotBlank()) {
                        startActivity(
                            WebViewActivity.intent(
                                this@MainActivity, url, "Chat",
                            ),
                        )
                        vm.refreshChatUnread()
                    }
                }.onFailure {
                    android.widget.Toast.makeText(
                        this@MainActivity, it.message ?: "Couldn't open chat.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        // The app opens straight into the browser. Login is optional and only adds SyncUp features.
        val loggedIn = state.isLoggedIn && state.user != null

        // Radio is for signed-in users while the admin has it on. When that ends (sign-out, or the
        // admin turns it off), a station that's still playing stops. Songs carry on.
        val radioAvailable = loggedIn && radioEnabled
        var radioWasAvailable by remember { mutableStateOf(false) }
        LaunchedEffect(radioAvailable) {
            if (radioWasAvailable && !radioAvailable && player.isRadio) player.stop()
            radioWasAvailable = radioAvailable
        }
        val screen = when {
            !booted -> AppScreen.Splash
            forceUpdate -> AppScreen.ForceUpdate
            loggedIn && showAnnouncement -> AppScreen.Announcement
            showMusic -> AppScreen.Music
            showPartners && loggedIn -> AppScreen.Partners
            showProfile -> AppScreen.Profile
            libraryPage != null -> AppScreen.Library
            else -> AppScreen.Browser
        }

        CompositionLocalProvider(LocalPlayer provides player) {
            Crossfade(targetState = screen, animationSpec = tween(300), label = "screen") { target ->
                when (target) {
                    AppScreen.Splash -> SplashScreen()
                    AppScreen.ForceUpdate -> ForceUpdateScreen()
                    AppScreen.Announcement -> AnnouncementScreen(
                        title = ann?.title ?: "",
                        message = ann?.message ?: "",
                        blocking = ann?.blocking ?: false,
                        onDismiss = {
                            appPrefs.setAcknowledgedAnnouncement(annHash)
                            announcementDismissed = true
                        },
                    )
                    AppScreen.Profile -> run {
                        ProfileScreen(
                            user = state.user,
                            onSignIn = {
                                showProfile = false
                                showLogin = true
                            },
                            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            themeMode = themeMode,
                            onThemeChange = onThemeChange,
                            supportEmail = supportEmail,
                            supportPhone = supportPhone,
                            privacyPolicyUrl = privacyUrl,
                            chatEnabled = chatEnabled,
                            chatUnread = state.chatUnread,
                            onOpenChat = { openChatRequest.value = true },
                            onBack = { showProfile = false },
                            onLogout = {
                                showProfile = false
                                tabManager.wipeWork()
                                vm.logout()
                            },
                            onChangePassword = { current, new -> vm.changePassword(current, new) },
                            partnersWaiting = state.partnersWaiting,
                            showPartners = state.hasPartners,
                            onOpenPartners = { showPartners = true },
                            onUpdateProfile = { vm.updateProfile(it) },
                            onCheckUsername = { vm.checkUsername(it) },
                            onClearBrowsingData = { history, cookies, cache -> tabManager.clearBrowsingData(history, cookies, cache) },
                            onDeleteAccount = {
                                val result = vm.deleteAccount()
                                if (result.isSuccess) {
                                    showProfile = false
                                    tabManager.wipeWork()
                                }
                                result
                            },
                        )
                    }
                    AppScreen.Partners -> com.agani.syncup.ui.PartnersScreen(
                        load = { vm.partners() },
                        enable = { id, password -> vm.enablePartner(id, password) },
                        disable = { id -> vm.disablePartner(id) },
                        // Enabled/disabled partners' Work links appear/disappear; the badge updates.
                        onChanged = { runFullRefresh(silent = true) },
                        onBack = { showPartners = false },
                    )
                    AppScreen.Music -> {
                        RequestNotificationPermission() // media notification (status bar + lock screen)
                        MusicScreen(
                            radioAvailable = radioAvailable,
                            loadStations = { vm.radioChannels() },
                            onBack = { showMusic = false },
                        )
                    }
                    AppScreen.Browser -> {
                        // Asked once on first launch for every install (signed in or not) — SyncUp can notify anyone.
                        RequestNotificationPermission(onceOnly = true)
                        BrowserScreen(
                            tabs = tabManager,
                            web = webPlatform,
                            db = browserDb,
                            account = if (loggedIn) BrowserAccount(
                                user = state.user,
                                links = state.urls,
                                chatEnabled = chatEnabled,
                                chatUnread = state.chatUnread,
                                announcement = announcement,
                                refreshing = state.refreshing,
                                partnersWaiting = state.partnersWaiting,
                                hasPartners = state.hasPartners,
                            ) else BrowserAccount(
                                loginLoading = state.loading,
                                loginError = state.error,
                                supportEmail = supportEmail,
                                supportPhone = supportPhone,
                                signupEnabled = signupEnabled,
                                privacyUrl = privacyUrl,
                            ),
                            signInVisible = showLogin && !loggedIn,
                            actions = BrowserActions(
                                onSignIn = { showLogin = true },
                                onDismissSignIn = { showLogin = false },
                                onLogin = { mode, login, password -> vm.login(mode, login.trim(), password) },
                                onSignup = { name, email, phone, password -> vm.signup(name, email, phone, password) },
                                onClearAuthError = { vm.clearError() },
                                onSignOut = {
                                    tabManager.wipeWork()
                                    vm.logout()
                                },
                                onOpenPartners = { showPartners = true },
                                onOpenSettings = { showProfile = true },
                                onOpenChat = { openChatRequest.value = true },
                                onOpenMusic = { showMusic = true },
                                onOpenLibrary = { libraryPage = it },
                                onRefreshLinks = { runFullRefresh(silent = false) },
                            ),
                        )
                    }
                    AppScreen.Library -> LibraryScreen(
                        page = libraryPage ?: LibraryPage.HISTORY,
                        db = browserDb,
                        onOpen = { url ->
                            libraryPage = null
                            val t = tabManager.activeTab(Section.NORMAL)
                            if (tabManager.section == Section.NORMAL && t != null && t.isHome) tabManager.load(t, url)
                            else tabManager.newTab(Section.NORMAL, url)
                        },
                        onBack = { libraryPage = null },
                    )
                }
            }
        }

    }

    private fun promptBiometric(onSuccess: () -> Unit) {
        val canAuth = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK,
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock SyncUp")
            .setSubtitle("Use your fingerprint or face")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK,
            )
            .build()
        prompt.authenticate(info)
    }

    @Composable
    private fun RequestNotificationPermission(onceOnly: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) return@LaunchedEffect
            val prefs = getSharedPreferences("browser_settings", MODE_PRIVATE)
            if (onceOnly && prefs.getBoolean("notif_asked", false)) return@LaunchedEffect
            prefs.edit().putBoolean("notif_asked", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** Intent extra: when true, MainActivity opens the chat screen (used by chat push taps). */
        const val EXTRA_OPEN_CHAT = "extra_open_chat"

        /** Intent extra: when true, MainActivity opens the Music player (media-notification tap). */
        const val EXTRA_OPEN_MUSIC = "extra_open_music"

        /** Intent extra: id of a partner verification prompt to open (used by action push taps). */
        const val EXTRA_ACTION_ID = "extra_action_id"

        /** Intent extra / FCM data key: "normal" = open the push link in a Normal tab (broadcasts). */
        const val EXTRA_OPEN_IN = "open_in"

        /** Intent extra: open the Partners page (a partner added this user). */
        const val EXTRA_OPEN_PARTNERS = "extra_open_partners"
    }
}
