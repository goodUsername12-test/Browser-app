@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.fable.browser.ui.browser

import androidx.activity.compose.BackHandler
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.IntOffset
import com.fable.browser.engine.BrowserTab
import com.fable.browser.engine.RecAudioSource
import com.fable.browser.engine.ScreenRecorderManager
import com.fable.browser.mcp.McpSessionManager
import com.fable.browser.util.UrlUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecorderSettings: () -> Unit = {}
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val incognito by viewModel.isIncognito.collectAsStateWithLifecycle()
    val toolbarVisible by viewModel.toolbarVisible.collectAsStateWithLifecycle()
    val locked by viewModel.incognitoLocked.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val omniboxText = viewModel.omniboxValue
    val omniboxFocused by viewModel.omniboxFocused.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val findQuery by viewModel.findQuery.collectAsStateWithLifecycle()
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val contextTarget by viewModel.contextMenu.collectAsStateWithLifecycle()
    val showDisclosure by viewModel.showDisclosure.collectAsStateWithLifecycle()
    val topSites by viewModel.topSites.collectAsStateWithLifecycle()
    val captureHandle = remember { GeckoCaptureHandle() }
    captureHandle.thumbnailWidth = if (settings.memoryMode == com.fable.browser.data.model.MemoryMode.LOW) 240 else 360

    val tab: BrowserTab? = remember(tabs, activeTabId) { tabs.firstOrNull { it.id == activeTabId } }
    DisposableEffect(viewModel, tab, captureHandle) {
        val controller = object : McpSessionManager.BrowserController {
            override fun currentUrl(): String = tab?.url?.value ?: "about:blank"
            override fun currentTitle(): String = tab?.title?.value.orEmpty()
            override fun navigate(url: String) { viewModel.load(url) }
            override fun reload() { viewModel.reload() }
            override fun goBack() { viewModel.goBack() }
            override fun goForward() { viewModel.goForward() }
            override fun openNewTab(url: String?, incognito: Boolean) { viewModel.openTab(url, incognito) }
            override fun closeActiveTab() { tab?.id?.let { viewModel.closeTab(it) } }
            override fun listTabsSummary(): String =
                viewModel.tabs.value.joinToString(" | ") { "${it.id.take(4)}:${it.url.value.take(45)}" }
            override fun activeBlinkWebView(): android.webkit.WebView? =
                captureHandle.blinkView ?: tab?.retainedBlinkView
            override fun activeRootView(): android.view.View? =
                captureHandle.blinkView ?: captureHandle.view
        }
        McpSessionManager.bindBrowserController(controller)
        onDispose {
            McpSessionManager.bindBrowserController(null)
        }
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val isScreenRecording by ScreenRecorderManager.isRecording.collectAsStateWithLifecycle()
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    val memState by viewModel.memoryEngineState.collectAsStateWithLifecycle()
    LaunchedEffect(settings.memoryEngineEnabled) {
        viewModel.memoryEngineManager.setEngineActive(settings.memoryEngineEnabled)
    }
    val perfSnap by viewModel.perfSnapshot.collectAsStateWithLifecycle()
    val recElapsedSec by ScreenRecorderManager.elapsedSeconds.collectAsStateWithLifecycle()
    val lastButtonPress by ScreenRecorderManager.lastButtonPress.collectAsStateWithLifecycle()

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        ScreenRecorderManager.startWithProjectionResult(
            context = context,
            resultCode = res.resultCode,
            data = res.data,
            config = settings.toRecorderConfig()
        ) { msg -> viewModel.toast(msg) }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val cfg = settings.toRecorderConfig()
        if (cfg.zeroLagBlinkMode && cfg.audioSource != RecAudioSource.SYSTEM && cfg.audioSource != RecAudioSource.SYSTEM_AND_MIC) {
            ScreenRecorderManager.startDirectWindowRecording(context, cfg) { msg -> viewModel.toast(msg) }
        } else {
            val intent = ScreenRecorderManager.createCaptureIntent(context)
            if (intent != null) projectionLauncher.launch(intent)
            else ScreenRecorderManager.startDirectWindowRecording(context, cfg) { msg -> viewModel.toast(msg) }
        }
    }

    val toggleScreenRecording = {
        if (isScreenRecording) {
            ScreenRecorderManager.stopRecording { msg -> if (!msg.isNullOrBlank()) viewModel.toast("Saved: $msg") }
        } else {
            val cfg = settings.toRecorderConfig()
            val needMic = cfg.audioSource == RecAudioSource.MIC || cfg.audioSource == RecAudioSource.SYSTEM_AND_MIC
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (needMic && !hasMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (cfg.zeroLagBlinkMode && cfg.audioSource != RecAudioSource.SYSTEM && cfg.audioSource != RecAudioSource.SYSTEM_AND_MIC) {
                ScreenRecorderManager.startDirectWindowRecording(context, cfg) { msg -> viewModel.toast(msg) }
            } else {
                val intent = ScreenRecorderManager.createCaptureIntent(context)
                if (intent != null) projectionLauncher.launch(intent)
                else ScreenRecorderManager.startDirectWindowRecording(context, cfg) { msg -> viewModel.toast(msg) }
            }
        }
    }

    val url = tab?.url?.collectAsStateWithLifecycle()?.value ?: "about:blank"
    val title = tab?.title?.collectAsStateWithLifecycle()?.value ?: ""
    val loading = tab?.isLoading?.collectAsStateWithLifecycle()?.value ?: false
    val canGoBack = tab?.canGoBack?.collectAsStateWithLifecycle()?.value ?: false
    val canGoForward = tab?.canGoForward?.collectAsStateWithLifecycle()?.value ?: false
    val isSecure = tab?.isSecure?.collectAsStateWithLifecycle()?.value ?: true
    val blockedCount = tab?.blockedCount?.collectAsStateWithLifecycle()?.value ?: 0
    val desktopMode = tab?.isDesktopMode?.collectAsStateWithLifecycle()?.value ?: false
    val hybridBlinkMode = tab?.isHybridBlink?.collectAsStateWithLifecycle()?.value ?: false
    val readerMode = tab?.isReaderMode?.collectAsStateWithLifecycle()?.value ?: false
    val webFullScreen = tab?.isFullScreen?.collectAsStateWithLifecycle()?.value ?: false
    val manualFullScreen by viewModel.manualFullScreen.collectAsStateWithLifecycle()
    val fullScreen = webFullScreen || manualFullScreen
    val fullScreenChangedAt = remember(fullScreen) { SystemClock.uptimeMillis() }
    val instantBars = true
    val interstitial = tab?.interstitial?.collectAsStateWithLifecycle()?.value
    val findMatch = tab?.findMatch?.collectAsStateWithLifecycle()?.value
    val keepLoaded = tab?.isPinned?.collectAsStateWithLifecycle()?.value ?: false
    val bookmarkFlow = remember(url) { viewModel.isBookmarked(url) }
    val bookmarked by bookmarkFlow.collectAsStateWithLifecycle(initialValue = false)

    BackHandler(enabled = fullScreen || omniboxFocused || findQuery != null || canGoBack || interstitial != null) {
        when {
            fullScreen -> viewModel.exitFullScreen()
            omniboxFocused -> viewModel.onOmniboxFocus(false)
            findQuery != null -> viewModel.closeFind()
            else -> viewModel.goBack()
        }
    }

    val toolbarAtTop = settings.toolbarAtTop
    val showToolbar = !fullScreen && (toolbarVisible || omniboxFocused || findQuery != null)
    val imeTarget = WindowInsets.imeAnimationTarget
    val pageIme = if (omniboxFocused) Modifier else Modifier.windowInsetsPadding(imeTarget)
    val pageInsets = when {
        fullScreen -> Modifier
        toolbarAtTop -> (if (showToolbar) Modifier else Modifier.statusBarsPadding())
            .navigationBarsPadding()
            .then(pageIme)
        else -> Modifier.statusBarsPadding()
            .then(if (showToolbar) Modifier else Modifier.navigationBarsPadding())
    }

    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val modeTabs = remember(tabs, incognito) { tabs.filter { it.isIncognito == incognito } }
    val openTabsAction = {
        val currentId = tab?.id
        captureHandle.capture { bitmap ->
            if (currentId != null && bitmap != null) {
                viewModel.setThumbnail(currentId, bitmap)
            }
            onOpenTabs()
        }
    }

    val toolbar: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .then(
                    if (toolbarAtTop) Modifier.statusBarsPadding()
                    else Modifier.navigationBarsPadding().windowInsetsPadding(imeTarget)
                )
        ) {
            if (!toolbarAtTop) TabProgress(tab = tab, loading = loading)
            if (findQuery != null) {
                FindBar(
                    query = findQuery.orEmpty(),
                    match = findMatch,
                    onQueryChange = viewModel::onFindQuery,
                    onNext = { viewModel.findNext(true) },
                    onPrevious = { viewModel.findNext(false) },
                    onClose = viewModel::closeFind
                )
            }
            if (isTablet) {
                if (!omniboxFocused) {
                    TabletTabBar(
                        tabs = modeTabs,
                        activeTabId = activeTabId,
                        incognito = incognito,
                        onSelect = viewModel::selectTab,
                        onClose = viewModel::closeTab,
                        onNewTab = { viewModel.openTab(incognito = incognito) },
                        onNewSuperTab = { viewModel.openSuperTab() }
                    )
                }
                TabletToolbar(
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    loading = loading,
                    tabCount = modeTabs.size,
                    incognito = incognito,
                    onBack = { viewModel.goBack() },
                    onForward = viewModel::goForward,
                    onReload = { viewModel.reload() },
                    onStop = { viewModel.stop() },
                    onFullScreen = viewModel::enterFullScreen,
                    onTabs = openTabsAction,
                    onToggleIncognito = { viewModel.setIncognitoMode(!incognito) },
                    onPanic = viewModel::panicWipe,
                    onMenu = { menuOpen = true },
                    omnibox = { mod ->
                        Omnibox(
                            url = url,
                            text = omniboxText,
                            focused = omniboxFocused,
                            isSecure = isSecure && !UrlUtils.isInsecure(url),
                            incognito = incognito,
                            blockedCount = blockedCount,
                            engineLabel = settings.searchEngine.label,
                            onTextChange = viewModel::onOmniboxChange,
                            onFocusChange = viewModel::onOmniboxFocus,
                            onSubmit = { viewModel.submit() },
                            onStartEditing = viewModel::startEditing,
                            onShieldClick = onOpenSettings,
                            modifier = mod
                        )
                    }
                )
            } else {
                Omnibox(
                    url = url,
                    text = omniboxText,
                    focused = omniboxFocused,
                    isSecure = isSecure && !UrlUtils.isInsecure(url),
                    incognito = incognito,
                    blockedCount = blockedCount,
                    engineLabel = settings.searchEngine.label,
                    onTextChange = viewModel::onOmniboxChange,
                    onFocusChange = viewModel::onOmniboxFocus,
                    onSubmit = { viewModel.submit() },
                    onStartEditing = viewModel::startEditing,
                    onShieldClick = onOpenSettings
                )
                BottomToolbar(
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    loading = loading,
                    tabCount = modeTabs.size,
                    incognito = incognito,
                    onBack = { viewModel.goBack() },
                    onForward = viewModel::goForward,
                    onReload = { viewModel.reload() },
                    onStop = { viewModel.stop() },
                    onFullScreen = viewModel::enterFullScreen,
                    onTabs = openTabsAction,
                    onToggleIncognito = { viewModel.setIncognitoMode(!incognito) },
                    onPanic = viewModel::panicWipe,
                    onMenu = { menuOpen = true }
                )
            }
            if (toolbarAtTop) TabProgress(tab = tab, loading = loading)
        }
    }

    val recentlyClosed by viewModel.recentlyClosed.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            if (toolbarAtTop) {
                AnimatedVisibility(
                    visible = showToolbar,
                    enter = if (instantBars) EnterTransition.None else expandVertically(expandFrom = Alignment.Top),
                    exit = if (instantBars) ExitTransition.None else shrinkVertically(shrinkTowards = Alignment.Top)
                ) { toolbar() }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(pageInsets)
            ) {
                LaunchedEffect(
                    tab?.id,
                    settings.batterySaverMaster,
                    settings.batteryGameMaxFps,
                    settings.batteryGameRenderScale,
                    settings.batteryIdleGameFps,
                    settings.batteryIdleTimeoutSec
                ) {
                    val wv = captureHandle.blinkView ?: tab?.retainedBlinkView
                    if (wv != null && tab != null) {
                        val pureGame = com.fable.browser.engine.HybridEngineBridge.isPure3dGameUrl(
                            tab.url.value,
                            settings.hybridCustomDomains
                        )
                        val plan = com.fable.browser.engine.FableBatteryGovernor.evaluatePlan(settings, pureGame)
                        val timeoutMs = settings.batteryIdleTimeoutSec.coerceIn(3, 60) * 1000
                        wv.evaluateJavascript(
                            "if(window.__fableSetPowerPlan)window.__fableSetPowerPlan(${plan.targetGameFps},${settings.batteryIdleGameFps},$timeoutMs,${plan.renderScalePercent});",
                            null
                        )
                    }
                }
                if (tab != null) {
                    GeckoViewHost(
                        tab = tab,
                        captureHandle = captureHandle,
                        showRecorderTouches = settings.recShowTouches,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (url == "about:blank" && !incognito && !loading && !fullScreen && interstitial == null) {
                        NewTabPage(
                            topSites = topSites,
                            onSearch = { viewModel.startEditing() },
                            onOpen = { viewModel.load(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (interstitial != null && !(locked && incognito)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        InterstitialScreen(
                            interstitial = interstitial,
                            onProceed = { viewModel.proceedOverHttp(it) },
                            onRetry = { viewModel.reload() },
                            onBack = { viewModel.goBack() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (settings.batteryOledDimPct > 0) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = (settings.batteryOledDimPct.coerceIn(0, 60) / 100f)))
                    )
                }
                if (locked && incognito) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        IncognitoLockScreen(
                            onUnlock = { viewModel.unlockIncognito() },
                            onLeaveIncognito = { viewModel.setIncognitoMode(false) }
                        )
                    }
                }
            }

            if (!toolbarAtTop) {
                AnimatedVisibility(
                    visible = showToolbar,
                    enter = if (instantBars) EnterTransition.None else slideInVertically { it },
                    exit = if (instantBars) ExitTransition.None else slideOutVertically { it }
                ) { toolbar() }
            }
        }

        if (omniboxFocused && suggestions.isNotEmpty()) {
            SuggestionList(
                suggestions = suggestions,
                onPick = { viewModel.submit(it) },
                modifier = if (toolbarAtTop) {
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp)
                        .padding(horizontal = 8.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .windowInsetsPadding(imeTarget)
                        .padding(bottom = 132.dp)
                        .padding(horizontal = 8.dp)
                }
            )
        }

        if (fullScreen) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0x78111827),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .clickable { viewModel.exitFullScreen() }
            ) {
                Text(
                    text = "✕ Exit Fullscreen",
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }


        if (isScreenRecording) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCCDC2626),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = if (toolbarAtTop && showToolbar) 68.dp else 8.dp, end = 12.dp)
                    .clickable { ScreenRecorderManager.stopRecording { msg -> if (!msg.isNullOrBlank()) viewModel.toast("Saved: $msg") } }
            ) {
                val mm = recElapsedSec / 60
                val ss = recElapsedSec % 60
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "● REC %02d:%02d".format(mm, ss),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (isScreenRecording && settings.recShowButtonPresses && !lastButtonPress.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xBB1E293B),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 84.dp)
            ) {
                Text(
                    text = lastButtonPress.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        McpLiveBrowserHud(
            toolbarAtTop = toolbarAtTop,
            showToolbar = showToolbar,
            onOpenMcpSettings = onOpenSettings,
            modifier = Modifier.fillMaxSize()
        )

        if (captureState.phase == com.fable.browser.engine.CapturePhase.CAPTURING ||
            captureState.phase == com.fable.browser.engine.CapturePhase.PAUSED ||
            captureState.phase == com.fable.browser.engine.CapturePhase.FINALIZING
        ) {
            CaptureControlHud(
                state = captureState,
                onPause = viewModel::pauseCapture,
                onResume = viewModel::resumeCapture,
                onStopAndSave = viewModel::stopAndSaveCapture,
                onCancel = viewModel::cancelCapture,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = if (toolbarAtTop || !showToolbar) 14.dp else 68.dp)
            )
        }

        if (settings.perfHudEnabled) {
            PerfOverlayHud(
                snap = perfSnap,
                onClick = {
                    val line = com.fable.browser.engine.PerformanceTelemetry.dumpSummary("hud_tap")
                    viewModel.toast(line.take(120))
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = if (toolbarAtTop && showToolbar) 68.dp else 8.dp, start = 8.dp)
            )
        }

        if (settings.timeScaleEnabled && settings.timeScaleShowHud) {
            com.fable.browser.ui.components.DraggableWebsiteSpeedPanel(
                tenths = settings.timeScaleMultiplierTenths,
                scalePct = settings.timeScalePanelScalePct,
                posX = settings.timeScalePanelPosX,
                posY = settings.timeScalePanelPosY,
                expanded = settings.timeScalePanelExpanded,
                topSafePadding = if (toolbarAtTop && showToolbar) 64.dp else 8.dp,
                bottomSafePadding = if (!toolbarAtTop && showToolbar) 64.dp else 8.dp,
                onLiveTenthsChange = viewModel::previewTimeScaleLive,
                onCommitTenths = viewModel::setTimeScaleTenths,
                onPositionChangeFinished = viewModel::setTimeScalePanelPosition,
                onToggleExpanded = { viewModel.setTimeScalePanelExpanded(!settings.timeScalePanelExpanded) },
                onTurnOff = { viewModel.setTimeScaleEnabled(false) },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            )
        }

        if (settings.memoryEngineEnabled && settings.memoryEngineShowPanel) {
            com.fable.browser.ui.components.DraggableMemoryEnginePanel(
                state = memState,
                scalePct = settings.memoryEnginePanelScalePct,
                posX = settings.memoryEnginePanelPosX,
                posY = settings.memoryEnginePanelPosY,
                expanded = settings.memoryEnginePanelExpanded,
                topSafePadding = if (toolbarAtTop && showToolbar) 64.dp else 8.dp,
                bottomSafePadding = if (!toolbarAtTop && showToolbar) 64.dp else 8.dp,
                onSelectScope = viewModel.memoryEngineManager::setScope,
                onSelectDataType = viewModel.memoryEngineManager::setDataType,
                onSelectScanType = viewModel.memoryEngineManager::setScanType,
                onFirstScan = viewModel.memoryEngineManager::firstScan,
                onNextScan = viewModel.memoryEngineManager::nextScan,
                onUndoScan = viewModel.memoryEngineManager::undoScan,
                onResetScan = viewModel.memoryEngineManager::resetScan,
                onAddWatch = { item -> viewModel.memoryEngineManager.addToWatch(item) },
                onSetWatchValue = viewModel.memoryEngineManager::setWatchValue,
                onRestoreWatchOriginal = viewModel.memoryEngineManager::restoreWatchOriginal,
                onToggleWatchFreeze = viewModel.memoryEngineManager::toggleWatchFreeze,
                onRemoveWatch = viewModel.memoryEngineManager::removeWatch,
                onUnfreezeAll = viewModel.memoryEngineManager::unfreezeAll,
                onInspectHex = viewModel.memoryEngineManager::inspectHex,
                onRefreshRegions = viewModel.memoryEngineManager::refreshRegions,
                onPositionChangeFinished = viewModel::setMemoryEnginePanelPosition,
                onToggleExpanded = { viewModel.setMemoryEnginePanelExpanded(!settings.memoryEnginePanelExpanded) },
                onTurnOff = { viewModel.setMemoryEngineEnabled(false) },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            )
        }

        com.fable.browser.ui.components.UndoCloseBar(
            closed = recentlyClosed,
            onUndo = { viewModel.undoClose() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (toolbarAtTop || !showToolbar) 8.dp else 60.dp)
        )
    }

    if (menuOpen && tab != null) {
        MenuSheet(
            isBookmarked = bookmarked,
            desktopMode = desktopMode,
            hybridBlinkMode = hybridBlinkMode,
            keepLoaded = keepLoaded,
            onToggleKeepLoaded = viewModel::toggleKeepLoaded,
            readerMode = readerMode,
            incognito = incognito,
            textZoom = settings.textZoomPercent,
            onDismiss = { menuOpen = false },
            onToggleBookmark = viewModel::toggleBookmark,
            onBookmarks = onOpenBookmarks,
            onHistory = onOpenHistory,
            onDownloads = onOpenDownloads,
            onSettings = onOpenSettings,
            onFind = viewModel::openFind,
            onToggleDesktop = viewModel::toggleDesktopMode,
            onToggleReader = viewModel::toggleReaderMode,
            onShare = viewModel::sharePage,
            onCopyLink = {
                clipboard.setText(AnnotatedString(url))
                viewModel.toast("Link copied")
            },
            onAddToHomeScreen = viewModel::addToHomeScreen,
            onSaveAsPdf = viewModel::saveAsPdf,
            onTextZoom = viewModel::setTextZoom,
            onFingerprintTest = viewModel::openFingerprintTest,
            onNewSuperTab = { viewModel.openSuperTab() },
            onSavePageCode = viewModel::savePageCode,
            onSaveMemorySnapshot = viewModel::saveMemorySnapshot,
            onToggleHybridBlink = viewModel::toggleHybridBlinkMode,
            onOpenBlinkGaming = {
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    com.fable.browser.BlinkGamingActivity.start(context, url)
                }
            },
            isScreenRecording = isScreenRecording,
            onToggleScreenRecording = toggleScreenRecording,
            onOpenRecorderSettings = onOpenRecorderSettings,
            timeScaleEnabled = settings.timeScaleEnabled,
            timeScaleRateLabel = com.fable.browser.engine.WebsiteSpeedEngine.formatTenths(settings.timeScaleMultiplierTenths),
            onToggleTimeScale = { viewModel.setTimeScaleEnabled(!settings.timeScaleEnabled) },
            memoryEngineEnabled = settings.memoryEngineEnabled,
            onToggleMemoryEngine = { viewModel.setMemoryEngineEnabled(!settings.memoryEngineEnabled) }
        )
    }

    contextTarget?.let { target ->
        ContextMenuSheet(
            target = target,
            onDismiss = viewModel::dismissContextMenu,
            onAction = viewModel::handleContextAction,
            onCopyLink = {
                clipboard.setText(AnnotatedString(it))
                viewModel.dismissContextMenu()
                viewModel.toast("Link copied")
            }
        )
    }

    prompt?.let { PromptHost(prompt = it) }

    if (showDisclosure) {
        IncognitoDisclosure(onAcknowledge = viewModel::acknowledgeDisclosure)
    }
}

