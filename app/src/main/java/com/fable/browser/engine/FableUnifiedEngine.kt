package com.fable.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import com.fable.browser.data.prefs.Settings
import com.fable.browser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class UnifiedEngineOwner {
    GECKO_PRIVACY_CORE,
    BLINK_3D_HYPER_CORE,
    UNIFIED_COORDINATED
}

data class UnifiedEngineStatus(
    val owner: UnifiedEngineOwner = UnifiedEngineOwner.UNIFIED_COORDINATED,
    val estimatedFps: Int = 120,
    val nativeCppActive: Boolean = NativeFastEngine.isNativeAvailable,
    val blinkPrewarmed: Boolean = false,
    val frameMonitorActive: Boolean = false,
    val webGpuPipelineCache: Boolean = true,
    val webGlAdaptiveStateCache: Boolean = true,
    val wasmModuleCache: Boolean = true
)

object FableUnifiedEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prewarmed = AtomicBoolean(false)
    private val dnsWarmCache: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 64
        }
    )

    @Volatile
    var defaultBlinkUserAgent: String = ""
        private set

    private val _estimatedFps = MutableStateFlow(120)
    val estimatedFps: StateFlow<Int> = _estimatedFps.asStateFlow()

    private val _status = MutableStateFlow(UnifiedEngineStatus())
    val status: StateFlow<UnifiedEngineStatus> = _status.asStateFlow()

    @Volatile
    private var monitorActive = false
    private var lastFrameNs = 0L
    private var uiPublishCounter = 0

    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!monitorActive) return
            val prev = lastFrameNs
            lastFrameNs = frameTimeNanos
            if (prev > 0L) {
                val delta = frameTimeNanos - prev
                if (delta in 1_000_000L..500_000_000L) {
                    val fps = NativeFastEngine.recordFrameDeltaNs(delta, frameTimeNanos)
                    uiPublishCounter++
                    if ((uiPublishCounter and 15) == 0 && _estimatedFps.value != fps) {
                        _estimatedFps.value = fps
                    }
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setFrameMonitorActive(active: Boolean) {
        if (monitorActive == active) return
        monitorActive = active
        mainHandler.post {
            if (active) {
                lastFrameNs = 0L
                Choreographer.getInstance().removeFrameCallback(choreographerCallback)
                Choreographer.getInstance().postFrameCallback(choreographerCallback)
            } else {
                Choreographer.getInstance().removeFrameCallback(choreographerCallback)
                lastFrameNs = 0L
            }
            _status.value = _status.value.copy(frameMonitorActive = active)
        }
    }

    fun prewarmAsync(context: Context) {
        if (!prewarmed.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        scope.launch {
            NativeFastEngine.prewarm()
            val ua = runCatching {
                WebSettings.getDefaultUserAgent(appCtx)
                    .replace("; wv)", ")")
                    .replace("Version/4.0 ", "")
            }.getOrDefault("")
            if (ua.isNotBlank()) {
                defaultBlinkUserAgent = ua
            }
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
            }
            _status.value = _status.value.copy(
                blinkPrewarmed = true,
                nativeCppActive = NativeFastEngine.isNativeAvailable
            )
        }
    }

    fun preconnectOriginAsync(url: String) {
        val host = UrlUtils.host(url) ?: return
        if (host.isBlank() || host == "localhost") return
        val now = System.currentTimeMillis()
        val last = dnsWarmCache[host]
        if (last != null && now - last < 120_000L) return
        dnsWarmCache[host] = now
        scope.launch(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(host) }
        }
    }

    fun shouldUseBlinkForUrl(
        url: String,
        settings: Settings,
        manualOverride: Boolean?
    ): Boolean {
        if (manualOverride != null) return manualOverride
        if (!settings.hybridGeckoBlinkBeta) return false
        if (!settings.hybridAutoForGames) return true
        return HybridEngineBridge.isHeavy3dGameUrl(url, settings.hybridCustomDomains)
    }

    fun recordFrameDeltaNs(deltaNs: Long, nowNs: Long = System.nanoTime()): Int {
        val fps = NativeFastEngine.recordFrameDeltaNs(deltaNs, nowNs)
        if (_estimatedFps.value != fps) {
            _estimatedFps.value = fps
        }
        return fps
    }

    fun flushBlinkCookies() {
        scope.launch(Dispatchers.IO) {
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    fun clearBlinkData(incognitoOnly: Boolean) {
        runCatching {
            val cm = CookieManager.getInstance()
            if (!incognitoOnly) {
                cm.removeAllCookies(null)
                cm.flush()
            }
        }
    }

    fun guessDownloadFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val guessed = runCatching {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }.getOrNull()
        if (!guessed.isNullOrBlank()) return guessed
        val host = UrlUtils.host(url) ?: "download"
        return "$host.bin"
    }

    fun extractContextMenuTarget(
        tab: BrowserTab,
        hitResult: WebView.HitTestResult?
    ): ContextMenuTarget? {
        val hit = hitResult ?: return null
        val extra = hit.extra?.takeIf { it.isNotBlank() } ?: return null
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> ContextMenuTarget(
                tabId = tab.id,
                linkUri = extra,
                imageUri = null,
                title = extra,
                isIncognito = tab.isIncognito
            )
            WebView.HitTestResult.IMAGE_TYPE -> ContextMenuTarget(
                tabId = tab.id,
                linkUri = null,
                imageUri = extra,
                title = extra,
                isIncognito = tab.isIncognito
            )
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> ContextMenuTarget(
                tabId = tab.id,
                linkUri = extra,
                imageUri = extra,
                title = extra,
                isIncognito = tab.isIncognito
            )
            else -> null
        }
    }
}
