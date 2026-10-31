package com.fable.browser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fable.browser.engine.ContentMenuAction
import com.fable.browser.engine.ContextMenuTarget
import com.fable.browser.ui.components.ListDivider

/**
 * Long-press menu for links and images. "Open in new incognito tab" is offered
 * from normal tabs too; the reverse (escaping incognito into a normal tab) is
 * deliberately not offered, so a private navigation cannot leak by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    target: ContextMenuTarget,
    onDismiss: () -> Unit,
    onAction: (ContentMenuAction) -> Unit,
    onCopyLink: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val link = target.linkUri
    val image = target.imageUri

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                text = link ?: image.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            ListDivider()

            if (link != null) {
                Item(Icons.Outlined.OpenInNew, "Open in new tab") {
                    onAction(ContentMenuAction.OPEN_IN_NEW_TAB)
                }
                Item(Icons.Outlined.VisibilityOff, "Open in new incognito tab") {
                    onAction(ContentMenuAction.OPEN_IN_NEW_INCOGNITO_TAB)
                }
                Item(Icons.Outlined.ContentCopy, "Copy link") { onCopyLink(link) }
            }
            if (image != null) {
                Item(Icons.Outlined.Download, "Download image") {
                    onAction(ContentMenuAction.DOWNLOAD_IMAGE)
                }
                if (link == null) {
                    Item(Icons.Outlined.ContentCopy, "Copy image address") { onCopyLink(image) }
                }
            }
            if (link != null || image != null) {
                Item(Icons.Outlined.Share, "Share") { onAction(ContentMenuAction.SHARE_LINK) }
            }
        }
    }
}

@Composable
private fun Item(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
