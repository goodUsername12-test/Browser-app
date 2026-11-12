package com.fable.browser.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.engine.BrowserTab
import com.fable.browser.engine.FindMatch
import com.fable.browser.engine.HybridBlinkController
import com.fable.browser.engine.NativeFastEngine
import com.fable.browser.util.UrlUtils
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView
import java.io.ByteArrayInputStream

class GeckoCaptureHandle {
    @Volatile var thumbnailWidth: Int = THUMBNAIL_WIDTH

    internal var view: GeckoView? = null
    internal var blinkView: WebView? = null
    @Volatile internal var hybridActive: Boolean = false

    fun capture(onDone: (Bitmap?) -> Unit) {
        val wv = blinkView
        if (hybridActive && wv != null && wv.width > 0 && wv.height > 0) {
            val bmp = runCatching {
                val width = thumbnailWidth.coerceAtMost(wv.width).coerceAtLeast(1)
                val height = (wv.height * width.toFloat() / wv.width).toInt().coerceAtLeast(1)
                val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                val canvas = Canvas(out)
                val scale = width.toFloat() / wv.width.toFloat()
                canvas.scale(scale, scale)
                wv.draw(canvas)
                out
            }.getOrNull()
            onDone(bmp)
            return
        }
        val target = view
        if (target == null || target.session == null) {
            onDone(null)
            return
        }
        var finished = false
        val finish: (Bitmap?) -> Unit = { bitmap ->
            if (!finished) {
                finished = true
                onDone(bitmap)
            }
        }
        target.postDelayed({ finish(null) }, CAPTURE_TIMEOUT_MS)
        runCatching {
            target.capturePixels().accept(
                { bitmap -> finish(bitmap?.let { shrink(it) }) },
                { finish(null) }
            )
        }.onFailure { finish(null) }
    }

    private fun shrink(source: Bitmap): Bitmap? = runCatching {
        val width = thumbnailWidth.coerceAtMost(source.width)
        val height = (source.height * width.toFloat() / source.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val compact = scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
        if (compact !== scaled) scaled.recycle()
        if (source !== scaled && source !== compact) source.recycle()
        compact
    }.getOrNull()

    private companion object {
        const val THUMBNAIL_WIDTH = 360
        const val CAPTURE_TIMEOUT_MS = 400L
    }
}

private class FableHybridJsBridge(private val tab: BrowserTab) {
    @JavascriptInterface
    fun postArchiveMessage(json: String) {
        tab.hybridArchiveMessageHandler?.invoke(json)
    }

