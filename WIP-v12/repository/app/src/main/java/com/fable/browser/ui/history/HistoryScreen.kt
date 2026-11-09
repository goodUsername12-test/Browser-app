package com.fable.browser.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.HistoryEntry
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var clearOpen by remember { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { clearOpen = true }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear history")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search history") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (entries.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(
                            entry = entry,
                            timestamp = formatter.format(Date(entry.visitedAt)),
                            onOpen = { onOpenUrl(entry.url) },
                            onDelete = { viewModel.delete(entry) }
                        )
                        ListDivider()
                    }
                }
            }
        }
    }

    if (clearOpen) {
        ClearHistoryDialog(
            onDismiss = { clearOpen = false },
            onConfirm = { range ->
                viewModel.clear(range)
                clearOpen = false
            }
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    timestamp: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$timestamp  ·  ${entry.url}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete entry", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ClearHistoryDialog(onDismiss: () -> Unit, onConfirm: (ClearRange) -> Unit) {
    var selected by remember { mutableStateOf(ClearRange.LAST_HOUR) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear history") },
        text = {
            RadioGroup(
                options = ClearRange.entries.toList(),
                selected = selected,
                label = { it.label },
                onSelect = { selected = it }
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text("No history", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Incognito browsing is never recorded here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
