package com.agani.syncup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.agani.syncup.ui.AccountScreen
import com.agani.syncup.ui.ForceUpdateScreen
import com.agani.syncup.ui.LockScreen
import com.agani.syncup.ui.LoginScreen
import com.agani.syncup.ui.ProfileScreen
import com.agani.syncup.ui.SplashScreen
import com.agani.syncup.ui.theme.AgHubTheme
import com.agani.syncup.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class AppScreen { Splash, ForceUpdate, Login, Account, Profile }

class MainActivity : FragmentActivity() {

    private val security by lazy { SecurityStore(this) }
    private val appPrefs by lazy { AppPrefs(this) }
    private val lockedState = mutableStateOf(false)

    // A link a notification/reminder tap wants opened — honored once unlocked & logged in.
    private data class PendingLink(val url: String, val title: String)
    private val pendingLink = mutableStateOf<PendingLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockedState.value = security.hasPin() // lock on cold start if a PIN is set
        readDeepLink(intent)
        ReminderSyncWorker.schedulePeriodic(this) // daily safety-net reminder sync

        setContent {
            var themeMode by remember { mutableStateOf(appPrefs.themeMode()) }
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.BLACK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            AgHubTheme(darkTheme = dark, amoled = themeMode == ThemeMode.BLACK) {
                com.agani.syncup.ui.CrashReportDialog()
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
                            com.agani.syncup.data.TokenStore(this).clear()
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
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-lock only if the whole app was backgrounded (not on internal navigation).
        if (AppLock.lockPending) {
            AppLock.lockPending = false
            if (security.hasPin()) lockedState.value = true
        }
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
            pendingLink.value = PendingLink(url, intent.getStringExtra(ReminderContract.EXTRA_LINK_TITLE) ?: "")
            // Consume it so a config change / re-onStart doesn't reopen the link.
            intent.removeExtra(ReminderContract.EXTRA_LINK_URL)
            intent.removeExtra(ReminderContract.EXTRA_LINK_TITLE)
        }
    }

    @Composable
    private fun AppContent(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
        val vm: AuthViewModel = viewModel()

        var booted by remember { mutableStateOf(false) }
        var forceUpdate by remember { mutableStateOf(false) }
        var showProfile by remember { mutableStateOf(false) }
        var announcement by remember { mutableStateOf<com.agani.syncup.data.AnnouncementDto?>(null) }
        var supportEmail by remember { mutableStateOf("") }
        var supportPhone by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            val started = SystemClock.elapsedRealtime()
            // Base URL is needed before any API call — bound it so startup never hangs.
            withTimeoutOrNull(2500) { withContext(Dispatchers.IO) { AppBootstrap.applyBaseUrl() } }
            if (vm.state.isLoggedIn) DeviceRegistrar.register(applicationContext)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed < 600) delay(600 - elapsed)
            booted = true
            // Server config (announcement + force-update + support contacts) — after the splash, non-blocking.
            val cfg = withContext(Dispatchers.IO) { AppBootstrap.fetchConfig() }
            if (cfg != null) {
                forceUpdate = cfg.minSupportedVersion > BuildConfig.VERSION_CODE
                announcement = cfg.announcement
                supportEmail = cfg.supportEmail
                supportPhone = cfg.supportPhone
            }
        }

        val state = vm.state

        // Sync reminders once booted (base URL applied) and whenever the user logs in.
        LaunchedEffect(booted, state.isLoggedIn) {
            if (booted && state.isLoggedIn) {
                withContext(Dispatchers.IO) { ReminderSync.sync(applicationContext) }
            }
        }

        // A reminder/push tap wants a link opened — do it once unlocked (this branch only
        // composes when unlocked) and logged in.
        LaunchedEffect(pendingLink.value, state.isLoggedIn, state.user) {
            val link = pendingLink.value
            if (link != null && state.isLoggedIn && state.user != null) {
                pendingLink.value = null
                startActivity(WebViewActivity.intent(this@MainActivity, link.url, link.title))
            }
        }

        val screen = when {
            !booted -> AppScreen.Splash
            forceUpdate -> AppScreen.ForceUpdate
            !state.isLoggedIn || state.user == null -> AppScreen.Login
            showProfile -> AppScreen.Profile
            else -> AppScreen.Account
        }

        Crossfade(targetState = screen, animationSpec = tween(300), label = "screen") { target ->
            when (target) {
                AppScreen.Splash -> SplashScreen()
                AppScreen.ForceUpdate -> ForceUpdateScreen()
                AppScreen.Login -> LoginScreen(
                    state = state,
                    onLogin = { email, password -> vm.login(email, password) },
                    supportEmail = supportEmail,
                    supportPhone = supportPhone,
                )
                AppScreen.Profile -> state.user?.let { user ->
                    ProfileScreen(
                        user = user,
                        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        themeMode = themeMode,
                        onThemeChange = onThemeChange,
                        supportEmail = supportEmail,
                        supportPhone = supportPhone,
                        onBack = { showProfile = false },
                        onLogout = {
                            showProfile = false
                            vm.logout()
                        },
                        onChangePassword = { current, new -> vm.changePassword(current, new) },
                        onDeleteAccount = {
                            val result = vm.deleteAccount()
                            if (result.isSuccess) showProfile = false
                            result
                        },
                    )
                }
                AppScreen.Account -> state.user?.let { user ->
                    RequestNotificationPermission()
                    AccountScreen(
                        user = user,
                        urls = state.urls,
                        announcement = announcement,
                        message = state.message,
                        onMessageShown = { vm.clearMessage() },
                        onOpenUrl = { item ->
                            startActivity(WebViewActivity.intent(this, item.url, item.title))
                        },
                        onOpenProfile = { showProfile = true },
                        onRefresh = {
                            vm.refresh()
                            // Collect reminders alongside the links refresh.
                            lifecycleScope.launch(Dispatchers.IO) { ReminderSync.sync(applicationContext) }
                        },
                        refreshing = state.refreshing,
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
    private fun RequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
