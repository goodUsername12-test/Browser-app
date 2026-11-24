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
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
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
import com.fable.browser.engine.FableBatteryGovernor
import com.fable.browser.engine.FableUnifiedEngine
import com.fable.browser.engine.HybridEngineBridge
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

    private fun bridgeAuthorized(token: String?): Boolean {
        val isolate = tab.hybridSettingsProvider?.invoke()?.isolateNativeBridge ?: true
        if (!isolate) return true
        return !token.isNullOrEmpty() && token == tab.bridgeToken
    }

    @JavascriptInterface
    fun readAccountVault(host: String?): String = readAccountVault(host, null)

    @JavascriptInterface
    fun readAccountVault(host: String?, token: String?): String {
        if (tab.isIncognito || host.isNullOrBlank() || !bridgeAuthorized(token)) return ""
        return runCatching { tab.hybridStorageVaultRead?.invoke(host).orEmpty() }.getOrDefault("")
    }

    @JavascriptInterface
    fun writeAccountVault(host: String?, jsonStr: String?) = writeAccountVault(host, jsonStr, null)

    @JavascriptInterface
    fun writeAccountVault(host: String?, jsonStr: String?, token: String?) {
        if (tab.isIncognito || host.isNullOrBlank() || jsonStr.isNullOrBlank() || !bridgeAuthorized(token)) return
        runCatching { tab.hybridStorageVaultWrite?.invoke(host, jsonStr) }
    }

    @JavascriptInterface
    fun readSiteRestorePayload(origin: String?, token: String?): String {
        if (tab.isIncognito || tab.isSuper || origin.isNullOrBlank() || !bridgeAuthorized(token)) return ""
        return runCatching { tab.hybridSiteRestoreRead?.invoke(origin).orEmpty() }.getOrDefault("")
    }

    @JavascriptInterface
    fun writeSiteSnapshot(origin: String?, jsonStr: String?, token: String?) {
        if (tab.isIncognito || tab.isSuper || origin.isNullOrBlank() || jsonStr.isNullOrBlank() || !bridgeAuthorized(token)) return
        runCatching { tab.hybridSiteSnapshotWrite?.invoke(origin, jsonStr) }
    }

    @JavascriptInterface
    fun readSavedLogin(host: String?, token: String?): String {
        if (host.isNullOrBlank() || !bridgeAuthorized(token)) return ""
        return runCatching { tab.hybridSavedLoginRead?.invoke(host).orEmpty() }.getOrDefault("")
    }

    @JavascriptInterface
    fun saveSiteLogin(origin: String?, username: String?, password: String?, token: String?) {
        if (origin.isNullOrBlank() || password.isNullOrBlank() || !bridgeAuthorized(token)) return
        runCatching { tab.hybridSavedLoginSave?.invoke(origin, username.orEmpty(), password) }
    }

    private data class StreamingJsDownload(
        val file: java.io.File,
        val out: java.io.BufferedOutputStream,
        val fileName: String,
        val mimeType: String
    )
    private val activeJsDownloads = java.util.concurrent.ConcurrentHashMap<String, StreamingJsDownload>()

    @JavascriptInterface
    fun onFrameMemResult(reqId: String?, frameId: String?, jsonStr: String?) {
        if (reqId.isNullOrBlank() || jsonStr.isNullOrBlank()) return
        pendingFrameMemCollectors[reqId]?.add(jsonStr)
    }

    companion object {
        val pendingFrameMemCollectors = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<String>>()
    }

    @JavascriptInterface
    fun beginJsDownload(dlId: String?, fileName: String?, mimeType: String?) {
        val id = dlId?.takeIf { it.isNotBlank() } ?: return
        val dir = java.io.File(appContext.cacheDir, "blink_dl").apply { mkdirs() }
        val tmp = java.io.File(dir, "jsdl_${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.part")
        val out = java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 256 * 1024)
        val safeName = (fileName?.takeIf { it.isNotBlank() } ?: "download").replace('"', '_')
        val safeMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        activeJsDownloads[id] = StreamingJsDownload(tmp, out, safeName, safeMime)
    }

    @JavascriptInterface
    fun appendJsDownloadChunk(dlId: String?, chunkB64: String?) {
        val id = dlId ?: return
        val state = activeJsDownloads[id] ?: return
        val raw = chunkB64?.takeIf { it.isNotEmpty() } ?: return
        runCatching {
            val bytes = NativeFastEngine.decodeBase64(raw)
            if (bytes.isNotEmpty()) {
                synchronized(state) { state.out.write(bytes) }
            }
        }
    }

    @JavascriptInterface
    fun finishJsDownload(dlId: String?) {
        val id = dlId ?: return
        val state = activeJsDownloads.remove(id) ?: return
        runCatching {
            synchronized(state) {
                state.out.flush()
                state.out.close()
            }
            val saver = tab.hybridFileSaveHandler
            if (saver != null) {
                saver.invoke(state.file, state.fileName, state.mimeType)
            } else {
                state.file.delete()
            }
        }.onFailure {
            runCatching { state.out.close() }
            state.file.delete()
        }
    }

    @JavascriptInterface
    fun enqueueHttpDownload(url: String?, suggestedName: String?) {
        val u = url?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
        val disp = suggestedName?.takeIf { it.isNotBlank() }?.let { "attachment; filename=${it.replace('"', '_')}" }
        tab.hybridDownloadHandler?.invoke(u, disp, null, FableUnifiedEngine.defaultBlinkUserAgent)
    }

    @JavascriptInterface
    fun onJsFullScreenChanged(active: Boolean) {
        tab.updateFullScreen(active)
    }

    @JavascriptInterface
    fun saveBlobBase64(b64: String?, fileName: String?, mimeType: String?) {
        val cleanB64 = b64?.trim() ?: return
        val safeMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val safeName = (fileName?.takeIf { it.isNotBlank() } ?: "download").replace('"', '_')
        val saver = tab.hybridFileSaveHandler
        if (saver != null) {
            runCatching {
                val dir = java.io.File(appContext.cacheDir, "blink_dl").apply { mkdirs() }
                val tmp = java.io.File(dir, "blob_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.bin")
                val decoded = if (cleanB64.isNotEmpty()) NativeFastEngine.decodeBase64(cleanB64) else ByteArray(0)
                tmp.writeBytes(decoded)
                saver.invoke(tmp, safeName, safeMime)
            }
            return
        }
        val dataUrl = "data:$safeMime;base64,$cleanB64"
        tab.hybridDownloadHandler?.invoke(dataUrl, "attachment; filename=$safeName", safeMime, null)
    }

    @JavascriptInterface
    fun getClipboardText(): String = getClipboardText(null)

    @JavascriptInterface
    fun getClipboardText(token: String?): String {
        if (!bridgeAuthorized(token)) return ""
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
    fun setClipboardText(text: String) = setClipboardText(text, null)

    @JavascriptInterface
    fun setClipboardText(text: String, token: String?) {
        if (!bridgeAuthorized(token)) return
        runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Fable", text))
        }
    }

    @JavascriptInterface
    fun onWebGpuTelemetry(json: String?) {
        com.fable.browser.engine.FableWebGpuManager.onWebGpuTelemetryJson(json)
    }

    @JavascriptInterface
    fun logDiagnostic(msg: String) {
        Log.i("FableShield", "[hybrid] $msg")
    }
}

