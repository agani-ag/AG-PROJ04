package com.agani.syncup.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agani.syncup.data.UrlItem
import com.agani.syncup.data.User

/** SyncUp's two places: the main page, and the browser with a website open. */
enum class MainTab { HOME, BROWSE }

/**
 * What this phone remembers for the main page: the welcome page (a fresh install only), the partners put
 * off with "Later" — and, for a partner user, their one link. Nothing here is a choice the user makes about
 * the page itself: it's the same for everyone (user, 14 Nov 2026).
 */
object HomePrefs {
    private lateinit var prefs: SharedPreferences

    /** A fresh install's first open: the welcome page comes before the main page (until Skip or a sign-in). */
    var welcomePending by mutableStateOf(false)
        private set

    /** The signed-in user is a partner user (created or added by a partner). */
    var partnerUser by mutableStateOf(false)
        private set
    /** The user's only link (signed in with exactly one), else null. */
    var singleLink by mutableStateOf<UrlItem?>(null)
        private set
    /** Partners whose "added you" card got "Later" — off the main page (the account sheet's Partners still has them). */
    var laterPartners by mutableStateOf<Set<String>>(emptySet())
        private set

    /** SyncUp always opens to the main page. */
    val startTab: MainTab get() = MainTab.HOME

    /** One partner, one link: SyncUp opens straight into it, as before — no switch any more (nothing to customise). */
    val opensLink: Boolean get() = singleLink != null && partnerUser

    /** [existingUser]: this phone used SyncUp before (it has browser tabs to restore). */
    fun init(context: Context, existingUser: Boolean) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)
        laterPartners = prefs.getStringSet(KEY_LATER_PARTNERS, emptySet()).orEmpty()
        // The welcome page: only a fresh install. v7 already ran here (it wrote the intro key) or the
        // phone has tabs: someone updating the app, who never sees it.
        if (!prefs.contains(KEY_WELCOME_DONE)) {
            val updating = existingUser || prefs.contains(KEY_INTRO_DONE)
            welcomePending = !updating
            prefs.edit().putBoolean(KEY_WELCOME_DONE, updating).apply()
        } else {
            welcomePending = !prefs.getBoolean(KEY_WELCOME_DONE, true)
        }
        // The old Home's choices (cards, rows, "open to", "open the link at start") are gone: drop them.
        prefs.edit().remove(KEY_START_HOME).remove(KEY_HIDDEN).remove(KEY_HIDDEN_ROWS).remove(KEY_START_LINK)
            .remove(KEY_PARTNER_DONE).putBoolean(KEY_INTRO_DONE, true).apply()
    }

    /** The welcome page is done for good (Skip, or signed in). */
    fun welcomeDone() {
        if (!welcomePending) return
        welcomePending = false
        prefs.edit().putBoolean(KEY_WELCOME_DONE, true).apply()
    }

    fun partnerLater(partnerId: String) {
        laterPartners = laterPartners + partnerId
        prefs.edit().putStringSet(KEY_LATER_PARTNERS, laterPartners).apply()
    }

    /** Who's signed in (null = nobody) and their links — for the partner-user rules above. */
    fun account(user: User?, links: List<UrlItem>) {
        partnerUser = user?.partnerUser == true
        singleLink = links.singleOrNull()?.takeIf { user != null }
    }

    private const val KEY_START_HOME = "start_home"
    private const val KEY_HIDDEN = "hidden_cards"
    private const val KEY_HIDDEN_ROWS = "hidden_today_rows"
    private const val KEY_INTRO_DONE = "intro_done"
    private const val KEY_WELCOME_DONE = "welcome_done"
    private const val KEY_START_LINK = "start_link"
    private const val KEY_PARTNER_DONE = "partner_defaults_done"
    private const val KEY_LATER_PARTNERS = "later_partners"
}
