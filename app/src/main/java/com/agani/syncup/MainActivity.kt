package com.agani.syncup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agani.syncup.auth.AuthViewModel
import com.agani.syncup.push.AppBootstrap
import com.agani.syncup.push.DeviceRegistrar
import com.agani.syncup.ui.AccountScreen
import com.agani.syncup.ui.ForceUpdateScreen
import com.agani.syncup.ui.LoginScreen
import com.agani.syncup.ui.ProfileScreen
import com.agani.syncup.ui.SplashScreen
import com.agani.syncup.ui.theme.AgHubTheme
import com.agani.syncup.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgHubTheme {
                val vm: AuthViewModel = viewModel()

                // Launch bootstrap: pull base URL from Remote Config + config (force-update gate),
                // while the rotating splash is shown.
                var booted by remember { mutableStateOf(false) }
                var forceUpdate by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val result = withContext(Dispatchers.IO) { AppBootstrap.run() }
                    forceUpdate = result.minSupportedVersion > BuildConfig.VERSION_CODE
                    if (vm.state.isLoggedIn) DeviceRegistrar.register(applicationContext)
                    delay(1200) // keep the splash visible briefly
                    booted = true
                }

                when {
                    !booted -> SplashScreen()
                    forceUpdate -> ForceUpdateScreen()
                    else -> {
                        val state = vm.state
                        val user = state.user
                        var showProfile by remember { mutableStateOf(false) }
                        if (state.isLoggedIn && user != null) {
                            // Ask for notification permission once (Android 13+), for push.
                            RequestNotificationPermission()
                            if (showProfile) {
                                ProfileScreen(
                                    user = user,
                                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    onBack = { showProfile = false },
                                    onLogout = {
                                        showProfile = false
                                        vm.logout()
                                    },
                                    onChangePassword = { current, new -> vm.changePassword(current, new) },
                                )
                            } else {
                                AccountScreen(
                                    user = user,
                                    urls = state.urls,
                                    onOpenUrl = { item ->
                                        startActivity(WebViewActivity.intent(this, item.url, item.title))
                                    },
                                    onOpenProfile = { showProfile = true },
                                    onRefresh = { vm.refresh() },
                                    refreshing = state.refreshing,
                                )
                            }
                        } else {
                            LoginScreen(
                                state = state,
                                onLogin = { email, password -> vm.login(email, password) },
                            )
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun RequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
