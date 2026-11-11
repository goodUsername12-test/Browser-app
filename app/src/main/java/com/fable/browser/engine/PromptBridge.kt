package com.fable.browser.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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

    data class Permission(
        override val title: String,
        val origin: String,
        val message: String,
        val onAllow: (remember: Boolean) -> Unit,
        val onBlock: (remember: Boolean) -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    data class SaveLogin(
        override val title: String,
        val host: String,
        val username: String,
        val password: String,
        val update: Boolean,
        val onSave: (username: String, password: String) -> Unit,
        val onNever: () -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt

    data class SelectLogin(
        override val title: String,
        val host: String,
        val accounts: List<LoginChoice>,
        val onPick: (Int) -> Unit,
        override val onDismiss: () -> Unit,
        val offersGenerated: Boolean = false,
        val onOwnPassword: () -> Unit = onDismiss,
        val onNeverSuggest: () -> Unit = onDismiss
    ) : FablePrompt

    data class IncognitoDownload(
        override val title: String,
        val fileName: String,
        val onConfirm: () -> Unit,
        override val onDismiss: () -> Unit
    ) : FablePrompt
}

data class ChoiceOption(val id: String, val label: String, val selected: Boolean)

data class LoginChoice(val label: String, val detail: String)

@Singleton
class PromptBridge @Inject constructor() {
    private val _current = MutableStateFlow<FablePrompt?>(null)
    val current: StateFlow<FablePrompt?> = _current.asStateFlow()

    fun show(prompt: FablePrompt) {
        _current.value?.onDismiss?.invoke()
        _current.value = prompt
    }

    fun clear() {
        _current.value = null
    }

    fun dismissAll() {
        _current.value?.onDismiss?.invoke()
        _current.value = null
    }
}
