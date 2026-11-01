package com.fable.browser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.SuperDeviceRange
import com.fable.browser.data.model.SuperProxyType
import com.fable.browser.engine.SuperIdentity
import com.fable.browser.engine.SuperIdentityFactory
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperIncognitoSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val identity by viewModel.superIdentity.collectAsStateWithLifecycle()
    var showRange by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showKeepWarning by remember { mutableStateOf(false) }
    var showNewConfirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SuperIdentity?>(null) }
    var host by remember(settings.superProxyHost) { mutableStateOf(settings.superProxyHost) }
    var port by remember(settings.superProxyPort) { mutableStateOf(settings.superProxyPort.toString()) }
    var user by remember { mutableStateOf(viewModel.superProxyUser()) }
    var pass by remember { mutableStateOf("") }
    val hasPassword = remember(user) { viewModel.superProxyHasPassword() }

    SettingsPage(title = "Super incognito", onBack = onBack, viewModel = viewModel) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Every session is a different device", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Each new super incognito session gets a random fake device: browser, system, screen, CPU, " +
                        "graphics card, fonts, languages, time zone, plus random canvas, WebGL, audio and layout " +
                        "noise. All super incognito tabs in one session share the same identity, like one real " +
                        "device. Closing the last super tab ends the session.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        SectionHeader("Current identity")
        val current = identity
        if (current == null) {
            SettingRow(
                title = "No super incognito session",
                subtitle = "A new random identity is made when you open a super incognito tab",
                icon = Icons.Outlined.Security
            )
        } else {
            current.details().forEach { (name, value) ->
                SettingRow(title = name, subtitle = value)
            }
            SettingRow(
                title = "New identity now",
                subtitle = "Closes all super incognito tabs so the next one is a brand new device",
                icon = Icons.Outlined.Refresh,
                onClick = { showNewConfirm = true }
            )
        }
        SettingRow(
            title = "Preview a random identity",
            subtitle = "Shows an example of what the next identity could look like",
            icon = Icons.Outlined.Casino,
            onClick = { preview = viewModel.previewSuperIdentity() }
        )
        ListDivider()

        SectionHeader("Fake device")
        SettingRow(
            title = "Device",
            subtitle = if (settings.superDevicePick.isBlank()) {
                "Random device every session"
            } else {
                settings.superDevicePick + " (everything else stays random)"
            },
            icon = Icons.Outlined.Devices,
            onClick = { showDevices = true }
        )
        if (settings.superDevicePick.isBlank()) {
            SettingRow(
                title = "Pick random devices from",
                subtitle = settings.superDeviceRange.label + ". " + settings.superDeviceRange.summary,
                icon = Icons.Outlined.Public,
                onClick = { showRange = true }
            )
        }
        SwitchRow(
            title = "Random time zone",
            subtitle = "Clocks on websites use a random time zone. Turn off if sites show wrong times for you",
            checked = settings.superRandomTimezone,
            icon = Icons.Outlined.Schedule,
            onCheckedChange = viewModel::setSuperRandomTimezone
        )
        ListDivider()

        SectionHeader("After closing the app")
        SwitchRow(
            title = "Keep super incognito tabs",
            subtitle = "Reopen super tabs with the same identity next time. Their cookies are kept encrypted on " +
                "this phone until you close the tabs",
            checked = settings.keepSuperTabs,
            icon = Icons.Outlined.Inventory2,
            onCheckedChange = { on -> if (on) showKeepWarning = true else viewModel.setKeepSuperTabs(false) }
        )
        ListDivider()

        SectionHeader("Super proxy")
        SwitchRow(
            title = "Use a proxy for super incognito",
            subtitle = if (settings.superProxyEnabled && settings.superProxyHost.isBlank()) {
                "Enter the proxy address below"
            } else {
                "Only super incognito tabs use it. If the proxy fails, pages do not load instead of leaking your IP"
            },
            checked = settings.superProxyEnabled,
            icon = Icons.Outlined.VpnLock,
            onCheckedChange = viewModel::setSuperProxyEnabled
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            SuperProxyType.values().forEach { type ->
                FilterChip(
                    selected = settings.superProxyType == type,
                    onClick = { viewModel.setSuperProxyType(type) },
                    label = { Text(type.label) }
                )
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Proxy IP or host") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = port,
                onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = {
                    viewModel.setSuperProxyHost(host)
                    viewModel.setSuperProxyPortText(port)
                }) { Text("Save address") }
            }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text(if (hasPassword) "Password (saved, type to change)" else "Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = {
                    user = ""
                    pass = ""
                    viewModel.setSuperProxyLogin("", "")
                }) { Text("Remove login") }
                TextButton(onClick = {
                    val keep = if (pass.isEmpty() && hasPassword && user.isNotBlank()) null else pass
                    if (keep == null) {
                        viewModel.setSuperProxyLogin(user, viewModel.currentSuperProxyPassword())
                    } else {
                        viewModel.setSuperProxyLogin(user, keep)
                    }
                    pass = ""
                }) { Text("Save login") }
            }
            Text(
                "SOCKS5 also hides your DNS lookups. HTTP and HTTPS proxies work for websites; the login is stored " +
                    "encrypted with the Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
        }
    }

    if (showRange) {
        AlertDialog(
            onDismissRequest = { showRange = false },
            title = { Text("Pick random devices from") },
            text = {
                RadioGroup(
                    options = SuperDeviceRange.values().toList(),
                    selected = settings.superDeviceRange,
                    label = { it.label },
                    description = { it.summary },
                    onSelect = {
                        viewModel.setSuperDeviceRange(it)
                        showRange = false
                    }
                )
            },
            confirmButton = { TextButton(onClick = { showRange = false }) { Text("Close") } }
        )
    }

    if (showDevices) {
        val choices = remember { SuperIdentityFactory.deviceChoices() }
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text("Fake device") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    item {
                        DeviceOption(
                            label = "Random every session (recommended)",
                            selected = settings.superDevicePick.isBlank(),
                            onClick = {
                                viewModel.setSuperDevicePick("")
                                showDevices = false
                            }
                        )
                    }
                    var lastGroup = ""
                    val rows = ArrayList<Pair<String?, SuperIdentityFactory.Choice>>()
                    choices.forEach { c ->
                        rows += (if (c.group != lastGroup) c.group else null) to c
                        lastGroup = c.group
                    }
                    items(rows) { (group, choice) ->
                        Column {
                            if (group != null) {
                                Text(
                                    group,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                                )
                            }
                            DeviceOption(
                                label = choice.label,
                                selected = settings.superDevicePick == choice.label,
                                onClick = {
                                    viewModel.setSuperDevicePick(choice.label)
                                    showDevices = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Close") } }
        )
    }

    if (showKeepWarning) {
        AlertDialog(
            onDismissRequest = { showKeepWarning = false },
            title = { Text("Keep super incognito tabs?") },
            text = {
                Text(
                    "Super tabs, their identity and their cookies will be saved encrypted on this phone so they " +
                        "come back after you close the app. Anyone who can unlock this phone and open Fable could " +
                        "see them. Close the tabs or use New identity to delete them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setKeepSuperTabs(true)
                    showKeepWarning = false
                }) { Text("Keep them") }
            },
            dismissButton = { TextButton(onClick = { showKeepWarning = false }) { Text("Cancel") } }
        )
    }

    if (showNewConfirm) {
        AlertDialog(
            onDismissRequest = { showNewConfirm = false },
            title = { Text("Start a new identity?") },
            text = { Text("All super incognito tabs will close and their data is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.newSuperIdentity()
                    showNewConfirm = false
                }) { Text("New identity") }
            },
            dismissButton = { TextButton(onClick = { showNewConfirm = false }) { Text("Cancel") } }
        )
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Example identity") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(p.details()) { (name, value) ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { preview = viewModel.previewSuperIdentity() }) { Text("Another") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun DeviceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
