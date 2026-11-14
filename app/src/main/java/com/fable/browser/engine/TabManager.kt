package com.fable.browser.engine

import com.fable.browser.data.model.SavedTab
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.IncognitoTabStore
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
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TabManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val incognitoStore: com.fable.browser.data.repo.IncognitoTabStore,
    private val superIncognito: SuperIncognito,
    private val sessionFactory: SessionFactory,
    private val delegateFactory: SessionDelegateFactory,
    private val engineManager: EngineManager,
    private val fingerprintShield: FingerprintShield,
    private val proxyConfig: ProxyConfig,
    private val tabPersistence: TabPersistenceRepository,
    private val historyRepository: com.fable.browser.data.repo.HistoryRepository,
    private val promptBridge: PromptBridge,
    private val settingsStore: SettingsStore,
    private val passwordManager: PasswordManager,
    private val downloader: Downloader,
    private val activityBridge: com.fable.browser.util.ActivityBridge
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

    private var lastKilledTabId: String? = null
    private var lastKilledAt = 0L

    private val _manualFullScreen = MutableStateFlow(false)
    val manualFullScreen: StateFlow<Boolean> = _manualFullScreen.asStateFlow()

    private val _incognitoLocked = MutableStateFlow(false)
    val incognitoLocked: StateFlow<Boolean> = _incognitoLocked.asStateFlow()

    @Volatile private var settings: Settings = Settings()

    private var sharedIncognitoContext: String? = null

    private var autoWipeJob: Job? = null
    private var idleSweepJob: Job? = null
    private var hiddenUnloadJob: Job? = null
    private var persistJob: Job? = null
    @Volatile private var appVisible = true

    fun memoryPolicy(): MemoryPolicy = MemoryPolicy.resolve(appContext, settings)
    private var lastScrollY = 0
    private val hybridTabPrefs by lazy {
        appContext.getSharedPreferences("fable_hybrid_tab_state", android.content.Context.MODE_PRIVATE)
    }

    private fun saveTabHybridState(url: String, manualOverride: Boolean?) {
        if (!settings.hybridPersistTabMode) return
        val host = UrlUtils.host(url) ?: return
        runCatching {
            val ed = hybridTabPrefs.edit()
            if (manualOverride == null) ed.remove(host) else ed.putBoolean(host, manualOverride)
            ed.apply()
        }
    }

    private fun loadTabHybridState(url: String): Boolean? {
        if (!settings.hybridPersistTabMode) return null
        val host = UrlUtils.host(url) ?: return null
        return if (hybridTabPrefs.contains(host)) hybridTabPrefs.getBoolean(host, false) else null
    }

    private companion object {
        const val SUPER_WAIT_MS = 6000L
        const val UNDO_WINDOW_MS = 5000L
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_RUNNING_CRITICAL = 15
        const val KILL_LOOP_WINDOW_MS = 60_000L
        const val TRIM_UI_HIDDEN = 20
        const val TRIM_BACKGROUND = 40
        const val TRIM_COMPLETE = 80
    }

    init {
        scope.launch {
            settings = settingsStore.settings.first()
            settingsStore.settings.collect { updated ->
                val wipeChanged = updated.incognitoAutoWipe != settings.incognitoAutoWipe
                val budgetChanged = updated.tabUnloadMode != settings.tabUnloadMode ||
                    updated.keepLoadedTabs != settings.keepLoadedTabs ||
                    updated.memoryMode != settings.memoryMode ||
                    updated.idleTabMinutes != settings.idleTabMinutes
                val keepChanged = updated.keepIncognitoTabs != settings.keepIncognitoTabs || updated.keepSuperTabs != settings.keepSuperTabs
                val jsChanged = updated.incognitoJavascript != settings.incognitoJavascript
                settings = updated
                if (jsChanged) applyJavascriptToTabs(updated)
                if (wipeChanged) restartAutoWipeTimer()
                if (budgetChanged) {
                    enforceTabBudget()
                    if (appVisible) restartIdleSweep()
                }
                if (keepChanged) persist()
            }
        }
    }

    private fun applyJavascriptToTabs(current: Settings) {
        _tabs.value.forEach { tab ->
            val changed = runCatching { sessionFactory.applyJavascript(tab.session, current, tab.isIncognito) }.getOrDefault(false)
            if (changed && tab.session.isOpen && tab.url.value.startsWith("http", ignoreCase = true)) {
                runCatching { tab.session.reload() }
            }
        }
    }

    fun tab(id: String?): BrowserTab? = _tabs.value.firstOrNull { it.id == id }

    val activeTab: BrowserTab? get() = tab(_activeTabId.value)

    fun visibleTabs(): List<BrowserTab> = _tabs.value.filter { it.isIncognito == _isIncognitoMode.value }

    fun normalTabCount(): Int = _tabs.value.count { !it.isIncognito }

    fun incognitoTabCount(): Int = _tabs.value.count { it.isIncognito }

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
                    val savedManual = loadTabHybridState(entry.url)
                    tab.manualHybridOverride = savedManual
                    val wantHybrid = savedManual ?: (current.hybridGeckoBlinkBeta && (!current.hybridAutoForGames || HybridEngineBridge.isHeavy3dGameUrl(entry.url, current.hybridCustomDomains)))
                    tab.updateHybridBlink(wantHybrid)
                    tab.markUnloaded()
                    tab
                }
                val activeIndex = ordered.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
                restored.forEachIndexed { index, tab ->
                    tab.lastAccessed = System.currentTimeMillis() - (restored.size - index) * 1000L
                }
                restored.getOrNull(activeIndex)?.let { selectTab(it.id) }
            }
            if (current.keepIncognitoTabs || current.keepSuperTabs) restoreIncognitoTabs()
            restartAutoWipeTimer()
            restartIdleSweep()
        }
    }

    private suspend fun restoreIncognitoTabs() {
        val entries = withContext(Dispatchers.IO) { incognitoStore.load() }
        if (entries.isEmpty()) return
        if (entries.any { it.superTab }) withContext(Dispatchers.IO) { superIncognito.restoreIdentity() }
        var toSelect: BrowserTab? = null
        entries.forEachIndexed { index, entry ->
            val tab = createTab(incognito = true, open = false, superMode = entry.superTab)
            tab.updateTitle(entry.title)
            tab.updateUrl(entry.url)
            tab.savedState = entry.state?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
            tab.markUnloaded()
            if (entry.pinned) tab.updatePinned(true)
            tab.lastAccessed = System.currentTimeMillis() - (entries.size - index) * 1000L
            if (entry.active) toSelect = tab
        }
        if (settings.incognitoLock) _incognitoLocked.value = true
        toSelect?.let { selectTab(it.id) }
    }

    fun exportNormalTabs(): List<SavedTab> {
        val activeId = _activeTabId.value
        return _tabs.value.filter { !it.isIncognito }.mapIndexed { index, tab ->
            SavedTab(url = tab.url.value, title = tab.title.value, position = index, isActive = tab.id == activeId)
        }.filter { it.url.startsWith("http", ignoreCase = true) }
    }

    fun importTabs(entries: List<SavedTab>): Int {
        val existing = _tabs.value.filter { !it.isIncognito }.map { it.url.value }.toHashSet()
        var count = 0
        entries.sortedBy { it.position }.forEach { entry ->
            if (!entry.url.startsWith("http", ignoreCase = true) || entry.url in existing) return@forEach
            val tab = createTab(incognito = false, open = false)
            tab.updateTitle(entry.title)
            tab.updateUrl(entry.url)
            tab.markUnloaded()
            tab.lastAccessed = System.currentTimeMillis() - 60_000L
            existing.add(entry.url)
            count++
        }
        if (count > 0) schedulePersist()
        return count
    }

    fun persist() {
        persistJob?.cancel()
        persistJob = null
        val activeId = _activeTabId.value
        val snapshot = _tabs.value.filter { !it.isIncognito }.mapIndexed { index, tab ->
            SavedTab(
                url = tab.url.value,
                title = tab.title.value,
                position = index,
                isActive = tab.id == activeId
            )
        }.filter { it.url.startsWith("http", ignoreCase = true) }
        val keepNormalIncognito = settings.keepIncognitoTabs
        val keepSuper = settings.keepSuperTabs
        val keepIncognito = keepNormalIncognito || keepSuper
        val incognitoTabs = if (keepIncognito) {
            _tabs.value.filter { tab ->
                tab.isIncognito && tab.url.value.startsWith("http", ignoreCase = true) &&
                    (if (tab.isSuper) keepSuper else keepNormalIncognito)
            }.map { tab ->
                PendingIncognito(
                    url = tab.url.value,
                    title = tab.title.value,
                    state = tab.savedState,
                    active = tab.id == activeId,
                    pinned = tab.isPinned.value,
                    superTab = tab.isSuper
                )
            }
        } else emptyList()
        val saveIdentity = keepSuper && incognitoTabs.any { it.superTab }
        scope.launch {
            tabPersistence.save(snapshot)
            withContext(Dispatchers.IO) {
                if (keepIncognito) {
                    val entries = incognitoTabs.map { t ->
                        IncognitoTabStore.Entry(
                            url = t.url,
                            title = t.title,
                            state = t.state?.let { runCatching { it.toString() }.getOrNull() },
                            active = t.active,
                            pinned = t.pinned,
                            superTab = t.superTab
                        )
                    }
                    incognitoStore.save(entries)
                } else {
                    incognitoStore.clear()
                }
                if (saveIdentity) superIncognito.saveIdentity() else superIncognito.clearSaved()
            }
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(3_000L)
            persistJob = null
            persist()
        }
    }

    fun onAppExit(onDone: () -> Unit = {}) {
        if (settings.keepIncognitoTabs || settings.keepSuperTabs) persist() else wipeIncognito()
        val s = settings
        if (s.clearCookiesOnExit) engineManager.clearCookies()
        if (s.clearCacheOnExit) engineManager.clearCache()
        if (s.clearHistoryOnExit) scope.launch { historyRepository.clearAll() }
        onDone()
    }

    fun onAppPaused() {
        FableUnifiedEngine.setFrameMonitorActive(false)
        FableUnifiedEngine.flushBlinkCookies()
        runCatching { activeTab?.session?.takeIf { it.isOpen }?.setActive(false) }
    }

    fun onAppResumed() {
        val tab = activeTab ?: return
        if (tab.isEvicted || isGated(tab)) return
        FableUnifiedEngine.setFrameMonitorActive(tab.isHybridBlink.value)
        runCatching { tab.session.takeIf { it.isOpen }?.setActive(true) }
    }

    fun onAppBackgrounded(temporary: Boolean = false) {
        appVisible = false
        FableUnifiedEngine.flushBlinkCookies()
        if (!temporary && settings.batteryFreezeBgTabs) {
            _tabs.value.forEach { t ->
                runCatching { t.retainedBlinkView?.onPause() }
            }
        }
        persist()
        idleSweepJob?.cancel()
        activeTab?.session?.takeIf { it.isOpen }?.setActive(false)
        hiddenUnloadJob?.cancel()
        if (temporary) return
        if (settings.incognitoLock && _tabs.value.any { it.isIncognito }) {
            _incognitoLocked.value = true
        }
        val hiddenMs = memoryPolicy().hiddenUnloadMs
        if (hiddenMs > 0) {
            hiddenUnloadJob = scope.launch {
                delay(hiddenMs)
                if (!appVisible) evictBackgroundTabs()
            }
        }
    }

    fun onAppForegrounded() {
        appVisible = true
        runCatching { activeTab?.retainedBlinkView?.onResume() }
        hiddenUnloadJob?.cancel()
        restartIdleSweep()
        activeTab?.let { tab ->
            if (isGated(tab)) return@let
            if (tab.isEvicted) resumeIfEvicted(tab)
        }
    }

    private fun isGated(tab: BrowserTab): Boolean = tab.isIncognito && _incognitoLocked.value

    fun unlockIncognito() {
        _incognitoLocked.value = false
        activeTab?.let { tab ->
            if (tab.isEvicted) resumeIfEvicted(tab)
            runCatching { tab.session.takeIf { it.isOpen }?.setActive(true) }
        }
    }

    fun openExternalUrl(url: String) {
        if (url.isBlank()) return
        openTab(url, incognito = false)
    }

    fun openExternalSearch(query: String) {
        if (query.isBlank()) return
        openTab(UrlUtils.toUrlOrSearch(query, settings.searchEngine), incognito = false)
    }

    fun setThumbnail(tabId: String, bitmap: android.graphics.Bitmap?) {
        tab(tabId)?.updateThumbnail(bitmap)
    }

    fun openSuperTab(url: String? = null): BrowserTab = openTab(url, incognito = true, superMode = true)

    fun newSuperIdentity() {
        _tabs.value.filter { it.isSuper }.forEach { closeTab(it.id) }
        superIncognito.endSession()
        scope.launch(Dispatchers.IO) { superIncognito.clearSaved() }
    }

    fun openTab(url: String? = null, incognito: Boolean = _isIncognitoMode.value, superMode: Boolean = false): BrowserTab {
        val tab = createTab(incognito || superMode, superMode = superMode)
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
        inheritedContextId: String? = null,
        superMode: Boolean = false
    ): BrowserTab {
        val contextId = if (superMode) {
            inheritedContextId ?: if (settings.isolateIncognitoTabs) sessionFactory.newContextId()
            else superIncognito.sharedContextId ?: sessionFactory.newContextId().also { superIncognito.sharedContextId = it }
        } else if (incognito) {
            inheritedContextId ?: if (settings.isolateIncognitoTabs) sessionFactory.newContextId()
            else sharedIncognitoContext ?: sessionFactory.newContextId().also { sharedIncognitoContext = it }
        } else null

        val session = when {
            superMode -> sessionFactory.createSuperSession(settings, contextId!!, superIncognito.current(settings), open)
            incognito -> sessionFactory.createIncognitoSession(settings, contextId!!, open)
            else -> sessionFactory.createNormalSession(settings, open)
        }

        val tab = BrowserTab(
            isIncognito = incognito || superMode,
            initialSession = session,
            contextId = contextId,
            isSuper = superMode
        )
        wireHybridCallbacks(tab)
        delegateFactory.attach(tab, scope, { settings }, callbacks)
        _tabs.value = _tabs.value + tab
        return tab
    }

    private fun wireHybridCallbacks(tab: BrowserTab) {
        tab.hibernateGeckoWhenHybrid = settings.hybridHibernateGecko
        tab.fastAssetBypassEnabled = settings.hybridCppFastAssetBypass
        tab.hybridBlockChecker = { reqUrl, docHost ->
            val s = settings
            val adBlock = s.hybridGeckoAdBlock && (if (tab.isIncognito) s.incognitoAdBlock else s.adBlockEnabled)
            proxyConfig.shouldBlockHybrid(reqUrl, docHost, adBlock)
        }
        tab.hybridCustomUserAgent = {
            val s = settings
            when {
                tab.isSuper -> superIncognito.current(s).ua.takeIf { it.isNotBlank() }
                tab.isIncognito && s.incognitoFingerprintProtection ->
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
                s.normalTabShield ->
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
                else -> null
            }
        }
        tab.hybridExtraHeadersProvider = { _ ->
            val s = settings
            val sendDnt = if (tab.isIncognito) s.incognitoSendDnt else s.sendGpc
            val sendGpc = if (tab.isIncognito) s.incognitoGpc else s.sendGpc
            val shield = tab.isSuper || (tab.isIncognito && s.incognitoFingerprintProtection) || s.normalTabShield
            buildMap {
                if (sendDnt) put("DNT", "1")
                if (sendGpc) put("Sec-GPC", "1")
                if (shield) {
                    val lang = if (tab.isSuper) {
                        superIncognito.current(s).acceptLanguage().ifBlank { "en-US,en;q=0.9" }
                    } else {
                        "en-US,en;q=0.9"
                    }
                    put("Accept-Language", lang)
                }
            }
        }
        tab.hybridBootstrapProvider = { pageUrl ->
            val s = settings
            val id = if (tab.isSuper) superIncognito.current(s) else null
            HybridEngineBridge.buildCombinedBootstrapScript(
                settings = s,
                isIncognito = tab.isIncognito,
                isSuper = tab.isSuper,
                superIdentity = id,
                pageUrl = pageUrl.ifBlank { tab.url.value }
            )
        }
        tab.hybridArchiveScriptProvider = { reqId, mode ->
            HybridEngineBridge.buildBlinkArchiveScript(
                reqId = reqId,
                mode = mode,
                incognito = tab.isIncognito,
                settings = settings
            )
        }
        tab.hybridArchiveMessageHandler = { rawJson ->
            proxyConfig.onHybridArchiveMessage(rawJson)
        }
        tab.hybridDownloadHandler = { url, contentDisposition, mimeType ->
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                val suggested = FableUnifiedEngine.guessDownloadFileName(url, contentDisposition, mimeType)
                downloader.enqueueUrl(url, suggested, tab.isIncognito)
            }
        }
        tab.hybridScrollHandler = { scrollY ->
            if (tab.id == _activeTabId.value) {
                callbacks.onScroll(tab, scrollY)
            }
        }
        tab.hybridContextMenuHandler = { target ->
            callbacks.onContextMenu(target)
        }
        tab.hybridFileChooserHandler = { mimeTypes, allowMultiple, onResult ->
            activityBridge.requestFiles(mimeTypes, allowMultiple, onResult)
        }
        tab.hybridExternalUrlHandler = { externalUrl ->
            activityBridge.openExternal(externalUrl)
        }
    }

    fun toggleHybridBlinkMode() {
        val tab = activeTab ?: return
        val next = !tab.isHybridBlink.value
        tab.manualHybridOverride = next
        saveTabHybridState(tab.url.value, next)
        setHybridBlinkMode(tab, next)
    }

    fun setHybridBlinkMode(tab: BrowserTab, enabled: Boolean) {
        wireHybridCallbacks(tab)
        if (enabled && tab.retainedBlinkView == null) {
            tab.lastLoadedBlinkUrl = ""
        }
        tab.updateHybridBlink(enabled)
        if (enabled) {
            onGameModeStarted()
            if (tab.session.isOpen) {
                runCatching {
                    tab.session.stop()
                    if (settings.hybridHibernateGecko) {
                        tab.session.setActive(false)
                        setPriority(tab, high = false)
                    }
                }
            }
        } else {
            tab.destroyRetainedBlinkView()
            val currentUrl = tab.url.value
            if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
                ensureLive(tab)
                tab.session.load(GeckoSession.Loader().uri(currentUrl))
            }
        }
    }

    private fun activate(tab: BrowserTab) {
        val previous = activeTab
        if (previous != null && previous.id != tab.id) {
            previous.touch()
            if (previous.session.isOpen) {
                previous.session.setActive(false)
                engineManager.setTabActive(previous.session, false)
                setPriority(previous, high = false)
            }
        }
        tab.touch()
        _activeTabId.value = tab.id
        _isIncognitoMode.value = tab.isIncognito
        passwordManager.privateActive = tab.isIncognito
        if (tab.session.isOpen) {
            tab.session.setActive(true)
            engineManager.setTabActive(tab.session, true)
            setPriority(tab, high = true)
        }
    }

    private var lastSelectAt = 0L

    fun selectTab(id: String) {
        val tab = tab(id) ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (id == _activeTabId.value && !tab.isEvicted) {
            _toolbarVisible.value = true
            return
        }
        if (now - lastSelectAt < 400) return
        lastSelectAt = now
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
        commitPendingClose()
        val tab = tab(id) ?: return
        val remaining = _tabs.value.filter { it.id != id }
        _tabs.value = remaining
        releaseClosed(tab)
        selectAfterClose(tab, remaining)
        if (remaining.isEmpty()) openTab(incognito = false)
    }

    private fun releaseClosed(tab: BrowserTab) {
        val remaining = _tabs.value
        if (tab.isSuper) {
            if (remaining.none { it.contextId == tab.contextId }) tab.contextId?.let { engineManager.clearContainer(it) }
            if (remaining.none { it.isSuper }) {
                superIncognito.endSession()
                scope.launch(Dispatchers.IO) { superIncognito.clearSaved() }
            }
        }
        if (tab.isIncognito) {
            if (!tab.isSuper && remaining.none { it.contextId == tab.contextId }) {
                tab.contextId?.let { engineManager.clearContainer(it) }
            }
            if (remaining.none { it.isIncognito }) {
                sharedIncognitoContext = null
                fingerprintShield.resetSession()
            }
        }
        tab.close()
        schedulePersist()
    }

    private fun selectAfterClose(tab: BrowserTab, remaining: List<BrowserTab>) {
        if (_activeTabId.value == tab.id) {
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
    }

    data class ClosedTab(
        val tab: BrowserTab,
        val index: Int,
        val wasActive: Boolean,
        val replacementId: String?
    )

    private val _recentlyClosed = MutableStateFlow<ClosedTab?>(null)
    val recentlyClosed: StateFlow<ClosedTab?> = _recentlyClosed.asStateFlow()
    private var undoJob: Job? = null

    fun closeTabWithUndo(id: String) {
        commitPendingClose()
        val tab = tab(id) ?: return
        val index = _tabs.value.indexOfFirst { it.id == id }
        val wasActive = _activeTabId.value == id
        val remaining = _tabs.value.filter { it.id != id }
        _tabs.value = remaining
        if (tab.session.isOpen) runCatching { tab.session.setActive(false) }
        selectAfterClose(tab, remaining)
        val replacement = if (remaining.isEmpty()) openTab(incognito = false).id else null
        schedulePersist()
        _recentlyClosed.value = ClosedTab(tab, index, wasActive, replacement)
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(UNDO_WINDOW_MS)
            commitPendingClose()
        }
    }

    fun undoClose(): Boolean {
        val closed = _recentlyClosed.value ?: return false
        undoJob?.cancel()
        undoJob = null
        _recentlyClosed.value = null
        val list = _tabs.value.toMutableList()
        list.add(closed.index.coerceIn(0, list.size), closed.tab)
        _tabs.value = list
        if (closed.wasActive || activeTab == null) {
            activate(closed.tab)
            resumeIfEvicted(closed.tab)
        }
        closed.replacementId?.takeIf { it != closed.tab.id }?.let { rid ->
            val extra = tab(rid)
            if (extra != null && _tabs.value.size > 1 && (extra.url.value.isBlank() || extra.url.value.startsWith("about:"))) {
                _tabs.value = _tabs.value.filter { it.id != rid }
                extra.close()
            }
        }
        schedulePersist()
        return true
    }

    fun dismissUndo() {
        commitPendingClose()
    }

    private fun commitPendingClose() {
        val closed = _recentlyClosed.value ?: return
        undoJob?.cancel()
        undoJob = null
        _recentlyClosed.value = null
        releaseClosed(closed.tab)
    }

    fun closeAllTabs(incognito: Boolean) {
        _tabs.value.filter { it.isIncognito == incognito }.forEach { closeTab(it.id) }
    }

    fun setIncognitoMode(enabled: Boolean) {
        _isIncognitoMode.value = enabled
        val candidate = _tabs.value.lastOrNull { it.isIncognito == enabled }
        if (candidate == null) {
            openTab(incognito = enabled)
        } else {
            selectTab(candidate.id)
        }
    }

    fun wipeIncognito() {
        commitPendingClose()
        promptBridge.dismissAll()
        _incognitoLocked.value = false
        autoWipeJob?.cancel()
        val incognitoTabs = _tabs.value.filter { it.isIncognito }
        superIncognito.endSession()
        scope.launch(Dispatchers.IO) {
            incognitoStore.clear()
            superIncognito.clearSaved()
        }
        if (incognitoTabs.isEmpty()) {
            fingerprintShield.resetSession()
            return
        }
        incognitoTabs.mapNotNull { it.contextId }.distinct().forEach { engineManager.clearContainer(it) }
        incognitoTabs.forEach { it.close() }
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

    fun load(tab: BrowserTab, input: String) {
        val url = UrlUtils.toUrlOrSearch(input, settings.searchEngine)
        tab.updateInterstitial(null)
        if (settings.fastConnections && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))) {
            FableUnifiedEngine.preconnectOriginAsync(url)
            UrlUtils.origin(url)?.let { engineManager.speculativeConnect(it) }
        }
        wireHybridCallbacks(tab)
        val manual = tab.manualHybridOverride ?: loadTabHybridState(url)?.also { tab.manualHybridOverride = it }
        if (manual != null) {
            tab.updateHybridBlink(manual)
        } else if (settings.hybridGeckoBlinkBeta) {
            val wantHybrid = if (settings.hybridAutoForGames) {
                HybridEngineBridge.isHeavy3dGameUrl(url, settings.hybridCustomDomains)
            } else {
                true
            }
            if (tab.isHybridBlink.value != wantHybrid) {
                tab.updateHybridBlink(wantHybrid)
            }
        } else if (tab.isHybridBlink.value) {
            tab.updateHybridBlink(false)
        }
        if (tab.isHybridBlink.value) {
            tab.lastLoadedBlinkUrl = ""
            tab.updateUrl(url)
            if (tab.session.isOpen && settings.hybridHibernateGecko) {
                runCatching {
                    tab.session.stop()
                    tab.session.setActive(false)
                    setPriority(tab, high = false)
                }
            }
            tab.hybridController?.loadUrl(url)
            touchIncognitoActivity()
            return
        }
        ensureLive(tab)
        whenSuperReady(tab, url) { tab.session.load(GeckoSession.Loader().uri(url)) }
        touchIncognitoActivity()
    }

    private fun whenSuperReady(tab: BrowserTab, pendingUrl: String?, action: () -> Unit) {
        if (!tab.isSuper) {
            action()
            return
        }
        val id = superIncognito.identity.value?.id
        if (id == null || proxyConfig.isSuperApplied(id)) {
            if (id == null) superIncognito.current(settings)
            val fresh = superIncognito.identity.value?.id
            if (fresh != null && !proxyConfig.isSuperApplied(fresh)) {
                waitForSuper(tab, fresh, pendingUrl, action)
            } else {
                action()
            }
            return
        }
        waitForSuper(tab, id, pendingUrl, action)
    }

    private fun waitForSuper(tab: BrowserTab, id: String, pendingUrl: String?, action: () -> Unit) {
        proxyConfig.push()
        scope.launch {
            val ok = proxyConfig.awaitSuperApplied(id, SUPER_WAIT_MS)
            if (_tabs.value.none { it.id == tab.id }) return@launch
            val proxyWanted = settings.superProxyEnabled && settings.superProxyHost.isNotBlank()
            if (ok || !proxyWanted) {
                action()
            } else {
                tab.updateInterstitial(
                    Interstitial.Network(
                        pendingUrl ?: tab.url.value,
                        "Super incognito could not start its protected connection yet, so nothing was loaded. Tap Try again."
                    )
                )
            }
        }
    }

    fun loadInActive(input: String) {
        val tab = activeTab ?: openTab(incognito = _isIncognitoMode.value)
        load(tab, input)
    }

    fun reload() = activeTab?.let { tab ->
        val pending = (tab.interstitial.value as? Interstitial.Network)?.url
        if (tab.isSuper && pending != null && pending.isNotBlank() && pending != "about:blank") {
            load(tab, pending)
            return@let
        }
        tab.updateInterstitial(null)
        if (tab.isHybridBlink.value) {
            tab.hybridController?.reload()
            return@let
        }
        if (tab.isEvicted || !tab.session.isOpen) {
            if (!tab.isEvicted) tab.markUnloaded()
            resumeIfEvicted(tab)
        } else {
            tab.session.reload()
        }
    }

    fun stop() {
        val tab = activeTab ?: return
        if (tab.isHybridBlink.value) tab.hybridController?.stopLoading()
        runCatching { tab.session.stop() }
    }

    fun exitFullScreen() {
        _manualFullScreen.value = false
        activeTab?.hybridController?.exitFullScreen()
        activeTab?.session?.takeIf { it.isOpen }?.let { session ->
            runCatching { session.exitFullScreen() }
        }
    }

    fun enterFullScreen() {
        if (_manualFullScreen.value) return
        _manualFullScreen.value = true
        onGameModeStarted()
    }

    private fun onGameModeStarted() {
        if (!settings.gameMode) return
        NativeFastEngine.setGameBoost(true)
        scope.launch(Dispatchers.Main) {
            runCatching { evictBackgroundTabs() }
        }
    }

    fun goBack(): Boolean {
        val tab = activeTab ?: return false
        if (tab.interstitial.value != null) {
            tab.updateInterstitial(null)
            return true
        }
        if (!tab.canGoBack.value) return false
        if (tab.isHybridBlink.value) {
            tab.hybridController?.goBack()
            return true
        }
        tab.session.goBack()
        return true
    }

    fun goForward() {
        val tab = activeTab?.takeIf { it.canGoForward.value } ?: return
        if (tab.isHybridBlink.value) tab.hybridController?.goForward()
        else tab.session.goForward()
    }

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
        if (tab.isHybridBlink.value) tab.hybridController?.setDesktopMode(desktop)
        else tab.session.reload()
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

    fun savePageCode(mode: String = "all"): Boolean {
        val tab = activeTab ?: return false
        val url = tab.url.value
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) return false
        if (tab.isHybridBlink.value) {
            val hc = tab.hybridController ?: return false
            val reqId = proxyConfig.newHybridArchiveRequest() ?: return true
            return hc.savePageCode(mode, reqId)
        }
        return proxyConfig.savePageCode(url, mode)
    }

    fun findInPage(query: String, forward: Boolean = true) {
        val tab = activeTab ?: return
        if (query.isBlank()) {
            clearFind()
            return
        }
        if (tab.isHybridBlink.value) {
            tab.hybridController?.findInPage(query, forward)
            return
        }
        val flags = if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS
        tab.session.finder.find(query, flags).accept { result ->
            result?.let { tab.updateFindMatch(FindMatch(it.current, it.total)) }
        }
    }

    fun clearFind() {
        val tab = activeTab ?: return
        if (tab.isHybridBlink.value) {
            tab.hybridController?.clearFind()
            return
        }
        tab.session.finder.clear()
        tab.updateFindMatch(null)
    }

    fun evictBackgroundTabs() {
        val activeId = _activeTabId.value
        _tabs.value
            .filter {
                it.id != activeId && !it.isPinned.value && !it.isMediaPlaying &&
                    !(it.isHybridBlink.value && it.retainedBlinkView != null)
            }
            .forEach { unload(it) }
    }

    fun onTrimMemory(level: Int) {
        if (level == TRIM_UI_HIDDEN) return
        val gaming = settings.gameMode && (_manualFullScreen.value || activeTab?.isFullScreen?.value == true)
        if (gaming && level >= TRIM_RUNNING_LOW) {
            evictBackgroundTabs()
            return
        }
        val policy = memoryPolicy()
        when {
            level >= TRIM_COMPLETE -> evictBackgroundTabs()
            level >= TRIM_BACKGROUND -> if (policy.unloadOnBackgroundTrim) evictBackgroundTabs()
            level >= TRIM_RUNNING_CRITICAL -> evictBackgroundTabs()
            level >= TRIM_RUNNING_LOW -> if (policy.keepLoaded >= 0) evictBackgroundTabs()
        }
    }

    private fun restartIdleSweep() {
        idleSweepJob?.cancel()
        val idleMs = memoryPolicy().idleUnloadMs
        if (idleMs <= 0) return
        idleSweepJob = scope.launch {
            while (true) {
                delay((idleMs / 3).coerceIn(10_000L, 60_000L))
                val now = System.currentTimeMillis()
                val activeId = _activeTabId.value
                _tabs.value
                    .filter {
                        it.id != activeId && !it.isEvicted && !it.isPinned.value &&
                            !it.isMediaPlaying && now - it.lastAccessed >= idleMs
                    }
                    .forEach { unload(it) }
            }
        }
    }

    private fun enforceTabBudget() {
        val keep = memoryPolicy().keepLoaded
        if (keep < 0) return
        val activeId = _activeTabId.value
        _tabs.value
            .filter {
                it.id != activeId && !it.isEvicted && !it.isPinned.value && !it.isMediaPlaying &&
                    !(it.isHybridBlink.value && it.retainedBlinkView != null)
            }
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
            val state = tab.savedState
            val session = freshSession(tab)
            if (state != null) whenSuperReady(tab, null) { session.restoreState(state) }
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
            state != null -> whenSuperReady(tab, url) { session.restoreState(state) }
            url != null && url != "about:blank" -> whenSuperReady(tab, url) { session.load(GeckoSession.Loader().uri(url)) }
        }
    }

    private fun freshSession(tab: BrowserTab): GeckoSession {
        val session = when {
            tab.isSuper -> sessionFactory.createSuperSession(settings, tab.contextId ?: sessionFactory.newContextId(), superIncognito.current(settings))
            tab.isIncognito -> sessionFactory.createIncognitoSession(settings, tab.contextId ?: sessionFactory.newContextId())
            else -> sessionFactory.createNormalSession(settings)
        }
        if (tab.isDesktopMode.value && !tab.isSuper) applyDesktopMode(session, true)
        tab.replaceSession(session)
        delegateFactory.attach(tab, scope, { settings }, callbacks)
        val isAct = tab.id == _activeTabId.value
        session.setActive(isAct)
        engineManager.setTabActive(session, isAct)
        setPriority(tab, high = isAct)
        return session
    }

    private fun setPriority(tab: BrowserTab, high: Boolean) {
        val session = tab.session
        if (!session.isOpen) return
        val hint = if (high || tab.isMediaPlaying) {
            GeckoSession.PRIORITY_HIGH
        } else {
            GeckoSession.PRIORITY_DEFAULT
        }
        runCatching { session.setPriorityHint(hint) }
    }

    private fun restartAutoWipeTimer() {
        autoWipeJob?.cancel()
        val minutes = settings.incognitoAutoWipe.minutes
        if (minutes <= 0) return
        autoWipeJob = scope.launch {
            delay(minutes * 60_000L)
            if (_tabs.value.any { it.isIncognito }) wipeIncognito()
        }
    }

    private fun touchIncognitoActivity() {
        if (_isIncognitoMode.value) restartAutoWipeTimer()
    }

    fun dismissContextMenu() { _contextMenu.value = null }

    private val callbacks = object : TabCallbacks {
        override fun onPageChanged(tab: BrowserTab) {
            if (!tab.isIncognito || settings.keepIncognitoTabs || (tab.isSuper && settings.keepSuperTabs)) schedulePersist()
        }

        override fun onNewSessionRequested(tab: BrowserTab, uri: String): GeckoSession? {
            val child = createTab(tab.isIncognito, open = false, inheritedContextId = tab.contextId, superMode = tab.isSuper)
            if (uri.isNotBlank()) child.updateUrl(uri)
            activate(child)
            _toolbarVisible.value = true
            scope.launch(Dispatchers.Main) { enforceTabBudget() }
            return child.session
        }

        override fun onSessionLost(tab: BrowserTab, crashed: Boolean) {
            if (tab.isEvicted) return
            val active = tab.id == _activeTabId.value
            tab.markUnloaded()
            if (!active) return
            runCatching { evictBackgroundTabs() }
            if (crashed) return
            val now = android.os.SystemClock.elapsedRealtime()
            val looping = tab.id == lastKilledTabId && now - lastKilledAt < KILL_LOOP_WINDOW_MS
            lastKilledTabId = tab.id
            lastKilledAt = now
            if (looping) {
                tab.updateInterstitial(
                    Interstitial.Network(
                        tab.url.value,
                        "Android stopped this page because it needed more memory than was free. " +
                            "Close other apps and tap Retry."
                    )
                )
            } else {
                resumeIfEvicted(tab)
            }
        }

        override fun onFullScreenChanged(tab: BrowserTab, fullScreen: Boolean) {
            if (fullScreen && tab.id == _activeTabId.value) onGameModeStarted()
        }

        override fun onMediaStateChanged(tab: BrowserTab, playing: Boolean) {
            tab.isMediaPlaying = playing
            setPriority(tab, high = tab.id == _activeTabId.value)
            if (!playing) scope.launch(Dispatchers.Main) { enforceTabBudget() }
        }

        override fun onCloseRequested(tab: BrowserTab) = closeTab(tab.id)

        override fun onContextMenu(target: ContextMenuTarget) {
            val owner = tab(target.tabId)
            if (_manualFullScreen.value || owner?.isFullScreen?.value == true) return
            _contextMenu.value = target
        }

        override fun onScroll(tab: BrowserTab, scrollY: Int) {
            val delta = scrollY - lastScrollY
            lastScrollY = scrollY
            if (!settings.hideToolbarOnScroll) {
                if (!_toolbarVisible.value) _toolbarVisible.value = true
                return
            }
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

private class PendingIncognito(
    val url: String,
    val title: String,
    val state: GeckoSession.SessionState?,
    val active: Boolean,
    val pinned: Boolean,
    val superTab: Boolean
)
