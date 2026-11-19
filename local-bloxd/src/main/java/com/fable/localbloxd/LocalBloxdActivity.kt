package com.fable.localbloxd

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class LocalBloxdActivity : Activity() {
    companion object {
        private const val TAG = "LocalBloxdWeb"
    }

    private var webView: WebView? = null
    private lateinit var assetServer: LocalBloxdAssetServer

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyMaxRefreshRate()
        enterImmersiveFullscreen()

        runCatching { WebView.setWebContentsDebuggingEnabled(true) }

        assetServer = LocalBloxdAssetServer(applicationContext)
        NativeBloxdBridge.boostAsync()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B1020"))
        }

        val wv = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1020"))
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = false
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
                    return assetServer.intercept(uri, request.method ?: "GET", request.requestHeaders ?: emptyMap())
                }

                @Deprecated("Deprecated in Java")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                    val raw = url ?: return null
                    if (raw.startsWith("data:") || raw.startsWith("blob:") || raw.startsWith("about:")) return null
                    return runCatching { assetServer.intercept(Uri.parse(raw), "GET") }.getOrNull()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val host = uri.host?.lowercase().orEmpty()
                    return host != "bloxd.io" && !host.endsWith(".bloxd.io") && host != "localhost"
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
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val startUrl = resolveStartUrl(intent)
        Log.i(TAG, "Loading offline Bloxd URL: $startUrl")
        wv.loadUrl(startUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            val url = resolveStartUrl(intent)
            Log.i(TAG, "Navigating offline Bloxd URL: $url")
            webView?.loadUrl(url)
        }
    }

    private fun resolveStartUrl(intent: Intent?): String {
        val extraUrl = intent?.getStringExtra("bloxd_url")?.trim()
        if (!extraUrl.isNullOrEmpty() && extraUrl.startsWith("https://bloxd.io")) {
            return extraUrl
        }
        val data = intent?.data
        if (data != null && (data.host == "bloxd.io" || data.host?.endsWith(".bloxd.io") == true)) {
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
        NativeBloxdBridge.flushNow(applicationContext)
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
        NativeBloxdBridge.flushNow(applicationContext)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
