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
import com.fable.browser.engine.BrowserTab
import com.fable.browser.engine.RecAudioSource
import com.fable.browser.engine.ScreenRecorderManager
import com.fable.browser.util.UrlUtils

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
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val isScreenRecording by ScreenRecorderManager.isRecording.collectAsStateWithLifecycle()
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
            onOpenRecorderSettings = onOpenRecorderSettings
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
