package com.fable.browser.engine

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

sealed interface Interstitial {
    val url: String

    data class HttpsOnly(override val url: String) : Interstitial

    data class Ssl(override val url: String, val reason: String) : Interstitial

    data class Network(override val url: String, val reason: String) : Interstitial
}

data class FindMatch(val current: Int, val total: Int)

class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val isIncognito: Boolean,
    initialSession: GeckoSession,
    val contextId: String? = null
) {
    private val _session = MutableStateFlow(initialSession)
    val sessionFlow: StateFlow<GeckoSession> = _session.asStateFlow()
    val session: GeckoSession get() = _session.value

    private val _isPinned = MutableStateFlow(false)
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()

    private val _isUnloaded = MutableStateFlow(false)
    val isUnloaded: StateFlow<Boolean> = _isUnloaded.asStateFlow()

    @Volatile
    var lastAccessed: Long = System.currentTimeMillis()
        internal set

    @Volatile
    var isMediaPlaying: Boolean = false
        internal set

    internal var savedState: GeckoSession.SessionState? = null
    private val _url = MutableStateFlow("about:blank")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private val _isSecure = MutableStateFlow(true)
    val isSecure: StateFlow<Boolean> = _isSecure.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    private val _isDesktopMode = MutableStateFlow(false)
    val isDesktopMode: StateFlow<Boolean> = _isDesktopMode.asStateFlow()

    private val _thumbnail = MutableStateFlow<Bitmap?>(null)
    val thumbnail: StateFlow<Bitmap?> = _thumbnail.asStateFlow()

    private val _interstitial = MutableStateFlow<Interstitial?>(null)
    val interstitial: StateFlow<Interstitial?> = _interstitial.asStateFlow()

    private val _isFullScreen = MutableStateFlow(false)
    val isFullScreen: StateFlow<Boolean> = _isFullScreen.asStateFlow()

    private val _isReaderMode = MutableStateFlow(false)
    val isReaderMode: StateFlow<Boolean> = _isReaderMode.asStateFlow()

    private val _findMatch = MutableStateFlow<FindMatch?>(null)
    val findMatch: StateFlow<FindMatch?> = _findMatch.asStateFlow()

    private val httpExceptions = mutableSetOf<String>()

    var isEvicted: Boolean
        get() = _isUnloaded.value
        internal set(value) { _isUnloaded.value = value }

    internal var evictedUrl: String? = null

    internal fun updateUrl(v: String) { _url.value = v }
    internal fun updateTitle(v: String) { _title.value = v }
    internal fun updateProgress(v: Int) { _progress.value = v }
    internal fun updateLoading(v: Boolean) { _isLoading.value = v }
    internal fun updateCanGoBack(v: Boolean) { _canGoBack.value = v }
    internal fun updateCanGoForward(v: Boolean) { _canGoForward.value = v }
    internal fun updateSecure(v: Boolean) { _isSecure.value = v }
    internal fun incrementBlocked() { _blockedCount.value = _blockedCount.value + 1 }
    internal fun resetBlocked() { _blockedCount.value = 0 }
    internal fun updateDesktopMode(v: Boolean) { _isDesktopMode.value = v }
    internal fun updateInterstitial(v: Interstitial?) { _interstitial.value = v }
    internal fun updateFullScreen(v: Boolean) { _isFullScreen.value = v }
    internal fun updateReaderMode(v: Boolean) { _isReaderMode.value = v }
    internal fun updateFindMatch(v: FindMatch?) { _findMatch.value = v }

    internal fun allowHttp(host: String) { httpExceptions.add(host) }
    internal fun isHttpAllowed(host: String?): Boolean = host != null && host in httpExceptions

    internal fun updateThumbnail(bitmap: Bitmap?) {
        _thumbnail.value = bitmap
    }

    internal fun updatePinned(v: Boolean) { _isPinned.value = v }

    internal fun touch() { lastAccessed = System.currentTimeMillis() }

    internal fun replaceSession(newSession: GeckoSession) {
        val old = _session.value
        _session.value = newSession
        if (old !== newSession) runCatching { if (old.isOpen) old.close() }
    }

    internal fun markUnloaded() {
        evictedUrl = _url.value
        isEvicted = true
        isMediaPlaying = false
        updateLoading(false)
        updateFindMatch(null)
    }

    fun close() {
        updateThumbnail(null)
        updateFindMatch(null)
        httpExceptions.clear()
        savedState = null
        runCatching { session.close() }
    }
}
