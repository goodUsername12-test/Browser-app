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

    fun attach(extension: WebExtension) {
        extension.setMessageDelegate(this, NATIVE_APP)
    }

    fun toggleReaderMode() {
        port?.postMessage(JSONObject().apply { put("type", "reader-toggle") })
    }

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
            put("adBlockIncognito", s?.incognitoAdBlock ?: true)
            put("gameTouchFix", s?.gameTouchFix ?: true)
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
