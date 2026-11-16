package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.DefaultBrowserEngine
import com.fable.browser.engine.FableWebGpuManager
import com.fable.browser.engine.WebGpuPowerProfile
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

@Composable
fun HybridBetaSettingsScreen(
    onBack: () -> Unit,
    onOpenRecorder: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val gpuTelemetry by FableWebGpuManager.telemetry.collectAsStateWithLifecycle()
    val vkInfo = remember { FableWebGpuManager.vulkanHardware }
    val context = LocalContext.current
    var domainsText by remember { mutableStateOf(s.hybridCustomDomains) }
    LaunchedEffect(s.hybridCustomDomains) {
        domainsText = s.hybridCustomDomains
    }

    SettingsPage(title = "Beta: Blink × Gecko & WebGPU Core", onBack = onBack, viewModel = viewModel) {
        // --- Live Hardware Vulkan & WebGPU Diagnostics + Benchmark Card ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "WebGPU & Vulkan Hardware Hyper-Core v46",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("GPU: ${vkInfo.deviceName}")
                        if (vkInfo.available) append(" (Vulkan ${vkInfo.apiVersion})")
                        append("\nCPU Big/Total Cores: ${vkInfo.bigCores}/${vkInfo.totalCores} · Max 2D Tex: ${vkInfo.maxImageDimension2D}px")
                        if (vkInfo.available) {
                            val vkExts = listOfNotNull(
                                if (vkInfo.extFp16Int8) "FP16/Int8" else null,
                                if (vkInfo.extDescriptorIndexing) "DescIndexing" else null,
                                if (vkInfo.extRobustness2) "Robustness2(FastIndirect)" else null,
                                if (vkInfo.extBufferDeviceAddress) "BufferAddr" else null
                            )
                            if (vkExts.isNotEmpty()) append("\nVulkan Hardware Features: ${vkExts.joinToString(", ")}")
                        }
                        append("\nWebGPU Adapter: ${gpuTelemetry.adapterSummary}")
                        append("\nBackend Tier: ${gpuTelemetry.backendMode} · Format: ${gpuTelemetry.preferredCanvasFormat}")
                        if (gpuTelemetry.unlockedFeatures.isNotEmpty()) {
                            append("\nUnlocked WebGPU Features (${gpuTelemetry.unlockedFeatures.size}): ${gpuTelemetry.unlockedFeatures.take(6).joinToString(", ")}")
                        }
                        append("\nUID Cache Hits: ${gpuTelemetry.shadersCached} WGSL · ${gpuTelemetry.renderPipelinesCached} Render · ${gpuTelemetry.computePipelinesCached} Compute · ${gpuTelemetry.opaqueSwapchainHits} Opaque")
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (gpuTelemetry.lastBenchmarkSummary.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = gpuTelemetry.lastBenchmarkSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.runWebGpuBenchmarkNow(context) },
                        enabled = !gpuTelemetry.benchmarkRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (gpuTelemetry.benchmarkRunning) "Benchmarking..." else "⚡ Benchmark WebGPU")
                    }
                    OutlinedButton(
                        onClick = { viewModel.exportGpuPerformanceReport(context) }
                    ) {
                        Text("Export GPU Report")
                    }
                }
            }
        }

        SectionHeader("Default Browser Engine")
        RadioGroup(
            options = DefaultBrowserEngine.entries.toList(),
            selected = s.defaultEngine,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::setDefaultEngine
        )
        ListDivider()

        SectionHeader("WebGPU & Bloxd Graphics Power Profile")
        RadioGroup(
            options = WebGpuPowerProfile.entries.toList(),
            selected = s.hybridWebGpuProfile,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::setHybridWebGpuProfile
        )
        SwitchRow(
            title = "WebGPU Vulkan Hyper-Core & UID Pipeline Cache",
            subtitle = "Synchronous DOCUMENT_START hook, 3-tier adapter cascade (High-Perf -> Core -> Compatibility), unlocks shader-f16/subgroups/ASTC/ETC2, polyfills requestAdapterInfo(), and collision-free UID pipeline cache",
            checked = s.hybridWebGpuBoost,
            onCheckedChange = viewModel::setHybridWebGpuBoost
        )
        SwitchRow(
            title = "Bypass expensive 4x MSAA on High-DPI 3D game canvases",
            subtitle = "Saves 35-50% fragment GPU bandwidth in Bloxd & 3D WebGL2 games on sharp High-DPI mobile screens (DPR >= 1.75) while keeping Adreno GMEM / Mali Tile-RAM fast resolve",
            checked = s.hybridDisableGameMsaa,
            onCheckedChange = viewModel::setHybridDisableGameMsaa
        )
        ListDivider()

        SectionHeader("Master Engine Mode")
        SwitchRow(
            title = "Enable GeckoView + Blink Combined Engine (Beta)",
            subtitle = "Runs GeckoView for privacy, tracker blocking, local-IP hiding, and Super/Incognito identity while running Chromium Blink for WebGPU, 3D WebGL, 2D Canvas, WebAssembly, 90/120 Hz gaming, and fast UI/font layout",
            checked = s.hybridGeckoBlinkBeta,
            icon = Icons.Outlined.Speed,
            onCheckedChange = viewModel::setHybridGeckoBlinkBeta
        )
        SwitchRow(
            title = "Auto-combine only on 3D games & benchmarks",
            subtitle = if (s.hybridAutoForGames)
                "ON: Automatically activates Combined Blink + Gecko on listed game/benchmark domains while keeping other sites on GeckoView unless toggled in the menu"
            else
                "OFF: Runs EVERY website and tab in the Combined Blink + Gecko Engine by default",
            checked = s.hybridAutoForGames,
            enabled = s.hybridGeckoBlinkBeta,
            icon = Icons.Outlined.Tune,
            onCheckedChange = viewModel::setHybridAutoForGames
        )
        SwitchRow(
            title = "Hibernate background GeckoView surface while Blink renders",
            subtitle = "Releases GeckoView's hidden GPU surface and pauses background Gecko layout while Blink is active, freeing RAM and GPU bandwidth while keeping Gecko privacy rules active",
            checked = s.hybridHibernateGecko,
            onCheckedChange = viewModel::setHybridHibernateGecko
        )
        SwitchRow(
            title = "Mirror Blink inputs to background GeckoView (Experimental)",
            subtitle = "Optional two-account mode: keeps GeckoView active alongside Blink with separate cookies and logins, while mirroring touch, camera, scroll, mouse, and keyboard inputs to both engines simultaneously",
            checked = s.hybridMirrorInputToGecko,
            enabled = s.hybridGeckoBlinkBeta,
            onCheckedChange = viewModel::setHybridMirrorInputToGecko
        )
        SwitchRow(
            title = "Save per-tab & per-site Beta state across app updates",
            subtitle = "Remembers which sites/tabs you toggled into or out of Combined Blink + Gecko mode even after updating or restarting the app",
            checked = s.hybridPersistTabMode,
            onCheckedChange = viewModel::setHybridPersistTabMode
        )
        SettingRow(
            title = "Запись экрана · Zero-Lag Screen Recorder",
            subtitle = "${s.recResolution.label} · ${s.recBitrate.label} · ${s.recFrameRate.label} · ${s.recAudioSource.label}",
            icon = Icons.Outlined.Videocam,
            onClick = onOpenRecorder
        )
        SettingRow(
            title = "Reset saved per-site Beta overrides",
            subtitle = "Clear manual per-site overrides so all tabs follow the automatic rules above",
            icon = Icons.Outlined.Restore,
            onClick = { viewModel.resetHybridSiteOverrides(context) }
        )
        ListDivider()

        SectionHeader("Auto-Combined Domains (Games & Benchmarks)")
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = domainsText,
                onValueChange = { domainsText = it },
                label = { Text("Comma-separated domains for automatic Combined Engine") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { viewModel.setHybridCustomDomains(domainsText) }) {
                    Text("Save domains")
                }
            }
        }
        ListDivider()

        SectionHeader("CPU, Big-Core Affinity & Dawn/Vulkan Thread Stabilizer")
        SwitchRow(
            title = "Native C++ Big-Core Affinity & Dawn/Vulkan GPU Priority -20",
            subtitle = "Pins CrRendererMain, CrGpuMain, VizCompositor, Dawn, Vulkan, RenderThread, and GeckoMain to high-clock big cores via sched_setaffinity and priority -20 in C++",
            checked = s.hybridNativeCpuBoost,
            icon = Icons.Outlined.Memory,
            onCheckedChange = viewModel::setHybridNativeCpuBoost
        )
        SwitchRow(
            title = "Teleport & heavy-load lag-spike eliminator",
            subtitle = "Eliminates lag spikes during teleports, chunk bursts, and crowded multiplayer scenes using async texture decoding, KHR_parallel_shader_compile, and C++ worker boosting",
            checked = s.hybridTeleportStabilizer,
            onCheckedChange = viewModel::setHybridTeleportStabilizer
        )
        SwitchRow(
            title = "1 ns Kernel Timer Slack (PR_SET_TIMERSLACK)",
            subtitle = "Eliminates Android's default 50,000 ns timer slack so setTimeout(0) and requestAnimationFrame fire with microsecond precision",
            checked = s.hybridZeroTimerSlack,
            onCheckedChange = viewModel::setHybridZeroTimerSlack
        )
        SliderRow(
            title = "Reported CPU cores for WebAssembly game workers",
            value = s.hybridReportedCores,
            range = 2f..16f,
            steps = 13,
            suffix = " cores",
            onValueChange = viewModel::setHybridReportedCores
        )
        SwitchRow(
            title = "Offscreen tile pre-rasterization",
            subtitle = "Pre-rasterizes offscreen tiles in Blink to prevent checkerboarding and camera/scroll hitches",
            checked = s.hybridOffscreenPreRaster,
            onCheckedChange = viewModel::setHybridOffscreenPreRaster
        )
        SwitchRow(
            title = "Native C++ same-origin, 3D asset & font CDN fast-path",
            subtitle = "Approves same-origin assets, .wasm/.glb/.ktx2/.wgsl/.woff2 files, and font/game CDNs in C++ in nanoseconds without Kotlin allocations",
            checked = s.hybridCppFastAssetBypass,
            onCheckedChange = viewModel::setHybridCppFastAssetBypass
        )
        ListDivider()

        SectionHeader("3D WebGL2, 2D Canvas, Camera & Zero-Cost Clicks")
        SwitchRow(
            title = "Force High-Performance 3D WebGL / WebGL2 Tile-Memory Context",
            subtitle = "Promotes canvas.getContext('webgl' / 'webgl2') to powerPreference: 'high-performance', preserveDrawingBuffer: false, and pre-warms KHR_parallel_shader_compile + ASTC/ETC2",
            checked = s.hybridHighPerfWebGl,
            onCheckedChange = viewModel::setHybridHighPerfWebGl
        )
        SwitchRow(
            title = "3D WebGL2 VAO/state deduplication & 2D Canvas GPU/text cache",
            subtitle = "Deduplicates redundant VAO, program, texture, viewport, scissor, depth, and blend calls while keeping 2D HUD canvases (Bloxd FPS) crisp",
            checked = s.hybrid3d2dAccel,
            onCheckedChange = viewModel::setHybrid3d2dAccel
        )
        SwitchRow(
            title = "Camera anti-shutter & multi-touch ID lock",
            subtitle = "Locks joystick (Touch 0) and camera (Touch 1) IDs from touchstart to touchend, blocks synthetic mousemove double-accumulation, and never drops non-zero camera deltas",
            checked = s.hybridCameraAntiShutter,
            onCheckedChange = viewModel::setHybridCameraAntiShutter
        )
        SwitchRow(
            title = "Ultra-fast multi-click (20+ CPS zero FPS drop)",
            subtitle = "Disables Android's double-tap zoom and long-press detectors, caches canvas getBoundingClientRect/getComputedStyle, and suppresses synthetic hover raycasts so clicking eats 0 FPS",
            checked = s.hybridZeroDelayClicks,
            onCheckedChange = viewModel::setHybridZeroDelayClicks
        )
        SliderRow(
            title = "Canvas layout cache duration (getBoundingClientRect)",
            value = s.hybridCanvasRectCacheMs,
            range = 0f..1000f,
            steps = 19,
            suffix = " ms",
            onValueChange = viewModel::setHybridCanvasRectCacheMs
        )
        SwitchRow(
            title = "Suppress redundant post-render physics timer",
            subtitle = "Skips Bloxd's redundant post-render setTimeout(ip, 0) call so physics ticks stay strictly VSync-aligned inside requestAnimationFrame",
            checked = s.hybridSuppressPostRenderTimer,
            onCheckedChange = viewModel::setHybridSuppressPostRenderTimer
        )
        SwitchRow(
            title = "Instant UI & font rendering accelerator (font-display: swap)",
            subtitle = "Accelerates basic UI layout, applies text-rendering: optimizeSpeed and font-display: swap, and preconnects to Google Fonts, Cloudflare, and jsDelivr",
            checked = s.hybridFastFontSwap,
            onCheckedChange = viewModel::setHybridFastFontSwap
        )
        ListDivider()

        SectionHeader("GeckoView Privacy Core Inside Blink")
        SwitchRow(
            title = "GeckoView filter-list tracker blocking in Blink",
            subtitle = "Filters every Blink subresource request through Fable's EasyList + EasyPrivacy + NativeFastEngine blocker",
            checked = s.hybridGeckoAdBlock,
            icon = Icons.Outlined.Security,
            onCheckedChange = viewModel::setHybridGeckoAdBlock
        )
        SwitchRow(
            title = "WebRTC local-IP hiding in Blink",
            subtitle = "Strips host, .local, and RFC1918 LAN ICE candidates from RTCPeerConnection and SDP offers in Blink while keeping multiplayer game connections working",
            checked = s.hybridHideLocalIp,
            icon = Icons.Outlined.Security,
            onCheckedChange = viewModel::setHybridHideLocalIp
        )
        ListDivider()
    }
}
