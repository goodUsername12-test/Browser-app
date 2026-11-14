package com.fable.browser.ui.settings

import android.os.Build
import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SliderRow
import com.fable.browser.ui.components.SwitchRow

@Composable
fun DownloadSettingsScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    SettingsPage(title = "Downloads & Page Archive", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Page Code & Memory Archive (.zip)")
        SwitchRow(
            title = "Strict read-only mode (Zero page edits)",
            subtitle = "Never patch or wrap website functions. Uses Gecko network stream capture and read-only DOM/storage inspection.",
            checked = s.archiveReadOnly,
            icon = Icons.Outlined.Shield,
            onCheckedChange = viewModel::setArchiveReadOnly
        )
        SwitchRow(
            title = "Passive network stream capture",
            subtitle = "Capture loaded scripts, WASM, JSON, XHR/fetch responses and game assets directly from Gecko's network layer as the page loads.",
            checked = s.archiveNetworkStream,
            icon = Icons.Outlined.Code,
            onCheckedChange = viewModel::setArchiveNetworkStream
        )
        SwitchRow(
            title = "Include memory snapshot in Page Code (.zip)",
            subtitle = "Bundle canvases, storage, IndexedDB, Cache Storage, CSSOM and HAR network log when downloading page code.",
            checked = s.archiveIncludeMemory,
            icon = Icons.Outlined.Memory,
            onCheckedChange = viewModel::setArchiveIncludeMemory
        )
        SwitchRow(
            title = "Capture canvases & WebGL frames",
            subtitle = "Save 2D and 3D WebGL canvas framebuffers as PNG files inside memory/",
            checked = s.archiveIncludeCanvas,
            onCheckedChange = viewModel::setArchiveIncludeCanvas
        )
        SwitchRow(
            title = "Capture IndexedDB, Cache Storage & Web Storage",
            subtitle = "Export localStorage, sessionStorage, IndexedDB object stores, Service Worker Cache Storage and CSSOM rules.",
            checked = s.archiveIncludeStorage,
            onCheckedChange = viewModel::setArchiveIncludeStorage
        )
        SwitchRow(
            title = "Deep-scan scripts & CSS for game assets",
            subtitle = "Scan loaded JS, JSON and CSS for referenced .wasm, .glb, .gltf, .babylon, .bin, textures, audio and shaders.",
            checked = s.archiveGuessAssets,
            onCheckedChange = viewModel::setArchiveGuessAssets
        )
        SwitchRow(
            title = "Deep page hooks (Console, WebSockets, revoked Blobs)",
            subtitle = if (s.archiveReadOnly)
                "Disabled while Strict read-only mode is on (turn off Strict read-only mode to enable page hooks)"
            else
                "Hook console.*, WebSocket frames and URL.createObjectURL at document_start",
            checked = !s.archiveReadOnly && s.archiveDeepHooks,
            enabled = !s.archiveReadOnly,
            onCheckedChange = viewModel::setArchiveDeepHooks
        )
        ListDivider()

        SectionHeader("Archive Limits")
        SliderRow(
            title = "Max files per ZIP",
            value = s.archiveMaxFiles,
            range = 500f..10000f,
            steps = 18,
            suffix = " files",
            onValueChange = viewModel::setArchiveMaxFiles
        )
        SliderRow(
            title = "Max total ZIP size",
            value = s.archiveMaxTotalMb,
            range = 100f..2000f,
            steps = 18,
            suffix = " MB",
            onValueChange = viewModel::setArchiveMaxTotalMb
        )
        SliderRow(
            title = "Max single file size",
            value = s.archiveMaxFileMb,
            range = 20f..500f,
            steps = 23,
            suffix = " MB",
            onValueChange = viewModel::setArchiveMaxFileMb
        )
        SliderRow(
            title = "Live network buffer per tab",
            value = s.archiveStreamBufferMb,
            range = 32f..512f,
            steps = 14,
            suffix = " MB",
            onValueChange = viewModel::setArchiveStreamBufferMb
        )
        ListDivider()

        SectionHeader("Multi-Part ZIP Splitting (Upload Limits)")
        RadioGroup(
            options = listOf(0, 45, 95),
            selected = when {
                s.archiveSplitPartMb in 1..60 -> 45
                s.archiveSplitPartMb > 60 -> 95
                else -> 0
            },
            label = { mb ->
                when (mb) {
                    45 -> "Split into <= 45 MB parts (fits 50 MB upload limits)"
                    95 -> "Split into <= 95 MB parts (fits 100 MB upload limits)"
                    else -> "Single complete .zip archive (default)"
                }
            },
            description = { mb ->
                when (mb) {
                    45 -> "Automatically writes .part1ofN.zip, .part2ofN.zip with zero duplicate files + capture-manifest.json"
                    95 -> "Splits very large game/texture captures into <= 95 MB parts"
                    else -> "Saves all page code, IndexedDB, HAR 1.2, canvases, and 12-stream native assets in one .zip"
                }
            },
            onSelect = viewModel::setArchiveSplitPartMb
        )
        ListDivider()

        SectionHeader("Location")
        SettingRow(
            title = Environment.DIRECTORY_DOWNLOADS,
            subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "Written through MediaStore, so Fable never asks for storage permission"
            else
                "Legacy storage write on this Android version",
            icon = Icons.Outlined.Folder
        )
        ListDivider()

        SectionHeader("Incognito")
        SettingRow(
            title = "Confirm every private download",
            subtitle = "A file saved to disk outlives the private session. Fable always asks before writing one, and the record is marked Private in the download list.",
            icon = Icons.Outlined.Shield
        )
        ListDivider()

        SectionHeader("Manage")
        SettingRow(
            title = "Open download manager",
            subtitle = "Progress, open file, remove records",
            icon = Icons.Outlined.Download,
            onClick = onOpenDownloads
        )
        ListDivider()
    }
}
