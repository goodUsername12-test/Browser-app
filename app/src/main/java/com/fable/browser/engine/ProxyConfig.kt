package com.fable.browser.engine

import android.util.Log
import android.webkit.CookieManager
import com.fable.browser.BuildConfig
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.repo.FaviconStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyConfig @Inject constructor(
    private val contentBlocker: ContentBlocker,
    private val superIncognito: SuperIncognito,
    private val fingerprintShield: FingerprintShield,
    private val faviconStore: FaviconStore,
    private val pageArchiver: PageArchiver
) : WebExtension.MessageDelegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionSeed = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
    private val applied = MutableStateFlow<Set<String>>(emptySet())
    private val connected = MutableStateFlow(false)

    @Volatile private var dataSaverActive = false
    @Volatile private var current: Settings? = null
    @Volatile private var port: WebExtension.Port? = null
    private val cookieIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastBlinkCookieDigest = ConcurrentHashMap<String, String>()
    private var cookieFlushJob: kotlinx.coroutines.Job? = null

    init {
        superIncognito.addListener { push() }
        pageArchiver.onControlSignal = { action, id ->
            scope.launch {
                port?.postMessage(JSONObject().apply {
                    put("type", "capture-control")
                    put("action", action)
                    put("id", id)
                })
            }
        }
    }

    fun push() {
        scope.launch { port?.postMessage(configJson()) }
    }

    fun setDataSaverActive(active: Boolean) {
        if (dataSaverActive == active) return
        dataSaverActive = active
        push()
    }

    fun update(settings: Settings) {
        current = settings
        port?.postMessage(configJson())
    }

    fun attach(extension: WebExtension) {
        extension.setMessageDelegate(this, NATIVE_APP)
    }

    fun toggleReaderMode() {
        port?.postMessage(JSONObject().apply { put("type", "reader-toggle") })
    }

    fun shouldBlockHybrid(url: String, docHost: String?, adBlockActive: Boolean): Boolean {
        if (!adBlockActive) return false
        return contentBlocker.shouldBlock(url, docHost)
    }

    val captureState: kotlinx.coroutines.flow.StateFlow<CaptureUiState> = pageArchiver.state

    fun pauseCapture() = pageArchiver.pause()
    fun resumeCapture() = pageArchiver.resume()
    fun stopAndSaveCapture() = pageArchiver.stopAndSave(partial = true)
    fun cancelCapture() = pageArchiver.cancelAndDelete()

    fun newHybridArchiveRequest(mode: String = "all", continuous: Boolean = false): String? {
        val s = current
        return pageArchiver.newRequest(
            mode = mode,
            continuous = continuous,
            readOnly = s?.archiveReadOnly ?: true,
            includeCookies = s?.archiveIncludeCookies ?: false,
            maxFiles = s?.archiveMaxFiles ?: 4000,
            maxTotalMb = s?.archiveMaxTotalMb ?: 600
        )
    }

    fun enqueueBlinkObservedUrls(urls: Collection<String>, pageUrl: String, userAgent: String) {
        pageArchiver.enqueueObservedUrls(urls, pageUrl, userAgent)
    }

    fun onHybridArchiveMessage(rawJson: String) {
        runCatching { pageArchiver.onMessage(JSONObject(rawJson)) }
    }

    fun savePageCode(url: String, mode: String = "all", continuous: Boolean = false): Boolean {
        val p = port ?: return false
        val s = current
        val id = pageArchiver.newRequest(
            mode = mode,
            continuous = continuous,
            readOnly = s?.archiveReadOnly ?: true,
            includeCookies = s?.archiveIncludeCookies ?: false,
            maxFiles = s?.archiveMaxFiles ?: 4000,
            maxTotalMb = s?.archiveMaxTotalMb ?: 600
        ) ?: return true
        p.postMessage(JSONObject().apply {
            put("type", "save-page")
            put("id", id)
            put("url", url)
            put("mode", mode)
            put("continuous", continuous)
        })
        return true
    }


    fun syncBlinkCookiesToGecko(url: String) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return
        val p = port ?: return
        cookieIoScope.launch {
            val cookieHeader = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@launch
            val hostKey = com.fable.browser.util.UrlUtils.host(url) ?: url
            val prev = lastBlinkCookieDigest[hostKey]
            if (prev == cookieHeader) return@launch
            lastBlinkCookieDigest[hostKey] = cookieHeader
            if (lastBlinkCookieDigest.size > 128) lastBlinkCookieDigest.clear()
            scope.launch {
                p.postMessage(JSONObject().apply {
                    put("type", "sync-cookies-to-gecko")
                    put("url", url)
                    put("cookieHeader", cookieHeader)
                })
            }
        }
    }

    fun pullGeckoCookiesToBlink(url: String) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return
        val p = port ?: return
        scope.launch {
            p.postMessage(JSONObject().apply {
                put("type", "pull-gecko-cookies")
                put("url", url)
            })
        }
    }

    private fun scheduleCookieFlush() {
        cookieFlushJob?.cancel()
        cookieFlushJob = cookieIoScope.launch {
            delay(150L)
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    fun pushRules() {
        scope.launch { sendRules() }
    }

    fun isSuperApplied(id: String): Boolean = id in applied.value

    suspend fun awaitSuperApplied(id: String, timeoutMs: Long): Boolean {
        if (isSuperApplied(id)) return true
        return withTimeoutOrNull(timeoutMs) {
            connected.first { it }
            applied.first { id in it }
            true
        } ?: false
    }

    private suspend fun sendRules() {
        contentBlocker.ensureLoaded()
        val json = withContext(Dispatchers.Default) { contentBlocker.rulesJson() }
        port?.postMessage(json)
    }

    private fun configJson(): JSONObject {
        val s = current
        val supers = JSONArray()
        val reveal = s?.superReveal ?: emptySet()
        val gameMode = s?.superGameMode == true
        val gpu = s?.superGpu.orEmpty()
        superIncognito.identity.value?.let { identity ->
            val o = identity.toJson().put("hide", SuperIdentity.hideJson(reveal, gameMode))
            if (gpu.isNotEmpty()) {
                SuperIdentityFactory.gpuChoices().firstOrNull { it.second == gpu }?.let {
                    o.put("glVendor", it.first)
                    o.put("glRenderer", it.second)
                }
            }
            supers.put(o)
        }
        val superProxyOn = (s?.superProxyEnabled ?: false) && !s?.superProxyHost.isNullOrBlank()
        return JSONObject().apply {
            put("type", "config")
            put("proxyEnabled", s?.incognitoProxyEnabled ?: false)
            put("proxyHost", s?.incognitoProxyHost ?: "")
            put("proxyPort", s?.incognitoProxyPort ?: 9050)
            put("proxyType", (s?.incognitoProxyType ?: "SOCKS5").lowercase())
            val basicActive = FableProxyProfileManager.state.value.activeProfile
            put("basicProxyEnabled", basicActive != null)
            put("basicProxyType", basicActive?.protocol?.wire ?: "socks5")
            put("basicProxyHost", basicActive?.host ?: "")
            put("basicProxyPort", basicActive?.port ?: 1080)
            put("basicProxyUser", basicActive?.username ?: "")
            put("basicProxyPass", basicActive?.password ?: "")
            put("adBlockEnabled", s?.adBlockEnabled ?: true)
            put("adBlockIncognito", s?.incognitoAdBlock ?: true)
            put("gameTouchFix", s?.gameTouchFix ?: true)
            put("fingerprintShield", s?.fingerprintShield ?: true)
            put("normalTabShield", s?.normalTabShield ?: false)
            put("forceUtc", s?.forceUtcTimezone ?: true)
            put("isolateIncognitoTabs", s?.isolateIncognitoTabs ?: true)
            put("incognitoUa", fingerprintShield.incognitoUserAgent(s?.incognitoRandomUserAgent ?: false))
            put("trimReferrer", s?.incognitoTrimReferrer ?: true)
            put("stripClientHints", s?.incognitoStripClientHints ?: true)
            put("sendDnt", s?.incognitoSendDnt ?: true)
            put("adBlockCosmetic", s?.adBlockCosmetic ?: true)
            put("adBlockAllow", JSONArray((s?.adBlockAllowSites ?: emptySet()).toList()))
            put("sessionSeed", sessionSeed)
            put("supers", supers)
            put("superProxyEnabled", superProxyOn)
            put("superProxyType", s?.superProxyType?.wire ?: "socks5")
            put("superProxyHost", s?.superProxyHost?.trim() ?: "")
            put("superProxyPort", s?.superProxyPort ?: 1080)
            put("superProxyUser", superIncognito.proxyUser)
            put("superProxyPass", superIncognito.proxyPass)
            put("dataSaver", dataSaverActive)
            put("diag", BuildConfig.DEBUG)
            put("archiveReadOnly", s?.archiveReadOnly ?: true)
            put("archiveNetworkStream", s?.archiveNetworkStream ?: true)
            put("archiveIncludeMemory", s?.archiveIncludeMemory ?: true)
            put("archiveIncludeCanvas", s?.archiveIncludeCanvas ?: true)
            put("archiveIncludeStorage", s?.archiveIncludeStorage ?: true)
            put("archiveGuessAssets", s?.archiveGuessAssets ?: true)
            put("archiveDeepHooks", s?.archiveDeepHooks ?: false)
            put("archiveMaxFiles", s?.archiveMaxFiles ?: 4000)
            put("archiveMaxTotalMb", s?.archiveMaxTotalMb ?: 600)
            put("archiveMaxFileMb", s?.archiveMaxFileMb ?: 120)
            put("archiveStreamBufferMb", s?.archiveStreamBufferMb ?: 160)
            put("archiveIncludeCookies", s?.archiveIncludeCookies ?: false)
            put("archiveContinuousCapture", s?.archiveContinuousCapture ?: true)
            put("proxyStripLeakHeaders", s?.proxyStripLeakHeaders ?: true)
            put("stealthLowDetectability", s?.stealthLowDetectability ?: true)
            put("etpEnabled", s?.etpEnabled ?: true)
        }
    }

    private fun handlePortMessage(message: Any?, p: WebExtension.Port) {
        val json = message as? JSONObject
        when (json?.optString("type")) {
            "applied" -> {
                val ids = json.optJSONArray("superIds")
                val set = HashSet<String>()
                if (ids != null) for (i in 0 until ids.length()) set += ids.optString(i)
                applied.value = set
                if (BuildConfig.DEBUG) Log.i(TAG, "applied ok=${json.optBoolean("ok")} supers=${set.size}")
            }
            "icon" -> {
                val page = json.optString("page")
                val icons = json.optJSONArray("icons")
                val list = ArrayList<String>()
                if (icons != null) for (i in 0 until icons.length()) list += icons.optString(i)
                if (page.isNotBlank()) faviconStore.onPageIcons(page, list)
            }
            "diag" -> Log.i(TAG, json.optString("text").take(600))
            "save-page-data" -> pageArchiver.onMessage(json)
            "gecko-cookie-changed" -> {
                val u = json.optString("url")
                val sc = json.optString("setCookie")
                if (u.isNotBlank() && sc.isNotBlank()) {
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setCookie(u, sc)
                    }
                    scheduleCookieFlush()
                }
            }
            "gecko-cookies-batch" -> {
                val arr = json.optJSONArray("cookies")
                if (arr != null && arr.length() > 0) {
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val u = item.optString("url")
                            val sc = item.optString("setCookie")
                            if (u.isNotBlank() && sc.isNotBlank()) {
                                cm.setCookie(u, sc)
                            }
                        }
                    }
                    scheduleCookieFlush()
                }
            }
            else -> {
                p.postMessage(configJson())
                scope.launch { sendRules() }
            }
        }
    }

    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender
    ): GeckoResult<Any>? = GeckoResult.fromValue(configJson() as Any)

    override fun onConnect(port: WebExtension.Port) {
        this.port = port
        applied.value = emptySet()
        connected.value = true
        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, p: WebExtension.Port) {
                runCatching { handlePortMessage(message, p) }
            }

            override fun onDisconnect(p: WebExtension.Port) {
                if (this@ProxyConfig.port == p) {
                    this@ProxyConfig.port = null
                    connected.value = false
                    applied.value = emptySet()
                }
            }
        })
        port.postMessage(configJson())
        scope.launch {
            sendRules()
            maybeAutoUpdate()
        }
    }

    private var autoUpdateStarted = false

    private suspend fun maybeAutoUpdate() {
        if (autoUpdateStarted) return
        val s = current ?: return
        if (!s.adBlockAutoUpdate || !(s.adBlockEnabled || s.incognitoAdBlock)) return
        autoUpdateStarted = true
        delay(AUTO_UPDATE_DELAY_MS)
        if (runCatching { contentBlocker.autoUpdateIfDue() }.getOrDefault(false)) sendRules()
    }

    companion object {
        private const val AUTO_UPDATE_DELAY_MS = 20_000L
        const val NATIVE_APP = "fable"
        private const val TAG = "FableShield"
    }
}
