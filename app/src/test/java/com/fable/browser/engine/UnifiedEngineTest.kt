package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedEngineTest {

    @Test
    fun `heavy 3d game urls are detected accurately`() {
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://bloxd.io/"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://staging.bloxd.io/?lobby=1"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://www.testufo.com/"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://krunker.io/"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://example.com/play?hybrid=1"))
        assertTrue(HybridEngineBridge.isHeavy3dGameUrl("https://custom3d.example.org/game", "custom3d.example.org, other.io"))
        assertFalse(HybridEngineBridge.isHeavy3dGameUrl("https://en.wikipedia.org/wiki/Main_Page"))
    }

    @Test
    fun `unified engine routing respects manual override and auto game rules`() {
        val defaultSettings = Settings(hybridGeckoBlinkBeta = true, hybridAutoForGames = true)
        assertTrue(FableUnifiedEngine.shouldUseBlinkForUrl("https://bloxd.io", defaultSettings, manualOverride = null))
        assertFalse(FableUnifiedEngine.shouldUseBlinkForUrl("https://wikipedia.org", defaultSettings, manualOverride = null))
        assertTrue(FableUnifiedEngine.shouldUseBlinkForUrl("https://wikipedia.org", defaultSettings, manualOverride = true))
        assertFalse(FableUnifiedEngine.shouldUseBlinkForUrl("https://bloxd.io", defaultSettings, manualOverride = false))
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

    @Test
    fun `rolling 4-byte substring index matches thousands of rules and honors exceptions`() {
        val sb = StringBuilder()
        for (i in 0 until 2000) {
            sb.append("/SyntheticTrackerPath_").append(i).append("_\n")
        }
        sb.append("/pagead/adview\n")
        sb.append("@@||trusted-cdn.example.org/pagead/adview\n")
        val f = ContentBlocker.parse(sb.toString())

        assertFalse(ContentBlocker.shouldBlock(f, "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js", "bloxd.io"))
        assertTrue(ContentBlocker.shouldBlock(f, "https://evil-ads.net/SyntheticTrackerPath_1999_pixel.gif", "bloxd.io"))
        assertTrue(ContentBlocker.shouldBlock(f, "https://other-ads.net/pagead/adview?x=1", "bloxd.io"))
        assertFalse(ContentBlocker.shouldBlock(f, "https://trusted-cdn.example.org/pagead/adview?x=1", "bloxd.io"))
    }

    @Test
    fun `native fast engine domain matching and hashing work deterministically`() {
        assertTrue(NativeFastEngine.matchDomain("staging.bloxd.io", "bloxd.io"))
        assertTrue(NativeFastEngine.matchDomain("bloxd.io", "bloxd.io"))
        assertFalse(NativeFastEngine.matchDomain("notbloxd.io", "bloxd.io"))

        val bytes = "fable-unified-engine-v39".toByteArray(Charsets.UTF_8)
        val crc1 = NativeFastEngine.crc32(bytes)
        val crc2 = NativeFastEngine.crc32(bytes)
        assertEquals(crc1, crc2)
        assertTrue(crc1 != 0L)

        val h1 = NativeFastEngine.hash64(bytes)
        val h2 = NativeFastEngine.hash64(bytes)
        assertEquals(h1, h2)
        assertTrue(h1 != 0L)

        val fps120 = FableUnifiedEngine.recordFrameDeltaNs(8_333_333L)
        assertTrue(fps120 in 115..120)
    }
}
