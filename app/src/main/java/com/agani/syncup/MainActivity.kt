package com.agani.syncup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

private enum class AppScreen { ForceUpdate, Announcement, Home, Browser, ToolsHome, You, Profile, SiteSettings, Music, Radio, Library, Partners, Notifications, Tools }

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

    // Set when the party's notification is tapped — open Radio → Party.
    private val openPartyRequest = mutableStateOf(false)

    // Set when the Walkie Talkie card is tapped — open Tools → Walkie Talkie.
    private val openWalkieRequest = mutableStateOf(false)

    // Watch Party: its card tapped (open the tab), or "Watch together" from the Video player (start with that video).
    private val openWatchPartyRequest = mutableStateOf(false)
    private val watchTogether = mutableStateOf<com.agani.syncup.watchparty.WatchSource?>(null)

    // Set when a download notification is tapped — open Downloads; or a link shared to SyncUp to download.
    private val openDownloadsRequest = mutableStateOf(false)
    private val downloadLink = mutableStateOf<String?>(null)

    // Id of a partner verification prompt to open (from an action push tap).
    private val pendingActionId = mutableStateOf<String?>(null)

    // Set when a "partner added you" push is tapped — open the Partners page.
    private val openPartnersRequest = mutableStateOf(false)

    // Set by "Share → SyncUp Share" from another app or a SyncUp Share notification tap — open SyncUp Share.
    private val openQuickShareRequest = mutableStateOf(false)
    /** A Calendar reminder was tapped: open that to-do ("" = just the Calendar). */
    private val openCalendarRequest = mutableStateOf<String?>(null)
    private val openForestRequest = mutableStateOf(false)
    /** A Wellbeing notification: (ToolsPage name, its argument — "eye", "morning", "checkin"…). */
    private val openToolRequest = mutableStateOf<Pair<String, String?>?>(null)

    // A launcher shortcut (long-press the app icon): "new_tab", "incognito".
    private val shortcutRequest = mutableStateOf<String?>(null)

    // A tapped push's Push Log id: marked read on the Notifications page once the server address is set.
    private val pushReadId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The websites that get a SyncUp page token, saved from the last sync — before any tab loads.
        com.agani.syncup.browser.PageTokens.init(applicationContext)
        com.agani.syncup.sso.SyncUpSignIn.init(applicationContext)
        // The server address known already, so the first screen's calls go to the right place.
        AppBootstrap.applyKnownBaseUrl()
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
        com.agani.syncup.downloads.Downloads.init(this) // carries on downloads the app was closed in the middle of
        com.agani.syncup.quickshare.QuickShare.init(this)
        com.agani.syncup.party.Party.init(this)
        com.agani.syncup.walkie.Walkie.init(this)
        com.agani.syncup.watchparty.WatchParty.init(this)
        com.agani.syncup.group.GroupLink.init(this)
        // Tools → Calendar: its to-dos and their reminders (re-set on every start).
        com.agani.syncup.calendar.CalendarStore.init(this)
        com.agani.syncup.calendar.CalendarReminders.reschedule(this)
        // Tools → Forest: its trees, any calm time still running, the gentle reminder.
        com.agani.syncup.forest.ForestStore.init(this)
        com.agani.syncup.forest.CalmSession.init(this)
        com.agani.syncup.forest.ForestReminder.schedule(this)
        // Tools → Wellbeing: Water, Steps, Breaks, Sleep, Habits, Mood (their reminders re-set on every start).
        com.agani.syncup.wellbeing.Wellbeing.init(this)
        com.agani.syncup.browser.SitePermissions.init(this)
        com.agani.syncup.browser.AdBlocker.init(this) // loads the filter lists; refreshes them weekly
        // The home page's shortcut catalogue (backend; refreshed once the server address is known) and the sites' icons.
        com.agani.syncup.browser.ShortcutCatalog.init(this)
        // The admin's links for everyone — a signed-out phone's SyncUp section (kept on the phone).
        com.agani.syncup.browser.GeneralLinks.init(this)
        com.agani.syncup.browser.SiteIcons.init(this)
        tabManager = TabManager(this, webPlatform, browserDb).also { it.restore() }
        // SyncUp Home: what the app opens to, its cards; someone with tabs already used SyncUp before Home.
        com.agani.syncup.home.HomePrefs.init(this, existingUser = Section.entries.any { tabManager.tabsIn(it).isNotEmpty() })
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
            // Light or Dark (pure black) — AppPrefs turns any older System / Black choice into one of them.
            val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.BLACK
            // Android 13+: the system's launch screen (blank, no icon) matches the theme (light by default).
            LaunchedEffect(dark) {
                if (Build.VERSION.SDK_INT >= 33) {
                    runCatching {
                        splashScreen.setSplashScreenTheme(if (dark) R.style.Theme_AGHub_SplashDark else android.content.res.Resources.ID_NULL)
                    }
                }
            }
            AgHubTheme(darkTheme = dark) {
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
                                com.agani.syncup.sync.ItemSync.signedOutAll()
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
        com.agani.syncup.forest.CalmSession.onAppStarted(this)
        // Re-lock only when the app was away longer than the grace (AppLock decides), never on
        // rotation or a quick round-trip to a picker/camera/external link.
        if (AppLock.pendingLock) {
            AppLock.pendingLock = false
            if (security.hasPin()) lockedState.value = true
        }
    }

    override fun onStop() {
        // Calm time pauses when SyncUp is left with the screen on (not when the screen goes off).
        com.agani.syncup.forest.CalmSession.onAppStopped(this)
        super.onStop()
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

    /** Walkie Talkie's volume-key talk (hold a volume key to talk) gets the volume keys first. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        com.agani.syncup.walkie.Walkie.onVolumeKey(event) || com.agani.syncup.group.GroupWalkie.onVolumeKey(event) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLink(intent)
    }

    /** Pull a link (from a reminder/push tap) off the launch intent so we can open it after unlock. */
    private fun readDeepLink(intent: Intent?) {
        // A push was tapped: it's read on the Notifications page now ("log_id" — our extra when the app
        // was open, or the push's own data when the system tray showed it).
        intent?.getStringExtra(EXTRA_LOG_ID)?.takeIf { it.isNotBlank() }?.let {
            pushReadId.value = it
            intent.removeExtra(EXTRA_LOG_ID)
        }
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
        intent?.getStringExtra(EXTRA_SHORTCUT)?.let {
            when (it) {
                "calendar" -> openCalendarRequest.value = ""
                "walkie" -> openWalkieRequest.value = true
                else -> shortcutRequest.value = it
            }
            intent.removeExtra(EXTRA_SHORTCUT)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_MUSIC, false) == true) {
            openMusicRequest.value = true
            intent.removeExtra(EXTRA_OPEN_MUSIC)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_PARTY, false) == true) {
            openPartyRequest.value = true
            intent.removeExtra(EXTRA_OPEN_PARTY)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_WALKIE, false) == true) {
            openWalkieRequest.value = true
            intent.removeExtra(EXTRA_OPEN_WALKIE)
        }
        intent?.getStringExtra(EXTRA_OPEN_CALENDAR)?.let {
            openCalendarRequest.value = it
            intent.removeExtra(EXTRA_OPEN_CALENDAR)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_FOREST, false) == true) {
            openForestRequest.value = true
            intent.removeExtra(EXTRA_OPEN_FOREST)
        }
        intent?.getStringExtra(EXTRA_OPEN_TOOL)?.let {
            openToolRequest.value = it to intent.getStringExtra(EXTRA_TOOL_ARG)
            intent.removeExtra(EXTRA_OPEN_TOOL)
            intent.removeExtra(EXTRA_TOOL_ARG)
        }
        if (intent?.getBooleanExtra(EXTRA_GROUP_SWITCH, false) == true) {
            // The group's notification: Switch (host).
            com.agani.syncup.group.GroupLink.requestSwitch()
            intent.removeExtra(EXTRA_GROUP_SWITCH)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_GROUP, false) == true) {
            // The group's notification: whatever the group is doing now.
            com.agani.syncup.group.GroupLink.requestOpen()
            intent.removeExtra(EXTRA_OPEN_GROUP)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_WATCH_PARTY, false) == true) {
            openWatchPartyRequest.value = true
            intent.removeExtra(EXTRA_OPEN_WATCH_PARTY)
        }
        intent?.getStringExtra(EXTRA_WATCH_TOGETHER_URI)?.takeIf { it.isNotBlank() }?.let { uri ->
            watchTogether.value = com.agani.syncup.watchparty.WatchSource(
                uri, intent.getStringExtra(EXTRA_WATCH_TOGETHER_TITLE).orEmpty(), intent.getLongExtra(EXTRA_WATCH_TOGETHER_POS, 0L),
            )
            openWatchPartyRequest.value = true
            intent.removeExtra(EXTRA_WATCH_TOGETHER_URI)
            intent.removeExtra(EXTRA_WATCH_TOGETHER_TITLE)
            intent.removeExtra(EXTRA_WATCH_TOGETHER_POS)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            openDownloadsRequest.value = true
            intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
        }
        intent?.getStringExtra(EXTRA_DOWNLOAD_LINK)?.let {
            downloadLink.value = it
            openDownloadsRequest.value = true
            intent.removeExtra(EXTRA_DOWNLOAD_LINK)
        }
        // SyncUp Share: files shared in from another app (in the ClipData, with read access), or a
        // tap on the SyncUp Share notification (no files).
        if (intent?.getBooleanExtra(EXTRA_QUICK_SHARE, false) == true) {
            val clip = intent.clipData
            val uris = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (uris.isNotEmpty()) com.agani.syncup.quickshare.QuickShare.sharedIn = uris
            // Text shared in: Send text, filled in.
            intent.getStringExtra(EXTRA_QUICK_SHARE_TEXT)?.let { com.agani.syncup.quickshare.QuickShare.sharedText = it }
            // The Quick Settings tile: go straight to Receive.
            if (intent.getBooleanExtra(EXTRA_QUICK_SHARE_RECEIVE, false)) com.agani.syncup.quickshare.QuickShare.receiveRequested = true
            openQuickShareRequest.value = true
            intent.removeExtra(EXTRA_QUICK_SHARE)
            intent.removeExtra(EXTRA_QUICK_SHARE_RECEIVE)
            intent.removeExtra(EXTRA_QUICK_SHARE_TEXT)
            intent.clipData = null
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

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun AppContent(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
        val vm: AuthViewModel = viewModel()
        // The Music player's state (songs + Radio), shared by every screen through LocalPlayer.
        val player = rememberPlayerHandle()
        // Tools → Sensors → Wave to control: a wave over the phone pauses / skips songs (when switched on).
        com.agani.syncup.sensors.WaveEffect { action ->
            if (player.item == null) return@WaveEffect
            when (action) {
                com.agani.syncup.sensors.WaveControl.Action.PLAY_PAUSE -> player.toggle()
                com.agani.syncup.sensors.WaveControl.Action.NEXT -> player.next()
                com.agani.syncup.sensors.WaveControl.Action.PREVIOUS -> player.previous()
                com.agani.syncup.sensors.WaveControl.Action.NOTHING -> Unit
            }
        }

        var booted by remember { mutableStateOf(false) }
        var forceUpdate by remember { mutableStateOf(false) }
        var showProfile by remember { mutableStateOf(false) }
        var showSiteSettings by remember { mutableStateOf(false) }
        var showMusic by remember { mutableStateOf(false) }
        var showRadio by remember { mutableStateOf(false) }
        // Radio's tab: Party (true) or Live stations.
        var radioOnParty by remember { mutableStateOf(true) }
        var showLogin by remember { mutableStateOf(false) }
        var showPartners by remember { mutableStateOf(false) }
        var showNotifications by remember { mutableStateOf(false) }
        var signupEnabled by remember { mutableStateOf(false) }
        var libraryPage by remember { mutableStateOf<LibraryPage?>(null) }
        var toolsPage by remember { mutableStateOf<com.agani.syncup.tools.ToolsPage?>(null) }
        // SyncUp Home: Home · Browse · Tools · You (tools, settings and the rest open over the tab they came from).
        var mainTab by rememberSaveable { mutableStateOf(com.agani.syncup.home.HomePrefs.startTab) }
        var customiseHome by remember { mutableStateOf(false) }
        val authForm = remember { com.agani.syncup.browser.AuthFormState() }
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
            com.agani.syncup.sync.ItemSync.syncAllNow()                                      // Calendar, Forest
        }

        // The app shows its first screen at once (no splash); this sets up the server side behind it.
        LaunchedEffect(Unit) {
            // The fresh base URL from Remote Config — bounded so startup never hangs.
            withTimeoutOrNull(2500) { withContext(Dispatchers.IO) { AppBootstrap.applyBaseUrl() } }
            // A debug build made for a test server says so — on any other phone it can't reach anything.
            if (BuildConfig.DEBUG_API_BASE_URL.isNotBlank()) {
                android.widget.Toast.makeText(
                    this@MainActivity, "Test build — server: ${BuildConfig.DEBUG_API_BASE_URL}", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            // Every install checks in (signed in or not) so SyncUp can reach it.
            DeviceRegistrar.hello(applicationContext)
            booted = true
            // Server config (announcement + force-update + support contacts) — non-blocking.
            val cfg = withContext(Dispatchers.IO) { AppBootstrap.fetchConfig() }
            if (cfg != null) applyConfig(cfg)
            // The home page's shortcuts from the backend (the server address is known now).
            com.agani.syncup.browser.ShortcutCatalog.refresh(force = true)
            // The links for everyone (signed in they come with /sync; kept fresh for after sign-out).
            com.agani.syncup.browser.GeneralLinks.refresh()
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
                // Calendar and Forest sync on their own (same account, same switches).
                com.agani.syncup.sync.ItemSync.signedInAll(id)
                com.agani.syncup.sync.ItemSync.syncAllNow()
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
                // Signed out: the Notifications bell and the links for everyone (signed in, the /sync above brings both).
                if (event == Lifecycle.Event.ON_START && bootedNow && !loggedInNow) {
                    com.agani.syncup.notifications.Inbox.refreshCount(this@MainActivity)
                    lifecycleScope.launch { com.agani.syncup.browser.GeneralLinks.refresh() }
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
            if (link != null && (link.normal || !state.isLoggedIn)) {
                // A broadcast link: a Normal tab, for everyone. Signed out, any link does (a website's
                // push to this phone) — SyncUp tabs need sign-in. (The session is restored before this runs.)
                pendingLink.value = null
                showProfile = false
                showMusic = false
                showRadio = false
                showPartners = false
                showNotifications = false
                libraryPage = null
                toolsPage = null
                mainTab = com.agani.syncup.home.MainTab.BROWSE
                tabManager.newTab(Section.NORMAL, link.url)
            } else if (link != null && state.isLoggedIn && state.user != null) {
                pendingLink.value = null
                // A link from a SyncUp push/reminder is a work link: open it as a Work tab (address hidden)
                // — the link's own tab when it's one of the user's links and already open.
                showProfile = false
                showMusic = false
                showRadio = false
                showNotifications = false
                libraryPage = null
                toolsPage = null
                mainTab = com.agani.syncup.home.MainTab.BROWSE
                val item = state.urls.firstOrNull { it.url == link.url }
                tabManager.openWorkLink(item?.title ?: link.title, link.url, item?.id)
            }
        }

        // A tapped push is read now — after boot, so it goes to the server Remote Config chose.
        LaunchedEffect(booted, pushReadId.value) {
            val id = pushReadId.value
            if (booted && id != null) {
                pushReadId.value = null
                com.agani.syncup.notifications.Inbox.markReadFromPush(this@MainActivity, id)
            }
        }

        // Login is optional: close the sign-in screen once it succeeds.
        LaunchedEffect(state.isLoggedIn) { if (state.isLoggedIn) showLogin = false }

        // Signing out (logout, account deleted, or the session ended server-side) → close every Work
        // tab and wipe the Work session. Normal browsing is kept. Only on the change itself: a
        // signed-out phone's SyncUp logins (links for everyone) stay across launches, and its SyncUp
        // tabs stay open at sign-in.
        var wasLoggedIn by remember { mutableStateOf(state.isLoggedIn) }
        LaunchedEffect(state.isLoggedIn) {
            if (wasLoggedIn && !state.isLoggedIn) tabManager.wipeWork()
            wasLoggedIn = state.isLoggedIn
        }

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
                showRadio = false
                showPartners = true
            }
        }

        // The media-notification tap wants whatever's playing — a station opens Radio, a song opens Music.
        LaunchedEffect(openMusicRequest.value) {
            if (openMusicRequest.value) {
                openMusicRequest.value = false
                if (player.isRadio) {
                    radioOnParty = false
                    showRadio = true
                } else {
                    showMusic = true
                }
            }
        }

        // The party's notification: Radio → Party.
        LaunchedEffect(openPartyRequest.value) {
            if (openPartyRequest.value) {
                openPartyRequest.value = false
                showMusic = false
                showPartners = false
                showNotifications = false
                showProfile = false
                radioOnParty = true
                showRadio = true
            }
        }

        // Watch Party (its card, or "Watch together" in the Video player): Tools → Video player → Watch Party.
        var videoPartyTab by remember { mutableStateOf(false) }
        LaunchedEffect(openWatchPartyRequest.value) {
            if (openWatchPartyRequest.value) {
                openWatchPartyRequest.value = false
                showMusic = false
                showRadio = false
                showPartners = false
                showNotifications = false
                showProfile = false
                libraryPage = null
                videoPartyTab = true
                toolsPage = com.agani.syncup.tools.ToolsPage.VIDEOS
            }
        }

        // The group: every phone goes where the group is (just joined, the host switched, or "Open").
        val groupGen = com.agani.syncup.group.GroupLink.modeGen
        val groupOpen = com.agani.syncup.group.GroupLink.openRequest
        LaunchedEffect(groupGen, groupOpen) {
            if (!com.agani.syncup.group.GroupLink.inGroup || groupGen == 0) return@LaunchedEffect
            showMusic = false
            showPartners = false
            showNotifications = false
            showProfile = false
            libraryPage = null
            when (com.agani.syncup.group.GroupLink.mode) {
                com.agani.syncup.group.GroupMode.MUSIC -> {
                    radioOnParty = true
                    showRadio = true
                }
                com.agani.syncup.group.GroupMode.WATCH -> {
                    showRadio = false
                    // Straight into the player (the Watch Party tab opens it).
                    if (com.agani.syncup.watchparty.WatchParty.active) com.agani.syncup.watchparty.WatchParty.wantsPlayer = true
                    videoPartyTab = true
                    toolsPage = com.agani.syncup.tools.ToolsPage.VIDEOS
                }
                else -> {
                    showRadio = false
                    toolsPage = com.agani.syncup.tools.ToolsPage.GROUP
                }
            }
        }

        // The Walkie Talkie card: Tools → Walkie Talkie.
        LaunchedEffect(openWalkieRequest.value) {
            if (openWalkieRequest.value) {
                openWalkieRequest.value = false
                showMusic = false
                showRadio = false
                showPartners = false
                showNotifications = false
                showProfile = false
                libraryPage = null
                toolsPage = com.agani.syncup.tools.ToolsPage.WALKIE
            }
        }

        // A download notification / a link shared to SyncUp: the Download Manager.
        LaunchedEffect(openDownloadsRequest.value) {
            if (openDownloadsRequest.value) {
                openDownloadsRequest.value = false
                showMusic = false
                showRadio = false
                showPartners = false
                showNotifications = false
                showProfile = false
                libraryPage = LibraryPage.DOWNLOADS
            }
        }

        // A Calendar reminder: Tools → Calendar, at that to-do.
        var calendarTask by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(openCalendarRequest.value) {
            val id = openCalendarRequest.value ?: return@LaunchedEffect
            openCalendarRequest.value = null
            showMusic = false
            showRadio = false
            showPartners = false
            showNotifications = false
            showProfile = false
            libraryPage = null
            calendarTask = id.ifBlank { null }
            toolsPage = com.agani.syncup.tools.ToolsPage.CALENDAR
        }

        // Forest's notifications (calm time, its end, the gentle reminder): Tools → Forest.
        LaunchedEffect(openForestRequest.value) {
            if (openForestRequest.value) {
                openForestRequest.value = false
                showMusic = false
                showRadio = false
                showPartners = false
                showNotifications = false
                showProfile = false
                libraryPage = null
                toolsPage = com.agani.syncup.tools.ToolsPage.FOREST
            }
        }

        // A Wellbeing notification (Water, Steps, Breaks, Sleep, Habits, Mood): that tool, with its argument.
        var toolArg by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(openToolRequest.value) {
            val (name, arg) = openToolRequest.value ?: return@LaunchedEffect
            openToolRequest.value = null
            val page = runCatching { com.agani.syncup.tools.ToolsPage.valueOf(name) }.getOrNull() ?: return@LaunchedEffect
            showMusic = false
            showRadio = false
            showPartners = false
            showNotifications = false
            showProfile = false
            libraryPage = null
            toolArg = arg
            toolsPage = page
        }

        // "Share → SyncUp Share" from another app, or the SyncUp Share notification: Tools → SyncUp Share.
        LaunchedEffect(openQuickShareRequest.value) {
            if (openQuickShareRequest.value) {
                openQuickShareRequest.value = false
                showMusic = false
                showRadio = false
                showPartners = false
                showNotifications = false
                showProfile = false
                libraryPage = null
                toolsPage = com.agani.syncup.tools.ToolsPage.QUICK_SHARE
            }
        }

        // Long-press shortcuts: a new tab ready to type in, or a new Incognito tab.
        LaunchedEffect(shortcutRequest.value) {
            val what = shortcutRequest.value ?: return@LaunchedEffect
            shortcutRequest.value = null
            showMusic = false
            showRadio = false
            showPartners = false
            showNotifications = false
            showProfile = false
            libraryPage = null
            toolsPage = null
            mainTab = com.agani.syncup.home.MainTab.BROWSE
            if (what == "incognito") tabManager.newTab(Section.INCOGNITO) else tabManager.focusAddressBar = true
        }
        // "Tools" asked for from anywhere is the Tools tab.
        LaunchedEffect(toolsPage) {
            if (toolsPage == com.agani.syncup.tools.ToolsPage.HOME) {
                toolsPage = null
                mainTab = com.agani.syncup.home.MainTab.TOOLS
            }
        }

        // System back: step Music/Radio -> Partners -> Settings instead of exiting the app.
        androidx.activity.compose.BackHandler(enabled = showMusic) { showMusic = false }
        androidx.activity.compose.BackHandler(enabled = showRadio) { showRadio = false }
        androidx.activity.compose.BackHandler(enabled = showPartners && !showMusic && !showRadio) { showPartners = false }
        androidx.activity.compose.BackHandler(enabled = showNotifications && !showMusic && !showRadio && !showPartners) { showNotifications = false }
        androidx.activity.compose.BackHandler(enabled = showProfile && !showMusic && !showRadio && !showPartners && !showSiteSettings) { showProfile = false }
        androidx.activity.compose.BackHandler(enabled = showSiteSettings && !showMusic && !showRadio && !showPartners) { showSiteSettings = false }
        androidx.activity.compose.BackHandler(enabled = libraryPage != null && !showProfile) { libraryPage = null }
        // Tools: a tool goes back to the Tools page, the Tools page back to the browser.
        androidx.activity.compose.BackHandler(enabled = toolsPage != null && !showMusic && !showRadio && libraryPage == null && !showProfile) {
            toolsPage = null
        }

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
            forceUpdate -> AppScreen.ForceUpdate
            loggedIn && showAnnouncement -> AppScreen.Announcement
            showMusic -> AppScreen.Music
            showRadio -> AppScreen.Radio
            showPartners && loggedIn -> AppScreen.Partners
            showNotifications -> AppScreen.Notifications
            showSiteSettings -> AppScreen.SiteSettings
            showProfile -> AppScreen.Profile
            libraryPage != null -> AppScreen.Library
            toolsPage != null && toolsPage != com.agani.syncup.tools.ToolsPage.HOME -> AppScreen.Tools
            else -> when (mainTab) {
                com.agani.syncup.home.MainTab.HOME -> AppScreen.Home
                com.agani.syncup.home.MainTab.BROWSE -> AppScreen.Browser
                com.agani.syncup.home.MainTab.TOOLS -> AppScreen.ToolsHome
                com.agani.syncup.home.MainTab.YOU -> AppScreen.You
            }
        }

        // Back on Tools or You goes to the start tab; on Home it leaves the app (tabs and all kept).
        androidx.activity.compose.BackHandler(enabled = screen == AppScreen.ToolsHome || screen == AppScreen.You) {
            mainTab = com.agani.syncup.home.HomePrefs.startTab
        }
        androidx.activity.compose.BackHandler(enabled = screen == AppScreen.Home) { moveTaskToBack(true) }

        // What Home's cards and tiles open.
        fun openHomeTool(t: com.agani.syncup.home.HomeTool) {
            when (t) {
                com.agani.syncup.home.HomeTool.CALENDAR -> toolsPage = com.agani.syncup.tools.ToolsPage.CALENDAR
                com.agani.syncup.home.HomeTool.FOREST -> toolsPage = com.agani.syncup.tools.ToolsPage.FOREST
                com.agani.syncup.home.HomeTool.SHARE -> toolsPage = com.agani.syncup.tools.ToolsPage.QUICK_SHARE
                com.agani.syncup.home.HomeTool.WALKIE -> toolsPage = com.agani.syncup.tools.ToolsPage.WALKIE
                com.agani.syncup.home.HomeTool.SENSORS -> toolsPage = com.agani.syncup.tools.ToolsPage.SENSORS
                com.agani.syncup.home.HomeTool.VIDEO -> toolsPage = com.agani.syncup.tools.ToolsPage.VIDEOS
                com.agani.syncup.home.HomeTool.WATCH_PARTY -> {
                    videoPartyTab = true
                    toolsPage = com.agani.syncup.tools.ToolsPage.VIDEOS
                }
                com.agani.syncup.home.HomeTool.MUSIC -> showMusic = true
                com.agani.syncup.home.HomeTool.RADIO -> {
                    radioOnParty = com.agani.syncup.party.Party.active || !(player.isRadio && radioAvailable)
                    showRadio = true
                }
                com.agani.syncup.home.HomeTool.PARTY -> {
                    radioOnParty = true
                    showRadio = true
                }
                com.agani.syncup.home.HomeTool.GROUP -> com.agani.syncup.group.GroupLink.requestOpen()
                com.agani.syncup.home.HomeTool.WATER -> toolsPage = com.agani.syncup.tools.ToolsPage.WATER
                com.agani.syncup.home.HomeTool.STEPS -> toolsPage = com.agani.syncup.tools.ToolsPage.STEPS
                com.agani.syncup.home.HomeTool.HABITS -> toolsPage = com.agani.syncup.tools.ToolsPage.HABITS
                com.agani.syncup.home.HomeTool.SLEEP -> toolsPage = com.agani.syncup.tools.ToolsPage.SLEEP
                com.agani.syncup.home.HomeTool.BREAKS -> toolsPage = com.agani.syncup.tools.ToolsPage.BREAKS
                com.agani.syncup.home.HomeTool.MOOD -> toolsPage = com.agani.syncup.tools.ToolsPage.MOOD
            }
        }

        CompositionLocalProvider(LocalPlayer provides player) {
            Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { target -> // v7: quick and light
                when (target) {
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
                            onOpenSiteSettings = { showSiteSettings = true },
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
                    AppScreen.SiteSettings -> com.agani.syncup.browser.SiteSettingsScreen(onBack = { showSiteSettings = false })
                    AppScreen.Notifications -> com.agani.syncup.notifications.NotificationsScreen(
                        onBack = { showNotifications = false },
                        onOpen = { item ->
                            val url = item.linkUrl
                            when {
                                // "A partner added you": the Partners page (Back returns here).
                                item.type == "partners" && loggedIn -> showPartners = true
                                // What the push opens: a broadcast link in a Normal tab; a link pushed to
                                // this user in a SyncUp tab, as when the push itself is tapped.
                                !url.isNullOrBlank() -> pendingLink.value =
                                    PendingLink(url, item.linkTitle.orEmpty(), normal = item.openIn == "normal" || !loggedIn)
                            }
                        },
                    )
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
                            onBack = { showMusic = false },
                            // Music → Party: on to Radio → Party (the host screen).
                            onOpenParty = {
                                showMusic = false
                                radioOnParty = true
                                showRadio = true
                            },
                        )
                    }
                    AppScreen.Radio -> {
                        RequestNotificationPermission() // media notification (status bar + lock screen)
                        com.agani.syncup.music.RadioScreen(
                            liveStations = radioEnabled,
                            signedIn = loggedIn,
                            partyTab = radioOnParty,
                            onTab = { radioOnParty = it },
                            loadStations = { vm.radioChannels() },
                            onSignIn = {
                                showRadio = false
                                toolsPage = null
                                showLogin = true
                            },
                            onBack = { showRadio = false },
                        )
                    }
                    AppScreen.Home -> {
                        // Asked once on first launch for every install (signed in or not) — SyncUp can notify anyone.
                        RequestNotificationPermission(onceOnly = true)
                        OfferSyncUpShareTile()
                        com.agani.syncup.home.TabFrame(com.agani.syncup.home.MainTab.HOME, onSelect = { mainTab = it }) {
                            com.agani.syncup.home.HomeScreen(
                                signedIn = loggedIn,
                                userInitial = state.user?.name?.trim()?.take(1)?.uppercase(),
                                // v7: "Good afternoon," above the first name when signed in ("SyncUp" signed out).
                                userName = state.user?.name.takeIf { loggedIn },
                                links = if (loggedIn) state.urls else com.agani.syncup.browser.GeneralLinks.links,
                                onSearch = {
                                    tabManager.focusAddressBar = true
                                    mainTab = com.agani.syncup.home.MainTab.BROWSE
                                },
                                onNotifications = { showNotifications = true },
                                onAccount = { mainTab = com.agani.syncup.home.MainTab.YOU },
                                onTool = { openHomeTool(it) },
                                onAllTools = { mainTab = com.agani.syncup.home.MainTab.TOOLS },
                                onOpenLink = { link ->
                                    tabManager.openWorkLink(link.title, link.url, link.id)
                                    mainTab = com.agani.syncup.home.MainTab.BROWSE
                                },
                                onAllLinks = {
                                    tabManager.switchTo(Section.WORK)
                                    mainTab = com.agani.syncup.home.MainTab.BROWSE
                                },
                                onOpenBrowse = { mainTab = com.agani.syncup.home.MainTab.BROWSE },
                            )
                        }
                    }
                    AppScreen.ToolsHome -> com.agani.syncup.home.TabFrame(com.agani.syncup.home.MainTab.TOOLS, onSelect = { mainTab = it }) {
                        com.agani.syncup.tools.ToolsScreen(
                            radioAvailable = radioAvailable,
                            onMusic = { showMusic = true },
                            onVideos = { toolsPage = com.agani.syncup.tools.ToolsPage.VIDEOS },
                            onRadio = { openHomeTool(com.agani.syncup.home.HomeTool.RADIO) },
                            onChannels = { toolsPage = com.agani.syncup.tools.ToolsPage.CHANNELS },
                            onQuickShare = { toolsPage = com.agani.syncup.tools.ToolsPage.QUICK_SHARE },
                            onWalkie = { toolsPage = com.agani.syncup.tools.ToolsPage.WALKIE },
                            onSensors = { toolsPage = com.agani.syncup.tools.ToolsPage.SENSORS },
                            onCalendar = { toolsPage = com.agani.syncup.tools.ToolsPage.CALENDAR },
                            onForest = { toolsPage = com.agani.syncup.tools.ToolsPage.FOREST },
                            onGroup = { com.agani.syncup.group.GroupLink.requestOpen() },
                            onTool = { toolsPage = it },
                            onBack = { mainTab = com.agani.syncup.home.HomePrefs.startTab },
                            inTabs = true,
                        )
                    }
                    AppScreen.You -> com.agani.syncup.home.TabFrame(com.agani.syncup.home.MainTab.YOU, onSelect = { mainTab = it }) {
                        com.agani.syncup.home.YouScreen(
                            user = state.user.takeIf { loggedIn },
                            chatEnabled = chatEnabled,
                            chatUnread = state.chatUnread,
                            hasPartners = state.hasPartners,
                            partnersWaiting = state.partnersWaiting,
                            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            onSignIn = { showLogin = true },
                            onNotifications = { showNotifications = true },
                            onChat = { openChatRequest.value = true },
                            onPartners = { showPartners = true },
                            onLibrary = { libraryPage = it },
                            onCustomise = { customiseHome = true },
                            onSettings = { showProfile = true },
                        )
                    }
                    AppScreen.Browser -> {
                        // Asked once on first launch for every install (signed in or not) — SyncUp can notify anyone.
                        RequestNotificationPermission(onceOnly = true)
                        OfferSyncUpShareTile()
                        BrowserScreen(
                            tabs = tabManager,
                            web = webPlatform,
                            db = browserDb,
                            account = if (loggedIn) BrowserAccount(
                                user = state.user,
                                links = state.urls,
                                announcement = announcement,
                                refreshing = state.refreshing,
                                partnersWaiting = state.partnersWaiting,
                                hasPartners = state.hasPartners,
                                chatEnabled = chatEnabled,
                                chatUnread = state.chatUnread,
                            ) else BrowserAccount(
                                // Signed out, SyncUp shows the admin's links for everyone.
                                links = com.agani.syncup.browser.GeneralLinks.links,
                                refreshing = com.agani.syncup.browser.GeneralLinks.refreshing,
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
                                onOpenChat = { openChatRequest.value = true },
                                onOpenNotifications = { showNotifications = true },
                                onOpenSettings = { showProfile = true },
                                onOpenMusic = {
                                    if (player.isRadio) {
                                        radioOnParty = false
                                        showRadio = true
                                    } else {
                                        showMusic = true
                                    }
                                },
                                onOpenTools = { mainTab = com.agani.syncup.home.MainTab.TOOLS },
                                onHome = { mainTab = com.agani.syncup.home.MainTab.HOME },
                                onOpenYou = { mainTab = com.agani.syncup.home.MainTab.YOU },
                                onOpenLibrary = { libraryPage = it },
                                onRefreshLinks = {
                                    if (loggedIn) runFullRefresh(silent = false)
                                    else lifecycleScope.launch { com.agani.syncup.browser.GeneralLinks.refresh(spinner = true) }
                                },
                                backToHome = com.agani.syncup.home.HomePrefs.startOnHome,
                            ),
                        )
                    }
                    AppScreen.Tools -> when (toolsPage) {
                        com.agani.syncup.tools.ToolsPage.VIDEOS -> com.agani.syncup.video.VideoLibraryScreen(
                            onBack = {
                                videoPartyTab = false
                                toolsPage = null
                            },
                            startWith = watchTogether.value,
                            onStartWithUsed = { watchTogether.value = null },
                            partyTab = videoPartyTab,
                        )
                        com.agani.syncup.tools.ToolsPage.CHANNELS -> com.agani.syncup.video.ChannelsHomeScreen(
                            onBack = { toolsPage = null },
                        )
                        com.agani.syncup.tools.ToolsPage.GROUP -> com.agani.syncup.group.GroupModeScreen(
                            onBack = { toolsPage = null },
                        )
                        com.agani.syncup.tools.ToolsPage.WALKIE -> com.agani.syncup.walkie.WalkieScreen(
                            onBack = { toolsPage = null },
                        )
                        com.agani.syncup.tools.ToolsPage.SENSORS -> com.agani.syncup.sensors.SensorsScreen(
                            onBack = { toolsPage = null },
                        )
                        com.agani.syncup.tools.ToolsPage.CALENDAR -> com.agani.syncup.calendar.CalendarScreen(
                            signedIn = loggedIn,
                            onBack = { toolsPage = null },
                            onSignIn = {
                                toolsPage = null
                                showLogin = true
                            },
                            openTaskId = calendarTask,
                            onOpenTaskUsed = { calendarTask = null },
                        )
                        com.agani.syncup.tools.ToolsPage.FOREST -> com.agani.syncup.forest.ForestScreen(
                            signedIn = loggedIn,
                            onBack = { toolsPage = null },
                        )
                        com.agani.syncup.tools.ToolsPage.WATER -> com.agani.syncup.wellbeing.WaterScreen(onBack = { toolsPage = null })
                        com.agani.syncup.tools.ToolsPage.STEPS -> com.agani.syncup.wellbeing.StepsScreen(onBack = { toolsPage = null })
                        com.agani.syncup.tools.ToolsPage.HABITS -> com.agani.syncup.wellbeing.HabitsScreen(onBack = { toolsPage = null })
                        com.agani.syncup.tools.ToolsPage.BREAKS -> com.agani.syncup.wellbeing.BreaksScreen(
                            onBack = { toolsPage = null }, start = toolArg, onStartUsed = { toolArg = null },
                        )
                        com.agani.syncup.tools.ToolsPage.SLEEP -> com.agani.syncup.wellbeing.SleepScreen(
                            onBack = { toolsPage = null }, start = toolArg, onStartUsed = { toolArg = null },
                        )
                        com.agani.syncup.tools.ToolsPage.MOOD -> com.agani.syncup.wellbeing.MoodScreen(
                            onBack = { toolsPage = null }, start = toolArg, onStartUsed = { toolArg = null },
                        )
                        com.agani.syncup.tools.ToolsPage.QUICK_SHARE -> com.agani.syncup.quickshare.QuickShareScreen(
                            onBack = { toolsPage = null },
                            onOpenDownloads = { libraryPage = LibraryPage.DOWNLOADS },
                            onOpenLink = { url ->
                                // A link that arrived as text: a new Normal tab.
                                toolsPage = null
                                mainTab = com.agani.syncup.home.MainTab.BROWSE
                                tabManager.newTab(Section.NORMAL, url)
                            },
                        )
                        else -> com.agani.syncup.tools.ToolsScreen(
                            radioAvailable = radioAvailable,
                            onMusic = { showMusic = true },
                            onVideos = { toolsPage = com.agani.syncup.tools.ToolsPage.VIDEOS },
                            onRadio = {
                                // Straight to what's going on: the party, a station playing, else Party.
                                radioOnParty = com.agani.syncup.party.Party.active || !(player.isRadio && radioAvailable)
                                showRadio = true
                            },
                            onChannels = { toolsPage = com.agani.syncup.tools.ToolsPage.CHANNELS },
                            onQuickShare = { toolsPage = com.agani.syncup.tools.ToolsPage.QUICK_SHARE },
                            onWalkie = { toolsPage = com.agani.syncup.tools.ToolsPage.WALKIE },
                            onSensors = { toolsPage = com.agani.syncup.tools.ToolsPage.SENSORS },
                            onCalendar = { toolsPage = com.agani.syncup.tools.ToolsPage.CALENDAR },
                            onForest = { toolsPage = com.agani.syncup.tools.ToolsPage.FOREST },
                            onGroup = { com.agani.syncup.group.GroupLink.requestOpen() },
                            onTool = { toolsPage = it },
                            onBack = { toolsPage = null },
                        )
                    }
                    AppScreen.Library -> if (libraryPage == LibraryPage.DOWNLOADS) {
                        com.agani.syncup.downloads.DownloadsScreen(
                            link = downloadLink.value,
                            onLinkUsed = { downloadLink.value = null },
                            onBack = { libraryPage = null },
                        )
                    } else LibraryScreen(
                        page = libraryPage ?: LibraryPage.HISTORY,
                        db = browserDb,
                        onOpen = { url ->
                            libraryPage = null
                            mainTab = com.agani.syncup.home.MainTab.BROWSE
                            val t = tabManager.activeTab(Section.NORMAL)
                            if (tabManager.section == Section.NORMAL && t != null && t.isHome) tabManager.load(t, url)
                            else tabManager.newTab(Section.NORMAL, url)
                        },
                        onBack = { libraryPage = null },
                    )
                }
            }
            // The group, whatever screen is open: join requests, "switched to…", ended, notes.
            com.agani.syncup.group.GroupOverlays()
            // "Sign in to SyncHub" — SyncUp's own sheet when a SyncHub page asks the first time.
            com.agani.syncup.sso.SignInConsentHost(state.user.takeIf { loggedIn })
            // Sign-in from Home, You or a tool (the browser shows its own sheet).
            if (showLogin && !loggedIn && booted && screen != AppScreen.Browser) {
                androidx.compose.material3.ModalBottomSheet(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLowest,
                    onDismissRequest = {
                        vm.clearError()
                        showLogin = false
                    },
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    com.agani.syncup.browser.SignInSheet(
                        form = authForm,
                        loading = state.loading,
                        error = state.error,
                        supportEmail = supportEmail,
                        supportPhone = supportPhone,
                        signupEnabled = signupEnabled,
                        privacyUrl = privacyUrl,
                        onLogin = { mode, login, password -> vm.login(mode, login.trim(), password) },
                        onSignup = { name, email, phone, password -> vm.signup(name, email, phone, password) },
                        onClearError = { vm.clearError() },
                    )
                }
            }
            if (customiseHome) com.agani.syncup.home.CustomiseSheet(onDismiss = { customiseHome = false })
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

    /**
     * Android never lets an app put its tile into Quick Settings by itself, so on the first launch
     * (after install, or after the update that brought the tile) we show Android's own one-tap
     * "Add tile?" popup — once. Waits until any other system question (notifications) is answered.
     */
    @Composable
    private fun OfferSyncUpShareTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        LaunchedEffect(Unit) {
            val qs = com.agani.syncup.quickshare.QuickShare
            qs.init(this@MainActivity)
            val tile = com.agani.syncup.quickshare.SyncUpShareTileService
            if (qs.tileAdded || qs.tileOffered(this@MainActivity) || tile.isTurnedOff(this@MainActivity)) return@LaunchedEffect
            delay(1500)
            // A system dialog (e.g. the notification question) takes our window's focus — wait it out.
            withTimeoutOrNull(60_000) { while (!hasWindowFocus()) delay(300) } ?: return@LaunchedEffect
            delay(700)
            if (!hasWindowFocus()) return@LaunchedEffect
            qs.markTileOffered(this@MainActivity)
            tile.askToAdd(this@MainActivity) {}
        }
    }

    companion object {
        /** Intent extra: when true, MainActivity opens the chat screen (used by chat push taps). */
        const val EXTRA_OPEN_CHAT = "extra_open_chat"

        /** Intent extra: when true, MainActivity opens the Music player (media-notification tap). */
        const val EXTRA_OPEN_MUSIC = "extra_open_music"

        /** Intent extra: when true, MainActivity opens Tools → Walkie Talkie (its notification tap). */
        const val EXTRA_OPEN_WALKIE = "extra_open_walkie"

        /** A Calendar reminder's tap: the to-do's id. */
        const val EXTRA_OPEN_CALENDAR = "extra_open_calendar"
        const val EXTRA_OPEN_FOREST = "extra_open_forest"
        /** Open a tool from its notification: a ToolsPage name, plus [EXTRA_TOOL_ARG] for it. */
        const val EXTRA_OPEN_TOOL = "extra_open_tool"
        const val EXTRA_TOOL_ARG = "extra_tool_arg"
        const val EXTRA_SHORTCUT = "extra_shortcut"

        /** Intent extra: when true, MainActivity opens what the group is doing now (the group's notification). */
        const val EXTRA_OPEN_GROUP = "extra_open_group"

        /** Intent extra: when true, MainActivity opens "Switch the group to…" (the group notification's Switch, host). */
        const val EXTRA_GROUP_SWITCH = "extra_group_switch"

        /** Intent extra: when true, MainActivity opens Tools → Video player → Watch Party (its card). */
        const val EXTRA_OPEN_WATCH_PARTY = "extra_open_watch_party"

        /** Intent extras: "Watch together" in the Video player — start a Watch Party with this video from here. */
        const val EXTRA_WATCH_TOGETHER_URI = "extra_watch_together_uri"
        const val EXTRA_WATCH_TOGETHER_TITLE = "extra_watch_together_title"
        const val EXTRA_WATCH_TOGETHER_POS = "extra_watch_together_pos"

        /** Intent extra: when true, MainActivity opens Radio → Party (the party's notification tap). */
        const val EXTRA_OPEN_PARTY = "extra_open_party"

        /** Intent extra: when true, MainActivity opens the Download Manager (download notification tap). */
        const val EXTRA_OPEN_DOWNLOADS = "extra_open_downloads"

        /** Intent extra: a link shared to SyncUp to download (opens Downloads → Add link with it). */
        const val EXTRA_DOWNLOAD_LINK = "extra_download_link"

        /** Intent extra: id of a partner verification prompt to open (used by action push taps). */
        const val EXTRA_ACTION_ID = "extra_action_id"

        /** Intent extra / FCM data key: "normal" = open the push link in a Normal tab (broadcasts). */
        const val EXTRA_OPEN_IN = "open_in"

        /** Intent extra: open the Partners page (a partner added this user). */
        const val EXTRA_OPEN_PARTNERS = "extra_open_partners"

        /** Intent extra: open Tools → SyncUp Share (files to send, if any, ride in the ClipData). */
        const val EXTRA_QUICK_SHARE = "extra_quick_share"

        /** A tapped push's Push Log id — the same key as the push's own data, so both paths match. */
        const val EXTRA_LOG_ID = "log_id"

        /** With [EXTRA_QUICK_SHARE], from the Quick Settings tile: start Receive right away. */
        const val EXTRA_QUICK_SHARE_RECEIVE = "extra_quick_share_receive"
        /** With [EXTRA_QUICK_SHARE]: text shared in from another app — open Send text with it. */
        const val EXTRA_QUICK_SHARE_TEXT = "extra_quick_share_text"
    }
}
