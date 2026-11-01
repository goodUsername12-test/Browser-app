package com.fable.browser.engine

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class FingerprintShield @Inject constructor() {
    private val uaPool = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.104 Mobile Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0"
    )

    @Volatile private var sessionUa: String? = null

    fun knownUserAgents(): Set<String> = uaPool.toSet()

    fun sessionUserAgent(): String =
        sessionUa ?: synchronized(this) {
            sessionUa ?: uaPool[Random.nextInt(uaPool.size)].also { sessionUa = it }
        }

    fun resetSession() {
        synchronized(this) { sessionUa = null }
    }

    companion object {
        const val FINGERPRINT_TEST_URL = "https://coveryourtracks.eff.org"
    }
}
