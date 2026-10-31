package com.fable.browser.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fable.browser.data.model.DohProvider
import com.fable.browser.data.model.IncognitoAutoWipe
import com.fable.browser.data.model.PerformanceProfile
import com.fable.browser.data.model.SearchEngine
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.data.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fable_settings")

/** Every user-facing setting, backed by DataStore. Nothing here is ever sent anywhere. */
data class Settings(
    // General
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val homepage: String = "about:blank",
    val restoreTabs: Boolean = true,
    /** Address bar at the top of the screen (true) or at the bottom (false). */
    val toolbarAtTop: Boolean = true,
    /** Slide the toolbar away while scrolling down. Off by default: resizing the page
     *  in the middle of a touch gesture confuses games and canvas apps. */
    val hideToolbarOnScroll: Boolean = false,
    // Privacy & security
    val adBlockEnabled: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val httpsOnly: Boolean = true,
    val sendGpc: Boolean = true,
    val stripTrackingParams: Boolean = true,
    val dohProvider: DohProvider = DohProvider.CLOUDFLARE,
    val dohCustomUrl: String = "",
    val clearHistoryOnExit: Boolean = false,
    val clearCookiesOnExit: Boolean = false,
    val clearCacheOnExit: Boolean = false,
    // Incognito
    val isolateIncognitoTabs: Boolean = true,
    val fingerprintShield: Boolean = true,
    val forceUtcTimezone: Boolean = true,
    val incognitoLock: Boolean = false,
    val incognitoAutoWipe: IncognitoAutoWipe = IncognitoAutoWipe.OFF,
    val incognitoProxyEnabled: Boolean = false,
    val incognitoProxyHost: String = "",
    val incognitoProxyPort: Int = 9050,
    val incognitoProxyType: String = "SOCKS5", // SOCKS5 or HTTP
    val incognitoDisclosureShown: Boolean = false,
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val forceDarkWeb: Boolean = false,
    val textZoomPercent: Int = 100,
    val normalTabShield: Boolean = false,
    val strictTrackingProtection: Boolean = false,
    val tabUnloadMode: TabUnloadMode = TabUnloadMode.SMART,
    val keepLoadedTabs: Int = 3
) {
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
            strictTrackingProtection = p[Keys.STRICT_ETP] ?: false,
            tabUnloadMode = enum(p[Keys.TAB_UNLOAD_MODE], TabUnloadMode.SMART),
            keepLoadedTabs = (p[Keys.KEEP_LOADED_TABS] ?: 3).coerceIn(1, 8)
        )
    }

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
