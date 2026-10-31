package com.fable.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Thumb-reachable bottom controls. The incognito button doubles as the panic
 * button: a long-press wipes every private tab immediately.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    tabCount: Int,
    incognito: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onFullScreen: () -> Unit,
    onTabs: () -> Unit,
    onToggleIncognito: () -> Unit,
    onPanic: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
        }
        if (loading) {
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop loading")
            }
        } else {
            IconButton(onClick = onReload) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
        }
        IconButton(onClick = onFullScreen) {
            Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen")
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(
                    onClick = onToggleIncognito,
                    onLongClick = onPanic
                )
                .semantics {
                    contentDescription = if (incognito) {
                        "Leave incognito. Long press to wipe all incognito tabs"
                    } else {
                        "Enter incognito. Long press to wipe all incognito tabs"
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = if (incognito) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(onClick = onTabs, onLongClick = onTabs),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(7.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (tabCount > 99) "99+" else tabCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
        }
    }
}

/** Thin determinate loading line drawn above the toolbar. */
@Composable
fun PageProgress(progress: Int, loading: Boolean, modifier: Modifier = Modifier) {
    if (!loading || progress <= 0 || progress >= 100) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress / 100f)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
