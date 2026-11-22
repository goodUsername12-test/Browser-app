package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteSpeedEngineTest {

    @Test
    fun offByDefaultAndEmitsZeroBytesWhenOff() {
        val defaults = Settings()
        assertFalse(defaults.timeScaleEnabled)
        assertEquals(10, defaults.timeScaleMultiplierTenths)
        assertEquals(1.0f, defaults.timeScaleRate, 0.001f)
        assertEquals(100, defaults.timeScalePanelScalePct)
        assertEquals("", WebsiteSpeedEngine.buildBootstrapScript(defaults))

        val bootOff = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = defaults,
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://example.com"
        )
        assertFalse(bootOff.contains("__fableTimeScale"))
        assertFalse(bootOff.contains("scheduleTimeout"))
    }

    @Test
    fun emitsStealthVirtualClockAcrossFullRangeUpTo300x() {
        val slow = Settings(timeScaleEnabled = true, timeScaleMultiplierTenths = 1)
        assertEquals(0.1f, slow.timeScaleRate, 0.001f)
        val slowJs = WebsiteSpeedEngine.buildBootstrapScript(slow)
        assertTrue(slowJs.contains("0.1"))
        assertTrue(slowJs.contains("scheduleTimeout"))
        assertTrue(slowJs.contains("Function.prototype"))
        assertTrue(slowJs.contains("Performance.prototype"))
        // Stealth: no obvious __fableTimeScale or FableScaledWorker globals leaked
        assertFalse(slowJs.contains("__fableTimeScale"))
        assertFalse(slowJs.contains("FableScaledWorker"))
        assertFalse(slowJs.contains("__fableBaseRate"))
        assertFalse(slowJs.contains("__fableBaseMediaRate"))

        val max300 = Settings(timeScaleEnabled = true, timeScaleMultiplierTenths = 3000)
        assertEquals(300.0f, max300.timeScaleRate, 0.001f)
        val maxJs = WebsiteSpeedEngine.buildBootstrapScript(max300)
        assertTrue(maxJs.contains("300.0"))
        assertTrue(maxJs.contains("Math.min(300.0"))

        val bootOn = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = max300,
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://example.com"
        )
        assertTrue(bootOn.contains("scheduleTimeout"))
        assertFalse(bootOn.contains("__fableTimeScale"))
    }

    @Test
    fun clampsFormatsAndParsesExactSpeedInputs() {
        assertEquals(0.1f, WebsiteSpeedEngine.clampRate(0.001f), 0.0001f)
        assertEquals(0.1f, WebsiteSpeedEngine.clampRate(-5f), 0.0001f)
        assertEquals(300.0f, WebsiteSpeedEngine.clampRate(999f), 0.0001f)
        assertEquals(1.0f, WebsiteSpeedEngine.clampRate(Float.NaN), 0.0001f)

        assertEquals("0.1x", WebsiteSpeedEngine.formatTenths(1))
        assertEquals("0.5x", WebsiteSpeedEngine.formatTenths(5))
        assertEquals("1x", WebsiteSpeedEngine.formatTenths(10))
        assertEquals("3x", WebsiteSpeedEngine.formatTenths(30))
        assertEquals("3.5x", WebsiteSpeedEngine.formatTenths(35))
        assertEquals("6x", WebsiteSpeedEngine.formatTenths(60))
        assertEquals("12.5x", WebsiteSpeedEngine.formatTenths(125))
        assertEquals("100x", WebsiteSpeedEngine.formatTenths(1000))
        assertEquals("300x", WebsiteSpeedEngine.formatTenths(3000))

        assertEquals(30, WebsiteSpeedEngine.parseSpeedInputToTenths("3"))
        assertEquals(35, WebsiteSpeedEngine.parseSpeedInputToTenths("3.5"))
        assertEquals(35, WebsiteSpeedEngine.parseSpeedInputToTenths("3,5x"))
        assertEquals(60, WebsiteSpeedEngine.parseSpeedInputToTenths("x6"))
        assertEquals(125, WebsiteSpeedEngine.parseSpeedInputToTenths(" 12.5X "))
        assertEquals(3000, WebsiteSpeedEngine.parseSpeedInputToTenths("300"))
        assertEquals(3000, WebsiteSpeedEngine.parseSpeedInputToTenths("500"))
        assertEquals(1, WebsiteSpeedEngine.parseSpeedInputToTenths("0.01"))
        assertNull(WebsiteSpeedEngine.parseSpeedInputToTenths(""))
        assertNull(WebsiteSpeedEngine.parseSpeedInputToTenths("abc"))
        assertNull(WebsiteSpeedEngine.parseSpeedInputToTenths("-3"))
        assertNull(WebsiteSpeedEngine.parseSpeedInputToTenths("0"))
    }

    @Test
    fun precisionSliderRoundTripsExactTargets() {
        val exactTargets = listOf(1, 5, 10, 15, 20, 30, 35, 60, 75, 100, 250, 500, 1000, 1500, 2000, 3000)
        for (target in exactTargets) {
            val pos = WebsiteSpeedEngine.sliderPosFromTenths(target)
            val roundTrip = WebsiteSpeedEngine.tenthsFromSliderPos(pos)
            assertEquals("Slider round-trip mismatch for tenths=$target (pos=$pos)", target, roundTrip)
        }
        assertEquals(60, WebsiteSpeedEngine.clampPanelScalePct(10))
        assertEquals(160, WebsiteSpeedEngine.clampPanelScalePct(300))
        assertEquals(31, WebsiteSpeedEngine.nudgeTenths(30, +1))
        assertEquals(29, WebsiteSpeedEngine.nudgeTenths(30, -1))
        assertEquals(210, WebsiteSpeedEngine.nudgeTenths(200, +1))
        assertEquals(199, WebsiteSpeedEngine.nudgeTenths(200, -1))
        assertEquals(1050, WebsiteSpeedEngine.nudgeTenths(1000, +1))
    }

    @Test
    fun liveUpdateWhenDisabledNeverInstallsEngine() {
        val offScript = WebsiteSpeedEngine.buildLiveUpdateScript(
            enabled = false,
            rate = 300.0f,
            applyCss = true,
            applyMedia = true,
            applyWorkers = true
        )
        assertTrue(offScript.contains("r: 1.0, c: false, m: false, k: false"))
        assertFalse(offScript.contains("installIn"))
        assertFalse(offScript.contains("scheduleTimeout"))
    }
}
