package com.fable.browser.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
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
import com.fable.browser.engine.FableUnifiedEngine
import com.fable.browser.engine.FindMatch
import com.fable.browser.engine.HybridBlinkController
import com.fable.browser.engine.NativeFastEngine
import com.fable.browser.engine.ScreenRecorderManager
import com.fable.browser.engine.TouchIndicatorPoint
import com.fable.browser.util.HighRefreshRateEnforcer
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

private class FableHybridJsBridge(
    private val appContext: Context,
    private val tab: BrowserTab
) {
    @JavascriptInterface
    fun postArchiveMessage(json: String) {
        tab.hybridArchiveMessageHandler?.invoke(json)
    }

    @JavascriptInterface
    fun onHeavyBurst() {
        NativeFastEngine.notifyHeavyLoadBurst()
    }

    @JavascriptInterface
    fun onFrameDeltaNs(deltaNs: Long): Int {
        return FableUnifiedEngine.recordFrameDeltaNs(deltaNs)
    }

    @JavascriptInterface
    fun getClipboardText(): String {
        return runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
            } else {
                ""
            }
        }.getOrDefault("")
    }

    @JavascriptInterface
    fun setClipboardText(text: String) {
        runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Fable", text))
        }
    }

    @JavascriptInterface
    fun logDiagnostic(msg: String) {
        Log.i("FableShield", "[hybrid] $msg")
    }
}

