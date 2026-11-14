package com.fable.browser.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fable.browser.data.model.DataSaverMode
import com.fable.browser.data.model.DohProvider
import com.fable.browser.data.model.IncognitoAutoWipe
import com.fable.browser.data.model.MemoryMode
import com.fable.browser.data.model.PerformanceProfile
import com.fable.browser.data.model.SearchEngine
import com.fable.browser.data.model.SuperDeviceRange
import com.fable.browser.data.model.SuperProxyType
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.data.model.ThemeMode
import com.fable.browser.engine.RecAudioSource
import com.fable.browser.engine.RecBitrate
import com.fable.browser.engine.RecFrameRate
import com.fable.browser.engine.RecOrientation
import com.fable.browser.engine.RecResolution
import com.fable.browser.engine.RecorderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fable_settings")

data class Settings(
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val homepage: String = "about:blank",
    val restoreTabs: Boolean = true,
    val toolbarAtTop: Boolean = true,
    val hideToolbarOnScroll: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val httpsOnly: Boolean = true,
    val sendGpc: Boolean = true,
    val hideLocalIp: Boolean = true,
    val fingerprintProtectionAll: Boolean = true,
    val hideGpuModel: Boolean = true,
    val gameMode: Boolean = true,
    val highRefreshRate: Boolean = true,
    val vibrationApi: Boolean = true,
    val featureBatteryApi: Boolean = false,
    val featureNetworkInfo: Boolean = false,
    val featureWebGpu: Boolean = true,
    val featureSpeechRecognition: Boolean = false,
    val featureWebGlDraft: Boolean = false,
    val featureWasmExperimental: Boolean = true,
    val fpCanvas: Boolean = true,
    val fpCanvasThirdParty: Boolean = true,
    val fpFonts: Boolean = true,
    val fpUserAgent: Boolean = true,
    val fpHardware: Boolean = true,
    val fpScreen: Boolean = true,
    val fpAudio: Boolean = true,
    val fpMedia: Boolean = true,
    val fpWebGlLimits: Boolean = false,
    val fpSensors: Boolean = false,
    val fpTimezone: Boolean = false,
    val fpLanguage: Boolean = false,
    val fpTimers: Boolean = false,
    val webRtcProxyOnly: Boolean = false,
    val webRtcDisabled: Boolean = false,
    val blockPrefetch: Boolean = true,
    val strictReferrer: Boolean = true,
    val bounceTracking: Boolean = true,
    val noJit: Boolean = false,
    val disableWasm: Boolean = false,
    val speedMemory: Boolean = false,
    val incognitoJavascript: Boolean = true,
    val incognitoHttpsOnly: Boolean = true,
    val incognitoBlockThirdPartyCookies: Boolean = false,
    val fpTrustedSites: String = "",
    val stripTrackingParams: Boolean = true,
    val dohProvider: DohProvider = DohProvider.CLOUDFLARE,
    val dohCustomUrl: String = "",
    val clearHistoryOnExit: Boolean = false,
    val clearCookiesOnExit: Boolean = false,
    val clearCacheOnExit: Boolean = false,
    val isolateIncognitoTabs: Boolean = true,
    val fingerprintShield: Boolean = true,
    val forceUtcTimezone: Boolean = true,
    val incognitoLock: Boolean = false,
    val incognitoAutoWipe: IncognitoAutoWipe = IncognitoAutoWipe.OFF,
    val incognitoProxyEnabled: Boolean = false,
    val incognitoProxyHost: String = "",
    val incognitoProxyPort: Int = 9050,
    val incognitoProxyType: String = "SOCKS5",
    val incognitoDisclosureShown: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val forceDarkWeb: Boolean = false,
    val textZoomPercent: Int = 100,
    val normalTabShield: Boolean = false,
    val strictTrackingProtection: Boolean = true,
    val tabUnloadMode: TabUnloadMode = TabUnloadMode.SMART,
    val keepLoadedTabs: Int = 3,
    val memoryMode: MemoryMode = MemoryMode.AUTO,
    val idleTabMinutes: Int = 10,
    val preconnectSearch: Boolean = true,
    val preconnectTyped: Boolean = false,
    val gpuProcess: Boolean = true,
    val freezeDetector: Boolean = true,
    val keepIncognitoTabs: Boolean = false,
    val superDeviceRange: SuperDeviceRange = SuperDeviceRange.ANY,
    val superDevicePick: String = "",
    val superRandomTimezone: Boolean = true,
    val superReveal: Set<String> = emptySet(),
    val superGameMode: Boolean = false,
    val superGpu: String = "",
    val autoClearCache: Boolean = true,
    val keepSuperTabs: Boolean = false,
    val superProxyEnabled: Boolean = false,
    val superProxyType: SuperProxyType = SuperProxyType.SOCKS5,
    val superProxyHost: String = "",
    val superProxyPort: Int = 1080,
    val fastConnections: Boolean = true,
    val dataSaver: DataSaverMode = DataSaverMode.OFF,
    val incognitoAdBlock: Boolean = true,
    val incognitoTrackingProtection: Boolean = true,
    val incognitoStripParams: Boolean = true,
    val incognitoRandomUserAgent: Boolean = false,
    val incognitoTrimReferrer: Boolean = true,
    val incognitoStripClientHints: Boolean = true,
    val incognitoSendDnt: Boolean = true,
    val passwordSave: Boolean = true,
    val passwordAutofill: Boolean = true,
    val passwordIncognito: Boolean = false,
    val passwordSuggestStrong: Boolean = true,
    val passwordNeverSites: Set<String> = emptySet(),
    val optimizerEnabled: Boolean = true,
    val adBlockCosmetic: Boolean = true,
    val adBlockAutoUpdate: Boolean = true,
    val adBlockAllowSites: Set<String> = emptySet(),
    val incognitoGpc: Boolean = true,
    val incognitoFingerprintProtection: Boolean = true,
    val gameTouchFix: Boolean = true,
    val fpTouch: Boolean = false,
    val archiveReadOnly: Boolean = true,
    val archiveNetworkStream: Boolean = true,
    val archiveIncludeMemory: Boolean = true,
    val archiveIncludeCanvas: Boolean = true,
    val archiveIncludeStorage: Boolean = true,
    val archiveGuessAssets: Boolean = true,
    val archiveDeepHooks: Boolean = false,
    val archiveMaxFiles: Int = 4000,
    val archiveMaxTotalMb: Int = 600,
    val archiveMaxFileMb: Int = 120,
    val archiveStreamBufferMb: Int = 160,
    val hybridGeckoBlinkBeta: Boolean = true,
    val hybridAutoForGames: Boolean = true,
    val hybridNativeCpuBoost: Boolean = true,
    val hybridZeroTimerSlack: Boolean = true,
    val hybridReportedCores: Int = 8,
    val hybridOffscreenPreRaster: Boolean = true,
    val hybridHighPerfWebGl: Boolean = true,
    val hybridCameraAntiShutter: Boolean = true,
    val hybridZeroDelayClicks: Boolean = true,
    val hybridCanvasRectCacheMs: Int = 250,
    val hybridSuppressPostRenderTimer: Boolean = false,
    val hybridFastFontSwap: Boolean = true,
    val hybridCppFastAssetBypass: Boolean = true,
    val hybridGeckoAdBlock: Boolean = true,
    val hybridHideLocalIp: Boolean = true,
    val hybridPersistTabMode: Boolean = true,
    val hybridWebGpuBoost: Boolean = true,
    val hybrid3d2dAccel: Boolean = true,
    val hybridTeleportStabilizer: Boolean = true,
    val hybridHibernateGecko: Boolean = true,
    val hybridCustomDomains: String = "bloxd.io, staging.bloxd.io, testufo.com, browserbench.org, poki.com, crazygames.com, krunker.io, 1v1.lol, voxiom.io, kirka.io, kour.io, miniblox.io",
    val recResolution: RecResolution = RecResolution.RES_1640_720,
    val recBitrate: RecBitrate = RecBitrate.MBPS_50,
    val recOrientation: RecOrientation = RecOrientation.LANDSCAPE,
    val recAudioSource: RecAudioSource = RecAudioSource.SYSTEM,
    val recFrameRate: RecFrameRate = RecFrameRate.FPS_60,
    val recStopOnScreenLock: Boolean = true,
    val recShowTouches: Boolean = false,
    val recShowButtonPresses: Boolean = false,
    val recZeroLagBlinkMode: Boolean = true,
    val batterySaverMaster: Boolean = false,
    val batteryAutoLowPct: Int = 20,
    val batteryThermalGuard: Boolean = true,
    val batteryAdaptiveRefresh: Boolean = true,
    val batteryGameMaxFps: Int = 120,
    val batteryGameRenderScale: Int = 100,
    val batteryEcoCpu: Boolean = false,
    val batteryIdleGameFps: Int = 30,
    val batteryIdleTimeoutSec: Int = 8,
    val batteryFreezeBgTabs: Boolean = true,
    val batteryReduceMotion: Boolean = false,
    val batteryOledDimPct: Int = 0
) {
    fun toRecorderConfig(): RecorderConfig = RecorderConfig(
        resolution = recResolution,
        bitrate = recBitrate,
        orientation = recOrientation,
        audioSource = recAudioSource,
        frameRate = recFrameRate,
        stopOnScreenLock = recStopOnScreenLock,
        showTouches = recShowTouches,
        showButtonPresses = recShowButtonPresses,
        zeroLagBlinkMode = recZeroLagBlinkMode
    )
    val performanceProfile: PerformanceProfile
        get() = when {
            normalTabShield && strictTrackingProtection && adBlockEnabled -> PerformanceProfile.MAX_PRIVACY
            !normalTabShield && !strictTrackingProtection && adBlockEnabled -> PerformanceProfile.BALANCED
            !normalTabShield && !strictTrackingProtection && !adBlockEnabled -> PerformanceProfile.MAX_SPEED
            else -> PerformanceProfile.CUSTOM
        }
}

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val HOMEPAGE = stringPreferencesKey("homepage")
        val RESTORE_TABS = booleanPreferencesKey("restore_tabs")
        val TOOLBAR_TOP = booleanPreferencesKey("toolbar_top")
        val HIDE_TOOLBAR_ON_SCROLL = booleanPreferencesKey("hide_toolbar_on_scroll")
        val AD_BLOCK = booleanPreferencesKey("ad_block")
        val THIRD_PARTY_COOKIES = booleanPreferencesKey("block_3p_cookies")
        val HTTPS_ONLY = booleanPreferencesKey("https_only")
        val SEND_GPC = booleanPreferencesKey("send_gpc")
        val HIDE_LOCAL_IP = booleanPreferencesKey("hide_local_ip")
        val FPP_ALL = booleanPreferencesKey("fpp_all_tabs")
        val HIDE_GPU = booleanPreferencesKey("hide_gpu_model")
        val GAME_MODE = booleanPreferencesKey("game_mode")
        val HIGH_REFRESH = booleanPreferencesKey("high_refresh_rate")
        val VIBRATION_API = booleanPreferencesKey("vibration_api")
        val FEAT_BATTERY = booleanPreferencesKey("feat_battery_api")
        val FEAT_NETINFO = booleanPreferencesKey("feat_network_info")
        val FEAT_WEBGPU = booleanPreferencesKey("feat_webgpu")
        val FEAT_SPEECH = booleanPreferencesKey("feat_speech_recognition")
        val FEAT_WEBGL_DRAFT = booleanPreferencesKey("feat_webgl_draft")
        val FEAT_WASM_EXP = booleanPreferencesKey("feat_wasm_experimental")
        val FP_CANVAS = booleanPreferencesKey("fp_canvas")
        val FP_CANVAS_3P = booleanPreferencesKey("fp_canvas_third_party")
        val FP_FONTS = booleanPreferencesKey("fp_fonts")
        val FP_UA = booleanPreferencesKey("fp_user_agent")
        val FP_HW = booleanPreferencesKey("fp_hardware")
        val FP_SCREEN = booleanPreferencesKey("fp_screen")
        val FP_AUDIO = booleanPreferencesKey("fp_audio")
        val FP_MEDIA = booleanPreferencesKey("fp_media")
        val FP_WEBGL_LIMITS = booleanPreferencesKey("fp_webgl_limits")
        val FP_SENSORS = booleanPreferencesKey("fp_sensors")
        val FP_TIMEZONE = booleanPreferencesKey("fp_timezone")
        val FP_LANGUAGE = booleanPreferencesKey("fp_language")
        val FP_TIMERS = booleanPreferencesKey("fp_timers")
        val WEBRTC_PROXY_ONLY = booleanPreferencesKey("webrtc_proxy_only")
        val WEBRTC_DISABLED = booleanPreferencesKey("webrtc_disabled")
        val BLOCK_PREFETCH = booleanPreferencesKey("block_prefetch")
        val STRICT_REFERRER = booleanPreferencesKey("strict_referrer")
        val BOUNCE_TRACKING = booleanPreferencesKey("bounce_tracking")
        val NO_JIT = booleanPreferencesKey("no_jit")
        val DISABLE_WASM = booleanPreferencesKey("disable_wasm")
        val SPEED_MEMORY = booleanPreferencesKey("speed_memory")
        val INCOGNITO_JS = booleanPreferencesKey("incognito_javascript_v2")
        val INCOGNITO_HTTPS = booleanPreferencesKey("incognito_https_only")
        val INCOGNITO_3P_COOKIES = booleanPreferencesKey("incognito_block_3p_cookies")
        val FP_TRUSTED = stringPreferencesKey("fp_trusted_sites")
        val STRIP_TRACKING = booleanPreferencesKey("strip_tracking")
        val DOH_PROVIDER = stringPreferencesKey("doh_provider")
        val DOH_CUSTOM_URL = stringPreferencesKey("doh_custom_url")
        val CLEAR_HISTORY_EXIT = booleanPreferencesKey("clear_history_exit")
        val CLEAR_COOKIES_EXIT = booleanPreferencesKey("clear_cookies_exit")
        val CLEAR_CACHE_EXIT = booleanPreferencesKey("clear_cache_exit")
        val ISOLATE_INCOGNITO = booleanPreferencesKey("isolate_incognito")
        val FP_SHIELD = booleanPreferencesKey("fp_shield")
        val FORCE_UTC = booleanPreferencesKey("force_utc")
        val INCOGNITO_LOCK = booleanPreferencesKey("incognito_lock")
        val INCOGNITO_AUTO_WIPE = stringPreferencesKey("incognito_auto_wipe")
        val PROXY_ENABLED = booleanPreferencesKey("incognito_proxy_enabled")
        val PROXY_HOST = stringPreferencesKey("incognito_proxy_host")
        val PROXY_PORT = intPreferencesKey("incognito_proxy_port")
        val PROXY_TYPE = stringPreferencesKey("incognito_proxy_type")
        val DISCLOSURE_SHOWN = booleanPreferencesKey("incognito_disclosure_shown")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FORCE_DARK_WEB = booleanPreferencesKey("force_dark_web")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
        val NORMAL_TAB_SHIELD = booleanPreferencesKey("normal_tab_shield")
        val STRICT_ETP = booleanPreferencesKey("strict_tracking_protection")
        val TAB_UNLOAD_MODE = stringPreferencesKey("tab_unload_mode")
        val KEEP_LOADED_TABS = intPreferencesKey("keep_loaded_tabs")
        val MEMORY_MODE = stringPreferencesKey("memory_mode")
        val IDLE_TAB_MINUTES = intPreferencesKey("idle_tab_minutes")
        val PRECONNECT_SEARCH = booleanPreferencesKey("preconnect_search")
        val PRECONNECT_TYPED = booleanPreferencesKey("preconnect_typed")
        val GPU_PROCESS = booleanPreferencesKey("gpu_process")
        val FREEZE_DETECTOR = booleanPreferencesKey("freeze_detector")
        val KEEP_INCOGNITO_TABS = booleanPreferencesKey("keep_incognito_tabs")
        val SUPER_DEVICE_RANGE = stringPreferencesKey("super_device_range")
        val SUPER_RANDOM_TZ = booleanPreferencesKey("super_random_tz")
        val SUPER_REVEAL = stringSetPreferencesKey("super_reveal")
        val SUPER_GAME_MODE = booleanPreferencesKey("super_game_mode")
        val SUPER_GPU = stringPreferencesKey("super_gpu")
        val AUTO_CLEAR_CACHE = booleanPreferencesKey("auto_clear_cache")
        val KEEP_SUPER_TABS = booleanPreferencesKey("keep_super_tabs")
        val SUPER_PROXY_ENABLED = booleanPreferencesKey("super_proxy_enabled")
        val SUPER_PROXY_TYPE = stringPreferencesKey("super_proxy_type")
        val SUPER_PROXY_HOST = stringPreferencesKey("super_proxy_host")
        val SUPER_PROXY_PORT = intPreferencesKey("super_proxy_port")
        val SUPER_DEVICE_PICK = stringPreferencesKey("super_device_pick")
        val FAST_CONNECTIONS = booleanPreferencesKey("fast_connections")
        val DATA_SAVER = stringPreferencesKey("data_saver")
        val INCOGNITO_AD_BLOCK = booleanPreferencesKey("incognito_ad_block")
        val INCOGNITO_TP = booleanPreferencesKey("incognito_tracking_protection")
        val INCOGNITO_STRIP = booleanPreferencesKey("incognito_strip_params")
        val INCOGNITO_RANDOM_UA = booleanPreferencesKey("incognito_random_ua")
        val INCOGNITO_TRIM_REFERRER = booleanPreferencesKey("incognito_trim_referrer")
        val INCOGNITO_STRIP_HINTS = booleanPreferencesKey("incognito_strip_client_hints")
        val INCOGNITO_SEND_DNT = booleanPreferencesKey("incognito_send_dnt")
        val PASSWORD_SAVE = booleanPreferencesKey("passwords_offer_save")
        val PASSWORD_AUTOFILL = booleanPreferencesKey("passwords_autofill")
        val PASSWORD_INCOGNITO = booleanPreferencesKey("passwords_incognito")
        val PASSWORD_SUGGEST = booleanPreferencesKey("passwords_suggest_strong")
        val PASSWORD_NEVER = stringSetPreferencesKey("passwords_never_sites")
        val OPTIMIZER = booleanPreferencesKey("optimizer_enabled")
        val AD_BLOCK_COSMETIC = booleanPreferencesKey("adblock_cosmetic")
        val AD_BLOCK_AUTO_UPDATE = booleanPreferencesKey("adblock_auto_update")
        val AD_BLOCK_ALLOW = stringSetPreferencesKey("adblock_allow_sites")
        val INCOGNITO_GPC = booleanPreferencesKey("incognito_gpc")
        val INCOGNITO_FPP = booleanPreferencesKey("incognito_fingerprint_protection")
        val GAME_TOUCH_FIX = booleanPreferencesKey("game_touch_fix")
        val FP_TOUCH = booleanPreferencesKey("fp_touch")
        val ARCHIVE_READ_ONLY = booleanPreferencesKey("archive_read_only")
        val ARCHIVE_NET_STREAM = booleanPreferencesKey("archive_net_stream")
        val ARCHIVE_INC_MEMORY = booleanPreferencesKey("archive_inc_memory")
        val ARCHIVE_INC_CANVAS = booleanPreferencesKey("archive_inc_canvas")
        val ARCHIVE_INC_STORAGE = booleanPreferencesKey("archive_inc_storage")
        val ARCHIVE_GUESS_ASSETS = booleanPreferencesKey("archive_guess_assets")
        val ARCHIVE_DEEP_HOOKS = booleanPreferencesKey("archive_deep_hooks")
        val ARCHIVE_MAX_FILES = intPreferencesKey("archive_max_files")
        val ARCHIVE_MAX_TOTAL_MB = intPreferencesKey("archive_max_total_mb")
        val ARCHIVE_MAX_FILE_MB = intPreferencesKey("archive_max_file_mb")
        val ARCHIVE_STREAM_BUF_MB = intPreferencesKey("archive_stream_buf_mb")
        val HYBRID_GECKO_BLINK_BETA = booleanPreferencesKey("hybrid_gecko_blink_beta_v2")
        val HYBRID_AUTO_FOR_GAMES = booleanPreferencesKey("hybrid_auto_for_games")
        val HYBRID_NATIVE_CPU_BOOST = booleanPreferencesKey("hybrid_native_cpu_boost")
        val HYBRID_ZERO_TIMER_SLACK = booleanPreferencesKey("hybrid_zero_timer_slack")
        val HYBRID_REPORTED_CORES = intPreferencesKey("hybrid_reported_cores")
        val HYBRID_OFFSCREEN_PRERASTER = booleanPreferencesKey("hybrid_offscreen_preraster")
        val HYBRID_HIGH_PERF_WEBGL = booleanPreferencesKey("hybrid_high_perf_webgl")
        val HYBRID_CAMERA_ANTI_SHUTTER = booleanPreferencesKey("hybrid_camera_anti_shutter")
        val HYBRID_ZERO_DELAY_CLICKS = booleanPreferencesKey("hybrid_zero_delay_clicks")
        val HYBRID_CANVAS_RECT_CACHE_MS = intPreferencesKey("hybrid_canvas_rect_cache_ms")
        val HYBRID_SUPPRESS_POST_TIMER = booleanPreferencesKey("hybrid_suppress_post_timer")
        val HYBRID_FAST_FONT_SWAP = booleanPreferencesKey("hybrid_fast_font_swap")
        val HYBRID_CPP_FAST_ASSET = booleanPreferencesKey("hybrid_cpp_fast_asset")
        val HYBRID_GECKO_AD_BLOCK = booleanPreferencesKey("hybrid_gecko_ad_block")
        val HYBRID_HIDE_LOCAL_IP = booleanPreferencesKey("hybrid_hide_local_ip")
        val HYBRID_PERSIST_TAB_MODE = booleanPreferencesKey("hybrid_persist_tab_mode")
        val HYBRID_WEBGPU_BOOST = booleanPreferencesKey("hybrid_webgpu_boost")
        val HYBRID_3D_2D_ACCEL = booleanPreferencesKey("hybrid_3d_2d_accel")
        val HYBRID_TELEPORT_STABILIZER = booleanPreferencesKey("hybrid_teleport_stabilizer")
        val HYBRID_HIBERNATE_GECKO = booleanPreferencesKey("hybrid_hibernate_gecko")
        val HYBRID_CUSTOM_DOMAINS = stringPreferencesKey("hybrid_custom_domains")
        val REC_RESOLUTION = stringPreferencesKey("rec_resolution")
        val REC_BITRATE = stringPreferencesKey("rec_bitrate")
        val REC_ORIENTATION = stringPreferencesKey("rec_orientation")
        val REC_AUDIO_SOURCE = stringPreferencesKey("rec_audio_source")
        val REC_FRAME_RATE = stringPreferencesKey("rec_frame_rate")
        val REC_STOP_LOCK = booleanPreferencesKey("rec_stop_on_lock")
        val REC_SHOW_TOUCHES = booleanPreferencesKey("rec_show_touches")
        val REC_SHOW_BUTTONS = booleanPreferencesKey("rec_show_buttons")
        val REC_ZERO_LAG_BLINK = booleanPreferencesKey("rec_zero_lag_blink")
        val BAT_SAVER_MASTER = booleanPreferencesKey("bat_saver_master")
        val BAT_AUTO_LOW_PCT = intPreferencesKey("bat_auto_low_pct")
        val BAT_THERMAL_GUARD = booleanPreferencesKey("bat_thermal_guard")
        val BAT_ADAPTIVE_REFRESH = booleanPreferencesKey("bat_adaptive_refresh")
        val BAT_GAME_MAX_FPS = intPreferencesKey("bat_game_max_fps")
        val BAT_GAME_RENDER_SCALE = intPreferencesKey("bat_game_render_scale")
        val BAT_ECO_CPU = booleanPreferencesKey("bat_eco_cpu")
        val BAT_IDLE_GAME_FPS = intPreferencesKey("bat_idle_game_fps")
        val BAT_IDLE_TIMEOUT_SEC = intPreferencesKey("bat_idle_timeout_sec")
        val BAT_FREEZE_BG_TABS = booleanPreferencesKey("bat_freeze_bg_tabs")
        val BAT_REDUCE_MOTION = booleanPreferencesKey("bat_reduce_motion")
        val BAT_OLED_DIM_PCT = intPreferencesKey("bat_oled_dim_pct")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            searchEngine = enum(p[Keys.SEARCH_ENGINE], SearchEngine.GOOGLE),
            homepage = p[Keys.HOMEPAGE] ?: "about:blank",
            restoreTabs = p[Keys.RESTORE_TABS] ?: true,
            toolbarAtTop = p[Keys.TOOLBAR_TOP] ?: true,
            hideToolbarOnScroll = p[Keys.HIDE_TOOLBAR_ON_SCROLL] ?: false,
            adBlockEnabled = p[Keys.AD_BLOCK] ?: true,
            blockThirdPartyCookies = p[Keys.THIRD_PARTY_COOKIES] ?: true,
            httpsOnly = p[Keys.HTTPS_ONLY] ?: true,
            sendGpc = p[Keys.SEND_GPC] ?: true,
            hideLocalIp = p[Keys.HIDE_LOCAL_IP] ?: true,
            fingerprintProtectionAll = p[Keys.FPP_ALL] ?: true,
            hideGpuModel = p[Keys.HIDE_GPU] ?: true,
            gameMode = p[Keys.GAME_MODE] ?: true,
            highRefreshRate = p[Keys.HIGH_REFRESH] ?: true,
            vibrationApi = p[Keys.VIBRATION_API] ?: true,
            featureBatteryApi = p[Keys.FEAT_BATTERY] ?: false,
            featureNetworkInfo = p[Keys.FEAT_NETINFO] ?: false,
            featureWebGpu = p[Keys.FEAT_WEBGPU] ?: true,
            featureSpeechRecognition = p[Keys.FEAT_SPEECH] ?: false,
            featureWebGlDraft = p[Keys.FEAT_WEBGL_DRAFT] ?: false,
            featureWasmExperimental = p[Keys.FEAT_WASM_EXP] ?: false,
            fpCanvas = p[Keys.FP_CANVAS] ?: true,
            fpCanvasThirdParty = p[Keys.FP_CANVAS_3P] ?: true,
            fpFonts = p[Keys.FP_FONTS] ?: true,
            fpUserAgent = p[Keys.FP_UA] ?: true,
            fpHardware = p[Keys.FP_HW] ?: true,
            fpScreen = p[Keys.FP_SCREEN] ?: true,
            fpAudio = p[Keys.FP_AUDIO] ?: true,
            fpMedia = p[Keys.FP_MEDIA] ?: true,
            fpWebGlLimits = p[Keys.FP_WEBGL_LIMITS] ?: false,
            fpSensors = p[Keys.FP_SENSORS] ?: false,
            fpTimezone = p[Keys.FP_TIMEZONE] ?: false,
            fpLanguage = p[Keys.FP_LANGUAGE] ?: false,
            fpTimers = p[Keys.FP_TIMERS] ?: false,
            webRtcProxyOnly = p[Keys.WEBRTC_PROXY_ONLY] ?: false,
            webRtcDisabled = p[Keys.WEBRTC_DISABLED] ?: false,
            blockPrefetch = p[Keys.BLOCK_PREFETCH] ?: true,
            strictReferrer = p[Keys.STRICT_REFERRER] ?: true,
            bounceTracking = p[Keys.BOUNCE_TRACKING] ?: true,
            noJit = p[Keys.NO_JIT] ?: false,
            disableWasm = p[Keys.DISABLE_WASM] ?: false,
            speedMemory = p[Keys.SPEED_MEMORY] ?: false,
            incognitoJavascript = p[Keys.INCOGNITO_JS] ?: true,
            incognitoHttpsOnly = p[Keys.INCOGNITO_HTTPS] ?: true,
            incognitoBlockThirdPartyCookies = p[Keys.INCOGNITO_3P_COOKIES] ?: false,
            fpTrustedSites = p[Keys.FP_TRUSTED] ?: "",
            stripTrackingParams = p[Keys.STRIP_TRACKING] ?: true,
            dohProvider = enum(p[Keys.DOH_PROVIDER], DohProvider.CLOUDFLARE),
            dohCustomUrl = p[Keys.DOH_CUSTOM_URL] ?: "",
            clearHistoryOnExit = p[Keys.CLEAR_HISTORY_EXIT] ?: false,
            clearCookiesOnExit = p[Keys.CLEAR_COOKIES_EXIT] ?: false,
            clearCacheOnExit = p[Keys.CLEAR_CACHE_EXIT] ?: false,
            isolateIncognitoTabs = p[Keys.ISOLATE_INCOGNITO] ?: true,
            fingerprintShield = p[Keys.FP_SHIELD] ?: true,
            forceUtcTimezone = p[Keys.FORCE_UTC] ?: true,
            incognitoLock = p[Keys.INCOGNITO_LOCK] ?: false,
            incognitoAutoWipe = enum(p[Keys.INCOGNITO_AUTO_WIPE], IncognitoAutoWipe.OFF),
            incognitoProxyEnabled = p[Keys.PROXY_ENABLED] ?: false,
            incognitoProxyHost = p[Keys.PROXY_HOST] ?: "",
            incognitoProxyPort = p[Keys.PROXY_PORT] ?: 9050,
            incognitoProxyType = p[Keys.PROXY_TYPE] ?: "SOCKS5",
            incognitoDisclosureShown = p[Keys.DISCLOSURE_SHOWN] ?: false,
            themeMode = enum(p[Keys.THEME_MODE], ThemeMode.SYSTEM),
            forceDarkWeb = p[Keys.FORCE_DARK_WEB] ?: false,
            textZoomPercent = p[Keys.TEXT_ZOOM] ?: 100,
            normalTabShield = p[Keys.NORMAL_TAB_SHIELD] ?: false,
            strictTrackingProtection = p[Keys.STRICT_ETP] ?: true,
            tabUnloadMode = enum(p[Keys.TAB_UNLOAD_MODE], TabUnloadMode.SMART),
            keepLoadedTabs = (p[Keys.KEEP_LOADED_TABS] ?: 3).coerceIn(1, 8),
            memoryMode = enum(p[Keys.MEMORY_MODE], MemoryMode.AUTO),
            idleTabMinutes = (p[Keys.IDLE_TAB_MINUTES] ?: 10).coerceIn(0, 120),
            preconnectSearch = p[Keys.PRECONNECT_SEARCH] ?: true,
            preconnectTyped = p[Keys.PRECONNECT_TYPED] ?: false,
            gpuProcess = p[Keys.GPU_PROCESS] ?: true,
            freezeDetector = p[Keys.FREEZE_DETECTOR] ?: true,
            keepIncognitoTabs = p[Keys.KEEP_INCOGNITO_TABS] ?: false,
            superDeviceRange = enum(p[Keys.SUPER_DEVICE_RANGE], SuperDeviceRange.ANY),
            superDevicePick = p[Keys.SUPER_DEVICE_PICK] ?: "",
            superRandomTimezone = p[Keys.SUPER_RANDOM_TZ] ?: true,
            superReveal = p[Keys.SUPER_REVEAL] ?: emptySet(),
            superGameMode = p[Keys.SUPER_GAME_MODE] ?: false,
            superGpu = p[Keys.SUPER_GPU] ?: "",
            autoClearCache = p[Keys.AUTO_CLEAR_CACHE] ?: true,
            keepSuperTabs = p[Keys.KEEP_SUPER_TABS] ?: false,
            superProxyEnabled = p[Keys.SUPER_PROXY_ENABLED] ?: false,
            superProxyType = enum(p[Keys.SUPER_PROXY_TYPE], SuperProxyType.SOCKS5),
            superProxyHost = p[Keys.SUPER_PROXY_HOST] ?: "",
            superProxyPort = p[Keys.SUPER_PROXY_PORT] ?: 1080,
            fastConnections = p[Keys.FAST_CONNECTIONS] ?: true,
            dataSaver = enum(p[Keys.DATA_SAVER], DataSaverMode.OFF),
            incognitoAdBlock = p[Keys.INCOGNITO_AD_BLOCK] ?: true,
            incognitoTrackingProtection = p[Keys.INCOGNITO_TP] ?: true,
            incognitoStripParams = p[Keys.INCOGNITO_STRIP] ?: true,
            incognitoRandomUserAgent = p[Keys.INCOGNITO_RANDOM_UA] ?: false,
            incognitoTrimReferrer = p[Keys.INCOGNITO_TRIM_REFERRER] ?: true,
            incognitoStripClientHints = p[Keys.INCOGNITO_STRIP_HINTS] ?: true,
            incognitoSendDnt = p[Keys.INCOGNITO_SEND_DNT] ?: true,
            passwordSave = p[Keys.PASSWORD_SAVE] ?: true,
            passwordAutofill = p[Keys.PASSWORD_AUTOFILL] ?: true,
            passwordIncognito = p[Keys.PASSWORD_INCOGNITO] ?: false,
            passwordSuggestStrong = p[Keys.PASSWORD_SUGGEST] ?: true,
            passwordNeverSites = p[Keys.PASSWORD_NEVER] ?: emptySet(),
            optimizerEnabled = p[Keys.OPTIMIZER] ?: true,
            adBlockCosmetic = p[Keys.AD_BLOCK_COSMETIC] ?: true,
            adBlockAutoUpdate = p[Keys.AD_BLOCK_AUTO_UPDATE] ?: true,
            adBlockAllowSites = p[Keys.AD_BLOCK_ALLOW] ?: emptySet(),
            incognitoGpc = p[Keys.INCOGNITO_GPC] ?: true,
            incognitoFingerprintProtection = p[Keys.INCOGNITO_FPP] ?: true,
            gameTouchFix = p[Keys.GAME_TOUCH_FIX] ?: true,
            fpTouch = p[Keys.FP_TOUCH] ?: false,
            archiveReadOnly = p[Keys.ARCHIVE_READ_ONLY] ?: true,
            archiveNetworkStream = p[Keys.ARCHIVE_NET_STREAM] ?: true,
            archiveIncludeMemory = p[Keys.ARCHIVE_INC_MEMORY] ?: true,
            archiveIncludeCanvas = p[Keys.ARCHIVE_INC_CANVAS] ?: true,
            archiveIncludeStorage = p[Keys.ARCHIVE_INC_STORAGE] ?: true,
            archiveGuessAssets = p[Keys.ARCHIVE_GUESS_ASSETS] ?: true,
            archiveDeepHooks = p[Keys.ARCHIVE_DEEP_HOOKS] ?: false,
            archiveMaxFiles = (p[Keys.ARCHIVE_MAX_FILES] ?: 4000).coerceIn(500, 10000),
            archiveMaxTotalMb = (p[Keys.ARCHIVE_MAX_TOTAL_MB] ?: 600).coerceIn(100, 2000),
            archiveMaxFileMb = (p[Keys.ARCHIVE_MAX_FILE_MB] ?: 120).coerceIn(20, 500),
            archiveStreamBufferMb = (p[Keys.ARCHIVE_STREAM_BUF_MB] ?: 160).coerceIn(32, 512),
            hybridGeckoBlinkBeta = p[Keys.HYBRID_GECKO_BLINK_BETA] ?: true,
            hybridAutoForGames = p[Keys.HYBRID_AUTO_FOR_GAMES] ?: true,
            hybridNativeCpuBoost = p[Keys.HYBRID_NATIVE_CPU_BOOST] ?: true,
            hybridZeroTimerSlack = p[Keys.HYBRID_ZERO_TIMER_SLACK] ?: true,
            hybridReportedCores = (p[Keys.HYBRID_REPORTED_CORES] ?: 8).coerceIn(2, 16),
            hybridOffscreenPreRaster = p[Keys.HYBRID_OFFSCREEN_PRERASTER] ?: true,
            hybridHighPerfWebGl = p[Keys.HYBRID_HIGH_PERF_WEBGL] ?: true,
            hybridCameraAntiShutter = p[Keys.HYBRID_CAMERA_ANTI_SHUTTER] ?: true,
            hybridZeroDelayClicks = p[Keys.HYBRID_ZERO_DELAY_CLICKS] ?: true,
            hybridCanvasRectCacheMs = (p[Keys.HYBRID_CANVAS_RECT_CACHE_MS] ?: 250).coerceIn(0, 1000),
            hybridSuppressPostRenderTimer = p[Keys.HYBRID_SUPPRESS_POST_TIMER] ?: false,
            hybridFastFontSwap = p[Keys.HYBRID_FAST_FONT_SWAP] ?: true,
            hybridCppFastAssetBypass = p[Keys.HYBRID_CPP_FAST_ASSET] ?: true,
            hybridGeckoAdBlock = p[Keys.HYBRID_GECKO_AD_BLOCK] ?: true,
            hybridHideLocalIp = p[Keys.HYBRID_HIDE_LOCAL_IP] ?: true,
            hybridPersistTabMode = p[Keys.HYBRID_PERSIST_TAB_MODE] ?: true,
            hybridWebGpuBoost = p[Keys.HYBRID_WEBGPU_BOOST] ?: true,
            hybrid3d2dAccel = p[Keys.HYBRID_3D_2D_ACCEL] ?: true,
            hybridTeleportStabilizer = p[Keys.HYBRID_TELEPORT_STABILIZER] ?: true,
            hybridHibernateGecko = p[Keys.HYBRID_HIBERNATE_GECKO] ?: true,
            hybridCustomDomains = p[Keys.HYBRID_CUSTOM_DOMAINS] ?: "bloxd.io, staging.bloxd.io, testufo.com, browserbench.org, poki.com, crazygames.com, krunker.io, 1v1.lol, voxiom.io, kirka.io, kour.io, miniblox.io",
            recResolution = enum(p[Keys.REC_RESOLUTION], RecResolution.RES_1640_720),
            recBitrate = enum(p[Keys.REC_BITRATE], RecBitrate.MBPS_50),
            recOrientation = enum(p[Keys.REC_ORIENTATION], RecOrientation.LANDSCAPE),
            recAudioSource = enum(p[Keys.REC_AUDIO_SOURCE], RecAudioSource.SYSTEM),
            recFrameRate = enum(p[Keys.REC_FRAME_RATE], RecFrameRate.FPS_60),
            recStopOnScreenLock = p[Keys.REC_STOP_LOCK] ?: true,
            recShowTouches = p[Keys.REC_SHOW_TOUCHES] ?: false,
            recShowButtonPresses = p[Keys.REC_SHOW_BUTTONS] ?: false,
            recZeroLagBlinkMode = p[Keys.REC_ZERO_LAG_BLINK] ?: true,
            batterySaverMaster = p[Keys.BAT_SAVER_MASTER] ?: false,
            batteryAutoLowPct = (p[Keys.BAT_AUTO_LOW_PCT] ?: 20).coerceIn(0, 80),
            batteryThermalGuard = p[Keys.BAT_THERMAL_GUARD] ?: true,
            batteryAdaptiveRefresh = p[Keys.BAT_ADAPTIVE_REFRESH] ?: true,
            batteryGameMaxFps = (p[Keys.BAT_GAME_MAX_FPS] ?: 120).coerceIn(15, 120),
            batteryGameRenderScale = (p[Keys.BAT_GAME_RENDER_SCALE] ?: 100).coerceIn(50, 100),
            batteryEcoCpu = p[Keys.BAT_ECO_CPU] ?: false,
            batteryIdleGameFps = (p[Keys.BAT_IDLE_GAME_FPS] ?: 30).coerceIn(0, 60),
            batteryIdleTimeoutSec = (p[Keys.BAT_IDLE_TIMEOUT_SEC] ?: 8).coerceIn(3, 60),
            batteryFreezeBgTabs = p[Keys.BAT_FREEZE_BG_TABS] ?: true,
            batteryReduceMotion = p[Keys.BAT_REDUCE_MOTION] ?: false,
            batteryOledDimPct = (p[Keys.BAT_OLED_DIM_PCT] ?: 0).coerceIn(0, 50)
        )
    }.distinctUntilChanged()

    private inline fun <reified T : Enum<T>> enum(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    suspend fun setSearchEngine(v: SearchEngine) = set(Keys.SEARCH_ENGINE, v.name)
    suspend fun setHomepage(v: String) = set(Keys.HOMEPAGE, v)
    suspend fun setRestoreTabs(v: Boolean) = set(Keys.RESTORE_TABS, v)
    suspend fun setToolbarAtTop(v: Boolean) = set(Keys.TOOLBAR_TOP, v)
    suspend fun setHideToolbarOnScroll(v: Boolean) = set(Keys.HIDE_TOOLBAR_ON_SCROLL, v)
    suspend fun setAdBlockEnabled(v: Boolean) = set(Keys.AD_BLOCK, v)
    suspend fun setBlockThirdPartyCookies(v: Boolean) = set(Keys.THIRD_PARTY_COOKIES, v)
    suspend fun setHttpsOnly(v: Boolean) = set(Keys.HTTPS_ONLY, v)
    suspend fun setSendGpc(v: Boolean) = set(Keys.SEND_GPC, v)
    suspend fun setHideLocalIp(v: Boolean) = set(Keys.HIDE_LOCAL_IP, v)
    suspend fun setFingerprintProtectionAll(v: Boolean) = set(Keys.FPP_ALL, v)
    suspend fun setHideGpuModel(v: Boolean) = set(Keys.HIDE_GPU, v)
    suspend fun setGameMode(v: Boolean) = set(Keys.GAME_MODE, v)
    suspend fun setHighRefreshRate(v: Boolean) = set(Keys.HIGH_REFRESH, v)
    suspend fun setVibrationApi(v: Boolean) = set(Keys.VIBRATION_API, v)
    suspend fun setFeatureBatteryApi(v: Boolean) = set(Keys.FEAT_BATTERY, v)
    suspend fun setFeatureNetworkInfo(v: Boolean) = set(Keys.FEAT_NETINFO, v)
    suspend fun setFeatureWebGpu(v: Boolean) = set(Keys.FEAT_WEBGPU, v)
    suspend fun setFeatureSpeechRecognition(v: Boolean) = set(Keys.FEAT_SPEECH, v)
    suspend fun setFeatureWebGlDraft(v: Boolean) = set(Keys.FEAT_WEBGL_DRAFT, v)
    suspend fun setFeatureWasmExperimental(v: Boolean) = set(Keys.FEAT_WASM_EXP, v)
    suspend fun setFpCanvas(v: Boolean) = set(Keys.FP_CANVAS, v)
    suspend fun setFpCanvasThirdParty(v: Boolean) = set(Keys.FP_CANVAS_3P, v)
    suspend fun setFpFonts(v: Boolean) = set(Keys.FP_FONTS, v)
    suspend fun setFpUserAgent(v: Boolean) = set(Keys.FP_UA, v)
    suspend fun setFpHardware(v: Boolean) = set(Keys.FP_HW, v)
    suspend fun setFpScreen(v: Boolean) = set(Keys.FP_SCREEN, v)
    suspend fun setFpAudio(v: Boolean) = set(Keys.FP_AUDIO, v)
    suspend fun setFpMedia(v: Boolean) = set(Keys.FP_MEDIA, v)
    suspend fun setFpWebGlLimits(v: Boolean) = set(Keys.FP_WEBGL_LIMITS, v)
    suspend fun setFpSensors(v: Boolean) = set(Keys.FP_SENSORS, v)
    suspend fun setFpTimezone(v: Boolean) = set(Keys.FP_TIMEZONE, v)
    suspend fun setFpLanguage(v: Boolean) = set(Keys.FP_LANGUAGE, v)
    suspend fun setFpTimers(v: Boolean) = set(Keys.FP_TIMERS, v)
    suspend fun setWebRtcProxyOnly(v: Boolean) = set(Keys.WEBRTC_PROXY_ONLY, v)
    suspend fun setWebRtcDisabled(v: Boolean) = set(Keys.WEBRTC_DISABLED, v)
    suspend fun setBlockPrefetch(v: Boolean) = set(Keys.BLOCK_PREFETCH, v)
    suspend fun setStrictReferrer(v: Boolean) = set(Keys.STRICT_REFERRER, v)
    suspend fun setBounceTracking(v: Boolean) = set(Keys.BOUNCE_TRACKING, v)
    suspend fun setNoJit(v: Boolean) = set(Keys.NO_JIT, v)
    suspend fun setDisableWasm(v: Boolean) = set(Keys.DISABLE_WASM, v)
    suspend fun setSpeedMemory(v: Boolean) = set(Keys.SPEED_MEMORY, v)
    suspend fun setIncognitoJavascript(v: Boolean) = set(Keys.INCOGNITO_JS, v)
    suspend fun setIncognitoHttpsOnly(v: Boolean) = set(Keys.INCOGNITO_HTTPS, v)
    suspend fun setIncognitoBlockThirdPartyCookies(v: Boolean) = set(Keys.INCOGNITO_3P_COOKIES, v)
    suspend fun setFpTrustedSites(v: String) = set(Keys.FP_TRUSTED, v)
    suspend fun setStripTrackingParams(v: Boolean) = set(Keys.STRIP_TRACKING, v)
    suspend fun setDohProvider(v: DohProvider) = set(Keys.DOH_PROVIDER, v.name)
    suspend fun setDohCustomUrl(v: String) = set(Keys.DOH_CUSTOM_URL, v)
    suspend fun setClearHistoryOnExit(v: Boolean) = set(Keys.CLEAR_HISTORY_EXIT, v)
    suspend fun setClearCookiesOnExit(v: Boolean) = set(Keys.CLEAR_COOKIES_EXIT, v)
    suspend fun setClearCacheOnExit(v: Boolean) = set(Keys.CLEAR_CACHE_EXIT, v)
    suspend fun setIsolateIncognitoTabs(v: Boolean) = set(Keys.ISOLATE_INCOGNITO, v)
    suspend fun setFingerprintShield(v: Boolean) = set(Keys.FP_SHIELD, v)
    suspend fun setForceUtcTimezone(v: Boolean) = set(Keys.FORCE_UTC, v)
    suspend fun setIncognitoLock(v: Boolean) = set(Keys.INCOGNITO_LOCK, v)
    suspend fun setIncognitoAutoWipe(v: IncognitoAutoWipe) = set(Keys.INCOGNITO_AUTO_WIPE, v.name)
    suspend fun setIncognitoProxyEnabled(v: Boolean) = set(Keys.PROXY_ENABLED, v)
    suspend fun setIncognitoProxyHost(v: String) = set(Keys.PROXY_HOST, v)
    suspend fun setIncognitoProxyPort(v: Int) = set(Keys.PROXY_PORT, v)
    suspend fun setIncognitoProxyType(v: String) = set(Keys.PROXY_TYPE, v)
    suspend fun setIncognitoDisclosureShown(v: Boolean) = set(Keys.DISCLOSURE_SHOWN, v)
    suspend fun setThemeMode(v: ThemeMode) = set(Keys.THEME_MODE, v.name)
    suspend fun setForceDarkWeb(v: Boolean) = set(Keys.FORCE_DARK_WEB, v)
    suspend fun setTextZoomPercent(v: Int) = set(Keys.TEXT_ZOOM, v.coerceIn(50, 200))
    suspend fun setNormalTabShield(v: Boolean) = set(Keys.NORMAL_TAB_SHIELD, v)
    suspend fun setStrictTrackingProtection(v: Boolean) = set(Keys.STRICT_ETP, v)
    suspend fun setTabUnloadMode(v: TabUnloadMode) = set(Keys.TAB_UNLOAD_MODE, v.name)
    suspend fun setKeepLoadedTabs(v: Int) = set(Keys.KEEP_LOADED_TABS, v.coerceIn(1, 8))
    suspend fun setMemoryMode(v: MemoryMode) = set(Keys.MEMORY_MODE, v.name)
    suspend fun setIdleTabMinutes(v: Int) = set(Keys.IDLE_TAB_MINUTES, v.coerceIn(0, 120))
    suspend fun setPreconnectSearch(v: Boolean) = set(Keys.PRECONNECT_SEARCH, v)
    suspend fun setPreconnectTyped(v: Boolean) = set(Keys.PRECONNECT_TYPED, v)
    suspend fun setGpuProcess(v: Boolean) = set(Keys.GPU_PROCESS, v)
    suspend fun setFreezeDetector(v: Boolean) = set(Keys.FREEZE_DETECTOR, v)
    suspend fun setKeepIncognitoTabs(v: Boolean) = set(Keys.KEEP_INCOGNITO_TABS, v)
    suspend fun setSuperDeviceRange(v: SuperDeviceRange) = set(Keys.SUPER_DEVICE_RANGE, v.name)
    suspend fun setSuperRandomTimezone(v: Boolean) = set(Keys.SUPER_RANDOM_TZ, v)
    suspend fun setSuperGameMode(v: Boolean) = set(Keys.SUPER_GAME_MODE, v)
    suspend fun setSuperGpu(v: String) = set(Keys.SUPER_GPU, v)
    suspend fun setAutoClearCache(v: Boolean) = set(Keys.AUTO_CLEAR_CACHE, v)
    suspend fun setSuperHidden(item: String, hidden: Boolean) {
        context.dataStore.edit {
            val now = it[Keys.SUPER_REVEAL] ?: emptySet()
            it[Keys.SUPER_REVEAL] = if (hidden) now - item else now + item
        }
    }
    suspend fun setKeepSuperTabs(v: Boolean) = set(Keys.KEEP_SUPER_TABS, v)
    suspend fun setSuperProxyEnabled(v: Boolean) = set(Keys.SUPER_PROXY_ENABLED, v)
    suspend fun setSuperProxyType(v: SuperProxyType) = set(Keys.SUPER_PROXY_TYPE, v.name)
    suspend fun setSuperProxyHost(v: String) = set(Keys.SUPER_PROXY_HOST, v)
    suspend fun setSuperProxyPort(v: Int) = set(Keys.SUPER_PROXY_PORT, v)
    suspend fun setSuperDevicePick(v: String) = set(Keys.SUPER_DEVICE_PICK, v)
    suspend fun setFastConnections(v: Boolean) = set(Keys.FAST_CONNECTIONS, v)
    suspend fun setDataSaver(v: DataSaverMode) = set(Keys.DATA_SAVER, v.name)
    suspend fun setIncognitoAdBlock(v: Boolean) = set(Keys.INCOGNITO_AD_BLOCK, v)
    suspend fun setIncognitoTrackingProtection(v: Boolean) = set(Keys.INCOGNITO_TP, v)
    suspend fun setIncognitoStripParams(v: Boolean) = set(Keys.INCOGNITO_STRIP, v)
    suspend fun setIncognitoRandomUserAgent(v: Boolean) = set(Keys.INCOGNITO_RANDOM_UA, v)
    suspend fun setIncognitoTrimReferrer(v: Boolean) = set(Keys.INCOGNITO_TRIM_REFERRER, v)
    suspend fun setIncognitoStripClientHints(v: Boolean) = set(Keys.INCOGNITO_STRIP_HINTS, v)
    suspend fun setIncognitoSendDnt(v: Boolean) = set(Keys.INCOGNITO_SEND_DNT, v)
    suspend fun setPasswordSave(v: Boolean) = set(Keys.PASSWORD_SAVE, v)
    suspend fun setPasswordAutofill(v: Boolean) = set(Keys.PASSWORD_AUTOFILL, v)
    suspend fun setPasswordIncognito(v: Boolean) = set(Keys.PASSWORD_INCOGNITO, v)
    suspend fun setPasswordSuggestStrong(v: Boolean) = set(Keys.PASSWORD_SUGGEST, v)
    suspend fun setOptimizerEnabled(v: Boolean) = set(Keys.OPTIMIZER, v)
    suspend fun setAdBlockCosmetic(v: Boolean) = set(Keys.AD_BLOCK_COSMETIC, v)
    suspend fun setAdBlockAutoUpdate(v: Boolean) = set(Keys.AD_BLOCK_AUTO_UPDATE, v)
    suspend fun setAdBlockAllowed(site: String, allowed: Boolean) {
        val host = site.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
        if (host.isEmpty()) return
        context.dataStore.edit { p ->
            val current = p[Keys.AD_BLOCK_ALLOW] ?: emptySet()
            p[Keys.AD_BLOCK_ALLOW] = if (allowed) current + host else current - host
        }
    }
    suspend fun setPasswordNever(site: String, never: Boolean) {
        context.dataStore.edit {
            val now = it[Keys.PASSWORD_NEVER] ?: emptySet()
            it[Keys.PASSWORD_NEVER] = if (never) now + site else now - site
        }
    }

    suspend fun exportJson(): org.json.JSONObject {
        val prefs = context.dataStore.data.first().asMap()
        val out = org.json.JSONObject()
        prefs.forEach { (key, value) ->
            val entry = org.json.JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is Double -> entry.put("t", "d").put("v", value)
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "ss").put("v", org.json.JSONArray(value.map { it.toString() }))
                else -> return@forEach
            }
            out.put(key.name, entry)
        }
        return out
    }

    suspend fun importJson(data: org.json.JSONObject) {
        context.dataStore.edit { prefs ->
            prefs.clear()
            data.keys().forEach { name ->
                val entry = data.optJSONObject(name) ?: return@forEach
                runCatching {
                    when (entry.optString("t")) {
                        "b" -> prefs[booleanPreferencesKey(name)] = entry.getBoolean("v")
                        "i" -> prefs[androidx.datastore.preferences.core.intPreferencesKey(name)] = entry.getInt("v")
                        "l" -> prefs[androidx.datastore.preferences.core.longPreferencesKey(name)] = entry.getLong("v")
                        "f" -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(name)] = entry.getDouble("v").toFloat()
                        "d" -> prefs[androidx.datastore.preferences.core.doublePreferencesKey(name)] = entry.getDouble("v")
                        "s" -> prefs[androidx.datastore.preferences.core.stringPreferencesKey(name)] = entry.getString("v")
                        "ss" -> {
                            val arr = entry.getJSONArray("v")
                            prefs[stringSetPreferencesKey(name)] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                        }
                    }
                }
            }
        }
    }
    suspend fun setIncognitoGpc(v: Boolean) = set(Keys.INCOGNITO_GPC, v)
    suspend fun setIncognitoFingerprintProtection(v: Boolean) = set(Keys.INCOGNITO_FPP, v)
    suspend fun setGameTouchFix(v: Boolean) = set(Keys.GAME_TOUCH_FIX, v)
    suspend fun setFpTouch(v: Boolean) = set(Keys.FP_TOUCH, v)
    suspend fun setArchiveReadOnly(v: Boolean) = set(Keys.ARCHIVE_READ_ONLY, v)
    suspend fun setArchiveNetworkStream(v: Boolean) = set(Keys.ARCHIVE_NET_STREAM, v)
    suspend fun setArchiveIncludeMemory(v: Boolean) = set(Keys.ARCHIVE_INC_MEMORY, v)
    suspend fun setArchiveIncludeCanvas(v: Boolean) = set(Keys.ARCHIVE_INC_CANVAS, v)
    suspend fun setArchiveIncludeStorage(v: Boolean) = set(Keys.ARCHIVE_INC_STORAGE, v)
    suspend fun setArchiveGuessAssets(v: Boolean) = set(Keys.ARCHIVE_GUESS_ASSETS, v)
    suspend fun setArchiveDeepHooks(v: Boolean) = set(Keys.ARCHIVE_DEEP_HOOKS, v)
    suspend fun setArchiveMaxFiles(v: Int) = set(Keys.ARCHIVE_MAX_FILES, v.coerceIn(500, 10000))
    suspend fun setArchiveMaxTotalMb(v: Int) = set(Keys.ARCHIVE_MAX_TOTAL_MB, v.coerceIn(100, 2000))
    suspend fun setArchiveMaxFileMb(v: Int) = set(Keys.ARCHIVE_MAX_FILE_MB, v.coerceIn(20, 500))
    suspend fun setArchiveStreamBufferMb(v: Int) = set(Keys.ARCHIVE_STREAM_BUF_MB, v.coerceIn(32, 512))
    suspend fun setHybridGeckoBlinkBeta(v: Boolean) = set(Keys.HYBRID_GECKO_BLINK_BETA, v)
    suspend fun setHybridAutoForGames(v: Boolean) = set(Keys.HYBRID_AUTO_FOR_GAMES, v)
    suspend fun setHybridNativeCpuBoost(v: Boolean) = set(Keys.HYBRID_NATIVE_CPU_BOOST, v)
    suspend fun setHybridZeroTimerSlack(v: Boolean) = set(Keys.HYBRID_ZERO_TIMER_SLACK, v)
    suspend fun setHybridReportedCores(v: Int) = set(Keys.HYBRID_REPORTED_CORES, v.coerceIn(2, 16))
    suspend fun setHybridOffscreenPreRaster(v: Boolean) = set(Keys.HYBRID_OFFSCREEN_PRERASTER, v)
    suspend fun setHybridHighPerfWebGl(v: Boolean) = set(Keys.HYBRID_HIGH_PERF_WEBGL, v)
    suspend fun setHybridCameraAntiShutter(v: Boolean) = set(Keys.HYBRID_CAMERA_ANTI_SHUTTER, v)
    suspend fun setHybridZeroDelayClicks(v: Boolean) = set(Keys.HYBRID_ZERO_DELAY_CLICKS, v)
    suspend fun setHybridCanvasRectCacheMs(v: Int) = set(Keys.HYBRID_CANVAS_RECT_CACHE_MS, v.coerceIn(0, 1000))
    suspend fun setHybridSuppressPostRenderTimer(v: Boolean) = set(Keys.HYBRID_SUPPRESS_POST_TIMER, v)
    suspend fun setHybridFastFontSwap(v: Boolean) = set(Keys.HYBRID_FAST_FONT_SWAP, v)
    suspend fun setHybridCppFastAssetBypass(v: Boolean) = set(Keys.HYBRID_CPP_FAST_ASSET, v)
    suspend fun setHybridGeckoAdBlock(v: Boolean) = set(Keys.HYBRID_GECKO_AD_BLOCK, v)
    suspend fun setHybridHideLocalIp(v: Boolean) = set(Keys.HYBRID_HIDE_LOCAL_IP, v)
    suspend fun setHybridPersistTabMode(v: Boolean) = set(Keys.HYBRID_PERSIST_TAB_MODE, v)
    suspend fun setHybridWebGpuBoost(v: Boolean) = set(Keys.HYBRID_WEBGPU_BOOST, v)
    suspend fun setHybrid3d2dAccel(v: Boolean) = set(Keys.HYBRID_3D_2D_ACCEL, v)
    suspend fun setHybridTeleportStabilizer(v: Boolean) = set(Keys.HYBRID_TELEPORT_STABILIZER, v)
    suspend fun setHybridHibernateGecko(v: Boolean) = set(Keys.HYBRID_HIBERNATE_GECKO, v)
    suspend fun setHybridCustomDomains(v: String) = set(Keys.HYBRID_CUSTOM_DOMAINS, v)
    suspend fun setRecResolution(v: RecResolution) = set(Keys.REC_RESOLUTION, v.name)
    suspend fun setRecBitrate(v: RecBitrate) = set(Keys.REC_BITRATE, v.name)
    suspend fun setRecOrientation(v: RecOrientation) = set(Keys.REC_ORIENTATION, v.name)
    suspend fun setRecAudioSource(v: RecAudioSource) = set(Keys.REC_AUDIO_SOURCE, v.name)
    suspend fun setRecFrameRate(v: RecFrameRate) = set(Keys.REC_FRAME_RATE, v.name)
    suspend fun setRecStopOnScreenLock(v: Boolean) = set(Keys.REC_STOP_LOCK, v)
    suspend fun setRecShowTouches(v: Boolean) = set(Keys.REC_SHOW_TOUCHES, v)
    suspend fun setRecShowButtonPresses(v: Boolean) = set(Keys.REC_SHOW_BUTTONS, v)
    suspend fun setRecZeroLagBlinkMode(v: Boolean) = set(Keys.REC_ZERO_LAG_BLINK, v)
    suspend fun setBatterySaverMaster(v: Boolean) = set(Keys.BAT_SAVER_MASTER, v)
    suspend fun setBatteryAutoLowPct(v: Int) = set(Keys.BAT_AUTO_LOW_PCT, v.coerceIn(0, 80))
    suspend fun setBatteryThermalGuard(v: Boolean) = set(Keys.BAT_THERMAL_GUARD, v)
    suspend fun setBatteryAdaptiveRefresh(v: Boolean) = set(Keys.BAT_ADAPTIVE_REFRESH, v)
    suspend fun setBatteryGameMaxFps(v: Int) = set(Keys.BAT_GAME_MAX_FPS, v.coerceIn(15, 120))
    suspend fun setBatteryGameRenderScale(v: Int) = set(Keys.BAT_GAME_RENDER_SCALE, v.coerceIn(50, 100))
    suspend fun setBatteryEcoCpu(v: Boolean) = set(Keys.BAT_ECO_CPU, v)
    suspend fun setBatteryIdleGameFps(v: Int) = set(Keys.BAT_IDLE_GAME_FPS, v.coerceIn(0, 60))
    suspend fun setBatteryIdleTimeoutSec(v: Int) = set(Keys.BAT_IDLE_TIMEOUT_SEC, v.coerceIn(3, 60))
    suspend fun setBatteryFreezeBgTabs(v: Boolean) = set(Keys.BAT_FREEZE_BG_TABS, v)
    suspend fun setBatteryReduceMotion(v: Boolean) = set(Keys.BAT_REDUCE_MOTION, v)
    suspend fun setBatteryOledDimPct(v: Int) = set(Keys.BAT_OLED_DIM_PCT, v.coerceIn(0, 50))
    suspend fun applyEcoGamingPreset() {
        context.dataStore.edit {
            it[Keys.BAT_SAVER_MASTER] = true
            it[Keys.BAT_THERMAL_GUARD] = true
            it[Keys.BAT_ADAPTIVE_REFRESH] = true
            it[Keys.BAT_GAME_MAX_FPS] = 90
            it[Keys.BAT_GAME_RENDER_SCALE] = 85
            it[Keys.BAT_ECO_CPU] = true
            it[Keys.BAT_IDLE_GAME_FPS] = 30
            it[Keys.BAT_IDLE_TIMEOUT_SEC] = 6
            it[Keys.BAT_FREEZE_BG_TABS] = true
        }
    }
    suspend fun applyMaxCompetitivePreset() {
        context.dataStore.edit {
            it[Keys.BAT_SAVER_MASTER] = false
            it[Keys.BAT_THERMAL_GUARD] = true
            it[Keys.BAT_ADAPTIVE_REFRESH] = true
            it[Keys.BAT_GAME_MAX_FPS] = 120
            it[Keys.BAT_GAME_RENDER_SCALE] = 100
            it[Keys.BAT_ECO_CPU] = false
            it[Keys.BAT_IDLE_GAME_FPS] = 30
            it[Keys.BAT_IDLE_TIMEOUT_SEC] = 10
            it[Keys.BAT_OLED_DIM_PCT] = 0
        }
    }

    suspend fun copyIncognitoFromNormal(s: Settings) {
        context.dataStore.edit {
            it[Keys.INCOGNITO_AD_BLOCK] = s.adBlockEnabled
            it[Keys.INCOGNITO_HTTPS] = s.httpsOnly
            it[Keys.INCOGNITO_STRIP] = s.stripTrackingParams
            it[Keys.INCOGNITO_GPC] = s.sendGpc
            it[Keys.INCOGNITO_FPP] = s.fingerprintProtectionAll
            it[Keys.INCOGNITO_TP] = true
            it[Keys.INCOGNITO_JS] = true
            it[Keys.FP_SHIELD] = s.normalTabShield
        }
    }

    suspend fun applyPerformanceProfile(profile: PerformanceProfile) {
        val values = when (profile) {
            PerformanceProfile.MAX_PRIVACY -> Triple(true, true, true)
            PerformanceProfile.BALANCED -> Triple(false, false, true)
            PerformanceProfile.MAX_SPEED -> Triple(false, false, false)
            PerformanceProfile.CUSTOM -> return
        }
        context.dataStore.edit {
            it[Keys.NORMAL_TAB_SHIELD] = values.first
            it[Keys.STRICT_ETP] = values.second
            it[Keys.AD_BLOCK] = values.third
        }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
