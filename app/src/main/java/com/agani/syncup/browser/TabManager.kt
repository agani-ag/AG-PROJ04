package com.agani.syncup.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Owns every tab in all three sections and the WebView behind each one.
 *
 * - Each section remembers its own active tab; [section] is the one on screen.
 * - WebViews are created lazily (a tab showing its section's home page has none) and live until the
 *   tab closes, so switching tabs keeps each page's state — except that a SyncUp link keeps only its
 *   [AWAKE_PAGES_PER_LINK] latest pages loaded; older ones sleep (still listed) and reload when shown.
 * - SyncUp tabs are grouped by link: pages a link's site opens in new windows join that link
 *   ([BrowserTab.groupId]), so the tab switcher and the tab count show one entry per link.
 * - Only Normal tabs are persisted across app restarts. Work tabs are never saved (they need the
 *   login and must not outlive a sign-out) and Incognito tabs are never saved by definition.
 */
class TabManager(
    private val context: Context,
    private val web: WebPlatform,
    private val db: BrowserDb,
) : WebPlatform.Listener {

    val tabs = mutableStateListOf<BrowserTab>()
    var section by mutableStateOf(Section.NORMAL)

    private val active = mutableStateMapOf<Section, Long>()
    private val views = HashMap<Long, WebView>()
    private var nextId = 1L
    private var useClock = 0L
    private val prefs = context.getSharedPreferences("browser_tabs", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The open Normal tabs changed (browser sync shows them on the user's other devices). */
    var onNormalTabsChanged: (() -> Unit)? = null

    /** A SyncUp link to open directly — the user's only one. */
    data class DirectLink(val name: String, val url: String, val id: String)

    /**
     * Set while the user has exactly one SyncUp link: SyncUp then IS that website. Going to SyncUp
     * opens it, Back from its first page goes to the Normal browser, and the SyncUp links page
     * (the section's home tab) is never shown.
     */
    var directLink by mutableStateOf<DirectLink?>(null)

    fun tabsIn(s: Section): List<BrowserTab> = tabs.filter { it.section == s }

    /** What the tab switcher and the tab count show: one entry per SyncUp link (its latest page), one per tab elsewhere. */
    fun cardsIn(s: Section): List<BrowserTab> =
        if (s == Section.WORK) tabsIn(s).groupBy { it.groupId }.values.map { g -> g.maxBy { it.lastUsed } } else tabsIn(s)

    /** A SyncUp link's pages: its first page plus the ones its site opened, in the order they opened. */
    fun groupPages(groupId: Long): List<BrowserTab> = tabs.filter { it.section == Section.WORK && it.groupId == groupId }

    /** The page of an open SyncUp link that was last on screen, or null when the link isn't open. */
    fun linkFront(linkId: String): BrowserTab? =
        tabs.filter { it.section == Section.WORK && it.workLinkId == linkId && !it.isHome }.maxByOrNull { it.lastUsed }

    /** How many pages a SyncUp link has open (0 = not open). */
    fun linkPageCount(linkId: String): Int = tabs.count { it.section == Section.WORK && it.workLinkId == linkId && !it.isHome }

    fun activeTab(s: Section = section): BrowserTab? = active[s]?.let { id -> tabs.firstOrNull { it.id == id } }

    /** The section's current tab, creating a home tab if the section has none. */
    fun ensureTab(s: Section): BrowserTab =
        activeTab(s) ?: tabsIn(s).lastOrNull()?.also { active[s] = it.id } ?: newTab(s, select = false)

    fun switchTo(s: Section) {
        if (s != section) captureActive()
        // One SyncUp link: going to SyncUp opens that website (its open tab, or a new one).
        val direct = directLink
        if (s == Section.WORK && direct != null) {
            openWorkLink(direct.name, direct.url, direct.id)
            return
        }
        section = s
        ensureTab(s)
    }

    fun newTab(
        s: Section,
        url: String = "",
        workName: String? = null,
        workLinkId: String? = null,
        workRoots: List<String> = emptyList(),
        select: Boolean = true,
        openerId: Long? = null,
        groupId: Long? = null,
        desktop: Boolean = false,
    ): BrowserTab {
        val tab = BrowserTab(nextId++, s, "", workName, workLinkId, workRoots)
        tab.openerId = openerId
        tab.desktop = desktop
        groupId?.let { tab.groupId = it }
        tabs.add(tab)
        if (select) {
            active[s] = tab.id
            section = s
        }
        if (url.isNotBlank()) load(tab, url)
        if (select) touch(tab)
        persist()
        return tab
    }

    /**
     * Open a SyncUp link as a Work tab: shown by [name], its site's address hidden. One tab per link —
     * a link that's already open goes back to its page that was last on screen.
     */
    fun openWorkLink(name: String, url: String, linkId: String? = null): BrowserTab {
        val tab = linkId?.let { linkFront(it) }?.also { select(it) } ?: newTab(
            Section.WORK, url,
            workName = name.ifBlank { "SyncUp link" },
            workLinkId = linkId,
            workRoots = listOfNotNull(UrlInput.rootDomainOf(url)),
        )
        // A one-link user never sees the SyncUp links page: drop any leftover one.
        if (directLink != null) tabs.removeAll { it.section == Section.WORK && it.isHome }
        return tab
    }

    fun select(tab: BrowserTab) {
        captureActive()
        active[tab.section] = tab.id
        section = tab.section
        touch(tab)
        persist()
    }

    /** [tab] is on screen now: newest in its link, and the link's oldest loaded pages go to sleep. */
    private fun touch(tab: BrowserTab) {
        tab.lastUsed = ++useClock
        if (tab.section != Section.WORK) return
        groupPages(tab.groupId)
            .filter { it.id != tab.id && views.containsKey(it.id) }
            .sortedByDescending { it.lastUsed }
            .drop(AWAKE_PAGES_PER_LINK - 1)
            .forEach { sleep(it) }
    }

    /** Unload a page to free memory. It stays in its link's list and reloads (history kept) when shown. */
    private fun sleep(tab: BrowserTab) {
        val wv = views[tab.id] ?: return
        tab.savedState = runCatching { android.os.Bundle().also { wv.saveState(it) } }.getOrNull()
        destroyView(tab.id)
        tab.loading = false
        tab.asleep = true
    }

    fun close(tab: BrowserTab) {
        destroyView(tab.id)
        tabs.remove(tab)
        if (active[tab.section] == tab.id) {
            // A SyncUp page closing stays within its link while the link has other pages.
            val sibling = if (tab.isWork) groupPages(tab.groupId).maxByOrNull { it.lastUsed } else null
            val next = sibling ?: tabsIn(tab.section).lastOrNull()
            if (next != null) active[tab.section] = next.id else active.remove(tab.section)
        }
        if (tab.section == Section.INCOGNITO && tabsIn(Section.INCOGNITO).isEmpty()) {
            web.wipeSection(Section.INCOGNITO) // last private tab closed → forget everything
            SitePermissions.clearIncognito()
        }
        keepSectionUsable()
        persist()
    }

    /** Close every tab in [s]; returns them so the caller can offer Undo ([reopen]). */
    fun closeAll(s: Section): List<BrowserTab> {
        val closed = tabsIn(s).filter { !it.isHome }
        tabsIn(s).forEach { destroyView(it.id) }
        tabs.removeAll { it.section == s }
        active.remove(s)
        if (s == Section.INCOGNITO) {
            web.wipeSection(Section.INCOGNITO)
            SitePermissions.clearIncognito()
        }
        keepSectionUsable()
        persist()
        return closed
    }

    /** Close a SyncUp link: all its pages. Returns them for Undo ([reopen]). */
    fun closeGroup(groupId: Long): List<BrowserTab> {
        val closed = groupPages(groupId)
        if (closed.isEmpty()) return closed
        closed.forEach { sleep(it) } // keeps each page's history for Undo
        tabs.removeAll(closed)
        if (closed.any { it.id == active[Section.WORK] }) {
            tabsIn(Section.WORK).lastOrNull()?.let { active[Section.WORK] = it.id } ?: active.remove(Section.WORK)
        }
        keepSectionUsable()
        persist()
        return closed
    }

    /** Undo for [closeAll] / [closeGroup]: bring the tabs back (their pages reload when shown). */
    fun reopen(closed: List<BrowserTab>) {
        if (closed.isEmpty()) return
        val s = closed.first().section
        // The placeholder home tab the close added (created after the closed tabs).
        val newest = closed.maxOf { it.id }
        tabs.removeAll { it.section == s && it.isHome && it.id > newest }
        closed.forEach {
            it.loading = false
            it.error = null
        }
        tabs.addAll(closed)
        active[s] = closed.maxBy { it.lastUsed }.id
        section = s
        persist()
    }

    /** Snapshot the on-screen tab so the tab switcher shows a real preview. */
    fun captureActive() {
        activeTab()?.let { capture(it) }
    }

    fun capture(tab: BrowserTab) {
        if (tab.isHome) return
        val wv = views[tab.id] ?: return
        if (wv.width <= 0 || wv.height <= 0 || !wv.isAttachedToWindow) return
        runCatching {
            val scale = 0.34f
            val bmp = android.graphics.Bitmap.createBitmap(
                (wv.width * scale).toInt().coerceAtLeast(1),
                (wv.height * scale).toInt().coerceAtLeast(1),
                android.graphics.Bitmap.Config.RGB_565,
            )
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(canvas)
            tab.thumbnail = bmp.asImageBitmap()
        }
    }

    /** The section on screen always has a tab; an emptied Incognito section drops back to Normal. */
    private fun keepSectionUsable() {
        if (tabsIn(section).isNotEmpty()) return
        // An emptied one-link SyncUp section has no links page to show either.
        if (section == Section.INCOGNITO || (section == Section.WORK && directLink != null)) section = Section.NORMAL
        ensureTab(section)
    }

    /** Sign-out: close every Work tab and wipe the Work session (cookies, logins, site storage). */
    fun wipeWork() {
        tabsIn(Section.WORK).forEach { destroyView(it.id) }
        tabs.removeAll { it.section == Section.WORK }
        active.remove(Section.WORK)
        web.wipeSection(Section.WORK)
        if (section == Section.WORK) section = Section.NORMAL
        ensureTab(section)
    }

    /**
     * A different account signed in on this phone: close the previous person's Normal tabs and
     * clear Normal cookies and site data (their history and bookmarks are wiped by browser sync).
     */
    fun resetNormalForNewAccount() {
        closeAll(Section.NORMAL)
        clearBrowsingData(history = false, cookies = true, cache = true)
    }

    /**
     * Settings → Clear browsing data, for the Normal section (Work has its own session that sign-out
     * wipes; Incognito keeps nothing anyway). Cache is shared by every WebView in the app.
     */
    fun clearBrowsingData(history: Boolean, cookies: Boolean, cache: Boolean) {
        if (history) io.execute { db.clearHistory() }
        if (cookies) {
            android.webkit.CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        if (cache) {
            views.values.firstOrNull()?.clearCache(true) ?: WebView(context).apply {
                clearCache(true)
                destroy()
            }
        }
    }

    // ------------------------------------------------------------------ navigation
    fun load(tab: BrowserTab, url: String) {
        if (url.isBlank()) return
        tab.error = null
        tab.url = url
        val existing = views[tab.id]
        if (existing != null) existing.loadUrl(url) else webView(tab) // new view loads tab.url itself
    }

    /** The tab's WebView, created on first use (and loading the tab's URL if it has one). */
    fun webView(tab: BrowserTab): WebView = views.getOrPut(tab.id) {
        web.create(tab, this).also { wv ->
            // A page waking from sleep gets its history back; otherwise it just loads its URL.
            val saved = tab.savedState
            tab.savedState = null
            tab.asleep = false
            val restored = saved != null && runCatching { wv.restoreState(saved) }.getOrNull() != null
            if (!restored && tab.url.isNotBlank()) wv.loadUrl(tab.url)
        }
    }

    /** The tab's WebView detached from any previous parent, ready to be hosted on screen. */
    fun attachable(tab: BrowserTab): WebView {
        val wv = webView(tab)
        (wv.parent as? ViewGroup)?.removeView(wv)
        return wv
    }

    /** Return the tab to its section's home page (drops the page and its WebView). */
    fun goHome(tab: BrowserTab) {
        destroyView(tab.id)
        tab.savedState = null
        tab.asleep = false
        tab.url = ""
        tab.title = ""
        tab.error = null
        tab.loading = false
        tab.progress = 0
        tab.canGoBack = false
        tab.canGoForward = false
        persist()
    }

    /** System Back inside the browser. Returns false when there's nowhere left to go. */
    fun back(): Boolean {
        val tab = activeTab() ?: return false
        val wv = views[tab.id]
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return true
        }
        // A tab a page opened: close it and go back to the page that opened it (like Chrome) — for a
        // SyncUp page whose opener was closed, to its link's latest other page.
        val back = tab.openerId?.let { id -> tabs.firstOrNull { it.id == id } }
            ?: tab.takeIf { it.isWork && it.openerId != null }
                ?.let { t -> groupPages(t.groupId).filter { it.id != t.id }.maxByOrNull { it.lastUsed } }
        if (back != null) {
            close(tab)
            select(back)
            return true
        }
        if (!tab.isHome) {
            // One SyncUp link: from the website's first page Back goes to the Normal browser, and
            // the website stays open for next time (there's no links page to go back to).
            if (tab.isWork && directLink != null) {
                switchTo(Section.NORMAL)
                return true
            }
            goHome(tab)
            return true
        }
        return false
    }

    fun forward() {
        val tab = activeTab() ?: return
        views[tab.id]?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload(tab: BrowserTab) {
        tab.error = null
        views[tab.id]?.reload() ?: run { if (tab.url.isNotBlank()) webView(tab) }
    }

    fun stop(tab: BrowserTab) {
        views[tab.id]?.stopLoading()
        tab.loading = false
    }

    // ------------------------------------------------------------------ pop-ups
    /** A pop-up that was blocked, for the "Pop-up blocked · Allow" notice (null = none). */
    class BlockedPopup(val fromId: Long, val url: String)

    var blockedPopup by mutableStateOf<BlockedPopup?>(null)
        private set

    /** "Allow" on the notice: this site may open pop-ups from now on, and the blocked one opens. */
    fun allowBlockedPopup(blocked: BlockedPopup) {
        blockedPopup = null
        val from = tabs.firstOrNull { it.id == blocked.fromId } ?: return
        UrlInput.rootDomainOf(from.url)?.let { BrowserSettings.allowPopups(it, from.section == Section.INCOGNITO) }
        openFrom(from, blocked.url)
    }

    fun dismissBlockedPopup() {
        blockedPopup = null
    }

    /** Open [url] as a page [from] opened (a new tab next to it; a SyncUp page's joins its link). */
    private fun openFrom(from: BrowserTab, url: String) {
        // A page the link already has is shown instead of a copy.
        if (from.isWork) {
            groupPages(from.groupId).firstOrNull { it.url == url && it.id != from.id }?.let {
                select(it)
                return
            }
        }
        // New windows stay in the same section; a work link's new window stays Work (and masked).
        newTab(
            from.section, url,
            workName = from.workName,
            workLinkId = from.workLinkId,
            workRoots = from.maskedRoots.toList(),
            openerId = from.id,
            groupId = if (from.isWork) from.groupId else null,
            desktop = from.desktop,
        )
    }

    // ------------------------------------------------------------------ long-press menu
    /** A long-press on a link or an image: the menu to show (null = none). */
    class Pressed(val tabId: Long, val target: PressTarget)

    var pressed by mutableStateOf<Pressed?>(null)

    fun dismissPressed() {
        pressed = null
    }

    /**
     * "Open in new tab" from a page: a new tab next to it that stays in the background (a SyncUp
     * page's joins its link). Returns the new tab so the caller can offer to switch to it.
     */
    fun openInBackground(from: BrowserTab, url: String): BrowserTab =
        newTab(
            from.section, url,
            workName = from.workName,
            workLinkId = from.workLinkId,
            workRoots = from.maskedRoots.toList(),
            select = false,
            openerId = null,
            groupId = if (from.isWork) from.groupId else null,
            desktop = from.desktop,
        )

    /** The WebView behind a tab, if it has one (for the long-press menu's actions). */
    fun viewOf(tab: BrowserTab): WebView? = views[tab.id]

    // ------------------------------------------------------------------ page settings
    /** Desktop site on/off for [tab]: its pages get a desktop or a phone user agent, and it reloads. */
    fun setDesktop(tab: BrowserTab, on: Boolean) {
        tab.desktop = on
        views[tab.id]?.let { wv ->
            wv.settings.userAgentString = web.userAgent(on)
            wv.reload()
        }
    }

    /** The user changed text size / website darkening: apply it to every open page. */
    fun applySettings() {
        views.values.forEach { web.applySettings(it) }
    }

    // ------------------------------------------------------------------ WebPlatform.Listener
    override fun onCreateWindow(from: BrowserTab): WebView {
        // The new window is a tab of the same section; a SyncUp page's joins its link (one tab per
        // link) and stays Work (and masked).
        val tab = BrowserTab(
            nextId++, from.section, "about:blank",
            workName = from.workName,
            workLinkId = from.workLinkId,
            workRoots = from.maskedRoots.toList(),
        )
        tab.openerId = from.id
        tab.freshPopup = true
        tab.desktop = from.desktop
        if (from.isWork) tab.groupId = from.groupId
        captureActive()
        tabs.add(tab)
        val wv = web.create(tab, this)
        views[tab.id] = wv
        active[tab.section] = tab.id
        section = tab.section
        touch(tab)
        persist()
        return wv
    }

    override fun onPopupStarting(popup: BrowserTab, url: String): Boolean {
        // A SyncUp page opening a page its link already has: show that one instead of a copy.
        if (!popup.isWork) return false
        val existing = groupPages(popup.groupId).firstOrNull { it.url == url && it.id != popup.id } ?: return false
        mainHandler.post {
            close(popup)
            select(existing)
        }
        return true
    }

    override fun onPopupBlocked(from: BrowserTab, url: String) {
        blockedPopup = BlockedPopup(from.id, url)
    }

    override fun onCloseWindow(tab: BrowserTab) {
        // window.close() from a page a site opened (a sign-in or payment pop-up): back to its opener.
        if (tab.openerId == null || tabs.none { it.id == tab.id }) return
        val opener = tabs.firstOrNull { it.id == tab.openerId }
        close(tab)
        opener?.let { select(it) }
    }

    override fun onLongPress(tab: BrowserTab, target: PressTarget) {
        if (activeTab(tab.section)?.id == tab.id) pressed = Pressed(tab.id, target)
    }

    override fun onPullRefresh(tab: BrowserTab) {
        tab.pullRefreshing = true
        reload(tab)
    }

    override fun onPageLoaded(tab: BrowserTab, url: String, title: String) {
        if (tab.section == Section.NORMAL) {
            io.execute { runCatching { db.addVisit(url, title) } }
            persist()
        }
    }

    // ------------------------------------------------------------------ lifecycle
    fun onPause() {
        views.values.forEach { it.onPause() }
        views.values.firstOrNull()?.pauseTimers() // timers are process-wide
    }

    fun onResume() {
        views.values.forEach { it.onResume() }
        views.values.firstOrNull()?.resumeTimers()
    }

    fun destroyAll() {
        views.keys.toList().forEach { destroyView(it) }
    }

    private fun destroyView(id: Long) {
        val wv = views.remove(id) ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.removeAllViews()
            wv.destroy()
        }
    }

    // ------------------------------------------------------------------ persistence (Normal only)
    private fun persist() {
        val normal = tabsIn(Section.NORMAL)
        val arr = JSONArray()
        normal.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) }
        val activeIndex = normal.indexOfFirst { it.id == active[Section.NORMAL] }
        prefs.edit().putString("normal", arr.toString()).putInt("active", activeIndex).apply()
        onNormalTabsChanged?.invoke()
    }

    /** Re-create the saved Normal tabs (pages load lazily when a tab is first shown). */
    fun restore() {
        if (tabs.isNotEmpty()) return
        val arr = runCatching { JSONArray(prefs.getString("normal", "[]")) }.getOrNull() ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tab = BrowserTab(nextId++, Section.NORMAL, o.optString("url"))
            tab.title = o.optString("title")
            tabs.add(tab)
        }
        val normal = tabsIn(Section.NORMAL)
        val idx = prefs.getInt("active", normal.lastIndex)
        normal.getOrNull(idx)?.let { active[Section.NORMAL] = it.id } ?: normal.lastOrNull()?.let { active[Section.NORMAL] = it.id }
        section = Section.NORMAL
        ensureTab(Section.NORMAL)
    }
}

/** A SyncUp link keeps this many of its pages loaded; older ones sleep until shown again. */
private const val AWAKE_PAGES_PER_LINK = 5
