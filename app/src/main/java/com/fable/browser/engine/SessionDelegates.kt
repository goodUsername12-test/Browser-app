package com.fable.browser.engine

import android.Manifest
import com.fable.browser.data.model.PermissionDecision
import com.fable.browser.data.model.PermissionKind
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.repo.HistoryRepository
import com.fable.browser.data.repo.SitePermissionRepository
import com.fable.browser.util.ActivityBridge
import com.fable.browser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import javax.inject.Inject
import javax.inject.Singleton

/** What the long-press context menu was opened on. */
data class ContextMenuTarget(
    val tabId: String,
    val linkUri: String?,
    val imageUri: String?,
    val title: String?,
    val isIncognito: Boolean
)

/** Callbacks the delegates need from the tab layer. */
interface TabCallbacks {
    /** A page asked for a new window/tab (target=_blank, window.open). */
    fun onNewSessionRequested(tab: BrowserTab, uri: String): GeckoSession?

    /** window.close() on a tab we opened. */
    fun onCloseRequested(tab: BrowserTab)

    fun onContextMenu(target: ContextMenuTarget)

    /** Vertical scroll delta, used to collapse/expand the bottom toolbar. */
    fun onScroll(tab: BrowserTab, scrollY: Int)

    fun onSessionLost(tab: BrowserTab, crashed: Boolean)

    fun onMediaStateChanged(tab: BrowserTab, playing: Boolean)
}

/**
 * Wires every GeckoSession delegate for a tab.
 *
 * Privacy rules enforced here:
 *  - tracking query parameters are stripped before the request leaves;
 *  - HTTPS-only mode refuses plain HTTP and shows a Compose interstitial;
 *  - history is recorded through [HistoryRepository] for normal tabs only —
 *    incognito tabs never reach that call;
 *  - blocked-request counting for the shield badge;
 *  - downloads in incognito require per-file confirmation.
 */
