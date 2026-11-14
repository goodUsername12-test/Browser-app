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
    }

    @Test
    fun `local html tools route to blink secure context without game hooks`() {
        assertTrue(HybridEngineBridge.isLocalHtmlAppUrl("https://local.fable.app/opened_html/123/bloxd_video_maker.html"))
        assertTrue(HybridEngineBridge.isLocalHtmlAppUrl("file:///data/user/0/com.fable.browser/cache/opened_html/123/tool.html"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://local.fable.app/opened_html/123/bloxd_video_maker.html"))
        assertFalse(HybridEngineBridge.isPure3dGameUrl("https://local.fable.app/opened_html/123/bloxd_video_maker.html"))
    }

    @Test
    fun `rust network optimizer scales parallel download streams by wifi quality`() {
        val fast5g = RustFastCore.recommendNetworkPlan(
            rssiDbm = -50,
            linkMbps = 400,
            downKbps = 250_000,
            upKbps = 100_000,
            freqMhz = 5240,
            isWifi = true,
            isMetered = false,
            fastConn = true,
            fileSizeMb = 25
        )
        assertTrue(fast5g.qualityScore >= 85)
        assertEquals(6, fast5g.parallelStreams)
        assertEquals(2048, fast5g.uploadChunkKb)

        val meteredSmall = RustFastCore.recommendNetworkPlan(
            rssiDbm = -75,
            linkMbps = 40,
            downKbps = 15_000,
            upKbps = 5_000,
            freqMhz = 2412,
            isWifi = true,
            isMetered = true,
            fastConn = true,
            fileSizeMb = 25
        )
        assertEquals(1, meteredSmall.parallelStreams)
    }

    @Test
    fun `same-site registrable domain matches auth and api subdomains for bloxd`() {
        assertTrue(UrlUtils.isSameSite("auth.bloxd.io", "bloxd.io"))
        assertTrue(UrlUtils.isSameSite("api.bloxd.io", "staging.bloxd.io"))
        assertTrue(UrlUtils.isSameSite("bloxd.io", "www.bloxd.io"))
        assertFalse(UrlUtils.isSameSite("doubleclick.net", "bloxd.io"))
    }
}
