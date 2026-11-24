package com.fable.browser.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.backup.BackupException
import com.fable.browser.data.backup.BackupManager
import com.fable.browser.data.backup.BackupOptions
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val restartNeeded: Boolean = false
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val manager: BackupManager
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val _options = MutableStateFlow(BackupOptions())
    val options: StateFlow<BackupOptions> = _options.asStateFlow()

    fun updateOptions(transform: (BackupOptions) -> BackupOptions) {
        _options.value = transform(_options.value)
    }

    fun showError(msg: String) {
        _state.value = BackupUiState(busy = false, message = msg, isError = true)
    }

    fun backup(uri: Uri, password: String) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true, message = "Collecting site cookies, storage, passwords & encrypting backup...")
        viewModelScope.launch {
            val chars = password.toCharArray()
            val result = runCatching { manager.backup(uri, chars, _options.value) }
            chars.fill('\u0000')
            _state.value = result.fold(
                onSuccess = { BackupUiState(message = "Backup saved: ${it.summary()}") },
                onFailure = { BackupUiState(message = errorText(it, "Backup failed"), isError = true) }
            )
        }
    }

    fun restore(uri: Uri, password: String) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true, message = "Decrypting backup & restoring cookies, site storage, passwords...")
        viewModelScope.launch {
            val chars = password.toCharArray()
            val result = runCatching { manager.restore(uri, chars) }
            chars.fill('\u0000')
            _state.value = result.fold(
                onSuccess = { BackupUiState(message = "Restored: ${it.summary()}", restartNeeded = it.needsRestart) },
                onFailure = { BackupUiState(message = errorText(it, "Restore failed"), isError = true) }
            )
        }
    }

    fun dismissRestart() {
        _state.value = _state.value.copy(restartNeeded = false)
    }

    private fun errorText(error: Throwable, fallback: String): String =
        if (error is BackupException) error.message ?: fallback else "$fallback: ${error.message ?: error.javaClass.simpleName}"
}

private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingBackup by remember { mutableStateOf<Uri?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(state.message, state.busy) {
        val msg = state.message
        if (!state.busy && !msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pendingBackup = uri
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }

    SettingsPage(title = "Backup & restore", onBack = onBack) {
        SettingRow(
            title = "End-to-end encrypted",
            subtitle = "AES-256-GCM encrypted backup of passwords, cookies, localStorage, sessionStorage, tabs, bookmarks, history and settings.",
            icon = Icons.Outlined.Lock
        )
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.message?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = msg,
                    color = if (state.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
        ListDivider()

        SectionHeader("Include in backup")
        SwitchRow(title = "Settings", subtitle = "All preferences, hybrid modes, super incognito proxy and identity", checked = options.settings) { v -> viewModel.updateOptions { it.copy(settings = v) } }
        SwitchRow(title = "Passwords", subtitle = "Saved site logins, auto-filled on Blink & Gecko", checked = options.passwords) { v -> viewModel.updateOptions { it.copy(passwords = v) } }
        SwitchRow(title = "Bookmarks and folders", checked = options.bookmarks) { v -> viewModel.updateOptions { it.copy(bookmarks = v) } }
        SwitchRow(title = "History and searches", checked = options.history) { v -> viewModel.updateOptions { it.copy(history = v) } }
        SwitchRow(title = "Open tabs", checked = options.tabs) { v -> viewModel.updateOptions { it.copy(tabs = v) } }
        SwitchRow(title = "Site permissions", checked = options.permissions) { v -> viewModel.updateOptions { it.copy(permissions = v) } }
        SwitchRow(title = "Download list", subtitle = "Records only, files stay in your Downloads folder", checked = options.downloads) { v -> viewModel.updateOptions { it.copy(downloads = v) } }
        SwitchRow(title = "Cookies, localStorage & site data", subtitle = "Captures Blink & Gecko sessions and auto-restores them into sites", checked = options.siteData) { v -> viewModel.updateOptions { it.copy(siteData = v) } }
        ListDivider()

        SectionHeader("Actions")
        SettingRow(
            title = "Create encrypted backup",
            subtitle = "Save a .fable file with all passwords, cookies, localStorage & settings",
            icon = Icons.Outlined.Backup,
            onClick = {
                if (!state.busy) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    runCatching { createLauncher.launch("fable-backup-$stamp.fable") }
                        .onFailure { viewModel.showError("Cannot open file saver: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        )
        SettingRow(
            title = "Restore from backup",
            subtitle = "Restores cookies, localStorage, passwords, tabs, bookmarks & settings immediately",
            icon = Icons.Outlined.Restore,
            onClick = {
                if (!state.busy) {
                    runCatching { openLauncher.launch(arrayOf("*/*")) }
                        .onFailure { viewModel.showError("Cannot open file picker: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        )
        ListDivider()
    }

    pendingBackup?.let { uri ->
        BackupPasswordDialog(
            title = "Protect backup",
            confirm = true,
            onSubmit = { password ->
                pendingBackup = null
                viewModel.backup(uri, password)
            },
            onDismiss = { pendingBackup = null }
        )
    }

    pendingRestore?.let { uri ->
        BackupPasswordDialog(
            title = "Backup password",
            confirm = false,
            onSubmit = { password ->
                pendingRestore = null
                viewModel.restore(uri, password)
            },
            onDismiss = { pendingRestore = null }
        )
    }

    if (state.restartNeeded) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestart,
            title = { Text("Restart Fable") },
            text = { Text("Passwords, live cookies and site storage are already restored. Restart Fable now to also apply raw profile databases.") },
            confirmButton = { TextButton(onClick = { restartApp(context) }) { Text("Restart now") } },
            dismissButton = { TextButton(onClick = viewModel::dismissRestart) { Text("Later") } }
        )
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    confirm: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val longEnough = first.length >= BackupManager.MIN_PASSWORD
    val matches = !confirm || first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (confirm) "Choose a password of at least ${BackupManager.MIN_PASSWORD} characters."
                    else "Enter the backup password (at least ${BackupManager.MIN_PASSWORD} characters)."
                )
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    singleLine = true,
                    label = { Text("Password") },
                    isError = first.isNotEmpty() && !longEnough,
                    supportingText = if (first.isNotEmpty() && !longEnough) {
                        { Text("Minimum ${BackupManager.MIN_PASSWORD} characters") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirm) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        singleLine = true,
                        label = { Text("Repeat password") },
                        isError = second.isNotEmpty() && !matches,
                        supportingText = if (second.isNotEmpty() && !matches) {
                            { Text("Passwords do not match") }
                        } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = longEnough && matches, onClick = { onSubmit(first) }) { Text(if (confirm) "Encrypt" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
