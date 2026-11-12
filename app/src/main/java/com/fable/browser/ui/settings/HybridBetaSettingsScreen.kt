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
            subtitle = "Runs GeckoView for privacy, tracker blocking, local-IP hiding, and Super/Incognito identity while running Chromium Blink for 3D WebGL, WebAssembly, 90/120 Hz gaming, and fast DOM/font layout",
            checked = s.hybridGeckoBlinkBeta,
            icon = Icons.Outlined.Speed,
            onCheckedChange = viewModel::setHybridGeckoBlinkBeta
        )
        SwitchRow(
            title = "Auto-combine only on 3D games & benchmarks",
            subtitle = if (s.hybridAutoForGames)
                "ON: Automatically activates Combined Blink + Gecko on listed game/benchmark domains (Bloxd, Speedometer, Poki, CrazyGames, etc.) while keeping other sites on GeckoView unless toggled in the menu"
            else
                "OFF: Runs EVERY website and tab in the Combined Blink + Gecko Engine by default",
            checked = s.hybridAutoForGames,
            enabled = s.hybridGeckoBlinkBeta,
            icon = Icons.Outlined.Tune,
            onCheckedChange = viewModel::setHybridAutoForGames
        )
        SwitchRow(
            title = "Save per-tab & per-site Beta state across app updates",
            subtitle = "Remembers which sites/tabs you toggled into or out of Combined Blink + Gecko mode even after updating or restarting the app",
            checked = s.hybridPersistTabMode,
            onCheckedChange = viewModel::setHybridPersistTabMode
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

        SectionHeader("CPU, Scheduler & Speed Boost (mt6768 / Cortex-A75 Tuned)")
        SwitchRow(
            title = "Native C++ Big-Core (Cortex-A75) Thread Boost",
            subtitle = "Boosts CrRendererMain, CrGpuMain, VizCompositor, GeckoMain, and UI threads to priority -16 in C++ (libfable_fast.so) so Android schedules them on your 2.0 GHz Cortex-A75 big cores",
            checked = s.hybridNativeCpuBoost,
            icon = Icons.Outlined.Memory,
            onCheckedChange = viewModel::setHybridNativeCpuBoost
        )
        SwitchRow(
            title = "1 ns Kernel Timer Slack (PR_SET_TIMERSLACK)",
            subtitle = "Eliminates Android's default 50,000 ns timer slack so setTimeout(0) and requestAnimationFrame fire with microsecond precision in games and Speedometer 3.0",
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
            title = "Native C++ same-origin & font CDN fast-path",
            subtitle = "Approves same-origin game/benchmark assets and Google Fonts in C++ in nanoseconds without Kotlin allocations",
            checked = s.hybridCppFastAssetBypass,
            onCheckedChange = viewModel::setHybridCppFastAssetBypass
        )
        ListDivider()

        SectionHeader("3D Graphics, Camera Anti-Shutter & High-CPS Clicks")
        SwitchRow(
            title = "Force High-Performance WebGL / WebGPU context",
            subtitle = "Promotes canvas.getContext('webgl' / 'webgl2') to powerPreference: 'high-performance' for maximum Mali-G52 GPU clock speed",
            checked = s.hybridHighPerfWebGl,
            onCheckedChange = viewModel::setHybridHighPerfWebGl
        )
        SwitchRow(
            title = "Camera anti-shutter & multi-touch ID lock",
            subtitle = "Locks joystick (Touch 0) and camera (Touch 1) IDs from touchstart to touchend, blocks synthetic mousemove double-accumulation, and never drops non-zero camera deltas",
            checked = s.hybridCameraAntiShutter,
            onCheckedChange = viewModel::setHybridCameraAntiShutter
        )
        SwitchRow(
            title = "Ultra-fast multi-click (20+ CPS zero delay)",
            subtitle = "Disables Android's 300 ms double-tap zoom gesture recognizer, suppresses dblclick/contextmenu stalls, and caches canvas dimensions so rapid clicking eats 0 FPS",
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
            subtitle = "Skips Bloxd's redundant post-render setTimeout(ip, 0) call so physics ticks stay strictly VSync-aligned inside requestAnimationFrame without mid-frame lag spikes",
            checked = s.hybridSuppressPostRenderTimer,
            onCheckedChange = viewModel::setHybridSuppressPostRenderTimer
        )
        SwitchRow(
            title = "Instant font swap (font-display: swap) & CDN preconnect",
            subtitle = "Renders text immediately and preconnects to Google Fonts / GStatic so font loading never blocks game frames",
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
