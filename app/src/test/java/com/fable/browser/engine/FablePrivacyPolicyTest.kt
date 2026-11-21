package com.fable.browser.engine

import com.fable.browser.data.model.DohProvider
import com.fable.browser.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FablePrivacyPolicyTest {

    @Test
    fun allPrivacyTogglesCanBeTurnedOffCleanly() {
        val off = Settings(
            adBlockEnabled = false,
            etpEnabled = false,
            cookiePurging = false,
            stripTrackingParams = false,
            blockThirdPartyCookies = false,
            httpsOnly = false,
            sendGpc = false,
            strictReferrer = false,
            hideLocalIp = false,
            webRtcDisabled = false,
            fingerprintProtectionAll = false,
            normalTabShield = false,
            stealthLowDetectability = false,
            blinkPrivacyParity = false,
            isolateNativeBridge = false,
            proxyStripLeakHeaders = false,
            dohProvider = DohProvider.OFF
        )
        val decision = FablePrivacyPolicy.evaluate(off, "https://example.com/page", isIncognito = false, isSuper = false)
        assertFalse(decision.adAndTrackerBlocking)
        assertFalse(decision.etpEnabled)
        assertFalse(decision.cookiePurging)
        assertFalse(decision.stripTrackingParams)
        assertFalse(decision.blockThirdPartyCookies)
        assertFalse(decision.httpsOnly)
        assertFalse(decision.sendGpc)
        assertFalse(decision.strictReferrer)
        assertFalse(decision.hideLocalIp)
        assertFalse(decision.webRtcDisabled)
        assertFalse(decision.fingerprintProtectionActive)
        assertFalse(decision.blinkPrivacyParity)
        assertFalse(decision.isolateNativeBridge)
        assertFalse(decision.proxyStripLeakHeaders)

        val caps = FablePrivacyPolicy.capabilityMatrix(off)
        assertTrue(caps.all { it.geckoStatus == CapabilityLevel.DISABLED && it.blinkStatus == CapabilityLevel.DISABLED })
    }

    @Test
    fun stealthLowDetectabilityEnforcesNetworkPrivacyWithoutNoisyJsSpoofing() {
        val stealth = Settings(
            adBlockEnabled = true,
            etpEnabled = true,
            blockThirdPartyCookies = true,
            stripTrackingParams = true,
            sendGpc = true,
            hideLocalIp = true,
            stealthLowDetectability = true,
            normalTabShield = false,
            blinkPrivacyParity = true,
            isolateNativeBridge = true
        )
        val decision = FablePrivacyPolicy.evaluate(stealth, "https://news.ycombinator.com", isIncognito = false, isSuper = false)
        assertTrue(decision.adAndTrackerBlocking)
        assertTrue(decision.blockThirdPartyCookies)
        assertTrue(decision.stripTrackingParams)
        assertTrue(decision.sendGpc)
        assertTrue(decision.hideLocalIp)
        assertTrue(decision.isolateNativeBridge)
        assertTrue(decision.stealthLowDetectability)
        assertFalse("Stealth mode should not inject noisy fingerprint spoofing unless normalTabShield is enabled", decision.fingerprintProtectionActive)
    }

    @Test
    fun trustedSiteDisablesFingerprintOverridesWhileKeepingNetworkProtection() {
        val s = Settings(
            normalTabShield = true,
            stealthLowDetectability = false,
            gameMode = false,
            fingerprintProtectionAll = true,
            fpCanvas = true,
            fpTrustedSites = "poki.com"
        )
        val trusted = FablePrivacyPolicy.evaluate(s, "https://sub.poki.com/game", isIncognito = false, isSuper = false)
        assertTrue(trusted.isTrustedSite)
        assertFalse(trusted.fingerprintProtectionActive)
        assertFalse(trusted.fpCanvas)
        assertTrue(trusted.adAndTrackerBlocking)
        assertTrue(trusted.hideLocalIp)

        val normal = FablePrivacyPolicy.evaluate(s, "https://example.org/", isIncognito = false, isSuper = false)
        assertFalse(normal.isTrustedSite)
        assertTrue(normal.fingerprintProtectionActive)
        assertTrue(normal.fpCanvas)
    }

    @Test
    fun capabilityMatrixReflectsBlinkParityToggle() {
        val withParity = FablePrivacyPolicy.capabilityMatrix(Settings(blinkPrivacyParity = true))
        val adWith = withParity.first { it.id == "adblock" }
        assertEquals(CapabilityLevel.ENFORCED, adWith.geckoStatus)
        assertEquals(CapabilityLevel.ENFORCED, adWith.blinkStatus)

        val noParity = FablePrivacyPolicy.capabilityMatrix(Settings(blinkPrivacyParity = false))
        val adNo = noParity.first { it.id == "adblock" }
        assertEquals(CapabilityLevel.ENFORCED, adNo.geckoStatus)
        assertEquals(CapabilityLevel.DISABLED, adNo.blinkStatus)
    }
}
