package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow

/** Version, the explicit no-telemetry statement, and third-party licenses. */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    SettingsPage(title = "About Fable", onBack = onBack, viewModel = viewModel) {
        SettingRow(
            title = "Fable Browser $version",
            subtitle = "Built on Mozilla GeckoView. Personal build, not affiliated with Mozilla.",
            icon = Icons.Outlined.Info
        )
        ListDivider()

        SectionHeader("No telemetry")
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Fable makes no network request you did not start.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "There is no analytics SDK, no crash reporter, no A/B framework, no push " +
                        "registration and no phone-home check. Gecko's own telemetry and crash " +
                        "reporting are opt-in and Fable never opts in. The only requests Fable " +
                        "initiates on its own are the two filter-list downloads you trigger by " +
                        "tapping \"Update filter lists\", and the DNS-over-HTTPS resolver you chose.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        SettingRow(
            title = "Permissions Fable declares",
            subtitle = "INTERNET and ACCESS_NETWORK_STATE (a browser cannot work without them), " +
                "POST_NOTIFICATIONS for download progress, USE_BIOMETRIC for the incognito lock, " +
                "FOREGROUND_SERVICE_DATA_SYNC so a download survives you leaving the app, and " +
                "WRITE_EXTERNAL_STORAGE capped at API 28 for legacy downloads. Camera, microphone " +
                "and location are requested at runtime only when a page asks and you agree.",
            icon = Icons.Outlined.Shield
        )
        ListDivider()

        SectionHeader("Open source licenses")
        LICENSES.forEach { (name, license) ->
            SettingRow(title = name, subtitle = license)
        }
        ListDivider()
    }
}

private val LICENSES = listOf(
    "Mozilla GeckoView" to "Mozilla Public License 2.0 — the rendering engine",
    "AndroidX / Jetpack Compose, Room, DataStore, Biometric" to "Apache License 2.0",
    "Kotlin and kotlinx.coroutines" to "Apache License 2.0",
    "Dagger Hilt" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "EasyList" to "GPLv3 / CC BY-SA 3.0 — bundled filter rules",
    "EasyPrivacy" to "GPLv3 / CC BY-SA 3.0 — bundled tracker rules"
)
