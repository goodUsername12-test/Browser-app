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
import org.mozilla.geckoview.GeckoPreferenceController
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
            // Keep fast HTTP/2 + HTTP/3 connections alive longer: repeat visits load faster.
            .largeKeepaliveFactor(4)

        writeEngineConfig()?.let { builder.configFilePath(it) }

        val runtime = GeckoRuntime.create(context, builder.build())
        installShieldExtension(runtime)
        installOrientationDelegate(runtime)
        return runtime
    }

    private fun writeEngineConfig(): String? = runCatching {
        val file = File(context.filesDir, ENGINE_CONFIG_FILE)
        val config = buildEngineConfig()
        if (!file.exists() || file.readText() != config) file.writeText(config)
        file.absolutePath
    }.getOrNull()

    private val engineFlags by lazy {
        context.getSharedPreferences(ENGINE_FLAGS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * Base engine prefs + the user's optional feature switches. The switches are
     * cached in SharedPreferences so they are already correct at the very first
     * page after a cold start (Gecko reads some of them only once per process).
     */
    private fun buildEngineConfig(): String {
        val lines = ArrayList<String>()
        lines += "prefs:"
        BASE_PREFS.forEach { (k, v) -> lines += "  $k: $v" }
        val flags = runCatching { engineFlags.all }.getOrNull().orEmpty()
        privacyPrefs(null).forEach { (k, default) ->
            val v = flags[k] as? Boolean ?: default
            lines += "  $k: $v"
        }
        intPrefs(null).forEach { (k, default) ->
            val v = flags[k] as? Int ?: default
            lines += "  $k: $v"
        }
        stringPrefs(null).forEach { (k, default) ->
            val v = flags[k] as? String ?: default
            // YAML single-quoted scalar: only ' needs escaping (doubled).
            lines += "  $k: '" + v.replace("'", "''") + "'"
        }
        // Optional features are only written when switched on, so "off" always
        // means the engine's own default.
        featurePrefs(null).forEach { (k, default) ->
            val v = flags[k] as? Boolean ?: default
            if (v) lines += "  $k: true"
        }
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * Optional / privacy prefs driven by Settings. With s == null returns the
     * defaults used before settings have been read.
     */
    private fun privacyPrefs(s: Settings?): List<Pair<String, Boolean>> {
        val d = s ?: Settings()
        return listOf(
            // WebRTC: never reveal the local (LAN / Wi-Fi) IP address.
            "media.peerconnection.ice.no_host" to d.hideLocalIp,
            "media.peerconnection.ice.default_address_only" to d.hideLocalIp,
            // Firefox's own tracking-parameter stripping (fbclid, gclid, ...).
            "privacy.query_stripping.enabled" to d.stripTrackingParams,
            "privacy.query_stripping.enabled.pbmode" to d.stripTrackingParams,
            // The GPU name is now hidden per site by fingerprint protection
            // (WebGLRenderInfo), so the global switch stays on.
            "webgl.enable-debug-renderer-info" to true,
            // WebRTC: relay only through the proxy / switch off completely.
            "media.peerconnection.ice.proxy_only" to d.webRtcProxyOnly,
            "media.peerconnection.enabled" to !d.webRtcDisabled,
            // No silent connections to sites you have not opened.
            "network.prefetch-next" to !d.blockPrefetch,
            "network.predictor.enabled" to !d.blockPrefetch,
            // Security hardening: JavaScript without JIT compilers, WebAssembly off.
            "javascript.options.baselinejit" to !d.noJit,
            "javascript.options.ion" to !d.noJit,
            "javascript.options.native_regexp" to !d.noJit,
            "javascript.options.wasm_optimizingjit" to !d.noJit,
            "javascript.options.wasm" to !d.disableWasm
        )
    }

    private fun intPrefs(s: Settings?): List<Pair<String, Int>> {
        val d = s ?: Settings()
        return listOf(
            "network.http.speculative-parallel-limit" to if (d.blockPrefetch) 0 else 6,
            // Cross-site requests only get the site name as referrer, never the full address.
            "network.http.referer.XOriginTrimmingPolicy" to if (d.strictReferrer) 2 else 0,
            "privacy.bounceTrackingProtection.mode" to if (d.bounceTracking) 1 else 0,
            // Speed: more RAM for caches and instant back/forward when the user allows it.
            "browser.cache.memory.capacity" to if (d.speedMemory) 65536 else 16384,
            "browser.sessionhistory.max_total_viewers" to if (d.speedMemory) 3 else 1,
            "image.mem.surfacecache.max_size_kb" to if (d.speedMemory) 262144 else 131072
        )
    }

    private fun stringPrefs(s: Settings?): List<Pair<String, String>> {
        val d = s ?: Settings()
        return listOf(
            "privacy.fingerprintingProtection.overrides" to fingerprintOverrides(d),
            "privacy.fingerprintingProtection.granularOverrides" to trustedSitesJson(d)
        )
    }

    /** Firefox fingerprinting-protection targets chosen in Settings > Privacy. */
    private fun fingerprintOverrides(d: Settings): String {
        val targets = listOf(
            d.fpCanvas to listOf("CanvasRandomization"),
            d.fpCanvasThirdParty to listOf("CanvasExtractionFromThirdPartiesIsBlocked"),
            d.fpFonts to listOf("FontVisibilityBaseSystem", "FontVisibilityLangPack"),
            d.hideGpuModel to listOf("WebGLRenderInfo"),
            d.fpWebGlLimits to listOf("WebGLRenderCapability"),
            d.fpUserAgent to listOf(
                "NavigatorUserAgent", "HttpUserAgent", "NavigatorAppVersion",
                "NavigatorOscpu", "NavigatorPlatform", "NavigatorBuildID"
            ),
            d.fpHardware to listOf("NavigatorHWConcurrencyTiered", "MaxTouchPointsCollapse"),
            d.fpScreen to listOf(
                "ScreenRect", "ScreenAvailRect", "WindowOuterSize", "WindowScreenXY",
                "ScreenPixelDepth", "CSSDeviceSize"
            ),
            d.fpAudio to listOf("AudioContext", "AudioSampleRate"),
            d.fpMedia to listOf("MediaDevices", "MediaCapabilities", "SpeechSynthesis", "NetworkConnection"),
            d.fpSensors to listOf("DeviceSensors"),
            d.fpTimezone to listOf("JSDateTimeUTC"),
            d.fpLanguage to listOf("JSLocale"),
            d.fpTimers to listOf("ReduceTimerPrecision")
        )
        return targets.flatMap { (on, names) -> names.map { (if (on) "+" else "-") + it } }
            .joinToString(",")
    }

    /** Trusted sites (games etc.) get no fingerprinting protection at all. */
    private fun trustedSitesJson(d: Settings): String {
        val hosts = d.fpTrustedSites.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) return "[]"
        return hosts.joinToString(prefix = "[", postfix = "]", separator = ",") { host ->
            "{\"firstPartyDomain\":\"$host\",\"overrides\":\"-AllTargets\"}"
        }
    }

    /** Optional web platform features. Only applied when switched on. */
    private fun featurePrefs(s: Settings?): List<Pair<String, Boolean>> {
        val d = s ?: Settings()
        return listOf(
            "dom.vibrator.enabled" to d.vibrationApi,
            "dom.battery.enabled" to d.featureBatteryApi,
            "dom.netinfo.enabled" to d.featureNetworkInfo,
            "dom.webgpu.enabled" to d.featureWebGpu,
            "media.webspeech.recognition.enable" to d.featureSpeechRecognition,
            "media.webspeech.recognition.force_enable" to d.featureSpeechRecognition,
            "webgl.enable-draft-extensions" to d.featureWebGlDraft,
            "javascript.options.wasm_relaxed_simd" to d.featureWasmExperimental
        )
    }

    /** Apply the optional prefs live (new pages see them at once) and cache them for the next start. */
    private fun applyOptionalPrefs(s: Settings) {
        val privacy = privacyPrefs(s)
        val features = featurePrefs(s)
        val ints = intPrefs(s)
        val strings = stringPrefs(s)
        runCatching {
            val editor = engineFlags.edit()
            (privacy + features).forEach { (k, v) -> editor.putBoolean(k, v) }
            ints.forEach { (k, v) -> editor.putInt(k, v) }
            strings.forEach { (k, v) -> editor.putString(k, v) }
            editor.apply()
        }
        ints.forEach { (k, v) ->
            runCatching {
                GeckoPreferenceController.setGeckoPref(k, v, GeckoPreferenceController.PREF_BRANCH_USER)
            }
        }
        strings.forEach { (k, v) ->
            runCatching {
                GeckoPreferenceController.setGeckoPref(k, v, GeckoPreferenceController.PREF_BRANCH_USER)
            }
        }
        privacy.forEach { (k, v) ->
            runCatching {
                GeckoPreferenceController.setGeckoPref(k, v, GeckoPreferenceController.PREF_BRANCH_USER)
            }
        }
        features.forEach { (k, v) ->
            runCatching {
                if (v) {
                    GeckoPreferenceController.setGeckoPref(k, true, GeckoPreferenceController.PREF_BRANCH_USER)
                } else {
                    GeckoPreferenceController.clearGeckoUserPref(k)
                }
            }
        }
        runCatching {
            GeckoPreferenceController.setGeckoPref(
                "privacy.query_stripping.strip_list",
                QUERY_STRIP_LIST,
                GeckoPreferenceController.PREF_BRANCH_USER
            )
        }
    }

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

        // Fingerprinting: Firefox's protections. Baseline everywhere (safe for
        // sites and games), full protection always in incognito, and in normal
        // tabs when the user turns it on.
        runCatching {
            rt.settings.setBaselineFingerprintingProtection(true)
            rt.settings.setFingerprintingProtectionPrivateBrowsing(true)
            rt.settings.setFingerprintingProtection(s.fingerprintProtectionAll)
            // Incognito: third-party cookies fully blocked if chosen, else isolated per site.
            rt.settings.contentBlocking.setCookieBehaviorPrivateMode(
                if (s.incognitoBlockThirdPartyCookies) ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
                else ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
            )
        }
        // Speed: honour <img fetchpriority="high"> etc. so important resources load first.
        runCatching { rt.settings.setFetchPriorityEnabled(true) }

        // WebRTC leak protection, optional web features, query stripping.
        applyOptionalPrefs(s)

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
        private const val ENGINE_FLAGS_FILE = "fable_engine_flags"

        private const val QUERY_STRIP_LIST =
            "__hsfp __hssc __hstc __s _hsenc _openstat dclid fbclid gbraid gclid hsctatracking " +
                "igshid mc_eid ml_subscriber ml_subscriber_hash msclkid oft_c oft_ck oft_d oft_id " +
                "oft_ids oft_k oft_lk oft_sk oly_anon_id oly_enc_id rb_clickid s_cid twclid " +
                "vero_conv vero_id wbraid wickedid yclid"

        private val STANDARD_ANTI_TRACKING =
            ContentBlocking.AntiTracking.DEFAULT

        private val STRICT_ANTI_TRACKING =
            ContentBlocking.AntiTracking.AD or
                ContentBlocking.AntiTracking.ANALYTIC or
                ContentBlocking.AntiTracking.SOCIAL or
                ContentBlocking.AntiTracking.FINGERPRINTING or
                ContentBlocking.AntiTracking.CRYPTOMINING

        /** Engine prefs that are always on. Values are written as YAML scalars. */
        private val BASE_PREFS: List<Pair<String, Any>> = listOf(
            // Memory (unchanged from v5)
            "dom.ipc.processPrelaunch.enabled" to false,
            // Privacy: hide host candidates behind mDNS names if any are gathered,
            // and send WebRTC through the proxy when one is set.
            "media.peerconnection.ice.obfuscate_host_addresses" to true,
            "media.peerconnection.ice.proxy_only_if_behind_proxy" to true,
            // Security: show look-alike (homograph) domains as punycode.
            "network.IDN_show_punycode" to true,
            // Speed: more parallel connections, fewer artificial delays, longer DNS cache.
            "network.http.max-persistent-connections-per-server" to 10,
            "network.http.max-urgent-start-excessive-connections-per-host" to 5,
            "network.http.pacing.requests.enabled" to false,
            "network.dnsCacheEntries" to 1000,
            "network.dnsCacheExpiration" to 3600,
            "network.ssl_tokens_cache_capacity" to 10240,
            "content.notify.interval" to 100000,
            // Gaming: GPU-accelerated 2D canvas (driver blocklist still applies).
            "gfx.canvas.accelerated" to true,
            "dom.gamepad.enabled" to true
        )
    }
}