private class CombinedEngineContainer(context: Context) : FrameLayout(context) {
    val frameVoter: SurfaceView? = if (android.os.Build.VERSION.SDK_INT < 34) {
        HighRefreshRateEnforcer.createFrameRateVoterSurfaceView(context)
    } else {
        null
    }
    var lastAppliedRate: Float = -1f
    var lastAppliedBoost: Boolean? = null
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
    var authPopupOverlay: View? = null
    var authPopupWebView: WebView? = null
    var boundTab: BrowserTab? = null

    private fun closeAuthPopup() {
        authPopupOverlay?.let { removeView(it) }
        authPopupOverlay = null
        authPopupWebView?.let { pwv ->
            runCatching {
                pwv.stopLoading()
                pwv.webChromeClient = null
                pwv.webViewClient = WebViewClient()
                pwv.destroy()
            }
        }
        authPopupWebView = null
        FableUnifiedEngine.flushBlinkCookies()
        boundTab?.let { t ->
            val u = t.url.value
            if (u.startsWith("http", ignoreCase = true)) t.hybridCookieSyncHandler?.invoke(u)
        }
    }
    @Volatile var showRecorderTouches: Boolean = false

    init {
        setWillNotDraw(true)
        isMotionEventSplittingEnabled = true
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        if (frameVoter != null) {
            addView(frameVoter, LayoutParams(1, 1))
        }
        addView(geckoView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        HighRefreshRateEnforcer.applyToViewTree(this)
        lastAppliedRate = HighRefreshRateEnforcer.targetRefreshRate
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
        val mirrorEv = if (ev != null && shouldMirrorToGecko()) MotionEvent.obtain(ev) else null
        val handled = super.dispatchTouchEvent(ev)
        if (mirrorEv != null) {
            try {
                geckoView.dispatchTouchEvent(mirrorEv)
            } finally {
                mirrorEv.recycle()
            }
        }
        return handled
    }

    private fun shouldMirrorToGecko(): Boolean {
        val tab = boundTab ?: return false
        return tab.isHybridBlink.value &&
            tab.mirrorInputToGecko.value &&
            geckoView.visibility == View.VISIBLE &&
            geckoView.alpha > 0f &&
            geckoView.session?.isOpen == true &&
            blinkView != null &&
            authPopupOverlay == null &&
            customFullScreenView == null
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        val mirrorEv = if (ev != null && shouldMirrorToGecko()) MotionEvent.obtain(ev) else null
        val handled = super.dispatchGenericMotionEvent(ev)
        if (mirrorEv != null) {
            try {
                geckoView.dispatchGenericMotionEvent(mirrorEv)
            } finally {
                mirrorEv.recycle()
            }
        }
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val mirrorKey = if (
            event != null &&
            event.keyCode != KeyEvent.KEYCODE_BACK &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
            shouldMirrorToGecko()
        ) {
            KeyEvent(event)
        } else {
            null
        }
        val handled = super.dispatchKeyEvent(event)
        if (mirrorKey != null) {
            runCatching { geckoView.dispatchKeyEvent(mirrorKey) }
        }
        return handled
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
            val pct = tab.currentTextZoomPercent.coerceIn(50, 200)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val zVal = if (pct == 100) "" else "${pct / 100.0}"
                    retained.evaluateJavascript(
                        "if(window.__fableSetPageZoom){window.__fableSetPageZoom($pct);}else if(document.documentElement){document.documentElement.style.zoom='$zVal';}",
                        null
                    )
                }
                tab.hybridTimeScaleSyncHandler?.invoke()
            }
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

