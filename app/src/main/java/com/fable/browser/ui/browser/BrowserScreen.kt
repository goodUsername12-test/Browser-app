package com.fable.browser.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.engine.BrowserTab
import com.fable.browser.util.UrlUtils

/**
 * The browsing surface: engine view, omnibox, bottom toolbar, and every
 * overlay (interstitials, find bar, prompts, long-press menu, incognito lock).
 *
 * The toolbar is bottom-anchored and collapses as the page scrolls; in
 * fullscreen (video) it is removed entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val incognito by viewModel.isIncognito.collectAsStateWithLifecycle()
    val toolbarVisible by viewModel.toolbarVisible.collectAsStateWithLifecycle()
    val locked by viewModel.incognitoLocked.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val omniboxText by viewModel.omniboxText.collectAsStateWithLifecycle()
    val omniboxFocused by viewModel.omniboxFocused.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val findQuery by viewModel.findQuery.collectAsStateWithLifecycle()
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val contextTarget by viewModel.contextMenu.collectAsStateWithLifecycle()
    val showDisclosure by viewModel.showDisclosure.collectAsStateWithLifecycle()
    val topSites by viewModel.topSites.collectAsStateWithLifecycle()
    val captureHandle = remember { GeckoCaptureHandle() }

    val tab: BrowserTab? = remember(tabs, activeTabId) { tabs.firstOrNull { it.id == activeTabId } }
    val clipboard = LocalClipboardManager.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val url = tab?.url?.collectAsStateWithLifecycle()?.value ?: "about:blank"
    val title = tab?.title?.collectAsStateWithLifecycle()?.value ?: ""
    val progress = tab?.progress?.collectAsStateWithLifecycle()?.value ?: 0
    val loading = tab?.isLoading?.collectAsStateWithLifecycle()?.value ?: false
    val canGoBack = tab?.canGoBack?.collectAsStateWithLifecycle()?.value ?: false
    val canGoForward = tab?.canGoForward?.collectAsStateWithLifecycle()?.value ?: false
    val isSecure = tab?.isSecure?.collectAsStateWithLifecycle()?.value ?: true
    val blockedCount = tab?.blockedCount?.collectAsStateWithLifecycle()?.value ?: 0
    val desktopMode = tab?.isDesktopMode?.collectAsStateWithLifecycle()?.value ?: false
    val readerMode = tab?.isReaderMode?.collectAsStateWithLifecycle()?.value ?: false
    val fullScreen = tab?.isFullScreen?.collectAsStateWithLifecycle()?.value ?: false
    val interstitial = tab?.interstitial?.collectAsStateWithLifecycle()?.value
    val findMatch = tab?.findMatch?.collectAsStateWithLifecycle()?.value
    val keepLoaded = tab?.isPinned?.collectAsStateWithLifecycle()?.value ?: false
    val bookmarkFlow = remember(url) { viewModel.isBookmarked(url) }
    val bookmarked by bookmarkFlow.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(loading) { if (!loading) refreshing = false }

    // Hardware / gesture back: close overlays, then walk page history, then exit.
    BackHandler(enabled = fullScreen || omniboxFocused || findQuery != null || canGoBack || interstitial != null) {
        when {
            fullScreen -> viewModel.exitFullScreen()
            omniboxFocused -> viewModel.onOmniboxFocus(false)
            findQuery != null -> viewModel.closeFind()
            else -> viewModel.goBack()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    locked && incognito -> IncognitoLockScreen(
                        onUnlock = { viewModel.unlockIncognito() },
                        onLeaveIncognito = { viewModel.setIncognitoMode(false) }
                    )

                    interstitial != null -> InterstitialScreen(
                        interstitial = interstitial,
                        onProceed = { viewModel.proceedOverHttp(it) },
                        onRetry = { viewModel.reload() },
                        onBack = { viewModel.goBack() },
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    )

                    tab != null -> PullToRefreshBox(
                        isRefreshing = refreshing && loading,
                        onRefresh = {
                            refreshing = true
                            viewModel.reload()
                        },
                        state = rememberPullToRefreshState(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        GeckoViewHost(
                            tab = tab,
                            captureHandle = captureHandle,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (fullScreen) Modifier else Modifier.statusBarsPadding())
                        )
                        if (url == "about:blank" && !incognito && !loading && !fullScreen) {
                            NewTabPage(
                                topSites = topSites,
                                onSearch = { viewModel.startEditing() },
                                onOpen = { viewModel.load(it) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                            )
                        }
                    }
                }
            }

            if (!fullScreen) {
                AnimatedVisibility(
                    visible = toolbarVisible || omniboxFocused || findQuery != null,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
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
                        PageProgress(progress = progress, loading = loading)
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
                            tabCount = tabs.count { it.isIncognito == incognito },
                            incognito = incognito,
                            onBack = { viewModel.goBack() },
                            onForward = viewModel::goForward,
                            onTabs = {
                                val currentId = tab?.id
                                captureHandle.capture { bitmap ->
                                    if (currentId != null && bitmap != null) {
                                        viewModel.setThumbnail(currentId, bitmap)
                                    }
                                    onOpenTabs()
                                }
                            },
                            onToggleIncognito = { viewModel.setIncognitoMode(!incognito) },
                            onPanic = viewModel::panicWipe,
                            onMenu = { menuOpen = true }
                        )
                    }
                }
            }
        }

        // Omnibox suggestions float above the page, anchored to the toolbar.
        if (omniboxFocused && suggestions.isNotEmpty()) {
            SuggestionList(
                suggestions = suggestions,
                onPick = { viewModel.submit(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
                    .padding(horizontal = 8.dp)
            )
        }
    }

    if (menuOpen && tab != null) {
        MenuSheet(
            isBookmarked = bookmarked,
            desktopMode = desktopMode,
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
            onFingerprintTest = viewModel::openFingerprintTest
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
