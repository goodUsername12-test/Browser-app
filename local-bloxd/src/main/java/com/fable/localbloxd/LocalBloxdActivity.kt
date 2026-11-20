package com.fable.localbloxd

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream

class LocalBloxdActivity : Activity() {
    companion object {
        private const val TAG = "LocalBloxdWeb"
        private const val MAX_RENDERER_RECOVERIES = 3
    }

    private var rootLayout: FrameLayout? = null
    private var webView: WebView? = null
    private lateinit var assetServer: LocalBloxdAssetServer
    private var currentUrl: String = "https://bloxd.io/"
    private var rendererRecoveryCount: Int = 0
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyMaxRefreshRate()
        enterImmersiveFullscreen()

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        runCatching { WebView.setWebContentsDebuggingEnabled(isDebuggable) }

        assetServer = LocalBloxdAssetServer(applicationContext)
        configureOfflineServiceWorkerController()
        NativeBloxdBridge.boostAsync()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B1020"))
        }
        rootLayout = root
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = runCatching { Api33BackHelper.register(this) { handleBackNavigation() } }.getOrNull()
        }

        currentUrl = savedInstanceState?.getString("fable_current_url")
            ?.takeIf { isAllowedBloxdUrl(it) }
            ?: resolveStartUrl(intent)

        val wv = createAndAttachWebView(root)
        Log.i(TAG, "Loading offline Bloxd URL: $currentUrl (cpp=${NativeBloxdBridge.hasCpp}, rust=${NativeBloxdBridge.hasRust})")
        wv.loadUrl(currentUrl)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("fable_current_url", currentUrl)
    }

    private fun configureOfflineServiceWorkerController() {
        runCatching {
            val controller = ServiceWorkerController.getInstance()
            // Never set blockNetworkLoads = false when android.permission.INTERNET is omitted.
            controller.serviceWorkerWebSettings.blockNetworkLoads = true
            controller.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val scheme = uri.scheme?.lowercase() ?: return null
                    if (scheme == "data" || scheme == "blob" || scheme == "about") return null
                    return runCatching {
                        assetServer.intercept(uri, request.method ?: "GET", request.requestHeaders ?: emptyMap())
                    }.onFailure { err ->
                        Log.e(TAG, "ServiceWorker intercept error for $uri: ${err.message}", err)
                    }.getOrElse {
                        fallbackErrorResponse()
                    }
                }
            })
        }.onFailure { err ->
            Log.w(TAG, "ServiceWorkerController setup skipped: ${err.message}")
        }
    }

    private fun fallbackErrorResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        500,
        "Internal Server Error",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("Offline asset error".toByteArray(Charsets.UTF_8))
    )

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createAndAttachWebView(root: FrameLayout): WebView {
        val wv = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1020"))
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                // Offline APK omits android.permission.INTERNET; blockNetworkLoads MUST stay true
                // (calling setBlockNetworkLoads(false) throws SecurityException on Android).
                // Chromium invokes shouldInterceptRequest() before applying the network block.
                blockNetworkLoads = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }

            addJavascriptInterface(NativeBloxdBridge.JsBridge(applicationContext), "FableLocalNative")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val scheme = uri.scheme?.lowercase() ?: return null
                    if (scheme == "data" || scheme == "blob" || scheme == "about") return null
                    return runCatching {
                        assetServer.intercept(uri, request.method ?: "GET", request.requestHeaders ?: emptyMap())
                    }.onFailure { err ->
                        Log.e(TAG, "Intercept error for $uri: ${err.message}", err)
                    }.getOrElse {
                        fallbackErrorResponse()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                    val raw = url ?: return null
                    if (raw.startsWith("data:") || raw.startsWith("blob:") || raw.startsWith("about:")) return null
                    return runCatching { assetServer.intercept(Uri.parse(raw), "GET") }
                        .onFailure { err -> Log.e(TAG, "Legacy intercept error for $raw: ${err.message}", err) }
                        .getOrElse { fallbackErrorResponse() }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    val host = uri.host?.lowercase().orEmpty()
                    val allowed = (scheme == "https" || scheme == "http") &&
                        (host == "bloxd.io" || host.endsWith(".bloxd.io") || host == "localhost")
                    if (allowed) {
                        currentUrl = uri.toString()
                        return false
                    }
                    Log.w(TAG, "Blocked external navigation in offline mode: $uri")
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.i(TAG, "onPageStarted: $url")
                    if (url != null && isAllowedBloxdUrl(url)) {
                        currentUrl = url
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "onPageFinished: $url")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val isMain = request?.isForMainFrame == true
                    Log.e(
                        TAG,
                        "onReceivedError(main=$isMain) url=${request?.url} code=${error?.errorCode} desc=${error?.description}"
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    val status = errorResponse?.statusCode ?: -1
                    if (status >= 400) {
                        Log.w(TAG, "onReceivedHttpError status=$status url=${request?.url}")
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    Log.e(TAG, "onReceivedSslError: ${error?.primaryError} url=${error?.url}")
                    handler?.cancel()
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    val didCrash = detail?.didCrash() == true
                    Log.e(TAG, "WebView render process gone (didCrash=$didCrash, recovery=$rendererRecoveryCount)")
                    if (view != null) {
                        destroyWebViewSafely(view)
                    }
                    if (view == webView) {
                        webView = null
                    }
                    val container = rootLayout
                    if (!isFinishing && !isDestroyed && container != null && rendererRecoveryCount < MAX_RENDERER_RECOVERIES) {
                        rendererRecoveryCount += 1
                        val fresh = createAndAttachWebView(container)
                        fresh.loadUrl(currentUrl)
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val cm = consoleMessage ?: return true
                    val line = "[${cm.messageLevel()}] ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})"
                    when (cm.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, line)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, line)
                        else -> Log.i(TAG, line)
                    }
                    return true
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                val m = View::class.java.getMethod("setRequestedFrameRate", Float::class.javaPrimitiveType)
                m.invoke(wv, 120f)
            }
        }

        webView = wv
        root.removeAllViews()
        root.addView(
            wv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return wv
    }

    private fun destroyWebViewSafely(wv: WebView) {
        runCatching { wv.stopLoading() }
        runCatching { wv.removeJavascriptInterface("FableLocalNative") }
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        runCatching { wv.destroy() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            val url = resolveStartUrl(intent)
            currentUrl = url
            Log.i(TAG, "Navigating offline Bloxd URL: $url")
            webView?.loadUrl(url)
        }
    }

    private fun isAllowedBloxdUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return scheme == "https" && (host == "bloxd.io" || host.endsWith(".bloxd.io"))
    }

    private fun resolveStartUrl(intent: Intent?): String {
        val extraUrl = intent?.getStringExtra("bloxd_url")?.trim()
        if (isAllowedBloxdUrl(extraUrl)) {
            return extraUrl!!
        }
        val data = intent?.data
        if (data != null && isAllowedBloxdUrl(data.toString())) {
            return data.toString()
        }
        return "https://bloxd.io/"
    }

    private fun applyMaxRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) this.display else windowManager.defaultDisplay
            val modes = display?.supportedModes ?: return
            val current = display.mode
            val best = modes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
                ?: modes.maxByOrNull { it.refreshRate }
            if (best != null) {
                val attrs = window.attributes
                attrs.preferredDisplayModeId = best.modeId
                attrs.preferredRefreshRate = best.refreshRate
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    attrs.preferMinimalPostProcessing = true
                }
                window.attributes = attrs
            }
        }
    }

    private fun enterImmersiveFullscreen() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { ctrl ->
                    ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveFullscreen()
            NativeBloxdBridge.boostAsync()
        }
    }

    private fun handleBackNavigation() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onPause() {
        NativeBloxdBridge.flushNowBlocking(applicationContext, 1500L)
        NativeBloxdBridge.relaxAsync()
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.resumeTimers()
        webView?.onResume()
        NativeBloxdBridge.boostAsync()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { cb ->
                runCatching { Api33BackHelper.unregister(this, cb) }
            }
            backCallback = null
        }
        NativeBloxdBridge.flushNowBlocking(applicationContext, 1500L)
        webView?.let { destroyWebViewSafely(it) }
        webView = null
        rootLayout = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33BackHelper {
        fun register(activity: Activity, onBack: () -> Unit): Any {
            val cb = android.window.OnBackInvokedCallback { onBack() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                cb
            )
            return cb
        }

        fun unregister(activity: Activity, handle: Any) {
            val cb = handle as? android.window.OnBackInvokedCallback ?: return
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        }
    }
}
