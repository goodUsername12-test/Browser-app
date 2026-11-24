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

    @Test
    fun mergesMultiFrameResultsAndParsesCommas() {
        assertEquals("4976216", WebsiteMemoryEngine.normalizeNumericInput("4,976,216", MemDataType.AUTO))
        assertEquals("4976216", WebsiteMemoryEngine.normalizeNumericInput(" 4 976 216 ", MemDataType.INT32))
        assertEquals("4,976,216", WebsiteMemoryEngine.normalizeNumericInput("4,976,216", MemDataType.STRING))

        val (fId, rawId) = WebsiteMemoryEngine.splitFrameAddr("@f_game1|wasm#0+0x2800000:i32")
        assertEquals("f_game1", fId)
        assertEquals("wasm#0+0x2800000:i32", rawId)

        val topFrame = """{"ok":true,"frameId":"f0","total":1,"canUndo":false,"wasmCount":0,"bufCount":0,"items":[{"addr":"js:ui.label","val":"4976216","type":"i32","kind":"js"}]}"""
        val gameFrame = """{"ok":true,"frameId":"f_game1","total":1,"canUndo":false,"wasmCount":1,"bufCount":2,"items":[{"addr":"@f_game1|wasm#0+0x2800000:i32","val":"4976216","type":"i32","kind":"wasm"}]}"""
        val merged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(topFrame, gameFrame), "firstScan", 150))
        assertTrue(merged.getBoolean("ok"))
        assertEquals(2, merged.getInt("total"))
        assertEquals(1, merged.getInt("wasmCount"))
        assertEquals(2, merged.getInt("frameCount"))
        assertEquals("@f_game1|wasm#0+0x2800000:i32", merged.getJSONArray("items").getJSONObject(0).getString("addr"))
    }
}
