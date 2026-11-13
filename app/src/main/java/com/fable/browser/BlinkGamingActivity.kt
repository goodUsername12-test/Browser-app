package com.fable.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.ClipData
import android.content.ClipboardManager
import com.fable.browser.data.prefs.Settings
import com.fable.browser.engine.FableUnifiedEngine
import com.fable.browser.engine.HybridEngineBridge
import com.fable.browser.engine.NativeFastEngine
import com.fable.browser.util.HighRefreshRateEnforcer

class BlinkGamingActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var customView: View? = null
    private var customContainer: FrameLayout? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        applyRefreshRate()
        NativeFastEngine.setGameBoost(true)
        hideSystemUi()

        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
                if (ev != null) {
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                            val idx = ev.actionIndex
                            NativeFastEngine.resetTouchPointer(ev.getPointerId(idx))
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val count = ev.pointerCount
                            if (count >= 1) {
                                var anyMoved = false
                                val t = ev.eventTime
                                for (i in 0 until count) {
                                    val pid = ev.getPointerId(i)
                                    if (NativeFastEngine.filterCameraMove(pid, ev.getX(i), ev.getY(i), t)) {
                                        anyMoved = true
                                    }
                                }
                                if (!anyMoved) return true
                            }
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isMotionEventSplittingEnabled = true
        }
        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { settings.offscreenPreRaster = true }
            }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            runCatching {
                val cleanedUa = FableUnifiedEngine.defaultBlinkUserAgent.ifBlank {
                    settings.userAgentString
                        .replace("; wv)", ")")
                        .replace("Version/4.0 ", "")
                }
                if (cleanedUa.isNotBlank()) settings.userAgentString = cleanedUa
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
            }
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, false) }
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onHeavyBurst() {
                        NativeFastEngine.notifyHeavyLoadBurst()
                    }

                    @JavascriptInterface
                    fun getClipboardText(): String {
                        return runCatching {
                            val cm = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = cm?.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                clip.getItemAt(0)?.coerceToText(applicationContext)?.toString().orEmpty()
                            } else ""
                        }.getOrDefault("")
                    }

                    @JavascriptInterface
                    fun setClipboardText(text: String) {
                        runCatching {
                            val cm = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Fable", text))
                        }
                    }

                    @JavascriptInterface
                    fun logDiagnostic(msg: String) {
                        Log.i("FableShield", "[blink-game] $msg")
                    }
                },
                "FableHybridBridge"
            )
            val bootScript = HybridEngineBridge.buildCombinedBootstrapScript(
                settings = Settings(),
                isIncognito = false,
                isSuper = false,
                superIdentity = null,
                pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    runCatching { view.evaluateJavascript(bootScript, null) }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    runCatching { view.evaluateJavascript(bootScript, null) }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customView?.let { customContainer?.removeView(it) }
                    customView = view
                    customContainer = root
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    this@BlinkGamingActivity.hideSystemUi()
                }

                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                    customContainer = null
                    this@BlinkGamingActivity.hideSystemUi()
                }
            }
        }
        webView = view
        root.addView(HighRefreshRateEnforcer.createFrameRateVoterSurfaceView(this), FrameLayout.LayoutParams(1, 1))
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        HighRefreshRateEnforcer.applyToViewTree(root)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val active = webView
                when {
                    customView != null -> webView?.webChromeClient?.onHideCustomView()
                    active?.canGoBack() == true -> active.goBack()
                    else -> finish()
                }
            }
        })

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.startsWith("https://") || url.startsWith("http://")) {
            FableUnifiedEngine.preconnectOriginAsync(url)
            FableUnifiedEngine.setFrameMonitorActive(true)
            view.loadUrl(url, mapOf("DNT" to "1", "Sec-GPC" to "1"))
        }
    }

    private fun applyRefreshRate() {
        HighRefreshRateEnforcer.applyToWindow(this, true)
    }

    private fun hideSystemUi() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() {
        FableUnifiedEngine.setFrameMonitorActive(false)
        FableUnifiedEngine.flushBlinkCookies()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        FableUnifiedEngine.setFrameMonitorActive(true)
    }

    override fun onDestroy() {
        FableUnifiedEngine.setFrameMonitorActive(false)
        FableUnifiedEngine.flushBlinkCookies()
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        webView = null
        NativeFastEngine.setGameBoost(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "blink_game_url"

        fun start(context: Context, url: String) {
            val intent = Intent(context, BlinkGamingActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
