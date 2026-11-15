package com.fable.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.webkit.ProxyConfig as WebKitProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class FableProxyProtocol(val wire: String, val badge: String) {
    SOCKS5("socks5", "SOCKS5"),
    HTTP("http", "HTTP"),
    HTTPS("https", "HTTPS")
}

data class FableProxyProfile(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val protocol: FableProxyProtocol = FableProxyProtocol.SOCKS5,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    val endpointLabel: String get() = "${host.trim()}:$port"
}

data class BasicProxyLiveState(
    val profiles: List<FableProxyProfile> = emptyList(),
    val activeProfileId: String? = null,
    val statusMessage: String = "Direct connection (Proxy off)"
) {
    val activeProfile: FableProxyProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }
}

/**
 * Fable-Only Proxy Profile Manager (for Basic/General Settings tab).
 * Routes ONLY Fable Browser traffic (Blink WebView via AndroidX ProxyController + GeckoView via fable_shield)
 * without creating a system VPN or affecting any other phone app.
 */
object FableProxyProfileManager {
    private const val PREFS_NAME = "fable_basic_proxy_vault"
    private const val KEY_CIPHER_IV = "profiles_iv"
    private const val KEY_CIPHER_BLOB = "profiles_ct"
    private const val KEY_ACTIVE_ID = "active_profile_id"
    private const val KEYSTORE_ALIAS = "fable_proxy_vault_aes_v1"

    private val directExecutor = Executor { r -> r.run() }

    private val _state = MutableStateFlow(BasicProxyLiveState())
    val state: StateFlow<BasicProxyLiveState> = _state.asStateFlow()

    @Volatile private var initialized = false

