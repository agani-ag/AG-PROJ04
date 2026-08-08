package com.agani.syncup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agani.syncup.auth.AuthViewModel
import com.agani.syncup.ui.AccountScreen
import com.agani.syncup.ui.LoginScreen
import com.agani.syncup.ui.SplashScreen
import com.agani.syncup.ui.theme.AgHubTheme
import com.agani.syncup.web.WebViewActivity
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgHubTheme {
                val vm: AuthViewModel = viewModel()

                // Show the rotating splash briefly on launch, then the app.
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1500)
                    showSplash = false
                }

                if (showSplash) {
                    SplashScreen()
                } else {
                    val state = vm.state
                    val user = state.user
                    if (state.isLoggedIn && user != null) {
                        AccountScreen(
                            user = user,
                            urls = state.urls,
                            onOpenUrl = { item ->
                                startActivity(WebViewActivity.intent(this, item.url, item.title))
                            },
                            onLogout = { vm.logout() },
                        )
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
