package com.fable.browser.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.agent.AgentAutonomyPolicy
import com.fable.browser.agent.AgentLiveState
import com.fable.browser.agent.AgentMode
import com.fable.browser.agent.AgentRiskZone
import com.fable.browser.agent.RiskZonePolicy
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

@Composable
fun AgentSettingsScreen(
    onBack: () -> Unit,
    agentState: AgentLiveState,
    onDownloadCompactModel: (String) -> Unit,
    onImportModelUri: (Uri) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    var endpointText by remember(s.agentLocalLlmEndpoint) { mutableStateOf(s.agentLocalLlmEndpoint) }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportModelUri(uri)
    }

    SettingsPage(title = "Fable AI Agent & Risk Zones", onBack = onBack, viewModel = viewModel) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Compact Multi-Mode Visual Agent · ${agentState.freeStorageGb} GB Free Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Gaming Mode runs 100% strictly on screen pixels + native Android multi-touch (zero console or page-code access) with an 80 ms safety watchdog. Basic & Long Task modes combine live search, OCR, adaptive load waiting, and 12-stream .zip archiving.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        SectionHeader("Default AI Operating Mode")
        RadioGroup(
            options = AgentMode.entries.toList(),
            selected = s.agentDefaultMode,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::setAgentDefaultMode
        )
        ListDivider()

        SectionHeader("Autonomy & Confirmation Behavior")
        RadioGroup(
            options = AgentAutonomyPolicy.entries.toList(),
            selected = s.agentAutonomyPolicy,
            label = { it.label },
            description = { it.summary },
            onSelect = viewModel::setAgentAutonomyPolicy
        )
        SwitchRow(
            title = "Show live AI control & Emergency Stop HUD pill",
            subtitle = "Displays active action, latency (ms), countdown, and 1-tap red STOP button while AI is running",
            checked = s.agentShowHudOverlay,
            onCheckedChange = viewModel::setAgentShowHudOverlay
        )
        ListDivider()

        SectionHeader("Fast Gaming Mode Speed & Joystick Calibration")
        SliderRow(
            title = "Gaming reaction loop interval (50-100 ms target)",
            value = s.agentReactionIntervalMs,
            range = 40f..160f,
            steps = 11,
            suffix = " ms",
            onValueChange = viewModel::setAgentReactionIntervalMs
        )
        SliderRow(
            title = "On-screen Joystick Center X (% from left)",
            value = s.agentJoystickCenterX,
            range = 8f..40f,
            steps = 15,
            suffix = "%",
            onValueChange = viewModel::setAgentJoystickCenterX
        )
        SliderRow(
            title = "On-screen Joystick Center Y (% from top)",
            value = s.agentJoystickCenterY,
            range = 50f..90f,
            steps = 19,
            suffix = "%",
            onValueChange = viewModel::setAgentJoystickCenterY
        )
        ListDivider()

        SectionHeader("Adjustable Risk-Zone Permissions")
        Text(
            text = "Configure exactly what Fable AI may do automatically, ask once per session, ask every time, or block:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        AgentRiskZone.entries.forEach { zone ->
            val currentPolicy = s.policyForZone(zone)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(zone.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(zone.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RiskZonePolicy.entries.forEach { pol ->
                        FilterChip(
                            selected = currentPolicy == pol,
                            onClick = { viewModel.setRiskZonePolicy(zone, pol) },
                            label = {
                                Text(
                                    when (pol) {
                                        RiskZonePolicy.ALLOWED -> "Auto"
                                        RiskZonePolicy.ASK_ONCE -> "Ask 1x"
                                        RiskZonePolicy.ASK_EVERY_TIME -> "Always Ask"
                                        RiskZonePolicy.BLOCKED -> "Block"
                                    }
                                )
                            }
                        )
                    }
                }
            }
            ListDivider()
        }

        SectionHeader("AI Models & Local Storage Vault (${agentState.freeStorageGb} GB Free)")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = "Active Models:\n• " + agentState.installedModels.joinToString("\n• "),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDownloadCompactModel("qwen_0_5b_q4") }, modifier = Modifier.weight(1f)) {
                    Text("Get 0.5B Q4 (~395 MB)")
                }
                OutlinedButton(onClick = { onDownloadCompactModel("llama_1b_q4") }, modifier = Modifier.weight(1f)) {
                    Text("Get 1B Q4 (~770 MB)")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { modelPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Downloaded .gguf / .onnx Model into Vault")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = endpointText,
                onValueChange = { endpointText = it },
                label = { Text("Optional Local llama.cpp / Ollama / Fast API Endpoint") },
                placeholder = { Text("http://127.0.0.1:8080/v1/chat/completions") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                text = "Format: URL  or  URL|API_KEY|MODEL_NAME. Automatically paused during 50-100ms Gaming loops to save RAM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = { viewModel.setAgentLocalLlmEndpoint(endpointText) }) {
                Text("Save Model Endpoint")
            }
        }
        ListDivider()
    }
}
