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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.DataSaverMode
import com.fable.browser.data.model.DohProvider
import com.fable.browser.engine.FableNetworkOptimizer
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow
import kotlinx.coroutines.launch

@Composable
fun WifiBoosterSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snap by FableNetworkOptimizer.snapshot.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }

    LaunchedEffect(settings.fastConnections) {
        FableNetworkOptimizer.initAsync(ctx)
        FableNetworkOptimizer.refreshSnapshot(ctx, settings.fastConnections)
    }

    SettingsPage(title = "Wi-Fi & Network Optimizer", onBack = onBack, viewModel = viewModel) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${snap.tierLabel} (${snap.qualityScore}/100) · ${snap.bandLabel}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Link: ~${snap.linkSpeedMbps} Mbps (Down ~${snap.downMbpsEstimate} Mbps / Up ~${snap.upMbpsEstimate} Mbps)" +
                        (if (snap.isWifi) " · Signal ${snap.rssiDbm} dBm" else "") +
                        (snap.lastRttMs?.let { " · Ping ${it} ms" } ?: "") + "\n" +
                        "Rust Download Streams: ${snap.parallelStreams}x parallel Range chunks · Upload Buffer: ${snap.uploadChunkKb} KB · " +
                        (if (snap.platformCronetAvailable) "Android Cronet HTTP/3 QUIC Ready" else "HTTP/2 + Preconnect Active"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        SettingRow(
            title = if (probing) "Testing live connection latency..." else "Run Live Wi-Fi & Ping Check",
            subtitle = snap.lastRttMs?.let { "Last HTTP/TLS roundtrip: $it ms · Tap to refresh link quality and stream count" }
                ?: "Measures real HTTP/TLS roundtrip time and tunes parallel download/upload streams",
            onClick = {
                if (!probing) {
                    probing = true
                    scope.launch {
                        FableNetworkOptimizer.probeLatencyMs(ctx)
                        probing = false
                    }
                }
            }
        )
        ListDivider()

        SectionHeader("Engine Speed Mode")
        SwitchRow(
            title = "Use Blink Chromium Engine on All Websites",
            subtitle = "Runs Fable's fast Chromium/Blink renderer on every normal website and local HTML tool (not just 3D games), while keeping Gecko privacy filters active",
            checked = settings.hybridGeckoBlinkBeta && !settings.hybridAutoForGames,
            onCheckedChange = { allSites ->
                if (allSites) {
                    viewModel.setHybridGeckoBlinkBeta(true)
                    viewModel.setHybridAutoForGames(false)
                } else {
                    viewModel.setHybridAutoForGames(true)
                }
            }
        )
        ListDivider()

        SectionHeader("Connections & Parallel Downloads")
        SwitchRow(
            title = "Faster connections & Multi-Stream Downloads",
            subtitle = "HTTP/3 QUIC, 0-RTT reconnects, origin DNS pre-warming, and up to 6x parallel Range chunks on large downloads",
            checked = settings.fastConnections,
            icon = Icons.Outlined.Bolt,
            onCheckedChange = viewModel::setFastConnections
        )
        ListDivider()

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
