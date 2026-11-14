package com.fable.browser.engine

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.fable.browser.R
import com.fable.browser.util.ActivityBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageArchiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: Downloader,
    private val activityBridge: ActivityBridge
) {
    private class Active(
        val id: String,
        val exportDir: File,
        var currentFile: File,
        var zip: ZipOutputStream,
        val title: String,
        val page: String,
        val incognito: Boolean,
        val mode: String,
        val engine: String,
        val splitPartMb: Int
    ) {
        val completedParts = ArrayList<File>()
        var entry: String? = null
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        var files: Int = 0
        var bytes: Long = 0
        var observedResources: Int = 0
        var nativeRequested: Int = 0
        var nativeSaved: Int = 0
        val failedReasons = ConcurrentLinkedQueue<JSONObject>()
        var lastToast: Long = 0
        val names = HashSet<String>()
        val zipLock = Any()

        fun rotatePartIfNeeded() {
            if (splitPartMb <= 0 || entry != null) return
            val limitBytes = splitPartMb.toLong() * 1024L * 1024L
            runCatching { zip.flush() }
            if (currentFile.length() >= limitBytes) {
                runCatching { zip.close() }
                completedParts.add(currentFile)
                val nextIndex = completedParts.size + 1
                val nextFile = File(exportDir, "page-$id-p$nextIndex.zip")
                val nextZip = ZipOutputStream(BufferedOutputStream(FileOutputStream(nextFile), 512 * 1024))
                nextZip.setLevel(4)
                currentFile = nextFile
                zip = nextZip
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<JSONObject>(Channel.UNLIMITED)

    @Volatile private var pendingId: String? = null
    @Volatile private var requestedAt: Long = 0
    @Volatile private var active: Active? = null

    init {
        scope.launch {
            for (event in events) {
                runCatching { handle(event) }.onFailure {
                    Log.w(TAG, "page archive failed", it)
                    abort(context.getString(R.string.page_code_failed))
                }
            }
        }
        scope.launch {
            while (true) {
                delay(5_000)
                events.trySend(JSONObject().put("op", "tick"))
            }
        }
    }

    fun newRequest(): String? {
        if (active != null) {
            activityBridge.toast(context.getString(R.string.page_code_busy))
            return null
        }
        val id = UUID.randomUUID().toString()
        pendingId = id
        requestedAt = System.currentTimeMillis()
        activityBridge.toast(context.getString(R.string.page_code_collecting))
        return id
    }

    fun onMessage(json: JSONObject) {
        events.trySend(json)
    }

    private suspend fun handle(json: JSONObject) {
        val op = json.optString("op")
        if (op == "tick") {
            val a = active
            val now = System.currentTimeMillis()
            if (a != null && now - a.lastActivity > IDLE_TIMEOUT_MS) {
                abort(context.getString(R.string.page_code_failed))
            } else if (a == null && pendingId != null && now - requestedAt > START_TIMEOUT_MS) {
                pendingId = null
                activityBridge.toast(context.getString(R.string.page_code_no_response))
            }
            return
        }
        val id = json.optString("id")
        if (id.isEmpty() || id != pendingId) return
        when (op) {
            "begin" -> begin(id, json)
            "file" -> write(id, json)
            "fetch_batch" -> fetchBatch(id, json)
            "progress" -> progress(id, json)
            "end" -> finish(id)
            "error" -> abort(context.getString(R.string.page_code_failed))
        }
    }

    private fun begin(id: String, json: JSONObject) {
        if (active != null) return
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("page-") && System.currentTimeMillis() - it.lastModified() > 3_600_000L }
            ?.forEach { it.delete() }
        val file = File(dir, "page-$id-p1.zip")
        val zip = ZipOutputStream(BufferedOutputStream(FileOutputStream(file), 512 * 1024))
        zip.setLevel(4)
        active = Active(
            id = id,
            exportDir = dir,
            currentFile = file,
            zip = zip,
            title = json.optString("title"),
            page = json.optString("page"),
            incognito = json.optBoolean("incognito"),
            mode = json.optString("mode", "code"),
            engine = json.optString("engine", "GeckoView-144"),
            splitPartMb = json.optInt("splitPartMb", 0).coerceIn(0, 200)
        ).also {
            it.observedResources = json.optInt("observedResources", 0)
        }
    }

    private fun isPreCompressed(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in PRECOMPRESSED_EXTS
    }

    private fun writeCompleteEntryLocked(a: Active, rawPath: String, bytes: ByteArray) {
        synchronized(a.zipLock) {
            a.lastActivity = System.currentTimeMillis()
            if (a.entry != null) {
                a.zip.closeEntry()
                a.entry = null
            }
            val path = cleanPath(rawPath)
            var name = path
            var n = 1
            while (!a.names.add(name)) {
                val dot = path.lastIndexOf('.')
                name = if (dot > path.lastIndexOf('/')) path.substring(0, dot) + "_" + n + path.substring(dot) else path + "_" + n
                n++
            }
            val ze = ZipEntry(name)
            if (isPreCompressed(name)) {
                ze.method = ZipEntry.STORED
                ze.size = bytes.size.toLong()
                ze.compressedSize = bytes.size.toLong()
                ze.crc = NativeFastEngine.crc32(bytes)
            }
            a.zip.putNextEntry(ze)
            if (bytes.isNotEmpty()) {
                a.zip.write(bytes)
                a.bytes += bytes.size
            }
            a.zip.closeEntry()
            a.files++
            a.rotatePartIfNeeded()
        }
    }

    private fun write(id: String, json: JSONObject) {
        val a = active?.takeIf { it.id == id } ?: return
        val path = cleanPath(json.optString("path"))
        val seq = json.optInt("seq")
        val isLast = json.optBoolean("last")
        val data = json.optString("data")
        val decoded = if (data.isNotEmpty()) NativeFastEngine.decodeBase64(data) else ByteArray(0)
        if (seq == 0 && isLast) {
            writeCompleteEntryLocked(a, path, decoded)
            return
        }
        synchronized(a.zipLock) {
            a.lastActivity = System.currentTimeMillis()
            if (seq == 0 || a.entry == null) {
                if (a.entry != null) a.zip.closeEntry()
                var name = path
                var n = 1
                while (!a.names.add(name)) {
                    val dot = path.lastIndexOf('.')
                    name = if (dot > path.lastIndexOf('/')) path.substring(0, dot) + "_" + n + path.substring(dot) else path + "_" + n
                    n++
                }
                val ze = ZipEntry(name)
                a.zip.putNextEntry(ze)
                a.entry = name
            }
            if (decoded.isNotEmpty()) {
                a.zip.write(decoded)
                a.bytes += decoded.size
            }
            if (isLast) {
                a.zip.closeEntry()
                a.entry = null
                a.files++
                a.rotatePartIfNeeded()
            }
        }
    }

    private fun assetPathForUrl(urlStr: String, contentType: String?): String {
        val u = runCatching { android.net.Uri.parse(urlStr) }.getOrNull()
        val host = (u?.host ?: "site").lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        var rawPath = (u?.path ?: "/index").trim('/')
        if (rawPath.isBlank()) rawPath = "index"
        rawPath = rawPath.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { seg -> seg.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100) }
        val q = u?.encodedQuery
        if (!q.isNullOrBlank()) {
            val qHash = (NativeFastEngine.hash64(q) and 0xFFFFFFFFL).toString(16)
            val dot = rawPath.lastIndexOf('.')
            val slash = rawPath.lastIndexOf('/')
            rawPath = if (dot > slash) {
                rawPath.substring(0, dot) + "_q$qHash" + rawPath.substring(dot)
            } else {
                "${rawPath}_q$qHash"
            }
        }
        val lastSeg = rawPath.substringAfterLast('/')
        if (!lastSeg.contains('.')) {
            val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val ext = when {
                mime.contains("javascript") || mime.contains("ecmascript") -> "js"
                mime.contains("css") -> "css"
                mime.contains("html") -> "html"
                mime.contains("json") -> "json"
                mime.contains("wasm") -> "wasm"
                mime.contains("svg") -> "svg"
                mime.contains("png") -> "png"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("webp") -> "webp"
                mime.contains("gif") -> "gif"
                mime.contains("woff2") -> "woff2"
                mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
                mime.contains("ogg") -> "ogg"
                mime.contains("mp4") -> "mp4"
                mime.contains("webm") -> "webm"
                else -> ""
            }
            if (ext.isNotEmpty()) rawPath = "$rawPath.$ext"
        }
        return "files/$host/$rawPath"
    }

    private suspend fun fetchBatch(id: String, json: JSONObject) {
        val a = active?.takeIf { it.id == id } ?: return
        val arr = json.optJSONArray("urls") ?: return
        val totalUrls = arr.length()
        if (totalUrls <= 0) return
        val obs = json.optInt("observedResources", 0)
        if (obs > a.observedResources) a.observedResources = obs

        val ua = json.optString("userAgent").ifBlank { FableUnifiedEngine.defaultBlinkUserAgent }
        val referer = json.optString("referer").ifBlank { a.page }
        val maxFileBytes = json.optInt("maxFileMb", 120).coerceIn(10, 500).toLong() * 1024L * 1024L
        val maxTotalBytes = json.optInt("maxTotalMb", 600).coerceIn(50, 2000).toLong() * 1024L * 1024L

        val uniqueUrls = LinkedHashSet<String>(totalUrls)
        for (i in 0 until totalUrls) {
            val u = arr.optString(i).trim()
            if (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) {
                uniqueUrls.add(u)
            }
        }
        a.nativeRequested += uniqueUrls.size
        if (uniqueUrls.isEmpty()) return

        val urlQueue = Channel<String>(uniqueUrls.size)
        for (u in uniqueUrls) urlQueue.trySend(u)
        urlQueue.close()

        val doneCount = AtomicInteger(0)
        val savedCount = AtomicInteger(0)
        val total = uniqueUrls.size
        val workers = minOf(12, total)

        coroutineScope {
            (0 until workers).map {
                async(Dispatchers.IO) {
                    val readBuf = ByteArray(64 * 1024)
                    for (targetUrl in urlQueue) {
                        if (active?.id != id) break
                        a.lastActivity = System.currentTimeMillis()
                        if (a.bytes >= maxTotalBytes) {
                            if (a.failedReasons.size < 120) {
                                a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", "archive_total_limit_reached"))
                            }
                            continue
                        }
                        runCatching {
                            val cookieHeader = if (!a.incognito) {
                                runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
                            } else null
                            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 8_000
                                readTimeout = 20_000
                                instanceFollowRedirects = true
                                setRequestProperty("Accept", "*/*")
                                if (ua.isNotBlank()) setRequestProperty("User-Agent", ua)
                                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                                if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
                                connect()
                            }
                            val status = conn.responseCode
                            val cLen = conn.contentLengthLong
                            val cType = conn.contentType
                            if (status !in 200..299) {
                                conn.disconnect()
                                if (a.failedReasons.size < 120) {
                                    a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", "http_$status"))
                                }
                                return@runCatching
                            }
                            if (cLen > maxFileBytes) {
                                conn.disconnect()
                                if (a.failedReasons.size < 120) {
                                    a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", "exceeds_max_file_size"))
                                }
                                return@runCatching
                            }
                            val baos = ByteArrayOutputStream(if (cLen in 1..(16 * 1024 * 1024)) cLen.toInt() else 32 * 1024)
                            var overLimit = false
                            conn.inputStream.use { inp ->
                                while (true) {
                                    val n = inp.read(readBuf)
                                    if (n <= 0) break
                                    if (baos.size().toLong() + n > maxFileBytes) {
                                        overLimit = true
                                        break
                                    }
                                    baos.write(readBuf, 0, n)
                                }
                            }
                            conn.disconnect()
                            if (overLimit) {
                                if (a.failedReasons.size < 120) {
                                    a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", "stream_exceeded_max_file_size"))
                                }
                                return@runCatching
                            }
                            val rawBytes = baos.toByteArray()
                            val relPath = assetPathForUrl(targetUrl, cType)
                            writeCompleteEntryLocked(a, relPath, rawBytes)
                            savedCount.incrementAndGet()
                        }.onFailure { err ->
                            if (a.failedReasons.size < 120) {
                                a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", err.javaClass.simpleName))
                            }
                        }
                        val d = doneCount.incrementAndGet()
                        val now = System.currentTimeMillis()
                        if (now - a.lastToast > 6_000) {
                            a.lastToast = now
                            activityBridge.toast(context.getString(R.string.page_code_progress, d, total, a.bytes / (1024 * 1024)))
                        }
                    }
                }
            }.awaitAll()
        }
        a.nativeSaved += savedCount.get()
    }

    private fun progress(id: String, json: JSONObject) {
        val a = active?.takeIf { it.id == id } ?: return
        a.lastActivity = System.currentTimeMillis()
        if (a.lastActivity - a.lastToast > 8_000) {
            a.lastToast = a.lastActivity
            val done = json.optInt("done")
            val total = json.optInt("total").coerceAtLeast(done)
            activityBridge.toast(context.getString(R.string.page_code_progress, done, total, a.bytes / (1024 * 1024)))
        }
    }

    private fun finish(id: String) {
        val a = active?.takeIf { it.id == id } ?: return
        val failedArr = JSONArray()
        for (item in a.failedReasons) failedArr.put(item)
        val totalPartsEstimate = a.completedParts.size + 1
        val manifest = JSONObject().apply {
            put("schema", "fable-page-archive-v2")
            put("capturedAt", java.time.Instant.now().toString())
            put("page", a.page)
            put("title", a.title)
            put("mode", a.mode)
            put("engine", a.engine)
            put("incognito", a.incognito)
            put("observedResources", a.observedResources)
            put("savedFiles", a.files + 1)
            put("totalUncompressedBytes", a.bytes)
            put("nativeBatchRequested", a.nativeRequested)
            put("nativeBatchSaved", a.nativeSaved)
            put("nativeBatchFailedCount", failedArr.length())
            put("partCount", totalPartsEstimate)
            put("splitPartMb", a.splitPartMb)
            put("skippedOrFailedSample", failedArr)
        }
        writeCompleteEntryLocked(a, "capture-manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))

        synchronized(a.zipLock) {
            if (a.entry != null) {
                a.zip.closeEntry()
                a.entry = null
            }
            a.zip.close()
        }
        val allParts = ArrayList<File>(a.completedParts.size + 1).apply {
            addAll(a.completedParts)
            if (a.currentFile.exists() && a.currentFile.length() > 0L) add(a.currentFile)
        }
        active = null
        pendingId = null

        val basePrefix = archiveBasePrefix(a)
        if (allParts.size <= 1) {
            val single = allParts.firstOrNull() ?: return
            downloader.saveLocalFile(single, "$basePrefix.zip", "application/zip", a.incognito)
        } else {
            val totalP = allParts.size
            allParts.forEachIndexed { idx, partFile ->
                val partName = "$basePrefix.part${idx + 1}of$totalP.zip"
                downloader.saveLocalFile(partFile, partName, "application/zip", a.incognito)
            }
        }
    }

    private fun abort(message: String) {
        val a = active
        active = null
        pendingId = null
        if (a != null) {
            runCatching { synchronized(a.zipLock) { a.zip.close() } }
            a.currentFile.delete()
            a.completedParts.forEach { it.delete() }
        }
        activityBridge.toast(message)
    }

    private fun archiveBasePrefix(a: Active): String {
        val host = runCatching { android.net.Uri.parse(a.page).host }.getOrNull().orEmpty().removePrefix("www.")
        val base = a.title.ifBlank { host }.ifBlank { "page" }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(60)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val tag = if (a.mode == "memory") "memory" else "code"
        return "$base-$tag-$stamp"
    }

    private fun cleanPath(raw: String): String {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return parts.joinToString("/").ifEmpty { "file" }.take(400)
    }

    companion object {
        private const val TAG = "PageArchiver"
        private const val IDLE_TIMEOUT_MS = 180_000L
        private const val START_TIMEOUT_MS = 20_000L
        private val PRECOMPRESSED_EXTS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "avif", "woff2",
            "mp3", "ogg", "mp4", "webm", "wasm", "glb", "ktx2", "zip", "gz", "xz", "br"
        )
    }
}
