package com.fable.browser.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.fable.browser.R
import com.fable.browser.data.model.DownloadEntry
import com.fable.browser.data.model.DownloadStatus
import com.fable.browser.data.repo.DownloadRepository
import com.fable.browser.util.ActivityBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Downloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadRepository,
    private val notifications: DownloadNotifier,
    private val activityBridge: ActivityBridge,
    private val engineManager: dagger.Lazy<EngineManager>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Log.w(TAG, "download job failed", e)
    })

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun suggestedName(response: WebResponse): String = fileNameOf(response)

    fun discard(response: WebResponse) {
        runCatching { response.body?.close() }
    }

    fun enqueue(response: WebResponse, fromIncognito: Boolean) {
        activityBridge.ensureNotificationPermission()
        val fileName = fileNameOf(response)
        val mime = mimeOf(response.headers["Content-Type"], fileName)
        val total = response.headers["Content-Length"]?.trim()?.toLongOrNull() ?: -1L
        runCatching { response.setReadTimeoutMillis(READ_TIMEOUT_MS) }
        val body = response.body
        val url = response.uri
        if (body == null && isHttp(url)) {
            enqueueUrl(url, fileName, fromIncognito)
            return
        }
        activityBridge.toast(context.getString(R.string.download_started, fileName))
        scope.launch {
            runDownload(
                fileName = fileName,
                url = url,
                mimeType = mime,
                totalBytes = total,
                fromIncognito = fromIncognito,
                openStream = { body },
                retry = if (isHttp(url)) ({ fetchStream(url, fromIncognito) }) else null
            )
        }
    }

    fun enqueueUrl(
        url: String,
        suggestedName: String?,
        fromIncognito: Boolean,
        mimeTypeHint: String? = null,
        userAgentHint: String? = null,
        preferBlinkNetwork: Boolean = false
    ) {
        activityBridge.ensureNotificationPermission()
        val displayHint = suggestedName?.takeIf { it.isNotBlank() } ?: fileNameFromUrl(url)
        activityBridge.toast(context.getString(R.string.download_started, displayHint))
        scope.launch {
            val rawFallback = suggestedName?.takeIf { it.isNotBlank() }?.let { sanitize(it) } ?: fileNameFromUrl(url)
            val fallbackName = withExtension(rawFallback, mimeTypeHint)
            if (url.startsWith("data:", ignoreCase = true)) {
                val decoded = runCatching { decodeDataUrl(url) }.getOrNull()
                if (decoded == null) {
                    reportFailure(fallbackName)
                    return@launch
                }
                val effMime = decoded.first ?: mimeTypeHint
                val name = withExtension(fallbackName, effMime)
                runDownload(name, "data:", mimeOf(effMime, name), decoded.second.size.toLong(), fromIncognito, { decoded.second.inputStream() }, null)
                return@launch
            }
            if (!fromIncognito && isHttp(url)) {
                val segmentedDone = runCatching { tryParallelSegmentedDownload(url, fallbackName, userAgentHint) }.getOrDefault(false)
                if (segmentedDone) return@launch
            }
            if (preferBlinkNetwork && isHttp(url)) {
                val blinkStream = runCatching { legacyStream(url, fromIncognito, userAgentHint) }.getOrNull()
                if (blinkStream != null) {
                    val name = suggestedName?.takeIf { it.isNotBlank() }?.let { sanitize(it) } ?: blinkStream.fileName ?: fallbackName
                    val effMime = mimeOf(blinkStream.mimeType ?: mimeTypeHint, name)
                    runDownload(
                        fileName = withExtension(name, effMime),
                        url = url,
                        mimeType = effMime,
                        totalBytes = blinkStream.length,
                        fromIncognito = fromIncognito,
                        openStream = { blinkStream.stream },
                        retry = { fetchStream(url, fromIncognito) }
                    )
                    return@launch
                }
            }
            val fetched = runCatching { fetchStream(url, fromIncognito) }.getOrNull()
            if (fetched != null) {
                val name = suggestedName?.takeIf { it.isNotBlank() }?.let { sanitize(it) } ?: fetched.fileName ?: fallbackName
                val effMime = mimeOf(fetched.mimeType ?: mimeTypeHint, name)
                runDownload(
                    fileName = withExtension(name, effMime),
                    url = url,
                    mimeType = effMime,
                    totalBytes = fetched.length,
                    fromIncognito = fromIncognito,
                    openStream = { fetched.stream },
                    retry = { legacyStream(url, fromIncognito, userAgentHint) }
                )
                return@launch
            }
            val legacy = runCatching { legacyStream(url, fromIncognito, userAgentHint) }.getOrNull()
            if (legacy == null) {
                reportFailure(fallbackName)
                return@launch
            }
            val name = suggestedName?.takeIf { it.isNotBlank() }?.let { sanitize(it) } ?: legacy.fileName ?: fallbackName
            val effMime = mimeOf(legacy.mimeType ?: mimeTypeHint, name)
            runDownload(
                fileName = withExtension(name, effMime),
                url = url,
                mimeType = effMime,
                totalBytes = legacy.length,
                fromIncognito = fromIncognito,
                openStream = { legacy.stream },
                retry = null
            )
        }
    }

    fun savePdf(stream: InputStream, fileName: String, fromIncognito: Boolean) {
        scope.launch {
            runDownload(
                fileName = sanitize(if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf"),
                url = "about:pdf",
                mimeType = "application/pdf",
                totalBytes = -1,
                fromIncognito = fromIncognito,
                openStream = { stream },
                retry = null
            )
        }
    }

    fun saveLocalFile(file: File, fileName: String, mimeType: String, fromIncognito: Boolean) {
        activityBridge.ensureNotificationPermission()
        scope.launch {
            try {
                val safeRaw = sanitize(fileName)
                val finalName = if (mimeType == "application/zip" && !safeRaw.endsWith(".zip", ignoreCase = true)) {
                    "$safeRaw.zip"
                } else {
                    withExtension(safeRaw, mimeType)
                }
                runDownload(
                    fileName = finalName,
                    url = "about:local-save",
                    mimeType = mimeType,
                    totalBytes = file.length(),
                    fromIncognito = fromIncognito,
                    openStream = { file.inputStream() },
                    retry = null
                )
            } finally {
                file.delete()
            }
        }
    }

    private class Fetched(val stream: InputStream, val mimeType: String?, val length: Long, val fileName: String?)

    private fun isHttp(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    private suspend fun tryParallelSegmentedDownload(url: String, fallbackName: String, userAgentHint: String? = null): Boolean = withContext(Dispatchers.IO) {
        val cookieHeader = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
        val ua = userAgentHint?.takeIf { it.isNotBlank() } ?: FableUnifiedEngine.defaultBlinkUserAgent
        val headConn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 8000
            readTimeout = 10_000
            instanceFollowRedirects = true
            if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
            if (ua.isNotBlank()) setRequestProperty("User-Agent", ua)
            connect()
        }
        val code = headConn.responseCode
        val total = headConn.contentLengthLong
        val acceptRanges = headConn.getHeaderField("Accept-Ranges").orEmpty()
        val contentType = headConn.contentType
        val dispName = nameFromDisposition(headConn.getHeaderField("Content-Disposition"))
        headConn.disconnect()

        if (code !in 200..299 || total < 2L * 1024L * 1024L || !acceptRanges.contains("bytes", ignoreCase = true)) {
            return@withContext false
        }
        val plan = FableNetworkOptimizer.planForDownload(context, total, fastConn = true)
        val streams = plan.parallelStreams.coerceIn(1, 6)
        if (streams < 2) return@withContext false

        val finalName = dispName ?: fallbackName
        val mime = mimeOf(contentType, finalName)
        val target = createTarget(finalName, mime) ?: return@withContext false
        val id = runCatching {
            repository.start(
                DownloadEntry(
                    fileName = target.fileName,
                    url = url,
                    filePath = target.displayPath,
                    mimeType = mime,
                    totalBytes = total,
                    fromIncognito = false
                )
            )
        }.getOrElse {
            target.delete()
            return@withContext false
        }

        val tmpFile = File(context.cacheDir, "seg_dl_${id}_${System.currentTimeMillis()}.part")
        _activeCount.update { it + 1 }
        try {
            DownloadService.start(context)
            notifications.showProgress(id, target.fileName, 0, total)
            java.io.RandomAccessFile(tmpFile, "rw").use { it.setLength(total) }

            val chunkSize = (total + streams - 1) / streams
            val progressAtomic = java.util.concurrent.atomic.AtomicLong(0L)
            val lastUiAtomic = java.util.concurrent.atomic.AtomicLong(0L)
            val bufSize = plan.socketBufferKb.coerceIn(128, 512) * 1024

            val allOk = coroutineScope {
                (0 until streams).map { idx ->
                    val startByte = idx * chunkSize
                    val endByte = minOf(total - 1, startByte + chunkSize - 1)
                    async(Dispatchers.IO) {
                        if (startByte > endByte) return@async true
                        val partConn = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 45_000
                            instanceFollowRedirects = true
                            setRequestProperty("Range", "bytes=$startByte-$endByte")
                            if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
                            if (ua.isNotBlank()) setRequestProperty("User-Agent", ua)
                            connect()
                        }
                        if (partConn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                            partConn.disconnect()
                            return@async false
                        }
                        partConn.inputStream.use { input ->
                            java.io.RandomAccessFile(tmpFile, "rw").use { raf ->
                                raf.seek(startByte)
                                val buf = ByteArray(bufSize)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    raf.write(buf, 0, n)
                                    val cur = progressAtomic.addAndGet(n.toLong())
                                    val now = System.currentTimeMillis()
                                    val prevT = lastUiAtomic.get()
                                    if (now - prevT > 400 && lastUiAtomic.compareAndSet(prevT, now)) {
                                        repository.updateProgress(id, cur, total)
                                        notifications.showProgress(id, target.fileName, cur, total)
                                    }
                                }
                            }
                        }
                        partConn.disconnect()
                        true
                    }
                }.awaitAll().all { it }
            }

            if (!allOk || progressAtomic.get() != total) {
                tmpFile.delete()
                target.delete()
                repository.setStatus(id, DownloadStatus.FAILED)
                return@withContext false
            }

            tmpFile.inputStream().use { src ->
                target.openOutput().use { dst ->
                    src.copyTo(dst, 512 * 1024)
                    dst.flush()
                    target.sync(dst)
                }
            }
            tmpFile.delete()
            target.finish()
            repository.updateProgress(id, total, total)
            repository.setStatus(id, DownloadStatus.COMPLETED)
            notifications.showComplete(id, target.fileName, target.contentUri, mime)
            activityBridge.toast(context.getString(R.string.download_saved, target.locationLabel))
            true
        } catch (e: Throwable) {
            tmpFile.delete()
            target.delete()
            runCatching { repository.setStatus(id, DownloadStatus.FAILED) }
            false
        } finally {
            _activeCount.update { (it - 1).coerceAtLeast(0) }
        }
    }

    private fun fetchStream(url: String, fromIncognito: Boolean): Fetched? {
        if (!isHttp(url)) return null
        val executor = GeckoWebExecutor(engineManager.get().runtime)
        val request = WebRequest.Builder(url).method("GET").build()
        val flags = if (fromIncognito) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        val response = executor.fetch(request, flags).poll(FETCH_TIMEOUT_MS) ?: return null
        if (response.statusCode !in 200..299) {
            runCatching { response.body?.close() }
            return null
        }
        runCatching { response.setReadTimeoutMillis(READ_TIMEOUT_MS) }
        val body = response.body ?: return null
        return Fetched(
            stream = body,
            mimeType = response.headers["Content-Type"],
            length = response.headers["Content-Length"]?.trim()?.toLongOrNull() ?: -1L,
            fileName = nameFromDisposition(response.headers["Content-Disposition"])
        )
    }

    private fun legacyStream(url: String, fromIncognito: Boolean = false, userAgentHint: String? = null): Fetched? {
        if (!isHttp(url)) return null
        val cookieHeader = if (!fromIncognito) {
            runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
        } else null
        val ua = userAgentHint?.takeIf { it.isNotBlank() } ?: FableUnifiedEngine.defaultBlinkUserAgent
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("DNT", "1")
            setRequestProperty("Sec-GPC", "1")
            if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
            if (ua.isNotBlank()) setRequestProperty("User-Agent", ua)
            connect()
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        return Fetched(
            stream = conn.inputStream,
            mimeType = conn.contentType,
            length = conn.contentLengthLong,
            fileName = nameFromDisposition(conn.getHeaderField("Content-Disposition"))
        )
    }

    private fun decodeDataUrl(url: String): Pair<String?, ByteArray> {
        val comma = url.indexOf(',')
        require(comma >= 5)
        val meta = url.substring(5, comma)
        val data = url.substring(comma + 1)
        val mime = meta.substringBefore(';').ifBlank { null }
        val bytes = if (meta.contains(";base64", ignoreCase = true)) {
            runCatching { NativeFastEngine.decodeBase64(data) }.getOrElse {
                android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            }
        } else {
            URLDecoder.decode(data, "UTF-8").toByteArray()
        }
        return mime to bytes
    }

    private suspend fun runDownload(
        fileName: String,
        url: String,
        mimeType: String?,
        totalBytes: Long,
        fromIncognito: Boolean,
        openStream: () -> InputStream?,
        retry: (() -> Fetched?)?
    ) = withContext(Dispatchers.IO) {
        val target = runCatching { createTarget(fileName, mimeType) }.getOrNull()
        if (target == null) {
            runCatching { openStream()?.close() }
            reportFailure(fileName)
            return@withContext
        }
        val id = runCatching {
            repository.start(
                DownloadEntry(
                    fileName = target.fileName,
                    url = url,
                    filePath = target.displayPath,
                    mimeType = mimeType,
                    totalBytes = totalBytes,
                    fromIncognito = fromIncognito
                )
            )
        }.getOrElse {
            target.delete()
            reportFailure(fileName)
            return@withContext
        }
        _activeCount.update { it + 1 }
        try {
            DownloadService.start(context)
            notifications.showProgress(id, target.fileName, 0, totalBytes)

            var written = copyInto(target, openStream(), id, totalBytes)
            val incomplete = written < 0 || (written == 0L && totalBytes != 0L) || (totalBytes > 0 && written in 0 until totalBytes)
            if (incomplete && retry != null) {
                val again = runCatching { retry() }.getOrNull()
                if (again != null) {
                    val expected = if (again.length > 0) again.length else totalBytes
                    written = copyInto(target, again.stream, id, expected)
                }
            }

            val ok = written > 0 || (written == 0L && totalBytes == 0L)
            val finished = ok && runCatching { target.finish() }.isSuccess
            if (finished) {
                repository.updateProgress(id, written, if (totalBytes > 0) maxOf(totalBytes, written) else written)
                repository.setStatus(id, DownloadStatus.COMPLETED)
                notifications.showComplete(id, target.fileName, target.contentUri, mimeType)
                activityBridge.toast(context.getString(R.string.download_saved, target.locationLabel))
            } else {
                repository.setStatus(id, DownloadStatus.FAILED)
                target.delete()
                reportFailure(target.fileName)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "download failed", e)
            runCatching { repository.setStatus(id, DownloadStatus.FAILED) }
            target.delete()
            reportFailure(target.fileName)
        } finally {
            _activeCount.update { (it - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun copyInto(target: Target, input: InputStream?, id: Long, totalBytes: Long): Long {
        if (input == null) return -1L
        var written = 0L
        val result = runCatching {
            input.use { source ->
                target.openOutput().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var lastNotify = 0L
                    var lastTime = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        sink.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (written - lastNotify > 512 * 1024 && now - lastTime > 400) {
                            lastNotify = written
                            lastTime = now
                            repository.updateProgress(id, written, totalBytes)
                            notifications.showProgress(id, target.fileName, written, totalBytes)
                        }
                    }
                    sink.flush()
                    target.sync(sink)
                }
            }
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "stream copy failed after $written bytes", it) }
        return if (result.isSuccess) written else -1L
    }

    private fun reportFailure(fileName: String) {
        notifications.showFailure(fileName)
        activityBridge.toast(context.getString(R.string.download_failed_named, fileName))
    }

    suspend fun open(entry: DownloadEntry) {
        val uri = withContext(Dispatchers.IO) { resolveUri(entry) } ?: run {
            activityBridge.toast(context.getString(R.string.cannot_open_file))
            return
        }
        activityBridge.openFile(uri, entry.mimeType)
    }

    private fun resolveUri(entry: DownloadEntry): Uri? {
        if (entry.filePath.startsWith("content://")) {
            val uri = Uri.parse(entry.filePath)
            val exists = runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() }
            }.getOrNull()
            return if (exists == false) null else uri
        }
        val file = File(entry.filePath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private interface Target {
        val fileName: String
        val displayPath: String
        val locationLabel: String
        val contentUri: Uri?
        fun openOutput(): OutputStream
        fun sync(stream: OutputStream) {}
        fun finish()
        fun delete()
    }

    private fun createTarget(fileName: String, mimeType: String?): Target? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val root = Environment.DIRECTORY_DOWNLOADS
            runCatching { mediaStoreTarget(fileName, mimeType, root) }.getOrNull()?.let { return it }
            runCatching { mediaStoreTarget(fileName, null, root) }.getOrNull()?.let { return it }
            runCatching { mediaStoreTarget(sanitize("download_" + System.currentTimeMillis() + extOf(fileName)), null, root) }.getOrNull()?.let { return it }
        } else {
            runCatching { legacyTarget(fileName) }.getOrNull()?.let { return it }
        }
        return runCatching { privateTarget(fileName) }.getOrNull()
    }

    private fun mediaStoreTarget(fileName: String, mimeType: String?, relativePath: String): Target? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            if (mimeType != null) put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val probe = runCatching { resolver.openOutputStream(uri, "w")?.close() }.isSuccess
        if (!probe) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        val storedName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: fileName
        return object : Target {
            override val fileName = storedName
            override val displayPath = uri.toString()
            override val locationLabel = "${Environment.DIRECTORY_DOWNLOADS}/$storedName"
            override val contentUri: Uri = uri
            override fun openOutput(): OutputStream =
                resolver.openOutputStream(uri, "wt") ?: error("cannot open $uri")

            override fun sync(stream: OutputStream) {
                if (stream is java.io.FileOutputStream) runCatching { stream.fd.sync() }
            }

            override fun finish() {
                val updated = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
                if (updated <= 0) error("cannot publish $uri")
            }

            override fun delete() {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun legacyTarget(fileName: String): Target? {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return null
        return fileTarget(uniqueFile(dir, fileName), scan = true, label = Environment.DIRECTORY_DOWNLOADS)
    }

    private fun privateTarget(fileName: String): Target? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        if (!dir.exists() && !dir.mkdirs()) return null
        return fileTarget(uniqueFile(dir, fileName), scan = false, label = dir.absolutePath)
    }

    private fun fileTarget(file: File, scan: Boolean, label: String): Target {
        file.outputStream().close()
        return object : Target {
            override val fileName = file.name
            override val displayPath = file.absolutePath
            override val locationLabel = "$label/${file.name}"
            override val contentUri: Uri? =
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()

            override fun openOutput(): OutputStream = file.outputStream()
            override fun sync(stream: OutputStream) {
                if (stream is java.io.FileOutputStream) runCatching { stream.fd.sync() }
            }

            override fun finish() {
                if (!file.exists()) error("missing ${file.absolutePath}")
                if (scan) {
                    context.sendBroadcast(
                        Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file))
                    )
                }
            }

            override fun delete() {
                runCatching { file.delete() }
            }
        }
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$base ($i)$suffix")
            i++
        }
        return candidate
    }

    private fun fileNameOf(response: WebResponse): String {
        val fromHeader = nameFromDisposition(response.headers["Content-Disposition"])
        val base = fromHeader ?: fileNameFromUrl(response.uri)
        return withExtension(base, response.headers["Content-Type"])
    }

    private fun nameFromDisposition(disposition: String?): String? {
        val header = disposition ?: return null
        val star = FILENAME_STAR_REGEX.find(header)?.groupValues?.get(1)?.trim()?.trim('"')?.let {
            runCatching { URLDecoder.decode(it.substringAfter("''"), "UTF-8") }.getOrNull()
        }
        val plain = FILENAME_REGEX.find(header)?.groupValues?.get(1)?.trim()
        val name = (star ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\')
        return name?.takeIf { it.isNotBlank() }?.let { sanitize(it) }
    }

    private fun withExtension(name: String, contentType: String?): String {
        if (name.substringAfterLast('.', "").isNotEmpty() && name.contains('.')) return name
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return name
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
        return sanitize("$name.$ext")
    }

    private fun mimeOf(contentType: String?, fileName: String): String? {
        val declared = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.contains('/') }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val guessed = if (ext.isNotEmpty()) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        return when {
            declared == null -> guessed
            declared == "application/octet-stream" || declared == "binary/octet-stream" || declared == "application/force-download" ||
                declared == "application/x-download" || declared == "application/download" -> guessed ?: "application/octet-stream"
            else -> declared
        }
    }

    private fun fileNameFromUrl(url: String): String {
        if (url.startsWith("data:") || url.startsWith("blob:")) return "download"
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val name = runCatching { URLDecoder.decode(path.substringAfterLast('/'), "UTF-8") }.getOrDefault(path.substringAfterLast('/'))
        return sanitize(name.ifBlank { "download" })
    }

    private fun extOf(name: String): String =
        name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }?.let { ".$it" } ?: ""

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(180).ifBlank { "download" }

    companion object {
        private const val TAG = "FableDownload"
        private const val READ_TIMEOUT_MS = 120_000L
        private const val FETCH_TIMEOUT_MS = 60_000L
        private const val COPY_BUFFER = 256 * 1024
        private val FILENAME_REGEX = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
        private val FILENAME_STAR_REGEX =
            Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
    }
}
