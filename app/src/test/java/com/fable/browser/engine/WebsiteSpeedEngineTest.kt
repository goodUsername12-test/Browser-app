package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteSpeedEngineTest {

    @Test
    fun offByDefaultAndEmitsZeroBytesWhenOff() {
        val defaults = Settings()
        assertFalse(defaults.timeScaleEnabled)
        assertEquals(10, defaults.timeScaleMultiplierTenths)
        assertEquals(1.0f, defaults.timeScaleRate, 0.001f)
        assertEquals("", WebsiteSpeedEngine.buildBootstrapScript(defaults))

        val bootOff = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = defaults,
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://example.com"
        )
        assertFalse(bootOff.contains("__fableTimeScale"))
    }

    @Test
    fun emitsFullVirtualClockWhenEnabledAcrossFullRange() {
        val slow = Settings(timeScaleEnabled = true, timeScaleMultiplierTenths = 1)
        assertEquals(0.1f, slow.timeScaleRate, 0.001f)
        val slowJs = WebsiteSpeedEngine.buildBootstrapScript(slow)
        assertTrue(slowJs.contains("0.100"))
        assertTrue(slowJs.contains("__fableTimeScale"))
        assertTrue(slowJs.contains("scheduleTimeoutEntry"))

        val turbo = Settings(timeScaleEnabled = true, timeScaleMultiplierTenths = 1000)
        assertEquals(100.0f, turbo.timeScaleRate, 0.001f)
        val turboJs = WebsiteSpeedEngine.buildBootstrapScript(turbo)
        assertTrue(turboJs.contains("100.000"))

        val bootOn = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = slow,
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://example.com"
        )
        assertTrue(bootOn.contains("__fableTimeScale"))
    }

    @Test
    fun clampsExtremeValuesAndFormatsCleanly() {
        assertEquals(0.1f, WebsiteSpeedEngine.clampRate(0.001f), 0.0001f)
        assertEquals(0.1f, WebsiteSpeedEngine.clampRate(-5f), 0.0001f)
        assertEquals(100.0f, WebsiteSpeedEngine.clampRate(500f), 0.0001f)
        assertEquals(1.0f, WebsiteSpeedEngine.clampRate(Float.NaN), 0.0001f)
        assertEquals("0.1x", WebsiteSpeedEngine.formatRate(0.1f))
        assertEquals("0.5x", WebsiteSpeedEngine.formatRate(0.5f))
        assertEquals("1x", WebsiteSpeedEngine.formatRate(1.0f))
        assertEquals("10x", WebsiteSpeedEngine.formatRate(10.0f))
        assertEquals("100x", WebsiteSpeedEngine.formatRate(100.0f))
    }

    @Test
    fun liveUpdateWhenDisabledNeverInstallsEngine() {
        val offScript = WebsiteSpeedEngine.buildLiveUpdateScript(
            enabled = false,
            rate = 0.1f,
            applyCss = true,
            applyMedia = true,
            applyWorkers = true
        )
        assertTrue(offScript.contains("setConfig(1.0, false, false, false)"))
        assertFalse(offScript.contains("origSetTimeout"))
    }
}
