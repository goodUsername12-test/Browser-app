package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The native half of the Fable Shield bridge.
 *
 * Responsibilities:
 *  - answer the extension's config query (proxy, blocker and shield toggles);
 *  - stream the filter rules parsed by [ContentBlocker] into the extension so
 *    Kotlin remains the single source of truth for blocking;
 *  - push live updates when settings change;
 *  - forward one-shot commands (reader-mode toggle) to the active tab.
 *
 * Optional incognito proxying (user-supplied HTTP/SOCKS5, e.g. Tor on 9050) is
 * enforced by the extension's `browser.proxy.onRequest` handler, which routes
 * private-tab requests through the proxy and leaves normal tabs direct.
 */
@Singleton
class ProxyConfig @Inject constructor(
    private val contentBlocker: ContentBlocker
) : WebExtension.MessageDelegate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var current: Settings? = null
    @Volatile private var port: WebExtension.Port? = null

    fun update(settings: Settings) {
        current = settings
        port?.postMessage(configJson())
    }

    /** Called by EngineManager once the shield extension is installed. */
    fun attach(extension: WebExtension) {
        extension.setMessageDelegate(this, NATIVE_APP)
    }

    /** Ask the active tab's content script to toggle reader mode. */
    fun toggleReaderMode() {
        port?.postMessage(JSONObject().apply { put("type", "reader-toggle") })
    }

    /** Re-send rules after the user updates the filter lists. */
    fun pushRules() {
        scope.launch { sendRules() }
    }

    private suspend fun sendRules() {
        contentBlocker.ensureLoaded()
        val json = withContext(Dispatchers.Default) { contentBlocker.rulesJson() }
        port?.postMessage(json)
    }

    private fun configJson(): JSONObject {
        val s = current
        return JSONObject().apply {
            put("type", "config")
            put("proxyEnabled", s?.incognitoProxyEnabled ?: false)
            put("proxyHost", s?.incognitoProxyHost ?: "")
            put("proxyPort", s?.incognitoProxyPort ?: 9050)
            put("proxyType", (s?.incognitoProxyType ?: "SOCKS5").lowercase())
            put("adBlockEnabled", s?.adBlockEnabled ?: true)
            put("fingerprintShield", s?.fingerprintShield ?: true)
            put("normalTabShield", s?.normalTabShield ?: false)
            put("forceUtc", s?.forceUtcTimezone ?: true)
            put("isolateIncognitoTabs", s?.isolateIncognitoTabs ?: true)
        }
    }

    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender
    ): GeckoResult<Any>? = GeckoResult.fromValue(configJson() as Any)

    override fun onConnect(port: WebExtension.Port) {
        this.port = port
        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, p: WebExtension.Port) {
                // The background script pings on start (and after a restart);
                // answer with the current config and the full rule set.
                p.postMessage(configJson())
                scope.launch { sendRules() }
            }

            override fun onDisconnect(p: WebExtension.Port) {
                if (this@ProxyConfig.port == p) this@ProxyConfig.port = null
            }
        })
        port.postMessage(configJson())
        scope.launch { sendRules() }
    }

    companion object {
        const val NATIVE_APP = "fable"
    }
}