@Composable
private fun TabProgress(tab: BrowserTab?, loading: Boolean) {
    val progress = tab?.progress?.collectAsStateWithLifecycle()?.value ?: 0
    PageProgress(progress = progress, loading = loading)
}


@Composable
private fun McpLiveBrowserHud(
    toolbarAtTop: Boolean,
    showToolbar: Boolean,
    onOpenMcpSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mcpState by McpSessionManager.state.collectAsStateWithLifecycle()
    if ((!mcpState.connected && !mcpState.connecting) || !mcpState.showLiveHud) return
    var expanded by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier) {
        val click = mcpState.lastClick
        if (click != null && SystemClock.uptimeMillis() - click.timestampMs < 4_000L) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((click.x - 18f).roundToInt(), (click.y - 18f).roundToInt()) }
                    .size(36.dp)
                    .background(Color(0x5522C55E), CircleShape)
                    .border(2.dp, Color(0xFF4ADE80), CircleShape)
            )
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xDD0F172A),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(
                    top = if (toolbarAtTop && showToolbar) 68.dp else 8.dp,
                    start = 10.dp,
                    end = 10.dp
                )
        ) {
            Column(
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (mcpState.paused) {
                            "⏸ MCP PAUSED (${mcpState.remainingMinutes}m)"
                        } else if (mcpState.connecting) {
                            "◌ MCP Connecting..."
                        } else {
                            "● MCP LIVE (${mcpState.remainingMinutes}m) · ${mcpState.currentAction.take(36)}"
                        },
                        color = Color(0xFF4ADE80),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (expanded) "Hide" else "Logs",
                        color = Color(0xFF93C5FD),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (expanded) {
                    Column(Modifier.padding(top = 6.dp)) {
                        mcpState.activity.takeLast(5).asReversed().forEach { entry ->
                            Text(
                                text = entry,
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        Row(Modifier.padding(top = 6.dp)) {
                            Text(
                                text = if (mcpState.paused) "▶ Resume AI" else "⏸ Pause AI",
                                color = Color(0xFFFDE047),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clickable { McpSessionManager.togglePause() }
                                    .padding(end = 12.dp, top = 2.dp, bottom = 2.dp)
                            )
                            Text(
                                text = "⏹ Stop MCP",
                                color = Color(0xFFF87171),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clickable { McpSessionManager.disconnect("Stopped from Live HUD") }
                                    .padding(end = 12.dp, top = 2.dp, bottom = 2.dp)
                            )
                            Text(
                                text = "⚙ MCP Panel",
                                color = Color(0xFF93C5FD),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clickable { onOpenMcpSettings() }
                                    .padding(top = 2.dp, bottom = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun CaptureControlHud(
    state: com.fable.browser.engine.CaptureUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAndSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPaused = state.phase == com.fable.browser.engine.CapturePhase.PAUSED
    val isFinalizing = state.phase == com.fable.browser.engine.CapturePhase.FINALIZING
    val badgeColor = when (state.phase) {
        com.fable.browser.engine.CapturePhase.PAUSED -> Color(0xFFD97706)
        com.fable.browser.engine.CapturePhase.FINALIZING -> Color(0xFF2563EB)
        else -> Color(0xFF16A34A)
    }
    val mbText = String.format(java.util.Locale.US, "%.2f MB", state.bytesCaptured / (1024.0 * 1024.0))
    val mm = state.elapsedSec / 60
    val ss = state.elapsedSec % 60
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xEE0F172A),
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, badgeColor, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(badgeColor, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                val label = when {
                    isFinalizing -> "Saving ZIP..."
                    isPaused -> "Capture Paused"
                    state.continuous -> "Live Snapshot"
                    else -> "Capturing Page ZIP"
                }
                Text(
                    text = "$label · ${state.filesCaptured} files · $mbText · %02d:%02d".format(mm, ss),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (state.currentResource.isNotBlank() || state.statusMessage.isNotBlank()) {
                Text(
                    text = state.currentResource.ifBlank { state.statusMessage },
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (!isFinalizing) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isPaused) Color(0xFF16A34A) else Color(0xFFD97706),
                        modifier = Modifier.clickable { if (isPaused) onResume() else onPause() }
                    ) {
                        Text(
                            text = if (isPaused) "Resume" else "Pause",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF2563EB),
                        modifier = Modifier.clickable(onClick = onStopAndSave)
                    ) {
                        Text(
                            text = "Stop & Save",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF475569),
                        modifier = Modifier.clickable(onClick = onCancel)
                    ) {
                        Text(
                            text = "Cancel",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerfOverlayHud(
    snap: com.fable.browser.engine.PerfSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xCC0F172A),
        modifier = modifier
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = "${snap.fps} FPS (${snap.displayHz.toInt()}Hz) · p95 ${"%.1f".format(snap.frameP95Ms)}ms · jank ${"%.1f".format(snap.jankPercent)}%\n" +
                "boot ${snap.coldStartMs}ms · load ${snap.lastPageLoadMs}ms · ${snap.heapUsedMb}MB · ${snap.activeEngine}",
            color = Color(0xFF86EFAC),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}