@Singleton
class SessionDelegateFactory @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val sitePermissionRepository: SitePermissionRepository,
    private val promptBridge: PromptBridge,
    private val downloader: Downloader,
    private val contentBlocker: ContentBlocker,
    private val activityBridge: ActivityBridge
) {

    fun attach(
        tab: BrowserTab,
        scope: CoroutineScope,
        settings: () -> Settings,
        callbacks: TabCallbacks
    ) {
        val session = tab.session

        session.navigationDelegate = navigationDelegate(tab, scope, settings, callbacks)
        session.progressDelegate = progressDelegate(tab)
        session.contentDelegate = contentDelegate(tab, settings, callbacks)
        session.permissionDelegate = permissionDelegate(tab, scope)
        session.promptDelegate = FablePromptDelegate(promptBridge, activityBridge)
        session.historyDelegate = historyDelegate(tab, scope)
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                callbacks.onScroll(tab, scrollY)
            }
        }
        session.mediaSessionDelegate = mediaSessionDelegate(tab, callbacks)
        activityBridge.activity()?.let { activity ->
            session.selectionActionDelegate = BasicSelectionActionDelegate(activity)
        }
    }

    // ---------------- navigation ----------------

    private fun navigationDelegate(
        tab: BrowserTab,
        scope: CoroutineScope,
        settings: () -> Settings,
        callbacks: TabCallbacks
    ) = object : GeckoSession.NavigationDelegate {

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            url?.let {
                tab.updateUrl(it)
                tab.updateSecure(!UrlUtils.isInsecure(it))
                tab.updateReaderMode(false)
                if (tab.interstitial.value?.url != it) tab.updateInterstitial(null)
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tab.updateCanGoBack(canGoBack)
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tab.updateCanGoForward(canGoForward)
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            val current = settings()
            val uri = request.uri

            // Non-web schemes (intent://, mailto:, tel:, market:, geo:) go to the OS.
            if (!UrlUtils.isWebScheme(uri)) {
                if (UrlUtils.isExternalScheme(uri)) activityBridge.openExternal(uri)
                return GeckoResult.deny()
            }

            // HTTPS-only mode: refuse plain HTTP unless the user allowed this host.
            if (current.httpsOnly && UrlUtils.isInsecure(uri) &&
                !tab.isHttpAllowed(UrlUtils.host(uri))
            ) {
                tab.updateInterstitial(Interstitial.HttpsOnly(uri))
                tab.updateLoading(false)
                return GeckoResult.deny()
            }

            // Strip tracking parameters; reload the clean URL instead.
            if (current.stripTrackingParams) {
                val stripped = UrlUtils.stripTrackingParams(uri)
                if (stripped != uri) {
                    session.load(GeckoSession.Loader().uri(stripped))
                    return GeckoResult.deny()
                }
            }

            // Top-level document check against the filter lists.
            if (current.adBlockEnabled &&
                request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW &&
                contentBlocker.shouldBlock(uri, UrlUtils.host(tab.url.value))
            ) {
                tab.incrementBlocked()
                return GeckoResult.deny()
            }

            return GeckoResult.allow()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            val newSession = callbacks.onNewSessionRequested(tab, uri)
                ?: return GeckoResult.fromValue(null)
            return GeckoResult.fromValue(newSession)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            val target = uri ?: tab.url.value
            val reason = describe(error)
            tab.updateLoading(false)
            tab.updateProgress(0)
            when (error.category) {
                WebRequestError.ERROR_CATEGORY_SECURITY ->
                    tab.updateInterstitial(Interstitial.Ssl(target, reason))
                else ->
                    tab.updateInterstitial(Interstitial.Network(target, reason))
            }
            // Suppress Gecko's own error page; Compose draws the interstitial.
            return GeckoResult.fromValue("about:blank")
        }
    }

    private fun describe(error: WebRequestError): String = when (error.code) {
        WebRequestError.ERROR_SECURITY_SSL -> "The site's TLS handshake failed."
        WebRequestError.ERROR_SECURITY_BAD_CERT -> "The site's certificate is not valid."
        WebRequestError.ERROR_NET_INTERRUPT -> "The connection was interrupted."
        WebRequestError.ERROR_NET_TIMEOUT -> "The server took too long to respond."
        WebRequestError.ERROR_CONNECTION_REFUSED -> "The server refused the connection."
        WebRequestError.ERROR_UNKNOWN_HOST -> "That address could not be resolved."
        WebRequestError.ERROR_OFFLINE -> "You appear to be offline."
        WebRequestError.ERROR_MALFORMED_URI -> "That address is not a valid URL."
        WebRequestError.ERROR_FILE_NOT_FOUND -> "The file could not be found."
        WebRequestError.ERROR_PROXY_CONNECTION_REFUSED ->
            "The configured proxy refused the connection. Check the incognito proxy settings."
        else -> "The page could not be loaded."
    }

    // ---------------- progress ----------------

    private fun progressDelegate(tab: BrowserTab) = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            tab.updateLoading(true)
            tab.updateProgress(5)
            tab.resetBlocked()
            tab.updateUrl(url)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            tab.updateLoading(false)
            tab.updateProgress(if (success) 100 else 0)
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            tab.updateProgress(progress)
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            tab.updateSecure(securityInfo.isSecure)
        }

        override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
            if (session === tab.session) tab.savedState = sessionState
        }
    }

    private fun mediaSessionDelegate(tab: BrowserTab, callbacks: TabCallbacks) =
        object : MediaSession.Delegate {
            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                callbacks.onMediaStateChanged(tab, true)
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                callbacks.onMediaStateChanged(tab, false)
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                callbacks.onMediaStateChanged(tab, false)
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                callbacks.onMediaStateChanged(tab, false)
            }
        }

    // ---------------- content ----------------

    private fun contentDelegate(
        tab: BrowserTab,
        settings: () -> Settings,
        callbacks: TabCallbacks
    ) = object : GeckoSession.ContentDelegate {

        override fun onTitleChange(session: GeckoSession, title: String?) {
            tab.updateTitle(title.orEmpty())
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            tab.updateFullScreen(fullScreen)
            // No link/image menu while a game or video is in full screen.
            runCatching { session.panZoomController.setIsLongpressEnabled(!fullScreen) }
        }

        override fun onCloseRequest(session: GeckoSession) {
            callbacks.onCloseRequested(tab)
        }

        override fun onContextMenu(
            session: GeckoSession,
            screenX: Int,
            screenY: Int,
            element: GeckoSession.ContentDelegate.ContextElement
        ) {
            if (element.linkUri == null && element.srcUri == null) return
            callbacks.onContextMenu(
                ContextMenuTarget(
                    tabId = tab.id,
                    linkUri = element.linkUri,
                    imageUri = element.srcUri?.takeIf {
                        element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
                    },
                    title = element.title ?: element.altText,
                    isIncognito = tab.isIncognito
                )
            )
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            val fileName = response.headers["Content-Disposition"]
                ?.substringAfter("filename=", "")
                ?.trim('"', ' ')
                ?.ifBlank { null }
                ?: response.uri.substringAfterLast('/').substringBefore('?')

            if (tab.isIncognito) {
                // Never write to disk from incognito without explicit consent.
                promptBridge.show(
                    FablePrompt.IncognitoDownload(
                        title = "Save this file?",
                        fileName = fileName.ifBlank { "download" },
                        onConfirm = {
                            promptBridge.clear()
                            downloader.enqueue(response, fromIncognito = true)
                        },
                        onDismiss = { promptBridge.clear() }
                    )
                )
            } else {
                downloader.enqueue(response, fromIncognito = false)
            }
        }

        override fun onCrash(session: GeckoSession) {
            if (session !== tab.session) return
            tab.updateInterstitial(
                Interstitial.Network(tab.url.value, "The page's content process crashed.")
            )
            callbacks.onSessionLost(tab, crashed = true)
        }

        override fun onKill(session: GeckoSession) {
            if (session !== tab.session) return
            callbacks.onSessionLost(tab, crashed = false)
        }
    }

    // ---------------- permissions ----------------

    private fun permissionDelegate(tab: BrowserTab, scope: CoroutineScope) =
        object : GeckoSession.PermissionDelegate {

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                val requested = permissions?.filterNotNull()?.toTypedArray() ?: emptyArray()
                activityBridge.requestAndroidPermissions(requested) { granted ->
                    if (granted) callback.grant() else callback.reject()
                }
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                val result = GeckoResult<Int>()
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE) {
                    result.complete(
                        if (tab.isIncognito) GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                        else GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    )
                    return result
                }
                val kind = perm.permission.toPermissionKind()
                if (kind == null) {
                    result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    return result
                }
                val origin = UrlUtils.origin(perm.uri)
                scope.launch {
                    // Incognito never persists a decision; it always asks.
                    val stored = if (tab.isIncognito) PermissionDecision.ASK
                    else sitePermissionRepository.decisionFor(origin, kind)
                    when (stored) {
                        PermissionDecision.ALLOW ->
                            result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                        PermissionDecision.BLOCK ->
                            result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                        PermissionDecision.ASK -> if (kind == PermissionKind.AUTOPLAY) {
                            result.complete(
                                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE)
                                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                                else
                                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                            )
                        } else promptBridge.show(
                            FablePrompt.Permission(
                                title = kind.label(),
                                origin = UrlUtils.displayHost(perm.uri),
                                message = kind.requestMessage(),
                                onAllow = { remember ->
                                    promptBridge.clear()
                                    if (remember && !tab.isIncognito) scope.launch {
                                        sitePermissionRepository.set(origin, kind, PermissionDecision.ALLOW)
                                    }
                                    result.complete(
                                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                                    )
                                },
                                onBlock = { remember ->
                                    promptBridge.clear()
                                    if (remember && !tab.isIncognito) scope.launch {
                                        sitePermissionRepository.set(origin, kind, PermissionDecision.BLOCK)
                                    }
                                    result.complete(
                                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                                    )
                                },
                                onDismiss = {
                                    result.complete(
                                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                                    )
                                }
                            )
                        )
                    }
                }
                return result
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                val needed = buildList {
                    if (!video.isNullOrEmpty()) add(Manifest.permission.CAMERA)
                    if (!audio.isNullOrEmpty()) add(Manifest.permission.RECORD_AUDIO)
                }
                if (needed.isEmpty()) {
                    callback.reject()
                    return
                }
                activityBridge.requestAndroidPermissions(needed.toTypedArray()) { granted ->
                    if (granted) {
                        callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                    } else {
                        callback.reject()
                    }
                }
            }
        }

    // ---------------- history ----------------

    private fun historyDelegate(tab: BrowserTab, scope: CoroutineScope) =
        object : GeckoSession.HistoryDelegate {
            override fun onVisited(
                session: GeckoSession,
                url: String,
                lastVisitedURL: String?,
                flags: Int
            ): GeckoResult<Boolean> {
                // Incognito: no history, ever. Not written, not even read back.
                if (tab.isIncognito) return GeckoResult.fromValue(false)
                if (!url.startsWith("http", ignoreCase = true)) {
                    return GeckoResult.fromValue(false)
                }
                scope.launch { historyRepository.record(tab.title.value, url) }
                return GeckoResult.fromValue(true)
            }

            override fun onHistoryStateChange(
                session: GeckoSession,
                historyList: GeckoSession.HistoryDelegate.HistoryList
            ) {
                tab.updateCanGoBack(historyList.currentIndex > 0)
                tab.updateCanGoForward(historyList.currentIndex < historyList.size - 1)
            }
        }
}

private fun Int.toPermissionKind(): PermissionKind? = when (this) {
    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> PermissionKind.LOCATION
    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> PermissionKind.NOTIFICATIONS
    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> PermissionKind.AUTOPLAY
    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> null
    GeckoSession.PermissionDelegate.PERMISSION_XR -> PermissionKind.XR
    else -> null
}

private fun PermissionKind.label(): String = when (this) {
    PermissionKind.CAMERA -> "Camera access"
    PermissionKind.MICROPHONE -> "Microphone access"
    PermissionKind.LOCATION -> "Location access"
    PermissionKind.NOTIFICATIONS -> "Notifications"
    PermissionKind.AUTOPLAY -> "Autoplay media"
    PermissionKind.XR -> "Virtual reality devices"
}

private fun PermissionKind.requestMessage(): String = when (this) {
    PermissionKind.CAMERA -> "wants to use your camera."
    PermissionKind.MICROPHONE -> "wants to use your microphone."
    PermissionKind.LOCATION -> "wants to know your location."
    PermissionKind.NOTIFICATIONS -> "wants to send you notifications."
    PermissionKind.AUTOPLAY -> "wants to play media automatically."
    PermissionKind.XR -> "wants to access VR devices."
}
