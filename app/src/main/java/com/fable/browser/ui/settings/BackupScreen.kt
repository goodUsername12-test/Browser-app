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
import com.fable.browser.data.backup.PreparedBackup
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow
import com.fable.browser.util.ActivityBridge
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
    val restartNeeded: Boolean = false,
    val readyToSaveBackup: PreparedBackup? = null,
    val savePickerLaunched: Boolean = false,
    val pendingRestoreUri: Uri? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val manager: BackupManager,
    private val activityBridge: ActivityBridge
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val _options = MutableStateFlow(BackupOptions())
    val options: StateFlow<BackupOptions> = _options.asStateFlow()

    fun updateOptions(transform: (BackupOptions) -> BackupOptions) {
        _options.value = transform(_options.value)
    }

    fun showError(msg: String) {
        _state.value = _state.value.copy(busy = false, message = msg, isError = true)
    }

    fun markPickerOpen(open: Boolean) {
        activityBridge.markExternalPickerOpen(open)
    }

    fun markSavePickerLaunched() {
        _state.value = _state.value.copy(savePickerLaunched = true)
    }

    fun prepareBackup(password: String) {
        if (_state.value.busy) return
        manager.discardPreparedBackup(_state.value.readyToSaveBackup)
        _state.value = BackupUiState(
            busy = true,
            message = "Collecting cookies, site storage, passwords, CE tables & encrypting backup..."
        )
        viewModelScope.launch {
            val chars = password.toCharArray()
            val res = runCatching { manager.prepareEncryptedBackupToCache(chars, _options.value) }
            chars.fill('\u0000')
            _state.value = res.fold(
                onSuccess = { prepared ->
                    BackupUiState(
                        busy = false,
                        message = "Encrypted ${BackupManager.formatByteSize(prepared.bytesWritten)} — choose where to save the .fable file",
                        readyToSaveBackup = prepared
                    )
                },
                onFailure = {
                    BackupUiState(message = errorText(it, "Backup failed"), isError = true)
                }
            )
        }
    }

    fun savePreparedToUri(uri: Uri?) {
        activityBridge.markExternalPickerOpen(false)
        val prepared = _state.value.readyToSaveBackup
        if (prepared == null) return
        if (uri == null) {
            manager.discardPreparedBackup(prepared)
            _state.value = BackupUiState(message = "Backup save cancelled")
            return
        }
        _state.value = BackupUiState(busy = true, message = "Writing & verifying .fable backup file...")
        viewModelScope.launch {
            val res = runCatching { manager.commitPreparedBackupToUri(prepared, uri) }
            _state.value = res.fold(
                onSuccess = { BackupUiState(message = "Backup saved & verified: ${it.summary()}") },
                onFailure = { BackupUiState(message = errorText(it, "Backup save failed"), isError = true) }
            )
        }
    }

    fun onRestoreFilePicked(uri: Uri?) {
        activityBridge.markExternalPickerOpen(false)
        if (uri == null) return
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true, message = "Checking .fable backup file header...")
        viewModelScope.launch {
            val probe = runCatching { manager.inspectBackupUri(uri) }.getOrNull()
            if (probe == null || !probe.valid) {
                _state.value = BackupUiState(
                    busy = false,
                    message = probe?.error ?: "This file is not a valid Fable backup",
                    isError = true
                )
            } else {
                _state.value = BackupUiState(
                    busy = false,
                    message = "Valid Fable backup (${BackupManager.formatByteSize(probe.sizeBytes)}) — enter password to decrypt",
                    pendingRestoreUri = uri
                )
            }
        }
    }

    fun cancelPendingRestore() {
        _state.value = _state.value.copy(pendingRestoreUri = null)
    }

    fun restore(uri: Uri, password: String) {
        if (_state.value.busy) return
        _state.value = BackupUiState(
            busy = true,
            message = "Decrypting backup & restoring cookies, site storage, passwords..."
        )
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

    override fun onCleared() {
        super.onCleared()
        manager.discardPreparedBackup(_state.value.readyToSaveBackup)
    }

    private fun errorText(error: Throwable, fallback: String): String =
        if (error is BackupException) error.message ?: fallback
        else "$fallback: ${error.message ?: error.javaClass.simpleName}"
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
    var askBackupPassword by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        viewModel.savePreparedToUri(uri)
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.onRestoreFilePicked(uri)
    }

    LaunchedEffect(state.readyToSaveBackup, state.savePickerLaunched) {
        if (state.readyToSaveBackup != null && !state.savePickerLaunched) {
            viewModel.markSavePickerLaunched()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            viewModel.markPickerOpen(true)
            runCatching { createLauncher.launch("fable-backup-$stamp.fable") }
                .onFailure {
                    viewModel.markPickerOpen(false)
                    viewModel.savePreparedToUri(null)
                    viewModel.showError("Cannot open file saver: ${it.message ?: it.javaClass.simpleName}")
                }
        }
    }

    LaunchedEffect(state.message, state.busy) {
        val msg = state.message
        if (!state.busy && !msg.isNullOrBlank() && state.readyToSaveBackup == null && state.pendingRestoreUri == null) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    SettingsPage(title = "Backup & restore", onBack = onBack) {
        SettingRow(
            title = "End-to-end encrypted",
            subtitle = "AES-256-GCM streaming backup with SHA-256 verification for passwords, cookies, localStorage, sessionStorage, CE tables, tabs, bookmarks, history & settings.",
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
        SwitchRow(title = "Settings", subtitle = "All preferences, hybrid modes, CE tables & recipes, super incognito proxy", checked = options.settings) { v -> viewModel.updateOptions { it.copy(settings = v) } }
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
            subtitle = "Encrypts and verifies your .fable archive first, then saves it cleanly (never creates 0-byte files)",
            icon = Icons.Outlined.Backup,
            onClick = {
                if (!state.busy) askBackupPassword = true
            }
        )
        SettingRow(
            title = "Restore from backup",
            subtitle = "Verifies the .fable file header first, then restores cookies, localStorage, passwords, CE tables & settings",
            icon = Icons.Outlined.Restore,
            onClick = {
                if (!state.busy) {
                    viewModel.markPickerOpen(true)
                    runCatching { openLauncher.launch(arrayOf("*/*")) }
                        .onFailure {
                            viewModel.markPickerOpen(false)
                            viewModel.showError("Cannot open file picker: ${it.message ?: it.javaClass.simpleName}")
                        }
                }
            }
        )
        ListDivider()
    }

    if (askBackupPassword) {
        BackupPasswordDialog(
            title = "Protect backup",
            confirm = true,
            onSubmit = { password ->
                askBackupPassword = false
                viewModel.prepareBackup(password)
            },
            onDismiss = { askBackupPassword = false }
        )
    }

    state.pendingRestoreUri?.let { uri ->
        BackupPasswordDialog(
            title = "Backup password",
            confirm = false,
            onSubmit = { password ->
                viewModel.restore(uri, password)
            },
            onDismiss = { viewModel.cancelPendingRestore() }
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
            TextButton(enabled = longEnough && matches, onClick = { onSubmit(first) }) { Text(if (confirm) "Encrypt & Save" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
