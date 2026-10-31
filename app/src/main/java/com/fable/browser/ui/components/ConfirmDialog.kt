package com.fable.browser.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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

/**
 * Safety question before anything that deletes data. With [requireTick] the
 * delete button only works after ticking "I understand", so one accidental
 * tap can never wipe everything.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    requireTick: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var ticked by remember { mutableStateOf(!requireTick) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                if (requireTick) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Checkbox(checked = ticked, onCheckedChange = { ticked = it })
                        Text("I understand this cannot be undone")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ticked,
                onClick = {
                    onDismiss()
                    onConfirm()
                }
            ) {
                Text(confirmLabel, color = if (ticked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
