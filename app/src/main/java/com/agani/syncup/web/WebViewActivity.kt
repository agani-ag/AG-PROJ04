package com.agani.syncup.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.agani.syncup.R
import java.io.File
import kotlin.math.abs

/**
 * Hosts a WebView that loads a selected URL with full device-permission bridging:
 * camera, microphone (WebRTC), geolocation, file picker, and downloads.
 * Security: HTTPS only, SSL errors are never bypassed. A single narrow JavaScript
 * bridge (title + body) forwards web-page notifications to the Android status bar.
 *
 * A draggable floating button overlays the page; tapping it reveals Home
 * (return to the links list) and Refresh (reload) actions.
 */
class WebViewActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // Floater views + state
    private lateinit var mainFab: ImageButton
    private lateinit var actionHome: ImageButton
    private lateinit var actionRefresh: ImageButton
    private lateinit var scrim: View

    // Custom offline / error page shown when a main-frame load fails.
    private lateinit var errorView: LinearLayout
    private lateinit var errorMessage: TextView
    private var loadFailed = false
    private var floaterExpanded = false
    private var fabPx = 0
    private var miniPx = 0
    private var marginPx = 0f
    private var gapPx = 0f
    private var touchSlopPx = 0f
    private var dragDX = 0f
    private var dragDY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    private var pendingWebRtcRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val callback = filePathCallback
            filePathCallback = null
            val camUri = cameraPhotoUri
            cameraPhotoUri = null
            callback ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) {
                callback.onReceiveValue(null) // cancelled — must report so the input can be reused
                return@registerForActivityResult
            }
            val data = result.data
            val uris: Array<Uri>? = when {
                // A file/photo was picked (single via data.data, multiple via clipData)
                data?.dataString != null || data?.clipData != null ->
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                // Camera capture: no result data, photo was written to our file Uri
                camUri != null -> arrayOf(camUri)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val webRtcPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val request = pendingWebRtcRequest
            pendingWebRtcRequest = null
            request ?: return@registerForActivityResult
            val granted = request.resources.all { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasPermission(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasPermission(Manifest.permission.RECORD_AUDIO)
                    else -> true
                }
            }
            if (granted) request.grant(request.resources) else request.deny()
        }

    private val geoPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

    private var notifId = 1000
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val webContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        webView = WebView(this)
        webContainer.addView(progressBar)
        webContainer.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
        )

        root = FrameLayout(this)
        root.addView(
            webContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setupFloater()
        setupErrorView()
        setContentView(root)

        ensureNotificationChannel()
        requestNotificationPermissionIfNeeded()
        configureWebView()
        webView.loadUrl(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    floaterExpanded -> collapseFloater()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ---------------------------------------------------------------------
    // Floating action button (draggable) + speed-dial menu
    // ---------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloater() {
        val d = resources.displayMetrics.density
        fabPx = (48 * d).toInt()
        miniPx = (48 * d).toInt()
        marginPx = 16 * d
        gapPx = 14 * d
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        // Dim layer behind the expanded menu; tap to close.
        scrim = View(this).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            setOnClickListener { collapseFloater() }
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        actionRefresh = miniButton(R.drawable.ic_refresh, "Refresh") {
            collapseFloater()
            webView.reload()
        }
        actionHome = miniButton(R.drawable.ic_home, "Home") {
            finish() // return to the links list
        }

        mainFab = ImageButton(this).apply {
            // White, ~75% transparent (alpha 0x40 ≈ 25% opacity) so it stays subtle over the page.
            background = circle(0x40FFFFFF)
            setImageResource(R.drawable.ic_more)
            setColorFilter(0xFF334155.toInt())
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = (13 * d).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 4 * d
            contentDescription = "Quick actions"
        }
        root.addView(mainFab, FrameLayout.LayoutParams(fabPx, fabPx))

        mainFab.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDX = v.x - e.rawX
                    dragDY = v.y - e.rawY
                    downRawX = e.rawX
                    downRawY = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (root.width - fabPx) - marginPx
                    val maxY = (root.height - fabPx) - marginPx
                    v.x = (e.rawX + dragDX).coerceIn(marginPx, maxX.coerceAtLeast(marginPx))
                    v.y = (e.rawY + dragDY).coerceIn(marginPx, maxY.coerceAtLeast(marginPx))
                    if (!dragging &&
                        (abs(e.rawX - downRawX) > touchSlopPx || abs(e.rawY - downRawY) > touchSlopPx)
                    ) {
                        dragging = true
                        if (floaterExpanded) collapseFloater()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        v.performClick()
                        toggleFloater()
                    } else {
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }

        // Initial position: bottom-right, clear of the nav bar.
        root.post {
            mainFab.x = root.width - fabPx - marginPx
            mainFab.y = root.height - fabPx - marginPx * 3
        }
    }

    private fun miniButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        val d = resources.displayMetrics.density
        val btn = ImageButton(this).apply {
            background = circle(Color.WHITE)
            setImageResource(iconRes)
            setColorFilter(0xFF334155.toInt())
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 4 * d
            visibility = View.GONE
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        root.addView(btn, FrameLayout.LayoutParams(miniPx, miniPx))
        return btn
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun toggleFloater() {
        if (floaterExpanded) collapseFloater() else expandFloater()
    }

    private fun expandFloater() {
        positionMenu()
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(120).start()
        listOf(actionHome, actionRefresh).forEach {
            it.alpha = 0f
            it.visibility = View.VISIBLE
            it.animate().alpha(1f).setDuration(140).start()
        }
        mainFab.setImageResource(R.drawable.ic_close)
        floaterExpanded = true
    }

    private fun collapseFloater() {
        scrim.visibility = View.GONE
        actionHome.visibility = View.GONE
        actionRefresh.visibility = View.GONE
        mainFab.setImageResource(R.drawable.ic_more)
        floaterExpanded = false
    }

    private fun positionMenu() {
        val cx = mainFab.x + fabPx / 2f
        val minX = marginPx
        val maxX = (root.width - miniPx) - marginPx
        val itemX = (cx - miniPx / 2f).coerceIn(minX, maxX.coerceAtLeast(minX))
        val openUp = mainFab.y > root.height / 2f
        if (openUp) {
            actionHome.x = itemX
            actionHome.y = mainFab.y - gapPx - miniPx
            actionRefresh.x = itemX
            actionRefresh.y = actionHome.y - gapPx - miniPx
        } else {
            actionHome.x = itemX
            actionHome.y = mainFab.y + fabPx + gapPx
            actionRefresh.x = itemX
            actionRefresh.y = actionHome.y + miniPx + gapPx
        }
    }

    private fun snapToEdge() {
        val targetX =
            if (mainFab.x + fabPx / 2f < root.width / 2f) marginPx
            else root.width - fabPx - marginPx
        mainFab.animate().x(targetX).setDuration(180).start()
    }

    @Suppress("SetJavaScriptEnabled")
    // ---------------------------------------------------------------------
    // Custom offline / error page
    // ---------------------------------------------------------------------

    private fun setupErrorView() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFFF8FAFC.toInt()) // opaque so it fully covers the page
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            isClickable = true // swallow taps so the page underneath isn't touched
            elevation = 8 * d // above the floating bubble (elevation 4dp) so it covers everything
        }

        val icon = TextView(this).apply {
            text = "📴"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "Can't load this page"
            textSize = 20f
            setTextColor(0xFF0F172A.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        errorMessage = TextView(this).apply {
            text = "Check your internet connection and try again."
            textSize = 15f
            setTextColor(0xFF5B6472.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val retry = Button(this).apply {
            text = "Retry"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF2563EB.toInt())
            }
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener {
                hideErrorPage()
                webView.reload()
            }
        }

        errorView.addView(icon)
        errorView.addView(title)
        errorView.addView(errorMessage)
        errorView.addView(
            retry,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        // Added last → sits on top of the WebView and the floating button.
        root.addView(
            errorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun showErrorPage(message: String) {
        loadFailed = true
        errorMessage.text = message
        errorView.visibility = View.VISIBLE
        errorView.bringToFront()
    }

    private fun hideErrorPage() {
        loadFailed = false
        errorView.visibility = View.GONE
    }

    private fun isOffline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // HTTPS only — never load mixed/insecure content.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        // Let web content follow the system dark theme where the WebView supports it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge web-page notifications (Notification API) to the Android status bar.
        webView.addJavascriptInterface(NotificationBridge(), "AndroidNotifyBridge")
        webView.addJavascriptInterface(PrintBridge(), "AndroidPrintBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, NOTIFICATION_SHIM_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, PRINT_SHIM_JS, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // A fresh navigation is starting — clear any stale error page.
                if (loadFailed) hideErrorPage()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Ensure the shims are present (covers pages/frames the document-start
                // script may miss, e.g. data: pages). The scripts self-guard against re-install.
                view?.evaluateJavascript(NOTIFICATION_SHIM_JS, null)
                view?.evaluateJavascript(PRINT_SHIM_JS, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Only the main page failing warrants the full-screen error; ignore sub-resources.
                if (request?.isForMainFrame == true) {
                    val message = if (isOffline()) {
                        "You're offline. Check your internet connection and try again."
                    } else {
                        "Something went wrong loading this page. Please try again."
                    }
                    showErrorPage(message)
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError?,
            ) {
                // Never bypass certificate validation (Google Play security requirement).
                handler.cancel()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                return routeUrl(uri)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                // A link with target="_blank" (or window.open) — capture its URL and route it.
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val temp = WebView(this@WebViewActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        req: WebResourceRequest?,
                    ): Boolean {
                        val target = req?.url
                        if (target != null) {
                            when (target.scheme?.lowercase()) {
                                // New-window web link → let the user choose in-app or browser.
                                "http", "https" -> promptOpenLocation(target)
                                // App schemes (tel:, upi:, whatsapp:, intent:, …) → hand off.
                                else -> routeUrl(target)
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

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = mutableListOf<String>()
                    val resources = request.resources
                    if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                        !hasPermission(Manifest.permission.CAMERA)
                    ) needed.add(Manifest.permission.CAMERA)
                    if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                        !hasPermission(Manifest.permission.RECORD_AUDIO)
                    ) needed.add(Manifest.permission.RECORD_AUDIO)

                    if (needed.isEmpty()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebRtcRequest = request
                        webRtcPermissionLauncher.launch(needed.toTypedArray())
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback ?: return
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    geoPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Downloading …", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------
    // Web-page notifications → Android status bar
    // ---------------------------------------------------------------------

    /** Narrow JS bridge: the injected shim forwards Notification API calls here. */
    private inner class NotificationBridge {
        @JavascriptInterface
        fun notify(title: String?, body: String?) {
            runOnUiThread { showWebNotification(title, body) }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Web notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notifications from web pages opened in the app" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showWebNotification(title: String?, body: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) return
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(if (title.isNullOrBlank()) "Notification" else title)
            .setContentText(body.orEmpty())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(notifId++, notification)
    }

    // ---------------------------------------------------------------------
    // Printing: route the page's window.print() to Android's print system
    // ---------------------------------------------------------------------

    private inner class PrintBridge {
        @JavascriptInterface
        fun print() {
            runOnUiThread { doPrint() }
        }
    }

    private fun doPrint() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val jobName = getString(R.string.app_name) + " — " + (webView.title ?: "Document")
        val adapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }

    // ---------------------------------------------------------------------
    // Link handling: web pages load in-app; other schemes hand off to apps
    // ---------------------------------------------------------------------

    /** Returns true if handled (external/app); false → the caller should load it in the WebView. */
    private fun routeUrl(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val host = uri.host?.lowercase().orEmpty()
                if (host == "wa.me" || host.endsWith("whatsapp.com")) {
                    // Open the WhatsApp app directly; if it's not installed, load the page in-app.
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
                    }.isSuccess
                } else {
                    false // normal web page → load inside the WebView
                }
            }
            "intent" -> {
                handleIntentScheme(uri.toString())
                true
            }
            null -> false
            else -> {
                // tel:, sms:, mailto:, upi:, whatsapp:, market:, geo:, etc.
                openExternal(Intent(Intent.ACTION_VIEW, uri))
                true
            }
        }
    }

    private fun handleIntentScheme(url: String) {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        if (intent == null) {
            toast("Can't open this link")
            return
        }
        if (runCatching { startActivity(intent) }.isSuccess) return
        // App not installed → try the page's declared fallback URL, then the Play Store.
        val fallback = intent.getStringExtra("browser_fallback_url")
        when {
            !fallback.isNullOrBlank() -> webView.loadUrl(fallback)
            !intent.`package`.isNullOrBlank() ->
                openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}")))
            else -> toast("No app found to open this link")
        }
    }

    private fun openExternal(intent: Intent) {
        if (!runCatching { startActivity(intent) }.isSuccess) {
            toast("No app found to open this link")
        }
    }

    /** For new-window links, ask whether to open in this app (WebView) or externally (Custom Tab). */
    private fun promptOpenLocation(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Open link")
            .setItems(arrayOf("Open in this app", "Open in browser")) { _, which ->
                when (which) {
                    0 -> webView.loadUrl(uri.toString())
                    1 -> openCustomTab(uri)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens a web URL in a Chrome Custom Tab (real browser overlay); falls back to the WebView. */
    private fun openCustomTab(uri: Uri) {
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(0xFF2563EB.toInt())
            .build()
        val customTab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .build()
        runCatching { customTab.launchUrl(this, uri) }
            .onFailure { webView.loadUrl(uri.toString()) }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------------
    // File uploads (<input type="file">): reliable Files/Gallery + Camera chooser
    // ---------------------------------------------------------------------

    private fun openFileChooser(params: WebChromeClient.FileChooserParams?): Boolean {
        cameraPhotoUri = null

        // Files / gallery picker with the page's accepted MIME types.
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
        // Offer the camera too (when permission is granted and a camera app exists).
        createCameraIntent()?.let { camera ->
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        }

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
        if (!hasPermission(Manifest.permission.CAMERA)) return null
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val photo = runCatching { File.createTempFile("upload_", ".jpg", cacheDir) }.getOrNull()
            ?: return null
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
        }.getOrNull() ?: return null
        cameraPhotoUri = uri
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return intent
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onDestroy() {
        // Detach and fully tear down the WebView to avoid memory leaks.
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val NOTIF_CHANNEL_ID = "web_notifications"

        /**
         * Injected before page scripts run: replaces the (missing) WebView Notification
         * API with a shim that forwards notifications to the native bridge.
         */
        private val NOTIFICATION_SHIM_JS = """
            (function () {
              if (window.__androidNotifyInstalled) return;
              window.__androidNotifyInstalled = true;
              function N(title, options) {
                options = options || {};
                this.title = title;
                this.body = options.body || '';
                try {
                  AndroidNotifyBridge.notify(
                    String(title == null ? '' : title),
                    String(options.body == null ? '' : options.body)
                  );
                } catch (e) {}
              }
              N.permission = 'granted';
              N.requestPermission = function (cb) {
                if (typeof cb === 'function') { try { cb('granted'); } catch (e) {} }
                return Promise.resolve('granted');
              };
              N.prototype.close = function () {};
              try {
                Object.defineProperty(window, 'Notification', {
                  value: N, configurable: true, writable: true
                });
              } catch (e) { window.Notification = N; }
            })();
        """.trimIndent()

        /** Injected: routes the page's window.print() to the native print bridge. */
        private val PRINT_SHIM_JS = """
            (function () {
              if (window.__androidPrintInstalled) return;
              window.__androidPrintInstalled = true;
              window.print = function () {
                try { AndroidPrintBridge.print(); } catch (e) {}
              };
            })();
        """.trimIndent()

        fun intent(context: Context, url: String, title: String?): Intent =
            Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