private class CombinedEngineContainer(context: Context) : FrameLayout(context) {
    val frameVoter: SurfaceView = HighRefreshRateEnforcer.createFrameRateVoterSurfaceView(context)
    val geckoView: GeckoView = GeckoView(context).apply {
        setAutofillEnabled(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }
    var blinkView: WebView? = null
    var customFullScreenView: View? = null
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var boundTab: BrowserTab? = null
    @Volatile var showRecorderTouches: Boolean = false

    init {
        setWillNotDraw(true)
        isMotionEventSplittingEnabled = true
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        addView(frameVoter, LayoutParams(1, 1))
        addView(geckoView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        HighRefreshRateEnforcer.applyToViewTree(this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            if (showRecorderTouches && ScreenRecorderManager.isRecording.value) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        ScreenRecorderManager.onTouchEvent(emptyList(), true)
                    }
                    else -> {
                        val pts = ArrayList<TouchIndicatorPoint>(ev.pointerCount)
                        for (i in 0 until ev.pointerCount) {
                            if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP && i == ev.actionIndex) continue
                            pts.add(TouchIndicatorPoint(ev.getPointerId(i), ev.getX(i), ev.getY(i)))
                        }
                        ScreenRecorderManager.onTouchEvent(pts, true)
                    }
                }
            }
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
                        if (!anyMoved) {
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
        val currentAttached = blinkView
        if (currentAttached != null && boundTab === tab && currentAttached.parent === this) {
            HighRefreshRateEnforcer.applyToViewTree(currentAttached)
            return currentAttached
        }
        if (currentAttached != null && currentAttached.parent === this) {
            removeView(currentAttached)
        }
        boundTab = tab

        val retained = tab.retainedBlinkView
        if (retained != null) {
            (retained.context as? MutableContextWrapper)?.baseContext = context
            (retained.parent as? ViewGroup)?.removeView(retained)
            blinkView = retained
            addView(retained, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            HighRefreshRateEnforcer.applyToViewTree(retained)
            return retained
        }

        val mutableCtx = MutableContextWrapper(context)
        val wv = object : WebView(mutableCtx) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                if (t != oldt) {
                    tab.hybridScrollHandler?.invoke(t)
                }
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_NONE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            isLongClickable = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                isContextClickable = true
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                runCatching { settings.offscreenPreRaster = true }
            }
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
            addJavascriptInterface(FableHybridJsBridge(context.applicationContext, tab), "FableHybridBridge")
            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                val cur = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                tab.updateFindMatch(FindMatch(cur, numberOfMatches))
            }
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (!url.isNullOrBlank()) {
                    tab.hybridDownloadHandler?.invoke(url, contentDisposition, mimeType)
                }
            }
            setOnLongClickListener { v ->
                val hit = (v as? WebView)?.hitTestResult
                val target = FableUnifiedEngine.extractContextMenuTarget(tab, hit)
                if (target != null) {
                    tab.hybridContextMenuHandler?.invoke(target)
                    true
                } else {
                    false
                }
            }
            webViewClient = object : WebViewClient() {
                @Volatile private var cachedDocHost: String? = null

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (request.isForMainFrame) return null
                    val reqUrl = request.url?.toString() ?: return null
                    val docHost = cachedDocHost
                        ?: UrlUtils.host(tab.url.value)
                        ?: UrlUtils.host(tab.lastLoadedBlinkUrl)
                    val reqHost = UrlUtils.host(reqUrl)
                    if (docHost != null && reqHost != null && UrlUtils.isSameSite(reqHost, docHost)) return null
                    if (tab.fastAssetBypassEnabled && NativeFastEngine.isFastAsset(reqUrl, docHost)) return null
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
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val target = request.url?.toString() ?: return false
                    if (UrlUtils.isExternalScheme(target)) {
                        tab.hybridExternalUrlHandler?.invoke(target)
                        return true
                    }
                    val cleaned = UrlUtils.stripTrackingParams(target)
                    val extraHeaders = tab.hybridExtraHeadersProvider?.invoke(cleaned).orEmpty()
                    val needDntHeader = extraHeaders["DNT"] == "1" && request.requestHeaders["DNT"] != "1"
                    val isMainGet = request.isForMainFrame &&
                        request.method.equals("GET", ignoreCase = true) &&
                        (cleaned.startsWith("http://") || cleaned.startsWith("https://"))
                    if (cleaned != target || (isMainGet && needDntHeader)) {
                        view.post {
                            tab.lastLoadedBlinkUrl = cleaned
                            if (extraHeaders.isNotEmpty()) view.loadUrl(cleaned, extraHeaders) else view.loadUrl(cleaned)
                        }
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) {
                        tab.lastLoadedBlinkUrl = url
                        cachedDocHost = UrlUtils.host(url)
                        tab.updateUrl(url)
                        tab.updateSecure(!UrlUtils.isInsecure(url))
                        tab.resetBlocked()
                    }
                    NativeFastEngine.setGameBoost(true)
                    HighRefreshRateEnforcer.applyToViewTree(view)
                    tab.updateLoading(true)
                    tab.updateCanGoBack(view.canGoBack())
                    tab.updateCanGoForward(view.canGoForward())
                    val boot = tab.hybridBootstrapProvider?.invoke(url.orEmpty()).orEmpty()
                    if (boot.isNotEmpty()) {
                        runCatching { view.evaluateJavascript(boot, null) }
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (!url.isNullOrBlank()) {
                        tab.lastLoadedBlinkUrl = url
                        cachedDocHost = UrlUtils.host(url)
                        tab.updateUrl(url)
                        tab.updateCanGoBack(view.canGoBack())
                        tab.updateCanGoForward(view.canGoForward())
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        tab.lastLoadedBlinkUrl = url
                        cachedDocHost = UrlUtils.host(url)
                        tab.updateUrl(url)
                        tab.updateSecure(!UrlUtils.isInsecure(url))
                    }
                    tab.updateLoading(false)
                    tab.updateProgress(100)
                    tab.updateCanGoBack(view.canGoBack())
                    tab.updateCanGoForward(view.canGoForward())
                    NativeFastEngine.setGameBoost(true)
                    HighRefreshRateEnforcer.applyToViewTree(view)
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

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    val cb = filePathCallback ?: return false
                    val handler = tab.hybridFileChooserHandler
                    if (handler == null) {
                        cb.onReceiveValue(null)
                        return false
                    }
                    val types = fileChooserParams?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?: arrayOf("*/*")
                    val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    handler.invoke(types, multiple) { uris ->
                        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
                    }
                    return true
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
        val rawDefaultUa = FableUnifiedEngine.defaultBlinkUserAgent.ifBlank {
            runCatching { wv.settings.userAgentString }.getOrDefault("")
                .replace("; wv)", ")")
                .replace("Version/4.0 ", "")
        }
        val initialPrivacyUa = tab.hybridCustomUserAgent?.invoke()?.takeIf { it.isNotBlank() } ?: rawDefaultUa
        if (initialPrivacyUa.isNotBlank()) {
            runCatching { wv.settings.userAgentString = initialPrivacyUa }
        }
        val controller = object : HybridBlinkController {
            override fun loadUrl(url: String) {
                wv.post {
                    if (!tab.isDesktopMode.value) {
                        val ua = tab.hybridCustomUserAgent?.invoke()?.takeIf { it.isNotBlank() } ?: rawDefaultUa
                        if (ua.isNotBlank() && wv.settings.userAgentString != ua) {
                            runCatching { wv.settings.userAgentString = ua }
                        }
                    }
                    tab.lastLoadedBlinkUrl = url
                    val headers = tab.hybridExtraHeadersProvider?.invoke(url).orEmpty()
                    if (headers.isNotEmpty()) wv.loadUrl(url, headers) else wv.loadUrl(url)
                }
            }

            override fun reload() {
                wv.post {
                    val cur = wv.url ?: tab.lastLoadedBlinkUrl
                    val headers = tab.hybridExtraHeadersProvider?.invoke(cur).orEmpty()
                    if (cur.isNotBlank() && headers.isNotEmpty()) wv.loadUrl(cur, headers) else wv.reload()
                }
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
                        tab.hybridCustomUserAgent?.invoke()?.takeIf { it.isNotBlank() } ?: rawDefaultUa
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
        tab.retainedBlinkView = wv
        tab.hybridController = controller
        blinkView = wv
        addView(wv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        HighRefreshRateEnforcer.applyToViewTree(wv)
        return wv
    }

    fun detachBlinkView() {
        customFullScreenView?.let { removeView(it) }
        customFullScreenView = null
        customViewCallback = null
        boundTab = null
        blinkView?.let { wv ->
            if (wv.parent === this) {
                removeView(wv)
            }
        }
        blinkView = null
    }
}

@Composable
fun GeckoViewHost(
    tab: BrowserTab,
    captureHandle: GeckoCaptureHandle,
    showRecorderTouches: Boolean = false,
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
                ScreenRecorderManager.attachActiveGameView(container)
            }
        },
        update = { container ->
            container.showRecorderTouches = showRecorderTouches
            ScreenRecorderManager.attachActiveGameView(container)
            val gv = container.geckoView
            captureHandle.view = gv
            captureHandle.hybridActive = hybridBlink
            HighRefreshRateEnforcer.applyToViewTree(container)
            val target = session
            FableUnifiedEngine.setFrameMonitorActive(hybridBlink)
            if (hybridBlink) {
                NativeFastEngine.setGameBoost(true)
                if (tab.hibernateGeckoWhenHybrid) {
                    if (gv.session != null) {
                        runCatching { gv.releaseSession() }
                    }
                    if (target.isOpen) {
                        runCatching { target.setActive(false) }
                    }
                } else if (attachTick >= 0 && gv.session !== target && target.isOpen) {
                    if (gv.session != null) gv.releaseSession()
                    gv.setSession(target)
                }
                gv.visibility = View.GONE
                val wv = container.ensureBlinkView(tab)
                wv.tag = if (tab.isIncognito) "incognito" else "normal"
                wv.visibility = View.VISIBLE
                captureHandle.blinkView = wv
                if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://") || currentUrl.startsWith("file://")) {
                    val currentWvUrl = wv.url
                    if (tab.lastLoadedBlinkUrl.isBlank() && currentWvUrl.isNullOrBlank()) {
                        tab.lastLoadedBlinkUrl = currentUrl
                        val headers = tab.hybridExtraHeadersProvider?.invoke(currentUrl).orEmpty()
                        if (headers.isNotEmpty()) wv.loadUrl(currentUrl, headers) else wv.loadUrl(currentUrl)
                    }
                }
            } else {
                captureHandle.blinkView = null
                if (container.blinkView != null) {
                    container.detachBlinkView()
                }
                tab.destroyRetainedBlinkView()
                if (attachTick >= 0 && gv.session !== target && target.isOpen) {
                    if (gv.session != null) gv.releaseSession()
                    gv.setSession(target)
                }
                if (target.isOpen) {
                    runCatching { target.setActive(true) }
                }
                gv.visibility = View.VISIBLE
            }
        },
        onRelease = { container ->
            FableUnifiedEngine.setFrameMonitorActive(false)
            if (captureHandle.view === container.geckoView) captureHandle.view = null
            captureHandle.blinkView = null
            captureHandle.hybridActive = false
            container.detachBlinkView()
            if (container.geckoView.session != null) container.geckoView.releaseSession()
        }
    )
}
