package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEngineTest {

    @Test
    fun offByDefaultAndZeroLoadWhenOff() {
        val defaults = Settings()
        assertFalse(defaults.memoryEngineEnabled)
        assertEquals(100, defaults.memoryEnginePanelScalePct)
        assertEquals("", WebsiteMemoryEngine.buildBootstrapScript(defaults))

        val bootOff = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = defaults,
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://example.com"
        )
        assertFalse(bootOff.contains("trackWasmMem"))
    }

    @Test
    fun enabledEmitsStealthWasmAndJsScannerWithNoLeakyGlobals() {
        val enabled = Settings(memoryEngineEnabled = true)
        val boot = WebsiteMemoryEngine.buildBootstrapScript(enabled)
        assertTrue(boot.contains("trackWasmMem"))
        assertTrue(boot.contains("Function.prototype"))
        assertTrue(boot.contains("WebAssembly"))
        assertFalse(boot.contains("__fableMemory"))
        assertFalse(boot.contains("window.__fable"))

        val cmdScript = WebsiteMemoryEngine.buildBlinkCommandScript("{\"op\":\"firstScan\",\"val1\":\"100\"}")
        assertTrue(cmdScript.contains("JSON.stringify"))
        assertTrue(cmdScript.contains("CustomEvent"))

        val resetOnly = WebsiteMemoryEngine.buildBlinkResetIfInstalledScript()
        assertTrue(resetOnly.contains("\"reset\""))
        assertFalse(resetOnly.contains("trackWasmMem"))
    }

    @Test
    fun parsesHexAddressesAndClampsScale() {
        assertEquals(0x1A40L, NativeMemoryScanner.parseHexAddress("0x1A40"))
        assertEquals(0x7FFC6FF489A8L, NativeMemoryScanner.parseHexAddress("0x7FFC6FF489A8"))
        assertNull(NativeMemoryScanner.parseHexAddress(""))
        assertNull(NativeMemoryScanner.parseHexAddress("not_hex"))

        assertEquals(65, WebsiteMemoryEngine.clampPanelScalePct(20))
        assertEquals(150, WebsiteMemoryEngine.clampPanelScalePct(300))
        assertEquals(110, WebsiteMemoryEngine.clampPanelScalePct(110))
    }
}
