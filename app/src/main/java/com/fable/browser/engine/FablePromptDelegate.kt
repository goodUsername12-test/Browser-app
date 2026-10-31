package com.fable.browser.engine

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.fable.browser.util.ActivityBridge
import com.fable.browser.util.UploadFiles
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate

/**
 * Routes every web-content prompt into [PromptBridge] so Compose draws it with
 * the app theme, and hands `<input type="file">` to [ActivityBridge].
 *
 * Nothing here persists anything: there is no autofill store, no "remember my
 * answer" for credentials, and no login/password saving at all — which is also
 * why incognito cannot leak a saved credential into the normal profile.
 */
class FablePromptDelegate(
    private val bridge: PromptBridge,
    private val activityBridge: ActivityBridge
) : PromptDelegate {
    private val mainHandler = Handler(Looper.getMainLooper())


    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AlertPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        bridge.show(
            FablePrompt.Alert(
                title = prompt.title?.ifBlank { null } ?: "This page says",
                message = prompt.message.orEmpty(),
                onDismiss = {
                    bridge.clear()
                    result.complete(prompt.dismiss())
                }
            )
        )
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ButtonPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        bridge.show(
            FablePrompt.Confirm(
                title = prompt.title?.ifBlank { null } ?: "This page says",
                message = prompt.message.orEmpty(),
                positiveLabel = "OK",
                negativeLabel = "Cancel",
                onConfirm = {
                    bridge.clear()
                    result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE))
                },
                onDismiss = {
                    bridge.clear()
                    result.complete(prompt.dismiss())
                }
            )
        )
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.TextPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        bridge.show(
            FablePrompt.TextInput(
                title = prompt.title?.ifBlank { null } ?: "This page asks",
                message = prompt.message.orEmpty(),
                defaultValue = prompt.defaultValue.orEmpty(),
                onConfirm = { value ->
                    bridge.clear()
                    result.complete(prompt.confirm(value))
                },
                onDismiss = {
                    bridge.clear()
                    result.complete(prompt.dismiss())
                }
            )
        )
        return result
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AuthPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val onlyPassword = prompt.authOptions.flags and
            PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        bridge.show(
            FablePrompt.Auth(
                title = prompt.title?.ifBlank { null } ?: "Sign in",
                message = prompt.message.orEmpty(),
                usernameRequired = !onlyPassword,
                onConfirm = { username, password ->
                    bridge.clear()
                    if (onlyPassword) result.complete(prompt.confirm(password))
                    else result.complete(prompt.confirm(username, password))
                },
                onDismiss = {
                    bridge.clear()
                    result.complete(prompt.dismiss())
                }
            )
        )
        return result
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ChoicePrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val multiple = prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
        val options = prompt.choices.orEmpty().mapNotNull { choice ->
            choice?.let { ChoiceOption(it.id, it.label ?: it.id, it.selected) }
        }
        if (options.isEmpty()) {
            result.complete(prompt.dismiss())
            return result
        }
        bridge.show(
            FablePrompt.Choice(
                title = prompt.title?.ifBlank { null } ?: "Choose",
                options = options,
                multiple = multiple,
                onConfirm = { selected ->
                    bridge.clear()
                    result.complete(prompt.confirm(selected.toTypedArray()))
                },
                onDismiss = {
                    bridge.clear()
                    result.complete(prompt.dismiss())
                }
            )
        )
        return result
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.BeforeUnloadPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        bridge.show(
            FablePrompt.Confirm(
                title = "Leave this page?",
                message = "Changes you made may not be saved.",
                positiveLabel = "Leave",
                negativeLabel = "Stay",
                onConfirm = {
                    bridge.clear()
                    result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                },
                onDismiss = {
                    bridge.clear()
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                }
            )
        )
        return result
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.RepostConfirmPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        bridge.show(
            FablePrompt.Confirm(
                title = "Resend form data?",
                message = "This page needs to resubmit what you sent to display again.",
                positiveLabel = "Resend",
                negativeLabel = "Cancel",
                onConfirm = {
                    bridge.clear()
                    result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                },
                onDismiss = {
                    bridge.clear()
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                }
            )
        )
        return result
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.FilePrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        val mimeTypes = UploadFiles.normalizeMimeTypes(prompt.mimeTypes)
        activityBridge.requestFiles(mimeTypes, multiple) { uris ->
            if (uris.isEmpty()) {
                result.complete(prompt.dismiss())
                return@requestFiles
            }
            val context = activityBridge.context().applicationContext
            // Copy off the main thread (videos can be large), then answer the page.
            Thread({
                UploadFiles.cleanOld(context)
                val files = uris.mapNotNull { UploadFiles.copyToUploads(context, it) }
                mainHandler.post {
                    runCatching {
                        when {
                            files.isEmpty() -> result.complete(prompt.dismiss())
                            multiple -> result.complete(prompt.confirm(context, files.toTypedArray<Uri>()))
                            else -> result.complete(prompt.confirm(context, files.first()))
                        }
                    }.onFailure { runCatching { result.complete(prompt.dismiss()) } }
                }
            }, "fable-upload").start()
        }
        return result
    }

    /**
     * Login/credential saving is deliberately unimplemented: Fable never stores
     * passwords, so there is nothing to leak between profiles or off the device.
     */
    override fun onLoginSave(
        session: GeckoSession,
        request: PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSaveOption>
    ): GeckoResult<PromptDelegate.PromptResponse> = GeckoResult.fromValue(request.dismiss())

    override fun onLoginSelect(
        session: GeckoSession,
        request: PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSelectOption>
    ): GeckoResult<PromptDelegate.PromptResponse> = GeckoResult.fromValue(request.dismiss())
}
