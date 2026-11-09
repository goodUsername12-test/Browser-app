package com.fable.browser.ui.downloads

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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.DownloadEntry
import com.fable.browser.data.model.DownloadStatus
import com.fable.browser.ui.components.ListDivider
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val active by viewModel.activeCount.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (active > 0) "Downloads ($active active)" else "Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearFinished) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear finished")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (downloads.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(downloads, key = { it.id }) { entry ->
                        DownloadRow(
                            entry = entry,
                            onOpen = { viewModel.open(entry) },
                            onRemove = { viewModel.remove(entry) }
                        )
                        ListDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(entry: DownloadEntry, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (entry.status == DownloadStatus.IN_PROGRESS) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Outlined.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                statusLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.status == DownloadStatus.IN_PROGRESS && entry.totalBytes > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        (entry.downloadedBytes.toFloat() / entry.totalBytes).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove from list",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun statusLine(entry: DownloadEntry): String {
    val incognito = if (entry.fromIncognito) "Private  ·  " else ""
    return incognito + when (entry.status) {
        DownloadStatus.IN_PROGRESS ->
            if (entry.totalBytes > 0)
                "${formatBytes(entry.downloadedBytes)} of ${formatBytes(entry.totalBytes)}"
            else formatBytes(entry.downloadedBytes)
        DownloadStatus.COMPLETED -> "${formatBytes(entry.downloadedBytes)}  ·  Downloads/Fable"
        DownloadStatus.FAILED -> "Failed"
        DownloadStatus.CANCELLED -> "Cancelled"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text("No downloads", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Files are saved to Downloads/Fable. Incognito downloads ask for confirmation first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
