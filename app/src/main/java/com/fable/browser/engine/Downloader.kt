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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val activityBridge: ActivityBridge
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun enqueue(response: WebResponse, fromIncognito: Boolean) {
        val fileName = fileNameOf(response)
        val mime = response.headers["Content-Type"]?.substringBefore(';')?.trim()
        val total = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
        scope.launch {
            runDownload(
                fileName = fileName,
                url = response.uri,
                mimeType = mime,
                totalBytes = total,
                fromIncognito = fromIncognito,
                openStream = { response.body }
            )
        }
    }

    fun enqueueUrl(url: String, suggestedName: String?, fromIncognito: Boolean) {
        scope.launch {
            var connection: HttpURLConnection? = null
            runCatching {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("DNT", "1")
                    setRequestProperty("Sec-GPC", "1")
                    connect()
                }
                val conn = connection!!
                runDownload(
                    fileName = suggestedName?.takeIf { it.isNotBlank() } ?: fileNameFromUrl(url),
                    url = url,
                    mimeType = conn.contentType?.substringBefore(';')?.trim(),
                    totalBytes = conn.contentLengthLong,
                    fromIncognito = fromIncognito,
                    openStream = { conn.inputStream }
                )
            }.onFailure {
                notifications.showFailure(suggestedName ?: fileNameFromUrl(url))
            }
            connection?.disconnect()
        }
    }

    fun savePdf(stream: InputStream, fileName: String, fromIncognito: Boolean) {
        scope.launch {
            runDownload(
                fileName = if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf",
                url = "about:pdf",
                mimeType = "application/pdf",
                totalBytes = -1,
                fromIncognito = fromIncognito,
                openStream = { stream }
            )
        }
    }

    private suspend fun runDownload(
        fileName: String,
        url: String,
        mimeType: String?,
        totalBytes: Long,
        fromIncognito: Boolean,
        openStream: () -> InputStream?
    ) = withContext(Dispatchers.IO) {
        val target = createTarget(fileName, mimeType)
        if (target == null) {
            notifications.showFailure(fileName)
            return@withContext
        }
        val id = repository.start(
            DownloadEntry(
                fileName = fileName,
                url = url,
                filePath = target.displayPath,
                mimeType = mimeType,
                totalBytes = totalBytes,
                fromIncognito = fromIncognito
            )
        )
        _activeCount.value = _activeCount.value + 1
        DownloadService.start(context)
        notifications.showProgress(id, fileName, 0, totalBytes)

        val input = openStream()
        if (input == null) {
            repository.setStatus(id, DownloadStatus.FAILED)
            notifications.showFailure(fileName)
            _activeCount.value = (_activeCount.value - 1).coerceAtLeast(0)
            return@withContext
        }

        var written = 0L
        val result = runCatching {
            input.use { source ->
                target.openOutput().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastNotify = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        written += read
                        if (written - lastNotify > 256 * 1024) {
                            lastNotify = written
                            repository.updateProgress(id, written, totalBytes)
                            notifications.showProgress(id, fileName, written, totalBytes)
                        }
                    }
                    sink.flush()
                }
            }
        }
        target.finish()
        _activeCount.value = (_activeCount.value - 1).coerceAtLeast(0)

        if (result.isSuccess) {
            repository.updateProgress(id, written, if (totalBytes > 0) totalBytes else written)
            repository.setStatus(id, DownloadStatus.COMPLETED)
            notifications.showComplete(id, fileName, target.contentUri, mimeType)
        } else {
            repository.setStatus(id, DownloadStatus.FAILED)
            target.delete()
            notifications.showFailure(fileName)
        }
    }

    suspend fun open(entry: DownloadEntry) {
        val uri = withContext(Dispatchers.IO) { resolveUri(entry) } ?: run {
            activityBridge.toast(context.getString(R.string.cannot_open_file))
            return
        }
        activityBridge.openFile(uri, entry.mimeType)
    }

    private fun resolveUri(entry: DownloadEntry): Uri? {
        if (entry.filePath.startsWith("content://")) return Uri.parse(entry.filePath)
        val file = File(entry.filePath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private interface Target {
        val displayPath: String
        val contentUri: Uri?
        fun openOutput(): OutputStream
        fun finish()
        fun delete()
    }

    private fun createTarget(fileName: String, mimeType: String?): Target? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(fileName, mimeType)
        else legacyTarget(fileName)

    private fun mediaStoreTarget(fileName: String, mimeType: String?): Target? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            if (mimeType != null) put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return object : Target {
            override val displayPath = uri.toString()
            override val contentUri: Uri = uri
            override fun openOutput(): OutputStream =
                resolver.openOutputStream(uri) ?: error("cannot open $uri")

            override fun finish() {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
            }

            override fun delete() {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun legacyTarget(fileName: String): Target? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = uniqueFile(dir, fileName)
        return object : Target {
            override val displayPath = file.absolutePath
            override val contentUri: Uri? =
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()

            override fun openOutput(): OutputStream = file.outputStream()
            override fun finish() {
                context.sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file))
                )
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
        val disposition = response.headers["Content-Disposition"]
        val fromHeader = disposition?.let { header ->
            FILENAME_STAR_REGEX.find(header)?.groupValues?.get(1)?.let {
                runCatching { URLDecoder.decode(it.substringAfterLast("''"), "UTF-8") }.getOrNull()
            } ?: FILENAME_REGEX.find(header)?.groupValues?.get(1)
        }
        return sanitize(fromHeader?.takeIf { it.isNotBlank() } ?: fileNameFromUrl(response.uri))
    }

    private fun fileNameFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val name = path.substringAfterLast('/')
        return sanitize(name.ifBlank { "download" })
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(180).ifBlank { "download" }

    companion object {
        const val SUBFOLDER = "Fable"
        private val FILENAME_REGEX = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
        private val FILENAME_STAR_REGEX =
            Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
    }
}
