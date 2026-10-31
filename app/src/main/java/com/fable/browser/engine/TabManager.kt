package com.fable.browser.engine

import com.fable.browser.data.model.SavedTab
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.TabPersistenceRepository
import com.fable.browser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of every open tab.
 *
 * Incognito guarantees implemented here:
 *  - incognito tabs are never handed to [TabPersistenceRepository], so they
 *    cannot survive a process death or appear in a restore;
 *  - each incognito tab gets its own `contextId` when isolation is ON, and its
 *    storage partition is wiped the moment the tab closes;
 *  - [wipeIncognito] closes every private tab, clears each container, and
 *    resets the fingerprint identity so the next session looks like a new user;
 *  - an idle timer ([Settings.incognitoAutoWipe]) triggers the same wipe.
 */
@Singleton
class TabManager @Inject constructor(
    private val sessionFactory: SessionFactory,
    private val delegateFactory: SessionDelegateFactory,
    private val engineManager: EngineManager,
    private val fingerprintShield: FingerprintShield,
    private val proxyConfig: ProxyConfig,
    private val tabPersistence: TabPersistenceRepository,
    private val historyRepository: com.fable.browser.data.repo.HistoryRepository,
    private val promptBridge: PromptBridge,
    private val settingsStore: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _isIncognitoMode = MutableStateFlow(false)
    val isIncognitoMode: StateFlow<Boolean> = _isIncognitoMode.asStateFlow()

    private val _contextMenu = MutableStateFlow<ContextMenuTarget?>(null)
    val contextMenu: StateFlow<ContextMenuTarget?> = _contextMenu.asStateFlow()

    private val _toolbarVisible = MutableStateFlow(true)
    val toolbarVisible: StateFlow<Boolean> = _toolbarVisible.asStateFlow()

    /** True when the biometric/PIN gate must be passed before private tabs render. */
    private val _incognitoLocked = MutableStateFlow(false)
    val incognitoLocked: StateFlow<Boolean> = _incognitoLocked.asStateFlow()

    /** Latest settings snapshot, read synchronously by the Gecko delegates. */
    @Volatile private var settings: Settings = Settings()

    /** Shared incognito container used when per-tab isolation is OFF. */
    private var sharedIncognitoContext: String? = null

    private var autoWipeJob: Job? = null
    private var lastScrollY = 0

    private companion object {
        const val TRIM_RUNNING_CRITICAL = 15
        const val TRIM_UI_HIDDEN = 20
    }

    init {
        scope.launch {
            settings = settingsStore.settings.first()
            settingsStore.settings.collect { updated ->
                val wipeChanged = updated.incognitoAutoWipe != settings.incognitoAutoWipe
                val budgetChanged = updated.tabUnloadMode != settings.tabUnloadMode ||
                    updated.keepLoadedTabs != settings.keepLoadedTabs
                settings = updated
                if (wipeChanged) restartAutoWipeTimer()
                if (budgetChanged) enforceTabBudget()
            }
        }
    }

    // ------------------------------------------------------------------ query

    fun tab(id: String?): BrowserTab? = _tabs.value.firstOrNull { it.id == id }

    val activeTab: BrowserTab? get() = tab(_activeTabId.value)

    fun visibleTabs(): List<BrowserTab> = _tabs.value.filter { it.isIncognito == _isIncognitoMode.value }

    fun normalTabCount(): Int = _tabs.value.count { !it.isIncognito }

    fun incognitoTabCount(): Int = _tabs.value.count { it.isIncognito }

    // ------------------------------------------------------------- lifecycle

    /**
     * Restores persisted normal tabs, or opens a fresh one. Incognito tabs are
     * intentionally absent — they never touched storage.
     */
    fun restoreOrCreate() {
        scope.launch {
            val current = settingsStore.settings.first()
            settings = current
            val saved = if (current.restoreTabs) tabPersistence.load() else emptyList()
            if (saved.isEmpty()) {
                openTab(current.homepage.takeIf { it.isNotBlank() && it != "about:blank" }, incognito = false)
            } else {
                val ordered = saved.sortedBy { it.position }
                val restored = ordered.map { entry ->
                    val tab = createTab(incognito = false, open = false)
                    tab.updateTitle(entry.title)
                    tab.updateUrl(entry.url)
                    tab.markUnloaded()
                    tab
                }
                val activeIndex = ordered.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
                restored.forEachIndexed { index, tab ->
                    tab.lastAccessed = System.currentTimeMillis() - (restored.size - index) * 1000L
                }
                restored.getOrNull(activeIndex)?.let { selectTab(it.id) }
            }
            restartAutoWipeTimer()
        }
    }

    /** Persist normal tabs only. Called from onStop. */
    fun persist() {
        val snapshot = _tabs.value.filter { !it.isIncognito }.mapIndexed { index, tab ->
            SavedTab(
                url = tab.url.value,
                title = tab.title.value,
                position = index,
                isActive = tab.id == _activeTabId.value
            )
        }.filter { it.url.startsWith("http", ignoreCase = true) }
        scope.launch { tabPersistence.save(snapshot) }
    }

    /** Honour the clear-on-exit switches. Called when the task is removed. */
    fun onAppExit(onDone: () -> Unit = {}) {
        wipeIncognito()
        val s = settings
        if (s.clearCookiesOnExit) engineManager.clearCookies()
        if (s.clearCacheOnExit) engineManager.clearCache()
        if (s.clearHistoryOnExit) scope.launch { historyRepository.clearAll() }
        onDone()
    }

    /**
     * Called from MainActivity.onStop: persist the normal tabs and, when the
     * incognito lock is enabled, arm the gate so returning to the app requires
     * biometrics before any private tab is shown again.
     */
    fun onAppBackgrounded() {
        persist()
        activeTab?.session?.takeIf { it.isOpen }?.setActive(false)
        if (settings.incognitoLock && _tabs.value.any { it.isIncognito }) {
            _incognitoLocked.value = true
        }
    }

    fun onAppForegrounded() {
        activeTab?.let { tab ->
            if (tab.isEvicted) resumeIfEvicted(tab)
            tab.session.takeIf { it.isOpen }?.setActive(true)
        }
    }

    fun unlockIncognito() { _incognitoLocked.value = false }

    /** ACTION_VIEW from another app — always lands in a normal tab. */
    fun openExternalUrl(url: String) {
        if (url.isBlank()) return
        openTab(url, incognito = false)
    }

    /** ACTION_WEB_SEARCH from another app. */
    fun openExternalSearch(query: String) {
        if (query.isBlank()) return
        openTab(UrlUtils.toUrlOrSearch(query, settings.searchEngine), incognito = false)
    }

    /**
     * Store a freshly captured tab preview. Incognito previews stay in this
     * process's heap only — there is no thumbnail cache directory at all — and
     * are recycled by [BrowserTab.close].
     */
    fun setThumbnail(tabId: String, bitmap: android.graphics.Bitmap?) {
        tab(tabId)?.updateThumbnail(bitmap)
    }

    // ------------------------------------------------------------ tab actions

    fun openTab(url: String? = null, incognito: Boolean = _isIncognitoMode.value): BrowserTab {
        val tab = createTab(incognito)
        activate(tab)
        val target = url?.takeIf { it.isNotBlank() }
        if (target != null) load(tab, target) else tab.updateUrl("about:blank")
        touchIncognitoActivity()
        enforceTabBudget()
        return tab
    }

    private fun createTab(
        incognito: Boolean,
        open: Boolean = true,
        inheritedContextId: String? = null
    ): BrowserTab {
        val contextId = if (incognito) {
            inheritedContextId ?: if (settings.isolateIncognitoTabs) sessionFactory.newContextId()
            else sharedIncognitoContext ?: sessionFactory.newContextId().also { sharedIncognitoContext = it }
        } else null

        val session = if (incognito) {
            sessionFactory.createIncognitoSession(settings, contextId!!, open)
        } else {
            sessionFactory.createNormalSession(settings, open)
        }

        val tab = BrowserTab(isIncognito = incognito, initialSession = session, contextId = contextId)
        delegateFactory.attach(tab, scope, { settings }, callbacks)
        _tabs.value = _tabs.value + tab
        return tab
    }

    private fun activate(tab: BrowserTab) {
        val previous = activeTab
        if (previous != null && previous.id != tab.id && previous.session.isOpen) {
            previous.session.setActive(false)
        }
        tab.touch()
        _activeTabId.value = tab.id
        _isIncognitoMode.value = tab.isIncognito
        if (tab.session.isOpen) tab.session.setActive(true)
    }

    fun selectTab(id: String) {
        val tab = tab(id) ?: return
        activate(tab)
        resumeIfEvicted(tab)
        _toolbarVisible.value = true
        touchIncognitoActivity()
        enforceTabBudget()
    }

    fun setPinned(id: String, pinned: Boolean) {
        tab(id)?.updatePinned(pinned)
        if (!pinned) enforceTabBudget()
    }

    fun toggleKeepLoaded() {
        val tab = activeTab ?: return
        setPinned(tab.id, !tab.isPinned.value)
    }

    fun closeTab(id: String) {
        val tab = tab(id) ?: return
        val remaining = _tabs.value.filter { it.id != id }
        _tabs.value = remaining

        // Incognito teardown: drop the storage partition with the tab.
        if (tab.isIncognito) {
            tab.contextId?.let { engineManager.clearContainer(it) }
            if (remaining.none { it.isIncognito }) {
                sharedIncognitoContext = null
                fingerprintShield.resetSession()
            }
        }
        tab.close()

        if (_activeTabId.value == id) {
            val sameMode = remaining.filter { it.isIncognito == tab.isIncognito }
            val next = sameMode.maxByOrNull { it.lastAccessed } ?: remaining.maxByOrNull { it.lastAccessed }
            if (next != null) {
                activate(next)
                resumeIfEvicted(next)
            } else {
                _activeTabId.value = null
                _isIncognitoMode.value = false
            }
        }
        if (remaining.isEmpty()) openTab(incognito = false)
    }

    fun closeAllTabs(incognito: Boolean) {
        _tabs.value.filter { it.isIncognito == incognito }.forEach { closeTab(it.id) }
    }

    /** Switch between the normal and incognito tab strips. */
    fun setIncognitoMode(enabled: Boolean) {
        _isIncognitoMode.value = enabled
        val candidate = _tabs.value.lastOrNull { it.isIncognito == enabled }
        if (candidate == null) {
            openTab(incognito = enabled)
        } else {
            selectTab(candidate.id)
        }
    }

    /**
     * The panic path: close every incognito tab, wipe each container, and
     * reset the spoofed identity. Nothing survives.
     */
    fun wipeIncognito() {
        promptBridge.dismissAll()
        _incognitoLocked.value = false
        autoWipeJob?.cancel()
        val incognitoTabs = _tabs.value.filter { it.isIncognito }
        if (incognitoTabs.isEmpty()) {
            fingerprintShield.resetSession()
            return
        }
        incognitoTabs.forEach { tab ->
            tab.contextId?.let { engineManager.clearContainer(it) }
            tab.close()
        }
        _tabs.value = _tabs.value.filter { !it.isIncognito }
        sharedIncognitoContext = null
        fingerprintShield.resetSession()

        if (_tabs.value.isEmpty()) {
            _isIncognitoMode.value = false
            openTab(incognito = false)
        } else if (activeTab == null) {
            _isIncognitoMode.value = false
            _tabs.value.lastOrNull()?.let { selectTab(it.id) }
        }
    }

    // ------------------------------------------------------------ navigation

    fun load(tab: BrowserTab, input: String) {
        val url = UrlUtils.toUrlOrSearch(input, settings.searchEngine)
        tab.updateInterstitial(null)
        ensureLive(tab)
        tab.session.load(GeckoSession.Loader().uri(url))
        touchIncognitoActivity()
    }

    fun loadInActive(input: String) {
        val tab = activeTab ?: openTab(incognito = _isIncognitoMode.value)
        load(tab, input)
    }

    fun reload() = activeTab?.let { tab ->
        tab.updateInterstitial(null)
        if (tab.isEvicted || !tab.session.isOpen) {
            if (!tab.isEvicted) tab.markUnloaded()
            resumeIfEvicted(tab)
        } else {
            tab.session.reload()
        }
    }

    fun stop() = activeTab?.session?.stop()

    fun exitFullScreen() {
        activeTab?.session?.takeIf { it.isOpen }?.exitFullScreen()
    }

    fun goBack(): Boolean {
        val tab = activeTab ?: return false
        if (tab.interstitial.value != null) {
            tab.updateInterstitial(null)
            return true
        }
        if (!tab.canGoBack.value) return false
        tab.session.goBack()
        return true
    }

    fun goForward() = activeTab?.takeIf { it.canGoForward.value }?.session?.goForward()

    /** HTTPS-only interstitial escape hatch, scoped to this tab and host. */
    fun proceedOverHttp(tab: BrowserTab, url: String) {
        UrlUtils.host(url)?.let { tab.allowHttp(it) }
        tab.updateInterstitial(null)
        ensureLive(tab)
        tab.session.load(GeckoSession.Loader().uri(url))
    }

    fun toggleDesktopMode() {
        val tab = activeTab ?: return
        val desktop = !tab.isDesktopMode.value
        applyDesktopMode(tab.session, desktop)
        tab.updateDesktopMode(desktop)
        tab.session.reload()
    }

    private fun applyDesktopMode(session: GeckoSession, desktop: Boolean) {
        session.settings.userAgentMode =
            if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }

    fun toggleReaderMode() {
        val tab = activeTab ?: return
        tab.updateReaderMode(!tab.isReaderMode.value)
        proxyConfig.toggleReaderMode()
    }

    // -------------------------------------------------------- find in page

    fun findInPage(query: String, forward: Boolean = true) {
        val tab = activeTab ?: return
        if (query.isBlank()) {
            clearFind()
            return
        }
        val flags = if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS
        tab.session.finder.find(query, flags).accept { result ->
            result?.let { tab.updateFindMatch(FindMatch(it.current, it.total)) }
        }
    }

    fun clearFind() {
        val tab = activeTab ?: return
        tab.session.finder.clear()
        tab.updateFindMatch(null)
    }

    // ------------------------------------------------- memory / eviction

    /**
     * Free the engine session of background tabs under memory pressure. The
     * tab keeps its URL so selecting it reloads transparently.
     */
    fun evictBackgroundTabs() {
        val activeId = _activeTabId.value
        _tabs.value
            .filter { it.id != activeId && !it.isPinned.value && !it.isMediaPlaying }
            .forEach { unload(it) }
    }

    fun onTrimMemory(level: Int) {
        if (settings.tabUnloadMode == TabUnloadMode.KEEP_ALL) return
        if (level >= TRIM_RUNNING_CRITICAL && level != TRIM_UI_HIDDEN) evictBackgroundTabs()
    }

    private fun enforceTabBudget() {
        val mode = settings.tabUnloadMode
        if (mode == TabUnloadMode.KEEP_ALL) return
        val keep = if (mode == TabUnloadMode.AGGRESSIVE) 0 else settings.keepLoadedTabs
        val activeId = _activeTabId.value
        _tabs.value
            .filter { it.id != activeId && !it.isEvicted && !it.isPinned.value && !it.isMediaPlaying }
            .sortedByDescending { it.lastAccessed }
            .drop(keep)
            .forEach { unload(it) }
    }

    private fun unload(tab: BrowserTab) {
        if (tab.isEvicted || tab.id == _activeTabId.value) return
        val session = tab.session
        tab.markUnloaded()
        runCatching { if (session.isOpen) session.close() }
    }

    private fun ensureLive(tab: BrowserTab) {
        if (tab.isEvicted) {
            tab.isEvicted = false
            tab.evictedUrl = null
            tab.savedState?.let { state -> freshSession(tab).restoreState(state) } ?: freshSession(tab)
        } else if (!tab.session.isOpen) {
            freshSession(tab)
        }
    }

    private fun resumeIfEvicted(tab: BrowserTab) {
        tab.touch()
        if (!tab.isEvicted) return
        tab.isEvicted = false
        val url = tab.evictedUrl
        tab.evictedUrl = null
        val state = tab.savedState
        val session = freshSession(tab)
        when {
            state != null -> session.restoreState(state)
            url != null && url != "about:blank" -> session.load(GeckoSession.Loader().uri(url))
        }
    }

    private fun freshSession(tab: BrowserTab): GeckoSession {
        val session = if (tab.isIncognito) {
            sessionFactory.createIncognitoSession(settings, tab.contextId ?: sessionFactory.newContextId())
        } else {
            sessionFactory.createNormalSession(settings)
        }
        if (tab.isDesktopMode.value) applyDesktopMode(session, true)
        tab.replaceSession(session)
        delegateFactory.attach(tab, scope, { settings }, callbacks)
        session.setActive(tab.id == _activeTabId.value)
        return session
    }

    // -------------------------------------------------- incognito auto-wipe

    private fun restartAutoWipeTimer() {
        autoWipeJob?.cancel()
        val minutes = settings.incognitoAutoWipe.minutes
        if (minutes <= 0) return
        autoWipeJob = scope.launch {
            delay(minutes * 60_000L)
            if (_tabs.value.any { it.isIncognito }) wipeIncognito()
        }
    }

    /** Any incognito interaction resets the idle countdown. */
    private fun touchIncognitoActivity() {
        if (_isIncognitoMode.value) restartAutoWipeTimer()
    }

    // ------------------------------------------------------ context menu

    fun dismissContextMenu() { _contextMenu.value = null }

    // ------------------------------------------------------------ callbacks

    private val callbacks = object : TabCallbacks {
        override fun onNewSessionRequested(tab: BrowserTab, uri: String): GeckoSession? {
            // Inherit the parent's mode and container so a popup from an
            // incognito tab can never escape into the persistent profile.
            val child = createTab(tab.isIncognito, open = false, inheritedContextId = tab.contextId)
            if (uri.isNotBlank()) child.updateUrl(uri)
            child.touch()
            _activeTabId.value = child.id
            _isIncognitoMode.value = child.isIncognito
            _toolbarVisible.value = true
            scope.launch(Dispatchers.Main) { enforceTabBudget() }
            return child.session
        }

        override fun onSessionLost(tab: BrowserTab, crashed: Boolean) {
            if (tab.isEvicted) return
            tab.markUnloaded()
            if (!crashed && tab.id == _activeTabId.value) resumeIfEvicted(tab)
        }

        override fun onMediaStateChanged(tab: BrowserTab, playing: Boolean) {
            tab.isMediaPlaying = playing
            if (!playing) scope.launch(Dispatchers.Main) { enforceTabBudget() }
        }

        override fun onCloseRequested(tab: BrowserTab) = closeTab(tab.id)

        override fun onContextMenu(target: ContextMenuTarget) {
            _contextMenu.value = target
        }

        override fun onScroll(tab: BrowserTab, scrollY: Int) {
            val delta = scrollY - lastScrollY
            lastScrollY = scrollY
            if (scrollY <= 0) {
                _toolbarVisible.value = true
            } else if (delta > 12) {
                _toolbarVisible.value = false
            } else if (delta < -12) {
                _toolbarVisible.value = true
            }
        }
    }
}
