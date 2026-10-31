package com.fable.browser.engine

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Per-incognito-session fingerprint spoofing decisions made on the Kotlin side:
 * a User-Agent chosen from a pool of common real-world UAs. The JS half of the
 * shield (assets/extensions/fable_shield/fingerprint_shield.js) runs at
 * document_start in private tabs and handles canvas/WebGL/audio noise, WebRTC,
 * screen letterboxing, timezone, fonts, and timer precision.
 *
 * A new random pick is made once per incognito session batch and cleared on
 * wipe, so two different incognito sessions do not share a fingerprint.
 */
@Singleton
class FingerprintShield @Inject constructor() {

    /** Common desktop/mobile UAs (high-population, so we blend into the crowd). */
    private val uaPool = listOf(
        // Windows Chrome
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        // Windows Firefox
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        // macOS Safari
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
        // macOS Chrome
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        // Android Chrome (generic device)
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.104 Mobile Safari/537.36",
        // Linux Firefox
        "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0"
    )

    @Volatile private var sessionUa: String? = null

    /** The UA for the current incognito session. Stable within a session, random across sessions. */
    fun sessionUserAgent(): String =
        sessionUa ?: synchronized(this) {
            sessionUa ?: uaPool[Random.nextInt(uaPool.size)].also { sessionUa = it }
        }

    /** Called on incognito wipe so the next session gets a fresh identity. */
    fun resetSession() {
        synchronized(this) { sessionUa = null }
    }

    companion object {
        const val FINGERPRINT_TEST_URL = "https://coveryourtracks.eff.org"
    }
}
