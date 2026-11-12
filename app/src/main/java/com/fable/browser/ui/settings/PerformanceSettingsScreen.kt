package com.fable.browser.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.PerformanceProfile
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.data.model.MemoryMode
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    onOpenHybridBeta: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profile = settings.performanceProfile
    val profileOptions = if (profile == PerformanceProfile.CUSTOM) {
        PerformanceProfile.entries.toList()
    } else {
        PerformanceProfile.entries.filter { it != PerformanceProfile.CUSTOM }
    }

    val deviceContext = androidx.compose.ui.platform.LocalContext.current
    val weakDevice = remember { com.fable.browser.engine.MemoryPolicy.isWeakDevice(deviceContext) }
    val deviceRamGb = remember { com.fable.browser.engine.MemoryPolicy.totalRamMb(deviceContext) / 1024.0 }
    val deviceCores = remember { com.fable.browser.engine.MemoryPolicy.cpuCores() }

    SettingsPage(title = "Performance", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Optimizer")
        SwitchRow(
            title = "Weak device optimizer",
            subtitle = "This device: " + String.format(java.util.Locale.US, "%.1f", deviceRamGb) + " GB RAM, $deviceCores cores. " +
                if (weakDevice) "Detected as a weak device: tabs unload sooner, caches shrink, background tabs are throttled and prefetching stops"
                else "Fast enough, so the optimizer stays idle until memory runs low",
            checked = settings.optimizerEnabled,
            onCheckedChange = viewModel::setOptimizerEnabled
        )
        ListDivider()

        SectionHeader("Profile")
        RadioGroup(
            options = profileOptions,
            selected = profile,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::applyPerformanceProfile
        )
        ListDivider()

        SectionHeader("Profile switches")
        SwitchRow(
            title = "Filter-list blocking in normal tabs",
            subtitle = "Incognito tabs have their own switch in Settings > Incognito",
            checked = settings.adBlockEnabled,
            onCheckedChange = viewModel::setAdBlockEnabled
        )
        SwitchRow(
            title = "Fingerprint shield in normal tabs",
            subtitle = "Also cleans request headers. Can slow down games",
            checked = settings.normalTabShield,
            onCheckedChange = viewModel::setNormalTabShield
        )
        SwitchRow(
            title = "Strict tracking protection",
            subtitle = "Blocks more trackers. Some sites and game ads may break",
            checked = settings.strictTrackingProtection,
            onCheckedChange = viewModel::setStrictTrackingProtection
        )
        ListDivider()

        SectionHeader("Memory & speed")
        val context = androidx.compose.ui.platform.LocalContext.current
        val autoInfo = androidx.compose.runtime.remember { viewModel.autoMemoryInfo(context) }
        RadioGroup(
            options = MemoryMode.entries.toList(),
            selected = settings.memoryMode,
            label = {
                if (it == MemoryMode.AUTO) "Automatic (now: ${autoInfo.first.label}, ${(autoInfo.second + 512) / 1024} GB RAM)"
                else it.label
            },
            description = { it.summary },
            onSelect = viewModel::setMemoryMode
        )
        if (settings.memoryMode == MemoryMode.CUSTOM) {
            RadioGroup(
                options = TabUnloadMode.entries.toList(),
                selected = settings.tabUnloadMode,
                label = { it.label },
                description = { it.summary },
                onSelect = viewModel::setTabUnloadMode
            )
            if (settings.tabUnloadMode == TabUnloadMode.SMART) {
                SliderRow(
                    title = "Recent tabs kept loaded",
                    value = settings.keepLoadedTabs,
                    range = 1f..8f,
                    steps = 6,
                    onValueChange = viewModel::setKeepLoadedTabs
                )
            }
            SliderRow(
                title = "Unused tabs sleep after (0 = never)",
                value = settings.idleTabMinutes,
                range = 0f..60f,
                steps = 11,
                suffix = " min",
                onValueChange = viewModel::setIdleTabMinutes
            )
            SwitchRow(
                title = "Use more memory for speed",
                subtitle = "Bigger caches and instant Back/Forward. Best with 6 GB RAM or more",
                checked = settings.speedMemory,
                onCheckedChange = viewModel::setSpeedMemory
            )
        }
        SettingRow(
            title = "Keep a tab always instant",
            subtitle = "In the tab list, tap the pin on a tab. Pinned tabs never go to sleep, so switching to them is instant. Sleeping tabs reload when you open them"
        )
        ListDivider()

        SectionHeader("Cache")
        SwitchRow(
            title = "Clear cache automatically",
            subtitle = "Once a day when you leave the app, or sooner if it grows past 300 MB",
            checked = settings.autoClearCache,
            onCheckedChange = viewModel::setAutoClearCache
        )
        ListDivider()

        SectionHeader("Faster loading")
        SwitchRow(
            title = "Connect early to your search engine",
            subtitle = "Opens the connection when you tap the address bar, so results appear sooner. Normal tabs only",
            checked = settings.preconnectSearch,
            onCheckedChange = viewModel::setPreconnectSearch
        )
        SwitchRow(
            title = "Connect early to typed addresses",
            subtitle = "Connects to a site while you are still typing its address. Faster, but the site sees a connection even if you do not open it",
            checked = settings.preconnectTyped,
            onCheckedChange = viewModel::setPreconnectTyped
        )
        ListDivider()

        SectionHeader("Beta: GeckoView + Blink Combined Engine")
        SwitchRow(
            title = "GeckoView + Blink Combined Mode (Beta)",
            subtitle = "Runs GeckoView for privacy, tracker blocking, local-IP hiding, and Super/Incognito identity while using Chromium Blink simultaneously for 3D WebGL, WebAssembly, 90/120 Hz gaming, and fast font layout",
            checked = settings.hybridGeckoBlinkBeta,
            onCheckedChange = viewModel::setHybridGeckoBlinkBeta
        )
        SwitchRow(
            title = "Auto-combine on 3D game & benchmark sites",
            subtitle = "When Beta is on, automatically activates Combined Gecko + Blink on 3D WebGL games and benchmarks (Bloxd.io, Speedometer, Poki, CrazyGames, Krunker) while keeping normal pages on pure GeckoView unless toggled in the menu",
            checked = settings.hybridAutoForGames,
            onCheckedChange = viewModel::setHybridAutoForGames
        )
        SettingRow(
            title = "Edit all Beta Gecko + Blink settings",
            subtitle = "Configure custom domains, Cortex-A75 CPU boost, 1 ns timer slack, WebAssembly core count, camera anti-shutter, 20+ CPS clicks, and privacy controls",
            onClick = onOpenHybridBeta
        )
        ListDivider()

        SectionHeader("Gaming")
        SwitchRow(
            title = "Game mode in full screen",
            subtitle = "Gives the game all free memory and top priority so it does not crash while loading",
            checked = settings.gameMode,
            onCheckedChange = viewModel::setGameMode
        )
        SwitchRow(
            title = "Fix touch controls in games",
            subtitle = "Joystick + camera at the same time: each finger is measured on its own, and the browser never zooms or scrolls a game canvas. Fixes camera turning too fast or not at all",
            checked = settings.gameTouchFix,
            onCheckedChange = viewModel::setGameTouchFix
        )
        SwitchRow(
            title = "Highest screen refresh rate",
            subtitle = "Uses 90/120 Hz when your screen supports it for smoother games and scrolling",
            checked = settings.highRefreshRate,
            onCheckedChange = viewModel::setHighRefreshRate
        )
        SwitchRow(
            title = "Vibration for games",
            subtitle = "Lets pages use navigator.vibrate() for haptic feedback",
            checked = settings.vibrationApi,
            onCheckedChange = viewModel::setVibrationApi
        )
        ListDivider()

        SectionHeader("Stability")
        SwitchRow(
            title = "Separate graphics process",
            subtitle = "Off (recommended): no freezes when switching apps or opening the keyboard on some phones (Xiaomi), and less RAM. On: a graphics driver crash only blanks the page instead of closing the app. Restart the app to apply",
            checked = settings.gpuProcess,
            onCheckedChange = viewModel::setGpuProcess
        )
        SwitchRow(
            title = "Freeze detector",
            subtitle = "If the screen is stuck for more than 2.5 seconds, saves what it was waiting for and shows it in the next crash report",
            checked = settings.freezeDetector,
            onCheckedChange = viewModel::setFreezeDetector
        )
        ListDivider()

        SectionHeader("Optional web features (restart the app after changing)")
        SwitchRow(
            title = "WebGPU (experimental)",
            subtitle = "Next-generation 3D. Not finished on Android: some games may fail or crash with it on",
            checked = settings.featureWebGpu,
            onCheckedChange = viewModel::setFeatureWebGpu
        )
        SwitchRow(
            title = "Draft WebGL extensions",
            subtitle = "Extra 3D extensions that are still being standardised",
            checked = settings.featureWebGlDraft,
            onCheckedChange = viewModel::setFeatureWebGlDraft
        )
        SwitchRow(
            title = "Experimental WebAssembly",
            subtitle = "Relaxed SIMD and other new WebAssembly features used by some game engines",
            checked = settings.featureWasmExperimental,
            onCheckedChange = viewModel::setFeatureWasmExperimental
        )
        SwitchRow(
            title = "Battery Status API",
            subtitle = "Lets sites read your battery level. Can be used to track you",
            checked = settings.featureBatteryApi,
            onCheckedChange = viewModel::setFeatureBatteryApi
        )
        SwitchRow(
            title = "Network Information API",
            subtitle = "Lets sites see your connection type. Can be used to track you",
            checked = settings.featureNetworkInfo,
            onCheckedChange = viewModel::setFeatureNetworkInfo
        )
        SwitchRow(
            title = "Speech recognition API",
            subtitle = "Turns on the API. The engine has no built-in speech service on Android, so many sites still cannot hear you",
            checked = settings.featureSpeechRecognition,
            onCheckedChange = viewModel::setFeatureSpeechRecognition
        )
    }
}
