package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.IncognitoAutoWipe
import com.fable.browser.ui.browser.BrowserViewModel
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow

@Composable
fun IncognitoSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    browserViewModel: BrowserViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var proxyHost by remember(settings.incognitoProxyHost) {
        mutableStateOf(settings.incognitoProxyHost)
    }
    var proxyPort by remember(settings.incognitoProxyPort) {
        mutableStateOf(settings.incognitoProxyPort.toString())
    }
    var showCopyConfirm by remember { mutableStateOf(false) }
    var showKeepWarning by remember { mutableStateOf(false) }

    SettingsPage(title = "Incognito", onBack = onBack, viewModel = viewModel) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("In-memory by design", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Incognito sessions use an in-memory Gecko profile: no cache, no cookies, " +
                        "no localStorage, IndexedDB or service workers, no favicons, no autofill, " +
                        "no history, and no thumbnails on disk. Nothing below is needed to make " +
                        "that true — it is the default.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        SectionHeader("Isolation")
        SwitchRow(
            title = "Isolate each incognito tab",
            subtitle = "Every private tab gets its own cookie jar and storage partition, so two tabs cannot be correlated. Chrome shares one jar across all its incognito tabs.",
            icon = Icons.Outlined.Shield,
            checked = settings.isolateIncognitoTabs,
            onCheckedChange = viewModel::setIsolateIncognitoTabs
        )
        ListDivider()

        SectionHeader("Fingerprinting")
        SwitchRow(
            title = "Fingerprint shield",
            subtitle = "Spoofs the user agent and platform, rounds screen metrics, noises Canvas/WebGL/AudioContext readback, blocks WebRTC IP disclosure, blunts performance.now(), and freezes font enumeration.",
            icon = Icons.Outlined.Fingerprint,
            checked = settings.fingerprintShield,
            onCheckedChange = viewModel::setFingerprintShield
        )
        SwitchRow(
            title = "Report UTC to pages",
            subtitle = "Hides your timezone from JavaScript",
            icon = Icons.Outlined.Schedule,
            checked = settings.forceUtcTimezone,
            enabled = settings.fingerprintShield,
            onCheckedChange = viewModel::setForceUtcTimezone
        )
        SettingRow(
            title = "Test my fingerprint",
            subtitle = "Opens coveryourtracks.eff.org in a fresh incognito tab",
            onClick = { browserViewModel.openFingerprintTest() }
        )
        ListDivider()

        SectionHeader("Access")
        SwitchRow(
            title = "Require unlock for incognito",
            subtitle = if (viewModel.biometricsAvailable())
                "Biometric or device PIN, re-armed every time the app goes to the background"
            else
                "Set a screen lock on this device to use this",
            icon = Icons.Outlined.Lock,
            checked = settings.incognitoLock,
            onCheckedChange = viewModel::setIncognitoLock
        )
        ListDivider()

        SectionHeader("Auto-wipe after inactivity")
        Text(
            if (settings.keepIncognitoTabs)
                "Incognito site data is wiped when the last private tab closes or the app closes. This adds an idle timer on top."
            else
                "Incognito is always wiped when the last private tab closes or the process dies. This adds an idle timer on top.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        RadioGroup(
            options = IncognitoAutoWipe.entries.toList(),
            selected = settings.incognitoAutoWipe,
            label = { it.label },
            onSelect = viewModel::setIncognitoAutoWipe
        )
        ListDivider()

        SectionHeader("Proxy for incognito traffic")
        SwitchRow(
            title = "Route incognito through a proxy",
            subtitle = "Normal tabs keep using your direct connection",
            icon = Icons.Outlined.VpnLock,
            checked = settings.incognitoProxyEnabled,
            onCheckedChange = viewModel::setIncognitoProxyEnabled
        )
        if (settings.incognitoProxyEnabled) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("SOCKS5", "HTTP").forEach { type ->
                    FilterChip(
                        selected = settings.incognitoProxyType == type,
                        onClick = { viewModel.setIncognitoProxyType(type) },
                        label = { Text(type) }
                    )
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = proxyHost,
                    onValueChange = { proxyHost = it },
                    label = { Text("Host") },
                    placeholder = { Text("127.0.0.1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = { proxyPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    placeholder = { Text("9050") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "Tor's default SOCKS5 port is 9050 (Orbot uses 9050 too). DNS is resolved by the proxy, not by this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                TextButton(
                    onClick = {
                        viewModel.setIncognitoProxyHost(proxyHost)
                        viewModel.setIncognitoProxyPort(proxyPort)
                    },
                    enabled = proxyHost.trim() != settings.incognitoProxyHost ||
                        proxyPort != settings.incognitoProxyPort.toString(),
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Save proxy") }
            }
        }
        ListDivider()

        SectionHeader("Also always on")
        SettingRow(
            title = "FLAG_SECURE on private screens",
            subtitle = "Screenshots and screen recording are blocked, and the app-switcher thumbnail is blank while incognito is showing",
            icon = Icons.Outlined.Shield
        )
        SettingRow(
            title = "Panic button",
            subtitle = "Long-press the incognito icon in the toolbar to kill every private tab and wipe session data instantly",
            icon = Icons.Outlined.Timer
        )
        SettingRow(
            title = "Downloads need confirmation",
            subtitle = "Writing a file to disk is the one thing incognito cannot hide, so Fable asks first, per file"
        )
        ListDivider()

        SectionHeader("Incognito protection")
        Text(
            "Incognito tabs have their own value for every switch below. Normal tabs are not affected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        SwitchRow(
            title = "Block ads and trackers (filter lists)",
            subtitle = "Uses the same filter lists as normal tabs",
            checked = settings.incognitoAdBlock,
            onCheckedChange = viewModel::setIncognitoAdBlock
        )
        SwitchRow(
            title = "Tracking protection",
            subtitle = "Firefox Enhanced Tracking Protection. Applies to new incognito tabs",
            checked = settings.incognitoTrackingProtection,
            onCheckedChange = viewModel::setIncognitoTrackingProtection
        )
        SwitchRow(
            title = "HTTPS-only",
            subtitle = "Never load unencrypted pages in incognito",
            checked = settings.incognitoHttpsOnly,
            onCheckedChange = viewModel::setIncognitoHttpsOnly
        )
        SwitchRow(
            title = "Remove tracking parameters from links",
            subtitle = "Strips fbclid, gclid, utm_ and similar from addresses",
            checked = settings.incognitoStripParams,
            onCheckedChange = viewModel::setIncognitoStripParams
        )
        SwitchRow(
            title = "Tell sites not to sell or share my data",
            subtitle = "Sends Global Privacy Control. Restart the app to apply",
            checked = settings.incognitoGpc,
            onCheckedChange = viewModel::setIncognitoGpc
        )
        SwitchRow(
            title = "Firefox fingerprinting protection",
            subtitle = "Uses the protections chosen in Privacy > Fingerprint protection. Turn off only if a site or game does not work",
            checked = settings.incognitoFingerprintProtection,
            onCheckedChange = viewModel::setIncognitoFingerprintProtection
        )
        SwitchRow(
            title = "JavaScript",
            subtitle = "Turn off for the most private and secure browsing. Most sites and all games need it. Applies to new incognito tabs",
            checked = settings.incognitoJavascript,
            onCheckedChange = viewModel::setIncognitoJavascript
        )
        SwitchRow(
            title = "Block all third-party cookies",
            subtitle = "Stronger than the default per-site isolation. Can stop logins and games embedded in other sites (like itch.io) from saving",
            checked = settings.incognitoBlockThirdPartyCookies,
            onCheckedChange = viewModel::setIncognitoBlockThirdPartyCookies
        )
        SettingRow(
            title = "Copy settings from normal tabs",
            subtitle = "Sets every switch above to the value used in normal tabs",
            onClick = { showCopyConfirm = true }
        )
        ListDivider()

        SectionHeader("When the app is closed")
        SwitchRow(
            title = "Keep incognito tabs after closing",
            subtitle = "Open incognito tabs come back the next time you start the app",
            checked = settings.keepIncognitoTabs,
            onCheckedChange = { on ->
                if (on) {
                    showKeepWarning = true
                } else {
                    viewModel.setKeepIncognitoTabs(false)
                }
            }
        )
        if (settings.keepIncognitoTabs) {
            Text(
                "On: the addresses and back/forward history of open incognito tabs are saved encrypted on this phone. Cookies, logins and site data are still deleted when the app closes. Closing all incognito tabs or the panic button deletes the saved tabs too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        ListDivider()
    }

    if (showCopyConfirm) {
        AlertDialog(
            onDismissRequest = { showCopyConfirm = false },
            title = { Text("Copy from normal tabs?") },
            text = { Text("Every incognito protection switch will be set to the value used in normal tabs.") },
            confirmButton = {
                TextButton(onClick = {
                    showCopyConfirm = false
                    viewModel.copyIncognitoFromNormal()
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showKeepWarning) {
        AlertDialog(
            onDismissRequest = { showKeepWarning = false },
            title = { Text("Keep incognito tabs?") },
            text = {
                Text(
                    "The addresses and history of your open incognito tabs will be saved encrypted on this phone until you close them, so they come back after the app is closed. Cookies, logins and site data are still deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showKeepWarning = false
                    viewModel.setKeepIncognitoTabs(true)
                }) { Text("Keep tabs") }
            },
            dismissButton = {
                TextButton(onClick = { showKeepWarning = false }) { Text("Cancel") }
            }
        )
    }
}
