package com.fable.browser.engine

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import com.fable.browser.data.model.DohProvider
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.util.ActivityBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.OrientationController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single GeckoRuntime for the process and applies global privacy
 * configuration: Enhanced Tracking Protection (strict), cookie policy,
 * Global Privacy Control, DNS-over-HTTPS, and the bundled Fable Shield
 * WebExtension (network-layer ad/tracker blocking + fingerprint shield).
 *
 * Zero telemetry: GeckoView's crash reporter and telemetry are never enabled
 * (they are opt-in and we do not opt in).
 */
@Singleton
class EngineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val proxyConfig: ProxyConfig,
    private val activityBridge: ActivityBridge
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var _runtime: GeckoRuntime? = null
    @Volatile private var shieldExtension: WebExtension? = null

    val runtime: GeckoRuntime
        get() = _runtime ?: synchronized(this) {
            _runtime ?: createRuntime().also { _runtime = it }
        }

    fun warmUp() {
        mainHandler.post {
            runtime // touch to initialize on the main thread (Gecko requirement)
            scope.launch { applySettings(settingsStore.settings.first()) }
            observeSettings()
        }
    }

    private fun createRuntime(): GeckoRuntime {
        val contentBlocking = ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
            .antiTracking(STANDARD_ANTI_TRACKING)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookiePurging(true)
            .strictSocialTrackingProtection(false)
            .build()

        val builder = GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking)
            .globalPrivacyControlEnabled(true)
            .consoleOutput(false)
            .remoteDebuggingEnabled(false)
            .aboutConfigEnabled(false)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)

        writeEngineConfig()?.let { builder.configFilePath(it) }

        val runtime = GeckoRuntime.create(context, builder.build())
        installShieldExtension(runtime)
        installOrientationDelegate(runtime)
        return runtime
    }

    private fun writeEngineConfig(): String? = runCatching {
        val file = File(context.filesDir, ENGINE_CONFIG_FILE)
        if (!file.exists() || file.readText() != ENGINE_CONFIG) file.writeText(ENGINE_CONFIG)
        file.absolutePath
    }.getOrNull()

    private fun installOrientationDelegate(runtime: GeckoRuntime) {
        runtime.orientationController.delegate = object : OrientationController.OrientationDelegate {
            override fun onOrientationLock(aOrientation: Int): GeckoResult<AllowOrDeny>? {
                val activity = activityBridge.activity() ?: return GeckoResult.deny()
                activity.requestedOrientation = aOrientation
                return GeckoResult.allow()
            }

            override fun onOrientationUnlock() {
                activityBridge.activity()?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    /**
     * The Fable Shield extension does two jobs, both inside Gecko's network stack:
     * 1. blocker.js — blocking webRequest listener fed by bundled EasyList +
     *    EasyPrivacy rules (real network-layer request cancellation).
     * 2. fingerprint_shield.js — document_start content script that spoofs and
     *    noises fingerprinting surfaces, active only in private (incognito) tabs.
     */
    private fun installShieldExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/extensions/fable_shield/", SHIELD_ID)
            .accept(
                { ext ->
                    shieldExtension = ext
                    ext?.let { proxyConfig.attach(it) }
                },
                { /* extension failed to load; ETP still active */ }
            )
    }

    private fun observeSettings() {
        scope.launch {
            settingsStore.settings.collect { applySettings(it) }
        }
    }

    private fun applySettings(s: Settings) {
        val rt = _runtime ?: return
        proxyConfig.update(s)

        // DNS over HTTPS
        val dohUrl = when (s.dohProvider) {
            DohProvider.OFF -> null
            DohProvider.CUSTOM -> s.dohCustomUrl.ifBlank { null }
            else -> s.dohProvider.url
        }
        if (dohUrl != null) {
            rt.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
            rt.settings.setTrustedRecursiveResolverUri(dohUrl)
        } else {
            rt.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
        }

        // Global Privacy Control
        rt.settings.setGlobalPrivacyControl(s.sendGpc)

        // Appearance: page zoom and forced-dark web content
        rt.settings.setAutomaticFontSizeAdjustment(false)
        rt.settings.setFontSizeFactor(s.textZoomPercent / 100f)
        rt.settings.setPreferredColorScheme(
            if (s.forceDarkWeb) GeckoRuntimeSettings.COLOR_SCHEME_DARK
            else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        )

        // Cookie policy
        rt.settings.contentBlocking.setCookieBehavior(
            if (s.blockThirdPartyCookies)
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
            else
                ContentBlocking.CookieBehavior.ACCEPT_ALL
        )

        applyTrackingProtection(rt, s.strictTrackingProtection)

        shieldExtension?.let { ext ->
            rt.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER)
        }
    }

    private fun applyTrackingProtection(rt: GeckoRuntime, strict: Boolean) {
        val blocking = rt.settings.contentBlocking
        if (strict) {
            blocking.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            blocking.setAntiTracking(STRICT_ANTI_TRACKING)
            blocking.setStrictSocialTrackingProtection(true)
        } else {
            blocking.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
            blocking.setAntiTracking(STANDARD_ANTI_TRACKING)
            blocking.setStrictSocialTrackingProtection(false)
        }
    }

    /** Wipe every Gecko data type. Called by clear-on-exit and the incognito panic path. */
    fun clearAllBrowsingData(onDone: (() -> Unit)? = null) {
        val rt = _runtime ?: return
        rt.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
            .accept({ onDone?.invoke() }, { onDone?.invoke() })
    }

    fun clearCookies() {
        _runtime?.storageController
            ?.clearData(org.mozilla.geckoview.StorageController.ClearFlags.COOKIES)
    }

    fun clearCache() {
        _runtime?.storageController
            ?.clearData(org.mozilla.geckoview.StorageController.ClearFlags.NETWORK_CACHE or
                org.mozilla.geckoview.StorageController.ClearFlags.IMAGE_CACHE)
    }

    /**
     * Wipe all data belonging to one incognito container (contextId).
     * Used by per-tab isolation teardown and the panic button.
     */
    fun clearContainer(contextId: String) {
        _runtime?.storageController?.clearDataForSessionContext(contextId)
    }

    companion object {
        const val SHIELD_ID = "fable-shield@fable.browser"

        private const val ENGINE_CONFIG_FILE = "geckoview-config.yaml"

        private val STANDARD_ANTI_TRACKING =
            ContentBlocking.AntiTracking.DEFAULT

        private val STRICT_ANTI_TRACKING =
            ContentBlocking.AntiTracking.AD or
                ContentBlocking.AntiTracking.ANALYTIC or
                ContentBlocking.AntiTracking.SOCIAL or
                ContentBlocking.AntiTracking.FINGERPRINTING or
                ContentBlocking.AntiTracking.CRYPTOMINING

        private val ENGINE_CONFIG = listOf(
            "prefs:",
            "  dom.ipc.processPrelaunch.enabled: false",
            "  browser.sessionhistory.max_total_viewers: 1",
            "  browser.cache.memory.capacity: 16384",
            "  image.mem.surfacecache.max_size_kb: 131072"
        ).joinToString(separator = "\n", postfix = "\n")
    }
}
