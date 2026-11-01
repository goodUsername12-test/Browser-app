package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Column
import com.fable.browser.ui.components.ConfirmDialog
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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

@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val updating by viewModel.updatingFilters.collectAsStateWithLifecycle()
    var customDoh by remember(settings.dohCustomUrl) { mutableStateOf(settings.dohCustomUrl) }

    SettingsPage(title = "Privacy & security", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Quick setup")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { viewModel.applyPreset(PrivacyPreset.BALANCED) }, modifier = Modifier.weight(1f)) {
                Text("Balanced")
            }
            OutlinedButton(onClick = { viewModel.applyPreset(PrivacyPreset.MAXIMUM) }, modifier = Modifier.weight(1f)) {
                Text("Maximum")
            }
            OutlinedButton(onClick = { viewModel.applyPreset(PrivacyPreset.GAMING) }, modifier = Modifier.weight(1f)) {
                Text("Gaming")
            }
        }
        SettingRow(
            title = "Balanced is the default",
            subtitle = "Maximum may break sites and games. Gaming relaxes only what games need; your IP, cookies and tracking stay protected"
        )
        ListDivider()

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

        SectionHeader("Fingerprint protection")
        SwitchRow(
            title = "Fingerprint protection in normal tabs",
            subtitle = "Master switch for the protections below. Always on in incognito",
            checked = settings.fingerprintProtectionAll,
            onCheckedChange = viewModel::setFingerprintProtectionAll
        )
        SwitchRow(
            title = "Canvas",
            subtitle = "Adds invisible noise to canvas reads so drawings can't identify you",
            checked = settings.fpCanvas,
            onCheckedChange = viewModel::setFpCanvas
        )
        SwitchRow(
            title = "Block canvas reading by third parties",
            subtitle = "Embedded trackers can't read canvas pixels",
            checked = settings.fpCanvasThirdParty,
            onCheckedChange = viewModel::setFpCanvasThirdParty
        )
        SwitchRow(
            title = "Fonts",
            subtitle = "Sites only see the standard Android fonts, not every installed font",
            checked = settings.fpFonts,
            onCheckedChange = viewModel::setFpFonts
        )
        SwitchRow(
            title = "WebGL graphics chip name",
            subtitle = "Hides the GPU model. Trust a game site below to give it the real name",
            checked = settings.hideGpuModel,
            onCheckedChange = viewModel::setHideGpuModel
        )
        SwitchRow(
            title = "WebGL limits",
            subtitle = "Reports common WebGL limits instead of your phone's exact ones. Can lower game graphics",
            checked = settings.fpWebGlLimits,
            onCheckedChange = viewModel::setFpWebGlLimits
        )
        SwitchRow(
            title = "User-Agent and platform",
            subtitle = "Sends the common Firefox for Android identity (same Android/build version as most users)",
            checked = settings.fpUserAgent,
            onCheckedChange = viewModel::setFpUserAgent
        )
        SwitchRow(
            title = "Hardware details",
            subtitle = "CPU core count and touch points are rounded to common values",
            checked = settings.fpHardware,
            onCheckedChange = viewModel::setFpHardware
        )
        SwitchRow(
            title = "Screen size",
            subtitle = "Sites see the page size instead of your exact screen and window position",
            checked = settings.fpScreen,
            onCheckedChange = viewModel::setFpScreen
        )
        SwitchRow(
            title = "Audio",
            subtitle = "Audio fingerprinting gets random noise and a standard sample rate",
            checked = settings.fpAudio,
            onCheckedChange = viewModel::setFpAudio
        )
        SwitchRow(
            title = "Media devices and voices",
            subtitle = "Hides camera/microphone list, speech voices and connection type",
            checked = settings.fpMedia,
            onCheckedChange = viewModel::setFpMedia
        )
        SwitchRow(
            title = "Motion sensors",
            subtitle = "Hides motion/orientation sensors. Breaks tilt-controlled games",
            checked = settings.fpSensors,
            onCheckedChange = viewModel::setFpSensors
        )
        SwitchRow(
            title = "Time zone",
            subtitle = "Sites see UTC. Clocks on websites will show the wrong local time",
            checked = settings.fpTimezone,
            onCheckedChange = viewModel::setFpTimezone
        )
        SwitchRow(
            title = "Language",
            subtitle = "Sites see English (US) date and number formats",
            checked = settings.fpLanguage,
            onCheckedChange = viewModel::setFpLanguage
        )
        SwitchRow(
            title = "Precise timers",
            subtitle = "Rounds timers. Stronger protection but games can stutter",
            checked = settings.fpTimers,
            onCheckedChange = viewModel::setFpTimers
        )
        SettingRow(
            title = "Client Hints (Sec-CH-UA)",
            subtitle = "Never sent: the Gecko engine does not support them, so this cannot leak"
        )
        ListDivider()

        SectionHeader("Trusted sites (games)")
        SettingRow(
            title = "No fingerprint protection on these sites",
            subtitle = "For games that need the real graphics chip, canvas and screen. Tracking, cookie and WebRTC protection still apply. Enter the main site name, e.g. poki.com"
        )
        var newSite by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newSite,
                onValueChange = { newSite = it },
                label = { Text("Site") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                viewModel.addTrustedSite(newSite)
                newSite = ""
            }) { Text("Add") }
        }
        settings.fpTrustedSites.split(',').filter { it.isNotBlank() }.forEach { host ->
            SettingRow(
                title = host,
                trailing = {
                    IconButton(onClick = { viewModel.removeTrustedSite(host) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove $host")
                    }
                }
            )
        }
        ListDivider()

        SectionHeader("WebRTC")
        SwitchRow(
            title = "Hide local IP address",
            subtitle = "Sites can't see your Wi-Fi/LAN address (192.168.x.x). Calls and online games keep working",
            checked = settings.hideLocalIp,
            onCheckedChange = viewModel::setHideLocalIp
        )
        SwitchRow(
            title = "Only through proxy",
            subtitle = "WebRTC never connects directly. Calls/games only work when a proxy is set",
            checked = settings.webRtcProxyOnly,
            onCheckedChange = viewModel::setWebRtcProxyOnly
        )
        SwitchRow(
            title = "Turn off WebRTC completely",
            subtitle = "No IP can leak through WebRTC. Breaks video calls and some multiplayer games",
            checked = settings.webRtcDisabled,
            onCheckedChange = viewModel::setWebRtcDisabled
        )
        ListDivider()

        SectionHeader("Network privacy")
        SwitchRow(
            title = "Block prefetching",
            subtitle = "No hidden connections to links and sites you have not opened",
            checked = settings.blockPrefetch,
            onCheckedChange = viewModel::setBlockPrefetch
        )
        SwitchRow(
            title = "Strict referrer",
            subtitle = "Other sites only learn the site name you came from, never the full address",
            checked = settings.strictReferrer,
            onCheckedChange = viewModel::setStrictReferrer
        )
        SwitchRow(
            title = "Bounce tracking protection",
            subtitle = "Clears data of trackers that bounce you through redirects",
            checked = settings.bounceTracking,
            onCheckedChange = viewModel::setBounceTracking
        )
        ListDivider()

        SectionHeader("Security hardening")
        SwitchRow(
            title = "Super-secure JavaScript (no JIT)",
            subtitle = "Blocks a whole class of browser exploits. Pages and games run slower",
            checked = settings.noJit,
            onCheckedChange = viewModel::setNoJit
        )
        SwitchRow(
            title = "Turn off WebAssembly",
            subtitle = "Extra security. Most 3D games and some apps will not work",
            checked = settings.disableWasm,
            onCheckedChange = viewModel::setDisableWasm
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
        var askClear by remember { mutableStateOf<String?>(null) }
        SettingRow(title = "Clear cookies and site data", onClick = { askClear = "cookies" })
        SettingRow(title = "Clear cache", onClick = { askClear = "cache" })
        SettingRow(
            title = "Clear everything",
            subtitle = "Cookies, cache, site data, and all normal-mode history",
            onClick = { askClear = "all" }
        )
        when (askClear) {
            "cookies" -> ConfirmDialog(
                title = "Clear all cookies?",
                message = "You will be signed out of every website and site data (saves, settings) will be deleted.",
                confirmLabel = "Clear cookies",
                requireTick = true,
                onConfirm = viewModel::clearCookiesNow,
                onDismiss = { askClear = null }
            )
            "cache" -> ConfirmDialog(
                title = "Clear cache?",
                message = "Websites will load slower the next time. You stay signed in.",
                confirmLabel = "Clear cache",
                onConfirm = viewModel::clearCacheNow,
                onDismiss = { askClear = null }
            )
            "all" -> ConfirmDialog(
                title = "Delete ALL browsing data?",
                message = "This deletes every cookie, login, site data, cache and your whole history. It cannot be undone.",
                confirmLabel = "Delete everything",
                requireTick = true,
                onConfirm = viewModel::clearEverythingNow,
                onDismiss = { askClear = null }
            )
        }
        ListDivider()
    }
}
