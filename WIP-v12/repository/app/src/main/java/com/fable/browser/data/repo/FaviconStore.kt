package com.fable.browser.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class SiteIconImage(val bitmap: Bitmap, val fullBleed: Boolean)

// favicons
@Singleton
class FaviconStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File by lazy { File(context.filesDir, "favicons").apply { mkdirs() } }
    private val memory = LruCache<String, SiteIconImage>(120)
    private val missing = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val limiter = Semaphore(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun key(url: String): String? {
        val host = runCatching { Uri.parse(url.trim()).host }.getOrNull()?.lowercase() ?: return null
        val clean = host.removePrefix("www.").removePrefix("m.")
        return clean.takeIf { it.contains('.') }
    }

    fun cached(url: String): SiteIconImage? = key(url)?.let { memory.get(it) }

    suspend fun load(url: String): SiteIconImage? {
        val k = key(url) ?: return null
        memory.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = fileFor(k)
            if (file.exists()) {
                decodeFile(file)?.let {
                    memory.put(k, it)
                    return@withContext it
                }
            }
            fetchDefault(url, k)
        }
    }

    fun onPageIcons(pageUrl: String, icons: List<String>) {
        val k = key(pageUrl) ?: return
        val candidates = icons.filter {
            it.startsWith("https://") || it.startsWith("http://") ||
                (it.startsWith("data:image/") && it.length <= MAX_DATA_URI)
        }
        if (candidates.isEmpty()) return
        if (!inFlight.add(k)) return
        scope.launch {
            try {
                val file = fileFor(k)
                if (file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_MS) return@launch
                limiter.withPermit {
                    for (c in candidates) {
                        val image = download(c) ?: continue
                        store(k, image)
                        break
                    }
                }
            } finally {
                inFlight.remove(k)
            }
        }
    }

    fun prune(maxAgeMs: Long = PRUNE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        runCatching { dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() } }
    }

    fun clear() {
        memory.evictAll()
        missing.clear()
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        _revision.value = _revision.value + 1
    }

    private suspend fun fetchDefault(url: String, k: String): SiteIconImage? {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val scheme = if (uri.scheme == "http") "http" else "https"
        val marker = File(dir, name(k) + ".none")
        val lastMiss = missing[k] ?: if (marker.exists()) marker.lastModified() else 0L
        if (System.currentTimeMillis() - lastMiss < MISS_MS) return null
        if (!inFlight.add(k)) return null
        try {
            return limiter.withPermit {
                val bases = linkedSetOf("$scheme://$host")
                if (!host.startsWith("www.") && host.count { it == '.' } == 1) bases += "$scheme://www.$host"
                for (base in bases) {
                    for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
                        val image = download(base + path) ?: continue
                        store(k, image)
                        return@withPermit memory.get(k)
                    }
                }
                missing[k] = System.currentTimeMillis()
                runCatching { marker.writeText("") }
                null
            }
        } finally {
            inFlight.remove(k)
        }
    }

    private fun store(k: String, bitmap: Bitmap) {
        val image = SiteIconImage(bitmap, isFullBleed(bitmap))
        runCatching {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            fileFor(k).writeBytes(out.toByteArray())
            File(dir, name(k) + ".none").delete()
        }
        missing.remove(k)
        memory.put(k, image)
        _revision.value = _revision.value + 1
    }

    private fun download(url: String): Bitmap? {
        val bytes = if (url.startsWith("data:image/")) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).contains("base64")) return null
            runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
        } else {
            fetchBytes(url)
        } ?: return null
        return decode(bytes)
    }

    private fun fetchBytes(url: String): ByteArray? {
        var target = runCatching { URL(url) }.getOrNull() ?: return null
        repeat(5) {
            val c = runCatching { target.openConnection() as HttpURLConnection }.getOrNull() ?: return null
            try {
                c.connectTimeout = 5000
                c.readTimeout = 6000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/*;q=0.8,*/*;q=0.5")
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location") ?: return null
                    target = URL(target, location)
                    if (target.protocol != "https" && target.protocol != "http") return null
                    return@repeat
                }
                if (code != 200) return null
                val type = c.contentType?.lowercase() ?: ""
                if (type.contains("text/html") || type.contains("svg")) return null
                val out = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return null
                        out.write(buffer, 0, n)
                    }
                }
                return out.toByteArray()
            } catch (e: Exception) {
                return null
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET && bounds.outHeight / (sample * 2) >= TARGET) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (bitmap.width < 16 || bitmap.height < 16) return null
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= TARGET) return bitmap
        val scale = TARGET.toFloat() / max
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun decodeFile(file: File): SiteIconImage? {
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        return SiteIconImage(bitmap, isFullBleed(bitmap))
    }

    private fun isFullBleed(bitmap: Bitmap): Boolean {
        if (bitmap.width < 96 || bitmap.height < 96) return false
        return runCatching {
            val w = bitmap.width - 1
            val h = bitmap.height - 1
            listOf(bitmap.getPixel(2, 2), bitmap.getPixel(w - 2, 2), bitmap.getPixel(2, h - 2), bitmap.getPixel(w - 2, h - 2))
                .all { (it ushr 24) == 0xFF }
        }.getOrDefault(false)
    }

    private fun fileFor(k: String): File = File(dir, name(k) + ".png")

    private fun name(k: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(k.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TARGET = 128
        private const val MAX_BYTES = 600_000
        private const val MISS_MS = 12L * 60 * 60 * 1000
        private const val REFRESH_MS = 5L * 24 * 60 * 60 * 1000
        private const val PRUNE_MS = 60L * 24 * 60 * 60 * 1000
        private const val MAX_DATA_URI = 800_000
        private const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) FableBrowser"
    }
}
