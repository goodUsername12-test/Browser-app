package com.fable.browser.ui.settings

import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.agent.AgentAutonomyPolicy
import com.fable.browser.agent.AgentLiveState
import com.fable.browser.agent.AgentMode
import com.fable.browser.agent.AgentRiskZone
import com.fable.browser.agent.FableLocalLlmRuntime
import com.fable.browser.agent.FableOwnerAuthManager
import com.fable.browser.agent.RiskZonePolicy
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

private fun Context.findFragmentActivity(): FragmentActivity? {
    var cur: Context? = this
    while (cur is ContextWrapper) {
        if (cur is FragmentActivity) return cur
        cur = cur.baseContext
    }
    return null
}

@Composable
fun AgentSettingsScreen(
    onBack: () -> Unit,
    agentState: AgentLiveState,
    onDownloadCompactModel: (String) -> Unit,
    onImportModelUri: (Uri) -> Unit,
    onLoadModel: (Boolean) -> Unit = {},
    onUnloadModel: () -> Unit = {},
    onCreateOwnerPassword: (String) -> Result<Unit> = { Result.success(Unit) },
    onUnlockOwnerPassword: (String) -> Result<Unit> = { Result.success(Unit) },
    onBiometricOwnerResult: (Boolean, String) -> Unit = { _, _ -> },
    onLockOwnerMode: () -> Unit = {},
    onResetOwnerMode: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val s by viewModel.settings.collectAsStateWithLifecycle()
    var endpointText by remember(s.agentLocalLlmEndpoint) { mutableStateOf(s.agentLocalLlmEndpoint) }

    var ownerPassInput by remember { mutableStateOf("") }
    var ownerPassConfirm by remember { mutableStateOf("") }
    var ownerFeedback by remember { mutableStateOf("") }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportModelUri(uri)
    }

    SettingsPage(title = "Fable AI Agent, Models & Owner Beta", onBack = onBack, viewModel = viewModel) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Dual-Model Native Agent v45 · ${agentState.freeStorageGb} GB Free Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Engine: ${FableLocalLlmRuntime.backendStatusSummary()}\nActive Brain: ${agentState.activeModelBadge}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // --- 1. Dual-Model Local GGUF Vault (Ai_models GitHub Release) ---
        SectionHeader("Dual-Model Local GGUF Vault (${agentState.freeStorageGb} GB Free)")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = "Specialized Fable Model Architecture:\n" +
                    "• Primary (Qwen3.5-0.8B Q4_K_M, ~508 MB): Conversation, multi-step planning, YouTube strategy synthesis & Owner Research\n" +
                    "• Fast (Qwen3-0.6B Q4_K_M, ~378 MB): Instant /no_think commands, typing, camera & button holds\n" +
                    "• Gaming RAM Guard: Automatically unloads GGUF models before 50-100ms native Rust/Vision loops.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Installed Models:\n• " + agentState.installedModels.joinToString("\n• "),
                style = MaterialTheme.typography.bodySmall
            )
            if (agentState.modelDownloadStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⬇️ ${agentState.modelDownloadStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onDownloadCompactModel("download_both") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download Both Fable Models to Vault (~886 MB)")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDownloadCompactModel("qwen35_0_8b_q4") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Get 0.8B (508 MB)")
                }
                OutlinedButton(
                    onClick = { onDownloadCompactModel("qwen3_0_6b_q4") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Get 0.6B (378 MB)")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { modelPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Downloaded .gguf File from Phone")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLoadModel(false) }, modifier = Modifier.weight(1f)) {
                    Text("Load 0.8B")
                }
                Button(onClick = { onLoadModel(true) }, modifier = Modifier.weight(1f)) {
                    Text("Load 0.6B")
                }
                OutlinedButton(onClick = onUnloadModel) {
                    Text("Free RAM")
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = endpointText,
                onValueChange = { endpointText = it },
                label = { Text("Optional External / Local HTTP Endpoint") },
                placeholder = { Text("http://127.0.0.1:8080/v1/chat/completions") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { viewModel.setAgentLocalLlmEndpoint(endpointText) }) {
                Text("Save Optional Endpoint")
            }
        }
        ListDivider()

        // --- 2. Owner-Only Research Mode · Beta (Hardware Keystore + Read-Only Guard) ---
        SectionHeader("Owner Research Mode · Beta (Owner Password + Read-Only Guard)")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (agentState.ownerResearchUnlocked) Color(0xFF431407) else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = if (agentState.ownerResearchUnlocked) {
                            "🔓 OWNER RESEARCH MODE · ACTIVE (${agentState.ownerUnlockRemainingSec / 60}m left)"
                        } else if (agentState.ownerPasswordConfigured) {
                            "🔒 Owner Research Mode · Locked (Owner Password Configured)"
                        } else {
                            "🔐 Owner Research Mode · Not Configured Yet"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (agentState.ownerResearchUnlocked) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Unlocks private local research conversation & deep read-only page/source/header inspection for you as the device owner. Non-bypassable guards remain permanently enforced:\n" +
                            "• Strictly READ-ONLY on pages (0% JS, CSS, or DOM injection)\n" +
                            "• Gaming Mode stays 100% strict Screen-Only\n" +
                            "• Emergency Stop (■ STOP) & Irreversible Action confirmations remain active",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (agentState.ownerResearchUnlocked) Color(0xFFFED7AA) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (agentState.ownerResearchUnlocked) {
                Button(
                    onClick = {
                        onLockOwnerMode()
                        ownerFeedback = "Owner Research Mode locked."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lock Owner Research Mode Now", color = Color.White)
                }
            } else if (!agentState.ownerPasswordConfigured) {
                Text(
                    text = "Create your private owner password (minimum 8 chars, 16+ recommended). Protected via Android Keystore AES-256-GCM + PBKDF2-SHA256; never stored in plain text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ownerPassInput,
                    onValueChange = { ownerPassInput = it },
                    label = { Text("Create Owner Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ownerPassConfirm,
                    onValueChange = { ownerPassConfirm = it },
                    label = { Text("Confirm Owner Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (ownerPassInput != ownerPassConfirm) {
                            ownerFeedback = "Passwords do not match."
                        } else {
                            val res = onCreateOwnerPassword(ownerPassInput)
                            ownerFeedback = res.fold(
                                onSuccess = {
                                    ownerPassInput = ""
                                    ownerPassConfirm = ""
                                    "Owner password created & Owner Research Mode unlocked!"
                                },
                                onFailure = { it.message ?: "Could not create password." }
                            )
                        }
                    },
                    enabled = ownerPassInput.length >= 8,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Owner Password & Unlock")
                }
            } else {
                OutlinedTextField(
                    value = ownerPassInput,
                    onValueChange = { ownerPassInput = it },
                    label = { Text("Enter Owner Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val res = onUnlockOwnerPassword(ownerPassInput)
                            ownerFeedback = res.fold(
                                onSuccess = {
                                    ownerPassInput = ""
                                    "Owner Research Mode unlocked!"
                                },
                                onFailure = { it.message ?: "Incorrect password." }
                            )
                        },
                        enabled = ownerPassInput.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Unlock with Password")
                    }
                    if (FableOwnerAuthManager.canUseBiometrics(ctx)) {
                        OutlinedButton(
                            onClick = {
                                val act = ctx.findFragmentActivity()
                                if (act != null) {
                                    FableOwnerAuthManager.promptBiometricUnlock(act, s.ownerAutoLockMinutes) { ok, msg ->
                                        ownerFeedback = msg
                                        onBiometricOwnerResult(ok, msg)
                                    }
                                }
                            }
                        ) {
                            Text("Biometric")
                        }
                    }
                }
                TextButton(
                    onClick = {
                        onResetOwnerMode()
                        ownerPassInput = ""
                        ownerFeedback = "Owner credentials wiped. You can create a new password above."
                    }
                ) {
                    Text("Forgot Password? Wipe & Reset Owner Verifier")
                }
            }

            if (ownerFeedback.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ownerFeedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        SliderRow(
            title = "Owner Mode auto-lock timeout",
            value = s.ownerAutoLockMinutes,
            range = 5f..120f,
            steps = 22,
            suffix = " min",
            onValueChange = viewModel::setOwnerAutoLockMinutes
        )
        ListDivider()

        // --- 3. Operating Mode & Autonomy ---
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
            subtitle = "Displays active action, latency (ms), Owner Beta badge, and 1-tap red STOP button",
            checked = s.agentShowHudOverlay,
            onCheckedChange = viewModel::setAgentShowHudOverlay
        )
        ListDivider()

        // --- 4. Gaming Speed, Camera & Multi-Touch Button Calibration ---
        SectionHeader("Fast Gaming Speed, Camera & Button Calibration")
        SliderRow(
            title = "Gaming reaction loop interval (50-100 ms target)",
            value = s.agentReactionIntervalMs,
            range = 40f..160f,
            steps = 11,
            suffix = " ms",
            onValueChange = viewModel::setAgentReactionIntervalMs
        )
        SliderRow(
            title = "Camera turn sensitivity",
            value = s.agentCameraSensitivityPct,
            range = 40f..200f,
            steps = 15,
            suffix = "%",
            onValueChange = viewModel::setAgentCameraSensitivityPct
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
        SliderRow(
            title = "Primary Attack / Mine / Hold Button X (%)",
            value = s.agentAttackBtnX,
            range = 50f..95f,
            steps = 14,
            suffix = "%",
            onValueChange = viewModel::setAgentAttackBtnX
        )
        SliderRow(
            title = "Primary Attack / Mine / Hold Button Y (%)",
            value = s.agentAttackBtnY,
            range = 30f..90f,
            steps = 19,
            suffix = "%",
            onValueChange = viewModel::setAgentAttackBtnY
        )
        SliderRow(
            title = "Jump / Secondary Action Button X (%)",
            value = s.agentJumpBtnX,
            range = 50f..95f,
            steps = 14,
            suffix = "%",
            onValueChange = viewModel::setAgentJumpBtnX
        )
        SliderRow(
            title = "Jump / Secondary Action Button Y (%)",
            value = s.agentJumpBtnY,
            range = 35f..92f,
            steps = 18,
            suffix = "%",
            onValueChange = viewModel::setAgentJumpBtnY
        )
        ListDivider()

        // --- 5. Adjustable Risk-Zone Permissions ---
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
    }
}
