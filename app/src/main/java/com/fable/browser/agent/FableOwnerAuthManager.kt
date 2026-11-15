package com.fable.browser.agent

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

data class OwnerModeStatus(
    val passwordConfigured: Boolean,
    val unlocked: Boolean,
    val remainingUnlockSeconds: Int,
    val lockedOutSeconds: Int,
    val readOnlyNoPageInjectionEnforced: Boolean = true
)

/**
 * Hardware-backed Owner-Only Research Mode (Beta) security gate & Read-Only Page Guard.
 *
 * Non-bypassable invariants:
 * 1. User creates their own password locally on device (zero default or hardcoded password).
 * 2. Verifier is PBKDF2-HMAC-SHA256 (120,000 rounds + 16B random salt) encrypted under Android Keystore AES-256-GCM.
 * 3. Unlocks broader local conversation & read-only page/source/screen research, NEVER page JS/DOM injection,
 *    NEVER disables strict Screen-Only Gaming Mode, NEVER disables Emergency Stop, and still requires confirmation
 *    for irreversible actions (purchases, deletes, account changes, private uploads).
 */
object FableOwnerAuthManager {

    private const val PREFS_NAME = "fable_owner_research_vault"
    private const val KEY_SALT_B64 = "salt_b64"
    private const val KEY_IV_B64 = "cipher_iv_b64"
    private const val KEY_CT_B64 = "cipher_verifier_b64"
    private const val KEYSTORE_ALIAS = "fable_owner_research_aes_v1"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 60_000L

    @Volatile private var unlockedUntilUptimeMs: Long = 0L
    @Volatile private var failedAttempts: Int = 0
    @Volatile private var lockoutUntilUptimeMs: Long = 0L

    // Hard non-bypassable architectural guards
    fun canInjectIntoPage(): Boolean = false
    fun canBypassScreenOnlyGaming(): Boolean = false
    fun canDisableEmergencyStop(): Boolean = false
    fun requiresIrreversibleActionConfirmation(): Boolean = true

    fun isPasswordConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_SALT_B64, null).isNullOrBlank() &&
            !prefs.getString(KEY_CT_B64, null).isNullOrBlank()
    }

    fun isUnlocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (unlockedUntilUptimeMs > 0L && now >= unlockedUntilUptimeMs) {
            unlockedUntilUptimeMs = 0L
            return false
        }
        return unlockedUntilUptimeMs > now
    }

    fun currentStatus(context: Context): OwnerModeStatus {
        val now = SystemClock.uptimeMillis()
        val unlocked = isUnlocked()
        val remUnlock = if (unlocked) ((unlockedUntilUptimeMs - now) / 1000L).toInt().coerceAtLeast(0) else 0
        val remLockout = if (lockoutUntilUptimeMs > now) ((lockoutUntilUptimeMs - now) / 1000L).toInt().coerceAtLeast(1) else 0
        return OwnerModeStatus(
            passwordConfigured = isPasswordConfigured(context),
            unlocked = unlocked,
            remainingUnlockSeconds = remUnlock,
            lockedOutSeconds = remLockout,
            readOnlyNoPageInjectionEnforced = true
        )
    }

    fun lockNow() {
        unlockedUntilUptimeMs = 0L
    }

    fun createOrReplacePassword(
        context: Context,
        newPassword: String,
        autoLockMinutes: Int = 30
    ): Result<Unit> {
        val trimmed = newPassword.trim()
        if (trimmed.length < 8) {
            return Result.failure(IllegalArgumentException("Owner password must be at least 8 characters (16+ recommended)."))
        }
        return runCatching {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val derived = derivePbkdf2(trimmed.toCharArray(), salt)
            val secretKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(derived)

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_SALT_B64, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_IV_B64, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_CT_B64, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply()

            failedAttempts = 0
            lockoutUntilUptimeMs = 0L
            unlockedUntilUptimeMs = SystemClock.uptimeMillis() + autoLockMinutes.coerceIn(5, 240) * 60_000L
        }
    }

    fun unlockWithPassword(
        context: Context,
        passwordAttempt: String,
        autoLockMinutes: Int = 30
    ): Result<Unit> {
        val now = SystemClock.uptimeMillis()
        if (lockoutUntilUptimeMs > now) {
            val sec = ((lockoutUntilUptimeMs - now) / 1000L).toInt().coerceAtLeast(1)
            return Result.failure(IllegalStateException("Too many failed attempts. Try again in ${sec}s."))
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saltB64 = prefs.getString(KEY_SALT_B64, null)
        val ivB64 = prefs.getString(KEY_IV_B64, null)
        val ctB64 = prefs.getString(KEY_CT_B64, null)
        if (saltB64.isNullOrBlank() || ivB64.isNullOrBlank() || ctB64.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No owner password set yet. Create one first."))
        }

        val verified = runCatching {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)

            val secretKey = getExistingKeystoreKey() ?: return@runCatching false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val expectedVerifier = cipher.doFinal(ct)
            val actualVerifier = derivePbkdf2(passwordAttempt.trim().toCharArray(), salt)
            MessageDigest.isEqual(expectedVerifier, actualVerifier)
        }.getOrDefault(false)

        return if (verified) {
            failedAttempts = 0
            lockoutUntilUptimeMs = 0L
            unlockedUntilUptimeMs = now + autoLockMinutes.coerceIn(5, 240) * 60_000L
            Result.success(Unit)
        } else {
            failedAttempts += 1
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                lockoutUntilUptimeMs = now + LOCKOUT_DURATION_MS
                failedAttempts = 0
                Result.failure(IllegalStateException("5 incorrect attempts. Owner Mode locked for 60 seconds."))
            } else {
                Result.failure(IllegalArgumentException("Incorrect owner password."))
            }
        }
    }

    fun canUseBiometrics(context: Context): Boolean = runCatching {
        isPasswordConfigured(context) &&
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    fun promptBiometricUnlock(
        activity: FragmentActivity,
        autoLockMinutes: Int = 30,
        onResult: (Boolean, String) -> Unit
    ) {
        if (!isPasswordConfigured(activity)) {
            onResult(false, "Create an owner password first before using biometric unlock.")
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    failedAttempts = 0
                    lockoutUntilUptimeMs = 0L
                    unlockedUntilUptimeMs = SystemClock.uptimeMillis() + autoLockMinutes.coerceIn(5, 240) * 60_000L
                    onResult(true, "Owner Research Mode unlocked via Biometrics.")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onResult(false, "Biometric not recognized.")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Owner Research Mode (Beta)")
            .setSubtitle("Read-only page guard & Emergency Stop remain active")
            .setNegativeButtonText("Use Password")
            .build()
        prompt.authenticate(info)
    }

    fun resetOwnerSecurityData(context: Context) {
        lockNow()
        failedAttempts = 0
        lockoutUntilUptimeMs = 0L
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    private fun derivePbkdf2(passwordChars: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passwordChars, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun getExistingKeystoreKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        getExistingKeystoreKey()?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }
}
