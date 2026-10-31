package com.fable.browser.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.PerformanceProfile
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profile = settings.performanceProfile
    val profileOptions = if (profile == PerformanceProfile.CUSTOM) {
        PerformanceProfile.entries.toList()
    } else {
        PerformanceProfile.entries.filter { it != PerformanceProfile.CUSTOM }
    }

    SettingsPage(title = "Performance", onBack = onBack, viewModel = viewModel) {
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
            subtitle = "Incognito tabs always use it",
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

        SectionHeader("Background tabs")
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
        ListDivider()

        SectionHeader("Gaming")
        SwitchRow(
            title = "Game mode in full screen",
            subtitle = "Gives the game all free memory and top priority so it does not crash while loading",
            checked = settings.gameMode,
            onCheckedChange = viewModel::setGameMode
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
