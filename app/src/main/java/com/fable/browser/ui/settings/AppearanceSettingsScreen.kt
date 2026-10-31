package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.ThemeMode
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

/** Theme, forced-dark web content, and text zoom. */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsPage(title = "Appearance", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Theme")
        RadioGroup(
            options = ThemeMode.entries.toList(),
            selected = settings.themeMode,
            label = { mode ->
                when (mode) {
                    ThemeMode.SYSTEM -> "Follow system"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                }
            },
            onSelect = viewModel::setThemeMode
        )
        ListDivider()

        SectionHeader("Web content")
        SwitchRow(
            title = "Force dark web pages",
            subtitle = "Tells sites you prefer a dark color scheme, and darkens pages that do not offer one",
            icon = Icons.Outlined.DarkMode,
            checked = settings.forceDarkWeb,
            onCheckedChange = viewModel::setForceDarkWeb
        )
        SliderRow(
            title = "Text size",
            value = settings.textZoomPercent,
            range = 50f..200f,
            steps = 14,
            suffix = "%",
            onValueChange = viewModel::setTextZoomPercent
        )
        Text(
            "Applies to every page. Pinch-to-zoom still works independently.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        ListDivider()

        SectionHeader("Toolbar")
        SettingRow(
            title = "Bottom toolbar",
            subtitle = "Fable's toolbar is anchored to the bottom of the screen so it stays in thumb reach, and it collapses while you scroll down.",
            icon = Icons.Outlined.FormatSize
        )
        ListDivider()
    }
}
