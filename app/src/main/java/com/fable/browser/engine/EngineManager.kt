package com.fable.browser.engine

import android.content.Context
import com.fable.browser.data.model.DataSaverMode
import android.net.NetworkCapabilities
import android.net.Network
import android.net.ConnectivityManager
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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.OrientationController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val proxyConfig: ProxyConfig,
    private val activityBridge: ActivityBridge,
    private val passwordManager: PasswordManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var _runtime: GeckoRuntime? = null
    @Volatile private var shieldExtension: WebExtension? = null

    val runtime: GeckoRuntime
        get() = _runtime ?: synchronized(this) {
            _runtime ?: createRuntime().also { _runtime = it }
        }

    private val warmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        FableUnifiedEngine.prewarmAsync(context)
        FableNetworkOptimizer.initAsync(context)
        mainHandler.post {
            runtime
            scope.launch {
                val initial = settingsStore.settings.first()
                applySettings(initial)
                if (!quietNetwork(initial) && initial.fastConnections) {
                    speculativeConnect("https://fonts.gstatic.com")
                    speculativeConnect("https://fonts.googleapis.com")
                    speculativeConnect("https://cdn.jsdelivr.net")
                    speculativeConnect("https://cdnjs.cloudflare.com")
                }
            }
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
            .consoleOutput(com.fable.browser.BuildConfig.DEBUG)
            .remoteDebuggingEnabled(false)
            .aboutConfigEnabled(false)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .largeKeepaliveFactor(4)

        writeEngineConfig()?.let { builder.configFilePath(it) }

        val runtime = GeckoRuntime.create(context, builder.build())
        runCatching { runtime.setAutocompleteStorageDelegate(passwordManager) }
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

    private val webExecutor by lazy { org.mozilla.geckoview.GeckoWebExecutor(runtime) }

    fun speculativeConnect(url: String) {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
        runCatching { webExecutor.speculativeConnect(url) }
    }

    private val engineFlags by lazy {
        context.getSharedPreferences(ENGINE_FLAGS_FILE, Context.MODE_PRIVATE)
    }

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
            lines += "  $k: '" + v.replace("'", "''") + "'"
        }
        featurePrefs(null).forEach { (k, default) ->
            val v = flags[k] as? Boolean ?: default
            if (v) lines += "  $k: true"
        }
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    @Volatile private var metered = false
    @Volatile private var lastSettings: Settings? = null
    private var networkWatchStarted = false

    private fun superProxyOn(d: Settings): Boolean = d.superProxyEnabled && d.superProxyHost.isNotBlank()

    private fun dataSaverOn(d: Settings): Boolean = when (d.dataSaver) {
        DataSaverMode.ALWAYS -> true
        DataSaverMode.MOBILE -> metered
        DataSaverMode.OFF -> false
    }

    private fun quietNetwork(d: Settings): Boolean = d.blockPrefetch || dataSaverOn(d) || superProxyOn(d)

    private fun startNetworkWatch() {
        if (networkWatchStarted) return
        networkWatchStarted = true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        metered = runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.let { meteredOf(it) } ?: cm.isActiveNetworkMetered
        }.getOrDefault(false)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = meteredOf(caps)
                    if (now == metered) return
                    metered = now
                    scope.launch {
                        val s = lastSettings ?: return@launch
                        if (s.dataSaver == DataSaverMode.MOBILE) {
                            proxyConfig.setDataSaverActive(dataSaverOn(s))
                            if (_runtime != null) applyOptionalPrefs(s)
                        }
                    }
                }
            })
        }
    }

    private fun meteredOf(caps: NetworkCapabilities): Boolean {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) return false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
        }
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun privacyPrefs(s: Settings?): List<Pair<String, Boolean>> {
        val d = s ?: Settings()
        val quiet = quietNetwork(d)
        val anyProxy = superProxyOn(d) || (d.incognitoProxyEnabled && d.incognitoProxyHost.isNotBlank())
        val wasmOn = !d.disableWasm || d.superGameMode
        val wasmAdv = wasmOn && (d.featureWasmExperimental || d.gameMode || d.superGameMode || d.hybridGeckoBlinkBeta)
        return listOf(
            "media.peerconnection.ice.no_host" to (d.hideLocalIp || superProxyOn(d)),
            "media.peerconnection.ice.default_address_only" to (d.hideLocalIp || superProxyOn(d)),
            "network.proxy.failover_direct" to !anyProxy,
            "network.http.http3.enable" to true,
            "network.http.http3.enable_0rtt" to d.fastConnections,
            "security.tls.enable_0rtt_data" to d.fastConnections,
            "network.http.rcwn.enabled" to false,
            "network.dns.disablePrefetch" to quiet,
            "network.dns.disablePrefetchFromHTTPS" to (quiet || !d.fastConnections),
            "privacy.query_stripping.enabled" to d.stripTrackingParams,
            "privacy.query_stripping.enabled.pbmode" to d.incognitoStripParams,
            "privacy.globalprivacycontrol.pbmode.enabled" to d.incognitoGpc,
            "layers.gpu-process.enabled" to d.gpuProcess,
            "webgl.enable-debug-renderer-info" to true,
            "media.peerconnection.ice.proxy_only" to d.webRtcProxyOnly,
            "media.peerconnection.enabled" to !d.webRtcDisabled,
            "network.prefetch-next" to (!quiet && !optimizing(d)),
            "network.predictor.enabled" to (!quiet && !optimizing(d)),
            "javascript.options.baselinejit" to (!d.noJit || d.superGameMode),
            "javascript.options.ion" to (!d.noJit || d.superGameMode),
            "javascript.options.native_regexp" to (!d.noJit || d.superGameMode),
            "javascript.options.wasm_optimizingjit" to (!d.noJit || d.superGameMode),
            "javascript.options.wasm" to wasmOn,
            "javascript.options.wasm_simd" to wasmOn,
            "javascript.options.wasm_relaxed_simd" to wasmAdv,
            "javascript.options.wasm_gc" to wasmAdv,
            "javascript.options.wasm_tail_calls" to wasmAdv,
            "javascript.options.wasm_exceptions" to wasmAdv,
            "javascript.options.wasm_function_references" to wasmAdv,
            "javascript.options.wasm_multi_memory" to wasmAdv,
            "javascript.options.wasm_memory64" to wasmAdv,
            "javascript.options.wasm_extended_const" to wasmAdv,
            "javascript.options.wasm_branch_hinting" to wasmAdv,
            "javascript.options.wasm_js_string_builtins" to wasmAdv,
            "javascript.options.shared_memory" to wasmOn,
            "gfx.webrender.all" to true,
            "gfx.webrender.compositor" to true,
            "gfx.webrender.precache-shaders" to (d.gameMode || d.superGameMode),
            "webgl.force-enabled" to true,
            "webgl.msaa-force" to false,
            "webgl.lose-context-on-memory-pressure" to false,
            "media.hardware-video-decoding.enabled" to true,
            "media.navigator.mediadatadecoder_vpx_enabled" to true,
            "media.navigator.mediadatadecoder_h264_enabled" to true,
            "dom.ipc.processPriorityManager.enabled" to true
        )
    }

    private fun optimizing(s: Settings): Boolean = MemoryPolicy.optimizing(context, s)

    private fun intPrefs(s: Settings?): List<Pair<String, Int>> {
        val d = s ?: Settings()
        val policy = MemoryPolicy.resolve(context, d)
        return listOf(
            "network.http.speculative-parallel-limit" to if (quietNetwork(d)) 0 else 12,
            "network.http.max-persistent-connections-per-server" to if (d.fastConnections) 16 else 6,
            "gfx.downloadable_fonts.fallback_delay" to 0,
            "gfx.downloadable_fonts.fallback_delay_short" to 0,
            "gfx.display.max-frame-rate" to if (d.highRefreshRate) 120 else 60,
            "network.http.max-urgent-start-excessive-connections-per-host" to if (d.fastConnections) 5 else 3,
            "media.preload.auto" to if (dataSaverOn(d)) 1 else 2,
            "network.http.referer.XOriginTrimmingPolicy" to if (d.strictReferrer) 2 else 0,
            "privacy.bounceTrackingProtection.mode" to if (d.bounceTracking) 1 else 0,
            "browser.cache.memory.capacity" to policy.memoryCacheKb,
            "browser.sessionhistory.max_total_viewers" to policy.backForwardPages,
            "image.mem.surfacecache.max_size_kb" to policy.imageCacheKb,
            "dom.min_background_timeout_value" to if (optimizing(d)) 10_000 else 1_000,
            "dom.timeout.background_throttling_max_budget" to if (optimizing(d)) 20 else 50,
            "browser.sessionhistory.max_entries" to if (optimizing(d)) 25 else 50,
            "image.mem.decode_bytes_at_a_time" to if (d.gameMode || d.superGameMode) 524_288 else 65_536,
            "browser.cache.disk.max_entry_size" to 51_200,
            "network.buffer.cache.size" to 262_144,
            "network.buffer.cache.count" to 128,
            "network.http.send_window_size" to 1_048_576,
            "network.websocket.max-connections" to 200,
            "dom.workers.maxPerDomain" to 64,
            "dom.ipc.processCount.webIsolated" to if (optimizing(d)) 2 else 4,
            "layout.frame_rate" to 0,
            "webgl.max-contexts-per-principal" to 32,
            "javascript.options.mem.nursery.max_kb" to if (d.gameMode || d.superGameMode) 65_536 else 16_384,
            "javascript.options.mem.gc_incremental_slice_ms" to if (d.gameMode || d.superGameMode) 3 else 10,
            "javascript.options.mem.gc_high_frequency_time_limit_ms" to 400
        )
    }

    private fun stringPrefs(s: Settings?): List<Pair<String, String>> {
        val d = s ?: Settings()
        return listOf(
            "privacy.fingerprintingProtection.overrides" to fingerprintOverrides(d),
            "privacy.fingerprintingProtection.granularOverrides" to trustedSitesJson(d)
        )
    }

    private fun fingerprintOverrides(d: Settings): String {
        val activeFp = d.normalTabShield && !d.gameMode && !d.superGameMode
        val targets = listOf(
            (activeFp && d.fpCanvas) to listOf("CanvasRandomization"),
            (activeFp && d.fpCanvasThirdParty) to listOf("CanvasExtractionFromThirdPartiesIsBlocked"),
            (activeFp && d.fpFonts) to listOf("FontVisibilityBaseSystem", "FontVisibilityLangPack"),
            (activeFp && d.hideGpuModel) to listOf("WebGLRenderInfo"),
            (activeFp && d.fpWebGlLimits) to listOf("WebGLRenderCapability"),
            (activeFp && d.fpUserAgent) to listOf(
                "NavigatorUserAgent", "HttpUserAgent", "NavigatorAppVersion",
                "NavigatorOscpu", "NavigatorPlatform", "NavigatorBuildID"
            ),
            (activeFp && d.fpHardware) to listOf("NavigatorHWConcurrencyTiered", "MaxTouchPointsCollapse"),
            (activeFp && d.fpScreen) to listOf(
                "ScreenRect", "ScreenAvailRect", "WindowOuterSize", "WindowScreenXY",
                "ScreenPixelDepth", "CSSDeviceSize"
            ),
            (activeFp && d.fpAudio) to listOf("AudioContext", "AudioSampleRate"),
            (activeFp && d.fpMedia) to listOf("MediaDevices", "MediaCapabilities", "SpeechSynthesis", "NetworkConnection"),
            (activeFp && d.fpSensors) to listOf("DeviceSensors"),
            (activeFp && d.fpTimezone) to listOf("JSDateTimeUTC"),
            (activeFp && d.fpLanguage) to listOf("JSLocale"),
            (activeFp && d.fpTimers) to listOf("ReduceTimerPrecision", "FrameRate"),
            (activeFp && d.fpTouch) to listOf("TouchEvents", "PointerEvents", "MaxTouchPoints", "CSSPointerCapabilities"),
            false to listOf("WindowDevicePixelRatio")
        )
        return targets.flatMap { (on, names) -> names.map { (if (on) "+" else "-") + it } }
            .joinToString(",")
    }

    private fun trustedSitesJson(d: Settings): String {
        val hosts = d.fpTrustedSites.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) return "[]"
        return hosts.joinToString(prefix = "[", postfix = "]", separator = ",") { host ->
            "{\"firstPartyDomain\":\"$host\",\"overrides\":\"-AllTargets\"}"
        }
    }

    private fun featurePrefs(s: Settings?): List<Pair<String, Boolean>> {
        val d = s ?: Settings()
        return listOf(
            "dom.vibrator.enabled" to d.vibrationApi,
            "dom.battery.enabled" to d.featureBatteryApi,
            "dom.netinfo.enabled" to d.featureNetworkInfo,
            "dom.webgpu.enabled" to (d.featureWebGpu || d.hybridWebGpuBoost || d.gameMode),
            "media.webspeech.recognition.enable" to d.featureSpeechRecognition,
            "media.webspeech.recognition.force_enable" to d.featureSpeechRecognition,
            "webgl.enable-draft-extensions" to d.featureWebGlDraft,
            "javascript.options.wasm_relaxed_simd" to d.featureWasmExperimental
        )
    }

    @Volatile private var appliedPrefs: List<Any>? = null

    private fun applyOptionalPrefs(s: Settings) {
        val privacy = privacyPrefs(s)
        val features = featurePrefs(s)
        val ints = intPrefs(s)
        val strings = stringPrefs(s)
        val signature = listOf(privacy, features, ints, strings)
        if (signature == appliedPrefs) return
        appliedPrefs = signature
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

    private fun installShieldExtension(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller
            .ensureBuiltIn("resource://android/assets/extensions/fable_shield/", SHIELD_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        if (ext.metaData.allowedInPrivateBrowsing) {
                            useShield(ext)
                        } else {
                            controller.setAllowedInPrivateBrowsing(ext, true).accept(
                                { updated -> useShield(updated ?: ext) },
                                { useShield(ext) }
                            )
                        }
                    }
                },
                {}
            )
    }

    private fun useShield(ext: WebExtension) {
        shieldExtension = ext
        proxyConfig.attach(ext)
    }

    fun setTabActive(session: GeckoSession, active: Boolean) {
        runCatching { _runtime?.webExtensionController?.setTabActive(session, active) }
    }

    private fun observeSettings() {
        scope.launch {
            settingsStore.settings.collect { applySettings(it) }
        }
    }

    private fun applySettings(s: Settings) {
        val rt = _runtime ?: return
        lastSettings = s
        startNetworkWatch()
        proxyConfig.setDataSaverActive(dataSaverOn(s))
        proxyConfig.update(s)

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

        rt.settings.setGlobalPrivacyControl(s.sendGpc)

        runCatching {
            val normalFp = s.normalTabShield && !s.gameMode && !s.superGameMode
            rt.settings.setBaselineFingerprintingProtection(normalFp)
            rt.settings.setFingerprintingProtectionPrivateBrowsing(s.incognitoFingerprintProtection)
            rt.settings.setFingerprintingProtection(normalFp && s.fingerprintProtectionAll)
            rt.settings.contentBlocking.setCookieBehaviorPrivateMode(
                if (s.incognitoBlockThirdPartyCookies) ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
                else ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
            )
        }
        runCatching { rt.settings.setFetchPriorityEnabled(true) }

        applyOptionalPrefs(s)

        rt.settings.setAutomaticFontSizeAdjustment(false)
        rt.settings.setFontSizeFactor(s.textZoomPercent / 100f)
        rt.settings.setPreferredColorScheme(
            if (s.forceDarkWeb) GeckoRuntimeSettings.COLOR_SCHEME_DARK
            else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        )

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

    fun clearAllBrowsingData(onDone: (() -> Unit)? = null) {
        val rt = _runtime ?: return
        rt.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
            .accept({ onDone?.invoke() }, { onDone?.invoke() })
    }

    fun clearCookies() {
        FableUnifiedEngine.clearBlinkData(incognitoOnly = false)
        _runtime?.storageController
            ?.clearData(org.mozilla.geckoview.StorageController.ClearFlags.COOKIES)
    }

    fun clearCache() {
        _runtime?.storageController
            ?.clearData(org.mozilla.geckoview.StorageController.ClearFlags.NETWORK_CACHE or
                org.mozilla.geckoview.StorageController.ClearFlags.IMAGE_CACHE)
    }

    fun refreshFilterRules() = proxyConfig.pushRules()

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

        private val BASE_PREFS: List<Pair<String, Any>> = listOf(
            "javascript.enabled" to true,
            "signon.rememberSignons" to true,
            "signon.autofillForms" to true,
            "signon.generation.enabled" to true,
            "dom.ipc.processPrelaunch.enabled" to false,
            "browser.cache.disk.smart_size.enabled" to false,
            "browser.cache.disk.capacity" to 262144,
            "media.peerconnection.ice.obfuscate_host_addresses" to true,
            "media.peerconnection.ice.proxy_only_if_behind_proxy" to true,
            "network.IDN_show_punycode" to true,
            "network.http.pacing.requests.enabled" to false,
            "network.dnsCacheEntries" to 2048,
            "network.dnsCacheExpiration" to 3600,
            "network.ssl_tokens_cache_capacity" to 16384,
            "content.notify.interval" to 16000,
            "javascript.options.warp" to true,
            "javascript.options.parallel_parsing" to true,
            "javascript.options.wasm_baselinejit" to true,
            "dom.script_loader.bytecode_cache.enabled" to true,
            "dom.script_loader.bytecode_cache.strategy" to 0,
            "gfx.downloadable_fonts.enabled" to true,
            "gfx.font_rendering.opentype_svg.enabled" to true,
            "gfx.canvas.accelerated" to true,
            "gfx.canvas.accelerated.cache-items" to 4096,
            "gfx.canvas.accelerated.cache-size" to 512,
            "layout.css.stylo-threads" to 4,
            "gfx.webrender.max-shared-surface-size" to 2048,
            "image.mem.shared" to true,
            "javascript.options.mem.gc_ParallelMarkingEnabled" to true,
            "dom.gamepad.enabled" to true
        )
    }
}
