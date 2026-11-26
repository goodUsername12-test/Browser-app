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
        assertEquals(100, defaults.memoryEnginePanelHeightPct)
        assertEquals(15, defaults.memoryEngineTimeoutSec)
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
    fun enabledEmitsStealthMultiEngineScannerAndInstantFreezeWithNoLeakyGlobals() {
        val enabled = Settings(memoryEngineEnabled = true)
        val boot = WebsiteMemoryEngine.buildBootstrapScript(enabled)
        assertTrue(boot.contains("trackWasmMem"))
        assertTrue(boot.contains("Function.prototype"))
        assertTrue(boot.contains("WebAssembly"))
        assertTrue(boot.contains("detectEngines"))
        assertTrue(boot.contains("installFreezeEntry"))
        assertTrue(boot.contains("findPropertyDescriptorChain"))
        assertTrue(boot.contains("captureTargetBytes"))
        assertTrue(boot.contains("peekStruct"))
        assertTrue(boot.contains("captureUnknownSnapshots"))
        assertTrue(boot.contains("scanBufferFallback"))
        assertFalse(boot.contains("__fableMemory"))
        assertFalse(boot.contains("window.__fable"))

        val cmdScript = WebsiteMemoryEngine.buildBlinkCommandScript("{\"op\":\"firstScan\",\"val1\":\"100\",\"timeoutMs\":15000}")
        assertTrue(cmdScript.contains("JSON.stringify"))
        assertTrue(cmdScript.contains("CustomEvent"))

        val resetOnly = WebsiteMemoryEngine.buildBlinkResetIfInstalledScript()
        assertTrue(resetOnly.contains("\"reset\""))
        assertTrue(resetOnly.contains("clearFreezes"))
        assertFalse(resetOnly.contains("trackWasmMem"))
    }

    @Test
    fun parsesHexAddressesClampsScaleHeightAndTimeout() {
        assertEquals(0x1A40L, NativeMemoryScanner.parseHexAddress("0x1A40"))
        assertEquals(0x7FFC6FF489A8L, NativeMemoryScanner.parseHexAddress("0x7FFC6FF489A8"))
        assertNull(NativeMemoryScanner.parseHexAddress(""))
        assertNull(NativeMemoryScanner.parseHexAddress("not_hex"))

        assertEquals(50, WebsiteMemoryEngine.clampPanelScalePct(20))
        assertEquals(160, WebsiteMemoryEngine.clampPanelScalePct(300))
        assertEquals(110, WebsiteMemoryEngine.clampPanelScalePct(110))

        assertEquals(55, WebsiteMemoryEngine.clampPanelHeightPct(10))
        assertEquals(150, WebsiteMemoryEngine.clampPanelHeightPct(300))
        assertEquals(120, WebsiteMemoryEngine.clampPanelHeightPct(120))

        assertEquals(1, WebsiteMemoryEngine.clampTimeoutSec(0))
        assertEquals(1, WebsiteMemoryEngine.clampTimeoutSec(-5))
        assertEquals(80, WebsiteMemoryEngine.clampTimeoutSec(120))
        assertEquals(42, WebsiteMemoryEngine.clampTimeoutSec(42))

        assertEquals(MemFreezeRule.MIN, MemFreezeRule.EXACT.next())
        assertEquals(MemFreezeRule.MAX, MemFreezeRule.MIN.next())
        assertEquals(MemFreezeRule.EXACT, MemFreezeRule.MAX.next())
    }

    @Test
    fun mergesMultiFrameResultsHonestFreezeBatchAndParsesCommas() {
        assertEquals("4976216", WebsiteMemoryEngine.normalizeNumericInput("4,976,216", MemDataType.AUTO))
        assertEquals("4976216", WebsiteMemoryEngine.normalizeNumericInput(" 4 976 216 ", MemDataType.INT32))
        assertEquals("4,976,216", WebsiteMemoryEngine.normalizeNumericInput("4,976,216", MemDataType.STRING))

        val (fId, rawId) = WebsiteMemoryEngine.splitFrameAddr("@f_game1|wasm#0+0x2800000:i32")
        assertEquals("f_game1", fId)
        assertEquals("wasm#0+0x2800000:i32", rawId)

        val topFrame = """{"ok":true,"frameId":"f0","total":1,"canUndo":false,"wasmCount":0,"bufCount":0,"workerCount":1,"elapsedMs":12,"engines":["Phaser"],"items":[{"addr":"js:ui.label","val":"4976216","type":"i32","kind":"js","engine":"Phaser"}]}"""
        val gameFrame = """{"ok":true,"frameId":"f_game1","total":1,"canUndo":false,"wasmCount":1,"bufCount":2,"workerCount":1,"elapsedMs":45,"engines":["Unity"],"items":[{"addr":"@f_game1|wasm#0+0x2800000:i32","val":"4976216","type":"i32","kind":"wasm","engine":"Unity"}]}"""
        val merged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(topFrame, gameFrame), "firstScan", 300))
        assertTrue(merged.getBoolean("ok"))
        assertEquals(2, merged.getInt("total"))
        assertEquals(1, merged.getInt("wasmCount"))
        assertEquals(2, merged.getInt("workerCount"))
        assertEquals(2, merged.getInt("frameCount"))
        assertEquals(45L, merged.getLong("elapsedMs"))
        assertEquals("@f_game1|wasm#0+0x2800000:i32", merged.getJSONArray("items").getJSONObject(0).getString("addr"))

        val rb1 = """{"ok":true,"values":{"js:a":"99"},"freezeStats":{"js:a":{"mode":"accessor-trap","rule":"eq","blocked":5,"status":"enforcing"}}}"""
        val rb2 = """{"ok":true,"values":{"@f1|wasm#0+0x40:i32":"777"},"freezeStats":{"@f1|wasm#0+0x40:i32":{"mode":"250Hz","rule":"min","blocked":12,"status":"enforcing"}}}"""
        val rbMerged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(rb1, rb2), "readBatch", 300))
        assertEquals("99", rbMerged.getJSONObject("values").getString("js:a"))
        assertEquals("777", rbMerged.getJSONObject("values").getString("@f1|wasm#0+0x40:i32"))
        assertEquals("accessor-trap", rbMerged.getJSONObject("freezeStats").getJSONObject("js:a").getString("mode"))
        assertEquals(12, rbMerged.getJSONObject("freezeStats").getJSONObject("@f1|wasm#0+0x40:i32").getInt("blocked"))

        val fb1 = """{"ok":true,"locked":2,"lockedIds":["js:a","js:b"],"failedIds":[],"freezeStats":{"js:a":{"mode":"trap","blocked":0,"status":"locked"}}}"""
        val fb2 = """{"ok":true,"locked":3,"lockedIds":["@f1|wasm#0+0x40:i32"],"failedIds":[],"freezeStats":{"@f1|wasm#0+0x40:i32":{"mode":"250Hz","blocked":0,"status":"locked"}}}"""
        val fbMerged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(fb1, fb2), "freezeBatch", 300))
        assertTrue(fbMerged.getBoolean("ok"))
        assertEquals(5, fbMerged.getInt("locked"))
        assertEquals(3, fbMerged.getJSONArray("lockedIds").length())

        val fbEmpty1 = """{"ok":false,"locked":0,"lockedIds":[],"failedIds":["js:missing"],"freezeStats":{}}"""
        val fbEmpty2 = """{"ok":false,"locked":0,"lockedIds":[],"failedIds":[],"freezeStats":{}}"""
        val fbEmptyMerged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(fbEmpty1, fbEmpty2), "freezeBatch", 300))
        assertFalse(fbEmptyMerged.getBoolean("ok"))
        assertEquals(0, fbEmptyMerged.getInt("locked"))
    }
}
