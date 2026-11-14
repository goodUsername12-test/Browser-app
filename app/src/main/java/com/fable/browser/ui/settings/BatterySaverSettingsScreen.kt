package com.fable.browser.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.engine.FableBatteryGovernor
import com.fable.browser.engine.RustFastCore
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

@Composable
fun BatterySaverSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snap by FableBatteryGovernor.snapshot.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        FableBatteryGovernor.init(ctx)
        FableBatteryGovernor.refreshBatteryState(ctx)
    }

    val livePlan = FableBatteryGovernor.evaluatePlan(settings, isPureGame = true)

    SettingsPage(title = "Battery & Eco Gaming", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Live Governor Status")
        SettingRow(
            title = "Battery ${snap.batteryPercent}%" + (if (snap.isCharging) " (Charging)" else "") + " • Thermal: ${snap.thermalLabel}",
            subtitle = "In-Game Plan: ${livePlan.targetGameFps} FPS cap • ${livePlan.renderScalePercent}% 3D Resolution • ${livePlan.targetDisplayHz} Hz Display • " +
                (if (RustFastCore.isRustAvailable) "Rust Power Core v${RustFastCore.rustVersion()} Active" else "Native Power Core Active")
        )
        SettingRow(
            title = "Apply Eco Gaming Preset (Much Slower Drain, Smooth FPS)",
            subtitle = "Sets 90 FPS cap, 85% 3D resolution (-28% GPU pixels), Eco CPU cores, 30 FPS AFK idle drop, and Thermal Guard",
            onClick = viewModel::applyEcoGamingPreset
        )
        SettingRow(
            title = "Apply Max Competitive 120 Hz Preset",
            subtitle = "Restores 120 FPS, 100% native 3D resolution, and Cortex Big-Core Turbo while keeping Thermal Guard ready",
            onClick = viewModel::applyMaxCompetitivePreset
        )
        ListDivider()

        SectionHeader("Master Power & Thermal Controls")
        SwitchRow(
            title = "Battery Saver Mode",
            subtitle = "Cuts CPU, GPU, and display power across normal tabs and 3D games with minimal gameplay impact",
            checked = settings.batterySaverMaster,
            onCheckedChange = viewModel::setBatterySaverMaster
        )
        RadioGroup(
            options = listOf(0, 15, 20, 30, 50),
            selected = settings.batteryAutoLowPct,
            label = { if (it == 0) "Never auto-enable" else "Auto-enable at $it% or lower" },
            description = {
                if (it == 0) "Only use Battery Saver when you turn it on manually"
                else "Automatically activates Eco savings when unplugged at or below $it%"
            },
            onSelect = viewModel::setBatteryAutoLowPct
        )
        SwitchRow(
            title = "Android ADPF Thermal Overheat Guard",
            subtitle = "Monitors phone temperature and steps down boost before thermal throttling spikes battery drain",
            checked = settings.batteryThermalGuard,
            onCheckedChange = viewModel::setBatteryThermalGuard
        )
        ListDivider()

        SectionHeader("3D Games Battery Controls (Bloxd, WebGL, WebGPU)")
        RadioGroup(
            options = listOf(120, 90, 60, 45, 30),
            selected = settings.batteryGameMaxFps,
            label = { "$it FPS Max in 3D Games" },
            description = {
                when (it) {
                    120 -> "Maximum 120 Hz competitive smoothness (highest GPU & screen power)"
                    90 -> "Very smooth 90 Hz gameplay with ~22% lower GPU & display drain"
                    60 -> "Locked 60 FPS console smoothness with ~40% lower GPU & display drain"
                    45 -> "Balanced low-power 45 FPS for long unplugged sessions"
                    else -> "Ultra battery saver 30 FPS"
                }
            },
            onSelect = viewModel::setBatteryGameMaxFps
        )
        ListDivider()

        SectionHeader("3D Game Render Resolution Scale (Biggest GPU Battery Saver)")
        RadioGroup(
            options = listOf(100, 85, 75, 60),
            selected = settings.batteryGameRenderScale,
            label = {
                when (it) {
                    100 -> "100% Native Screen DPR"
                    85 -> "85% Scale (Saves ~28% GPU pixels, sharp look)"
                    75 -> "75% Scale (Saves ~44% GPU pixels, higher 1% low FPS)"
                    else -> "60% Scale (Saves ~64% GPU pixels, max battery life)"
                }
            },
            description = {
                "Scales 3D canvas pixel ratio while keeping UI and touch coordinates 100% accurate"
            },
            onSelect = viewModel::setBatteryGameRenderScale
        )
        ListDivider()

        SectionHeader("In-Game CPU, Idle AFK & OLED Savings")
        SwitchRow(
            title = "Eco CPU Core Scheduling in Games",
            subtitle = "Avoids locking big CPU cores at maximum voltage unless a heavy frame spike occurs",
            checked = settings.batteryEcoCpu,
            onCheckedChange = viewModel::setBatteryEcoCpu
        )
        RadioGroup(
            options = listOf(0, 60, 30, 15),
            selected = settings.batteryIdleGameFps,
            label = { if (it == 0) "AFK Idle FPS Drop: Off" else "Drop to $it FPS when untouched" },
            description = {
                if (it == 0) "Keeps full FPS even when you are not touching the screen"
                else "Drops game FPS when hands are off the screen and snaps back to full speed on the first touch"
            },
            onSelect = viewModel::setBatteryIdleGameFps
        )
        if (settings.batteryIdleGameFps > 0) {
            SliderRow(
                title = "Seconds untouched before AFK FPS drop",
                value = settings.batteryIdleTimeoutSec,
                range = 4f..30f,
                steps = 12,
                suffix = " s",
                onValueChange = viewModel::setBatteryIdleTimeoutSec
            )
        }
        RadioGroup(
            options = listOf(0, 10, 20, 35),
            selected = settings.batteryOledDimPct,
            label = { if (it == 0) "Extra OLED Dimmer: Off" else "Dim screen by extra $it%" },
            description = { "Hardware-composited dark scrim that reduces display power without extra GPU shader cost" },
            onSelect = viewModel::setBatteryOledDimPct
        )
        ListDivider()

        SectionHeader("Normal Browsing & Background Power")
        SwitchRow(
            title = "Dynamic 60/120 Hz Refresh Rate on Websites",
            subtitle = "Uses high refresh rate while scrolling or touching, then drops to 60 Hz after 2.5s of static reading",
            checked = settings.batteryAdaptiveRefresh,
            onCheckedChange = viewModel::setBatteryAdaptiveRefresh
        )
        SwitchRow(
            title = "Deep-Freeze Background Tabs & Timers",
            subtitle = "Pauses hidden WebView timers and hibernates inactive Gecko sessions so background tabs use ~0% battery",
            checked = settings.batteryFreezeBgTabs,
            onCheckedChange = viewModel::setBatteryFreezeBgTabs
        )
        SwitchRow(
            title = "Reduce Decorative Website Animations",
            subtitle = "Signals prefers-reduced-motion to stop battery-draining background CSS animations on normal sites",
            checked = settings.batteryReduceMotion,
            onCheckedChange = viewModel::setBatteryReduceMotion
        )
    }
}
