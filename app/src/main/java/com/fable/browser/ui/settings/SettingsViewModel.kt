package com.fable.browser.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.model.DohProvider
import com.fable.browser.data.model.IncognitoAutoWipe
import com.fable.browser.data.model.PermissionDecision
import com.fable.browser.data.model.PermissionKind
import com.fable.browser.data.model.SearchEngine
import com.fable.browser.data.model.SitePermission
import com.fable.browser.data.model.ThemeMode
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.HistoryRepository
import com.fable.browser.data.repo.SitePermissionRepository
import com.fable.browser.engine.ContentBlocker
import com.fable.browser.engine.EngineManager
import com.fable.browser.util.BiometricGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.fable.browser.data.model.PerformanceProfile
import com.fable.browser.data.model.TabUnloadMode
import javax.inject.Inject

/**
 * Backs every settings sub-screen. Writes go straight to DataStore;
 * [EngineManager] observes the same flow and re-applies engine prefs, so there
 * is no separate "apply" step and no chance of UI and engine drifting apart.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val engineManager: EngineManager,
    private val contentBlocker: ContentBlocker,
    private val sitePermissions: SitePermissionRepository,
    private val historyRepository: HistoryRepository,
    private val biometricGate: BiometricGate
) : ViewModel() {

    val settings: StateFlow<Settings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val permissions: StateFlow<List<SitePermission>> = sitePermissions.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _updatingFilters = MutableStateFlow(false)
    val updatingFilters: StateFlow<Boolean> = _updatingFilters.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun biometricsAvailable(): Boolean = biometricGate.isAvailable()

    fun filterRuleCount(): Int = contentBlocker.ruleCount()

    // ------------------------------------------------------------- general

    fun setSearchEngine(v: SearchEngine) = edit { store.setSearchEngine(v) }
    fun setHomepage(v: String) = edit { store.setHomepage(v.trim()) }
    fun setRestoreTabs(v: Boolean) = edit { store.setRestoreTabs(v) }

    // ---------------------------------------------------- privacy & security

    fun setAdBlockEnabled(v: Boolean) = edit { store.setAdBlockEnabled(v) }
    fun setBlockThirdPartyCookies(v: Boolean) = edit { store.setBlockThirdPartyCookies(v) }
    fun setHttpsOnly(v: Boolean) = edit { store.setHttpsOnly(v) }
    fun setSendGpc(v: Boolean) = edit { store.setSendGpc(v) }
    fun setStripTrackingParams(v: Boolean) = edit { store.setStripTrackingParams(v) }
    fun setDohProvider(v: DohProvider) = edit { store.setDohProvider(v) }
    fun setDohCustomUrl(v: String) = edit { store.setDohCustomUrl(v.trim()) }
    fun setClearHistoryOnExit(v: Boolean) = edit { store.setClearHistoryOnExit(v) }
    fun setClearCookiesOnExit(v: Boolean) = edit { store.setClearCookiesOnExit(v) }
    fun setClearCacheOnExit(v: Boolean) = edit { store.setClearCacheOnExit(v) }

    /** Explicit, user-initiated filter-list refresh — the only outbound call in Settings. */
    fun updateFilterLists() {
        if (_updatingFilters.value) return
        _updatingFilters.value = true
        viewModelScope.launch {
            val result = contentBlocker.updateFromUrl(
                ContentBlocker.DEFAULT_EASYLIST_URL,
                ContentBlocker.DEFAULT_EASYPRIVACY_URL
            )
            _updatingFilters.value = false
            _message.value = result.fold(
                onSuccess = { "Filter lists updated — $it rules" },
                onFailure = { "Could not reach the filter list server" }
            )
        }
    }

    fun clearCookiesNow() {
        engineManager.clearCookies()
        _message.value = "Cookies cleared"
    }

    fun clearCacheNow() {
        engineManager.clearCache()
        _message.value = "Cache cleared"
    }

    fun clearEverythingNow() {
        engineManager.clearAllBrowsingData()
        viewModelScope.launch {
            historyRepository.clearAll()
            _message.value = "All browsing data cleared"
        }
    }

    // ----------------------------------------------------------- incognito

    fun setIsolateIncognitoTabs(v: Boolean) = edit { store.setIsolateIncognitoTabs(v) }
    fun setFingerprintShield(v: Boolean) = edit { store.setFingerprintShield(v) }
    fun setForceUtcTimezone(v: Boolean) = edit { store.setForceUtcTimezone(v) }
    fun setIncognitoAutoWipe(v: IncognitoAutoWipe) = edit { store.setIncognitoAutoWipe(v) }
    fun setIncognitoProxyEnabled(v: Boolean) = edit { store.setIncognitoProxyEnabled(v) }
    fun setIncognitoProxyHost(v: String) = edit { store.setIncognitoProxyHost(v.trim()) }
    fun setIncognitoProxyType(v: String) = edit { store.setIncognitoProxyType(v) }

    fun setIncognitoProxyPort(raw: String) {
        val port = raw.filter { it.isDigit() }.toIntOrNull() ?: return
        edit { store.setIncognitoProxyPort(port.coerceIn(1, 65535)) }
    }

    fun setIncognitoLock(v: Boolean) {
        if (v && !biometricGate.isAvailable()) {
            _message.value = "Set a screen lock or fingerprint on this device first"
            return
        }
        edit { store.setIncognitoLock(v) }
    }

    // ---------------------------------------------------------- appearance

    fun setThemeMode(v: ThemeMode) = edit { store.setThemeMode(v) }
    fun setForceDarkWeb(v: Boolean) = edit { store.setForceDarkWeb(v) }
    fun setTextZoomPercent(v: Int) = edit { store.setTextZoomPercent(v) }
    fun applyPerformanceProfile(v: PerformanceProfile) = edit { store.applyPerformanceProfile(v) }
    fun setNormalTabShield(v: Boolean) = edit { store.setNormalTabShield(v) }
    fun setStrictTrackingProtection(v: Boolean) = edit { store.setStrictTrackingProtection(v) }
    fun setTabUnloadMode(v: TabUnloadMode) = edit { store.setTabUnloadMode(v) }
    fun setKeepLoadedTabs(v: Int) = edit { store.setKeepLoadedTabs(v) }

    // -------------------------------------------------------- site settings

    fun setPermission(origin: String, kind: PermissionKind, decision: PermissionDecision) {
        viewModelScope.launch { sitePermissions.set(origin, kind, decision) }
    }

    fun removePermission(permission: SitePermission) {
        viewModelScope.launch { sitePermissions.remove(permission.id) }
    }

    fun clearOrigin(origin: String) {
        viewModelScope.launch { sitePermissions.clearOrigin(origin) }
    }

    private inline fun edit(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
