package com.fable.browser.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web content prompts (alert / confirm / prompt / auth / select / beforeunload)
 * and permission requests are surfaced to Compose through this bridge instead of
 * the engine drawing its own dialogs, so everything matches the app theme and
 * nothing leaks page-controlled text into a system dialog.
 */
sealed interface FablePrompt {
    val title: String
    val onDismiss: () -> Unit

    data class Alert(
        override val title: String,
        val message: String,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    data class Confirm(
        override val title: String,
        val message: String,
        val positiveLabel: String,
        val negativeLabel: String,
        val onConfirm: () -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    data class TextInput(
        override val title: String,
        val message: String,
        val defaultValue: String,
        val onConfirm: (String) -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    data class Auth(
        override val title: String,
        val message: String,
        val usernameRequired: Boolean,
        val onConfirm: (username: String, password: String) -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    data class Choice(
        override val title: String,
        val options: List<ChoiceOption>,
        val multiple: Boolean,
        val onConfirm: (List<String>) -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    /** Site permission request (camera / mic / location / notifications …). */
    data class Permission(
        override val title: String,
        val origin: String,
        val message: String,
        val onAllow: (remember: Boolean) -> Unit,
        val onBlock: (remember: Boolean) -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    /** Incognito download confirmation — required per file, never automatic. */
    data class IncognitoDownload(
        override val title: String,
        val fileName: String,
        val onConfirm: () -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt
}

data class ChoiceOption(val id: String, val label: String, val selected: Boolean)

@Singleton
class PromptBridge @Inject constructor() {

    private val _current = MutableStateFlow<FablePrompt?>(null)
    val current: StateFlow<FablePrompt?> = _current.asStateFlow()

    /** Shows [prompt]; the previous prompt (if any) is dismissed first. */
    fun show(prompt: FablePrompt) {
        _current.value?.onDismiss?.invoke()
        _current.value = prompt
    }

    fun clear() {
        _current.value = null
    }

    /** Dismiss without confirming — used when a tab closes or is wiped. */
    fun dismissAll() {
        _current.value?.onDismiss?.invoke()
        _current.value = null
    }
}
