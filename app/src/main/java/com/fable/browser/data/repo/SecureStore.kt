package com.fable.browser.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private fun file(name: String): File = File(context.noBackupFilesDir, "secure_$name.bin")

    @Synchronized
    fun write(name: String, text: String) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            val target = file(name)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out ->
                out.write(iv.size)
                out.write(iv)
                out.write(data)
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    @Synchronized
    fun read(name: String): String? {
        val f = file(name)
        if (!f.exists()) return null
        return runCatching {
            val bytes = f.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes.copyOfRange(1 + ivLen, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun delete(name: String) {
        runCatching { file(name).delete() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "fable_secure_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