            private fun currentGeckoIc(): InputConnection? {
                if (!shouldMirrorToGecko()) return null
                return runCatching {
                    geckoView.session?.textInput?.onCreateInputConnection(EditorInfo())
                }.getOrNull()
            }

            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val base = super.onCreateInputConnection(outAttrs) ?: return null
                return object : InputConnectionWrapper(base, true) {
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        if (shouldMirrorToGecko() && !text.isNullOrEmpty()) {
                            runCatching {
                                val ic = currentGeckoIc()
                                if (ic != null) {
                                    ic.commitText(text, newCursorPosition)
                                } else {
                                    val now = SystemClock.uptimeMillis()
                                    geckoView.dispatchKeyEvent(KeyEvent(now, text.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
                                }
                            }
                        }
                        return super.commitText(text, newCursorPosition)
                    }

                    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        if (shouldMirrorToGecko()) {
                            runCatching { currentGeckoIc()?.setComposingText(text, newCursorPosition) }
                        }
                        return super.setComposingText(text, newCursorPosition)
                    }

                    override fun finishComposingText(): Boolean {
                        if (shouldMirrorToGecko()) {
                            runCatching { currentGeckoIc()?.finishComposingText() }
                        }
                        return super.finishComposingText()
                    }

                    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                        if (shouldMirrorToGecko()) {
                            runCatching {
                                val ic = currentGeckoIc()
                                if (ic != null) {
                                    ic.deleteSurroundingText(beforeLength, afterLength)
                                } else if (beforeLength > 0) {
                                    val now = SystemClock.uptimeMillis()
                                    repeat(beforeLength.coerceAtMost(8)) {
                                        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
                                        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
                                    }
                                }
                            }
                        }
                        return super.deleteSurroundingText(beforeLength, afterLength)
                    }

                    override fun sendKeyEvent(event: KeyEvent?): Boolean {
                        if (event != null && event.keyCode != KeyEvent.KEYCODE_BACK && shouldMirrorToGecko()) {
                            runCatching { geckoView.dispatchKeyEvent(KeyEvent(event)) }
                        }
                        return super.sendKeyEvent(event)
                    }

