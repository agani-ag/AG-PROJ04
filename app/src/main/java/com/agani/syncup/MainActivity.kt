package com.agani.syncup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agani.syncup.auth.AuthViewModel
import com.agani.syncup.ui.AccountScreen
import com.agani.syncup.ui.LoginScreen
import com.agani.syncup.ui.theme.AgHubTheme
import com.agani.syncup.web.WebViewActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgHubTheme {
                val vm: AuthViewModel = viewModel()
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
