package com.fable.browser.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.ui.components.ListDivider
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
    val context = LocalContext.current
    var domainsText by remember { mutableStateOf(s.hybridCustomDomains) }
    LaunchedEffect(s.hybridCustomDomains) {
        domainsText = s.hybridCustomDomains
    }

    SettingsPage(title = "Beta: Gecko + Blink Engine", onBack = onBack, viewModel = viewModel) {
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

        SectionHeader("CPU, Big-Core Affinity & Teleport Heavy-Load Stabilizer")
        SwitchRow(
            title = "Native C++ Big-Core (Cortex-A75) Affinity & Priority -18",
            subtitle = "Pins CrRendererMain, CrGpuMain, VizCompositor, RenderThread, and GeckoMain to 2.0 GHz Cortex-A75 big cores via sched_setaffinity and priority -18 in C++",
            checked = s.hybridNativeCpuBoost,
            icon = Icons.Outlined.Memory,
            onCheckedChange = viewModel::setHybridNativeCpuBoost
        )
        SwitchRow(
            title = "Teleport & heavy-load lag-spike eliminator",
            subtitle = "Eliminates lag spikes during teleports, chunk bursts, and crowded multiplayer scenes using async texture decoding, frame-budget idle scheduling, and C++ worker thread boosting",
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

        SectionHeader("WebGPU, 3D WebGL, 2D Canvas, Camera & Zero-Cost Clicks")
        SwitchRow(
            title = "WebGPU high-performance adapter & WGSL shader cache",
            subtitle = "Forces high-performance WebGPU adapters, unlocks hardware buffer/texture limits, caches compiled WGSL shader modules, and auto-recovers on device loss",
            checked = s.hybridWebGpuBoost,
            onCheckedChange = viewModel::setHybridWebGpuBoost
        )
        SwitchRow(
            title = "Force High-Performance 3D WebGL / WebGL2 context",
            subtitle = "Promotes canvas.getContext('webgl' / 'webgl2') to powerPreference: 'high-performance' and disables forced MSAA on heavy 3D games",
            checked = s.hybridHighPerfWebGl,
            onCheckedChange = viewModel::setHybridHighPerfWebGl
        )
        SwitchRow(
            title = "3D WebGL state deduplication & 2D Canvas GPU/text cache",
            subtitle = "Caches WebGL getParameter/getExtension calls, deduplicates redundant GL state/program binds, GPU-accelerates 2D canvases, and caches 2D measureText",
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
