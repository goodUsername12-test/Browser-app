package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.fable.browser.ui.components.ConfirmDialog
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.PermissionDecision
import com.fable.browser.data.model.PermissionKind
import com.fable.browser.data.model.SitePermission
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader

/**
 * Per-site permissions. Rows only exist for sites the user actually answered a
 * prompt for; incognito decisions are session-scoped and never reach this list.
 */
@Composable
fun SiteSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val byOrigin = permissions.groupBy { it.origin }.toSortedMap()

    SettingsPage(title = "Site settings", onBack = onBack, viewModel = viewModel) {
        if (permissions.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "No site has been granted anything",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    "Camera, microphone, location and notification requests are denied until you allow them, and every decision you make shows up here so you can revoke it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            return@SettingsPage
        }

        byOrigin.forEach { (origin, entries) ->
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(origin, Modifier.weight(1f))
                var askForget by remember { mutableStateOf(false) }
                if (askForget) {
                    ConfirmDialog(
                        title = "Forget $origin?",
                        message = "All permissions you gave this site will be removed.",
                        confirmLabel = "Forget",
                        onConfirm = { viewModel.clearOrigin(origin) },
                        onDismiss = { askForget = false }
                    )
                }
                IconButton(onClick = { askForget = true }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Forget $origin",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            entries.sortedBy { it.permission.name }.forEach { entry ->
                PermissionRow(
                    entry = entry,
                    onDecision = { decision ->
                        viewModel.setPermission(entry.origin, entry.permission, decision)
                    },
                    onRemove = { viewModel.removePermission(entry) }
                )
            }
            ListDivider()
        }
    }
}

@Composable
private fun PermissionRow(
    entry: SitePermission,
    onDecision: (PermissionDecision) -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label(entry.permission),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Remove rule",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionDecision.entries.forEach { decision ->
                FilterChip(
                    selected = entry.decision == decision,
                    onClick = { onDecision(decision) },
                    label = {
                        Text(
                            when (decision) {
                                PermissionDecision.ALLOW -> "Allow"
                                PermissionDecision.BLOCK -> "Block"
                                PermissionDecision.ASK -> "Ask"
                            }
                        )
                    }
                )
            }
        }
    }
}

private fun label(kind: PermissionKind): String = when (kind) {
    PermissionKind.CAMERA -> "Camera"
    PermissionKind.MICROPHONE -> "Microphone"
    PermissionKind.LOCATION -> "Location"
    PermissionKind.NOTIFICATIONS -> "Notifications"
    PermissionKind.AUTOPLAY -> "Autoplay with sound"
    PermissionKind.XR -> "Virtual reality"
}
