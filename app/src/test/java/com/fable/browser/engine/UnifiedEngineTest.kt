package com.fable.browser.engine

import com.fable.browser.data.model.DefaultBrowserEngine
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
        assertEquals(DefaultBrowserEngine.BLINK_GECKO_AUTO, defaults.defaultEngine)
    }

    @Test
    fun `bootstrap script keeps 2d hud canvases composited for bloxd fps and retains revoked blobs`() {
        val boot = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = Settings(),
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://bloxd.io/play/1v1_simulator"
        )
        assertFalse(boot.contains("desynchronized: true"))
        assertTrue(boot.contains("__fableRect"))
        assertTrue(boot.contains("blobRegistry"))
        assertTrue(boot.contains("beginJsDownload"))
    }

    @Test
    fun `same-site registrable domain matches auth and api subdomains for bloxd`() {
        assertTrue(UrlUtils.isSameSite("auth.bloxd.io", "bloxd.io"))
        assertTrue(UrlUtils.isSameSite("api.bloxd.io", "staging.bloxd.io"))
        assertTrue(UrlUtils.isSameSite("bloxd.io", "www.bloxd.io"))
        assertFalse(UrlUtils.isSameSite("doubleclick.net", "bloxd.io"))
    }

    @Test
    fun `v46 webgpu vulkan hyper-core script includes uid cache 3-tier cascade turnstile guard and polyfills`() {
        val boot = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = Settings(),
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://bloxd.io/play/classic"
        )
        assertTrue(boot.contains("challenges") && boot.contains("cloudflare"))
        assertTrue(boot.contains("__fableNativeToStringMap"))
        assertTrue(boot.contains("requestAdapterInfo"))
        assertTrue(boot.contains("shader-f16"))
        assertTrue(boot.contains("texture-compression-astc"))
        assertTrue(boot.contains("__fableGpuUid"))
        assertTrue(boot.contains("KHR_parallel_shader_compile"))
        assertTrue(boot.contains("bindVertexArray"))
        assertFalse(boot.contains("desynchronized: true"))
    }

    @Test
    fun `v46 vulkan hardware json parser extracts extensions and core counts`() {
        val parsed = FableWebGpuManager.parseVulkanHardwareJson(
            """{"available":true,"deviceName":"Adreno (TM) 740","apiVersion":"1.3.277","maxImageDimension2D":16384,"maxStorageBufferMb":512,"maxComputeSharedKb":32,"maxDescriptorSets":8,"extFp16Int8":true,"extDescriptorIndexing":true,"extBufferDeviceAddress":true,"extRobustness2":true,"bigCores":4,"totalCores":8}"""
        )
        assertTrue(parsed.available)
        assertEquals("Adreno (TM) 740", parsed.deviceName)
        assertEquals("1.3.277", parsed.apiVersion)
        assertEquals(16384, parsed.maxImageDimension2D)
        assertTrue(parsed.extFp16Int8)
        assertTrue(parsed.extRobustness2)
        assertEquals(4, parsed.bigCores)
    }

    @Test
    fun `v46 upload mime normalizer prevents greyed-out supported files`() {
        val customExt = com.fable.browser.util.UploadFiles.normalizeMimeTypes(arrayOf(".gguf"))
        assertEquals("*/*", customExt.single())

        val docAndVideo = com.fable.browser.util.UploadFiles.normalizeMimeTypes(arrayOf(".mp4,.json,.zip"))
        assertTrue(docAndVideo.contains("video/mp4"))
        assertTrue(docAndVideo.contains("application/json"))
        assertTrue(docAndVideo.contains("application/zip"))
        assertTrue(docAndVideo.contains("application/octet-stream"))
    }
}
