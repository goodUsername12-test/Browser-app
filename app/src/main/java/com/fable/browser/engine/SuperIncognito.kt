package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.repo.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuperIncognito @Inject constructor(
    private val secureStore: SecureStore,
    private val fingerprintShield: FingerprintShield
) {
    private val _identity = MutableStateFlow<SuperIdentity?>(null)
    val identity: StateFlow<SuperIdentity?> = _identity.asStateFlow()

    @Volatile
    var proxyUser: String = ""
        private set

    @Volatile
    var proxyPass: String = ""
        private set

    var sharedContextId: String? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    init {
        Thread { runCatching { loadSecrets() } }.start()
    }

    private fun changed() {
        listeners.forEach { runCatching { it() } }
    }

    fun current(settings: Settings): SuperIdentity {
        _identity.value?.let { return it }
        val avoid = HashSet<String>(fingerprintShield.knownUserAgents())
        runCatching { avoid += GeckoSession.getDefaultUserAgent() }
        val made = SuperIdentityFactory.create(
            settings.superDeviceRange,
            settings.superDevicePick,
            settings.superRandomTimezone,
            avoid
        )
        val created = if (SuperIdentity.HIDE_BROWSER in settings.superReveal) made.withRealBrowser() else made
        _identity.value = created
        changed()
        return created
    }

    fun endSession() {
        sharedContextId = null
        if (_identity.value != null) {
            _identity.value = null
            changed()
        }
    }

    fun loadSecrets() {
        val raw = secureStore.read(PROXY_FILE) ?: return
        runCatching {
            val o = JSONObject(raw)
            proxyUser = o.optString("user")
            proxyPass = o.optString("pass")
        }
        changed()
    }

    fun setProxyAuth(user: String, pass: String) {
        proxyUser = user
        proxyPass = pass
        if (user.isEmpty() && pass.isEmpty()) {
            secureStore.delete(PROXY_FILE)
        } else {
            secureStore.write(PROXY_FILE, JSONObject().put("user", user).put("pass", pass).toString())
        }
        changed()
    }

    fun saveIdentity() {
        val id = _identity.value
        if (id == null) secureStore.delete(IDENTITY_FILE) else secureStore.write(IDENTITY_FILE, id.toJson().toString())
    }

    fun restoreIdentity(): SuperIdentity? {
        val raw = secureStore.read(IDENTITY_FILE) ?: return null
        val id = runCatching { SuperIdentity.fromJson(JSONObject(raw)) }.getOrNull() ?: return null
        _identity.value = id
        changed()
        return id
    }

    fun clearSaved() {
        secureStore.delete(IDENTITY_FILE)
    }

    private companion object {
        const val PROXY_FILE = "super_proxy"
        const val IDENTITY_FILE = "super_identity"
    }
}
