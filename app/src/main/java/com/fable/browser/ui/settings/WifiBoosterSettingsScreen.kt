package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.DataSaverMode
import com.fable.browser.data.model.DohProvider
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SwitchRow

@Composable
fun WifiBoosterSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsPage(title = "Wi-Fi booster", onBack = onBack, viewModel = viewModel) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Faster pages on the same connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The booster cannot make your Wi-Fi itself faster, but it cuts waiting time: quicker DNS " +
                        "lookups, faster secure connections and fewer wasted downloads. Most pages open about " +
                        "5 to 30% faster.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        SectionHeader("Fast secure DNS")
        RadioGroup(
            options = listOf(DohProvider.CLOUDFLARE, DohProvider.GOOGLE, DohProvider.QUAD9, DohProvider.OFF) +
                if (settings.dohProvider == DohProvider.CUSTOM) listOf(DohProvider.CUSTOM) else emptyList(),
            selected = settings.dohProvider,
            label = { it.label },
            description = {
                when (it) {
                    DohProvider.CLOUDFLARE -> "Usually the fastest. Encrypted, falls back to normal DNS if it fails"
                    DohProvider.GOOGLE -> "Very fast in most countries. Encrypted"
                    DohProvider.QUAD9 -> "Encrypted and blocks known malware sites"
                    DohProvider.OFF -> "Use your Wi-Fi or carrier DNS"
                    DohProvider.CUSTOM -> "Your own server from Privacy settings"
                }
            },
            onSelect = viewModel::setDohProvider
        )
        ListDivider()

        SectionHeader("Connections")
        SwitchRow(
            title = "Faster connections",
            subtitle = "HTTP/3, 0-RTT quick reconnects, more parallel downloads per site and racing the cache " +
                "against the network",
            checked = settings.fastConnections,
            icon = Icons.Outlined.Bolt,
            onCheckedChange = viewModel::setFastConnections
        )
        ListDivider()

        SectionHeader("Data saver")
        RadioGroup(
            options = DataSaverMode.values().toList(),
            selected = settings.dataSaver,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::setDataSaver
        )
        Text(
            "Data saver sends the Save-Data signal so supporting sites send lighter images and video, and stops " +
                "preloading pages and media.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}
