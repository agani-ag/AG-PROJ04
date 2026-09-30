package com.agani.syncup.browser

import android.content.Context
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
 *   tab closes, so switching tabs keeps each page's state.
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
    private val prefs = context.getSharedPreferences("browser_tabs", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    /** The open Normal tabs changed (browser sync shows them on the user's other devices). */
    var onNormalTabsChanged: (() -> Unit)? = null

    fun tabsIn(s: Section): List<BrowserTab> = tabs.filter { it.section == s }

    fun activeTab(s: Section = section): BrowserTab? = active[s]?.let { id -> tabs.firstOrNull { it.id == id } }

    /** The section's current tab, creating a home tab if the section has none. */
    fun ensureTab(s: Section): BrowserTab =
        activeTab(s) ?: tabsIn(s).lastOrNull()?.also { active[s] = it.id } ?: newTab(s, select = false)

    fun switchTo(s: Section) {
        if (s != section) captureActive()
        section = s
        ensureTab(s)
    }

    fun newTab(
        s: Section,
        url: String = "",
        workName: String? = null,
        workLinkId: String? = null,
        workRoots: List<String> = emptyList(),
        notifyToken: String = "",
        select: Boolean = true,
    ): BrowserTab {
        val tab = BrowserTab(nextId++, s, "", workName, workLinkId, workRoots, notifyToken)
        tabs.add(tab)
        if (select) {
            active[s] = tab.id
            section = s
        }
        if (url.isNotBlank()) load(tab, url)
        persist()
        return tab
    }

    /** Open a SyncUp link as a Work tab: shown by [name], its site's address hidden. */
    fun openWorkLink(name: String, url: String, linkId: String? = null, notifyToken: String = ""): BrowserTab =
        newTab(
            Section.WORK, url,
            workName = name.ifBlank { "SyncUp link" },
            workLinkId = linkId,
            workRoots = listOfNotNull(UrlInput.rootDomainOf(url)),
            notifyToken = notifyToken,
        )

    fun select(tab: BrowserTab) {
        captureActive()
        active[tab.section] = tab.id
        section = tab.section
        persist()
    }

    fun close(tab: BrowserTab) {
        destroyView(tab.id)
        tabs.remove(tab)
        if (active[tab.section] == tab.id) {
            val next = tabsIn(tab.section).lastOrNull()
            if (next != null) active[tab.section] = next.id else active.remove(tab.section)
        }
        if (tab.section == Section.INCOGNITO && tabsIn(Section.INCOGNITO).isEmpty()) {
            web.wipeSection(Section.INCOGNITO) // last private tab closed → forget everything
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
        if (s == Section.INCOGNITO) web.wipeSection(Section.INCOGNITO)
        keepSectionUsable()
        persist()
        return closed
    }

    /** Undo for [closeAll]: bring the tabs back (their pages reload when shown). */
    fun reopen(closed: List<BrowserTab>) {
        if (closed.isEmpty()) return
        val s = closed.first().section
        tabs.removeAll { it.section == s && it.isHome } // the placeholder home tab added on close
        closed.forEach {
            it.loading = false
            it.error = null
        }
        tabs.addAll(closed)
        active[s] = closed.last().id
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
        if (section == Section.INCOGNITO) section = Section.NORMAL
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
        web.create(tab, this).also { if (tab.url.isNotBlank()) it.loadUrl(tab.url) }
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
        if (!tab.isHome) {
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

    // ------------------------------------------------------------------ WebPlatform.Listener
    override fun onNewWindow(from: BrowserTab, url: String) {
        // New windows stay in the same section; a work link's new window stays Work (and masked).
        newTab(
            from.section, url,
            workName = from.workName,
            workLinkId = from.workLinkId,
            workRoots = from.maskedRoots.toList(),
            notifyToken = from.notifyToken,
        )
    }

    override fun onPageLoaded(tab: BrowserTab, url: String, title: String) {
        if (tab.section == Section.NORMAL) {
            io.execute { runCatching { db.addVisit(url, title) } }
            persist()
        }
    }

    override fun onDownload(tab: BrowserTab, fileName: String, systemId: Long) {
        val work = tab.isWork
        val source = if (work) tab.workName ?: "SyncUp" else UrlInput.display(tab.url).substringBefore('/')
        io.execute { runCatching { db.addDownload(fileName, source, work, systemId) } }
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
