package com.fable.browser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SliderRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    isBookmarked: Boolean,
    desktopMode: Boolean,
    keepLoaded: Boolean,
    onToggleKeepLoaded: () -> Unit,
    readerMode: Boolean,
    incognito: Boolean,
    textZoom: Int,
    onDismiss: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onFind: () -> Unit,
    onToggleDesktop: () -> Unit,
    onToggleReader: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onSaveAsPdf: () -> Unit,
    onTextZoom: (Int) -> Unit,
    onFingerprintTest: () -> Unit,
    onNewSuperTab: () -> Unit = {}
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            MenuItem(
                icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                label = if (isBookmarked) "Remove bookmark" else "Add bookmark",
                onClick = { onToggleBookmark(); onDismiss() }
            )
            MenuItem(Icons.Outlined.Bookmarks, "Bookmarks") { onBookmarks(); onDismiss() }
            if (!incognito) {
                MenuItem(Icons.Outlined.History, "History") { onHistory(); onDismiss() }
            }
            MenuItem(Icons.Outlined.Download, "Downloads") { onDownloads(); onDismiss() }
            ListDivider()
            MenuItem(Icons.Outlined.Search, "Find in page") { onFind(); onDismiss() }
            MenuToggle(
                icon = Icons.Outlined.PushPin,
                label = "Keep this tab loaded",
                checked = keepLoaded,
                onCheckedChange = { onToggleKeepLoaded() }
            )
            MenuToggle(
                icon = Icons.Outlined.DesktopWindows,
                label = "Desktop site",
                checked = desktopMode,
                onCheckedChange = { onToggleDesktop() }
            )
            MenuToggle(
                icon = Icons.Outlined.Article,
                label = "Reader mode",
                checked = readerMode,
                onCheckedChange = { onToggleReader() }
            )
            SliderRow(
                title = "Page zoom",
                value = textZoom,
                range = 50f..200f,
                steps = 14,
                suffix = "%",
                onValueChange = onTextZoom
            )
            ListDivider()
            MenuItem(Icons.Outlined.Share, "Share page") { onShare(); onDismiss() }
            MenuItem(Icons.Outlined.ContentCopy, "Copy link") { onCopyLink(); onDismiss() }
            MenuItem(Icons.Outlined.AddToHomeScreen, "Add to home screen") {
                onAddToHomeScreen(); onDismiss()
            }
            MenuItem(Icons.Outlined.Print, "Print / Save as PDF") { onSaveAsPdf(); onDismiss() }
            ListDivider()
            MenuItem(Icons.Outlined.Fingerprint, "Test my fingerprint") {
                onFingerprintTest(); onDismiss()
            }
            MenuItem(Icons.Outlined.Security, "New super incognito tab") { onNewSuperTab(); onDismiss() }
            MenuItem(Icons.Outlined.Settings, "Settings") { onSettings(); onDismiss() }
        }
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    overrideIcon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            overrideIcon ?: icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MenuToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
