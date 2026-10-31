package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import com.fable.browser.data.model.DohProvider
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow

/**
 * Privacy & Security. Every toggle here applies to BOTH normal and incognito
 * mode — incognito only ever adds protection, never removes it.
 */
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val updating by viewModel.updatingFilters.collectAsStateWithLifecycle()
    var customDoh by remember(settings.dohCustomUrl) { mutableStateOf(settings.dohCustomUrl) }

    SettingsPage(title = "Privacy & security", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Blocking")
        SwitchRow(
            title = "Block ads and trackers",
            subtitle = "EasyList + EasyPrivacy enforced inside the network stack (${viewModel.filterRuleCount()} rules loaded)",
            icon = Icons.Outlined.Block,
            checked = settings.adBlockEnabled,
            onCheckedChange = viewModel::setAdBlockEnabled
        )
        SettingRow(
            title = "Update filter lists",
            subtitle = "Downloads EasyList and EasyPrivacy from easylist.to. This is the only network request Fable makes that you did not start by browsing.",
            icon = Icons.Outlined.Refresh,
            onClick = { viewModel.updateFilterLists() },
            trailing = {
                if (updating) CircularProgressIndicator(
                    modifier = Modifier.padding(4.dp),
                    strokeWidth = 2.dp
                )
            }
        )
        SwitchRow(
            title = "Strip tracking parameters",
            subtitle = "Removes utm_*, fbclid, gclid, igshid, mc_eid and friends from every URL you open",
            checked = settings.stripTrackingParams,
            onCheckedChange = viewModel::setStripTrackingParams
        )
        ListDivider()

        SectionHeader("Cookies and storage")
        SwitchRow(
            title = "Block third-party cookies",
            subtitle = "Also partitions third-party storage so embedded frames cannot share state across sites",
            icon = Icons.Outlined.Cookie,
            checked = settings.blockThirdPartyCookies,
            onCheckedChange = viewModel::setBlockThirdPartyCookies
        )
        ListDivider()

        SectionHeader("Connection")
        SwitchRow(
            title = "HTTPS-only mode",
            subtitle = "Plain HTTP pages show an interstitial you must confirm before loading",
            icon = Icons.Outlined.Lock,
            checked = settings.httpsOnly,
            onCheckedChange = viewModel::setHttpsOnly
        )
        SwitchRow(
            title = "Send Global Privacy Control",
            subtitle = "Sec-GPC: 1 and DNT: 1 on every request",
            checked = settings.sendGpc,
            onCheckedChange = viewModel::setSendGpc
        )
        ListDivider()

        SectionHeader("Leak protection")
        SwitchRow(
            title = "Hide local IP address (WebRTC)",
            subtitle = "Sites can no longer see your Wi-Fi/LAN address (like 192.168.x.x). Calls and online games keep working",
            checked = settings.hideLocalIp,
            onCheckedChange = viewModel::setHideLocalIp
        )
        SwitchRow(
            title = "Strong fingerprint protection in all tabs",
            subtitle = "Firefox's protection against canvas, font and hardware fingerprinting. Always on in incognito. May slightly change some games' pixel reading",
            checked = settings.fingerprintProtectionAll,
            onCheckedChange = viewModel::setFingerprintProtectionAll
        )
        SwitchRow(
            title = "Hide graphics chip name",
            subtitle = "Removes the GPU model from WebGL. Some games use it to choose graphics quality",
            checked = settings.hideGpuModel,
            onCheckedChange = viewModel::setHideGpuModel
        )
        ListDivider()

        SectionHeader("DNS over HTTPS")
        com.fable.browser.ui.components.RadioGroup(
            options = DohProvider.entries.toList(),
            selected = settings.dohProvider,
            label = { it.label },
            description = { provider ->
                when (provider) {
                    DohProvider.CLOUDFLARE -> "mozilla.cloudflare-dns.com — no logging agreement"
                    DohProvider.QUAD9 -> "dns.quad9.net — malware filtering, Swiss non-profit"
                    DohProvider.CUSTOM -> "Your own resolver endpoint"
                    DohProvider.OFF -> "Use the network's DNS (your ISP can see every hostname)"
                }
            },
            onSelect = viewModel::setDohProvider
        )
        if (settings.dohProvider == DohProvider.CUSTOM) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = customDoh,
                    onValueChange = { customDoh = it },
                    label = { Text("Resolver URL") },
                    placeholder = { Text("https://example.com/dns-query") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { viewModel.setDohCustomUrl(customDoh) },
                    enabled = customDoh.trim() != settings.dohCustomUrl,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Save resolver") }
            }
        }
        SettingRow(
            title = "Resolver in use",
            subtitle = when (settings.dohProvider) {
                DohProvider.OFF -> "System DNS"
                DohProvider.CUSTOM -> settings.dohCustomUrl.ifBlank { "Not set — system DNS is being used" }
                else -> settings.dohProvider.url
            },
            icon = Icons.Outlined.Dns
        )
        ListDivider()

        SectionHeader("Clear on exit")
        Text(
            "Applied when the last window closes. Incognito data is always wiped regardless of these switches.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        SwitchRow(
            title = "Clear history",
            checked = settings.clearHistoryOnExit,
            onCheckedChange = viewModel::setClearHistoryOnExit
        )
        SwitchRow(
            title = "Clear cookies and site data",
            checked = settings.clearCookiesOnExit,
            onCheckedChange = viewModel::setClearCookiesOnExit
        )
        SwitchRow(
            title = "Clear cache",
            checked = settings.clearCacheOnExit,
            onCheckedChange = viewModel::setClearCacheOnExit
        )
        ListDivider()

        SectionHeader("Clear now")
        SettingRow(title = "Clear cookies and site data", onClick = viewModel::clearCookiesNow)
        SettingRow(title = "Clear cache", onClick = viewModel::clearCacheNow)
        SettingRow(
            title = "Clear everything",
            subtitle = "Cookies, cache, site data, and all normal-mode history",
            onClick = viewModel::clearEverythingNow
        )
        ListDivider()
    }
}
