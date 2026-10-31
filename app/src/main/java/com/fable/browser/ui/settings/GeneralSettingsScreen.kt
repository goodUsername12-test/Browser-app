package com.fable.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.data.model.SearchEngine
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.RadioGroup
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SwitchRow

/**
 * General: default search engine, homepage / startup page, tab restore.
 *
 * Google is the default engine because it was requested explicitly; the
 * privacy-preserving alternatives are one tap away in the same list.
 */
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var homepage by remember(settings.homepage) { mutableStateOf(settings.homepage) }

    SettingsPage(title = "General", onBack = onBack, viewModel = viewModel) {
        SectionHeader("Search engine")
        RadioGroup(
            options = SearchEngine.entries.toList(),
            selected = settings.searchEngine,
            label = { it.label },
            description = { engine ->
                when (engine) {
                    SearchEngine.GOOGLE -> "Best results, most tracking"
                    SearchEngine.DUCKDUCKGO -> "No search history profile"
                    SearchEngine.BING -> "Microsoft index"
                    SearchEngine.BRAVE -> "Independent index"
                    SearchEngine.STARTPAGE -> "Google results, proxied"
                    SearchEngine.ECOSIA -> "Bing results, trees planted"
                }
            },
            onSelect = viewModel::setSearchEngine
        )
        ListDivider()

        SectionHeader("Startup")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = homepage,
                onValueChange = { homepage = it },
                label = { Text("Homepage") },
                placeholder = { Text("about:blank") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Opened by new tabs and at startup. Leave as about:blank for the fastest, quietest start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            TextButton(
                onClick = { viewModel.setHomepage(homepage) },
                enabled = homepage.trim() != settings.homepage,
                modifier = Modifier.align(Alignment.End)
            ) { Text("Save homepage") }
        }
        ListDivider()
        SwitchRow(
            title = "Restore tabs on launch",
            subtitle = "Normal tabs only. Incognito tabs are never written to disk, so they can never come back.",
            checked = settings.restoreTabs,
            onCheckedChange = viewModel::setRestoreTabs
        )
        ListDivider()
    }
}
