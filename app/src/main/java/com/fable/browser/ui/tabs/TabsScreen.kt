package com.fable.browser.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import com.fable.browser.ui.components.ConfirmDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.engine.BrowserTab
import com.fable.browser.ui.browser.BrowserViewModel
import com.fable.browser.util.UrlUtils

/**
 * Grid tab switcher with two visually distinct trays.
 *
 * Incognito previews come from [BrowserTab.thumbnail], which only ever exists
 * in RAM — there is no thumbnail cache on disk for either mode, and the
 * incognito tray is drawn in the violet private palette so the two can never
 * be confused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(
    viewModel: BrowserViewModel,
    onClose: () -> Unit
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val incognitoMode by viewModel.isIncognito.collectAsStateWithLifecycle()
    var showIncognito by remember { mutableStateOf(incognitoMode) }

    LaunchedEffect(incognitoMode) { showIncognito = incognitoMode }

    val visible = remember(tabs, showIncognito) { tabs.filter { it.isIncognito == showIncognito } }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (showIncognito) "Incognito tabs" else "Tabs") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close tab switcher")
                        }
                    },
                    actions = {
                        var askCloseAll by remember { mutableStateOf(false) }
                        if (askCloseAll) {
                            ConfirmDialog(
                                title = if (showIncognito) "Close all incognito tabs?" else "Close all tabs?",
                                message = if (showIncognito) {
                                    "All incognito tabs and their data will be erased right away."
                                } else {
                                    "Every open tab will be closed."
                                },
                                confirmLabel = "Close all",
                                // "Close all incognito" takes the panic path: tabs
                                // closed, containers dropped, identity reset.
                                onConfirm = {
                                    if (showIncognito) viewModel.panicWipe()
                                    else viewModel.closeAll(false)
                                },
                                onDismiss = { askCloseAll = false }
                            )
                        }
                        IconButton(
                            onClick = { askCloseAll = true }
                        ) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = if (showIncognito) "Close all incognito tabs"
                                else "Close all tabs"
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.openTab(incognito = showIncognito)
                                onClose()
                            }
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "New tab")
                        }
                    }
                )
                TabRow(selectedTabIndex = if (showIncognito) 1 else 0) {
                    Tab(
                        selected = !showIncognito,
                        onClick = { showIncognito = false },
                        text = { Text("Normal (${tabs.count { !it.isIncognito }})") }
                    )
                    Tab(
                        selected = showIncognito,
                        onClick = { showIncognito = true },
                        icon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                        text = { Text("Incognito (${tabs.count { it.isIncognito }})") }
                    )
                }
            }
        }
    ) { padding ->
        if (visible.isEmpty()) {
            EmptyTray(
                incognito = showIncognito,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visible, key = { it.id }) { tab ->
                    TabCard(
                        tab = tab,
                        isActive = tab.id == activeTabId,
                        onSelect = {
                            viewModel.selectTab(tab.id)
                            onClose()
                        },
                        onClose = { viewModel.closeTab(tab.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabCard(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val title by tab.title.collectAsStateWithLifecycle()
    val url by tab.url.collectAsStateWithLifecycle()
    val thumbnail by tab.thumbnail.collectAsStateWithLifecycle()

    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) onClose()
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(
                    width = if (isActive) 2.dp else 1.dp,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable(onClick = onSelect)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = thumbnail
                if (bitmap != null && !bitmap.isRecycled) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        if (tab.isIncognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Language,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title.ifBlank { UrlUtils.displayHost(url) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = UrlUtils.displayHost(url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close tab",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTray(incognito: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (incognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Language,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (incognito) "No incognito tabs" else "No tabs",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (incognito) "Private tabs live only in memory and are never restored."
            else "Open a tab to start browsing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
