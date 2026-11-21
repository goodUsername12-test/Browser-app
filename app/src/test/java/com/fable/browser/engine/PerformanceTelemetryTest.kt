package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import com.fable.browser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerformanceTelemetryTest {

    @Before
    fun setup() {
        PerformanceTelemetry.resetForTest()
    }

    @Test
    fun tracksCookieFlushesAndEngineLabels() {
        PerformanceTelemetry.setActiveEngine("Blink")
        PerformanceTelemetry.recordCookieFlush()
        PerformanceTelemetry.recordCookieFlush()
        val snap = PerformanceTelemetry.snapshot.value
        assertEquals("Blink", snap.activeEngine)
        assertEquals(2, snap.cookieFlushCount)
        assertTrue(snap.lastCookieFlushAtMs > 0L)
    }

    @Test
    fun tracksColdStartPageLoadAndSummaryWithoutAndroidMocks() {
        PerformanceTelemetry.markAppCreated()
        PerformanceTelemetry.markFirstFrame(120f)
        PerformanceTelemetry.markNavigationStart("https://example.com", "GeckoView")
        PerformanceTelemetry.markPageLoaded("https://example.com", "GeckoView")
        val snap = PerformanceTelemetry.snapshot.value
        assertEquals(120f, snap.displayHz, 0.01f)
        assertEquals(1, snap.pageLoadsMeasured)
        assertTrue(snap.lastPageLoadMs >= 1L)
        val summary = PerformanceTelemetry.dumpSummary("unit")
        assertTrue(summary.contains("SUMMARY[unit]"))
        assertTrue(summary.contains("hz=120"))
    }

    @Test
    fun loopbackUrlsAreRecognizedAndExternalHttpIsNotLoopback() {
        assertTrue(UrlUtils.isLoopback("http://127.0.0.1:18080/index.html"))
        assertTrue(UrlUtils.isLoopback("http://localhost:8080/test"))
        assertTrue(UrlUtils.isLoopback("http://[::1]:3000/"))
        assertFalse(UrlUtils.isLoopback("http://example.com/"))
        assertFalse(UrlUtils.isLoopback("https://bloxd.io/"))
    }

    @Test
    fun hybridBootstrapAndArchiveScriptReflectPrivacyAndCaptureSettings() {
        val s = Settings(
            stealthLowDetectability = true,
            isolateNativeBridge = true,
            archiveReadOnly = true,
            archiveIncludeCookies = false,
            archiveContinuousCapture = true
        )
        val boot = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = s,
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://example.com",
            bridgeToken = "tok-secret-123"
        )
        assertTrue(boot.contains("tok-secret-123"))
        assertTrue(boot.contains("\"isolateBridge\":true"))
        assertTrue(boot.contains("\"stealthLowDetect\":true"))

        val arch = HybridEngineBridge.buildBlinkArchiveScript(
            reqId = "req-1",
            mode = "memory",
            incognito = false,
            settings = s,
            continuous = true,
            extraObservedUrls = listOf("https://example.com/a.js", "https://example.com/b.wasm")
        )
        assertTrue(arch.contains("const CONTINUOUS = true;"))
        assertTrue(arch.contains("const READ_ONLY = true;"))
        assertTrue(arch.contains("const INC_COOKIES = false;"))
        assertTrue(arch.contains("https://example.com/b.wasm"))
    }
}
