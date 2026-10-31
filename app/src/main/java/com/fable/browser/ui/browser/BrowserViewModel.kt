package com.fable.browser.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.model.SearchEngine
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.BookmarkRepository
import com.fable.browser.data.repo.HistoryRepository
import com.fable.browser.engine.BrowserTab
import com.fable.browser.engine.ContentMenuAction
import com.fable.browser.engine.Downloader
import com.fable.browser.engine.EngineManager
import com.fable.browser.engine.FingerprintShield
import com.fable.browser.engine.PromptBridge
import com.fable.browser.engine.TabManager
import com.fable.browser.util.ActivityBridge
import com.fable.browser.util.BiometricGate
import com.fable.browser.util.UrlUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.fable.browser.data.model.TopSite
import javax.inject.Inject

/** One omnibox suggestion. [isBookmark] drives the icon. */
data class Suggestion(val title: String, val url: String, val isBookmark: Boolean)

/**
 * The single ViewModel behind the browser chrome. It owns no engine state
 * itself — [TabManager] does — so a configuration change or a screen rotation
 * never rebuilds a session (and never resurrects an incognito one).
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val tabManager: TabManager,
    private val settingsStore: SettingsStore,
    private val bookmarkRepository: BookmarkRepository,
    private val historyRepository: HistoryRepository,
    private val promptBridge: PromptBridge,
    private val downloader: Downloader,
    private val engineManager: EngineManager,
    private val activityBridge: ActivityBridge,
    private val biometricGate: BiometricGate
) : ViewModel() {

    val tabs: StateFlow<List<BrowserTab>> = tabManager.tabs
    val activeTabId: StateFlow<String?> = tabManager.activeTabId
    val isIncognito: StateFlow<Boolean> = tabManager.isIncognitoMode
    val topSites: StateFlow<List<TopSite>> = historyRepository.topSites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val toolbarVisible: StateFlow<Boolean> = tabManager.toolbarVisible
    val incognitoLocked: StateFlow<Boolean> = tabManager.incognitoLocked
    val contextMenu = tabManager.contextMenu
    val prompt = promptBridge.current
    val activeDownloads: StateFlow<Int> = downloader.activeCount

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Omnibox editing state. */
    private val _omniboxText = MutableStateFlow("")
    val omniboxText: StateFlow<String> = _omniboxText.asStateFlow()

    private val _omniboxFocused = MutableStateFlow(false)
    val omniboxFocused: StateFlow<Boolean> = _omniboxFocused.asStateFlow()

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private val _findQuery = MutableStateFlow<String?>(null)
    val findQuery: StateFlow<String?> = _findQuery.asStateFlow()

    /** First-run incognito disclosure card. */
    private val _showDisclosure = MutableStateFlow(false)
    val showDisclosure: StateFlow<Boolean> = _showDisclosure.asStateFlow()

    val activeTab: BrowserTab? get() = tabManager.tab(activeTabId.value)

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            _omniboxText.debounce(120).collect { query -> refreshSuggestions(query) }
        }
    }

    // ------------------------------------------------------------- omnibox

    fun onOmniboxChange(value: String) { _omniboxText.value = value }

    fun onOmniboxFocus(focused: Boolean) {
        _omniboxFocused.value = focused
        if (!focused) _suggestions.value = emptyList()
    }

    fun startEditing() {
        _omniboxText.value = activeTab?.url?.value?.takeIf { it != "about:blank" }.orEmpty()
        _omniboxFocused.value = true
    }

    private suspend fun refreshSuggestions(query: String) {
        val trimmed = query.trim()
        // Incognito never reads history or bookmarks back into the omnibox, so a
        // private session cannot be reconstructed from what it suggests.
        if (trimmed.length < 2 || isIncognito.value || !_omniboxFocused.value) {
            _suggestions.value = emptyList()
            return
        }
        val bookmarks = bookmarkRepository.searchOnce(trimmed, 4)
            .map { Suggestion(it.title, it.url, isBookmark = true) }
        val history = historyRepository.searchOnce(trimmed, 6)
            .map { Suggestion(it.title, it.url, isBookmark = false) }
        _suggestions.value = (bookmarks + history).distinctBy { it.url }.take(8)
    }

    /** Enter pressed, or a suggestion tapped. */
    fun submit(input: String = _omniboxText.value) {
        if (input.isBlank()) return
        tabManager.loadInActive(input)
        _omniboxFocused.value = false
        _suggestions.value = emptyList()
    }

    fun searchEngine(): SearchEngine = settings.value.searchEngine

    /** True when the typed text will be treated as a URL rather than a search. */
    fun willNavigate(input: String): Boolean = UrlUtils.isLikelyUrl(input)

    // ---------------------------------------------------------- navigation

    fun reload() = tabManager.reload()
    fun stop() = tabManager.stop()
    fun goBack(): Boolean = tabManager.goBack()
    fun exitFullScreen() = tabManager.exitFullScreen()
    fun toggleKeepLoaded() = tabManager.toggleKeepLoaded()
    fun goForward() { tabManager.goForward() }
    fun load(url: String) = tabManager.loadInActive(url)

    fun proceedOverHttp(url: String) {
        val tab = activeTab ?: return
        tabManager.proceedOverHttp(tab, url)
    }

    fun toggleDesktopMode() = tabManager.toggleDesktopMode()
    fun toggleReaderMode() = tabManager.toggleReaderMode()

    // ---------------------------------------------------------------- tabs

    fun openTab(url: String? = null, incognito: Boolean = isIncognito.value) {
        maybeShowDisclosure(incognito)
        tabManager.openTab(url, incognito)
    }

    fun selectTab(id: String) = tabManager.selectTab(id)
    fun closeTab(id: String) = tabManager.closeTab(id)
    fun closeAll(incognito: Boolean) = tabManager.closeAllTabs(incognito)

    fun setIncognitoMode(enabled: Boolean) {
        maybeShowDisclosure(enabled)
        tabManager.setIncognitoMode(enabled)
    }

    fun setThumbnail(tabId: String, bitmap: android.graphics.Bitmap?) =
        tabManager.setThumbnail(tabId, bitmap)

    private fun maybeShowDisclosure(incognito: Boolean) {
        if (!incognito) return
        if (!settings.value.incognitoDisclosureShown) _showDisclosure.value = true
    }

    fun acknowledgeDisclosure() {
        _showDisclosure.value = false
        viewModelScope.launch { settingsStore.setIncognitoDisclosureShown(true) }
    }

    // ------------------------------------------------------ incognito guard

    /** Panic button: long-press the incognito icon. */
    fun panicWipe() {
        tabManager.wipeIncognito()
        engineManager.clearAllBrowsingData()
        activityBridge.toast("Incognito wiped")
    }

    fun unlockIncognito(onFailure: () -> Unit = {}) {
        biometricGate.authenticate(
            onSuccess = { tabManager.unlockIncognito() },
            onFailure = onFailure
        )
    }

    fun openFingerprintTest() =
        tabManager.openTab(FingerprintShield.FINGERPRINT_TEST_URL, incognito = true)

    // -------------------------------------------------------- find in page

    fun openFind() { _findQuery.value = "" }

    fun onFindQuery(value: String) {
        _findQuery.value = value
        tabManager.findInPage(value, forward = true)
    }

    fun findNext(forward: Boolean) {
        _findQuery.value?.let { tabManager.findInPage(it, forward) }
    }

    fun closeFind() {
        _findQuery.value = null
        tabManager.clearFind()
    }

    // --------------------------------------------------------- page actions

    fun toggleBookmark() {
        val tab = activeTab ?: return
        val url = tab.url.value
        if (!url.startsWith("http")) return
        viewModelScope.launch { bookmarkRepository.toggle(tab.title.value, url) }
    }

    fun isBookmarked(url: String) = bookmarkRepository.isBookmarked(url)

    fun sharePage() {
        val tab = activeTab ?: return
        activityBridge.sharePage(tab.title.value, tab.url.value)
    }

    fun addToHomeScreen() {
        val tab = activeTab ?: return
        activityBridge.addToHomeScreen(tab.title.value, tab.url.value, tab.thumbnail.value)
    }

    /**
     * "Print / Save as PDF": Gecko renders the page to a PDF stream which we
     * write into Downloads/Fable and hand to the system viewer, where the OS
     * print dialog is available.
     */
    fun saveAsPdf() {
        val tab = activeTab ?: return
        val name = (tab.title.value.ifBlank { UrlUtils.displayHost(tab.url.value) })
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
        tab.session.saveAsPdf().accept { stream ->
            if (stream == null) {
                activityBridge.toast("This page cannot be printed")
            } else {
                downloader.savePdf(stream, "$name.pdf", fromIncognito = tab.isIncognito)
            }
        }
    }

    // --------------------------------------------------- long-press actions

    fun handleContextAction(action: ContentMenuAction) {
        val target = contextMenu.value ?: return
        tabManager.dismissContextMenu()
        when (action) {
            ContentMenuAction.OPEN_IN_NEW_TAB ->
                target.linkUri?.let { tabManager.openTab(it, incognito = target.isIncognito) }
            ContentMenuAction.OPEN_IN_NEW_INCOGNITO_TAB -> target.linkUri?.let {
                maybeShowDisclosure(true)
                tabManager.openTab(it, incognito = true)
            }
            ContentMenuAction.DOWNLOAD_IMAGE -> target.imageUri?.let {
                downloader.enqueueUrl(it, null, fromIncognito = target.isIncognito)
            }
            ContentMenuAction.SHARE_LINK ->
                (target.linkUri ?: target.imageUri)?.let {
                    activityBridge.sharePage(target.title.orEmpty(), it)
                }
        }
    }

    fun dismissContextMenu() = tabManager.dismissContextMenu()

    fun toast(message: String) = activityBridge.toast(message)

    // ------------------------------------------------------------ settings

    fun currentHomepage(): String = settings.value.homepage

    /** Page zoom from the browser menu; persisted and applied to the engine. */
    fun setTextZoom(percent: Int) {
        viewModelScope.launch { settingsStore.setTextZoomPercent(percent) }
    }

    suspend fun settingsSnapshot(): Settings = settingsStore.settings.first()

    override fun onCleared() {
        // The engine sessions themselves are owned by the singleton TabManager,
        // but any pending page prompt belongs to this UI and must not outlive it.
        promptBridge.dismissAll()
        super.onCleared()
    }
}
