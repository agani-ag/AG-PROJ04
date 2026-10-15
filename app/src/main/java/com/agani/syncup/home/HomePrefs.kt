package com.agani.syncup.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** SyncUp's four places: Home, the browser, the tools, and the user's own things. */
enum class MainTab { HOME, BROWSE, TOOLS, YOU }

/** The cards on Home, each one the user can hide (Settings → Home, or "Customise Home"). */
enum class HomeCard(val title: String, val hint: String) {
    TODAY("Today", "To-dos due today and your calm minutes"),
    PLAYING("Now playing", "Music or a radio station, while one plays"),
    NEARBY("Nearby", "A group, party, walkie talkie or share in progress"),
    TOOLS("Your tools", "Quick ways into Calendar, Forest, Share and more"),
    LINKS("SyncUp links", "Your SyncUp links, when you're signed in"),
}

/** This phone's Home settings: what SyncUp opens to, the hidden cards, the one-time "SyncUp has grown" card. */
object HomePrefs {
    private lateinit var prefs: SharedPreferences

    /** SyncUp opens to Home (true) or straight into the browser. */
    var startOnHome by mutableStateOf(true)
        private set
    var hidden by mutableStateOf<Set<HomeCard>>(emptySet())
        private set
    /** Someone who used SyncUp before Home existed: tell them once where the browser went. */
    var introPending by mutableStateOf(false)
        private set

    val startTab: MainTab get() = if (startOnHome) MainTab.HOME else MainTab.BROWSE

    /** [existingUser]: this phone used SyncUp before (it has browser tabs to restore). */
    fun init(context: Context, existingUser: Boolean) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)
        startOnHome = prefs.getBoolean(KEY_START_HOME, true)
        hidden = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            .mapNotNull { runCatching { HomeCard.valueOf(it) }.getOrNull() }.toSet()
        if (!prefs.contains(KEY_INTRO_DONE)) {
            // First start with Home: only someone who already used SyncUp gets the card.
            introPending = existingUser
            prefs.edit().putBoolean(KEY_INTRO_DONE, !existingUser).apply()
        } else {
            introPending = !prefs.getBoolean(KEY_INTRO_DONE, true)
        }
    }

    fun shows(card: HomeCard): Boolean = card !in hidden

    fun changeStartOnHome(on: Boolean) {
        startOnHome = on
        prefs.edit().putBoolean(KEY_START_HOME, on).apply()
    }

    fun changeCard(card: HomeCard, show: Boolean) {
        hidden = if (show) hidden - card else hidden + card
        prefs.edit().putStringSet(KEY_HIDDEN, hidden.map { it.name }.toSet()).apply()
    }

    fun introDone() {
        introPending = false
        prefs.edit().putBoolean(KEY_INTRO_DONE, true).apply()
    }

    private const val KEY_START_HOME = "start_home"
    private const val KEY_HIDDEN = "hidden_cards"
    private const val KEY_INTRO_DONE = "intro_done"
}
