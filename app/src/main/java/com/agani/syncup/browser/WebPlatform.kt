package com.agani.syncup.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.agani.syncup.R
import com.agani.syncup.downloads.DownloadRequest
import com.agani.syncup.downloads.Downloads
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Everything a browser tab needs from the host Activity: device permissions (camera, mic,
 * location + in-app GPS dialog), file uploads, downloads (with the tab's cookies), HTML5 fullscreen
 * video, printing, web-page notifications, and handing non-web links to other apps.
 *
 * Must be constructed while the Activity is being created (a property initializer), because it
 * registers ActivityResult launchers.
 *
 * Each section gets its own WebView profile when the device's WebView supports it, so Normal, Work
 * and Incognito never share cookies, logins or site storage.
 */
class WebPlatform(private val activity: ComponentActivity) {

    /** Events a tab's WebView reports back to the tab manager. */
    interface Listener {
        /**
         * target="_blank" / window.open → host the new window in a new tab of the same section and
         * return its WebView. It stays a real window: the opener can still talk to it and close it,
         * and form posts and pages the opener writes itself reach it intact.
         */
        fun onCreateWindow(from: BrowserTab): WebView
        /**
         * A pop-up's first page is about to load [url]. Returns true when the tab manager showed a
         * page it already had instead (the pop-up is then closed).
         */
        fun onPopupStarting(popup: BrowserTab, url: String): Boolean
        /** A pop-up opened without the user tapping was blocked; it wanted to show [url]. */
        fun onPopupBlocked(from: BrowserTab, url: String)
        /** The page called window.close() (e.g. a sign-in pop-up finishing). */
        fun onCloseWindow(tab: BrowserTab)
        /** A main-frame page finished loading. */
        fun onPageLoaded(tab: BrowserTab, url: String, title: String)
        /** Long-press on a link or an image: the page's context menu. */
        fun onLongPress(tab: BrowserTab, target: PressTarget)
        /** The page was pulled down far enough at its top: reload it. */
        fun onPullRefresh(tab: BrowserTab)
    }

    /** True while an HTML5 video is fullscreen (the browser's Back closes it first). */
    var fullscreen by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ pending callbacks
    private var pendingWebRtcRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingDownload: (() -> Unit)? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var notifId = 2000

    // ------------------------------------------------------------------ launchers
    private val fileChooserLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            val camUri = cameraPhotoUri
            cameraPhotoUri = null
            callback ?: return@registerForActivityResult
            if (result.resultCode != android.app.Activity.RESULT_OK) {
                callback.onReceiveValue(null) // cancelled — must report so the input can be reused
                return@registerForActivityResult
            }
            val data = result.data
            val uris: Array<Uri>? = when {
                data?.dataString != null || data?.clipData != null ->
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                camUri != null -> arrayOf(camUri) // camera capture wrote to our file Uri
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val webRtcPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val request = pendingWebRtcRequest
            pendingWebRtcRequest = null
            request ?: return@registerForActivityResult
            val granted = request.resources.all { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> has(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> has(Manifest.permission.RECORD_AUDIO)
                    else -> true
                }
            }
            if (granted) {
                request.grant(request.resources)
            } else {
                request.deny()
                val needed = buildList {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) add(Manifest.permission.CAMERA)
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) add(Manifest.permission.RECORD_AUDIO)
                }
                if (permanentlyDenied(needed)) {
                    settingsDialog("Camera / microphone permission is off. Turn it on in Settings to use this feature.")
                }
            }
        }

    private val storagePermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDownload
            pendingDownload = null
            if (granted) action?.invoke() else toast("Storage permission is needed to download files")
        }

    private val geoPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ensureGpsThenGrant(pendingGeoOrigin, pendingGeoCallback)
            } else {
                pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
                pendingGeoCallback = null
                pendingGeoOrigin = null
                if (permanentlyDenied(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))) {
                    settingsDialog("Location permission is off. Turn it on in Settings to use this feature.")
                }
            }
        }

    private val gpsResolutionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val origin = pendingGeoOrigin
            val callback = pendingGeoCallback
            pendingGeoOrigin = null
            pendingGeoCallback = null
            // Permission is granted already; honour it whatever the GPS choice was.
            callback?.invoke(origin, true, false)
            if (result.resultCode != android.app.Activity.RESULT_OK) {
                toast("Location is off — turn on GPS to share your location.")
            }
        }

    private val main = Handler(Looper.getMainLooper())
    private val pageFiles = PageFiles(activity, main)

    /** The Chrome version of the phone's WebView (same engine, same version as Chrome). */
    private val chromeMajor: String by lazy {
        val ua = runCatching { WebSettings.getDefaultUserAgent(activity) }.getOrDefault("")
        Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1) ?: "130"
    }

    /**
     * Chrome's own user agent (its reduced form), not WebView's ("; wv" … "Version/4.0"), which some
     * sites answer with a cut-down "app" page or a "use a browser" wall. [desktop] = "Desktop site".
     */
    fun userAgent(desktop: Boolean): String =
        if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Mobile Safari/537.36"
        }

    /** Apply the user's page settings (text size, darkening websites) — on creation and when they change. */
    fun applySettings(webView: WebView) {
        webView.settings.textZoom = BrowserSettings.textZoom
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Off: a site with its own dark theme still follows the app's theme; others stay as designed.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, BrowserSettings.darkenWebsites)
        }
    }

    /**
     * A stand-in window for a blocked pop-up: reports the first address it's sent to and is then
     * thrown away. Shares [tab]'s profile, as a window it opens must.
     */
    private fun urlCatcher(tab: BrowserTab, onUrl: (String) -> Unit): WebView {
        val catcher = WebView(activity)
        if (tab.section != Section.NORMAL && MULTI_PROFILE) {
            runCatching { WebViewCompat.setProfile(catcher, profileName(tab.section)) }
        }
        var done = false
        val discard = {
            if (!done) {
                done = true
                runCatching { catcher.destroy() }
            }
        }
        catcher.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                val target = req?.url
                if (!done && target != null && target.scheme?.lowercase() in setOf("http", "https")) onUrl(target.toString())
                main.post(discard) // not inside its own callback
                return true
            }
        }
        // One that is never sent anywhere (window.open('') + document.write) is dropped too.
        main.postDelayed(discard, 10_000)
        return catcher
    }

    // ------------------------------------------------------------------ WebView factory
    /** Build a fully configured WebView for [tab] (assigned to its section's profile). */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(tab: BrowserTab, listener: Listener): WebView {
        val webView = BrowserWebView(activity)
        Downloads.defaultUserAgent = userAgent(false)
        // Compose hosts the WebView with WRAP_CONTENT params unless it already has some, and a
        // WRAP_CONTENT height makes WebView report a 0 px tall viewport to pages: 100vh/100dvh = 0,
        // so full-height layouts, menus and dialogs sized with vh collapse.
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // The profile must be set before the WebView is used for anything else.
        if (tab.section != Section.NORMAL && MULTI_PROFILE) {
            runCatching { WebViewCompat.setProfile(webView, profileName(tab.section)) }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            // Every window.open reaches onCreateWindow, which applies pop-up blocking itself — so a
            // blocked pop-up can still be offered ("Pop-up blocked · Allow"), like Chrome.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false // uploads use the system picker; keep file:// off
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Chrome enlarges the text of desktop-only pages so it's readable on a phone.
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            cacheMode = if (tab.section == Section.INCOGNITO) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            // Like Chrome: http images and media on an https page still show; http scripts don't run.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgent(tab.desktop)
        }
        applySettings(webView)
        cookieManager(webView).setAcceptThirdPartyCookies(webView, true)

        // The address bar slides away while the page scrolls down; pull down at the top to reload;
        // long-press a link or an image for its menu.
        watchScroll(webView, tab)
        webView.onPull = { d -> tab.pull = d }
        webView.onPullRelease = { refresh -> if (refresh) listener.onPullRefresh(tab) }
        webView.setOnLongClickListener { longPress(webView, tab, listener) }

        // window.print() → Android printing, files the page makes itself (blob: / data: links) → the
        // Download Manager, and the ad blocker's element hiding.
        webView.addJavascriptInterface(PrintBridge(webView), "AndroidPrintBridge")
        webView.addJavascriptInterface(pageFiles.bridge(tab), PageFiles.BRIDGE)
        webView.addJavascriptInterface(AdBridge(tab), "AndroidAdBridge")
        webView.addJavascriptInterface(NotifyStateBridge(tab), "AndroidNotifyState")
        // Incognito gets no SyncUp token at all — not even the bridge object — so a page there can't
        // read or push-notify this user, matching what "Incognito" already promises everywhere else.
        if (tab.section != Section.INCOGNITO) webView.addJavascriptInterface(PageTokenBridge, TOKEN_BRIDGE)
        // Page notifications (asked per site) and the media detector talk over message channels,
        // which tell us the real site a message comes from.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, NOTIFY_BRIDGE, setOf("*")) { _, message, sourceOrigin, _, reply ->
                onNotifyMessage(tab, message.data, sourceOrigin.toString(), reply)
            }
            WebViewCompat.addWebMessageListener(webView, MediaDetector.BRIDGE, setOf("*")) { _, message, _, _, _ ->
                if (!BrowserSettings.mediaDetector) return@addWebMessageListener
                val m = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                MediaDetector.fromPage(m.optString("url"), m.optString("name"), m.optBoolean("playing"))?.let { MediaDetector.add(tab, it) }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, NOTIFY_SHIM_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, PRINT_SHIM_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, PageFiles.NAME_HINT_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, AD_CSS_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, MediaDetector.SCAN_JS, setOf("*"))
            // YouTube's video ads come from the same servers as its videos: handled in the page.
            WebViewCompat.addDocumentStartJavaScript(webView, AdBlocker.YOUTUBE_JS, AdBlocker.YOUTUBE_ORIGINS)
            if (tab.section != Section.INCOGNITO) WebViewCompat.addDocumentStartJavaScript(webView, SYNCUP_TOKEN_JS, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                tab.freshPopup = false
                tab.error = null
                tab.loading = true
                // A new page: the address bar comes back, and the page's finds and counts start over.
                tab.barHidden = false
                tab.detected.clear()
                tab.adsCounter.set(0)
                tab.adsBlocked = 0
                if (!url.isNullOrBlank()) {
                    tab.pageHost = hostOf(url)
                    collectWorkRoot(tab, url)
                    tab.url = url
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // Without document-start scripts (old WebView), as early as possible instead.
                view?.evaluateJavascript(AD_CSS_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.loading = false
                tab.pullRefreshing = false
                view?.evaluateJavascript(NOTIFY_SHIM_JS, null)
                view?.evaluateJavascript(PRINT_SHIM_JS, null)
                view?.evaluateJavascript(PageFiles.NAME_HINT_JS, null)
                view?.evaluateJavascript(AD_CSS_JS, null)
                view?.evaluateJavascript(MediaDetector.SCAN_JS, null)
                if (tab.section != Section.INCOGNITO) view?.evaluateJavascript(SYNCUP_TOKEN_JS, null)
                // Tint the status + navigation bars to the page's theme-color (null → app theme).
                view?.evaluateJavascript(THEME_COLOR_JS) { result -> tab.themeColor = parseCssRgb(result) }
                if (!url.isNullOrBlank() && url != "about:blank") {
                    collectWorkRoot(tab, url)
                    tab.rootsSettled = true // redirects are done — later navigation isn't masked by default
                    tab.url = url
                    listener.onPageLoaded(tab, url, view?.title.orEmpty())
                }
                syncNav(tab, view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (!url.isNullOrBlank() && url != "about:blank") {
                    tab.url = url
                    tab.pageHost = hostOf(url)
                }
                syncNav(tab, view)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // WebView's own threads: only quick checks here. The page itself is never blocked.
                if (request == null || request.isForMainFrame) return null
                val url = request.url?.toString() ?: return null
                val headers = request.requestHeaders.orEmpty()
                if (AdBlocker.activeFor(tab) && AdBlocker.shouldBlock(url, tab.pageHost, header(headers, "Accept"))) {
                    countBlocked(tab)
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                if (BrowserSettings.mediaDetector) {
                    MediaDetector.fromRequest(url, header(headers, "Range"))?.let { f -> main.post { MediaDetector.add(tab, f) } }
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    tab.loading = false
                    tab.pullRefreshing = false
                    tab.error = if (isOffline()) {
                        "You're offline. Check your internet connection and try again."
                    } else {
                        "Something went wrong loading this page. Please try again."
                    }
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError?,
            ) {
                // Never bypass certificate validation.
                handler.cancel()
                if (error?.url == view?.url || (view?.progress ?: 0) < 100) {
                    tab.loading = false
                    tab.error = "This site's security certificate isn't trusted, so it was blocked."
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val popupStart = tab.freshPopup && request.isForMainFrame
                if (popupStart) {
                    tab.freshPopup = false
                    if (listener.onPopupStarting(tab, uri.toString())) return true
                }
                // A pop-up that only hands a link to another app (WhatsApp, UPI, tel: …) has nothing
                // left to show: close it, back to the page that opened it.
                return routeUrl(uri, view) { if (popupStart) main.post { listener.onCloseWindow(tab) } }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                // Like Chrome, a pop-up the user didn't tap for is blocked (unless allowed for this
                // site). Its address is still caught so the user can open it from the notice.
                val site = UrlInput.rootDomainOf(tab.url).orEmpty()
                transport.webView = if (isUserGesture || BrowserSettings.popupsAllowed(site, tab.section == Section.INCOGNITO)) {
                    listener.onCreateWindow(tab)
                } else {
                    urlCatcher(tab) { url -> listener.onPopupBlocked(tab, url) }
                }
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                main.post { listener.onCloseWindow(tab) } // closing destroys this WebView: not inside its own callback
            }

            override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
                // A site that turns away SiteIcons' own fetch still shows its icon to the browser.
                // Never kept from Incognito.
                val url = view?.url
                if (icon != null && url != null && tab.section != Section.INCOGNITO) SiteIcons.offer(url, icon)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title.orEmpty()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
                tab.loading = newProgress in 1..99
                syncNav(tab, view)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()

            override fun onPermissionRequest(request: PermissionRequest) {
                // Camera / microphone: the site asks the user first (remembered per site), like Chrome.
                val origin = SitePermissions.originOf(request.origin.toString())
                val incognito = tab.section == Section.INCOGNITO
                val perms = buildList {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) add(SitePerm.CAMERA)
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) add(SitePerm.MIC)
                }
                if (perms.isEmpty()) {
                    // Protected (DRM) playback is allowed, as in Chrome; nothing else is.
                    val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    if (drm.isNotEmpty()) request.grant(drm.toTypedArray()) else request.deny()
                    return
                }
                val answers = perms.map { SitePermissions.get(origin, it, incognito) }
                when {
                    answers.any { it == false } -> request.deny()
                    answers.all { it == true } -> grantMedia(request)
                    else -> SitePermissions.ask(origin, perms, siteLabel(tab, origin), incognito) { allowed ->
                        if (allowed) grantMedia(request) else runCatching { request.deny() }
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                SitePermissions.cancel(SitePermissions.originOf(request.origin.toString()), listOf(SitePerm.CAMERA, SitePerm.MIC))
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback ?: return
                val site = SitePermissions.originOf(origin.orEmpty())
                val incognito = tab.section == Section.INCOGNITO
                when (SitePermissions.get(site, SitePerm.LOCATION, incognito)) {
                    false -> callback.invoke(origin, false, false)
                    true -> shareLocation(origin, callback)
                    null -> SitePermissions.ask(site, listOf(SitePerm.LOCATION), siteLabel(tab, site), incognito) { allowed ->
                        if (allowed) shareLocation(origin, callback) else callback.invoke(origin, false, false)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return openFileChooser(fileChooserParams)
            }
        }

        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, contentLength ->
            // Files the page made itself (blob: / data:) exist only inside the page; everything else
            // goes to SyncUp's Download Manager.
            val pageMade = downloadUrl.startsWith("blob:") || downloadUrl.startsWith("data:")
            val start = {
                if (pageMade) pageFiles.save(webView, tab, downloadUrl, mimeType)
                else Downloads.request(downloadRequest(webView, tab, downloadUrl, userAgent, contentDisposition, mimeType, contentLength))
            }
            withStorage(start)
        }
        return webView
    }

    /** Run [action] once old Android versions (9 and below) have the storage permission downloads need. */
    fun withStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingDownload = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    /**
     * A download from [tab]'s page: with the page's cookies (downloads behind a sign-in work) and the
     * page as Referer, labelled by its site — or by the SyncUp link's name, never its address.
     */
    fun downloadRequest(
        webView: WebView,
        tab: BrowserTab,
        url: String,
        userAgent: String = webView.settings.userAgentString,
        contentDisposition: String? = null,
        mimeType: String? = null,
        contentLength: Long = -1,
        suggestedName: String? = null,
    ) = DownloadRequest(
        url = url,
        userAgent = userAgent,
        referer = tab.url.takeIf { it.startsWith("http") }.orEmpty(),
        cookies = runCatching { cookieManager(webView).getCookie(url) }.getOrNull()?.takeIf { it.isNotEmpty() },
        contentDisposition = contentDisposition,
        mime = mimeType,
        contentLength = contentLength,
        source = tab.downloadSource,
        work = tab.isWork,
        incognito = tab.section == Section.INCOGNITO,
        suggestedName = suggestedName,
    )

    /** Download [url] (a link or an image from the long-press menu, or the detector) like any download. */
    fun downloadUrl(webView: WebView, tab: BrowserTab, url: String, suggestedName: String? = null, ask: Boolean = true) {
        withStorage {
            when {
                url.startsWith("blob:") || url.startsWith("data:") -> pageFiles.save(webView, tab, url, null)
                ask -> Downloads.request(downloadRequest(webView, tab, url, suggestedName = suggestedName))
                else -> Downloads.start(downloadRequest(webView, tab, url, suggestedName = suggestedName))
            }
        }
    }

    /** Share an image from the page (long-press → Share image) as a file, to WhatsApp and other apps. */
    fun shareImage(webView: WebView, tab: BrowserTab, url: String) {
        if (url.startsWith("blob:")) {
            toast("Download this image to share it")
            return
        }
        val req = downloadRequest(webView, tab, url)
        Thread {
            val file = runCatching {
                val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
                if (url.startsWith("data:")) {
                    val meta = url.substringAfter("data:").substringBefore(',')
                    val bytes = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(meta.substringBefore(';')) ?: "png"
                    File(dir, "image.$ext").apply { writeBytes(bytes) }
                } else {
                    val client = Downloads.client.newBuilder().followRedirects(true).followSslRedirects(true).build()
                    val call = okhttp3.Request.Builder().url(url).header("User-Agent", req.userAgent)
                        .apply {
                            req.cookies?.let { header("Cookie", it) }
                            if (req.referer.isNotBlank()) header("Referer", req.referer)
                        }
                        .build()
                    client.newCall(call).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        File(dir, com.agani.syncup.downloads.Files.cleanName(req.guessName())).apply {
                            r.body!!.byteStream().use { input -> outputStream().use { input.copyTo(it) } }
                        }
                    }
                }
            }.getOrNull()
            main.post {
                if (file == null) {
                    toast("Couldn't get this image")
                    return@post
                }
                val uri = runCatching { FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file) }.getOrNull()
                    ?: return@post
                val type = com.agani.syncup.downloads.Files.mimeFor(file.name, "image/*")
                val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { activity.startActivity(Intent.createChooser(send, "Share image")) }
            }
        }.start()
    }

    /** The address bar slides away after a scroll down and comes back on any scroll up (or at the top). */
    private fun watchScroll(webView: BrowserWebView, tab: BrowserTab) {
        val density = activity.resources.displayMetrics.density
        val hideAfter = 48 * density
        val showAfter = 24 * density
        var run = 0f
        var quietUntil = 0L
        webView.onScroll = { dy, y ->
            val now = SystemClock.uptimeMillis()
            when {
                y <= 0 -> {
                    if (tab.barHidden) {
                        tab.barHidden = false
                        quietUntil = now + 400
                    }
                    run = 0f
                }
                // The bar just moved and the page resized under it: that scroll isn't the user's.
                now < quietUntil -> run = 0f
                else -> {
                    if ((dy > 0) != (run > 0)) run = 0f
                    run += dy
                    if (run > hideAfter && !tab.barHidden || run < -showAfter && tab.barHidden) {
                        tab.barHidden = run > 0
                        quietUntil = now + 400
                        run = 0f
                    }
                }
            }
        }
    }

    /** Long-press: a link or an image opens the page's menu; anything else is left to WebView (text selection). */
    private fun longPress(webView: WebView, tab: BrowserTab, listener: Listener): Boolean {
        val hit = webView.hitTestResult
        val type = hit.type
        val extra = hit.extra
        if (type != WebView.HitTestResult.SRC_ANCHOR_TYPE && type != WebView.HitTestResult.IMAGE_TYPE &&
            type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        ) {
            return false
        }
        val msg = Handler(Looper.getMainLooper()) { m ->
            val href = m.data.getString("url")
            val text = m.data.getString("title")?.trim()?.takeIf { it.isNotBlank() }
            val link = (if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) extra else href)?.takeIf { usableLink(it) }
            val image = if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) null else (extra ?: m.data.getString("src"))?.takeIf { it.isNotBlank() }
            if (link != null || image != null) {
                webView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                listener.onLongPress(tab, PressTarget(link, text, image))
            }
            true
        }.obtainMessage()
        webView.requestFocusNodeHref(msg)
        return true
    }

    private fun usableLink(u: String) = u.startsWith("http://") || u.startsWith("https://") || u.startsWith("data:") || u.startsWith("blob:")

    /** Count a blocked ad for the page menu ("23 ads blocked"), batched onto the main thread. */
    private fun countBlocked(tab: BrowserTab) {
        tab.adsCounter.incrementAndGet()
        AdBlocker.counted()
        if (tab.adsPosted.compareAndSet(false, true)) {
            main.postDelayed({
                tab.adsPosted.set(false)
                tab.adsBlocked = tab.adsCounter.get()
            }, 400)
        }
    }

    /** What a permission question calls the site: its host, or the SyncUp link's name on the link's own site. */
    private fun siteLabel(tab: BrowserTab, origin: String): String {
        val host = SitePermissions.displayHost(origin)
        val onLinkSite = tab.isWork && tab.maskedRoots.any { UrlInput.hostMatches(host, it) }
        return if (onLinkSite) tab.workName ?: "This SyncUp link" else host
    }

    /** Camera / microphone allowed for the site: now Android's own permission, then grant. */
    private fun grantMedia(request: PermissionRequest) {
        val needed = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources && !has(Manifest.permission.CAMERA)) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources && !has(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) {
            runCatching { request.grant(request.resources) }
        } else {
            pendingWebRtcRequest = request
            webRtcPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** Location allowed for the site: Android's permission (with SyncUp's disclosure), GPS on, then share. */
    private fun shareLocation(origin: String?, callback: GeolocationPermissions.Callback) {
        if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            ensureGpsThenGrant(origin, callback)
        } else {
            pendingGeoOrigin = origin
            pendingGeoCallback = callback
            locationDisclosure(
                onContinue = { geoPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                onCancel = {
                    pendingGeoCallback?.invoke(origin, false, false)
                    pendingGeoCallback = null
                    pendingGeoOrigin = null
                },
            )
        }
    }

    /**
     * A page's Notification API call, from [origin] (the real site, as WebView reports it): the
     * permission state, a request (asks the user once per site), or a notification to show.
     */
    private fun onNotifyMessage(tab: BrowserTab, data: String?, origin: String, reply: JavaScriptReplyProxy) {
        val site = SitePermissions.originOf(origin)
        if (!site.startsWith("http")) return
        val m = runCatching { JSONObject(data ?: "") }.getOrNull() ?: return
        val incognito = tab.section == Section.INCOGNITO
        fun state() = when (SitePermissions.get(site, SitePerm.NOTIFICATIONS, incognito)) {
            true -> "granted"
            false -> "denied"
            null -> "default"
        }
        fun answer(id: Int) {
            runCatching { reply.postMessage(JSONObject().put("state", state()).put("id", id).toString()) }
        }
        when (m.optString("type")) {
            "state" -> answer(0)
            "request" -> {
                val id = m.optInt("id")
                if (SitePermissions.get(site, SitePerm.NOTIFICATIONS, incognito) != null) {
                    answer(id)
                } else {
                    SitePermissions.ask(site, listOf(SitePerm.NOTIFICATIONS), siteLabel(tab, site), incognito) { answer(id) }
                }
            }
            "show" -> if (SitePermissions.get(site, SitePerm.NOTIFICATIONS, incognito) == true) {
                showWebNotification(m.optString("title"), m.optString("body"))
            }
        }
    }

    /**
     * Notification.permission at page start, as Chrome knows it: answered only for the tab's own
     * site (a frame from another site, or a page asking about one, just gets "default").
     */
    private inner class NotifyStateBridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun state(origin: String?): String {
            val site = SitePermissions.originOf(origin.orEmpty())
            if (SitePermissions.displayHost(site) != tab.pageHost.removePrefix("www.")) return "default"
            return when (SitePermissions.get(site, SitePerm.NOTIFICATIONS, tab.section == Section.INCOGNITO)) {
                true -> "granted"
                false -> "denied"
                null -> "default"
            }
        }
    }

    /** The ad blocker's element hiding for the page (and its frames) asking. */
    private inner class AdBridge(private val tab: BrowserTab) {
        /** Are ads blocked for the page (or frame) on [host]? */
        @JavascriptInterface
        fun active(host: String?): Boolean {
            val h = host?.lowercase().orEmpty()
            if (!BrowserSettings.blockAds) return false
            return !AdBlocker.isAllowed(h) && !AdBlocker.isAllowed(tab.pageHost)
        }

        @JavascriptInterface
        fun css(host: String?): String = if (active(host)) AdBlocker.cosmeticCss(host?.lowercase().orEmpty()) else ""
    }

    private fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase().orEmpty()

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** While a work link's first load is still redirecting, every site it passes through is masked. */
    private fun collectWorkRoot(tab: BrowserTab, url: String) {
        if (!tab.isWork || tab.rootsSettled) return
        val root = UrlInput.rootDomainOf(url) ?: return
        if (root !in tab.maskedRoots) tab.maskedRoots.add(root)
    }

    private fun syncNav(tab: BrowserTab, view: WebView?) {
        view ?: return
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
    }

    /** The cookie store for this WebView's profile (the default one when profiles aren't supported). */
    private fun cookieManager(webView: WebView): CookieManager =
        if (MULTI_PROFILE) {
            runCatching { WebViewCompat.getProfile(webView).cookieManager }.getOrElse { CookieManager.getInstance() }
        } else {
            CookieManager.getInstance()
        }

    /** Wipe a section's cookies + site storage (sign-out for Work; closing Incognito). */
    fun wipeSection(section: Section) {
        if (section == Section.NORMAL) return
        if (MULTI_PROFILE) {
            runCatching {
                val profile = ProfileStore.getInstance().getOrCreateProfile(profileName(section))
                profile.cookieManager.removeAllCookies(null)
                profile.cookieManager.flush()
                profile.webStorage.deleteAllData()
            }
        }
    }

    // ------------------------------------------------------------------ link routing
    /**
     * Returns true if handled (external app / blocked); false → load it in the WebView.
     * [onLeftPage] runs when the link didn't load anything in the WebView.
     */
    private fun routeUrl(uri: Uri, view: WebView?, onLeftPage: () -> Unit = {}): Boolean {
        val handled = when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val host = uri.host?.lowercase().orEmpty()
                if (host == "wa.me" || host.endsWith("whatsapp.com")) {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")) }.isSuccess
                } else {
                    false
                }
            }
            "intent" -> {
                val fallbackLoaded = handleIntentScheme(uri.toString(), view)
                if (fallbackLoaded) return true // the page goes on, to the link's web version
                true
            }
            // Never let a page navigate into these.
            "javascript", "file", "content", "view-source", "chrome" -> true
            null -> false
            else -> {
                openExternal(Intent(Intent.ACTION_VIEW, uri)) // tel:, mailto:, upi:, market:, geo: …
                true
            }
        }
        if (handled) onLeftPage()
        return handled
    }

    /** Opens an intent: link in its app (or the Play Store). Returns true if its web fallback loaded in [view] instead. */
    private fun handleIntentScheme(url: String, view: WebView?): Boolean {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        if (intent == null) {
            toast("Can't open this link")
            return false
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (runCatching { activity.startActivity(intent) }.isSuccess) return false
        val fallback = intent.getStringExtra("browser_fallback_url")
        when {
            !fallback.isNullOrBlank() && fallback.startsWith("http") && view != null -> {
                view.loadUrl(fallback)
                return true
            }
            !intent.`package`.isNullOrBlank() ->
                openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}")))
            else -> toast("No app found to open this link")
        }
        return false
    }

    private fun openExternal(intent: Intent) {
        if (!runCatching { activity.startActivity(intent) }.isSuccess) toast("No app found to open this link")
    }

    // ------------------------------------------------------------------ fullscreen video
    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        savedOrientation = activity.requestedOrientation
        view.setBackgroundColor(android.graphics.Color.BLACK)
        (activity.window.decorView as? FrameLayout)?.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        fullscreen = true
    }

    fun exitFullscreen() {
        val v = customView ?: return
        (activity.window.decorView as? FrameLayout)?.removeView(v)
        customView = null
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = savedOrientation
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreen = false
    }

    // ------------------------------------------------------------------ file uploads
    private fun openFileChooser(params: WebChromeClient.FileChooserParams?): Boolean {
        cameraPhotoUri = null
        val content = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val mimes = params?.acceptTypes?.filter { it.contains("/") }?.toTypedArray()
            if (mimes != null && mimes.size == 1) {
                type = mimes[0]
            } else {
                type = "*/*"
                if (!mimes.isNullOrEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            }
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        val chooser = Intent.createChooser(content, "Select")
        createCameraIntent()?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
        val launched = runCatching { fileChooserLauncher.launch(chooser) }.isSuccess
        if (!launched) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            cameraPhotoUri = null
            toast("Couldn't open the file picker")
        }
        return launched
    }

    private fun createCameraIntent(): Intent? {
        if (!has(Manifest.permission.CAMERA)) return null
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(activity.packageManager) == null) return null
        val photo = runCatching { File.createTempFile("upload_", ".jpg", activity.cacheDir) }.getOrNull() ?: return null
        val uri = runCatching {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", photo)
        }.getOrNull() ?: return null
        cameraPhotoUri = uri
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return intent
    }

    // ------------------------------------------------------------------ location
    private fun ensureGpsThenGrant(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback ?: return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        val settings = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build()
        LocationServices.getSettingsClient(activity)
            .checkLocationSettings(settings)
            .addOnSuccessListener { callback.invoke(origin, true, false) }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    val handled = runCatching {
                        gpsResolutionLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                    }.isSuccess
                    if (!handled) {
                        pendingGeoOrigin = null
                        pendingGeoCallback = null
                        callback.invoke(origin, true, false)
                    }
                } else {
                    callback.invoke(origin, true, false)
                }
            }
    }

    private fun locationDisclosure(onContinue: () -> Unit, onCancel: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Share your location?")
            .setMessage(
                "This page is requesting your location. SyncUp will ask for the location permission and " +
                    "prompt to turn on GPS so your location can be shared with the page you opened. " +
                    "SyncUp does not collect or store your location.",
            )
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Not now") { _, _ -> onCancel() }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------------ notifications + print bridges
    private inner class PrintBridge(private val webView: WebView) {
        @JavascriptInterface
        fun print() {
            activity.runOnUiThread {
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return@runOnUiThread
                val job = activity.getString(R.string.app_name) + " — " + (webView.title ?: "Document")
                pm.print(job, webView.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
            }
        }
    }

    private fun showWebNotification(title: String?, body: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(Manifest.permission.POST_NOTIFICATIONS)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "Web notifications", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Notifications from web pages you open" },
            )
        }
        val n = NotificationCompat.Builder(activity, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(if (title.isNullOrBlank()) "Notification" else title)
            .setContentText(body.orEmpty())
            .setAutoCancel(true)
            .build()
        activity.getSystemService(NotificationManager::class.java).notify(notifId++, n)
    }

    // ------------------------------------------------------------------ helpers
    fun isOffline(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun permanentlyDenied(perms: List<String>) =
        perms.any { !has(it) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    private fun settingsDialog(message: String) {
        AlertDialog.Builder(activity)
            .setTitle("Permission needed")
            .setMessage(message)
            .setPositiveButton("Open settings") { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val NOTIF_CHANNEL_ID = "web_notifications"

        /**
         * Reads the page's <meta name="theme-color"> and resolves it (named/hex/rgb/hsl) to a plain
         * "rgb(r, g, b)" string, or "" when the page sets none.
         */
        private val THEME_COLOR_JS = """
            (function () {
              try {
                var m = document.querySelector('meta[name="theme-color"]');
                if (!m || !m.content) return '';
                var d = document.createElement('div');
                d.style.color = m.content;
                document.documentElement.appendChild(d);
                var c = getComputedStyle(d).color;
                d.remove();
                return c || '';
              } catch (e) { return ''; }
            })();
        """.trimIndent()

        /** Parse the "rgb(r, g, b)" / "rgba(...)" that THEME_COLOR_JS returns; null if absent. */
        private fun parseCssRgb(evalResult: String?): Int? {
            if (evalResult.isNullOrBlank()) return null
            val match = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)""").find(evalResult.trim().trim('"')) ?: return null
            val (r, g, b) = match.destructured
            return android.graphics.Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }

        /** Separate cookie/storage profiles per section need a recent Android System WebView. */
        val MULTI_PROFILE: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

        fun profileName(section: Section) = when (section) {
            Section.NORMAL -> androidx.webkit.Profile.DEFAULT_PROFILE_NAME
            Section.WORK -> "syncup_work"
            Section.INCOGNITO -> "syncup_incognito"
        }

        /**
         * The signed-in user's notification token (blank when signed out), put on every page in every
         * Normal and Work tab as window.SyncUp.token — never in Incognito. A site needs the SyncUp
         * notify key as well to push with it. Set by MainActivity from the account.
         */
        @Volatile var pageToken: String = ""

        private const val TOKEN_BRIDGE = "AndroidSyncUpToken"

        /** Reads the token when the page asks, so a sign-in after the tab opened still reaches new pages. */
        private object PageTokenBridge {
            @JavascriptInterface
            fun get(): String = pageToken
        }

        private val SYNCUP_TOKEN_JS = """
            (function () {
              var b = window.$TOKEN_BRIDGE;
              var t = b && b.get();
              if (t) window.SyncUp = Object.assign(window.SyncUp || {}, { token: t });
            })();
        """.trimIndent()

        private const val NOTIFY_BRIDGE = "SyncUpNotify"

        /**
         * The page's Notification API, answered per site: permission state, requestPermission()
         * (asks the user once for the site), and new Notification(…) shown in the status bar when
         * the site is allowed. Talks to [onNotifyMessage] over a message channel.
         */
        private val NOTIFY_SHIM_JS = """
            (function () {
              if (window.__syncupNotify) return;
              var port = window.$NOTIFY_BRIDGE;
              if (!port) return;
              window.__syncupNotify = true;
              var state = 'default', waiters = {}, seq = 0;
              try { state = window.AndroidNotifyState.state(location.origin) || 'default'; } catch (e) {}
              port.onmessage = function (e) {
                var m;
                try { m = JSON.parse(e.data); } catch (x) { return; }
                if (m.state) state = m.state;
                if (m.id && waiters[m.id]) { waiters[m.id](state); delete waiters[m.id]; }
              };
              port.postMessage(JSON.stringify({ type: 'state' }));
              function N(title, options) {
                options = options || {};
                this.title = String(title == null ? '' : title);
                this.body = options.body == null ? '' : String(options.body);
                if (state === 'granted') port.postMessage(JSON.stringify({ type: 'show', title: this.title, body: this.body }));
              }
              Object.defineProperty(N, 'permission', { get: function () { return state; } });
              N.requestPermission = function (cb) {
                return new Promise(function (resolve) {
                  var id = ++seq;
                  waiters[id] = function (s) {
                    if (typeof cb === 'function') { try { cb(s); } catch (x) {} }
                    resolve(s);
                  };
                  port.postMessage(JSON.stringify({ type: 'request', id: id }));
                });
              };
              N.prototype.close = function () {};
              N.prototype.addEventListener = function () {};
              try { Object.defineProperty(window, 'Notification', { value: N, configurable: true, writable: true }); }
              catch (e) { window.Notification = N; }
            })();
        """.trimIndent()

        /** Hides the page's ad boxes (CSS from the ad blocker for this frame's site). */
        private val AD_CSS_JS = """
            (function () {
              try {
                if (window.__syncupAds || !window.AndroidAdBridge) return;
                window.__syncupAds = true;
                var css = AndroidAdBridge.css(location.hostname);
                if (!css) return;
                var s = document.createElement('style');
                s.setAttribute('data-syncup', 'ads');
                s.textContent = css;
                (document.head || document.documentElement).appendChild(s);
              } catch (e) {}
            })();
        """.trimIndent()

        private val PRINT_SHIM_JS = """
            (function () {
              if (window.__androidPrintInstalled) return;
              window.__androidPrintInstalled = true;
              window.print = function () { try { AndroidPrintBridge.print(); } catch (e) {} };
            })();
        """.trimIndent()
    }
}
