package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import com.fable.browser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedEngineTest {

    @Test
    fun `default settings have zero unexpected afk or low-battery throttling`() {
        val defaults = Settings()
        assertEquals(0, defaults.batteryIdleGameFps)
        assertEquals(0, defaults.batteryAutoLowPct)
        assertEquals(120, defaults.batteryGameMaxFps)
        assertEquals(100, defaults.batteryGameRenderScale)

        val planAfter20sOnJoystick = RustFastCore.computePowerPlan(
            settings = defaults,
            batteryPct = 15,
            isCharging = false,
            thermalStatus = 0,
            isGame = true,
            idleMs = 20_000L
        )
        assertEquals(120, planAfter20sOnJoystick.targetGameFps)
        assertEquals(120, planAfter20sOnJoystick.targetDisplayHz)
        assertEquals(100, planAfter20sOnJoystick.renderScalePercent)
        assertEquals(2, planAfter20sOnJoystick.cpuBoostMode)
    }

    @Test
    fun `same-site registrable domain matches auth and api subdomains for bloxd`() {
        assertTrue(UrlUtils.isSameSite("auth.bloxd.io", "bloxd.io"))
        assertTrue(UrlUtils.isSameSite("api.bloxd.io", "staging.bloxd.io"))
        assertTrue(UrlUtils.isSameSite("bloxd.io", "www.bloxd.io"))
        assertFalse(UrlUtils.isSameSite("doubleclick.net", "bloxd.io"))
    }

    @Test
    fun `heavy 3d game urls and benchmark urls are separated cleanly`() {
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://bloxd.io/"))
        assertTrue(HybridEngineBridge.isPure3dGameUrl("https://bloxd.io/"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://browserbench.org/Speedometer3.1/"))
        assertFalse(HybridEngineBridge.isPure3dGameUrl("https://browserbench.org/Speedometer3.1/"))
    }
}
