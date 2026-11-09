package com.fable.browser.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncognitoTabStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Entry(
        val url: String,
        val title: String,
        val state: String?,
        val active: Boolean,
        val pinned: Boolean,
        val superTab: Boolean = false
    )

    private val file: File get() = File(context.noBackupFilesDir, "incognito_tabs.bin")

    @Synchronized
    fun save(entries: List<Entry>) {
        if (entries.isEmpty()) {
            clear()
            return
        }
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("url", e.url)
                        .put("title", e.title)
                        .put("state", e.state ?: "")
                        .put("active", e.active)
                        .put("pinned", e.pinned)
                        .put("super", e.superTab)
                )
            }
            val plain = arr.toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { out ->
                out.write(iv.size)
                out.write(iv)
                out.write(body)
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    @Synchronized
    fun load(): List<Entry> {
        val f = file
        if (!f.exists()) return emptyList()
        return runCatching {
            val bytes = f.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val body = bytes.copyOfRange(1 + ivLen, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val arr = JSONArray(String(cipher.doFinal(body), Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    state = o.optString("state").takeIf { it.isNotEmpty() },
                    active = o.optBoolean("active"),
                    pinned = o.optBoolean("pinned"),
                    superTab = o.optBoolean("super")
                )
            }.filter { it.url.isNotBlank() }
        }.getOrElse {
            f.delete()
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + ".tmp").delete() }
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
        const val ALIAS = "fable_incognito_tabs"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
