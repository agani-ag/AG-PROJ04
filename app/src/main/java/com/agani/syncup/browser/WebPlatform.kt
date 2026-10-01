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
import android.os.Message
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
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.agani.syncup.R
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
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
        /** target="_blank" / window.open → open [url] in a new tab of the same section. */
        fun onNewWindow(from: BrowserTab, url: String)
        /** The page called window.close() (e.g. a sign-in pop-up finishing). */
        fun onCloseWindow(tab: BrowserTab)
        /** A main-frame page finished loading. */
        fun onPageLoaded(tab: BrowserTab, url: String, title: String)
        /** A download was handed to the system download manager. */
        fun onDownload(tab: BrowserTab, fileName: String, systemId: Long)
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

    // ------------------------------------------------------------------ WebView factory
    /** Build a fully configured WebView for [tab] (assigned to its section's profile). */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(tab: BrowserTab, listener: Listener): WebView {
        val webView = WebView(activity)
        // The profile must be set before the WebView is used for anything else.
        if (tab.section != Section.NORMAL && MULTI_PROFILE) {
            runCatching { WebViewCompat.setProfile(webView, profileName(tab.section)) }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            // Pop-ups without a user tap are blocked unless the user turned blocking off in Settings.
            javaScriptCanOpenWindowsAutomatically = !BrowserSettings.blockPopups
            setSupportMultipleWindows(true)
            allowFileAccess = false // uploads use the system picker; keep file:// off
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = if (tab.section == Section.INCOGNITO) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }
        cookieManager(webView).setAcceptThirdPartyCookies(webView, true)

        // Page Notification API → status bar, and window.print() → Android printing.
        webView.addJavascriptInterface(NotificationBridge(), "AndroidNotifyBridge")
        webView.addJavascriptInterface(PrintBridge(webView), "AndroidPrintBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, NOTIFICATION_SHIM_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, PRINT_SHIM_JS, setOf("*"))
            if (tab.notifyToken.isNotBlank()) {
                WebViewCompat.addDocumentStartJavaScript(webView, syncUpJs(tab.notifyToken), setOf("*"))
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                tab.error = null
                tab.loading = true
                if (!url.isNullOrBlank()) {
                    collectWorkRoot(tab, url)
                    tab.url = url
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.loading = false
                view?.evaluateJavascript(NOTIFICATION_SHIM_JS, null)
                view?.evaluateJavascript(PRINT_SHIM_JS, null)
                if (tab.notifyToken.isNotBlank()) view?.evaluateJavascript(syncUpJs(tab.notifyToken), null)
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
                if (!url.isNullOrBlank() && url != "about:blank") tab.url = url
                syncNav(tab, view)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    tab.loading = false
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
                return routeUrl(uri, view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                // target="_blank" / window.open → capture the URL and open it as a new tab.
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val temp = WebView(activity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val target = req?.url
                        if (target != null) {
                            when (target.scheme?.lowercase()) {
                                "http", "https" -> listener.onNewWindow(tab, target.toString())
                                else -> routeUrl(target, view)
                            }
                        }
                        v?.post { v.destroy() }
                        return true
                    }
                }
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                listener.onCloseWindow(tab)
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
                // Grant synchronously on the UI thread (deferring causes NotReadableError on some builds).
                val needed = mutableListOf<String>()
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources && !has(Manifest.permission.CAMERA)) {
                    needed.add(Manifest.permission.CAMERA)
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources && !has(Manifest.permission.RECORD_AUDIO)) {
                    needed.add(Manifest.permission.RECORD_AUDIO)
                }
                if (needed.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingWebRtcRequest = request
                    webRtcPermissionLauncher.launch(needed.toTypedArray())
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback ?: return
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

        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
            val enqueue = {
                val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    // Forward this tab's session cookies (its own profile) so downloads behind a login work.
                    cookieManager(webView).getCookie(downloadUrl)
                        ?.takeIf { it.isNotEmpty() }?.let { addRequestHeader("Cookie", it) }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val id = runCatching {
                    (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                }.getOrNull()
                if (id != null) {
                    listener.onDownload(tab, fileName, id)
                    toast("Downloading $fileName")
                } else {
                    toast("Couldn't start the download")
                }
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                pendingDownload = enqueue
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueue()
            }
        }
        return webView
    }

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
    /** Returns true if handled (external app / blocked); false → load it in the WebView. */
    private fun routeUrl(uri: Uri, view: WebView?): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val host = uri.host?.lowercase().orEmpty()
                if (host == "wa.me" || host.endsWith("whatsapp.com")) {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")) }.isSuccess
                } else {
                    false
                }
            }
            "intent" -> {
                handleIntentScheme(uri.toString(), view)
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
    }

    private fun handleIntentScheme(url: String, view: WebView?) {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        if (intent == null) {
            toast("Can't open this link")
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (runCatching { activity.startActivity(intent) }.isSuccess) return
        val fallback = intent.getStringExtra("browser_fallback_url")
        when {
            !fallback.isNullOrBlank() && fallback.startsWith("http") -> view?.loadUrl(fallback)
            !intent.`package`.isNullOrBlank() ->
                openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}")))
            else -> toast("No app found to open this link")
        }
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
    private inner class NotificationBridge {
        @JavascriptInterface
        fun notify(title: String?, body: String?) {
            activity.runOnUiThread { showWebNotification(title, body) }
        }
    }

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

        private fun syncUpJs(token: String): String {
            val safe = token.replace("\\", "\\\\").replace("\"", "\\\"")
            return "window.SyncUp=Object.assign(window.SyncUp||{},{token:\"$safe\"});"
        }

        private val NOTIFICATION_SHIM_JS = """
            (function () {
              if (window.__androidNotifyInstalled) return;
              window.__androidNotifyInstalled = true;
              function N(title, options) {
                options = options || {};
                this.title = title;
                this.body = options.body || '';
                try {
                  AndroidNotifyBridge.notify(String(title == null ? '' : title), String(options.body == null ? '' : options.body));
                } catch (e) {}
              }
              N.permission = 'granted';
              N.requestPermission = function (cb) {
                if (typeof cb === 'function') { try { cb('granted'); } catch (e) {} }
                return Promise.resolve('granted');
              };
              N.prototype.close = function () {};
              try { Object.defineProperty(window, 'Notification', { value: N, configurable: true, writable: true }); }
              catch (e) { window.Notification = N; }
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
