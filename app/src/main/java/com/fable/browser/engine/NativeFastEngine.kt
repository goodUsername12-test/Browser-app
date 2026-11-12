package com.fable.browser.engine

import android.util.Base64

object NativeFastEngine {
    val isNativeAvailable: Boolean = runCatching {
        System.loadLibrary("fable_fast")
        true
    }.getOrDefault(false)

    @JvmStatic
    private external fun nativeDecodeBase64(b64: String): ByteArray?

    @JvmStatic
    private external fun nativeHash64(bytes: ByteArray): Long

    @JvmStatic
    private external fun nativeMatchDomain(host: String, domain: String): Boolean

    fun decodeBase64(data: String): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        if (isNativeAvailable) {
            runCatching { nativeDecodeBase64(data) }.getOrNull()?.let { return it }
        }
        return Base64.decode(data, Base64.DEFAULT)
    }

    fun hash64(bytes: ByteArray): Long {
        if (bytes.isEmpty()) return 0L
        if (isNativeAvailable) {
            runCatching { return nativeHash64(bytes) }
        }
        var h = -0x340d631b7bdddcdbL xor (bytes.size.toLong() * -0x61c8864680b583ebL)
        for (b in bytes) {
            h = (h xor (b.toLong() and 0xFFL)) * 0x100000001b3L
        }
        h = h xor (h ushr 29)
        h *= -0xae502812aa7333L
        return h xor (h ushr 32)
    }

    fun matchDomain(host: String, domain: String): Boolean {
        if (host.isEmpty() || domain.isEmpty() || host.length < domain.length) return false
        if (isNativeAvailable) {
            runCatching { return nativeMatchDomain(host, domain) }
        }
        if (host.length == domain.length) return host == domain
        val prefix = host.length - domain.length
        return host[prefix - 1] == '.' && host.endsWith(domain)
    }
}
