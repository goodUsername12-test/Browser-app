package com.fable.browser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fable.browser.engine.FablePrompt

@Composable
fun PromptHost(prompt: FablePrompt) {
    when (prompt) {
        is FablePrompt.Alert -> AlertDialog(
            onDismissRequest = prompt.onDismiss,
            title = { Text(prompt.title) },
            text = { ScrollableText(prompt.message) },
            confirmButton = { TextButton(onClick = prompt.onDismiss) { Text("OK") } }
        )

        is FablePrompt.Confirm -> AlertDialog(
            onDismissRequest = prompt.onDismiss,
            title = { Text(prompt.title) },
            text = { ScrollableText(prompt.message) },
            confirmButton = {
                TextButton(onClick = prompt.onConfirm) { Text(prompt.positiveLabel) }
            },
            dismissButton = {
                TextButton(onClick = prompt.onDismiss) { Text(prompt.negativeLabel) }
            }
        )

        is FablePrompt.TextInput -> {
            var value by remember(prompt) { mutableStateOf(prompt.defaultValue) }
            AlertDialog(
                onDismissRequest = prompt.onDismiss,
                title = { Text(prompt.title) },
                text = {
                    Column {
                        if (prompt.message.isNotBlank()) ScrollableText(prompt.message)
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { prompt.onConfirm(value) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = prompt.onDismiss) { Text("Cancel") } }
            )
        }

        is FablePrompt.Auth -> {
            var user by remember { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = prompt.onDismiss,
                title = { Text(prompt.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (prompt.message.isNotBlank()) ScrollableText(prompt.message)
                        Text(
                            "Fable never saves credentials.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (prompt.usernameRequired) {
                            OutlinedTextField(
                                value = user,
                                onValueChange = { user = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { prompt.onConfirm(user, pass) }) { Text("Sign in") }
                },
                dismissButton = { TextButton(onClick = prompt.onDismiss) { Text("Cancel") } }
            )
        }

        is FablePrompt.Choice -> {
            val selected = remember {
                mutableStateOf(prompt.options.filter { it.selected }.map { it.id }.toSet())
            }
            AlertDialog(
                onDismissRequest = prompt.onDismiss,
                title = { Text(prompt.title) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        prompt.options.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected.value = when {
                                            !prompt.multiple -> setOf(option.id)
                                            option.id in selected.value ->
                                                selected.value - option.id
                                            else -> selected.value + option.id
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = option.id in selected.value,
                                    onCheckedChange = null
                                )
                                Text(option.label, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { prompt.onConfirm(selected.value.toList()) }) {
                        Text("OK")
                    }
                },
                dismissButton = { TextButton(onClick = prompt.onDismiss) { Text("Cancel") } }
            )
        }

        is FablePrompt.Permission -> {
            var remember2 by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = prompt.onDismiss,
                title = { Text(prompt.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${prompt.origin} ${prompt.message}")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { remember2 = !remember2 }
                        ) {
                            Checkbox(checked = remember2, onCheckedChange = { remember2 = it })
                            Text(
                                "Remember this decision for this site",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { prompt.onAllow(remember2) }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = { prompt.onBlock(remember2) }) { Text("Block") }
                }
            )
        }

        is FablePrompt.SaveLogin -> {
            var user by remember(prompt) { mutableStateOf(prompt.username) }
            var pass by remember(prompt) { mutableStateOf(prompt.password) }
            var show by remember(prompt) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = prompt.onDismiss,
                title = { Text(prompt.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(prompt.host, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!prompt.update) {
                            TextButton(onClick = prompt.onNever) { Text("Never for this site") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { prompt.onSave(user, pass) },
                        enabled = pass.isNotEmpty()
                    ) { Text(if (prompt.update) "Update" else "Save") }
                },
                dismissButton = { TextButton(onClick = prompt.onDismiss) { Text("Not now") } }
            )
        }
        is FablePrompt.SelectLogin -> AlertDialog(
            onDismissRequest = prompt.onDismiss,
            title = { Text(prompt.title) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(prompt.host, style = MaterialTheme.typography.bodySmall)
                    prompt.accounts.forEachIndexed { index, account ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { prompt.onPick(index) }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(account.label, style = MaterialTheme.typography.bodyLarge)
                            Text(account.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = prompt.onDismiss) { Text("Cancel") } }
        )
        is FablePrompt.IncognitoDownload -> AlertDialog(
            onDismissRequest = prompt.onDismiss,
            title = { Text(prompt.title) },
            text = {
                Text(
                    "Saving \"${prompt.fileName}\" writes it to your device storage, where it " +
                        "stays after this incognito session is wiped. Nothing else from this " +
                        "session touches the disk."
                )
            },
            confirmButton = { TextButton(onClick = prompt.onConfirm) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = prompt.onDismiss) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ScrollableText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
    )
}
