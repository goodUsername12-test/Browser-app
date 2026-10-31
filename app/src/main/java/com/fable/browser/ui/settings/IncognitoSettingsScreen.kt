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

/**
 * The incognito control panel. Everything here is ON by default except the
 * biometric lock and the proxy, which need hardware or a server the user has to
 * supply. Nothing on this screen can be used to weaken normal-mode privacy.
 */
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
        SwitchRow(
            title = "JavaScript in incognito",
            subtitle = "Turn off for the most private and secure browsing. Most sites and all games need it. Applies to new incognito tabs",
            checked = settings.incognitoJavascript,
            onCheckedChange = viewModel::setIncognitoJavascript
        )
        SwitchRow(
            title = "HTTPS-only in incognito",
            subtitle = "Never load unencrypted pages in incognito, even if HTTPS-only is off for normal tabs",
            checked = settings.incognitoHttpsOnly,
            onCheckedChange = viewModel::setIncognitoHttpsOnly
        )
        SwitchRow(
            title = "Block all third-party cookies in incognito",
            subtitle = "Stronger than the default per-site isolation. Can stop logins and games embedded in other sites (like itch.io) from saving",
            checked = settings.incognitoBlockThirdPartyCookies,
            onCheckedChange = viewModel::setIncognitoBlockThirdPartyCookies
        )
        SettingRow(
            title = "Fingerprint protection is always on in incognito",
            subtitle = "Uses the protections chosen in Privacy > Fingerprint protection, plus a separate identity per incognito session"
        )
        ListDivider()
    }
}
