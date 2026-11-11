package com.fable.browser.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fable.browser.R
import com.fable.browser.util.ActivityBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
        val file: File,
        val zip: ZipOutputStream,
        val title: String,
        val page: String,
        val incognito: Boolean
    ) {
        var entry: String? = null
        var lastActivity: Long = System.currentTimeMillis()
        var files: Int = 0
        var bytes: Long = 0
        var lastToast: Long = 0
        val names = HashSet<String>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<JSONObject>(Channel.UNLIMITED)

    @Volatile private var pendingId: String? = null
    @Volatile private var requestedAt: Long = 0
    private var active: Active? = null

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

    private fun handle(json: JSONObject) {
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
        val file = File(dir, "page-$id.zip")
        val zip = ZipOutputStream(BufferedOutputStream(FileOutputStream(file), 256 * 1024))
        zip.setLevel(6)
        active = Active(
            id = id,
            file = file,
            zip = zip,
            title = json.optString("title"),
            page = json.optString("page"),
            incognito = json.optBoolean("incognito")
        )
    }

    private fun write(id: String, json: JSONObject) {
        val a = active?.takeIf { it.id == id } ?: return
        a.lastActivity = System.currentTimeMillis()
        val path = cleanPath(json.optString("path"))
        val seq = json.optInt("seq")
        if (seq == 0 || a.entry == null) {
            if (a.entry != null) a.zip.closeEntry()
            var name = path
            var n = 1
            while (!a.names.add(name)) {
                val dot = path.lastIndexOf('.')
                name = if (dot > path.lastIndexOf('/')) path.substring(0, dot) + "_" + n + path.substring(dot) else path + "_" + n
                n++
            }
            a.zip.putNextEntry(ZipEntry(name))
            a.entry = name
        }
        val data = json.optString("data")
        if (data.isNotEmpty()) {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            a.zip.write(bytes)
            a.bytes += bytes.size
        }
        if (json.optBoolean("last")) {
            a.zip.closeEntry()
            a.entry = null
            a.files++
        }
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
        if (a.entry != null) a.zip.closeEntry()
        a.zip.close()
        active = null
        pendingId = null
        downloader.saveLocalFile(a.file, archiveName(a), "application/zip", a.incognito)
    }

    private fun abort(message: String) {
        val a = active
        active = null
        pendingId = null
        if (a != null) {
            runCatching { a.zip.close() }
            a.file.delete()
        }
        activityBridge.toast(message)
    }

    private fun archiveName(a: Active): String {
        val host = runCatching { android.net.Uri.parse(a.page).host }.getOrNull().orEmpty().removePrefix("www.")
        val base = a.title.ifBlank { host }.ifBlank { "page" }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(60)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        return "$base-code-$stamp.zip"
    }

    private fun cleanPath(raw: String): String {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return parts.joinToString("/").ifEmpty { "file" }.take(400)
    }

    companion object {
        private const val TAG = "PageArchiver"
        private const val IDLE_TIMEOUT_MS = 120_000L
        private const val START_TIMEOUT_MS = 15_000L
    }
}
