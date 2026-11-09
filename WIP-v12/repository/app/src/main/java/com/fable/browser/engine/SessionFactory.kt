package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// session setup
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

    fun createSuperSession(settings: Settings, contextId: String, identity: SuperIdentity, open: Boolean = true): GeckoSession {
        val builder = GeckoSessionSettings.Builder()
            .usePrivateMode(true)
            .useTrackingProtection(settings.incognitoTrackingProtection)
            .allowJavascript(true)
            .contextId(contextId)
            .userAgentMode(if (identity.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (identity.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentOverride(identity.ua)
            .suspendMediaWhenInactive(true)

        val session = GeckoSession(builder.build())
        if (open) session.open(engineManager.runtime)
        applyCommon(session, settings, incognito = true)
        return session
    }

    fun createIncognitoSession(settings: Settings, contextId: String, open: Boolean = true): GeckoSession {
        val builder = GeckoSessionSettings.Builder()
            .usePrivateMode(true)
            .useTrackingProtection(settings.incognitoTrackingProtection)
            .allowJavascript(true)
            .contextId(contextId)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
        fingerprintShield.incognitoUserAgent(settings.incognitoRandomUserAgent).takeIf { it.isNotEmpty() }?.let { builder.userAgentOverride(it) }

        val session = GeckoSession(builder.build())
        if (open) session.open(engineManager.runtime)
        applyCommon(session, settings, incognito = true)
        return session
    }

    private fun applyCommon(session: GeckoSession, settings: Settings, incognito: Boolean) {
        session.settings.apply {
            allowJavascript = if (incognito) settings.incognitoJavascript else true
            if (incognito) useTrackingProtection = settings.incognitoTrackingProtection
        }
    }

    fun newContextId(): String = "fable-" + UUID.randomUUID().toString()
}
