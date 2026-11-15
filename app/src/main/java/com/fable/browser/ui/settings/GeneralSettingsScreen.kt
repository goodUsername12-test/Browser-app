package com.fable.browser.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.DefaultBrowserEngine
import com.fable.browser.data.model.SearchEngine
import com.fable.browser.engine.FableProxyProfile
import com.fable.browser.engine.FableProxyProfileManager
import com.fable.browser.engine.FableProxyProtocol
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SwitchRow

@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val proxyState by FableProxyProfileManager.state.collectAsStateWithLifecycle()
    var homepage by remember(settings.homepage) { mutableStateOf(settings.homepage) }
    var showAddProxyDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureBasicProxiesLoaded(context)
    }

    SettingsPage(title = "General (Basic)", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Default browser engine")
        RadioGroup(
            options = DefaultBrowserEngine.entries.toList(),
            selected = settings.defaultEngine,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::setDefaultEngine
        )
        ListDivider()

        // --- Fable-Only Proxy Profiles (Super Proxy style cards in Basic tab) ---
        SectionHeader("Proxy Profiles (Fable-Only · SOCKS5 / HTTP)")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${proxyState.statusMessage}\nRoutes only Fable Browser tabs (Blink + GeckoView); other phone apps stay unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            proxyState.profiles.forEach { prof ->
                val isActive = proxyState.activeProfileId == prof.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = prof.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = prof.endpointLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF10B981), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = prof.protocol.badge,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Switch(
                                checked = isActive,
                                onCheckedChange = { checked ->
                                    viewModel.toggleBasicProxyActive(context, if (checked) prof.id else null)
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.testBasicProxyLatency(prof) }) {
                                Text("Test")
                            }
                            TextButton(onClick = { viewModel.deleteBasicProxy(context, prof.id) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showAddProxyDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Add Proxy")
                }
                if (proxyState.activeProfileId != null) {
                    OutlinedButton(onClick = { viewModel.toggleBasicProxyActive(context, null) }) {
                        Text("Disable Proxy")
                    }
                }
            }
        }
        ListDivider()

        SectionHeader("Search engine")
        RadioGroup(
            options = SearchEngine.entries.toList(),
            selected = settings.searchEngine,
            label = { it.label },
            description = { engine ->
                when (engine) {
                    SearchEngine.GOOGLE -> "Best results, most tracking"
                    SearchEngine.DUCKDUCKGO -> "No search history profile"
                    SearchEngine.BING -> "Microsoft index"
                    SearchEngine.BRAVE -> "Independent index"
                    SearchEngine.STARTPAGE -> "Google results, proxied"
                    SearchEngine.ECOSIA -> "Bing results, trees planted"
                }
            },
            onSelect = viewModel::setSearchEngine
        )
        ListDivider()

        SectionHeader("Startup")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = homepage,
                onValueChange = { homepage = it },
                label = { Text("Homepage") },
                placeholder = { Text("about:blank") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Opened by new tabs and at startup. Leave as about:blank for the fastest, quietest start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            TextButton(
                onClick = { viewModel.setHomepage(homepage) },
                enabled = homepage.trim() != settings.homepage,
                modifier = Modifier.align(Alignment.End)
            ) { Text("Save homepage") }
        }
        ListDivider()
        SwitchRow(
            title = "Restore tabs on launch",
            subtitle = "Normal tabs only. Incognito tabs are never written to disk, so they can never come back.",
            checked = settings.restoreTabs,
            onCheckedChange = viewModel::setRestoreTabs
        )
        ListDivider()
        SwitchRow(
            title = "Address bar at the top",
            subtitle = "Turn off to move the address bar and buttons to the bottom of the screen.",
            checked = settings.toolbarAtTop,
            onCheckedChange = viewModel::setToolbarAtTop
        )
        ListDivider()
        SwitchRow(
            title = "Hide toolbar while scrolling",
            subtitle = "Gives pages more room, but the page changes size while you scroll. Keep this off for games.",
            checked = settings.hideToolbarOnScroll,
            onCheckedChange = viewModel::setHideToolbarOnScroll
        )
        ListDivider()
    }

    if (showAddProxyDialog) {
        var name by remember { mutableStateOf("") }
        var proto by remember { mutableStateOf(FableProxyProtocol.SOCKS5) }
        var host by remember { mutableStateOf("") }
        var portText by remember { mutableStateOf("1080") }
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var errText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddProxyDialog = false },
            title = { Text("Add Fable Proxy Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FableProxyProtocol.entries.forEach { p ->
                            FilterChip(
                                selected = proto == p,
                                onClick = { proto = p },
                                label = { Text(p.badge) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile name (optional)") },
                        placeholder = { Text("New profile") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = {
                            val v = it.trim()
                            // Conveniently split pasted "23.26.71.145:5628" automatically!
                            if (v.count { c -> c == ':' } == 1 && v.substringAfter(':').all { c -> c.isDigit() }) {
                                host = v.substringBefore(':')
                                portText = v.substringAfter(':')
                            } else {
                                host = it
                            }
                        },
                        label = { Text("Host or IP (or paste IP:Port)") },
                        placeholder = { Text("23.26.71.145") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Port") },
                        placeholder = { Text("5628") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                        label = { Text("Password (optional, encrypted)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errText.isNotBlank()) {
                        Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val port = portText.toIntOrNull() ?: -1
                        val res = viewModel.addOrUpdateBasicProxy(
                            context = context,
                            profile = FableProxyProfile(
                                name = name,
                                protocol = proto,
                                host = host,
                                port = port,
                                username = user,
                                password = pass
                            ),
                            activateNow = true
                        )
                        res.fold(
                            onSuccess = { showAddProxyDialog = false },
                            onFailure = { errText = it.message ?: "Invalid proxy details" }
                        )
                    }
                ) {
                    Text("Save & Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProxyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
