package com.fable.browser.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fable.browser.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricGate @Inject constructor(private val activityBridge: ActivityBridge) {
    private val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(): Boolean {
        val activity = activityBridge.activity() ?: return false
        return BiometricManager.from(activity).canAuthenticate(allowed) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
        val activity = activityBridge.activity() as? FragmentActivity
        if (activity == null || !isAvailable()) {
            onSuccess()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(allowed)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { onFailure() }
    }
}
