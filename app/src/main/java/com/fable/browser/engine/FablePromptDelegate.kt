package com.fable.browser.engine

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.fable.browser.util.ActivityBridge
import com.fable.browser.util.UploadFiles
import com.fable.browser.data.repo.PasswordStore
import java.util.concurrent.atomic.AtomicBoolean
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate

class FablePromptDelegate(
    private val bridge: PromptBridge,
    private val activityBridge: ActivityBridge,
    private val passwords: PasswordManager? = null,
    private val incognito: Boolean = false
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

    override fun onLoginSave(
        session: GeckoSession,
        request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val manager = passwords ?: return GeckoResult.fromValue(request.dismiss())
        val entry = request.options.firstOrNull()?.value ?: return GeckoResult.fromValue(request.dismiss())
        val host = PasswordStore.hostOf(entry.origin)
        if (host.isEmpty() || entry.password.isEmpty() || !manager.canSave(host, incognito)) {
            return GeckoResult.fromValue(request.dismiss())
        }
        val existing = manager.existing(entry)
        if (existing != null && existing.password == entry.password && existing.username == entry.username) {
            return GeckoResult.fromValue(request.dismiss())
        }
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val done = AtomicBoolean(false)
        fun finish(response: () -> PromptDelegate.PromptResponse = { request.dismiss() }) {
            bridge.clear()
            if (done.compareAndSet(false, true)) result.complete(runCatching { response() }.getOrElse { request.dismiss() })
        }
        bridge.show(
            FablePrompt.SaveLogin(
                title = if (existing != null) "Update saved password?" else "Save password?",
                host = host,
                username = entry.username,
                password = entry.password,
                update = existing != null,
                onSave = { user, pass ->
                    val savedEntry = Autocomplete.LoginEntry.Builder()
                        .guid(existing?.guid ?: entry.guid)
                        .origin(entry.origin)
                        .formActionOrigin(entry.formActionOrigin)
                        .httpRealm(entry.httpRealm)
                        .username(user)
                        .password(pass)
                        .build()
                    manager.save(savedEntry)
                    activityBridge.toast("Password saved")
                    finish { request.confirm(Autocomplete.LoginSaveOption(savedEntry)) }
                },
                onNever = {
                    manager.neverForSite(host)
                    finish()
                },
                onDismiss = { finish() }
            )
        )
        return result
    }

    override fun onLoginSelect(
        session: GeckoSession,
        request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val manager = passwords ?: return GeckoResult.fromValue(request.dismiss())
        val options = request.options
        if (options.isEmpty() || !manager.canFill(incognito)) return GeckoResult.fromValue(request.dismiss())
        val host = PasswordStore.hostOf(options[0].value.origin)
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val done = AtomicBoolean(false)
        fun finish(response: () -> PromptDelegate.PromptResponse) {
            bridge.clear()
            if (done.compareAndSet(false, true)) result.complete(response())
        }
        bridge.show(
            FablePrompt.SelectLogin(
                title = "Sign in with a saved password",
                host = host,
                accounts = options.map { option ->
                    val generated = (option.hint and 1) != 0
                    LoginChoice(
                        label = when {
                            generated -> "Use a strong generated password"
                            option.value.username.isBlank() -> "(no username)"
                            else -> option.value.username
                        },
                        detail = if (generated) "Saved after you sign up" else PasswordStore.hostOf(option.value.origin) + "  " + "\u2022".repeat(8)
                    )
                },
                onPick = { index ->
                    val picked = options.getOrNull(index)
                    if (picked == null) finish { request.dismiss() }
                    else {
                        manager.store.markUsed(picked.value.guid)
                        finish { request.confirm(picked) }
                    }
                },
                onDismiss = { finish { request.dismiss() } }
            )
        )
        return result
    }
}
