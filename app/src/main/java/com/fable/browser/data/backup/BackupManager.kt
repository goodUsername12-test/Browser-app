package com.fable.browser.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.CookieManager
import com.fable.browser.data.db.BookmarkDao
import com.fable.browser.data.db.DownloadDao
import com.fable.browser.data.db.HistoryDao
import com.fable.browser.data.db.SitePermissionDao
import com.fable.browser.data.model.Bookmark
import com.fable.browser.data.model.BookmarkFolder
import com.fable.browser.data.model.DownloadEntry
import com.fable.browser.data.model.DownloadStatus
import com.fable.browser.data.model.HistoryEntry
import com.fable.browser.data.model.PermissionDecision
import com.fable.browser.data.model.PermissionKind
import com.fable.browser.data.model.SavedTab
import com.fable.browser.data.model.SitePermission
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.PasswordStore
import com.fable.browser.data.repo.SecureStore
import com.fable.browser.engine.ProxyConfig
import com.fable.browser.engine.TabManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class BackupOptions(
    val settings: Boolean = true,
    val passwords: Boolean = true,
    val bookmarks: Boolean = true,
    val history: Boolean = true,
    val tabs: Boolean = true,
    val permissions: Boolean = true,
    val downloads: Boolean = true,
    val siteData: Boolean = true
)

data class BackupResult(
    val bookmarks: Int = 0,
    val history: Int = 0,
    val passwords: Int = 0,
    val tabs: Int = 0,
    val permissions: Int = 0,
    val downloads: Int = 0,
    val settings: Boolean = false,
    val cookies: Int = 0,
    val siteStorages: Int = 0,
    val siteDataFiles: Int = 0,
    val ceTables: Int = 0,
    val bytesWritten: Long = 0L,
    val needsRestart: Boolean = false
) {
    fun summary(): String {
        val items = buildList {
            if (bytesWritten > 0L) add(BackupManager.formatByteSize(bytesWritten))
            if (passwords > 0) add("$passwords password${if (passwords == 1) "" else "s"}")
            if (cookies > 0) add("$cookies cookie${if (cookies == 1) "" else "s"}")
            if (siteStorages > 0) add("$siteStorages site storage${if (siteStorages == 1) "" else "s"}")
            if (bookmarks > 0) add("$bookmarks bookmark${if (bookmarks == 1) "" else "s"}")
            if (history > 0) add("$history history")
            if (tabs > 0) add("$tabs tab${if (tabs == 1) "" else "s"}")
            if (permissions > 0) add("$permissions permission${if (permissions == 1) "" else "s"}")
            if (downloads > 0) add("$downloads download record${if (downloads == 1) "" else "s"}")
            if (ceTables > 0) add("$ceTables CE table${if (ceTables == 1) "" else "s"}")
            if (settings) add("settings")
            if (siteDataFiles > 0) add("$siteDataFiles profile file${if (siteDataFiles == 1) "" else "s"}")
        }
        return items.joinToString(", ").ifEmpty { "settings & empty profile" }
    }
}

data class PreparedBackup(
    val filePath: String,
    val bytesWritten: Long,
    val result: BackupResult
)

data class BackupHeaderProbe(
    val valid: Boolean,
    val sizeBytes: Long,
    val error: String? = null
)