    @JavascriptInterface
    fun logDiagnostic(msg: String) {
        Log.i("FableShield", "[hybrid] $msg")
    }
}

private class CombinedEngineContainer(context: android.content.Context) : FrameLayout(context) {
    val geckoView: GeckoView = GeckoView(context).apply {
        setAutofillEnabled(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isFocusable = true
        isFocusableInTouchMode = true
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }
    var blinkView: WebView? = null
    var customFullScreenView: View? = null
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var boundTab: BrowserTab? = null
    var lastBlinkUrl: String = ""

    init {
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        addView(geckoView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = ev.actionIndex
                    NativeFastEngine.resetTouchPointer(ev.getPointerId(idx))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val idx = ev.actionIndex
                    NativeFastEngine.resetTouchPointer(ev.getPointerId(idx))
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1) {
                        val pid = ev.getPointerId(0)
                        if (!NativeFastEngine.filterCameraMove(pid, ev.x, ev.y, ev.eventTime)) {
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun ensureBlinkView(tab: BrowserTab): WebView {
        val existing = blinkView
        if (existing != null && boundTab === tab) return existing
        destroyBlinkView()
        boundTab = tab
        val wv = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.cacheMode = if (tab.isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
            }
            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            }
            addJavascriptInterface(FableHybridJsBridge(tab), "FableHybridBridge")
            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                val cur = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                tab.updateFindMatch(FindMatch(cur, numberOfMatches))
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val reqUrl = request.url?.toString() ?: return null
                    if (!request.isForMainFrame) {
                        val docHost = UrlUtils.host(tab.url.value)
                        val blocked = tab.hybridBlockChecker?.invoke(reqUrl, docHost) == true
                        if (blocked) {
                            tab.incrementBlocked()
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                204,
                                "Blocked by Gecko Privacy Shield",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val target = request.url?.toString() ?: return false
                    if (UrlUtils.isExternalScheme(target)) return true
                    val cleaned = UrlUtils.stripTrackingParams(target)
                    if (cleaned != target) {
                        view.post { view.loadUrl(cleaned) }
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) {
                        lastBlinkUrl = url
                        tab.updateUrl(url)
                        tab.updateSecure(!UrlUtils.isInsecure(url))
                        tab.resetBlocked()
                    }
                    tab.updateLoading(true)
                    tab.updateCanGoBack(view.canGoBack())
                    tab.updateCanGoForward(view.canGoForward())
                    val boot = tab.hybridBootstrapProvider?.invoke(url.orEmpty()).orEmpty()
                    if (boot.isNotEmpty()) {
                        runCatching { view.evaluateJavascript(boot, null) }
                    }
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    super.onPageCommitVisible(view, url)
                    val boot = tab.hybridBootstrapProvider?.invoke(url.orEmpty()).orEmpty()
                    if (boot.isNotEmpty()) {
                        runCatching { view.evaluateJavascript(boot, null) }
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        lastBlinkUrl = url
                        tab.updateUrl(url)
                        tab.updateSecure(!UrlUtils.isInsecure(url))
                    }
                    tab.updateLoading(false)
                    tab.updateProgress(100)
                    tab.updateCanGoBack(view.canGoBack())
                    tab.updateCanGoForward(view.canGoForward())
                    val boot = tab.hybridBootstrapProvider?.invoke(url.orEmpty()).orEmpty()
                    if (boot.isNotEmpty()) {
                        runCatching { view.evaluateJavascript(boot, null) }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    tab.updateProgress(newProgress)
                    tab.updateLoading(newProgress < 100)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!title.isNullOrBlank()) tab.updateTitle(title)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customFullScreenView?.let { removeView(it) }
                    customFullScreenView = view
                    customViewCallback = callback
                    addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    tab.updateFullScreen(true)
                }

                override fun onHideCustomView() {
                    customFullScreenView?.let { removeView(it) }
                    customFullScreenView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    tab.updateFullScreen(false)
                }
            }
        }
        val defaultUa = runCatching { wv.settings.userAgentString }.getOrDefault("")
        val controller = object : HybridBlinkController {
            override fun loadUrl(url: String) {
                wv.post {
                    lastBlinkUrl = url
                    wv.loadUrl(url)
                }
            }

            override fun reload() {
                wv.post { wv.reload() }
            }

            override fun stopLoading() {
                wv.post { wv.stopLoading() }
            }

            override fun goBack() {
                wv.post { if (wv.canGoBack()) wv.goBack() }
            }

            override fun goForward() {
                wv.post { if (wv.canGoForward()) wv.goForward() }
            }

            override fun setDesktopMode(desktop: Boolean) {
                wv.post {
                    wv.settings.userAgentString = if (desktop) {
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                    } else {
                        defaultUa
                    }
                    wv.settings.useWideViewPort = true
                    wv.settings.loadWithOverviewMode = desktop
                    wv.reload()
                }
            }

            override fun setTextZoom(percent: Int) {
                wv.post { wv.settings.textZoom = percent.coerceIn(50, 200) }
            }

            override fun findInPage(query: String, forward: Boolean) {
                wv.post {
                    if (query.isBlank()) {
                        wv.clearMatches()
                        tab.updateFindMatch(null)
                    } else {
                        wv.findAllAsync(query)
                        wv.findNext(forward)
                    }
                }
            }

            override fun clearFind() {
                wv.post {
                    wv.clearMatches()
                    tab.updateFindMatch(null)
                }
            }

            override fun savePageCode(mode: String, reqId: String): Boolean {
                val script = tab.hybridArchiveScriptProvider?.invoke(reqId, mode) ?: return false
                wv.post {
                    runCatching { wv.evaluateJavascript(script, null) }
                }
                return true
            }

            override fun exitFullScreen() {
                wv.post {
                    if (customFullScreenView != null) {
                        wv.webChromeClient?.onHideCustomView()
                    }
                }
            }
        }
        tab.hybridController = controller
        blinkView = wv
        addView(wv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return wv
    }

    fun destroyBlinkView() {
        customFullScreenView?.let { removeView(it) }
        customFullScreenView = null
        customViewCallback = null
        boundTab?.let { t ->
            t.hybridController = null
        }
        boundTab = null
        lastBlinkUrl = ""
        blinkView?.let { wv ->
            removeView(wv)
            runCatching {
                wv.stopLoading()
                if (wv.tag == "incognito") wv.clearCache(true)
                wv.webChromeClient = null
                wv.webViewClient = WebViewClient()
                wv.destroy()
            }
        }
        blinkView = null
    }
}

@Composable
fun GeckoViewHost(
    tab: BrowserTab,
    captureHandle: GeckoCaptureHandle,
    modifier: Modifier = Modifier
) {
    val session by tab.sessionFlow.collectAsStateWithLifecycle()
    val hybridBlink by tab.isHybridBlink.collectAsStateWithLifecycle()
    val currentUrl by tab.url.collectAsStateWithLifecycle()
    var attachTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(session) {
        var attempts = 0
        while (!session.isOpen && attempts < 40) {
            delay(50)
            attempts++
        }
        attachTick++
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CombinedEngineContainer(ctx).also { container ->
                captureHandle.view = container.geckoView
            }
        },
        update = { container ->
            val gv = container.geckoView
            captureHandle.view = gv
            captureHandle.hybridActive = hybridBlink
            val target = session
            if (attachTick >= 0 && gv.session !== target && target.isOpen) {
                if (gv.session != null) gv.releaseSession()
                gv.setSession(target)
            }
            if (hybridBlink) {
                NativeFastEngine.setGameBoost(true)
                // Keep GeckoView privacy session attached in background while Blink renders 3D/WebGL foreground
                gv.visibility = View.GONE
                val wv = container.ensureBlinkView(tab)
                wv.tag = if (tab.isIncognito) "incognito" else "normal"
                wv.visibility = View.VISIBLE
                captureHandle.blinkView = wv
                if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://") || currentUrl.startsWith("file://")) {
                    if (container.lastBlinkUrl != currentUrl && wv.url != currentUrl) {
                        container.lastBlinkUrl = currentUrl
                        wv.loadUrl(currentUrl)
                    }
                }
            } else {
                captureHandle.blinkView = null
                if (container.blinkView != null) {
                    container.destroyBlinkView()
                }
                gv.visibility = View.VISIBLE
            }
        },
        onRelease = { container ->
            if (captureHandle.view === container.geckoView) captureHandle.view = null
            captureHandle.blinkView = null
            captureHandle.hybridActive = false
            container.destroyBlinkView()
            if (container.geckoView.session != null) container.geckoView.releaseSession()
        }
    )
}
