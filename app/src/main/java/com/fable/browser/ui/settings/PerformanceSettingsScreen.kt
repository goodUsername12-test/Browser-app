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
    }
}
