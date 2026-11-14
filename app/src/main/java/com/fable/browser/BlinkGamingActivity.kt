package com.fable.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fable.browser.data.prefs.Settings
import com.fable.browser.engine.FableUnifiedEngine
import com.fable.browser.engine.HybridEngineBridge
import com.fable.browser.engine.NativeFastEngine
import com.fable.browser.util.HighRefreshRateEnforcer
import com.fable.browser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class BlinkGamingActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var customContainer: FrameLayout? = null
    @Volatile private var jsFullScreenActive: Boolean = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gameAccountVaultPrefs by lazy {
        applicationContext.getSharedPreferences("fable_game_account_vault", Context.MODE_PRIVATE)
    }

    private data class StreamingJsDl(
        val file: File,
        val out: BufferedOutputStream,
        val fileName: String,
        val mimeType: String
    )
    private val activeJsDownloads = ConcurrentHashMap<String, StreamingJsDl>()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
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
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onHeavyBurst() {
                        NativeFastEngine.notifyHeavyLoadBurst()
                    }

                    @JavascriptInterface
                    fun onJsFullScreenChanged(active: Boolean) {
                        jsFullScreenActive = active
                    }

                    @JavascriptInterface
                    fun readAccountVault(host: String?): String {
                        val h = host?.takeIf { it.isNotBlank() } ?: return ""
                        val canonical = UrlUtils.registrableDomain(h) ?: h.lowercase().removePrefix("www.")
                        val key = if (h.startsWith("staging.", ignoreCase = true)) "staging.$canonical" else canonical
                        return gameAccountVaultPrefs.getString("ls_$key", "").orEmpty()
                    }

                    @JavascriptInterface
                    fun writeAccountVault(host: String?, jsonStr: String?) {
                        val h = host?.takeIf { it.isNotBlank() } ?: return
                        val p = jsonStr?.takeIf { it.length in 3..524288 } ?: return
                        val canonical = UrlUtils.registrableDomain(h) ?: h.lowercase().removePrefix("www.")
                        val key = if (h.startsWith("staging.", ignoreCase = true)) "staging.$canonical" else canonical
                        gameAccountVaultPrefs.edit().putString("ls_$key", p).apply()
                    }

                    @JavascriptInterface
                    fun beginJsDownload(dlId: String?, fileName: String?, mimeType: String?) {
                        val id = dlId?.takeIf { it.isNotBlank() } ?: return
                        val dir = File(cacheDir, "blink_dl").apply { mkdirs() }
                        val tmp = File(dir, "bgdl_${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.part")
                        val out = BufferedOutputStream(FileOutputStream(tmp), 256 * 1024)
                        activeJsDownloads[id] = StreamingJsDl(
                            tmp,
                            out,
                            (fileName?.takeIf { it.isNotBlank() } ?: "download").replace('"', '_'),
                            mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        )
                    }

                    @JavascriptInterface
                    fun appendJsDownloadChunk(dlId: String?, chunkB64: String?) {
                        val st = activeJsDownloads[dlId ?: return] ?: return
                        val raw = chunkB64?.takeIf { it.isNotEmpty() } ?: return
                        runCatching {
                            val bytes = NativeFastEngine.decodeBase64(raw)
                            if (bytes.isNotEmpty()) synchronized(st) { st.out.write(bytes) }
                        }
                    }

                    @JavascriptInterface
                    fun finishJsDownload(dlId: String?) {
                        val st = activeJsDownloads.remove(dlId ?: return) ?: return
                        ioScope.launch {
                            runCatching {
                                synchronized(st) { st.out.flush(); st.out.close() }
                                publishFileToDownloads(st.file, st.fileName, st.mimeType)
                            }
                            st.file.delete()
                        }
                    }

                    @JavascriptInterface
                    fun saveBlobBase64(b64: String?, fileName: String?, mimeType: String?) {
                        val raw = b64?.trim() ?: return
                        val safeName = (fileName?.takeIf { it.isNotBlank() } ?: "download").replace('"', '_')
                        val safeMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        ioScope.launch {
                            val tmp = File(cacheDir, "bgblob_${System.currentTimeMillis()}.bin")
                            runCatching {
                                tmp.writeBytes(if (raw.isNotEmpty()) NativeFastEngine.decodeBase64(raw) else ByteArray(0))
                                publishFileToDownloads(tmp, safeName, safeMime)
                            }
                            tmp.delete()
                        }
                    }

                    @JavascriptInterface
                    fun enqueueHttpDownload(url: String?, suggestedName: String?) {
                        val u = url?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
                        downloadHttpDirect(u, suggestedName, null)
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
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (!url.isNullOrBlank()) {
                    val fName = FableUnifiedEngine.guessDownloadFileName(url, contentDisposition, mimeType)
                    if (url.startsWith("blob:", true) || url.startsWith("data:", true)) {
                        val qUrl = JSONObject.quote(url)
                        val qName = JSONObject.quote(fName)
                        val qMime = JSONObject.quote(mimeType ?: "application/octet-stream")
                        post {
                            evaluateJavascript("if(window.__fableDownloadUrl)window.__fableDownloadUrl($qUrl,$qName,$qMime);", null)
                        }
                    } else {
                        downloadHttpDirect(url, fName, userAgent)
                    }
                }
            }
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
                    customCallback = callback
                    customContainer = root
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    exitPillView?.bringToFront()
                    this@BlinkGamingActivity.hideSystemUi()
                }

                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                    customCallback?.onCustomViewHidden()
                    customCallback = null
                    customContainer = null
                    this@BlinkGamingActivity.hideSystemUi()
                }
            }
        }
        webView = view
        root.addView(HighRefreshRateEnforcer.createFrameRateVoterSurfaceView(this), FrameLayout.LayoutParams(1, 1))
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val exitPill = createExitFullScreenPill()
        exitPillView = exitPill
        val dp = resources.displayMetrics.density
        val pillLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = (6f * dp).toInt()
        }
        root.addView(exitPill, pillLp)

        HighRefreshRateEnforcer.applyToViewTree(root)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitOrCloseBlinkGaming(preferCloseMode = false)
            }
        })

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.startsWith("https://") || url.startsWith("http://")) {
            FableUnifiedEngine.preconnectOriginAsync(url)
            FableUnifiedEngine.setFrameMonitorActive(true)
            view.loadUrl(url, mapOf("DNT" to "1", "Sec-GPC" to "1"))
        }
    }

    private var exitPillView: View? = null

    private fun createExitFullScreenPill(): View {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = "✕ Exit Fullscreen"
            setTextColor(0xE0FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding((10f * dp).toInt(), (4f * dp).toInt(), (10f * dp).toInt(), (4f * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * dp
                setColor(0x78111827)
            }
            contentDescription = "Exit full screen"
            setOnClickListener {
                exitOrCloseBlinkGaming(preferCloseMode = true)
            }
        }
    }

    private fun exitOrCloseBlinkGaming(preferCloseMode: Boolean) {
        val active = webView
        if (customView != null) {
            active?.webChromeClient?.onHideCustomView()
            return
        }
        if (jsFullScreenActive && active != null) {
            jsFullScreenActive = false
            runCatching {
                active.evaluateJavascript(
                    "try{if(document.fullscreenElement&&document.exitFullscreen){document.exitFullscreen();}else if(document.webkitFullscreenElement&&document.webkitExitFullscreen){document.webkitExitFullscreen();}}catch(_){}",
                    null
                )
            }
            if (!preferCloseMode) return
        }
        if (!preferCloseMode && active?.canGoBack() == true) {
            active.goBack()
            return
        }
        FableUnifiedEngine.flushBlinkCookies()
        finish()
    }

    private fun downloadHttpDirect(url: String, suggestedName: String?, uaHint: String?) {
        ioScope.launch {
            val tmp = File(cacheDir, "bghttp_${System.currentTimeMillis()}.part")
            runCatching {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                val ua = uaHint?.takeIf { it.isNotBlank() } ?: FableUnifiedEngine.defaultBlinkUserAgent
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                    if (ua.isNotBlank()) setRequestProperty("User-Agent", ua)
                    connect()
                }
                if (conn.responseCode in 200..299) {
                    val mime = conn.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
                    val fName = suggestedName?.takeIf { it.isNotBlank() }
                        ?: FableUnifiedEngine.guessDownloadFileName(url, conn.getHeaderField("Content-Disposition"), mime)
                    conn.inputStream.use { inp ->
                        FileOutputStream(tmp).use { out -> inp.copyTo(out, 256 * 1024) }
                    }
                    publishFileToDownloads(tmp, fName, mime)
                }
                conn.disconnect()
            }
            tmp.delete()
        }
    }

    private fun publishFileToDownloads(srcFile: File, rawName: String, mimeType: String) {
        val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                srcFile.inputStream().use { inp -> inp.copyTo(out, 256 * 1024) }
            }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            runOnUiThread {
                Toast.makeText(this, "Saved to Downloads/$safeName", Toast.LENGTH_SHORT).show()
            }
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
        ioScope.cancel()
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
