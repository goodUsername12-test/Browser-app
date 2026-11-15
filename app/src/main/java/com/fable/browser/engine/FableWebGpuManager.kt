package com.fable.browser.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fable.browser.data.prefs.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

enum class WebGpuPowerProfile(val wire: String, val label: String, val summary: String) {
    ULTRA_VULKAN_MAX(
        wire = "ultra",
        label = "Ultra Vulkan / Gaming Max (Recommended)",
        summary = "Forces high-performance GPU adapter, unlocks shader-f16 + subgroups + ASTC/ETC2, UID-caches WGSL & pipelines, uses opaque swapchain scanout & bypasses expensive High-DPI 3D MSAA"
    ),
    BALANCED_ADAPTIVE(
        wire = "balanced",
        label = "Balanced Adaptive",
        summary = "High-performance WebGPU & pipeline cache while keeping default canvas antialiasing and thermal headroom"
    ),
    COMPATIBILITY_SAFE(
        wire = "compat",
        label = "Compatibility + OpenGL/WebGL2 Fallback",
        summary = "Prefers WebGPU compatibility featureLevel and conservative limits for finicky drivers"
    )
}

data class VulkanHardwareInfo(
    val available: Boolean = false,
    val deviceName: String = "Probing GPU...",
    val apiVersion: String = "0.0.0",
    val maxImageDimension2D: Int = 4096,
    val maxStorageBufferMb: Long = 128L,
    val maxComputeSharedKb: Int = 16,
    val maxDescriptorSets: Int = 4,
    val extFp16Int8: Boolean = false,
    val extDescriptorIndexing: Boolean = false,
    val extBufferDeviceAddress: Boolean = false,
    val extRobustness2: Boolean = false,
    val bigCores: Int = 2,
    val totalCores: Int = 8
)

data class WebGpuLiveTelemetry(
    val webGpuAvailableInWebView: Boolean? = null,
    val adapterSummary: String = "Open a WebGPU page or tap 'Run Live Benchmark'",
    val backendMode: String = "Auto (Dawn -> Vulkan / GLES)",
    val preferredCanvasFormat: String = "rgba8unorm",
    val unlockedFeatures: List<String> = emptyList(),
    val shadersCached: Int = 0,
    val renderPipelinesCached: Int = 0,
    val computePipelinesCached: Int = 0,
    val opaqueSwapchainHits: Int = 0,
    val deviceLosses: Int = 0,
    val lastBenchmarkSummary: String = "",
    val benchmarkRunning: Boolean = false
)

object FableWebGpuManager {
    private val mainHandler = Handler(Looper.getMainLooper())

    val vulkanHardware: VulkanHardwareInfo by lazy {
        parseVulkanHardwareJson(NativeFastEngine.probeVulkanHardwareJson())
    }

    private val _telemetry = MutableStateFlow(WebGpuLiveTelemetry())
    val telemetry: StateFlow<WebGpuLiveTelemetry> = _telemetry.asStateFlow()

    fun parseVulkanHardwareJson(raw: String): VulkanHardwareInfo = runCatching {
        val o = JSONObject(raw)
        VulkanHardwareInfo(
            available = o.optBoolean("available", false),
            deviceName = o.optString("deviceName", "System GPU").ifBlank { "System GPU" },
            apiVersion = o.optString("apiVersion", "0.0.0"),
            maxImageDimension2D = o.optInt("maxImageDimension2D", 4096),
            maxStorageBufferMb = o.optLong("maxStorageBufferMb", 128L),
            maxComputeSharedKb = o.optInt("maxComputeSharedKb", 16),
            maxDescriptorSets = o.optInt("maxDescriptorSets", 4),
            extFp16Int8 = o.optBoolean("extFp16Int8", false),
            extDescriptorIndexing = o.optBoolean("extDescriptorIndexing", false),
            extBufferDeviceAddress = o.optBoolean("extBufferDeviceAddress", false),
            extRobustness2 = o.optBoolean("extRobustness2", false),
            bigCores = o.optInt("bigCores", 2),
            totalCores = o.optInt("totalCores", Runtime.getRuntime().availableProcessors())
        )
    }.getOrDefault(VulkanHardwareInfo())

