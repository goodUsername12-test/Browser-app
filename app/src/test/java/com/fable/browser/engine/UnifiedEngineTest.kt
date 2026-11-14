package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedEngineTest {

    @Test
    fun `heavy 3d game urls and benchmark urls are separated cleanly`() {
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://bloxd.io/"))
        assertTrue(HybridEngineBridge.isPure3dGameUrl("https://bloxd.io/"))
        assertTrue(HybridEngineBridge.isPure3dGameUrl("https://staging.bloxd.io/?lobby=1"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://browserbench.org/Speedometer3.1/"))
        assertFalse(HybridEngineBridge.isPure3dGameUrl("https://browserbench.org/Speedometer3.1/"))
        assertFalse(HybridEngineBridge.isHeavy3dGameUrl("https://en.wikipedia.org/wiki/Main_Page"))
    }

    @Test
    fun `rust power plan governor computes competitive eco thermal and afk plans accurately`() {
        val defaultSettings = Settings(
            highRefreshRate = true,
            batterySaverMaster = false,
            batteryAutoLowPct = 20,
            batteryThermalGuard = true,
            batteryGameMaxFps = 120,
            batteryGameRenderScale = 100,
            batteryIdleGameFps = 30,
            batteryIdleTimeoutSec = 8
        )
        // 1. Active competitive game, cool battery
        val comp = RustFastCore.computePowerPlan(defaultSettings, batteryPct = 85, isCharging = false, thermalStatus = 0, isGame = true, idleMs = 500L)
        assertEquals(120, comp.targetDisplayHz)
        assertEquals(120, comp.targetGameFps)
        assertEquals(100, comp.renderScalePercent)
        assertEquals(2, comp.cpuBoostMode)
        assertTrue(comp.choreographerActive)

        // 2. AFK idle in game for 10s -> drops to 30 FPS & 60 Hz display, disables CPU turbo
        val afk = RustFastCore.computePowerPlan(defaultSettings, batteryPct = 85, isCharging = false, thermalStatus = 0, isGame = true, idleMs = 10_000L)
        assertEquals(30, afk.targetGameFps)
        assertEquals(60, afk.targetDisplayHz)
        assertEquals(0, afk.cpuBoostMode)
        assertFalse(afk.choreographerActive)

        // 3. Severe thermal throttling -> caps at 60 FPS, 60 Hz, 75% render scale
        val hot = RustFastCore.computePowerPlan(defaultSettings, batteryPct = 85, isCharging = false, thermalStatus = 3, isGame = true, idleMs = 200L)
        assertEquals(60, hot.targetGameFps)
        assertEquals(60, hot.targetDisplayHz)
        assertEquals(75, hot.renderScalePercent)
        assertEquals(0, hot.cpuBoostMode)

        // 4. Low battery (15% <= 20% threshold, unplugged) -> auto-triggers saver & 85% 3D scale
        val lowBat = RustFastCore.computePowerPlan(defaultSettings, batteryPct = 15, isCharging = false, thermalStatus = 0, isGame = true, idleMs = 200L)
        assertTrue(lowBat.batterySaverActive)
        assertEquals(85, lowBat.renderScalePercent)

        // 5. Custom user Eco Gaming settings (90 FPS, 75% scale, Eco CPU)
        val userEco = defaultSettings.copy(batteryGameMaxFps = 90, batteryGameRenderScale = 75, batteryEcoCpu = true)
        val ecoPlan = RustFastCore.computePowerPlan(userEco, batteryPct = 90, isCharging = false, thermalStatus = 0, isGame = true, idleMs = 100L)
        assertEquals(90, ecoPlan.targetGameFps)
        assertEquals(90, ecoPlan.targetDisplayHz)
        assertEquals(75, ecoPlan.renderScalePercent)
        assertEquals(0, ecoPlan.cpuBoostMode)
    }

    @Test
    fun `first-party staging bloxd chunks are never blocked by substring rules`() {
        val filters = ContentBlocker.parse("-ad-banner-\n/ads.js\n||doubleclick.net^")
        assertFalse(
            ContentBlocker.shouldBlock(
                filters,
                "https://staging.bloxd.io/static/js/948-ad-banner-chunk.8f91a.js",
                "staging.bloxd.io"
            )
        )
        assertTrue(
            ContentBlocker.shouldBlock(
                filters,
                "https://doubleclick.net/pagead/ads.js",
                "staging.bloxd.io"
            )
        )
    }
}
