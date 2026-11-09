package com.fable.browser.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.PasswordStore
import com.fable.browser.data.repo.SavedLogin
import com.fable.browser.ui.components.ConfirmDialog
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow
import com.fable.browser.util.BiometricGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PasswordsViewModel @Inject constructor(
    private val passwordStore: PasswordStore,
    private val settingsStore: SettingsStore,
    private val biometricGate: BiometricGate
) : ViewModel() {
    val logins: StateFlow<List<SavedLogin>> = passwordStore.logins
    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { passwordStore.all() } }
    }

    fun requireUnlock(action: () -> Unit) {
        if (_unlocked.value) {
            action()
            return
        }
        biometricGate.authenticate(onSuccess = {
            _unlocked.value = true
            action()
        })
    }

    fun save(original: SavedLogin?, site: String, username: String, password: String) {
        val origin = site.trim().let { if (it.contains("://")) it else "https://$it" }
        viewModelScope.launch(Dispatchers.IO) {
            if (original != null && PasswordStore.hostOf(original.origin) != PasswordStore.hostOf(origin)) {
                passwordStore.delete(original.guid)
                passwordStore.upsert(null, origin, null, null, username, password)
            } else {
                passwordStore.upsert(original?.guid, original?.origin ?: origin, original?.formActionOrigin, original?.httpRealm, username, password)
            }
        }
    }

    fun delete(login: SavedLogin) {
        viewModelScope.launch(Dispatchers.IO) { passwordStore.delete(login.guid) }
    }

    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) { passwordStore.deleteAll() }
    }

    fun setSave(v: Boolean) { viewModelScope.launch { settingsStore.setPasswordSave(v) } }
    fun setAutofill(v: Boolean) { viewModelScope.launch { settingsStore.setPasswordAutofill(v) } }
    fun setIncognito(v: Boolean) { viewModelScope.launch { settingsStore.setPasswordIncognito(v) } }
    fun allowSite(site: String) { viewModelScope.launch { settingsStore.setPasswordNever(site, false) } }
}

private fun copySecret(context: Context, label: String, text: String, sensitive: Boolean) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    runCatching { manager.setPrimaryClip(clip) }
}

@Composable
fun PasswordsScreen(
    onBack: () -> Unit,
    viewModel: PasswordsViewModel = hiltViewModel()
) {
    val logins by viewModel.logins.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<SavedLogin?>(null) }
    var editing by remember { mutableStateOf<SavedLogin?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SavedLogin?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    SettingsPage(title = "Passwords", onBack = onBack) {
        SectionHeader("Options")
        SwitchRow(
            title = "Offer to save passwords",
            subtitle = "Ask after you sign in or sign up on a site",
            checked = settings.passwordSave,
            icon = Icons.Outlined.Key,
            onCheckedChange = viewModel::setSave
        )
        SwitchRow(
            title = "Sign in with saved passwords",
            subtitle = "Fill login forms and let you pick an account",
            checked = settings.passwordAutofill,
            onCheckedChange = viewModel::setAutofill
        )
        SwitchRow(
            title = "Use passwords in incognito",
            subtitle = "Off keeps private tabs fully separate",
            checked = settings.passwordIncognito,
            onCheckedChange = viewModel::setIncognito
        )
        SettingRow(
            title = "Stored encrypted on this device",
            subtitle = "Protected by the Android Keystore. Viewing or copying a password needs your fingerprint, face or screen lock.",
            icon = Icons.Outlined.Lock
        )
        ListDivider()

        SectionHeader("Saved passwords (${logins.size})")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Search passwords") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        SettingRow(
            title = "Add password",
            icon = Icons.Outlined.Add,
            onClick = { viewModel.requireUnlock { adding = true } }
        )
        val q = query.trim().lowercase()
        val shown = if (q.isEmpty()) logins else logins.filter { it.host.contains(q) || it.username.lowercase().contains(q) }
        if (shown.isEmpty()) {
            SettingRow(title = if (logins.isEmpty()) "No saved passwords yet" else "No matches")
        }
        shown.forEach { login ->
            SettingRow(
                title = login.host,
                subtitle = login.username.ifBlank { "(no username)" },
                icon = Icons.Outlined.Key,
                onClick = { viewModel.requireUnlock { selected = login } }
            )
        }
        ListDivider()

        if (settings.passwordNeverSites.isNotEmpty()) {
            SectionHeader("Never saved")
            settings.passwordNeverSites.sorted().forEach { site ->
                SettingRow(
                    title = site,
                    icon = Icons.Outlined.Block,
                    trailing = {
                        TextButton(onClick = { viewModel.allowSite(site) }) { Text("Remove") }
                    }
                )
            }
            ListDivider()
        }

        if (logins.isNotEmpty()) {
            SettingRow(
                title = "Delete all passwords",
                icon = Icons.Outlined.Delete,
                onClick = { viewModel.requireUnlock { confirmDeleteAll = true } }
            )
            ListDivider()
        }
    }

    selected?.let { login ->
        val current = logins.firstOrNull { it.guid == login.guid } ?: login
        LoginDetailDialog(
            login = current,
            onCopyUser = { copySecret(context, "Username", current.username, false) },
            onCopyPassword = { copySecret(context, "Password", current.password, true) },
            onEdit = {
                selected = null
                editing = current
            },
            onDelete = {
                selected = null
                confirmDelete = current
            },
            onDismiss = { selected = null }
        )
    }

    editing?.let { login ->
        LoginEditDialog(
            title = "Edit password",
            initialSite = login.origin,
            initialUser = login.username,
            initialPassword = login.password,
            onSave = { site, user, pass ->
                viewModel.save(login, site, user, pass)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    if (adding) {
        LoginEditDialog(
            title = "Add password",
            initialSite = "",
            initialUser = "",
            initialPassword = "",
            onSave = { site, user, pass ->
                viewModel.save(null, site, user, pass)
                adding = false
            },
            onDismiss = { adding = false }
        )
    }

    confirmDelete?.let { login ->
        ConfirmDialog(
            title = "Delete password?",
            message = "The saved password for ${login.username.ifBlank { "this account" }} on ${login.host} will be removed.",
            onConfirm = {
                viewModel.delete(login)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null }
        )
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Delete all passwords?",
            message = "Every saved password will be removed from this device. This cannot be undone.",
            requireTick = true,
            onConfirm = {
                viewModel.deleteAll()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false }
        )
    }
}

@Composable
private fun LoginDetailDialog(
    login: SavedLogin,
    onCopyUser: () -> Unit,
    onCopyPassword: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var reveal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(login.host) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Username", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(login.username.ifBlank { "(no username)" }, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCopyUser) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy username") }
                }
                Text("Password", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reveal) login.password else "\u2022".repeat(10), modifier = Modifier.weight(1f))
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (reveal) "Hide password" else "Show password"
                        )
                    }
                    IconButton(onClick = onCopyPassword) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy password") }
                }
                Text(
                    "Used ${login.timesUsed} times",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("Edit") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun LoginEditDialog(
    title: String,
    initialSite: String,
    initialUser: String,
    initialPassword: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var site by remember { mutableStateOf(initialSite) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPassword) }
    var reveal by remember { mutableStateOf(false) }
    val valid = PasswordStore.hostOf(site).contains('.') && pass.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = site,
                    onValueChange = { site = it },
                    singleLine = true,
                    label = { Text("Site") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(site, user, pass) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