                    override fun performEditorAction(editorAction: Int): Boolean {
                        if (shouldMirrorToGecko()) {
                            runCatching { currentGeckoIc()?.performEditorAction(editorAction) }
                        }
                        return super.performEditorAction(editorAction)
                    }
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
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = 100
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                runCatching { settings.offscreenPreRaster = true }
            }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = tab.isDesktopMode.value
            settings.javaScriptCanOpenWindowsAutomatically = !tab.isIncognito
            settings.setSupportMultipleWindows(!tab.isIncognito)
            settings.cacheMode = if (tab.isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
            }
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val decision = tab.hybridPrivacyProvider?.invoke(tab.url.value)
                val allow3p = if (decision != null && decision.blinkPrivacyParity) {
                    !decision.blockThirdPartyCookies
                } else {
                    !tab.isIncognito
                }
                cm.setAcceptThirdPartyCookies(this, allow3p)
            }
            addJavascriptInterface(FableHybridJsBridge(context.applicationContext, tab), "FableHybridBridge")
            val initBoot = tab.hybridBootstrapProvider?.invoke(tab.url.value).orEmpty()
            if (initBoot.isNotEmpty()) {
                com.fable.browser.engine.FableWebGpuManager.installDocumentStartBootstrap(this, initBoot)
            }
            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                val cur = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                tab.updateFindMatch(FindMatch(cur, numberOfMatches))
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (!url.isNullOrBlank()) {
                    if (url.startsWith("blob:", ignoreCase = true) || url.startsWith("data:", ignoreCase = true)) {
                        val qUrl = org.json.JSONObject.quote(url)
                        val qName = org.json.JSONObject.quote(FableUnifiedEngine.guessDownloadFileName(url, contentDisposition, mimeType))
                        val qMime = org.json.JSONObject.quote(mimeType ?: "application/octet-stream")
                        post {
                            evaluateJavascript(
                                "if(window.__fableDownloadUrl){window.__fableDownloadUrl($qUrl,$qName,$qMime);}else{fetch($qUrl).then(r=>r.blob()).then(b=>{const fr=new FileReader();fr.onloadend=()=>{const s=String(fr.result||'');const i=s.indexOf(',');if(i>=0)window.FableHybridBridge.saveBlobBase64(s.slice(i+1),$qName,$qMime);};fr.readAsDataURL(b);});}",
                                null
                            )
                        }
                    } else {
                        tab.hybridDownloadHandler?.invoke(url, contentDisposition, mimeType, userAgent)
                    }
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
                    val fullReqUrl = request.url?.toString().orEmpty()
                    if (fullReqUrl.startsWith("https://local.fable.app/opened_html/", ignoreCase = true)) {
                        return runCatching {
                            val relRaw = fullReqUrl.substringAfter("/opened_html/").substringBefore('?').substringBefore('#')
                            val relDecoded = android.net.Uri.decode(relRaw)
                            val baseRoot = java.io.File(context.cacheDir, "opened_html").canonicalFile
                            val targetFile = java.io.File(baseRoot, relDecoded).canonicalFile
                            if (!targetFile.path.startsWith(baseRoot.path + java.io.File.separator) || !targetFile.isFile) {
                                return@runCatching WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            }
                            val ext = targetFile.extension.lowercase()
                            val mime = when (ext) {
                                "html", "htm" -> "text/html"
                                "xhtml" -> "application/xhtml+xml"
                                "js", "mjs" -> "application/javascript"
                                "css" -> "text/css"
                                "json" -> "application/json"
                                "svg" -> "image/svg+xml"
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "webp" -> "image/webp"
                                "mp4" -> "video/mp4"
                                "webm" -> "video/webm"
                                else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/html"
                            }
                            WebResourceResponse(
                                mime,
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                targetFile.inputStream().buffered(64 * 1024)
                            )
                        }.getOrNull()
                    }
                    val reqUrl = request.url?.toString() ?: return null
                    if (reqUrl.startsWith("http://", true) || reqUrl.startsWith("https://", true)) {
                        tab.recordObservedBlinkUrl(reqUrl, request.method ?: "GET")
                    }
                    if (request.isForMainFrame) return null
                    val docHost = cachedDocHost
                        ?: UrlUtils.host(tab.url.value)
                        ?: UrlUtils.host(tab.lastLoadedBlinkUrl)
                    val reqHost = UrlUtils.host(reqUrl)
                    if (docHost != null && reqHost != null && UrlUtils.isSameSite(reqHost, docHost)) return null
                    if (reqHost != null && (
                        reqHost.endsWith("googleapis.com") ||
                        reqHost.endsWith("accounts.google.com") ||
                        reqHost.endsWith("apis.google.com") ||
                        reqHost.endsWith("firebaseapp.com") ||
                        reqHost.endsWith("gstatic.com") ||
                        reqHost.endsWith("challenges.cloudflare.com") ||
                        reqHost.endsWith("discord.com") ||
                        reqHost.endsWith("auth0.com") ||
                        reqHost.endsWith("microsoftonline.com") ||
                        reqHost.endsWith("live.com") ||
                        reqHost.endsWith("apple.com")
                    )) return null
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
                    val decision = tab.hybridPrivacyProvider?.invoke(target)
                    if (decision != null && decision.blinkPrivacyParity && decision.httpsOnly &&
                        request.isForMainFrame && UrlUtils.isInsecure(target) &&
                        !UrlUtils.isLoopback(target) &&
                        !tab.isHttpAllowed(UrlUtils.host(target))
                    ) {
                        tab.updateInterstitial(com.fable.browser.engine.Interstitial.HttpsOnly(target))
                        tab.updateLoading(false)
                        return true
                    }
                    val stripOn = decision != null && decision.blinkPrivacyParity && decision.stripTrackingParams
                    if (!request.isRedirect && stripOn) {
                        val cleaned = UrlUtils.stripTrackingParams(target)
                        if (cleaned != target && request.isForMainFrame && request.method.equals("GET", ignoreCase = true)) {
                            val extraHeaders = tab.hybridExtraHeadersProvider?.invoke(cleaned).orEmpty()
                            view.post {
                                tab.lastLoadedBlinkUrl = cleaned
                                if (extraHeaders.isNotEmpty()) view.loadUrl(cleaned, extraHeaders) else view.loadUrl(cleaned)
                            }
                            return true
                        }
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) {
                        com.fable.browser.engine.PerformanceTelemetry.markNavigationStart(url, "Blink")
                        runCatching {
                            val dec = tab.hybridPrivacyProvider?.invoke(url)
                            val allow3p = if (dec != null && dec.blinkPrivacyParity) !dec.blockThirdPartyCookies else !tab.isIncognito
                            CookieManager.getInstance().setAcceptThirdPartyCookies(view, allow3p)
                        }
                        tab.observedBlinkUrls.clear()
                        tab.recordObservedBlinkUrl(url, "GET")
                        tab.lastLoadedBlinkUrl = url
                        cachedDocHost = UrlUtils.host(url)
                        tab.updateUrl(url)
                        tab.updateSecure(!UrlUtils.isInsecure(url))
                        tab.resetBlocked()
                        tab.hybridCookieSyncHandler?.invoke(url)
                    }
                    NativeFastEngine.setGameBoost(true)
                    HighRefreshRateEnforcer.applyToViewTree(view)
                    tab.updateLoading(true)
                    tab.updateCanGoBack(view.canGoBack())
                    tab.updateCanGoForward(view.canGoForward())
                    val boot = tab.hybridBootstrapProvider?.invoke(url.orEmpty()).orEmpty()
                    if (boot.isNotEmpty()) {
                        com.fable.browser.engine.FableWebGpuManager.installDocumentStartBootstrap(view, boot)
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
                    val zPct = tab.currentTextZoomPercent.coerceIn(50, 200)
                    if (zPct != 100) {
                        runCatching {
                            val zVal = "${zPct / 100.0}"
                            view.evaluateJavascript(
                                "if(window.__fableSetPageZoom){window.__fableSetPageZoom($zPct);}else if(document.documentElement){document.documentElement.style.zoom='$zVal';}",
                                null
                            )
                        }
                    }
                    if (!url.isNullOrBlank()) {
                        com.fable.browser.engine.PerformanceTelemetry.markPageLoaded(url, "Blink")
                    }
                    FableUnifiedEngine.scheduleCookieFlush(immediate = false)
                    if (!url.isNullOrBlank()) tab.hybridCookieSyncHandler?.invoke(url)
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

                @SuppressLint("SetJavaScriptEnabled")
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (!isUserGesture || resultMsg == null || tab.isIncognito) return false
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    closeAuthPopup()
                    val wrapper = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setBackgroundColor(Color.parseColor("#111827"))
                        isClickable = true
                        isFocusable = true
                    }
                    val topBar = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        setBackgroundColor(Color.parseColor("#1F2937"))
                        val pad = (10 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val titleTv = android.widget.TextView(context).apply {
                        text = "Sign in"
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val closeBtn = android.widget.TextView(context).apply {
                        text = "✕ Close"
                        setTextColor(Color.parseColor("#93C5FD"))
                        textSize = 14f
                        val hPad = (12 * resources.displayMetrics.density).toInt()
                        setPadding(hPad, 0, hPad, 0)
                        setOnClickListener { closeAuthPopup() }
                    }
                    topBar.addView(titleTv)
                    topBar.addView(closeBtn)

                    val popupWv = WebView(MutableContextWrapper(context)).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        val cleanUa = FableUnifiedEngine.defaultBlinkUserAgent.ifBlank {
                            settings.userAgentString.replace("; wv)", ")").replace("Version/4.0 ", "")
                        }
                        if (cleanUa.isNotBlank()) settings.userAgentString = cleanUa
                        runCatching {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, u: String?, fav: Bitmap?) {
                                super.onPageStarted(v, u, fav)
                                if (!u.isNullOrBlank()) titleTv.text = UrlUtils.host(u) ?: u
                            }
                            override fun onPageFinished(v: WebView?, u: String?) {
                                super.onPageFinished(v, u)
                                FableUnifiedEngine.flushBlinkCookies()
                                if (!u.isNullOrBlank()) tab.hybridCookieSyncHandler?.invoke(u)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                closeAuthPopup()
                            }
                        }
                    }
                    wrapper.addView(topBar, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    wrapper.addView(popupWv, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    authPopupWebView = popupWv
                    authPopupOverlay = wrapper
                    addView(wrapper, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    transport.webView = popupWv
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    closeAuthPopup()
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
        val desktopBlinkUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        val applyDesktopState: (Boolean) -> Unit = { desk ->
            val targetUa = if (desk) {
                desktopBlinkUa
            } else {
                tab.hybridCustomUserAgent?.invoke()?.takeIf { it.isNotBlank() } ?: rawDefaultUa
            }
            if (targetUa.isNotBlank()) {
                runCatching { wv.settings.userAgentString = targetUa }
            }
            wv.settings.useWideViewPort = true
            wv.settings.loadWithOverviewMode = desk
            wv.setInitialScale(0)
        }
        val applyZoomState: (Int) -> Unit = { rawPct ->
            val pct = rawPct.coerceIn(50, 200)
            wv.settings.textZoom = 100
            val zoomJs = if (pct == 100) {
                "if(window.__fableSetPageZoom){window.__fableSetPageZoom(100);}else if(document.documentElement){document.documentElement.style.zoom='';}"
            } else {
                "if(window.__fableSetPageZoom){window.__fableSetPageZoom($pct);}else if(document.documentElement){document.documentElement.style.zoom='${pct / 100.0}';}"
            }
            runCatching { wv.evaluateJavascript(zoomJs, null) }
        }
        applyDesktopState(tab.isDesktopMode.value)
        val controller = object : HybridBlinkController {
            override fun loadUrl(url: String) {
                wv.post {
                    val effectiveUrl = if (url.startsWith("file://", ignoreCase = true) && url.contains("/cache/opened_html/")) {
                        val rel = url.substringAfter("/cache/opened_html/")
                        "https://local.fable.app/opened_html/$rel"
                    } else {
                        url
                    }
                    applyDesktopState(tab.isDesktopMode.value)
                    tab.lastLoadedBlinkUrl = effectiveUrl
                    val headers = tab.hybridExtraHeadersProvider?.invoke(effectiveUrl).orEmpty()
                    if (headers.isNotEmpty()) wv.loadUrl(effectiveUrl, headers) else wv.loadUrl(effectiveUrl)
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
                    applyDesktopState(desktop)
                    val cur = (wv.url ?: tab.lastLoadedBlinkUrl).ifBlank { tab.url.value }
                    val headers = tab.hybridExtraHeadersProvider?.invoke(cur).orEmpty()
                    if (cur.startsWith("http://") || cur.startsWith("https://")) {
                        tab.lastLoadedBlinkUrl = cur
                        if (headers.isNotEmpty()) wv.loadUrl(cur, headers) else wv.loadUrl(cur)
                    } else {
                        wv.reload()
                    }
                }
            }

            override fun setTextZoom(percent: Int) {
                val clamped = percent.coerceIn(50, 200)
                tab.currentTextZoomPercent = clamped
                wv.post { applyZoomState(clamped) }
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

            override fun savePageCode(mode: String, reqId: String, continuous: Boolean): Boolean {
                val script = tab.hybridArchiveScriptProvider?.invoke(reqId, mode, continuous) ?: return false
                wv.post {
                    runCatching { wv.evaluateJavascript(script, null) }
                }
                return true
            }

            override fun applyTimeScale(enabled: Boolean, rate: Float, css: Boolean, media: Boolean, workers: Boolean) {
                val script = com.fable.browser.engine.WebsiteSpeedEngine.buildLiveUpdateScript(
                    enabled = enabled,
                    rate = rate,
                    applyCss = css,
                    applyMedia = media,
                    applyWorkers = workers
                )
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        val freshBoot = tab.hybridBootstrapProvider?.invoke(wv.url ?: tab.url.value).orEmpty()
                        com.fable.browser.engine.FableWebGpuManager.installDocumentStartBootstrap(wv, freshBoot)
                    }
                    if (script.isNotBlank()) {
                        runCatching { wv.evaluateJavascript(script, null) }
                    }
                }
            }

            override suspend fun runMemoryCommand(cmdJson: String): String {
                val cmdObj = runCatching { org.json.JSONObject(cmdJson) }.getOrNull()
                val op = cmdObj?.optString("op").orEmpty()
                val maxRet = cmdObj?.optInt("maxReturn", 150) ?: 150
                val targetFrame = cmdObj?.optString("targetFrame").orEmpty()
                val reqId = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                val collector = java.util.concurrent.CopyOnWriteArrayList<String>()
                FableHybridJsBridge.pendingFrameMemCollectors[reqId] = collector
                try {
                    val script = com.fable.browser.engine.WebsiteMemoryEngine.buildBlinkCommandScript(cmdJson, reqId)
                    val topRaw = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                        kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                runCatching {
                                    wv.evaluateJavascript(script) { raw ->
                                        if (!cont.isActive) return@evaluateJavascript
                                        val unquoted = if (raw.isNullOrBlank() || raw == "null") {
                                            "{\"ok\":false,\"error\":\"Empty page response\"}"
                                        } else {
                                            runCatching { org.json.JSONTokener(raw).nextValue()?.toString() }.getOrNull() ?: raw
                                        }
                                        cont.resumeWith(Result.success(unquoted))
                                    }
                                }.onFailure {
                                    if (cont.isActive) {
                                        cont.resumeWith(Result.success("{\"ok\":false,\"error\":\"WebView eval failed\"}"))
                                    }
                                }
                            }
                        }
                    } ?: return "{\"ok\":false,\"error\":\"Blink scan timed out\"}"
                    val topObj = runCatching { org.json.JSONObject(topRaw) }.getOrNull()
                    val childFrames = topObj?.optInt("childFrames", 0) ?: 0
                    val needsSubframes = childFrames > 0 || (targetFrame.isNotBlank() && targetFrame != "f0")
                    if (needsSubframes) {
                        val waitMs = when (op) {
                            "firstScan" -> 480L
                            "nextScan", "writeAll", "regions" -> 260L
                            else -> 140L
                        }
                        val deadline = System.currentTimeMillis() + waitMs
                        while (System.currentTimeMillis() < deadline) {
                            if (targetFrame.isNotBlank() && targetFrame != "f0" && collector.isNotEmpty()) break
                            kotlinx.coroutines.delay(20L)
                        }
                    }
                    val allPayloads = ArrayList<String>(1 + collector.size)
                    if (topObj == null || !topObj.optBoolean("skippedFrame", false)) {
                        allPayloads.add(topRaw)
                    }
                    allPayloads.addAll(collector)
                    return com.fable.browser.engine.WebsiteMemoryEngine.mergeMultiFrameResults(allPayloads, op, maxRet)
                } finally {
                    FableHybridJsBridge.pendingFrameMemCollectors.remove(reqId)
                }
            }

            override fun resetMemoryEngine() {
                val resetJs = com.fable.browser.engine.WebsiteMemoryEngine.buildBlinkResetIfInstalledScript()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        val curSettings = tab.hybridSettingsProvider?.invoke()
                        val freshBoot = tab.hybridBootstrapProvider?.invoke(wv.url ?: tab.url.value).orEmpty()
                        com.fable.browser.engine.FableWebGpuManager.installDocumentStartBootstrap(wv, freshBoot)
                        if (curSettings?.memoryEngineEnabled == true) {
                            val memBoot = com.fable.browser.engine.WebsiteMemoryEngine.buildBootstrapScript(curSettings)
                            if (memBoot.isNotBlank()) wv.evaluateJavascript(memBoot, null)
                        } else {
                            wv.evaluateJavascript(resetJs, null)
                        }
                    }
                }
            }

            override suspend fun exportSiteStorage(): String {
                val js = """
                (function() {
                    try {
                        var orig = location.origin || "";
                        if (!orig || orig === "null") return "";
                        var ls = {}, ss = {};
                        var c1 = 0, c2 = 0;
                        for (var i = 0; i < localStorage.length && c1 < 200; i++) {
                            var k = localStorage.key(i);
                            if (!k || k.indexOf("__fable_") === 0) continue;
                            var v = localStorage.getItem(k);
                            if (typeof v === "string" && v.length <= 32768) { ls[k] = v; c1++; }
                        }
                        for (var j = 0; j < sessionStorage.length && c2 < 100; j++) {
                            var k2 = sessionStorage.key(j);
                            if (!k2 || k2.indexOf("__fable_") === 0) continue;
                            var v2 = sessionStorage.getItem(k2);
                            if (typeof v2 === "string" && v2.length <= 32768) { ss[k2] = v2; c2++; }
                        }
                        if (c1 === 0 && c2 === 0) return "";
                        return JSON.stringify({ origin: orig, ls: ls, ss: ss });
                    } catch (_) { return ""; }
                })();
                """.trimIndent()
                return kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                wv.evaluateJavascript(js) { raw ->
                                    if (!cont.isActive) return@evaluateJavascript
                                    val unquoted = if (raw.isNullOrBlank() || raw == "null") "" else {
                                        runCatching { org.json.JSONTokener(raw).nextValue()?.toString() }.getOrNull().orEmpty()
                                    }
                                    cont.resumeWith(Result.success(unquoted))
                                }
                            }.onFailure {
                                if (cont.isActive) cont.resumeWith(Result.success(""))
                            }
                        }
                    }
                }.orEmpty()
            }

            override fun applyRestoredSiteStorage(jsonPayload: String) {
                val q = org.json.JSONObject.quote(jsonPayload)
                val js = """
                (function() {
                    try {
                        var d = JSON.parse($q);
                        if (d.ls) { Object.keys(d.ls).forEach(function(k) { if (typeof d.ls[k] === "string") localStorage.setItem(k, d.ls[k]); }); }
                        if (d.ss) { Object.keys(d.ss).forEach(function(k) { if (typeof d.ss[k] === "string") sessionStorage.setItem(k, d.ss[k]); }); }
                    } catch (_) {}
                })();
                """.trimIndent()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { wv.evaluateJavascript(js, null) }
                }
            }

            override fun exitFullScreen() {
                wv.post {
                    if (customFullScreenView != null) {
                        wv.webChromeClient?.onHideCustomView()
                    }
                    runCatching {
                        wv.evaluateJavascript(
                            "try{if(document.fullscreenElement&&document.exitFullscreen){document.exitFullscreen();}else if(document.webkitFullscreenElement&&document.webkitExitFullscreen){document.webkitExitFullscreen();}}catch(_){}",
                            null
                        )
                    }
                    tab.updateFullScreen(false)
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
        closeAuthPopup()
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
    val sessionState by tab.sessionFlow.collectAsStateWithLifecycle()
    val hybridBlinkState by tab.isHybridBlink.collectAsStateWithLifecycle()
    val mirrorInputState by tab.mirrorInputToGecko.collectAsStateWithLifecycle()
    val currentUrlState by tab.url.collectAsStateWithLifecycle()
    var attachTick by remember(tab.id) { mutableIntStateOf(0) }

    // Always use tab's synchronous current state to prevent 1-frame stale StateFlow leak across tab switches
    val session = tab.session
    val hybridBlink = tab.isHybridBlink.value
    val mirrorInput = tab.mirrorInputToGecko.value
    val currentUrl = tab.url.value
    // Reference collected states so Compose subscribes to updates on the current tab
    @Suppress("UNUSED_EXPRESSION")
    if (sessionState !== session && hybridBlinkState != hybridBlink && mirrorInputState != mirrorInput && currentUrlState != currentUrl) Unit

    LaunchedEffect(tab.id, session) {
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
            val desiredRate = HighRefreshRateEnforcer.targetRefreshRate
            if (container.lastAppliedRate != desiredRate) {
                container.lastAppliedRate = desiredRate
                HighRefreshRateEnforcer.applyToViewTree(container, desiredRate)
            }
            val target = session
            val boostOn = hybridBlink && !tab.ecoBoostDisabled && !HybridEngineBridge.isBenchmarkUrl(currentUrl)
            FableUnifiedEngine.setFrameMonitorActive(boostOn)
            if (container.lastAppliedBoost != boostOn) {
                container.lastAppliedBoost = boostOn
                NativeFastEngine.setGameBoost(boostOn)
            }
            if (hybridBlink) {
                if (mirrorInput) {
                    if (attachTick >= 0 && gv.session !== target && target.isOpen) {
                        if (gv.session != null) gv.releaseSession()
                        gv.setSession(target)
                    }
                    if (target.isOpen) {
                        runCatching { target.setActive(true) }
                    }
                    gv.isFocusable = false
                    gv.isFocusableInTouchMode = false
                    gv.alpha = 1f
                    gv.visibility = View.VISIBLE
                } else {
                    gv.isFocusable = false
                    gv.isFocusableInTouchMode = false
                    if (tab.hibernateGeckoWhenHybrid) {
                        if (target.isOpen) {
                            runCatching { target.setActive(false) }
                        }
                        if (gv.session != null) {
                            runCatching { gv.releaseSession() }
                        }
                    } else if (attachTick >= 0 && gv.session !== target && target.isOpen) {
                        if (gv.session != null) gv.releaseSession()
                        gv.setSession(target)
                    }
                    // Keep INVISIBLE + alpha 0f instead of GONE during traversal so ViewRootImpl.relayoutWindow
                    // never blocks in BLASTBufferQueue.nativeDestroy while GeckoThread is active.
                    gv.alpha = 0f
                    if (gv.visibility != View.INVISIBLE) {
                        gv.visibility = View.INVISIBLE
                    }
                }
                val wv = container.ensureBlinkView(tab)
                wv.tag = if (tab.isIncognito) "incognito" else "normal"
                wv.visibility = View.VISIBLE
                if (mirrorInput && container.indexOfChild(wv) < container.indexOfChild(gv)) {
                    wv.bringToFront()
                }
                captureHandle.blinkView = wv
                if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://") || currentUrl.startsWith("file://")) {
                    val mappedUrl = if (currentUrl.startsWith("file://", ignoreCase = true) && currentUrl.contains("/cache/opened_html/")) {
                        "https://local.fable.app/opened_html/" + currentUrl.substringAfter("/cache/opened_html/")
                    } else {
                        currentUrl
                    }
                    val currentWvUrl = wv.url
                    if (tab.lastLoadedBlinkUrl.isBlank() && (currentWvUrl.isNullOrBlank() || currentWvUrl == "about:blank")) {
                        tab.hybridCookiePullHandler?.invoke(mappedUrl)
                        tab.lastLoadedBlinkUrl = mappedUrl
                        val headers = tab.hybridExtraHeadersProvider?.invoke(mappedUrl).orEmpty()
                        if (headers.isNotEmpty()) wv.loadUrl(mappedUrl, headers) else wv.loadUrl(mappedUrl)
                    }
                }
            } else {
                gv.isFocusable = true
                gv.isFocusableInTouchMode = true
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
                gv.alpha = 1f
                gv.visibility = View.VISIBLE
            }
        },
        onRelease = { container ->
            FableUnifiedEngine.setFrameMonitorActive(false)
            NativeFastEngine.setGameBoost(false)
            if (captureHandle.view === container.geckoView) captureHandle.view = null
            captureHandle.blinkView = null
            captureHandle.hybridActive = false
            container.detachBlinkView()
            runCatching {
                container.geckoView.session?.setActive(false)
                if (container.geckoView.session != null) container.geckoView.releaseSession()
            }
        }
    )
}
