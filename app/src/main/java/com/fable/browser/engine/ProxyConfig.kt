package com.fable.browser.engine

import android.util.Log
import com.fable.browser.BuildConfig
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.repo.FaviconStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyConfig @Inject constructor(
    private val contentBlocker: ContentBlocker,
    private val superIncognito: SuperIncognito,
    private val fingerprintShield: FingerprintShield,
    private val faviconStore: FaviconStore
) : WebExtension.MessageDelegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionSeed = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
    private val applied = MutableStateFlow<Set<String>>(emptySet())
    private val connected = MutableStateFlow(false)

    @Volatile private var dataSaverActive = false
    @Volatile private var current: Settings? = null
    @Volatile private var port: WebExtension.Port? = null

    init {
        superIncognito.addListener { push() }
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
        superIncognito.identity.value?.let { supers.put(it.toJson()) }
        val superProxyOn = (s?.superProxyEnabled ?: false) && !s?.superProxyHost.isNullOrBlank()
        return JSONObject().apply {
            put("type", "config")
            put("proxyEnabled", s?.incognitoProxyEnabled ?: false)
            put("proxyHost", s?.incognitoProxyHost ?: "")
            put("proxyPort", s?.incognitoProxyPort ?: 9050)
            put("proxyType", (s?.incognitoProxyType ?: "SOCKS5").lowercase())
            put("adBlockEnabled", s?.adBlockEnabled ?: true)
            put("adBlockIncognito", s?.incognitoAdBlock ?: true)
            put("gameTouchFix", s?.gameTouchFix ?: true)
            put("fingerprintShield", s?.fingerprintShield ?: true)
            put("normalTabShield", s?.normalTabShield ?: false)
            put("forceUtc", s?.forceUtcTimezone ?: true)
            put("isolateIncognitoTabs", s?.isolateIncognitoTabs ?: true)
            put("incognitoUa", fingerprintShield.sessionUserAgent())
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
        scope.launch { sendRules() }
    }

    companion object {
        const val NATIVE_APP = "fable"
        private const val TAG = "FableShield"
    }
}
