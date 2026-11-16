package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.mcp.McpSessionManager
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun McpSettingsScreen(onBack: () -> Unit) {
    val state by McpSessionManager.state.collectAsStateWithLifecycle()
    var endpoint by remember { mutableStateOf(state.endpoint.ifBlank { "local://fable-mcp" }) }
    var duration by remember { mutableFloatStateOf(60f) }
    var automaticAuth by remember { mutableStateOf(state.automaticAuth) }
    var customHeaders by remember { mutableStateOf(state.customHeaders) }
    var customHeaderText by remember { mutableStateOf(state.customHeaderText) }
    var toolArgsText by remember { mutableStateOf("{\"url\":\"https://bloxd.io\"}") }

    LaunchedEffect(state.endpoint) {
        if (state.endpoint.isNotBlank()) endpoint = state.endpoint
    }

    SettingsPage(title = "MCP connection & live control", onBack = onBack) {
        // IMPORTANT: SettingsPage already wraps content in Column(Modifier.verticalScroll()).
        // Use a regular Column here so Compose never throws Infinity maximum height constraints!
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (state.connected) "● MCP Connected (${state.remainingMinutes}m left)" else "○ MCP Disconnected",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Status: ${state.status}\nLive AI Action: ${state.currentAction}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text(
                text = "Connect an HTTP(S) MCP server (such as Dust MCP) or use local://fable-mcp for instant no-login live browser control.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MCP server URL") },
                placeholder = { Text("local://fable-mcp or https://dust.tt/api/mcp") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show Live AI HUD in Browser")
                    Text(
                        "Displays a live floating panel & click marker in the browser while MCP is active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.showLiveHud,
                    onCheckedChange = { McpSessionManager.setShowLiveHud(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Automatic authentication")
                Switch(checked = automaticAuth, onCheckedChange = { automaticAuth = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Use custom headers / Bearer token")
                Switch(checked = customHeaders, onCheckedChange = { customHeaders = it })
            }

            if (customHeaders) {
                OutlinedTextField(
                    value = customHeaderText,
                    onValueChange = {
                        customHeaderText = it
                        McpSessionManager.setCustomHeaderText(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Authorization Bearer token or Header: Value") },
                    placeholder = { Text("Bearer sk-... or X-API-Key: ...") },
                    singleLine = false,
                    maxLines = 3
                )
            }

            Column(Modifier.fillMaxWidth()) {
                val mins = duration.roundToInt()
                Text("Session lifetime: $mins minute${if (mins == 1) "" else "s"}")
                Slider(
                    value = duration,
                    onValueChange = { duration = it },
                    valueRange = 1f..1440f
                )
                Text(
                    "1 minute to 24 hours (default 1 hour). Automatically disconnects when expired.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !state.connecting,
                    onClick = {
                        McpSessionManager.connect(
                            endpoint = endpoint,
                            durationMinutes = duration.roundToInt(),
                            automaticAuth = automaticAuth,
                            customHeaders = customHeaders,
                            customHeaderText = customHeaderText
                        )
                    }
                ) {
                    Text(if (state.connected) "Reconnect" else "Connect")
                }
                OutlinedButton(
                    enabled = !state.connecting,
                    onClick = {
                        endpoint = "local://fable-mcp"
                        McpSessionManager.connect(
                            endpoint = "local://fable-mcp",
                            durationMinutes = duration.roundToInt(),
                            automaticAuth = automaticAuth,
                            customHeaders = false,
                            customHeaderText = customHeaderText
                        )
                    }
                ) {
                    Text("Start Local MCP")
                }
                TextButton(
                    enabled = state.connected || state.connecting,
                    onClick = { McpSessionManager.disconnect() }
                ) {
                    Text("Stop")
                }
            }

            HorizontalDivider()

            Text(
                text = "Tool Arguments (JSON for testing live browser actions)",
                style = MaterialTheme.typography.labelLarge
            )
            OutlinedTextField(
                value = toolArgsText,
                onValueChange = { toolArgsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("JSON arguments") },
                placeholder = { Text("{\"url\":\"https://bloxd.io\"}") },
                singleLine = true
            )

            Text(
                text = "Available MCP tools (${state.tools.size})",
                style = MaterialTheme.typography.titleSmall
            )
            state.tools.forEach { tool ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = tool.name + if (tool.builtIn) " · Live Browser Tool" else " · Remote MCP",
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (tool.description.isNotBlank()) {
                            Text(
                                text = tool.description.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = state.connected,
                                onClick = {
                                    val parsedArgs = runCatching { JSONObject(toolArgsText) }.getOrDefault(JSONObject())
                                    McpSessionManager.callTool(tool.name, parsedArgs)
                                }
                            ) {
                                Text("Run tool live")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "Live activity (${state.activity.size})",
                style = MaterialTheme.typography.titleSmall
            )
            if (state.activity.isEmpty()) {
                Text(
                    text = "No MCP actions recorded yet. Tap 'Start Local MCP' or 'Connect' above to begin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.activity.asReversed().forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
