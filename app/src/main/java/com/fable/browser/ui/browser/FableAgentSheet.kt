package com.fable.browser.ui.browser

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fable.browser.agent.AgentLiveState
import com.fable.browser.agent.AgentMode
import com.fable.browser.agent.ConfirmationDecision

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FableAgentSheet(
    state: AgentLiveState,
    onDismiss: () -> Unit,
    onSelectMode: (AgentMode) -> Unit,
    onSendInstruction: (String) -> Unit,
    onStopAll: () -> Unit,
    onReadScreenNow: () -> Unit,
    onUploadVideo: (Uri) -> Unit,
    onResolveConfirmation: (ConfirmationDecision) -> Unit,
    onOpenAgentSettings: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var promptText by rememberSaveable { mutableStateOf("") }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onUploadVideo(uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Fable AI Agent",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append(state.statusLine)
                            if (state.strictScreenOnlyActive) append(" · Screen-Only")
                            if (state.lastReactionMs > 0L) append(" · ${state.lastReactionMs}ms")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.running || state.activeInputsLabel != "None") {
                    Button(
                        onClick = onStopAll,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("■ STOP", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = onOpenAgentSettings) {
                        Text("Settings & Risk")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Mode Selector Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgentMode.entries.forEach { m ->
                    FilterChip(
                        selected = state.mode == m,
                        onClick = { onSelectMode(m) },
                        label = { Text(m.shortLabel) }
                    )
                }
            }

            // Pending Risk-Zone Confirmation Card
            val conf = state.pendingConfirmation
            if (conf != null) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "⚠️ Confirmation Required: ${conf.title}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = conf.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onResolveConfirmation(ConfirmationDecision.ALLOW_ONCE) }) {
                                Text("Allow Once")
                            }
                            if (conf.allowSessionOption) {
                                OutlinedButton(onClick = { onResolveConfirmation(ConfirmationDecision.ALLOW_SESSION) }) {
                                    Text("Allow Session")
                                }
                            }
                            TextButton(onClick = { onResolveConfirmation(ConfirmationDecision.CANCEL) }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Action Tools Row: Read Screen + Upload Video to Learn + Learned Skills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onReadScreenNow,
                    label = { Text("👁 Read Screen") }
                )
                AssistChip(
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    label = { Text("🎬 Learn from Video") }
                )
                state.learnedSkills.take(4).forEach { sk ->
                    AssistChip(
                        onClick = { onSendInstruction("run skill ${sk.name}") },
                        label = { Text("▶ ${sk.name}") }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Conversation & Live Action Log
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 250.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.logs.takeLast(18).forEach { entry ->
                    Text(
                        text = "[${entry.timeLabel}] ${entry.role}: ${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.role) {
                            "User" -> MaterialTheme.colorScheme.primary
                            "System" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Quick Example Prompts for Current Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val presets = when (state.mode) {
                    AgentMode.BASIC_BROWSING -> listOf(
                        "go to example.com and copy the page text and deliver whole page code as in zip file",
                        "what is on screen right now?",
                        "search WebGPU browser benchmarks"
                    )
                    AgentMode.GAMING_SCREEN_ONLY -> listOf(
                        "go to the nearest cow you see on the screen",
                        "follow player X and stop doing it in 10 seconds",
                        "hold forward for 3 seconds and jump"
                    )
                    AgentMode.LONG_TASK -> listOf(
                        "open analytics, members, upgrade sections and check them for 5 minutes, if no changes stop",
                        "scroll down 3 times"
                    )
                }
                presets.forEach { ex ->
                    AssistChip(
                        onClick = { promptText = ex },
                        label = { Text(ex.take(34) + if (ex.length > 34) "…" else "") }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                placeholder = { Text("Ask anything or give a Browsing / Gaming / Long Task command...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val toSend = promptText.trim()
                        if (toSend.isNotEmpty()) {
                            onSendInstruction(toSend)
                            promptText = ""
                            if (state.mode == AgentMode.GAMING_SCREEN_ONLY) {
                                onDismiss()
                            }
                        }
                    },
                    enabled = promptText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.mode == AgentMode.GAMING_SCREEN_ONLY) "Run on Screen (Close Sheet)" else "Send / Run")
                }
                OutlinedButton(onClick = onOpenAgentSettings) {
                    Text("Risk Zones")
                }
            }
        }
    }
}
