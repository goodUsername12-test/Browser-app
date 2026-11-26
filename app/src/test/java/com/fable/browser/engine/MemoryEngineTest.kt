package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEngineTest {

    @Test
    fun offByDefaultAndZeroLoadWhenOff() {
        val defaults = Settings()
        assertFalse(defaults.memoryEngineEnabled)
        assertEquals(88, defaults.memoryEnginePanelWidthPct)
        assertEquals(62, defaults.memoryEnginePanelHeightPct)
        assertEquals(100, defaults.memoryEnginePanelScalePct)
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
    fun enabledEmitsStealthUltraScannerWithNoLeakyGlobals() {
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
        assertTrue(boot.contains("scanBufferGroup"))
        assertTrue(boot.contains("scanBufferXor"))
        assertTrue(boot.contains("installTraceEntry"))
        assertTrue(boot.contains("pointerScan"))
        assertTrue(boot.contains("writeHex"))
        assertTrue(boot.contains("reattach"))
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
    fun parsesHexClampsWidthHeightScaleTimeoutAndRules() {
        assertEquals(0x1A40L, NativeMemoryScanner.parseHexAddress("0x1A40"))
        assertEquals(0x7FFC6FF489A8L, NativeMemoryScanner.parseHexAddress("0x7FFC6FF489A8"))
        assertNull(NativeMemoryScanner.parseHexAddress(""))
        assertNull(NativeMemoryScanner.parseHexAddress("not_hex"))

        assertEquals(55, WebsiteMemoryEngine.clampPanelWidthPct(10))
        assertEquals(100, WebsiteMemoryEngine.clampPanelWidthPct(180))
        assertEquals(80, WebsiteMemoryEngine.clampPanelWidthPct(80))

        assertEquals(40, WebsiteMemoryEngine.clampPanelHeightPct(10))
        assertEquals(95, WebsiteMemoryEngine.clampPanelHeightPct(200))
        assertEquals(70, WebsiteMemoryEngine.clampPanelHeightPct(70))

        assertEquals(60, WebsiteMemoryEngine.clampPanelScalePct(20))
        assertEquals(140, WebsiteMemoryEngine.clampPanelScalePct(300))
        assertEquals(110, WebsiteMemoryEngine.clampPanelScalePct(110))

        assertEquals(1, WebsiteMemoryEngine.clampTimeoutSec(0))
        assertEquals(80, WebsiteMemoryEngine.clampTimeoutSec(120))
        assertEquals(42, WebsiteMemoryEngine.clampTimeoutSec(42))

        assertEquals(MemFreezeRule.MIN, MemFreezeRule.EXACT.next())
        assertEquals(MemFreezeRule.MAX, MemFreezeRule.MIN.next())
        assertEquals(MemFreezeRule.RANGE, MemFreezeRule.MAX.next())
        assertEquals(MemFreezeRule.STEP, MemFreezeRule.RANGE.next())
        assertEquals(MemFreezeRule.EXACT, MemFreezeRule.STEP.next())
        assertEquals(MemFreezeRule.RANGE, MemFreezeRule.fromCode("range"))
        assertEquals(MemFreezeRule.STEP, MemFreezeRule.fromCode("step"))
    }

    @Test
    fun evaluatesRelativeMathAndNormalizesInputs() {
        assertEquals("4976216", WebsiteMemoryEngine.normalizeNumericInput("4,976,216", MemDataType.AUTO))
        assertEquals("4976216", WebsiteMemoryEngine.normalizeNumericInput(" 4 976 216 ", MemDataType.INT32))
        assertEquals("255", WebsiteMemoryEngine.normalizeNumericInput("0xFF", MemDataType.INT32))
        assertEquals("50..100", WebsiteMemoryEngine.normalizeNumericInput("50..100", MemDataType.INT32))
        assertEquals("100;200::64", WebsiteMemoryEngine.normalizeNumericInput("100;200::64", MemDataType.AUTO))
        assertEquals("4,976,216", WebsiteMemoryEngine.normalizeNumericInput("4,976,216", MemDataType.STRING))

        assertEquals("350", WebsiteMemoryEngine.evaluateRelativeMath("250", "+100", MemDataType.INT32))
        assertEquals("200", WebsiteMemoryEngine.evaluateRelativeMath("250", "--50", MemDataType.INT32))
        assertEquals("500", WebsiteMemoryEngine.evaluateRelativeMath("250", "x2", MemDataType.INT32))
        assertEquals("750", WebsiteMemoryEngine.evaluateRelativeMath("250", "*3", MemDataType.INT32))
        assertEquals("125", WebsiteMemoryEngine.evaluateRelativeMath("250", "/2", MemDataType.INT32))
        assertEquals("12.5", WebsiteMemoryEngine.evaluateRelativeMath("25", "/2", MemDataType.FLOAT))
        assertEquals("-50", WebsiteMemoryEngine.evaluateRelativeMath("250", "-50", MemDataType.INT32))
    }

    @Test
    fun cheatTableCodecRoundTripsAndRejectsMalformedJson() {
        val tbl = MemSavedTable(
            id = "tbl_1",
            name = "Test Game",
            origin = "example.com",
            savedAtMs = 123456L,
            entries = listOf(
                MemWatchEntry(
                    id = "PAGE_WASM|js:player.coins",
                    scope = MemScope.PAGE_WASM,
                    addr = "js:player.coins",
                    label = "Coins",
                    dataType = MemDataType.INT32,
                    currentValue = "999",
                    originalValue = "100",
                    frozen = true,
                    frozenValue = "50..1000",
                    freezeRule = MemFreezeRule.RANGE
                )
            )
        )
        val encoded = MemoryCheatTableCodec.encodeSingleTable(tbl)
        val decoded = MemoryCheatTableCodec.decodeSingleTable(encoded)
        assertNotNull(decoded)
        assertEquals("Test Game", decoded!!.name)
        assertEquals(1, decoded.entries.size)
        assertEquals(MemFreezeRule.RANGE, decoded.entries[0].freezeRule)
        assertEquals("50..1000", decoded.entries[0].frozenValue)
        // Safety invariant: imported entries never auto-freeze or pretend attached before reattach verification
        assertFalse(decoded.entries[0].frozen)
        assertFalse(decoded.entries[0].attached)

        assertNull(MemoryCheatTableCodec.decodeSingleTable("not json"))
        assertNull(MemoryCheatTableCodec.decodeSingleTable("{\"name\":\"empty\",\"entries\":[]}"))
    }

    @Test
    fun mergesMultiFrameResultsTracesPointersAndHonestFreezeBatch() {
        val (fId, rawId) = WebsiteMemoryEngine.splitFrameAddr("@f_game1|wasm#0+0x2800000:i32")
        assertEquals("f_game1", fId)
        assertEquals("wasm#0+0x2800000:i32", rawId)

        val topFrame = """{"ok":true,"frameId":"f0","total":1,"canUndo":true,"undoDepth":2,"wasmCount":0,"bufCount":0,"workerCount":1,"elapsedMs":12,"engines":["Phaser"],"items":[{"addr":"js:ui.label","val":"4976216","type":"i32","kind":"js","engine":"Phaser"}]}"""
        val gameFrame = """{"ok":true,"frameId":"f_game1","total":1,"canUndo":false,"undoDepth":0,"wasmCount":1,"bufCount":2,"workerCount":1,"elapsedMs":45,"engines":["Unity"],"items":[{"addr":"@f_game1|wasm#0+0x2800000:i32","val":"4976216","type":"i32","kind":"wasm","engine":"Unity"}]}"""
        val merged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(topFrame, gameFrame), "firstScan", 300))
        assertTrue(merged.getBoolean("ok"))
        assertEquals(2, merged.getInt("total"))
        assertEquals(2, merged.getInt("undoDepth"))
        assertEquals(1, merged.getInt("wasmCount"))
        assertEquals(2, merged.getInt("frameCount"))
        assertEquals("@f_game1|wasm#0+0x2800000:i32", merged.getJSONArray("items").getJSONObject(0).getString("addr"))

        val rb1 = """{"ok":true,"values":{"js:a":"99"},"freezeStats":{"js:a":{"mode":"accessor-trap","rule":"eq","blocked":5,"status":"enforcing"}},"traces":{"js:a":{"active":true,"count":2,"events":[]}}}"""
        val rb2 = """{"ok":true,"values":{"@f1|wasm#0+0x40:i32":"777"},"freezeStats":{"@f1|wasm#0+0x40:i32":{"mode":"250Hz","rule":"range","blocked":12,"status":"enforcing"}},"traces":{}}"""
        val rbMerged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(rb1, rb2), "readBatch", 300))
        assertEquals("99", rbMerged.getJSONObject("values").getString("js:a"))
        assertEquals("777", rbMerged.getJSONObject("values").getString("@f1|wasm#0+0x40:i32"))
        assertEquals(2, rbMerged.getJSONObject("traces").getJSONObject("js:a").getInt("count"))

        val fbEmpty1 = """{"ok":false,"locked":0,"lockedIds":[],"failedIds":["js:missing"],"freezeStats":{}}"""
        val fbEmpty2 = """{"ok":false,"locked":0,"lockedIds":[],"failedIds":[],"freezeStats":{}}"""
        val fbEmptyMerged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(fbEmpty1, fbEmpty2), "freezeBatch", 300))
        assertFalse(fbEmptyMerged.getBoolean("ok"))
        assertEquals(0, fbEmptyMerged.getInt("locked"))

        val ptr1 = """{"ok":false,"error":"not in frame"}"""
        val ptr2 = """{"ok":true,"count":1,"items":[{"ptrAddr":"@f1|wasm#0+0x400:u32","pointsTo":"0x200","offset":"+0x8","region":"wasm#0"}]}"""
        val ptrMerged = org.json.JSONObject(WebsiteMemoryEngine.mergeMultiFrameResults(listOf(ptr1, ptr2), "pointerScan", 300))
        assertTrue(ptrMerged.getBoolean("ok"))
        assertEquals(1, ptrMerged.getInt("count"))
    }
}
