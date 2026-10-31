package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.ui.Routes
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SettingRow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared chrome for every settings page: back arrow, scrolling body, and a
 * snackbar host fed by [SettingsViewModel.message].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    viewModel: SettingsViewModel? = null,
    content: @Composable () -> Unit
) {
    val snackbarHost = remember { SnackbarHostState() }
    val messageFlow = remember(viewModel) {
        viewModel?.message ?: MutableStateFlow<String?>(null)
    }
    val message by messageFlow.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(text)
        viewModel?.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            content()
        }
    }
}

/** Settings root: one row per category. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsPage(title = "Settings", onBack = onBack, viewModel = viewModel) {
        SettingRow(
            title = "General",
            subtitle = "Search engine, homepage, startup",
            icon = Icons.Outlined.Tune,
            onClick = { onNavigate(Routes.SETTINGS_GENERAL) }
        )
        ListDivider()
        SettingRow(
            title = "Performance",
            subtitle = "${settings.performanceProfile.label}  ·  Tabs: ${settings.tabUnloadMode.label}",
            icon = Icons.Outlined.Speed,
            onClick = { onNavigate(Routes.SETTINGS_PERFORMANCE) }
        )
        ListDivider()
        SettingRow(
            title = "Privacy & security",
            subtitle = buildString {
                append(if (settings.adBlockEnabled) "Blocker on" else "Blocker off")
                append(if (settings.httpsOnly) "  ·  HTTPS-only" else "")
                append("  ·  DNS: ${settings.dohProvider.label}")
            },
            icon = Icons.Outlined.Security,
            onClick = { onNavigate(Routes.SETTINGS_PRIVACY) }
        )
        ListDivider()
        SettingRow(
            title = "Incognito",
            subtitle = "Isolation, fingerprint shield, lock, auto-wipe, proxy",
            icon = Icons.Outlined.VisibilityOff,
            onClick = { onNavigate(Routes.SETTINGS_INCOGNITO) }
        )
        ListDivider()
        SettingRow(
            title = "Appearance",
            subtitle = "Theme, forced dark web content, font size",
            icon = Icons.Outlined.Palette,
            onClick = { onNavigate(Routes.SETTINGS_APPEARANCE) }
        )
        ListDivider()
        SettingRow(
            title = "Site settings",
            subtitle = "Per-site permissions and exceptions",
            icon = Icons.Outlined.Public,
            onClick = { onNavigate(Routes.SETTINGS_SITES) }
        )
        ListDivider()
        SettingRow(
            title = "Downloads",
            subtitle = "Location and incognito download policy",
            icon = Icons.Outlined.Download,
            onClick = { onNavigate(Routes.SETTINGS_DOWNLOADS) }
        )
        ListDivider()
        SettingRow(
            title = "About Fable",
            subtitle = "Version, licenses, no-telemetry statement",
            icon = Icons.Outlined.Info,
            onClick = { onNavigate(Routes.SETTINGS_ABOUT) }
        )
        ListDivider()
    }
}