    fun ensureLoaded(context: Context, geckoProxyBridge: ProxyConfig? = null) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val profiles = decryptProfiles(prefs.getString(KEY_CIPHER_IV, null), prefs.getString(KEY_CIPHER_BLOB, null))
            val activeId = prefs.getString(KEY_ACTIVE_ID, null)?.takeIf { id -> profiles.any { it.id == id } }
            _state.value = BasicProxyLiveState(
                profiles = profiles,
                activeProfileId = activeId,
                statusMessage = activeId?.let { id ->
                    val p = profiles.first { it.id == id }
                    "Active in Fable: ${p.name} (${p.protocol.badge} ${p.endpointLabel})"
                } ?: "Direct connection (Proxy off)"
            )
            initialized = true
            applyRoutingToEngines(_state.value.activeProfile, geckoProxyBridge)
        }
    }

    fun addOrUpdateProfile(
        context: Context,
        profile: FableProxyProfile,
        activateNow: Boolean,
        geckoProxyBridge: ProxyConfig? = null
    ): Result<FableProxyProfile> {
        ensureLoaded(context, geckoProxyBridge)
        val cleanHost = profile.host.trim()
        if (cleanHost.isBlank()) return Result.failure(IllegalArgumentException("Enter proxy host or IP address."))
        if (profile.port !in 1..65535) return Result.failure(IllegalArgumentException("Port must be between 1 and 65535."))
        val clean = profile.copy(
            name = profile.name.trim().ifBlank { "Proxy ${cleanHost}:${profile.port}" },
            host = cleanHost,
            username = profile.username.trim()
        )
        val cur = _state.value
        val updatedList = cur.profiles.filterNot { it.id == clean.id } + clean
        val nextActiveId = if (activateNow) clean.id else cur.activeProfileId
        saveAndApply(context, updatedList, nextActiveId, geckoProxyBridge)
        return Result.success(clean)
    }

    fun deleteProfile(
        context: Context,
        profileId: String,
        geckoProxyBridge: ProxyConfig? = null
    ) {
        ensureLoaded(context, geckoProxyBridge)
        val cur = _state.value
        val updated = cur.profiles.filterNot { it.id == profileId }
        val nextActive = cur.activeProfileId?.takeIf { it != profileId }
        saveAndApply(context, updated, nextActive, geckoProxyBridge)
    }

    fun setActiveProfile(
        context: Context,
        profileId: String?,
        geckoProxyBridge: ProxyConfig? = null
    ) {
        ensureLoaded(context, geckoProxyBridge)
        val cur = _state.value
        val validId = profileId?.takeIf { id -> cur.profiles.any { it.id == id } }
        saveAndApply(context, cur.profiles, validId, geckoProxyBridge)
    }

    suspend fun testProfileLatencyMs(profile: FableProxyProfile): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val t0 = SystemClock.uptimeMillis()
            Socket().use { sock ->
                sock.soTimeout = 5_000
                sock.connect(InetSocketAddress(profile.host.trim(), profile.port), 5_000)
                if (profile.protocol == FableProxyProtocol.SOCKS5) {
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()
                    val authMethod: Byte = if (profile.username.isNotBlank()) 0x02 else 0x00
                    out.write(byteArrayOf(0x05, 0x01, authMethod))
                    out.flush()
                    val ver = inp.read()
                    val chosen = inp.read()
                    if (ver != 0x05 || chosen == 0xFF) {
                        throw IllegalStateException("SOCKS5 handshake rejected (code=$chosen)")
                    }
                }
            }
            (SystemClock.uptimeMillis() - t0).coerceAtLeast(1L)
        }
    }

    private fun saveAndApply(
        context: Context,
        profiles: List<FableProxyProfile>,
        activeId: String?,
        geckoProxyBridge: ProxyConfig?
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val (ivB64, ctB64) = encryptProfiles(profiles)
        prefs.edit()
            .putString(KEY_CIPHER_IV, ivB64)
            .putString(KEY_CIPHER_BLOB, ctB64)
            .putString(KEY_ACTIVE_ID, activeId)
            .apply()

        val active = activeId?.let { id -> profiles.firstOrNull { it.id == id } }
        val msg = if (active != null) {
            "Active in Fable: ${active.name} (${active.protocol.badge} ${active.endpointLabel})"
        } else {
            "Direct connection (Proxy off)"
        }
        _state.value = BasicProxyLiveState(
            profiles = profiles,
            activeProfileId = active?.id,
            statusMessage = msg
        )
        applyRoutingToEngines(active, geckoProxyBridge)
    }

    @SuppressLint("RequiresFeature")
    fun applyRoutingToEngines(active: FableProxyProfile?, geckoProxyBridge: ProxyConfig?) {
        // 1. Apply to Chromium Blink WebView (Fable-only process override)
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                val controller = ProxyController.getInstance()
                if (active == null) {
                    controller.clearProxyOverride(directExecutor) {}
                } else {
                    val proxyUrl = when (active.protocol) {
                        FableProxyProtocol.SOCKS5 -> "socks5://${active.host.trim()}:${active.port}"
                        FableProxyProtocol.HTTPS -> "https://${active.host.trim()}:${active.port}"
                        FableProxyProtocol.HTTP -> "http://${active.host.trim()}:${active.port}"
                    }
                    val cfg = WebKitProxyConfig.Builder()
                        .addProxyRule(proxyUrl)
                        .addBypassRule("local.fable.app")
                        .addBypassRule("<local>")
                        .build()
                    controller.setProxyOverride(cfg, directExecutor) {}
                }
            }
        }
        // 2. Push to GeckoView fable_shield extension
        geckoProxyBridge?.push()
    }

    private fun encryptProfiles(list: List<FableProxyProfile>): Pair<String, String> = runCatching {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("protocol", p.protocol.name)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("username", p.username)
                    .put("password", p.password)
            )
        }
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(arr.toString().toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to Base64.encodeToString(ct, Base64.NO_WRAP)
    }.getOrDefault("" to "")

    private fun decryptProfiles(ivB64: String?, ctB64: String?): List<FableProxyProfile> = runCatching {
        if (ivB64.isNullOrBlank() || ctB64.isNullOrBlank()) return emptyList()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        val plain = String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        val arr = JSONArray(plain)
        val out = ArrayList<FableProxyProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val proto = runCatching { FableProxyProtocol.valueOf(o.optString("protocol", "SOCKS5")) }
                .getOrDefault(FableProxyProtocol.SOCKS5)
            val host = o.optString("host").trim()
            val port = o.optInt("port", 1080)
            if (host.isNotBlank() && port in 1..65535) {
                out.add(
                    FableProxyProfile(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                        name = o.optString("name", "$host:$port"),
                        protocol = proto,
                        host = host,
                        port = port,
                        username = o.optString("username"),
                        password = o.optString("password")
                    )
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}
