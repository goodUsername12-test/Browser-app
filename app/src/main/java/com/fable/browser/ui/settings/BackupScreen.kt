package com.fable.browser.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

    fun backup(uri: Uri, password: String) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true, message = "Encrypting backup")
        viewModelScope.launch {
            val chars = password.toCharArray()
            val result = runCatching { manager.backup(uri, chars, _options.value) }
            chars.fill('\u0000')
            _state.value = result.fold(
                onSuccess = { BackupUiState(message = "Backup saved: ${it.summary()}") },
                onFailure = { BackupUiState(message = errorText(it, "Backup failed")) }
            )
        }
    }

    fun restore(uri: Uri, password: String) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true, message = "Decrypting backup")
        viewModelScope.launch {
            val chars = password.toCharArray()
            val result = runCatching { manager.restore(uri, chars) }
            chars.fill('\u0000')
            _state.value = result.fold(
                onSuccess = { BackupUiState(message = "Restored ${it.summary()}", restartNeeded = it.needsRestart) },
                onFailure = { BackupUiState(message = errorText(it, "Restore failed")) }
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

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pendingBackup = uri
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }

    SettingsPage(title = "Backup & restore", onBack = onBack) {
        SettingRow(
            title = "End-to-end encrypted",
            subtitle = "Backups are sealed with AES-256-GCM using a key derived from your password (PBKDF2, 150,000 rounds). Without the password the file cannot be read, not even by Fable.",
            icon = Icons.Outlined.Lock
        )
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.message?.let { SettingRow(title = it) }
        ListDivider()

        SectionHeader("Include in backup")
        SwitchRow(title = "Settings", subtitle = "All preferences, super incognito proxy and identity", checked = options.settings) { v -> viewModel.updateOptions { it.copy(settings = v) } }
        SwitchRow(title = "Passwords", checked = options.passwords) { v -> viewModel.updateOptions { it.copy(passwords = v) } }
        SwitchRow(title = "Bookmarks and folders", checked = options.bookmarks) { v -> viewModel.updateOptions { it.copy(bookmarks = v) } }
        SwitchRow(title = "History and searches", checked = options.history) { v -> viewModel.updateOptions { it.copy(history = v) } }
        SwitchRow(title = "Open tabs", checked = options.tabs) { v -> viewModel.updateOptions { it.copy(tabs = v) } }
        SwitchRow(title = "Site permissions", checked = options.permissions) { v -> viewModel.updateOptions { it.copy(permissions = v) } }
        SwitchRow(title = "Download list", subtitle = "Records only, files stay in your Downloads folder", checked = options.downloads) { v -> viewModel.updateOptions { it.copy(downloads = v) } }
        SwitchRow(title = "Cookies and site data", subtitle = "Keeps you signed in to sites after restoring", checked = options.siteData) { v -> viewModel.updateOptions { it.copy(siteData = v) } }
        ListDivider()

        SectionHeader("Actions")
        SettingRow(
            title = "Create encrypted backup",
            subtitle = "Save a .fable file anywhere you like",
            icon = Icons.Outlined.Backup,
            onClick = {
                if (!state.busy) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    runCatching { createLauncher.launch("fable-backup-$stamp.fable") }
                }
            }
        )
        SettingRow(
            title = "Restore from backup",
            subtitle = "Merges bookmarks, history, passwords and tabs; replaces settings",
            icon = Icons.Outlined.Restore,
            onClick = { if (!state.busy) runCatching { openLauncher.launch(arrayOf("*/*")) } }
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
            text = { Text("Cookies, site data and super incognito settings are applied after a restart.") },
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
                if (confirm) Text("Choose a password of at least ${BackupManager.MIN_PASSWORD} characters. It cannot be recovered if you forget it.")
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    singleLine = true,
                    label = { Text("Password") },
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
