package com.fable.browser.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.model.DataSaverMode
import com.fable.browser.data.model.DohProvider
import com.fable.browser.data.model.SuperDeviceRange
import com.fable.browser.data.model.SuperProxyType
import com.fable.browser.data.model.IncognitoAutoWipe
import com.fable.browser.data.model.PermissionDecision
import com.fable.browser.data.model.PermissionKind
import com.fable.browser.data.model.SearchEngine
import com.fable.browser.data.model.SitePermission
import com.fable.browser.data.model.ThemeMode
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.FaviconStore
import com.fable.browser.data.repo.HistoryRepository
import com.fable.browser.data.repo.SitePermissionRepository
import com.fable.browser.engine.ContentBlocker
import com.fable.browser.engine.EngineManager
import com.fable.browser.engine.SuperIdentity
import com.fable.browser.engine.SuperIdentityFactory
import com.fable.browser.engine.SuperIncognito
import com.fable.browser.engine.TabManager
import kotlinx.coroutines.Dispatchers
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val engineManager: EngineManager,
    private val contentBlocker: ContentBlocker,
    private val sitePermissions: SitePermissionRepository,
    private val historyRepository: HistoryRepository,
    private val biometricGate: BiometricGate,
    private val superIncognito: SuperIncognito,
    private val tabManager: TabManager,
    private val faviconStore: FaviconStore
) : ViewModel() {
    val superIdentity: StateFlow<SuperIdentity?> = superIncognito.identity

    fun superProxyUser(): String = superIncognito.proxyUser

    fun superProxyHasPassword(): Boolean = superIncognito.proxyPass.isNotEmpty()

    fun currentSuperProxyPassword(): String = superIncognito.proxyPass

    fun setSuperProxyLogin(user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { superIncognito.setProxyAuth(user.trim(), pass) }
            _message.value = if (user.isBlank() && pass.isEmpty()) "Proxy login removed" else "Proxy login saved (encrypted)"
        }
    }

    fun newSuperIdentity() {
        tabManager.newSuperIdentity()
        _message.value = "Super incognito tabs closed. The next one gets a brand new random identity."
    }

    fun previewSuperIdentity(): SuperIdentity {
        val s = settings.value
        return SuperIdentityFactory.create(s.superDeviceRange, s.superDevicePick, s.superRandomTimezone)
    }

    fun setSuperProxyPortText(raw: String) {
        val port = raw.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            _message.value = "Port must be a number from 1 to 65535"
            return
        }
        setSuperProxyPort(port)
    }

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

    fun cosmeticRuleCount(): Int = contentBlocker.cosmeticCount()

    fun filterUpdatedText(): String {
        val last = contentBlocker.lastUpdated()
        if (last <= 0) return "Using the lists built into this version"
        val days = ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
        return when (days) {
            0 -> "Updated today"
            1 -> "Updated yesterday"
            else -> "Updated $days days ago"
        }
    }

    fun setAdBlockAllowed(site: String, allowed: Boolean) = edit { store.setAdBlockAllowed(site, allowed) }

    fun setSearchEngine(v: SearchEngine) = edit { store.setSearchEngine(v) }
    fun setHomepage(v: String) = edit { store.setHomepage(v.trim()) }
    fun setRestoreTabs(v: Boolean) = edit { store.setRestoreTabs(v) }
    fun setToolbarAtTop(v: Boolean) = edit { store.setToolbarAtTop(v) }
    fun setHideToolbarOnScroll(v: Boolean) = edit { store.setHideToolbarOnScroll(v) }

    fun setAdBlockEnabled(v: Boolean) = edit { store.setAdBlockEnabled(v) }
    fun setBlockThirdPartyCookies(v: Boolean) = edit { store.setBlockThirdPartyCookies(v) }
    fun setHttpsOnly(v: Boolean) = edit { store.setHttpsOnly(v) }
    fun setSendGpc(v: Boolean) = edit { store.setSendGpc(v) }
    fun setHideLocalIp(v: Boolean) = edit { store.setHideLocalIp(v) }
    fun setFingerprintProtectionAll(v: Boolean) = edit { store.setFingerprintProtectionAll(v) }
    fun setHideGpuModel(v: Boolean) = edit { store.setHideGpuModel(v) }
    fun setGameMode(v: Boolean) = edit { store.setGameMode(v) }
    fun setHighRefreshRate(v: Boolean) = edit { store.setHighRefreshRate(v) }
    fun setVibrationApi(v: Boolean) = edit { store.setVibrationApi(v) }
    fun setFeatureBatteryApi(v: Boolean) = edit { store.setFeatureBatteryApi(v) }
    fun setFeatureNetworkInfo(v: Boolean) = edit { store.setFeatureNetworkInfo(v) }
    fun setFeatureWebGpu(v: Boolean) = edit { store.setFeatureWebGpu(v) }
    fun setFeatureSpeechRecognition(v: Boolean) = edit { store.setFeatureSpeechRecognition(v) }
    fun setFeatureWebGlDraft(v: Boolean) = edit { store.setFeatureWebGlDraft(v) }
    fun setFeatureWasmExperimental(v: Boolean) = edit { store.setFeatureWasmExperimental(v) }
    fun setFpCanvas(v: Boolean) = edit { store.setFpCanvas(v) }
    fun setFpCanvasThirdParty(v: Boolean) = edit { store.setFpCanvasThirdParty(v) }
    fun setFpFonts(v: Boolean) = edit { store.setFpFonts(v) }
    fun setFpUserAgent(v: Boolean) = edit { store.setFpUserAgent(v) }
    fun setFpHardware(v: Boolean) = edit { store.setFpHardware(v) }
    fun setFpScreen(v: Boolean) = edit { store.setFpScreen(v) }
    fun setFpAudio(v: Boolean) = edit { store.setFpAudio(v) }
    fun setFpMedia(v: Boolean) = edit { store.setFpMedia(v) }
    fun setFpWebGlLimits(v: Boolean) = edit { store.setFpWebGlLimits(v) }
    fun setFpSensors(v: Boolean) = edit { store.setFpSensors(v) }
    fun setFpTimezone(v: Boolean) = edit { store.setFpTimezone(v) }
    fun setFpLanguage(v: Boolean) = edit { store.setFpLanguage(v) }
    fun setFpTimers(v: Boolean) = edit { store.setFpTimers(v) }
    fun setWebRtcProxyOnly(v: Boolean) = edit { store.setWebRtcProxyOnly(v) }
    fun setWebRtcDisabled(v: Boolean) = edit { store.setWebRtcDisabled(v) }
    fun setBlockPrefetch(v: Boolean) = edit { store.setBlockPrefetch(v) }
    fun setStrictReferrer(v: Boolean) = edit { store.setStrictReferrer(v) }
    fun setBounceTracking(v: Boolean) = edit { store.setBounceTracking(v) }
    fun setNoJit(v: Boolean) = edit { store.setNoJit(v) }
    fun setDisableWasm(v: Boolean) = edit { store.setDisableWasm(v) }
    fun setSpeedMemory(v: Boolean) = edit { store.setSpeedMemory(v) }
    fun setIncognitoJavascript(v: Boolean) = edit { store.setIncognitoJavascript(v) }
    fun setIncognitoHttpsOnly(v: Boolean) = edit { store.setIncognitoHttpsOnly(v) }
    fun setIncognitoBlockThirdPartyCookies(v: Boolean) = edit { store.setIncognitoBlockThirdPartyCookies(v) }

    fun addTrustedSite(raw: String) {
        val host = normalizeHost(raw) ?: return
        val current = settings.value.fpTrustedSites.split(',').filter { it.isNotBlank() }
        if (host in current) return
        edit { store.setFpTrustedSites((current + host).joinToString(",")) }
    }

    fun removeTrustedSite(host: String) {
        val current = settings.value.fpTrustedSites.split(',').filter { it.isNotBlank() && it != host }
        edit { store.setFpTrustedSites(current.joinToString(",")) }
    }

    private fun normalizeHost(raw: String): String? {
        val t = raw.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').removePrefix("www.")
        return t.takeIf { it.isNotEmpty() && '.' in it && t.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
    }

    fun applyPreset(preset: PrivacyPreset) = edit {
        val max = preset == PrivacyPreset.MAXIMUM
        val game = preset == PrivacyPreset.GAMING
        store.setFingerprintProtectionAll(true)
        store.setFpCanvas(!game)
        store.setFpCanvasThirdParty(!game)
        store.setFpFonts(true)
        store.setFpUserAgent(true)
        store.setFpHardware(!game)
        store.setFpScreen(!game)
        store.setFpAudio(!game)
        store.setFpMedia(true)
        store.setHideGpuModel(!game)
        store.setFpWebGlLimits(max)
        store.setFpSensors(max)
        store.setFpTimezone(max)
        store.setFpLanguage(max)
        store.setFpTimers(max)
        store.setHideLocalIp(true)
        store.setWebRtcProxyOnly(false)
        store.setWebRtcDisabled(max)
        store.setBlockPrefetch(true)
        store.setStrictReferrer(true)
        store.setBounceTracking(true)
        store.setStrictTrackingProtection(true)
        store.setNoJit(max)
        store.setDisableWasm(max)
        store.setGameMode(true)
        store.setHighRefreshRate(true)
    }
    fun setStripTrackingParams(v: Boolean) = edit { store.setStripTrackingParams(v) }
    fun setDohProvider(v: DohProvider) = edit { store.setDohProvider(v) }
    fun setDohCustomUrl(v: String) = edit { store.setDohCustomUrl(v.trim()) }
    fun setClearHistoryOnExit(v: Boolean) = edit { store.setClearHistoryOnExit(v) }
    fun setClearCookiesOnExit(v: Boolean) = edit { store.setClearCookiesOnExit(v) }
    fun setClearCacheOnExit(v: Boolean) = edit { store.setClearCacheOnExit(v) }

    fun updateFilterLists() {
        if (_updatingFilters.value) return
        _updatingFilters.value = true
        viewModelScope.launch {
            val result = contentBlocker.updateAll()
            _updatingFilters.value = false
            result.onSuccess { engineManager.refreshFilterRules() }
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
        faviconStore.clear()
        viewModelScope.launch {
            historyRepository.clearAll()
            _message.value = "All browsing data cleared"
        }
    }

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

    fun setThemeMode(v: ThemeMode) = edit { store.setThemeMode(v) }
    fun setForceDarkWeb(v: Boolean) = edit { store.setForceDarkWeb(v) }
    fun setTextZoomPercent(v: Int) = edit { store.setTextZoomPercent(v) }
    fun applyPerformanceProfile(v: PerformanceProfile) = edit { store.applyPerformanceProfile(v) }
    fun setNormalTabShield(v: Boolean) = edit { store.setNormalTabShield(v) }
    fun setStrictTrackingProtection(v: Boolean) = edit { store.setStrictTrackingProtection(v) }
    fun setTabUnloadMode(v: TabUnloadMode) = edit { store.setTabUnloadMode(v) }
    fun setKeepLoadedTabs(v: Int) = edit { store.setKeepLoadedTabs(v) }
    fun setMemoryMode(v: com.fable.browser.data.model.MemoryMode) = edit { store.setMemoryMode(v) }
    fun setIdleTabMinutes(v: Int) = edit { store.setIdleTabMinutes(v) }
    fun setPreconnectSearch(v: Boolean) = edit { store.setPreconnectSearch(v) }
    fun setPreconnectTyped(v: Boolean) = edit { store.setPreconnectTyped(v) }
    fun setGpuProcess(v: Boolean) = edit { store.setGpuProcess(v) }
    fun setFreezeDetector(v: Boolean) = edit { store.setFreezeDetector(v) }
    fun setKeepIncognitoTabs(v: Boolean) = edit { store.setKeepIncognitoTabs(v) }
    fun setSuperDeviceRange(v: SuperDeviceRange) = edit { store.setSuperDeviceRange(v) }
    fun setSuperRandomTimezone(v: Boolean) = edit { store.setSuperRandomTimezone(v) }
    fun setSuperHidden(item: String, hidden: Boolean) = edit { store.setSuperHidden(item, hidden) }
    fun setSuperGameMode(v: Boolean) = edit { store.setSuperGameMode(v) }
    fun setSuperGpu(v: String) = edit { store.setSuperGpu(v) }
    fun setAutoClearCache(v: Boolean) = edit { store.setAutoClearCache(v) }
    fun gpuChoices(): List<Pair<String, String>> = SuperIdentityFactory.gpuChoices()
    fun setKeepSuperTabs(v: Boolean) = edit { store.setKeepSuperTabs(v) }
    fun setSuperProxyEnabled(v: Boolean) = edit { store.setSuperProxyEnabled(v) }
    fun setSuperProxyType(v: SuperProxyType) = edit { store.setSuperProxyType(v) }
    fun setSuperProxyHost(v: String) = edit { store.setSuperProxyHost(v.trim()) }
    fun setSuperProxyPort(v: Int) = edit { store.setSuperProxyPort(v) }
    fun setSuperDevicePick(v: String) = edit { store.setSuperDevicePick(v) }
    fun setFastConnections(v: Boolean) = edit { store.setFastConnections(v) }
    fun setDataSaver(v: DataSaverMode) = edit { store.setDataSaver(v) }
    fun setIncognitoAdBlock(v: Boolean) = edit { store.setIncognitoAdBlock(v) }
    fun setIncognitoTrackingProtection(v: Boolean) = edit { store.setIncognitoTrackingProtection(v) }
    fun setIncognitoStripParams(v: Boolean) = edit { store.setIncognitoStripParams(v) }
    fun setIncognitoRandomUserAgent(v: Boolean) = edit { store.setIncognitoRandomUserAgent(v) }
    fun setIncognitoTrimReferrer(v: Boolean) = edit { store.setIncognitoTrimReferrer(v) }
    fun setIncognitoStripClientHints(v: Boolean) = edit { store.setIncognitoStripClientHints(v) }
    fun setIncognitoSendDnt(v: Boolean) = edit { store.setIncognitoSendDnt(v) }
    fun setPasswordSave(v: Boolean) = edit { store.setPasswordSave(v) }
    fun setPasswordAutofill(v: Boolean) = edit { store.setPasswordAutofill(v) }
    fun setPasswordIncognito(v: Boolean) = edit { store.setPasswordIncognito(v) }
    fun setPasswordNever(site: String, never: Boolean) = edit { store.setPasswordNever(site, never) }
    fun setOptimizerEnabled(v: Boolean) = edit { store.setOptimizerEnabled(v) }
    fun setAdBlockCosmetic(v: Boolean) = edit { store.setAdBlockCosmetic(v) }
    fun setAdBlockAutoUpdate(v: Boolean) = edit { store.setAdBlockAutoUpdate(v) }
    fun setIncognitoGpc(v: Boolean) = edit { store.setIncognitoGpc(v) }
    fun setIncognitoFingerprintProtection(v: Boolean) = edit { store.setIncognitoFingerprintProtection(v) }
    fun setGameTouchFix(v: Boolean) = edit { store.setGameTouchFix(v) }
    fun setFpTouch(v: Boolean) = edit { store.setFpTouch(v) }
    fun setArchiveReadOnly(v: Boolean) = edit { store.setArchiveReadOnly(v) }
    fun setArchiveNetworkStream(v: Boolean) = edit { store.setArchiveNetworkStream(v) }
    fun setArchiveIncludeMemory(v: Boolean) = edit { store.setArchiveIncludeMemory(v) }
    fun setArchiveIncludeCanvas(v: Boolean) = edit { store.setArchiveIncludeCanvas(v) }
    fun setArchiveIncludeStorage(v: Boolean) = edit { store.setArchiveIncludeStorage(v) }
    fun setArchiveGuessAssets(v: Boolean) = edit { store.setArchiveGuessAssets(v) }
    fun setArchiveDeepHooks(v: Boolean) = edit { store.setArchiveDeepHooks(v) }
    fun setArchiveMaxFiles(v: Int) = edit { store.setArchiveMaxFiles(v) }
    fun setArchiveMaxTotalMb(v: Int) = edit { store.setArchiveMaxTotalMb(v) }
    fun setArchiveMaxFileMb(v: Int) = edit { store.setArchiveMaxFileMb(v) }
    fun setArchiveStreamBufferMb(v: Int) = edit { store.setArchiveStreamBufferMb(v) }
    fun setHybridGeckoBlinkBeta(v: Boolean) = edit { store.setHybridGeckoBlinkBeta(v) }
    fun setHybridAutoForGames(v: Boolean) = edit { store.setHybridAutoForGames(v) }
    fun copyIncognitoFromNormal() = edit { store.copyIncognitoFromNormal(settings.value) }

    fun autoMemoryInfo(context: android.content.Context): Pair<com.fable.browser.data.model.MemoryMode, Long> =
        com.fable.browser.engine.MemoryPolicy.autoMode(context) to com.fable.browser.engine.MemoryPolicy.totalRamMb(context)

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