class BackupException(message: String) : Exception(message)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val passwordStore: PasswordStore,
    private val secureStore: SecureStore,
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    private val downloadDao: DownloadDao,
    private val permissionDao: SitePermissionDao,
    private val tabManager: Lazy<TabManager>,
    private val proxyConfig: Lazy<ProxyConfig>
) {
    suspend fun prepareEncryptedBackupToCache(
        password: CharArray,
        options: BackupOptions
    ): PreparedBackup = withContext(Dispatchers.IO) {
        val cacheRoot = File(context.cacheDir, "fable_backup_stage").apply { mkdirs() }
        cacheRoot.listFiles()?.forEach { old -> runCatching { old.delete() } }
        val sealedFile = File(cacheRoot, "staged_${System.currentTimeMillis()}.fable")
        val res = backupToFile(sealedFile, password, options)
        PreparedBackup(
            filePath = sealedFile.absolutePath,
            bytesWritten = res.bytesWritten,
            result = res
        )
    }

    suspend fun commitPreparedBackupToUri(prepared: PreparedBackup, uri: Uri): BackupResult =
        withContext(Dispatchers.IO + NonCancellable) {
            val src = File(prepared.filePath)
            if (!src.isFile || src.length() < MIN_ENVELOPE_SIZE) {
                throw BackupException("Prepared backup is missing or empty; please create the backup again")
            }
            try {
                writeFileToUriAndVerify(src, uri)
                prepared.result.copy(bytesWritten = src.length())
            } catch (e: Throwable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                if (e is BackupException) throw e
                throw BackupException("Could not save .fable file: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { src.delete() }
            }
        }

    fun discardPreparedBackup(prepared: PreparedBackup?) {
        if (prepared == null) return
        runCatching { File(prepared.filePath).delete() }
    }

    suspend fun backup(uri: Uri, password: CharArray, options: BackupOptions): BackupResult =
        withContext(Dispatchers.IO) {
            val prepared = prepareEncryptedBackupToCache(password, options)
            commitPreparedBackupToUri(prepared, uri)
        }

    suspend fun backupToFile(outFile: File, password: CharArray, options: BackupOptions): BackupResult =
        withContext(Dispatchers.IO) {
            if (password.size < MIN_PASSWORD) throw BackupException("Password must be at least $MIN_PASSWORD characters")
            val root = JSONObject()
            root.put("format", FORMAT_VERSION)
            root.put("created", System.currentTimeMillis())
            root.put("app", context.packageName)
            var result = BackupResult()
            val knownOrigins = LinkedHashSet<String>()

            if (options.settings) {
                root.put("settings", settingsStore.exportJson())
                val secure = JSONObject()
                SECURE_NAMES.forEach { name -> secureStore.read(name)?.let { secure.put(name, it) } }
                root.put("secure", secure)
                val hybridPrefs = context.getSharedPreferences(HYBRID_PREFS, Context.MODE_PRIVATE).all
                val hybridJson = JSONObject()
                hybridPrefs.forEach { (k, v) -> if (v is Boolean) hybridJson.put(k, v) }
                root.put("hybridModes", hybridJson)
                result = result.copy(settings = true)
            }
            runCatching {
                val curSettings = settingsStore.settings.first()
                val tablesRaw = curSettings.memoryEngineSavedTablesJson
                val recipesRaw = curSettings.memoryEngineRecipesJson
                val tArr = runCatching { JSONArray(tablesRaw) }.getOrDefault(JSONArray())
                val rArr = runCatching { JSONArray(recipesRaw) }.getOrDefault(JSONArray())
                if (tArr.length() > 0) root.put("ceTables", tArr)
                if (rArr.length() > 0) root.put("ceRecipes", rArr)
                result = result.copy(ceTables = tArr.length())
            }
            if (options.passwords) {
                val arr = passwordStore.exportJson()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("origin")?.let { addOriginFromUrl(knownOrigins, it) }
                }
                root.put("passwords", arr)
                result = result.copy(passwords = arr.length())
            }
            if (options.bookmarks) {
                val folders = JSONArray()
                bookmarkDao.allFoldersOnce().forEach { f ->
                    folders.put(
                        JSONObject().put("id", f.id).put("name", f.name)
                            .put("parentId", f.parentId ?: JSONObject.NULL).put("createdAt", f.createdAt)
                    )
                }
                val marks = JSONArray()
                bookmarkDao.allBookmarksOnce().forEach { b ->
                    addOriginFromUrl(knownOrigins, b.url)
                    marks.put(
                        JSONObject().put("title", b.title).put("url", b.url)
                            .put("folderId", b.folderId ?: JSONObject.NULL).put("createdAt", b.createdAt)
                    )
                }
                root.put("bookmarkFolders", folders)
                root.put("bookmarks", marks)
                result = result.copy(bookmarks = marks.length())
            }
            if (options.history) {
                val arr = JSONArray()
                historyDao.allOnce().forEach { h ->
                    addOriginFromUrl(knownOrigins, h.url)
                    arr.put(JSONObject().put("title", h.title).put("url", h.url).put("visitedAt", h.visitedAt))
                }
                root.put("history", arr)
                result = result.copy(history = arr.length())
            }
            if (options.tabs) {
                val tabs = withContext(Dispatchers.Main) { tabManager.get().exportNormalTabs() }
                val arr = JSONArray()
                tabs.forEach { t ->
                    addOriginFromUrl(knownOrigins, t.url)
                    arr.put(
                        JSONObject().put("url", t.url).put("title", t.title)
                            .put("position", t.position).put("active", t.isActive)
                    )
                }
                root.put("tabs", arr)
                result = result.copy(tabs = arr.length())
            }
            if (options.permissions) {
                val arr = JSONArray()
                permissionDao.allOnce().forEach { p ->
                    addOriginFromUrl(knownOrigins, p.origin)
                    arr.put(
                        JSONObject().put("origin", p.origin).put("permission", p.permission.name)
                            .put("decision", p.decision.name).put("updatedAt", p.updatedAt)
                    )
                }
                root.put("permissions", arr)
                result = result.copy(permissions = arr.length())
            }
            if (options.downloads) {
                val arr = JSONArray()
                downloadDao.allOnce().filter { !it.fromIncognito }.forEach { d ->
                    arr.put(
                        JSONObject().put("fileName", d.fileName).put("url", d.url).put("filePath", d.filePath)
                            .put("mimeType", d.mimeType ?: JSONObject.NULL).put("totalBytes", d.totalBytes)
                            .put("downloadedBytes", d.downloadedBytes).put("status", d.status.name)
                            .put("startedAt", d.startedAt)
                    )
                }
                root.put("downloads", arr)
                result = result.copy(downloads = arr.length())
            }

            var cookieCount = 0
            var siteStorageCount = 0
            if (options.siteData) {
                runCatching { CookieManager.getInstance().flush() }
                val geckoCookies = runCatching { proxyConfig.get().exportAllGeckoCookies() }.getOrDefault(JSONArray())
                root.put("geckoCookies", geckoCookies)
                for (i in 0 until geckoCookies.length()) {
                    val c = geckoCookies.optJSONObject(i) ?: continue
                    val dom = c.optString("domain").trimStart('.')
                    if (dom.isNotBlank()) knownOrigins.add("https://$dom")
                }

                val gameVault = context.getSharedPreferences(GAME_VAULT_PREFS, Context.MODE_PRIVATE).all
                val gameVaultJson = JSONObject()
                gameVault.forEach { (k, v) ->
                    if (v is String && k.startsWith("ls_")) {
                        gameVaultJson.put(k, v)
                        knownOrigins.add("https://${k.removePrefix("ls_")}")
                    }
                }
                root.put("gameAccountVault", gameVaultJson)

                val siteStorageMap = JSONObject()
                val savedSitePrefs = context.getSharedPreferences(BACKUP_SITE_STORAGE_PREFS, Context.MODE_PRIVATE).all
                savedSitePrefs.forEach { (k, v) ->
                    if (k.startsWith("site_") && v is String) {
                        val orig = k.removePrefix("site_")
                        knownOrigins.add(orig)
                        runCatching { siteStorageMap.put(orig, JSONObject(v)) }
                    }
                }
                val liveGeckoStorage = runCatching { proxyConfig.get().exportOpenGeckoStorage() }.getOrDefault(JSONObject())
                mergeSiteStorageMaps(siteStorageMap, liveGeckoStorage)
                val liveBlinkStorage = runCatching { tabManager.get().exportOpenBlinkSiteStorage() }.getOrDefault(JSONObject())
                mergeSiteStorageMaps(siteStorageMap, liveBlinkStorage)
                gameVault.forEach { (k, v) ->
                    if (k.startsWith("ls_") && v is String) {
                        val orig = "https://${k.removePrefix("ls_")}"
                        runCatching {
                            val lsObj = JSONObject(v)
                            val existing = siteStorageMap.optJSONObject(orig) ?: JSONObject()
                            val mergedLs = existing.optJSONObject("ls") ?: JSONObject()
                            lsObj.keys().forEach { lk -> if (!mergedLs.has(lk)) mergedLs.put(lk, lsObj.optString(lk)) }
                            existing.put("ls", mergedLs)
                            if (!existing.has("ss")) existing.put("ss", JSONObject())
                            siteStorageMap.put(orig, existing)
                        }
                    }
                }
                root.put("siteStorage", siteStorageMap)
                siteStorageCount = siteStorageMap.length()

                val blinkCookies = JSONObject()
                val cm = runCatching { CookieManager.getInstance() }.getOrNull()
                if (cm != null) {
                    for (origin in knownOrigins.take(250)) {
                        val hdr = runCatching { cm.getCookie(origin) }.getOrNull()?.trim()
                        if (!hdr.isNullOrEmpty()) {
                            blinkCookies.put(origin, hdr)
                            cookieCount += hdr.split(';').count { it.contains('=') }
                        }
                    }
                }
                root.put("blinkCookies", blinkCookies)
                cookieCount = maxOf(cookieCount, geckoCookies.length())
                result = result.copy(cookies = cookieCount, siteStorages = siteStorageCount)
            }

            val tmpZip = File.createTempFile("fable_bk_zip_", ".tmp", context.cacheDir)
            val tmpSealed = File.createTempFile("fable_bk_enc_", ".tmp", context.cacheDir)
            var siteFiles = 0
            try {
                val jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
                val jsonDigest = sha256Hex(jsonBytes)
                val addedEntries = HashSet<String>()
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpZip), STREAM_BUF)).use { zip ->
                    zip.putNextEntry(ZipEntry(DATA_ENTRY))
                    zip.write(jsonBytes)
                    zip.closeEntry()
                    addedEntries.add(DATA_ENTRY)

                    zip.putNextEntry(ZipEntry(CHECKSUM_ENTRY))
                    zip.write(jsonDigest.toByteArray(Charsets.US_ASCII))
                    zip.closeEntry()
                    addedEntries.add(CHECKSUM_ENTRY)

                    if (options.siteData) {
                        var budget = SITE_DATA_LIMIT
                        val profile = profileDir()
                        if (profile != null) {
                            siteDataFiles(profile).forEach { file ->
                                val len = file.length()
                                if (len <= 0L || len > budget) return@forEach
                                val entryName = PROFILE_PREFIX + file.relativeTo(profile).invariantSeparatorsPath
                                if (!addedEntries.add(entryName)) return@forEach
                                val copied = streamFileIntoZip(zip, entryName, file)
                                if (copied > 0L) {
                                    budget -= copied
                                    siteFiles++
                                }
                            }
                        }
                        val wvDir = webViewDefaultDir(context)
                        if (wvDir != null) {
                            webViewDataFiles(wvDir).forEach { file ->
                                val len = file.length()
                                if (len <= 0L || len > budget) return@forEach
                                val entryName = WEBVIEW_PREFIX + file.relativeTo(wvDir).invariantSeparatorsPath
                                if (!addedEntries.add(entryName)) return@forEach
                                val copied = streamFileIntoZip(zip, entryName, file)
                                if (copied > 0L) {
                                    budget -= copied
                                    siteFiles++
                                }
                            }
                        }
                    }
                    zip.flush()
                }
                if (!tmpZip.isFile || tmpZip.length() < 22L) {
                    throw BackupException("Backup archive assembly produced an empty file")
                }

                sealFileToFile(tmpZip, tmpSealed, password)
                val sealedSize = tmpSealed.length()
                if (sealedSize < MIN_ENVELOPE_SIZE) {
                    throw BackupException("Encrypted backup verification failed (size=$sealedSize)")
                }
                val probe = probeFileHeader(tmpSealed)
                if (!probe.valid) {
                    throw BackupException(probe.error ?: "Encrypted backup header check failed")
                }

                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { dst ->
                    FileInputStream(tmpSealed).use { src -> copyStream(src, dst) }
                    dst.flush()
                    runCatching { dst.fd.sync() }
                }
                if (!outFile.isFile || outFile.length() != sealedSize) {
                    runCatching { outFile.delete() }
                    throw BackupException("Failed to finalize backup file on disk")
                }
                result.copy(siteDataFiles = siteFiles, bytesWritten = sealedSize)
            } finally {
                runCatching { tmpZip.delete() }
                runCatching { tmpSealed.delete() }
            }
        }

    suspend fun inspectBackupUri(uri: Uri): BackupHeaderProbe = withContext(Dispatchers.IO) {
        val stream = openReadableStream(uri)
            ?: return@withContext BackupHeaderProbe(false, 0L, "Could not open the selected file")
        stream.use { ins ->
            val header = ByteArray(HEADER_LEN)
            var read = 0
            while (read < HEADER_LEN) {
                val n = ins.read(header, read, HEADER_LEN - read)
                if (n < 0) break
                read += n
            }
            var total = read.toLong()
            val buf = ByteArray(8192)
            while (total < MIN_ENVELOPE_SIZE) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
            }
            validateHeaderBytes(header, read, total)
        }
    }

    suspend fun restore(uri: Uri, password: CharArray): BackupResult = withContext(Dispatchers.IO) {
        val tmpEnc = File.createTempFile("fable_restore_in_", ".fable", context.cacheDir)
        try {
            val ins = openReadableStream(uri)
                ?: throw BackupException("Could not open the selected backup file")
            var copied = 0L
            ins.use { src ->
                BufferedOutputStream(FileOutputStream(tmpEnc), STREAM_BUF).use { dst ->
                    copied = copyStream(src, dst)
                    dst.flush()
                }
            }
            if (copied <= 0L) {
                throw BackupException("Selected .fable file is empty (0 bytes) and contains no backup data")
            }
            restoreFromFile(tmpEnc, password)
        } finally {
            runCatching { tmpEnc.delete() }
        }
    }

    suspend fun restoreFromFile(file: File, password: CharArray): BackupResult = withContext(Dispatchers.IO) {
        val probe = probeFileHeader(file)
        if (!probe.valid) throw BackupException(probe.error ?: "This file is not a valid Fable backup")

        val tmpZip = File.createTempFile("fable_restore_zip_", ".tmp", context.cacheDir)
        try {
            openFileToFile(file, tmpZip, password)
            var root: JSONObject? = null
            var rawJsonBytes: ByteArray? = null
            var expectedSha256: String? = null
            val staging = File(context.filesDir, STAGING_DIR)
            var stagedCount = 0
            var stagingCleared = false

            ZipInputStream(BufferedInputStream(FileInputStream(tmpZip), STREAM_BUF)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.contains("..")) continue
                    when {
                        name == DATA_ENTRY -> {
                            val bytes = zip.readBytes()
                            rawJsonBytes = bytes
                            root = JSONObject(String(bytes, Charsets.UTF_8))
                        }
                        name == CHECKSUM_ENTRY -> {
                            expectedSha256 = String(zip.readBytes(), Charsets.US_ASCII).trim()
                        }
                        name.startsWith(PROFILE_PREFIX) || name.startsWith(WEBVIEW_PREFIX) -> {
                            if (!stagingCleared) {
                                staging.deleteRecursively()
                                stagingCleared = true
                            }
                            val sub = if (name.startsWith(PROFILE_PREFIX)) PROFILE_SUBDIR else WEBVIEW_SUBDIR
                            val rel = if (name.startsWith(PROFILE_PREFIX)) {
                                name.removePrefix(PROFILE_PREFIX)
                            } else {
                                name.removePrefix(WEBVIEW_PREFIX)
                            }
                            val target = File(File(staging, sub), rel)
                            val canonStage = staging.canonicalPath
                            target.parentFile?.mkdirs()
                            if (target.canonicalPath.startsWith(canonStage)) {
                                BufferedOutputStream(FileOutputStream(target), STREAM_BUF).use { out ->
                                    copyStream(zip, out)
                                    out.flush()
                                }
                                stagedCount++
                            }
                        }
                    }
                }
            }
            if (!expectedSha256.isNullOrBlank() && rawJsonBytes != null) {
                val actual = sha256Hex(rawJsonBytes!!)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw BackupException("Backup checksum mismatch — file may be corrupted")
                }
            }
            val data = root ?: throw BackupException("This file is not a valid Fable backup (missing fable.json)")
            var result = BackupResult(bytesWritten = file.length())

            data.optJSONObject("settings")?.let {
                settingsStore.importJson(it)
                result = result.copy(settings = true)
            }
            data.optJSONArray("ceTables")?.let { arr ->
                if (arr.length() > 0) {
                    runCatching { settingsStore.setMemoryEngineSavedTablesJson(arr.toString()) }
                    result = result.copy(ceTables = arr.length())
                }
            }
            data.optJSONArray("ceRecipes")?.let { arr ->
                if (arr.length() > 0) {
                    runCatching { settingsStore.setMemoryEngineRecipesJson(arr.toString()) }
                }
            }
            data.optJSONObject("secure")?.let { secure ->
                secure.keys().forEach { name -> if (name in SECURE_NAMES) secureStore.write(name, secure.getString(name)) }
                if (secure.length() > 0) result = result.copy(needsRestart = true)
            }
            data.optJSONObject("hybridModes")?.let { hm ->
                val ed = context.getSharedPreferences(HYBRID_PREFS, Context.MODE_PRIVATE).edit()
                hm.keys().forEach { k -> ed.putBoolean(k, hm.optBoolean(k)) }
                ed.commit()
            }
            data.optJSONArray("passwords")?.let {
                result = result.copy(passwords = passwordStore.importJson(it))
            }
            data.optJSONArray("bookmarks")?.let { marks ->
                result = result.copy(
                    bookmarks = importBookmarks(data.optJSONArray("bookmarkFolders") ?: JSONArray(), marks)
                )
            }
            data.optJSONArray("history")?.let { arr ->
                var n = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val at = o.optLong("visitedAt", System.currentTimeMillis())
                    if (historyDao.hasVisit(url, at)) continue
                    historyDao.insert(HistoryEntry(title = o.optString("title"), url = url, visitedAt = at))
                    n++
                }
                result = result.copy(history = n)
            }
            data.optJSONArray("permissions")?.let { arr ->
                var n = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = runCatching { PermissionKind.valueOf(o.getString("permission")) }.getOrNull() ?: continue
                    val decision = runCatching { PermissionDecision.valueOf(o.getString("decision")) }.getOrNull() ?: continue
                    val origin = o.optString("origin").takeIf { it.isNotBlank() } ?: continue
                    val current = permissionDao.decisionFor(origin, kind)
                    permissionDao.upsert(
                        SitePermission(
                            id = current?.id ?: 0,
                            origin = origin,
                            permission = kind,
                            decision = decision,
                            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                    n++
                }
                result = result.copy(permissions = n)
            }
            data.optJSONArray("downloads")?.let { arr ->
                var n = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    val at = o.optLong("startedAt", System.currentTimeMillis())
                    if (downloadDao.hasEntry(url, at)) continue
                    val status = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.FAILED)
                    downloadDao.insert(
                        DownloadEntry(
                            fileName = o.optString("fileName"),
                            url = url,
                            filePath = o.optString("filePath"),
                            mimeType = if (o.isNull("mimeType")) null else o.optString("mimeType"),
                            totalBytes = o.optLong("totalBytes", -1),
                            downloadedBytes = o.optLong("downloadedBytes", 0),
                            status = if (status == DownloadStatus.IN_PROGRESS) DownloadStatus.FAILED else status,
                            startedAt = at,
                            fromIncognito = false
                        )
                    )
                    n++
                }
                result = result.copy(downloads = n)
            }

            var restoredCookies = 0
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            cm?.setAcceptCookie(true)

            data.optJSONArray("geckoCookies")?.let { arr ->
                val gCount = runCatching { proxyConfig.get().restoreGeckoCookies(arr) }.getOrDefault(0)
                restoredCookies += gCount
                if (cm != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val dom = c.optString("domain").trimStart('.')
                        val name = c.optString("name")
                        val value = c.optString("value")
                        val path = c.optString("path", "/")
                        val secure = c.optBoolean("secure", false)
                        if (dom.isNotBlank() && name.isNotBlank()) {
                            val url = "${if (secure) "https" else "http"}://$dom$path"
                            val sb = StringBuilder("$name=$value; Path=$path")
                            if (secure) sb.append("; Secure")
                            runCatching { cm.setCookie(url, sb.toString()) }
                        }
                    }
                }
            }
            data.optJSONObject("blinkCookies")?.let { bc ->
                val keys = bc.keys()
                while (keys.hasNext()) {
                    val origin = keys.next()
                    val header = bc.optString(origin)
                    if (origin.isBlank() || header.isBlank()) continue
                    val pairs = header.split(';')
                    for (pair in pairs) {
                        val clean = pair.trim()
                        if (!clean.contains('=')) continue
                        if (cm != null) {
                            runCatching { cm.setCookie(origin, "$clean; Path=/") }
                            restoredCookies++
                        }
                    }
                    runCatching { proxyConfig.get().syncBlinkCookiesToGecko(origin) }
                }
            }
            runCatching { cm?.flush() }

            val restoredSiteStorage = JSONObject()
            data.optJSONObject("gameAccountVault")?.let { gv ->
                val ed = context.getSharedPreferences(GAME_VAULT_PREFS, Context.MODE_PRIVATE).edit()
                gv.keys().forEach { k ->
                    val v = gv.optString(k)
                    if (k.startsWith("ls_") && v.isNotBlank()) {
                        ed.putString(k, v)
                        runCatching {
                            val orig = "https://${k.removePrefix("ls_")}"
                            val entry = JSONObject().put("ls", JSONObject(v)).put("ss", JSONObject())
                            restoredSiteStorage.put(orig, entry)
                        }
                    }
                }
                ed.commit()
            }
            data.optJSONObject("siteStorage")?.let { ssMap ->
                mergeSiteStorageMaps(restoredSiteStorage, ssMap)
            }
            if (restoredSiteStorage.length() > 0) {
                val ed = context.getSharedPreferences(BACKUP_SITE_STORAGE_PREFS, Context.MODE_PRIVATE).edit()
                val gvEd = context.getSharedPreferences(GAME_VAULT_PREFS, Context.MODE_PRIVATE).edit()
                restoredSiteStorage.keys().forEach { orig ->
                    val payload = restoredSiteStorage.optJSONObject(orig) ?: return@forEach
                    ed.putString("site_$orig", payload.toString())
                    ed.putBoolean("pending_$orig", true)
                    val ls = payload.optJSONObject("ls")
                    val host = runCatching { Uri.parse(orig).host.orEmpty().lowercase() }.getOrDefault("")
                    if (ls != null && ls.length() > 0 && host.isNotBlank()) {
                        gvEd.putString("ls_${host.removePrefix("www.")}", ls.toString())
                    }
                }
                ed.commit()
                gvEd.commit()
                runCatching { proxyConfig.get().pushPendingBackupSiteStorage(restoredSiteStorage) }
                withContext(Dispatchers.Main) {
                    runCatching { tabManager.get().applyRestoredSiteStorageToOpenBlinkTabs(restoredSiteStorage) }
                }
            }
            result = result.copy(cookies = restoredCookies, siteStorages = restoredSiteStorage.length())

            data.optJSONArray("tabs")?.let { arr ->
                val tabs = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    SavedTab(
                        url = o.optString("url"),
                        title = o.optString("title"),
                        position = o.optInt("position", i),
                        isActive = o.optBoolean("active", false)
                    )
                }
                val n = withContext(Dispatchers.Main) { tabManager.get().importTabs(tabs) }
                result = result.copy(tabs = n)
            }

            if (stagedCount > 0) {
                File(staging, READY_MARK).writeText(System.currentTimeMillis().toString())
                result = result.copy(siteDataFiles = stagedCount, needsRestart = true)
            }
            result
        } finally {
            runCatching { tmpZip.delete() }
        }
    }

    private fun openReadableStream(uri: Uri): InputStream? {
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()?.let { return it }
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return object : FileInputStream(pfd.fileDescriptor) {
            override fun close() {
                runCatching { super.close() }
                runCatching { pfd.close() }
            }
        }
    }

    private fun writeFileToUriAndVerify(srcFile: File, uri: Uri) {
        val expectedBytes = srcFile.length()
        if (expectedBytes < MIN_ENVELOPE_SIZE) {
            throw BackupException("Internal error: prepared backup is empty")
        }
        var written = false
        var lastErr: Throwable? = null

        for (mode in listOf("wt", "w")) {
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, mode) }
                .onFailure { lastErr = it }
                .getOrNull()
            if (pfd != null) {
                try {
                    pfd.use { fd ->
                        FileOutputStream(fd.fileDescriptor).use { rawOut ->
                            val out = BufferedOutputStream(rawOut, STREAM_BUF)
                            FileInputStream(srcFile).use { ins -> copyStream(ins, out) }
                            out.flush()
                            rawOut.flush()
                            runCatching { fd.fileDescriptor.sync() }
                        }
                    }
                    written = true
                    break
                } catch (e: Throwable) {
                    lastErr = e
                }
            }
        }

        if (!written) {
            for (mode in listOf("wt", "w", null)) {
                val outStream = runCatching {
                    if (mode != null) context.contentResolver.openOutputStream(uri, mode)
                    else context.contentResolver.openOutputStream(uri)
                }.onFailure { lastErr = it }.getOrNull()
                if (outStream != null) {
                    try {
                        outStream.use { rawOut ->
                            val out = BufferedOutputStream(rawOut, STREAM_BUF)
                            FileInputStream(srcFile).use { ins -> copyStream(ins, out) }
                            out.flush()
                            rawOut.flush()
                        }
                        written = true
                        break
                    } catch (e: Throwable) {
                        lastErr = e
                    }
                }
            }
        }

        if (!written) {
            throw BackupException(
                "Could not open destination file for writing (${lastErr?.message ?: "storage provider rejected write"})"
            )
        }

        // Mandatory read-back verification so a 0-byte or truncated file can NEVER report success!
        val verifyStream = openReadableStream(uri)
            ?: throw BackupException("Backup file could not be verified after saving")
        var verifiedBytes = 0L
        val head = ByteArray(MAGIC.size)
        var headRead = 0
        verifyStream.use { ins ->
            val buf = ByteArray(STREAM_BUF)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (headRead < MAGIC.size) {
                    val take = minOf(MAGIC.size - headRead, n)
                    System.arraycopy(buf, 0, head, headRead, take)
                    headRead += take
                }
                verifiedBytes += n
            }
        }
        if (verifiedBytes == 0L) {
            throw BackupException("Destination provider saved 0 bytes — try saving to Downloads or Internal Storage")
        }
        if (verifiedBytes != expectedBytes) {
            throw BackupException("Saved file is truncated ($verifiedBytes / $expectedBytes bytes)")
        }
        if (headRead < MAGIC.size || !head.contentEquals(MAGIC)) {
            throw BackupException("Saved file header verification failed")
        }
    }

    private fun streamFileIntoZip(zip: ZipOutputStream, entryName: String, file: File): Long {
        return runCatching {
            val bytes = FileInputStream(file).use { ins ->
                val tmp = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    tmp.write(buf, 0, n)
                    total += n
                    if (total > MAX_SINGLE_PROFILE_FILE) return@runCatching 0L
                }
                tmp.toByteArray()
            }
            if (bytes.isEmpty()) return@runCatching 0L
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
            bytes.size.toLong()
        }.getOrDefault(0L)
    }

    private suspend fun importBookmarks(folders: JSONArray, marks: JSONArray): Int {
        val existingFolders = bookmarkDao.allFoldersOnce()
        val idMap = HashMap<Long, Long>()
        val parents = HashMap<Long, Long?>()
        for (i in 0 until folders.length()) {
            val o = folders.optJSONObject(i) ?: continue
            val oldId = o.optLong("id")
            val name = o.optString("name").ifBlank { "Imported" }
            val parent = if (o.isNull("parentId")) null else o.optLong("parentId")
            parents[oldId] = parent
            val match = existingFolders.firstOrNull { it.name == name }
            idMap[oldId] = match?.id ?: bookmarkDao.insertFolder(
                BookmarkFolder(name = name, parentId = null, createdAt = o.optLong("createdAt", System.currentTimeMillis()))
            )
        }
        val refreshed = bookmarkDao.allFoldersOnce().associateBy { it.id }
        parents.forEach { (oldId, oldParent) ->
            val newId = idMap[oldId] ?: return@forEach
            val newParent = oldParent?.let { idMap[it] } ?: return@forEach
            val folder = refreshed[newId] ?: return@forEach
            if (folder.parentId == null && newParent != newId) bookmarkDao.updateFolder(folder.copy(parentId = newParent))
        }
        var n = 0
        for (i in 0 until marks.length()) {
            val o = marks.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (bookmarkDao.byUrl(url) != null) continue
            val folder = if (o.isNull("folderId")) null else idMap[o.optLong("folderId")]
            bookmarkDao.insert(
                Bookmark(
                    title = o.optString("title"),
                    url = url,
                    folderId = folder,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
            n++
        }
        return n
    }

    private fun profileDir(): File? = Companion.profileDir(context)

    private fun siteDataFiles(profile: File): List<File> {
        val out = mutableListOf<File>()
        SITE_FILES.forEach { name ->
            File(profile, name).takeIf { it.isFile }?.let(out::add)
            File(profile, "$name-wal").takeIf { it.isFile }?.let(out::add)
        }
        File(profile, "storage/default").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile && !it.name.endsWith("-shm") && !it.name.endsWith(".lock") }
            ?.sortedBy { it.length() }
            ?.forEach(out::add)
        return out
    }

    private fun webViewDataFiles(wvDir: File): List<File> {
        val out = mutableListOf<File>()
        WEBVIEW_FILES.forEach { name ->
            File(wvDir, name).takeIf { it.isFile }?.let(out::add)
            File(wvDir, "$name-wal").takeIf { it.isFile }?.let(out::add)
            File(wvDir, "$name-journal").takeIf { it.isFile }?.let(out::add)
        }
        listOf("Local Storage/leveldb", "Session Storage").forEach { sub ->
            File(wvDir, sub).takeIf { it.isDirectory }?.walkTopDown()
                ?.filter { it.isFile && !it.name.equals("LOCK", ignoreCase = true) && !it.name.endsWith(".lock") }
                ?.sortedBy { it.length() }
                ?.forEach(out::add)
        }
        return out
    }

    companion object {
        const val MIN_PASSWORD = 4
        private const val FORMAT_VERSION = 3
        private const val ITERATIONS = 150_000
        private const val STREAM_BUF = 64 * 1024
        private const val DATA_ENTRY = "fable.json"
        private const val CHECKSUM_ENTRY = "fable.sha256"
        private const val PROFILE_PREFIX = "profile/"
        private const val WEBVIEW_PREFIX = "webview/"
        private const val PROFILE_SUBDIR = "profile"
        private const val WEBVIEW_SUBDIR = "webview"
        private const val STAGING_DIR = "fable_restore"
        private const val READY_MARK = ".ready"
        private const val SITE_DATA_LIMIT = 48L * 1024 * 1024
        private const val MAX_SINGLE_PROFILE_FILE = 16L * 1024 * 1024
        private const val HYBRID_PREFS = "fable_hybrid_tab_state"
        private const val GAME_VAULT_PREFS = "fable_game_account_vault"
        private const val BACKUP_SITE_STORAGE_PREFS = "fable_backup_site_storage"
        private val MAGIC = "FABLEBK1".toByteArray(Charsets.US_ASCII)
        private val HEADER_LEN = MAGIC.size + 4 + 16 + 12
        private val MIN_ENVELOPE_SIZE = HEADER_LEN + 16
        private val SECURE_NAMES = setOf("super_proxy", "super_identity", "fable_proxy_profiles")
        private val SITE_FILES = listOf(
            "cookies.sqlite",
            "webappsstore.sqlite",
            "permissions.sqlite",
            "content-prefs.sqlite",
            "cert_override.txt",
            "siteSecurityServiceState.bin"
        )
        private val WEBVIEW_FILES = listOf("Cookies", "Web Data")

        fun formatByteSize(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(dig.size * 2)
            for (b in dig) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
            return sb.toString()
        }

        private fun copyStream(ins: InputStream, out: OutputStream): Long {
            val buf = ByteArray(STREAM_BUF)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return total
        }

        fun probeFileHeader(file: File): BackupHeaderProbe {
            if (!file.isFile) return BackupHeaderProbe(false, 0L, "Backup file does not exist")
            val len = file.length()
            if (len == 0L) {
                return BackupHeaderProbe(false, 0L, "This .fable file is empty (0 bytes) and contains no backup data")
            }
            val header = ByteArray(HEADER_LEN)
            val read = FileInputStream(file).use { ins ->
                var r = 0
                while (r < HEADER_LEN) {
                    val n = ins.read(header, r, HEADER_LEN - r)
                    if (n < 0) break
                    r += n
                }
                r
            }
            return validateHeaderBytes(header, read, len)
        }

        internal fun validateHeaderBytes(header: ByteArray, headerRead: Int, totalSize: Long): BackupHeaderProbe {
            if (totalSize == 0L || headerRead == 0) {
                return BackupHeaderProbe(false, 0L, "This .fable file is empty (0 bytes) and contains no backup data")
            }
            if (totalSize < MIN_ENVELOPE_SIZE || headerRead < HEADER_LEN) {
                return BackupHeaderProbe(false, totalSize, "This file is too small ($totalSize bytes) to be a valid Fable backup")
            }
            for (i in MAGIC.indices) {
                if (header[i] != MAGIC[i]) {
                    return BackupHeaderProbe(false, totalSize, "This file is not a valid Fable backup (missing FABLEBK1 header)")
                }
            }
            val iterations = ByteBuffer.wrap(header, MAGIC.size, 4).int
            if (iterations < 10_000 || iterations > 5_000_000) {
                return BackupHeaderProbe(false, totalSize, "Backup header is damaged (invalid KDF iterations)")
            }
            return BackupHeaderProbe(true, totalSize, null)
        }

        fun addOriginFromUrl(dest: MutableSet<String>, rawUrl: String) {
            if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) return
            val noScheme = rawUrl.substringAfter("://")
            val scheme = rawUrl.substringBefore("://").lowercase()
            val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isNotBlank() && !authority.contains(' ')) {
                dest.add("$scheme://$authority")
            }
        }

        fun mergeSiteStorageMaps(target: JSONObject, source: JSONObject) {
            source.keys().forEach { origin ->
                val srcEntry = source.optJSONObject(origin) ?: return@forEach
                val dstEntry = target.optJSONObject(origin) ?: JSONObject()
                val dstLs = dstEntry.optJSONObject("ls") ?: JSONObject()
                val dstSs = dstEntry.optJSONObject("ss") ?: JSONObject()
                srcEntry.optJSONObject("ls")?.let { sls ->
                    sls.keys().forEach { k -> dstLs.put(k, sls.optString(k)) }
                }
                srcEntry.optJSONObject("ss")?.let { sss ->
                    sss.keys().forEach { k -> dstSs.put(k, sss.optString(k)) }
                }
                dstEntry.put("ls", dstLs)
                dstEntry.put("ss", dstSs)
                target.put(origin, dstEntry)
            }
        }

        fun sealFileToFile(plainFile: File, sealedFile: File, password: CharArray) {
            if (password.size < MIN_PASSWORD) throw BackupException("Password must be at least $MIN_PASSWORD characters")
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val header = ByteBuffer.allocate(HEADER_LEN)
                .put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            BufferedOutputStream(FileOutputStream(sealedFile), STREAM_BUF).use { out ->
                out.write(header)
                BufferedInputStream(FileInputStream(plainFile), STREAM_BUF).use { ins ->
                    val buf = ByteArray(STREAM_BUF)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        val chunk = cipher.update(buf, 0, n)
                        if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    }
                }
                val fin = cipher.doFinal()
                if (fin != null && fin.isNotEmpty()) out.write(fin)
                out.flush()
            }
        }

        fun openFileToFile(sealedFile: File, plainFile: File, password: CharArray) {
            val probe = probeFileHeader(sealedFile)
            if (!probe.valid) throw BackupException(probe.error ?: "This file is not a valid Fable backup")
            BufferedInputStream(FileInputStream(sealedFile), STREAM_BUF).use { ins ->
                val header = ByteArray(HEADER_LEN)
                DataInputStream(ins).readFully(header)
                val bb = ByteBuffer.wrap(header)
                val magic = ByteArray(MAGIC.size).also { bb.get(it) }
                if (!magic.contentEquals(MAGIC)) throw BackupException("This file is not a valid Fable backup (missing FABLEBK1 header)")
                val iterations = bb.int
                val salt = ByteArray(16).also { bb.get(it) }
                val iv = ByteArray(12).also { bb.get(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
                cipher.updateAAD(header)
                try {
                    BufferedOutputStream(FileOutputStream(plainFile), STREAM_BUF).use { out ->
                        val buf = ByteArray(STREAM_BUF)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            val chunk = cipher.update(buf, 0, n)
                            if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                        }
                        val fin = cipher.doFinal()
                        if (fin != null && fin.isNotEmpty()) out.write(fin)
                        out.flush()
                    }
                } catch (_: Throwable) {
                    runCatching { plainFile.delete() }
                    throw BackupException("Wrong password or damaged backup")
                }
            }
        }

        fun sealBytes(plain: ByteArray, password: CharArray): ByteArray {
            if (password.size < MIN_PASSWORD) throw BackupException("Password must be at least $MIN_PASSWORD characters")
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val header = ByteBuffer.allocate(HEADER_LEN)
                .put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        }

        fun openBytes(raw: ByteArray, password: CharArray): ByteArray {
            val probe = validateHeaderBytes(raw, minOf(raw.size, HEADER_LEN), raw.size.toLong())
            if (!probe.valid) throw BackupException(probe.error ?: "This file is not a valid Fable backup")
            val input = DataInputStream(ByteArrayInputStream(raw))
            val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw BackupException("This file is not a valid Fable backup (missing FABLEBK1 header)")
            val iterations = input.readInt()
            val salt = ByteArray(16).also { input.readFully(it) }
            val iv = ByteArray(12).also { input.readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(raw, 0, HEADER_LEN)
            return runCatching { cipher.doFinal(raw, HEADER_LEN, raw.size - HEADER_LEN) }
                .getOrElse { throw BackupException("Wrong password or damaged backup") }
        }

        private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
            val spec = PBEKeySpec(password, salt, iterations, 256)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            return SecretKeySpec(bytes, "AES")
        }

        fun profileDir(context: Context): File? {
            val base = File(context.filesDir, "mozilla")
            if (!base.isDirectory) return null
            val candidates = base.listFiles()?.filter { it.isDirectory } ?: return null
            return candidates.firstOrNull { File(it, "cookies.sqlite").exists() }
                ?: candidates.firstOrNull { File(it, "prefs.js").exists() }
                ?: candidates.firstOrNull { it.name.endsWith(".default") }
        }

        fun webViewDefaultDir(context: Context): File? {
            val dataDir = context.applicationInfo?.dataDir ?: return null
            val def = File(dataDir, "app_webview/Default")
            return if (def.isDirectory) def else null
        }

        fun applyStagedRestore(context: Context) {
            runCatching {
                val staging = File(context.filesDir, STAGING_DIR)
                if (!File(staging, READY_MARK).exists()) return

                val wvStage = File(staging, WEBVIEW_SUBDIR)
                if (wvStage.isDirectory) {
                    val dataDir = context.applicationInfo?.dataDir
                    if (!dataDir.isNullOrBlank()) {
                        val wvTargetRoot = File(dataDir, "app_webview/Default").apply { mkdirs() }
                        wvStage.walkTopDown().filter { it.isFile }.forEach { file ->
                            val rel = file.relativeTo(wvStage).invariantSeparatorsPath
                            val target = File(wvTargetRoot, rel)
                            target.parentFile?.mkdirs()
                            if (file.name == "Cookies" || file.name == "Web Data") {
                                File(target.path + "-wal").delete()
                                File(target.path + "-shm").delete()
                                File(target.path + "-journal").delete()
                            }
                            file.copyTo(target, overwrite = true)
                        }
                        wvStage.deleteRecursively()
                    }
                }

                val profStage = File(staging, PROFILE_SUBDIR)
                val legacyFiles = staging.listFiles()?.filter { it.isFile && it.name != READY_MARK } ?: emptyList()
                val hasProfileWork = profStage.isDirectory || legacyFiles.isNotEmpty()
                if (hasProfileWork) {
                    val profile = profileDir(context)
                    if (profile != null) {
                        val srcRoot = if (profStage.isDirectory) profStage else staging
                        srcRoot.walkTopDown().filter { it.isFile && it.name != READY_MARK }
                            .sortedBy { if (it.name.endsWith(".sqlite")) 0 else 1 }
                            .forEach { file ->
                                val rel = file.relativeTo(srcRoot).invariantSeparatorsPath
                                val target = File(profile, rel)
                                target.parentFile?.mkdirs()
                                if (file.name.endsWith(".sqlite")) {
                                    File(target.path + "-wal").delete()
                                    File(target.path + "-shm").delete()
                                }
                                file.copyTo(target, overwrite = true)
                            }
                        staging.deleteRecursively()
                    }
                } else {
                    staging.deleteRecursively()
                }
            }
        }
    }
}
