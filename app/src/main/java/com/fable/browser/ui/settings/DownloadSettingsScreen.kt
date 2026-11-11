package com.fable.browser.ui.settings

import android.os.Build
import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow

@Composable
fun DownloadSettingsScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    SettingsPage(title = "Downloads", onBack = onBack, viewModel = viewModel) {
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
