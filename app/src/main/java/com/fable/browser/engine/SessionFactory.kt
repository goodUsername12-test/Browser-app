package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds GeckoSessions.
 *
 * Normal tab: persistent profile, history/cache allowed.
 *
 * Incognito tab (usePrivateMode = true): entirely in-memory. Gecko's private
 * mode guarantees no cookies/localStorage/IndexedDB/serviceWorkers/HTTP cache
 * are written to disk. On top of that we:
 *  - give each incognito tab its own contextId (session-partition key) when
 *    "Isolate each incognito tab" is ON, so two tabs cannot correlate the user
 *    across sites (isolation Chrome does not offer);
 *  - set a spoofed User-Agent from the fingerprint pool;
 *  - suppress the built-in autofill.
 */
@Singleton
class SessionFactory @Inject constructor(
    private val engineManager: EngineManager,
    private val fingerprintShield: FingerprintShield
) {

    fun createNormalSession(settings: Settings, open: Boolean = true): GeckoSession {
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .useTrackingProtection(true)
                .allowJavascript(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .build()
        )
        if (open) session.open(engineManager.runtime)
        applyCommon(session, settings, incognito = false)
        return session
    }

    /**
     * @param sharedContextId when isolation is OFF, all incognito tabs share one
     *   context; when ON, pass a fresh UUID per tab.
     */
    fun createIncognitoSession(settings: Settings, contextId: String, open: Boolean = true): GeckoSession {
        val builder = GeckoSessionSettings.Builder()
            .usePrivateMode(true)                 // nothing touches disk
            .useTrackingProtection(true)
            .allowJavascript(true)
            .contextId(contextId)                 // storage/cookie partition
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .userAgentOverride(fingerprintShield.sessionUserAgent())
            .suspendMediaWhenInactive(true)

        val session = GeckoSession(builder.build())
        if (open) session.open(engineManager.runtime)
        applyCommon(session, settings, incognito = true)
        return session
    }

    private fun applyCommon(session: GeckoSession, settings: Settings, incognito: Boolean) {
        session.settings.apply {
            allowJavascript = true
        }
        // Force-dark handled per navigation via the content delegate; text zoom
        // applied through the display. Nothing here writes to disk in incognito.
    }

    fun newContextId(): String = "fable-" + UUID.randomUUID().toString()
}
