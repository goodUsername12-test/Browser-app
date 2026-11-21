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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class CapturePhase {
    IDLE,
    CAPTURING,
    PAUSED,
    FINALIZING,
    DONE,
    ERROR
}

data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val sessionId: String? = null,
    val mode: String = "all",
    val continuous: Boolean = false,
    val filesCaptured: Int = 0,
    val bytesCaptured: Long = 0L,
    val observedResources: Int = 0,
    val currentResource: String = "",
    val startedAtMs: Long = 0L,
    val elapsedSec: Int = 0,
    val lastSavedFile: String = "",
    val partial: Boolean = false,
    val statusMessage: String = ""
)

@Singleton
class PageArchiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: Downloader,
    private val activityBridge: ActivityBridge
) {
    private data class StagedRecord(
        val archivePath: String,
        val diskFile: File,
        val size: Long,
        val url: String = "",
        val mime: String = "",
        val hashHex: String = ""
    )

    private class Active(
        val id: String,
        val exportDir: File,
        val stagingDir: File,
        val journalFile: File,
        val title: String,
        val page: String,
        val incognito: Boolean,
        val mode: String,
        val engine: String,
        val splitPartMb: Int,
        val continuous: Boolean,
        val readOnlyZeroMutation: Boolean,
        val includeCookies: Boolean,
        val maxFiles: Int,
        val maxTotalBytes: Long
    ) {
        val paused = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val finalizing = AtomicBoolean(false)

        val startedAtMs: Long = System.currentTimeMillis()
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        @Volatile var currentResource: String = ""
        @Volatile var files: Int = 0
        @Volatile var bytes: Long = 0L
        @Volatile var observedResources: Int = 0
        @Volatile var nativeRequested: Int = 0
        @Volatile var nativeSaved: Int = 0
        @Volatile var limitReachedReason: String? = null
        var lastToast: Long = 0L

        val stagedRecords = ArrayList<StagedRecord>()
        val names = HashSet<String>()
        val failedReasons = ConcurrentLinkedQueue<JSONObject>()
        val writeLock = Any()

        // Multi-chunk in-flight staging file
        var openStreamPath: String? = null
        var openStreamPartFile: File? = null
        var openStreamFinalFile: File? = null
        var openStreamOut: BufferedOutputStream? = null
        var openStreamBytes: Long = 0L

        val capturedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<JSONObject>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    @Volatile private var pendingId: String? = null
    @Volatile private var pendingContinuous: Boolean = false
    @Volatile private var pendingReadOnly: Boolean = true
    @Volatile private var pendingIncludeCookies: Boolean = false
    @Volatile private var pendingMaxFiles: Int = 4000
    @Volatile private var pendingMaxTotalMb: Int = 600
    @Volatile private var requestedAt: Long = 0L
    @Volatile private var active: Active? = null
    @Volatile var onControlSignal: ((action: String, id: String) -> Unit)? = null

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
                delay(1_000)
                val a = active
                if (a != null && !a.finalizing.get() && !a.cancelled.get()) {
                    val elapsed = ((System.currentTimeMillis() - a.startedAtMs) / 1000L).toInt().coerceAtLeast(0)
                    publishUi(a, elapsedSec = elapsed)
                }
                if (System.currentTimeMillis() % 5_000L < 1_000L) {
                    events.trySend(JSONObject().put("op", "tick"))
                }
            }
        }
    }

    fun isCapturing(): Boolean = active != null || pendingId != null

    fun newRequest(
        mode: String = "all",
        continuous: Boolean = false,
        readOnly: Boolean = true,
        includeCookies: Boolean = false,
        maxFiles: Int = 4000,
        maxTotalMb: Int = 600
    ): String? {
        if (active != null) {
            activityBridge.toast(context.getString(R.string.page_code_busy))
            return null
        }
        val id = UUID.randomUUID().toString()
        pendingId = id
        pendingContinuous = continuous
        pendingReadOnly = readOnly
        pendingIncludeCookies = includeCookies
        pendingMaxFiles = maxFiles.coerceIn(100, 10000)
        pendingMaxTotalMb = maxTotalMb.coerceIn(50, 2000)
        requestedAt = System.currentTimeMillis()
        _state.value = CaptureUiState(
            phase = CapturePhase.CAPTURING,
            sessionId = id,
            mode = mode,
            continuous = continuous,
            startedAtMs = requestedAt,
            statusMessage = if (continuous) "Starting live capture..." else "Collecting page resources..."
        )
        activityBridge.toast(
            if (continuous) "Live capture started (use HUD to Pause or Stop & Save)"
            else context.getString(R.string.page_code_collecting)
        )
        return id
    }

    fun pause() {
        val a = active ?: return
        if (a.finalizing.get() || a.cancelled.get()) return
        if (a.paused.compareAndSet(false, true)) {
            onControlSignal?.invoke("pause", a.id)
            publishUi(a, statusOverride = "Paused")
            Log.i(TAG, "FABLE-CAPTURE PAUSED id=${a.id} files=${a.files} bytes=${a.bytes}")
        }
    }

    fun resume() {
        val a = active ?: return
        if (a.finalizing.get() || a.cancelled.get()) return
        if (a.paused.compareAndSet(true, false)) {
            a.lastActivity = System.currentTimeMillis()
            onControlSignal?.invoke("resume", a.id)
            publishUi(a, statusOverride = "Capturing...")
            Log.i(TAG, "FABLE-CAPTURE RESUMED id=${a.id} files=${a.files} bytes=${a.bytes}")
        }
    }

    fun stopAndSave(partial: Boolean = true) {
        val a = active
        if (a == null) {
            val pid = pendingId
            if (pid != null) {
                pendingId = null
                _state.value = CaptureUiState(phase = CapturePhase.IDLE)
            }
            return
        }
        if (!a.stopRequested.compareAndSet(false, true)) return
        a.paused.set(true)
        onControlSignal?.invoke("stop", a.id)
        publishUi(a, phaseOverride = CapturePhase.FINALIZING, statusOverride = "Finalizing ZIP...")
        Log.i(TAG, "FABLE-CAPTURE STOP_REQUESTED id=${a.id} partial=$partial files=${a.files} bytes=${a.bytes}")
        events.trySend(JSONObject().put("op", "user_stop").put("id", a.id).put("partial", partial))
    }

    fun cancelAndDelete() {
        val a = active
        val pid = a?.id ?: pendingId
        if (a != null) {
            a.cancelled.set(true)
            a.stopRequested.set(true)
        }
        if (pid != null) onControlSignal?.invoke("cancel", pid)
        events.trySend(JSONObject().put("op", "user_cancel").put("id", pid.orEmpty()))
    }

    fun enqueueObservedUrls(urls: Collection<String>, pageUrl: String, userAgent: String) {
        val a = active ?: return
        if (a.paused.get() || a.stopRequested.get() || a.cancelled.get()) return
        val fresh = urls.filter { u ->
            (u.startsWith("http://", true) || u.startsWith("https://", true)) && a.capturedUrls.add(u)
        }
        if (fresh.isEmpty()) return
        val arr = JSONArray()
        fresh.take(500).forEach { arr.put(it) }
        events.trySend(JSONObject().apply {
            put("op", "fetch_batch")
            put("id", a.id)
            put("urls", arr)
            put("referer", pageUrl)
            put("userAgent", userAgent)
            put("observedResources", a.observedResources + fresh.size)
        })
    }

    fun onMessage(json: JSONObject) {
        events.trySend(json)
    }

    private suspend fun handle(json: JSONObject) {
        val op = json.optString("op")
        if (op == "tick") {
            val a = active
            val now = System.currentTimeMillis()
            if (a != null && !a.continuous && !a.paused.get() && !a.finalizing.get() && now - a.lastActivity > IDLE_TIMEOUT_MS) {
                Log.w(TAG, "Idle timeout on capture ${a.id}, finalizing partial archive")
                finish(a.id, partial = true)
            } else if (a == null && pendingId != null && now - requestedAt > START_TIMEOUT_MS) {
                pendingId = null
                _state.value = CaptureUiState(phase = CapturePhase.ERROR, statusMessage = "No response from page")
                activityBridge.toast(context.getString(R.string.page_code_no_response))
            }
            return
        }
        if (op == "user_cancel") {
            abort("Capture cancelled and deleted")
            return
        }
        val id = json.optString("id")
        if (id.isEmpty() || (id != pendingId && id != active?.id)) return
        when (op) {
            "begin" -> begin(id, json)
            "file" -> write(id, json)
            "fetch_batch" -> fetchBatch(id, json)
            "progress" -> progress(id, json)
            "phase_snapshot_ready" -> {
                val a = active?.takeIf { it.id == id } ?: return
                a.lastActivity = System.currentTimeMillis()
                publishUi(a, statusOverride = "Live capture active — press Stop & Save when ready")
            }
            "user_stop" -> finish(id, partial = json.optBoolean("partial", true))
            "end" -> {
                val a = active?.takeIf { it.id == id } ?: return
                if (!a.continuous || a.stopRequested.get()) {
                    finish(id, partial = a.stopRequested.get() || a.limitReachedReason != null)
                } else {
                    publishUi(a, statusOverride = "Live capture active — press Stop & Save when ready")
                }
            }
            "error" -> abort(context.getString(R.string.page_code_failed))
        }
    }

    private fun begin(id: String, json: JSONObject) {
        if (active != null) return
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter {
            (it.name.startsWith("page-") || it.name.startsWith("staging-")) &&
                System.currentTimeMillis() - it.lastModified() > 3_600_000L
        }?.forEach { it.deleteRecursively() }

        val stagingDir = File(dir, "staging-$id").apply { mkdirs() }
        val journalFile = File(stagingDir, "journal.jsonl")
        val continuous = json.optBoolean("continuous", pendingContinuous)
        val maxFiles = json.optInt("maxFiles", pendingMaxFiles).coerceIn(100, 10000)
        val maxTotalMb = json.optInt("maxTotalMb", pendingMaxTotalMb).coerceIn(50, 2000)
        val a = Active(
            id = id,
            exportDir = dir,
            stagingDir = stagingDir,
            journalFile = journalFile,
            title = json.optString("title"),
            page = json.optString("page"),
            incognito = json.optBoolean("incognito"),
            mode = json.optString("mode", "code"),
            engine = json.optString("engine", "GeckoView-144"),
            splitPartMb = json.optInt("splitPartMb", 0).coerceIn(0, 200),
            continuous = continuous,
            readOnlyZeroMutation = json.optBoolean("readOnly", pendingReadOnly),
            includeCookies = json.optBoolean("includeCookies", pendingIncludeCookies),
            maxFiles = maxFiles,
            maxTotalBytes = maxTotalMb.toLong() * 1024L * 1024L
        ).also {
            it.observedResources = json.optInt("observedResources", 0)
        }
        active = a
        publishUi(a, statusOverride = if (continuous) "Live capture running..." else "Capturing resources...")
        Log.i(TAG, "FABLE-CAPTURE STARTED id=$id mode=${a.mode} continuous=$continuous engine=${a.engine} zeroMutation=${a.readOnlyZeroMutation}")
    }

    private fun hasDiskHeadroom(dir: File): Boolean {
        return runCatching { dir.usableSpace >= MIN_FREE_DISK_BYTES }.getOrDefault(true)
    }

    private fun allocateUniqueNameLocked(a: Active, rawPath: String): String {
        val path = cleanPath(rawPath)
        var name = path
        var n = 1
        while (!a.names.add(name)) {
            val dot = path.lastIndexOf('.')
            name = if (dot > path.lastIndexOf('/')) path.substring(0, dot) + "_" + n + path.substring(dot) else path + "_" + n
            n++
        }
        return name
    }

    private fun appendJournalLocked(a: Active, rec: StagedRecord) {
        runCatching {
            val line = JSONObject().apply {
                put("path", rec.archivePath)
                put("size", rec.size)
                if (rec.url.isNotEmpty()) put("url", rec.url)
                if (rec.mime.isNotEmpty()) put("mime", rec.mime)
                if (rec.hashHex.isNotEmpty()) put("hash", rec.hashHex)
                put("ts", System.currentTimeMillis())
            }.toString() + "\n"
            FileOutputStream(a.journalFile, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun stageCompleteBytesLocked(
        a: Active,
        rawPath: String,
        bytes: ByteArray,
        sourceUrl: String = "",
        mime: String = ""
    ): Boolean {
        synchronized(a.writeLock) {
            if (a.cancelled.get() || a.finalizing.get()) return false
            if (a.files >= a.maxFiles) {
                a.limitReachedReason = "max_files_reached(${a.maxFiles})"
                return false
            }
            if (a.bytes + bytes.size > a.maxTotalBytes) {
                a.limitReachedReason = "max_total_bytes_reached(${a.maxTotalBytes})"
                return false
            }
            if (!hasDiskHeadroom(a.stagingDir)) {
                a.limitReachedReason = "low_disk_space"
                return false
            }
            closeOpenStreamLocked(a, commit = false)
            val archiveName = allocateUniqueNameLocked(a, rawPath)
            val idx = a.stagedRecords.size + 1
            val partFile = File(a.stagingDir, "f_${idx}.part")
            val finalFile = File(a.stagingDir, "f_${idx}.dat")
            FileOutputStream(partFile).use { fos ->
                if (bytes.isNotEmpty()) fos.write(bytes)
                fos.flush()
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            val hashHex = if (bytes.isNotEmpty()) {
                java.lang.Long.toHexString(NativeFastEngine.hash64(bytes))
            } else "0"
            val rec = StagedRecord(archiveName, finalFile, bytes.size.toLong(), sourceUrl, mime, hashHex)
            if (sourceUrl.isNotEmpty()) a.capturedUrls.add(sourceUrl.substringBefore('#'))
            a.stagedRecords.add(rec)
            a.files++
            a.bytes += bytes.size.toLong()
            a.currentResource = archiveName.substringAfterLast('/')
            a.lastActivity = System.currentTimeMillis()
            appendJournalLocked(a, rec)
        }
        publishUi(a)
        return true
    }

    private fun closeOpenStreamLocked(a: Active, commit: Boolean) {
        val out = a.openStreamOut ?: return
        val part = a.openStreamPartFile
        val fin = a.openStreamFinalFile
        val archiveName = a.openStreamPath
        val sz = a.openStreamBytes
        a.openStreamOut = null
        a.openStreamPartFile = null
        a.openStreamFinalFile = null
        a.openStreamPath = null
        a.openStreamBytes = 0L
        runCatching {
            out.flush()
            out.close()
        }
        if (commit && part != null && fin != null && archiveName != null && part.exists()) {
            if (!part.renameTo(fin)) {
                part.copyTo(fin, overwrite = true)
                part.delete()
            }
            val rec = StagedRecord(archiveName, fin, sz)
            a.stagedRecords.add(rec)
            a.files++
            a.bytes += sz
            a.currentResource = archiveName.substringAfterLast('/')
            appendJournalLocked(a, rec)
        } else {
            part?.delete()
        }
    }

    private fun write(id: String, json: JSONObject) {
        val a = active?.takeIf { it.id == id } ?: return
        if (a.paused.get() || a.stopRequested.get() || a.cancelled.get() || a.finalizing.get()) return
        val path = cleanPath(json.optString("path"))
        val seq = json.optInt("seq")
        val isLast = json.optBoolean("last")
        val data = json.optString("data")
        val srcUrl = json.optString("url")
        val mime = json.optString("mime")
        val decoded = if (data.isNotEmpty()) NativeFastEngine.decodeBase64(data) else ByteArray(0)
        if (seq == 0 && isLast) {
            stageCompleteBytesLocked(a, path, decoded, srcUrl, mime)
            return
        }
        synchronized(a.writeLock) {
            if (a.cancelled.get() || a.finalizing.get()) return
            a.lastActivity = System.currentTimeMillis()
            if (seq == 0 || a.openStreamOut == null) {
                closeOpenStreamLocked(a, commit = false)
                if (a.files >= a.maxFiles || a.bytes >= a.maxTotalBytes || !hasDiskHeadroom(a.stagingDir)) {
                    a.limitReachedReason = "limit_or_disk_reached"
                    return
                }
                val archiveName = allocateUniqueNameLocked(a, path)
                val idx = a.stagedRecords.size + 1
                val partFile = File(a.stagingDir, "f_${idx}.part")
                val finalFile = File(a.stagingDir, "f_${idx}.dat")
                a.openStreamPath = archiveName
                a.openStreamPartFile = partFile
                a.openStreamFinalFile = finalFile
                a.openStreamOut = BufferedOutputStream(FileOutputStream(partFile), 256 * 1024)
                a.openStreamBytes = 0L
            }
            if (decoded.isNotEmpty()) {
                a.openStreamOut?.write(decoded)
                a.openStreamBytes += decoded.size
            }
            if (isLast) {
                closeOpenStreamLocked(a, commit = true)
            }
        }
        if (isLast) publishUi(a)
    }

    private fun isPreCompressed(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in PRECOMPRESSED_EXTS
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
            val qHash = (NativeFastEngine.hash64(q.toByteArray(Charsets.UTF_8)) and 0xFFFFFFFFL).toString(16)
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
                mime.contains("woff") -> "woff"
                mime.contains("ttf") || mime.contains("truetype") -> "ttf"
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
        if (a.stopRequested.get() || a.cancelled.get() || a.finalizing.get()) return
        val arr = json.optJSONArray("urls") ?: return
        val totalUrls = arr.length()
        if (totalUrls <= 0) return
        val obs = json.optInt("observedResources", 0)
        if (obs > a.observedResources) a.observedResources = obs

        val ua = json.optString("userAgent").ifBlank { FableUnifiedEngine.defaultBlinkUserAgent }
        val referer = json.optString("referer").ifBlank { a.page }
        val maxFileBytes = json.optInt("maxFileMb", 120).coerceIn(10, 500).toLong() * 1024L * 1024L
        val maxTotalBytes = a.maxTotalBytes

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
                        while (a.paused.get() && !a.stopRequested.get() && !a.cancelled.get()) {
                            delay(250L)
                        }
                        if (active?.id != id || a.stopRequested.get() || a.cancelled.get() || a.finalizing.get()) break
                        a.lastActivity = System.currentTimeMillis()
                        if (a.bytes >= maxTotalBytes || a.files >= a.maxFiles) {
                            a.limitReachedReason = "archive_limit_reached"
                            if (a.failedReasons.size < 120) {
                                a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", "archive_total_limit_reached"))
                            }
                            continue
                        }
                        runCatching {
                            val cookieHeader = if (!a.incognito && a.includeCookies) {
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
                                    if (a.stopRequested.get() || a.cancelled.get()) {
                                        overLimit = true
                                        break
                                    }
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
                                    a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", "stopped_or_exceeded_max_file_size"))
                                }
                                return@runCatching
                            }
                            val rawBytes = baos.toByteArray()
                            val relPath = assetPathForUrl(targetUrl, cType)
                            if (stageCompleteBytesLocked(a, relPath, rawBytes, targetUrl, cType.orEmpty())) {
                                savedCount.incrementAndGet()
                            }
                        }.onFailure { err ->
                            if (a.failedReasons.size < 120) {
                                a.failedReasons.add(JSONObject().put("url", targetUrl).put("reason", err.javaClass.simpleName))
                            }
                        }
                        val d = doneCount.incrementAndGet()
                        val now = System.currentTimeMillis()
                        if (now - a.lastToast > 6_000 && !a.continuous) {
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
        val cur = json.optString("current")
        if (cur.isNotBlank()) a.currentResource = cur.substringAfterLast('/').take(64)
        publishUi(a)
        if (!a.continuous && a.lastActivity - a.lastToast > 8_000) {
            a.lastToast = a.lastActivity
            val done = json.optInt("done")
            val total = json.optInt("total").coerceAtLeast(done)
            activityBridge.toast(context.getString(R.string.page_code_progress, done, total, a.bytes / (1024 * 1024)))
        }
    }

    private fun finish(id: String, partial: Boolean) {
        val a = active?.takeIf { it.id == id } ?: return
        if (!a.finalizing.compareAndSet(false, true)) return
        publishUi(a, phaseOverride = CapturePhase.FINALIZING, statusOverride = "Packing ZIP archive...")

        val snapshotRecords: List<StagedRecord>
        synchronized(a.writeLock) {
            // Safely discard incomplete streaming chunk or commit finished ones
            closeOpenStreamLocked(a, commit = false)
            snapshotRecords = ArrayList(a.stagedRecords)
        }

        val failedArr = JSONArray()
        for (item in a.failedReasons) failedArr.put(item)

        val capabilities = JSONObject().apply {
            val isGecko = a.engine.contains("Gecko", ignoreCase = true)
            put("networkLayerByteCapture", if (isGecko) "webRequest.filterResponseData (all resource types)" else "passive URL observation + native fetch + CacheStorage")
            put("zeroPageCodeMutation", a.readOnlyZeroMutation)
            put("storageExport", "localStorage, sessionStorage, IndexedDB, CacheStorage, ServiceWorkers, CSSOM, Fonts, Canvases, HAR 1.2")
            put("cookiesIncluded", a.includeCookies && !a.incognito)
            put("sensitiveHeadersRedacted", !a.includeCookies)
            put("limitations", JSONArray(listOf(
                "Server-side code and unrequested remote files cannot be captured",
                "DRM-protected media and cross-origin iframe DOM are restricted by browser security",
                if (!isGecko) "Blink shouldInterceptRequest cannot read POST request bodies or tee in-flight responses directly" else "WebRTC media streams and opaque cross-origin bodies may be limited"
            )))
        }

        val manifest = JSONObject().apply {
            put("schema", "fable-page-archive-v3")
            put("status", if (partial || a.limitReachedReason != null) "partial" else "complete")
            put("capturedAt", java.time.Instant.now().toString())
            put("durationMs", (System.currentTimeMillis() - a.startedAtMs).coerceAtLeast(0L))
            put("page", a.page)
            put("title", a.title)
            put("mode", a.mode)
            put("continuous", a.continuous)
            put("engine", a.engine)
            put("incognito", a.incognito)
            put("zeroMutation", a.readOnlyZeroMutation)
            put("containsSensitiveData", a.includeCookies)
            put("limitReachedReason", a.limitReachedReason ?: JSONObject.NULL)
            put("observedResources", a.observedResources)
            put("savedFiles", snapshotRecords.size + 1)
            put("totalUncompressedBytes", a.bytes)
            put("nativeBatchRequested", a.nativeRequested)
            put("nativeBatchSaved", a.nativeSaved)
            put("nativeBatchFailedCount", failedArr.length())
            put("splitPartMb", a.splitPartMb)
            put("capabilities", capabilities)
            put("skippedOrFailedSample", failedArr)
        }

        val allParts = packStagedIntoZips(a, snapshotRecords, manifest.toString(2).toByteArray(Charsets.UTF_8))

        // Cleanup journaled staging directory immediately after packing
        runCatching { a.stagingDir.deleteRecursively() }
        active = null
        pendingId = null

        val basePrefix = archiveBasePrefix(a, partial = partial)
        var savedDisplayName = "$basePrefix.zip"
        if (allParts.size <= 1) {
            val single = allParts.firstOrNull()
            if (single != null) {
                downloader.saveLocalFile(single, savedDisplayName, "application/zip", a.incognito)
            }
        } else {
            val totalP = allParts.size
            savedDisplayName = "$basePrefix.part1of$totalP.zip (+${totalP - 1})"
            allParts.forEachIndexed { idx, partFile ->
                val partName = "$basePrefix.part${idx + 1}of$totalP.zip"
                downloader.saveLocalFile(partFile, partName, "application/zip", a.incognito)
            }
        }

        Log.i(
            TAG,
            "FABLE-CAPTURE SAVED id=${a.id} status=${if (partial) "partial" else "complete"} " +
                "files=${snapshotRecords.size + 1} bytes=${a.bytes} zeroMutation=${a.readOnlyZeroMutation} file=$savedDisplayName"
        )
        _state.value = CaptureUiState(
            phase = CapturePhase.DONE,
            sessionId = a.id,
            mode = a.mode,
            continuous = a.continuous,
            filesCaptured = snapshotRecords.size + 1,
            bytesCaptured = a.bytes,
            observedResources = a.observedResources,
            lastSavedFile = savedDisplayName,
            partial = partial,
            statusMessage = "Saved $savedDisplayName (${snapshotRecords.size + 1} files)"
        )
        scope.launch {
            delay(4_500L)
            if (_state.value.sessionId == a.id && _state.value.phase == CapturePhase.DONE) {
                _state.value = CaptureUiState(phase = CapturePhase.IDLE)
            }
        }
    }

    private fun packStagedIntoZips(
        a: Active,
        records: List<StagedRecord>,
        manifestBytes: ByteArray
    ): List<File> {
        val parts = ArrayList<File>()
        val splitLimit = if (a.splitPartMb > 0) a.splitPartMb.toLong() * 1024L * 1024L else 0L
        var partIdx = 1
        var currentFile = File(a.exportDir, "page-${a.id}-p$partIdx.zip")
        var zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(currentFile), 512 * 1024)).apply { setLevel(4) }
        val copyBuf = ByteArray(128 * 1024)

        fun rotateIfNeeded() {
            if (splitLimit <= 0L) return
            runCatching { zos.flush() }
            if (currentFile.length() >= splitLimit) {
                runCatching { zos.close() }
                parts.add(currentFile)
                partIdx++
                currentFile = File(a.exportDir, "page-${a.id}-p$partIdx.zip")
                zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(currentFile), 512 * 1024)).apply { setLevel(4) }
            }
        }

        for (rec in records) {
            if (!rec.diskFile.isFile) continue
            runCatching {
                val ze = ZipEntry(rec.archivePath)
                if (isPreCompressed(rec.archivePath) && rec.size <= 32L * 1024L * 1024L) {
                    val raw = rec.diskFile.readBytes()
                    ze.method = ZipEntry.STORED
                    ze.size = raw.size.toLong()
                    ze.compressedSize = raw.size.toLong()
                    ze.crc = NativeFastEngine.crc32(raw)
                    zos.putNextEntry(ze)
                    if (raw.isNotEmpty()) zos.write(raw)
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ze)
                    FileInputStream(rec.diskFile).use { fis ->
                        while (true) {
                            val n = fis.read(copyBuf)
                            if (n <= 0) break
                            zos.write(copyBuf, 0, n)
                        }
                    }
                    zos.closeEntry()
                }
                rotateIfNeeded()
            }
        }

        runCatching {
            val ze = ZipEntry("capture-manifest.json")
            zos.putNextEntry(ze)
            zos.write(manifestBytes)
            zos.closeEntry()
            if (a.journalFile.isFile) {
                val je = ZipEntry("capture-journal.jsonl")
                zos.putNextEntry(je)
                FileInputStream(a.journalFile).use { fis ->
                    while (true) {
                        val n = fis.read(copyBuf)
                        if (n <= 0) break
                        zos.write(copyBuf, 0, n)
                    }
                }
                zos.closeEntry()
            }
            zos.flush()
            zos.close()
        }
        if (currentFile.exists() && currentFile.length() > 0L) parts.add(currentFile)
        return parts
    }

    private fun abort(message: String) {
        val a = active
        active = null
        pendingId = null
        if (a != null) {
            synchronized(a.writeLock) {
                closeOpenStreamLocked(a, commit = false)
            }
            runCatching { a.stagingDir.deleteRecursively() }
        }
        _state.value = CaptureUiState(phase = CapturePhase.IDLE, statusMessage = message)
        activityBridge.toast(message)
        Log.i(TAG, "FABLE-CAPTURE ABORTED reason=$message")
    }

    private fun publishUi(
        a: Active,
        phaseOverride: CapturePhase? = null,
        elapsedSec: Int? = null,
        statusOverride: String? = null
    ) {
        val phase = phaseOverride ?: when {
            a.finalizing.get() -> CapturePhase.FINALIZING
            a.paused.get() -> CapturePhase.PAUSED
            else -> CapturePhase.CAPTURING
        }
        val elapsed = elapsedSec ?: ((System.currentTimeMillis() - a.startedAtMs) / 1000L).toInt().coerceAtLeast(0)
        _state.value = CaptureUiState(
            phase = phase,
            sessionId = a.id,
            mode = a.mode,
            continuous = a.continuous,
            filesCaptured = a.files,
            bytesCaptured = a.bytes,
            observedResources = a.observedResources,
            currentResource = a.currentResource,
            startedAtMs = a.startedAtMs,
            elapsedSec = elapsed,
            statusMessage = statusOverride ?: _state.value.statusMessage
        )
    }

    private fun archiveBasePrefix(a: Active, partial: Boolean): String {
        val host = runCatching { android.net.Uri.parse(a.page).host }.getOrNull().orEmpty().removePrefix("www.")
        val base = a.title.ifBlank { host }.ifBlank { "page" }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(55)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val tag = when {
            a.mode == "memory" && partial -> "snapshot-partial"
            a.mode == "memory" -> "snapshot"
            partial -> "code-partial"
            else -> "code"
        }
        return "$base-$tag-$stamp"
    }

    internal fun cleanPath(raw: String): String {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return parts.joinToString("/").ifEmpty { "file" }.take(400)
    }

    companion object {
        private const val TAG = "PageArchiver"
        private const val IDLE_TIMEOUT_MS = 180_000L
        private const val START_TIMEOUT_MS = 20_000L
        private const val MIN_FREE_DISK_BYTES = 64L * 1024L * 1024L
        private val PRECOMPRESSED_EXTS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "avif", "woff2",
            "mp3", "ogg", "mp4", "webm", "wasm", "glb", "ktx2", "zip", "gz", "xz", "br"
        )
    }
}
