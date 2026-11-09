package com.fable.browser.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.engine.BrowserTab
import com.fable.browser.util.UrlUtils

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
        IncognitoButton(incognito = incognito, onToggle = onToggleIncognito, onPanic = onPanic)
        TabsButton(tabCount = tabCount, onTabs = onTabs)
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
        }
    }
}

@Composable
fun TabletToolbar(
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
    omnibox: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
        omnibox(Modifier.weight(1f).padding(horizontal = 4.dp))
        IconButton(onClick = onFullScreen) {
            Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen")
        }
        IncognitoButton(incognito = incognito, onToggle = onToggleIncognito, onPanic = onPanic)
        TabsButton(tabCount = tabCount, onTabs = onTabs)
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
        }
    }
}

@Composable
fun TabletTabBar(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    incognito: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewSuperTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tabs, key = { it.id }) { item ->
                val title by item.title.collectAsStateWithLifecycle()
                val url by item.url.collectAsStateWithLifecycle()
                val active = item.id == activeTabId
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .widthIn(min = 120.dp, max = 220.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(item.id) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                    ) {
                        if (item.isIncognito) {
                            Icon(
                                if (item.isSuper) Icons.Outlined.Security else Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp).padding(end = 4.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = title.ifBlank { UrlUtils.displayHost(url).ifBlank { "New tab" } },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onClose(item.id) },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close tab",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
        IconButton(onClick = onNewTab, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = "New tab")
        }
        if (incognito) {
            IconButton(onClick = onNewSuperTab, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Security, contentDescription = "New super incognito tab")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IncognitoButton(
    incognito: Boolean,
    onToggle: () -> Unit,
    onPanic: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .combinedClickable(
                onClick = onToggle,
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabsButton(
    tabCount: Int,
    onTabs: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .combinedClickable(onClick = onTabs, onLongClick = onTabs)
            .semantics { contentDescription = "Show tabs" },
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
}

@Composable
fun PageProgress(progress: Int, loading: Boolean, modifier: Modifier = Modifier) {
    val show = loading && progress in 1..99
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .then(if (show) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
    ) {
        if (show) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress / 100f)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
