package com.fable.browser.engine

import android.util.Base64
import java.util.zip.CRC32

object NativeFastEngine {
    val isNativeAvailable: Boolean = runCatching {
        System.loadLibrary("fable_fast")
        true
    }.getOrDefault(false)

    @JvmStatic
    private external fun nativePrewarm(): Boolean

    @JvmStatic
    private external fun nativeDecodeBase64(b64: String): ByteArray?

    @JvmStatic
    private external fun nativeCrc32(bytes: ByteArray): Long

    @JvmStatic
    private external fun nativeHash64(bytes: ByteArray): Long

    @JvmStatic
    private external fun nativeFastHash64(str: String): Long

    @JvmStatic
    private external fun nativeMatchDomain(host: String, domain: String): Boolean

    @JvmStatic
    private external fun nativeIsBlockedDomain(host: String): Boolean

    @JvmStatic
    private external fun nativeSetGameBoost(enable: Boolean): Boolean

    @JvmStatic
    private external fun nativeNotifyHeavyLoadBurst(): Boolean

    @JvmStatic
    private external fun nativeRecordFrameDeltaNs(deltaNs: Long, nowNs: Long): Int

    @JvmStatic
    private external fun nativeSetScreenRecording(recording: Boolean): Boolean

    @JvmStatic
    private external fun nativeResetTouchPointer(pointerId: Int)

    @JvmStatic
    private external fun nativeFilterCameraMove(pointerId: Int, x: Float, y: Float, timeMs: Long): Boolean

    @JvmStatic
    private external fun nativeIsFastAsset(url: String, docHost: String?): Boolean

    fun prewarm() {
        if (isNativeAvailable) {
            runCatching { nativePrewarm() }
        }
    }

    fun decodeBase64(data: String): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        if (isNativeAvailable) {
            runCatching { nativeDecodeBase64(data) }.getOrNull()?.let { return it }
        }
        return Base64.decode(data, Base64.DEFAULT)
    }

    fun crc32(bytes: ByteArray): Long {
        if (bytes.isEmpty()) return 0L
        if (isNativeAvailable) {
            runCatching { return nativeCrc32(bytes) and 0xFFFFFFFFL }
        }
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
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

    fun fastHash64(str: String): Long {
        if (str.isEmpty()) return 0L
        if (isNativeAvailable) {
            runCatching { return nativeFastHash64(str) }
        }
        return hash64(str.toByteArray(Charsets.UTF_8))
    }

    fun matchDomain(host: String, domain: String): Boolean {
        if (host.isEmpty() || domain.isEmpty() || host.length < domain.length) return false
        if (isNativeAvailable) {
            runCatching { return nativeMatchDomain(host, domain) }
        }
        if (host.length == domain.length) return host.equals(domain, ignoreCase = true)
        val prefix = host.length - domain.length
        return host[prefix - 1] == '.' && host.endsWith(domain, ignoreCase = true)
    }

    fun isBlockedDomain(host: String): Boolean {
        if (host.isEmpty()) return false
        if (isNativeAvailable) {
            runCatching { return nativeIsBlockedDomain(host) }
        }
        return false
    }

    fun setGameBoost(enable: Boolean) {
        if (isNativeAvailable) {
            runCatching { nativeSetGameBoost(enable) }
        }
    }

    fun notifyHeavyLoadBurst() {
        if (isNativeAvailable) {
            runCatching { nativeNotifyHeavyLoadBurst() }
        }
    }

    fun recordFrameDeltaNs(deltaNs: Long, nowNs: Long = System.nanoTime()): Int {
        if (isNativeAvailable) {
            runCatching { return nativeRecordFrameDeltaNs(deltaNs, nowNs) }
        }
        return if (deltaNs > 0) (1_000_000_000L / deltaNs).toInt().coerceIn(1, 120) else 120
    }

    fun setScreenRecording(recording: Boolean) {
        if (isNativeAvailable) {
            runCatching { nativeSetScreenRecording(recording) }
        }
    }

    fun resetTouchPointer(pointerId: Int) {
        if (isNativeAvailable) {
            runCatching { nativeResetTouchPointer(pointerId) }
        }
    }

    fun filterCameraMove(pointerId: Int, x: Float, y: Float, timeMs: Long): Boolean {
        if (isNativeAvailable) {
            runCatching { return nativeFilterCameraMove(pointerId, x, y, timeMs) }
        }
        return true
    }

    fun isFastAsset(url: String, docHost: String?): Boolean {
        if (isNativeAvailable) {
            runCatching { return nativeIsFastAsset(url, docHost) }
        }
        return false
    }
}