    fun onWebGpuTelemetryJson(rawJson: String?) {
        val txt = rawJson?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val o = JSONObject(txt)
            val feats = ArrayList<String>()
            val arr = o.optJSONArray("features")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val f = arr.optString(i)
                    if (f.isNotBlank()) feats.add(f)
                }
            }
            _telemetry.update { cur ->
                cur.copy(
                    webGpuAvailableInWebView = o.optBoolean("supported", cur.webGpuAvailableInWebView ?: true),
                    adapterSummary = o.optString("adapter", cur.adapterSummary).ifBlank { cur.adapterSummary },
                    backendMode = o.optString("backendMode", cur.backendMode).ifBlank { cur.backendMode },
                    preferredCanvasFormat = o.optString("format", cur.preferredCanvasFormat).ifBlank { cur.preferredCanvasFormat },
                    unlockedFeatures = if (feats.isNotEmpty()) feats else cur.unlockedFeatures,
                    shadersCached = maxOf(cur.shadersCached, o.optInt("shadersCached", cur.shadersCached)),
                    renderPipelinesCached = maxOf(cur.renderPipelinesCached, o.optInt("renderPipesCached", cur.renderPipelinesCached)),
                    computePipelinesCached = maxOf(cur.computePipelinesCached, o.optInt("computePipesCached", cur.computePipelinesCached)),
                    opaqueSwapchainHits = maxOf(cur.opaqueSwapchainHits, o.optInt("opaqueHits", cur.opaqueSwapchainHits)),
                    deviceLosses = cur.deviceLosses + o.optInt("deviceLossDelta", 0)
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "RequiresFeature")
    fun installDocumentStartBootstrap(webView: WebView, script: String) {
        if (script.isBlank()) return
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val existing = webView.getTag(DOC_START_HANDLE_TAG) as? androidx.webkit.ScriptHandler
                existing?.remove()
                val handle = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                webView.setTag(DOC_START_HANDLE_TAG, handle)
            }
        }
    }

    private const val DOC_START_HANDLE_TAG = 0x7F0B4601

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun runLiveWebGpuBenchmark(
        context: Context,
        settings: Settings
    ): String {
        _telemetry.update { it.copy(benchmarkRunning = true, lastBenchmarkSummary = "Running real WebGPU compute + pipeline cache benchmark...") }
        NativeFastEngine.notifyHeavyLoadBurst()

        val benchResult = withTimeoutOrNull(12_000L) {
            withContext(Dispatchers.Main) {
                var benchWv: WebView? = null
                try {
                    suspendCancellableCoroutine<String> { cont ->
                        val wv = WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                        }
                        benchWv = wv
                        val boot = HybridEngineBridge.buildCombinedBootstrapScript(
                            settings = settings,
                            isIncognito = false,
                            isSuper = false,
                            superIdentity = null,
                            pageUrl = "https://webgpu.fable.benchmark/"
                        )
                        installDocumentStartBootstrap(wv, boot)
                        wv.addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onWebGpuTelemetry(json: String?) = onWebGpuTelemetryJson(json)

                                @JavascriptInterface
                                fun onBenchComplete(report: String?) {
                                    if (cont.isActive) {
                                        cont.resume(report ?: "Benchmark returned empty result.")
                                    }
                                }
                            },
                            "FableHybridBridge"
                        )
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                view.evaluateJavascript(boot, null)
                                view.evaluateJavascript(LIVE_WEBGPU_BENCH_JS, null)
                            }
                        }
                        wv.loadDataWithBaseURL(
                            "https://webgpu.fable.benchmark/",
                            "<!DOCTYPE html><html><body><canvas id='c' width='512' height='512'></canvas></body></html>",
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                } finally {
                    benchWv?.let { w ->
                        runCatching {
                            w.stopLoading()
                            w.destroy()
                        }
                    }
                }
            }
        } ?: "WebGPU benchmark timed out after 12s."

        _telemetry.update { it.copy(benchmarkRunning = false, lastBenchmarkSummary = benchResult) }
        return benchResult
    }

    suspend fun exportSanitizedPerformanceReport(
        context: Context,
        settings: Settings
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val vk = vulkanHardware
            val tel = _telemetry.value
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val fileName = "fable-gpu-perf-report-$stamp.zip"
            val tmpZip = File(context.cacheDir, fileName)

            val vkJson = JSONObject()
                .put("vulkanAvailable", vk.available)
                .put("gpuDeviceName", vk.deviceName)
                .put("vulkanApiVersion", vk.apiVersion)
                .put("maxImageDimension2D", vk.maxImageDimension2D)
                .put("maxStorageBufferMb", vk.maxStorageBufferMb)
                .put("maxComputeSharedKb", vk.maxComputeSharedKb)
                .put("maxDescriptorSets", vk.maxDescriptorSets)
                .put("VK_KHR_shader_float16_int8", vk.extFp16Int8)
                .put("VK_EXT_descriptor_indexing", vk.extDescriptorIndexing)
                .put("VK_KHR_buffer_device_address", vk.extBufferDeviceAddress)
                .put("VK_EXT_robustness2", vk.extRobustness2)
                .put("bigCpuCores", vk.bigCores)
                .put("totalCpuCores", vk.totalCores)

            val telJson = JSONObject()
                .put("webGpuSupportedInWebView", tel.webGpuAvailableInWebView)
                .put("adapterSummary", tel.adapterSummary)
                .put("backendMode", tel.backendMode)
                .put("preferredCanvasFormat", tel.preferredCanvasFormat)
                .put("unlockedFeatures", JSONArray(tel.unlockedFeatures))
                .put("shadersCached", tel.shadersCached)
                .put("renderPipelinesCached", tel.renderPipelinesCached)
                .put("computePipelinesCached", tel.computePipelinesCached)
                .put("opaqueSwapchainHits", tel.opaqueSwapchainHits)
                .put("deviceLosses", tel.deviceLosses)
                .put("lastBenchmarkSummary", tel.lastBenchmarkSummary)
                .put("estimatedFps", FableUnifiedEngine.estimatedFps.value)

            val devJson = JSONObject()
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("hardware", Build.HARDWARE)
                .put("socModel", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown")
                .put("totalRamMb", MemoryPolicy.totalRamMb(context))
                .put("targetRefreshRateHz", com.fable.browser.util.HighRefreshRateEnforcer.targetRefreshRate)

            val gfxJson = JSONObject()
                .put("defaultEngine", settings.defaultEngine.name)
                .put("hybridGeckoBlinkBeta", settings.hybridGeckoBlinkBeta)
                .put("webGpuBoost", settings.hybridWebGpuBoost)
                .put("webGpuProfile", settings.hybridWebGpuProfile.name)
                .put("disableHighDpiGameMsaa", settings.hybridDisableGameMsaa)
                .put("highPerfWebGl", settings.hybridHighPerfWebGl)
                .put("accel3d2d", settings.hybrid3d2dAccel)
                .put("gameMaxFps", settings.batteryGameMaxFps)
                .put("renderScalePct", settings.batteryGameRenderScale)

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpZip))).use { zos ->
                fun writeJsonEntry(entryName: String, obj: JSONObject) {
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(obj.toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                writeJsonEntry("vulkan-hardware.json", vkJson)
                writeJsonEntry("webgpu-telemetry.json", telJson)
                writeJsonEntry("device-cpu-ram.json", devJson)
                writeJsonEntry("fable-graphics-settings.json", gfxJson)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        tmpZip.inputStream().use { inp -> inp.copyTo(out, 64 * 1024) }
                    }
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
            }
            tmpZip.delete()
            fileName
        }.getOrNull()
    }

    private const val LIVE_WEBGPU_BENCH_JS = """
        (async function() {
            const done = function(msg) {
                try { window.FableHybridBridge.onBenchComplete(msg); } catch (_) {}
            };
            try {
                if (!navigator.gpu || typeof navigator.gpu.requestAdapter !== "function") {
                    try {
                        window.FableHybridBridge.onWebGpuTelemetry(JSON.stringify({
                            supported: false,
                            adapter: "WebGPU disabled in current Android System WebView (WebGL2 Ultra active)",
                            backendMode: "WebGL2 Hardware Fallback"
                        }));
                    } catch (_) {}
                    done("WebGPU API is not exposed by this device's Android System WebView version; Fable WebGL2 Ultra + Vulkan Thread Boost is active.");
                    return;
                }
                const t0 = performance.now();
                const adapter = await navigator.gpu.requestAdapter({ powerPreference: "high-performance" });
                if (!adapter) {
                    done("navigator.gpu exists, but requestAdapter returned null on this GPU driver (WebGL2 Ultra Fallback active).");
                    return;
                }
                const device = await adapter.requestDevice();
                const initMs = (performance.now() - t0).toFixed(1);
                const info = adapter.info || (device && device.adapterInfo) || {};
                const gpuLabel = [info.vendor, info.architecture, info.device || info.description].filter(Boolean).join(" / ") || "Hardware WebGPU Adapter";
                const feats = Array.from(device.features || []);

                const wgsl = `
                    @group(0) @binding(0) var<storage, read_write> data: array<f32>;
                    @compute @workgroup_size(64)
                    fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
                        let i = gid.x;
                        var v = data[i] + 1.0;
                        for (var k: u32 = 0u; k < 256u; k = k + 1u) {
                            v = fma(v, 1.0001, 0.0003);
                        }
                        data[i] = v;
                    }
                `;

                const tc0 = performance.now();
                const mod1 = device.createShaderModule({ code: wgsl });
                const pipe1 = await device.createComputePipelineAsync({
                    layout: "auto",
                    compute: { module: mod1, entryPoint: "main" }
                });
                const coldMs = (performance.now() - tc0).toFixed(2);

                const th0 = performance.now();
                const mod2 = device.createShaderModule({ code: wgsl });
                await device.createComputePipelineAsync({
                    layout: "auto",
                    compute: { module: mod2, entryPoint: "main" }
                });
                const cachedMs = (performance.now() - th0).toFixed(3);

                const count = 65536;
                const buf = device.createBuffer({
                    size: count * 4,
                    usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST
                });
                const bg = device.createBindGroup({
                    layout: pipe1.getBindGroupLayout(0),
                    entries: [{ binding: 0, resource: { buffer: buf } }]
                });

                const td0 = performance.now();
                const enc = device.createCommandEncoder();
                const pass = enc.beginComputePass();
                pass.setPipeline(pipe1);
                pass.setBindGroup(0, bg);
                for (let d = 0; d < 8; d++) {
                    pass.dispatchWorkgroups(count / 64);
                }
                pass.end();
                device.queue.submit([enc.finish()]);
                if (device.queue.onSubmittedWorkDone) {
                    await device.queue.onSubmittedWorkDone();
                }
                const computeMs = Math.max(0.05, performance.now() - td0);
                const totalFmaOps = count * 256 * 2 * 8;
                const gops = ((totalFmaOps / (computeMs / 1000.0)) / 1e9).toFixed(2);
                buf.destroy();

                const canvas = document.getElementById("c");
                const ctx = canvas.getContext("webgpu");
                const fmt = navigator.gpu.getPreferredCanvasFormat();
                if (ctx) {
                    ctx.configure({ device: device, format: fmt, alphaMode: "opaque" });
                }

                done(
                    "✅ WebGPU Ready (" + gpuLabel + ") · Init: " + initMs + "ms · " +
                    "WGSL Cold: " + coldMs + "ms vs Cached: " + cachedMs + "ms · " +
                    "Compute: " + gops + " GFLOPS · Swapchain: " + fmt + " (opaque) · " +
                    "Unlocked (" + feats.length + "): " + (feats.slice(0, 6).join(", ") || "core")
                );
            } catch (err) {
                done("Benchmark error: " + (err && err.message ? err.message : String(err)));
            }
        })();
    """
}
