package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.mcp.McpSessionManager
import kotlin.math.roundToInt

@Composable
fun McpSettingsScreen(onBack: () -> Unit) {
    val state by McpSessionManager.state.collectAsStateWithLifecycle()
    var endpoint by remember { mutableStateOf(state.endpoint) }
    var duration by remember { mutableFloatStateOf(60f) }
    var automaticAuth by remember { mutableStateOf(true) }
    var customHeaders by remember { mutableStateOf(false) }
    LaunchedEffect(state.endpoint) { if (state.endpoint.isNotBlank()) endpoint = state.endpoint }
    SettingsPage(title = "MCP connection", onBack = onBack) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Connect an MCP server like the Dust tools screen. Automatic authentication can open the provider's normal authorization flow when the endpoint requires it.")
            }
            item {
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("MCP server URL") }, placeholder = { Text("https://example.com/api/mcp") }, singleLine = true)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Automatic authentication")
                    Switch(automaticAuth, { automaticAuth = it })
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Use custom headers")
                    Switch(customHeaders, { customHeaders = it })
                }
            }
            item {
                Text("Session lifetime: ${duration.roundToInt()} minute${if (duration.roundToInt() == 1) "" else "s"}")
                Slider(duration, { duration = it }, valueRange = 1f..1440f, steps = 1438)
                Text("1 minute to 24 hours. The default is 1 hour.")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !state.connecting && !state.connected, onClick = { McpSessionManager.connect(endpoint, duration.roundToInt(), automaticAuth, customHeaders) }) { Text("Connect") }
                    TextButton(enabled = state.connected || state.connecting, onClick = { McpSessionManager.disconnect() }) { Text("Stop") }
                }
            }
            item { Text("Status: ${state.status}") }
            item { Text("Available MCP tools: ${state.tools.size}") }
            items(state.tools) { tool ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(tool.name)
                    if (tool.description.isNotBlank()) Text(tool.description.take(180))
                    TextButton(enabled = state.connected, onClick = { McpSessionManager.callTool(tool.name) }) { Text("Test tool") }
                }
            }
            item { HorizontalDivider() }
            item { Text("Live activity") }
            items(state.activity.asReversed()) { line -> Text(line, Modifier.padding(vertical = 2.dp)) }
        }
    }
}
