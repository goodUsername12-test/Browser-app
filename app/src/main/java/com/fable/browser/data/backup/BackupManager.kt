package com.fable.browser.data.backup

import android.content.Context
import android.net.Uri
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
import com.fable.browser.engine.TabManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
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
    val siteDataFiles: Int = 0,
    val needsRestart: Boolean = false
) {
    fun summary(): String = buildList {
        if (bookmarks > 0) add("$bookmarks bookmarks")
        if (history > 0) add("$history history entries")
        if (passwords > 0) add("$passwords passwords")
        if (tabs > 0) add("$tabs tabs")
        if (permissions > 0) add("$permissions site permissions")
        if (downloads > 0) add("$downloads download records")
        if (settings) add("settings")
        if (siteDataFiles > 0) add("cookies and site data")
    }.joinToString(", ").ifEmpty { "nothing new" }
}

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
    private val tabManager: Lazy<TabManager>
) {
    suspend fun backup(uri: Uri, password: CharArray, options: BackupOptions): BackupResult = withContext(Dispatchers.IO) {
        if (password.size < MIN_PASSWORD) throw BackupException("Password must be at least $MIN_PASSWORD characters")
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("created", System.currentTimeMillis())
        root.put("app", context.packageName)
        var result = BackupResult()
        if (options.settings) {
            root.put("settings", settingsStore.exportJson())
            val secure = JSONObject()
            SECURE_NAMES.forEach { name -> secureStore.read(name)?.let { secure.put(name, it) } }
            root.put("secure", secure)
            result = result.copy(settings = true)
        }
        if (options.passwords) {
            val arr = passwordStore.exportJson()
            root.put("passwords", arr)
            result = result.copy(passwords = arr.length())
        }
        if (options.bookmarks) {
            val folders = JSONArray()
            bookmarkDao.allFoldersOnce().forEach { f ->
                folders.put(JSONObject().put("id", f.id).put("name", f.name).put("parentId", f.parentId ?: JSONObject.NULL).put("createdAt", f.createdAt))
            }
            val marks = JSONArray()
            bookmarkDao.allBookmarksOnce().forEach { b ->
                marks.put(JSONObject().put("title", b.title).put("url", b.url).put("folderId", b.folderId ?: JSONObject.NULL).put("createdAt", b.createdAt))
            }
            root.put("bookmarkFolders", folders)
            root.put("bookmarks", marks)
            result = result.copy(bookmarks = marks.length())
        }
        if (options.history) {
            val arr = JSONArray()
            historyDao.allOnce().forEach { h -> arr.put(JSONObject().put("title", h.title).put("url", h.url).put("visitedAt", h.visitedAt)) }
            root.put("history", arr)
            result = result.copy(history = arr.length())
        }
        if (options.tabs) {
            val tabs = withContext(Dispatchers.Main) { tabManager.get().exportNormalTabs() }
            val arr = JSONArray()
            tabs.forEach { t -> arr.put(JSONObject().put("url", t.url).put("title", t.title).put("position", t.position).put("active", t.isActive)) }
            root.put("tabs", arr)
            result = result.copy(tabs = arr.length())
        }
        if (options.permissions) {
            val arr = JSONArray()
            permissionDao.allOnce().forEach { p ->
                arr.put(JSONObject().put("origin", p.origin).put("permission", p.permission.name).put("decision", p.decision.name).put("updatedAt", p.updatedAt))
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
                        .put("downloadedBytes", d.downloadedBytes).put("status", d.status.name).put("startedAt", d.startedAt)
                )
            }
            root.put("downloads", arr)
            result = result.copy(downloads = arr.length())
        }
        val zipBytes = ByteArrayOutputStream()
        var siteFiles = 0
        ZipOutputStream(zipBytes).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (options.siteData) {
                val profile = profileDir()
                if (profile != null) {
                    var budget = SITE_DATA_LIMIT
                    siteDataFiles(profile).forEach { file ->
                        val len = file.length()
                        if (len > budget) return@forEach
                        val rel = file.relativeTo(profile).invariantSeparatorsPath
                        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                        zip.putNextEntry(ZipEntry(PROFILE_PREFIX + rel))
                        zip.write(bytes)
                        zip.closeEntry()
                        budget -= len
                        siteFiles++
                    }
                }
            }
        }
        val sealed = seal(zipBytes.toByteArray(), password)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: context.contentResolver.openOutputStream(uri)
            ?: throw BackupException("Could not open the selected file")
        out.use { it.write(sealed) }
        result.copy(siteDataFiles = siteFiles)
    }

    suspend fun restore(uri: Uri, password: CharArray): BackupResult = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException("Could not open the selected file")
        val plain = open(raw, password)
        var root: JSONObject? = null
        val stagedFiles = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.contains("..")) continue
                val bytes = zip.readBytes()
                when {
                    name == DATA_ENTRY -> root = JSONObject(String(bytes, Charsets.UTF_8))
                    name.startsWith(PROFILE_PREFIX) -> stagedFiles.add(name.removePrefix(PROFILE_PREFIX) to bytes)
                }
            }
        }
        val data = root ?: throw BackupException("This file is not a Fable backup")
        var result = BackupResult()
        data.optJSONObject("settings")?.let {
            settingsStore.importJson(it)
            result = result.copy(settings = true)
        }
        data.optJSONObject("secure")?.let { secure ->
            secure.keys().forEach { name -> if (name in SECURE_NAMES) secureStore.write(name, secure.getString(name)) }
            if (secure.length() > 0) result = result.copy(needsRestart = true)
        }
        data.optJSONArray("passwords")?.let { result = result.copy(passwords = passwordStore.importJson(it)) }
        data.optJSONArray("bookmarks")?.let { marks ->
            result = result.copy(bookmarks = importBookmarks(data.optJSONArray("bookmarkFolders") ?: JSONArray(), marks))
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
        data.optJSONArray("tabs")?.let { arr ->
            val tabs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedTab(url = o.optString("url"), title = o.optString("title"), position = o.optInt("position", i), isActive = false)
            }
            val n = withContext(Dispatchers.Main) { tabManager.get().importTabs(tabs) }
            result = result.copy(tabs = n)
        }
        if (stagedFiles.isNotEmpty()) {
            val staging = File(context.filesDir, STAGING_DIR)
            staging.deleteRecursively()
            stagedFiles.forEach { (rel, bytes) ->
                val target = File(staging, rel)
                if (!target.canonicalPath.startsWith(staging.canonicalPath)) return@forEach
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            File(staging, READY_MARK).writeText(System.currentTimeMillis().toString())
            result = result.copy(siteDataFiles = stagedFiles.size, needsRestart = true)
        }
        result
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
            idMap[oldId] = match?.id ?: bookmarkDao.insertFolder(BookmarkFolder(name = name, parentId = null, createdAt = o.optLong("createdAt", System.currentTimeMillis())))
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
            bookmarkDao.insert(Bookmark(title = o.optString("title"), url = url, folderId = folder, createdAt = o.optLong("createdAt", System.currentTimeMillis())))
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

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun seal(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(MAGIC.size + 4 + salt.size + iv.size)
            .put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    private fun open(raw: ByteArray, password: CharArray): ByteArray {
        if (raw.size < MAGIC.size + 4 + 16 + 12 + 16) throw BackupException("This file is not a Fable backup")
        val input = DataInputStream(ByteArrayInputStream(raw))
        val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupException("This file is not a Fable backup")
        val iterations = input.readInt()
        if (iterations < 10_000 || iterations > 5_000_000) throw BackupException("Backup header is damaged")
        val salt = ByteArray(16).also { input.readFully(it) }
        val iv = ByteArray(12).also { input.readFully(it) }
        val headerLen = MAGIC.size + 4 + 16 + 12
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(raw, 0, headerLen)
        return runCatching { cipher.doFinal(raw, headerLen, raw.size - headerLen) }
            .getOrElse { throw BackupException("Wrong password or damaged backup") }
    }

    companion object {
        const val MIN_PASSWORD = 8
        private const val FORMAT_VERSION = 1
        private const val ITERATIONS = 150_000
        private const val DATA_ENTRY = "fable.json"
        private const val PROFILE_PREFIX = "profile/"
        private const val STAGING_DIR = "fable_restore"
        private const val READY_MARK = ".ready"
        private const val SITE_DATA_LIMIT = 48L * 1024 * 1024
        private val MAGIC = "FABLEBK1".toByteArray(Charsets.US_ASCII)
        private val SECURE_NAMES = setOf("super_proxy", "super_identity")
        private val SITE_FILES = listOf("cookies.sqlite", "webappsstore.sqlite", "permissions.sqlite", "content-prefs.sqlite", "cert_override.txt", "siteSecurityServiceState.bin")

        fun profileDir(context: Context): File? {
            val base = File(context.filesDir, "mozilla")
            if (!base.isDirectory) return null
            val candidates = base.listFiles()?.filter { it.isDirectory } ?: return null
            return candidates.firstOrNull { File(it, "cookies.sqlite").exists() }
                ?: candidates.firstOrNull { File(it, "prefs.js").exists() }
                ?: candidates.firstOrNull { it.name.endsWith(".default") }
        }

        fun applyStagedRestore(context: Context) {
            runCatching {
                val staging = File(context.filesDir, STAGING_DIR)
                if (!File(staging, READY_MARK).exists()) return
                val profile = profileDir(context)
                if (profile != null) {
                    staging.walkTopDown().filter { it.isFile && it.name != READY_MARK }
                        .sortedBy { if (it.name.endsWith(".sqlite")) 0 else 1 }
                        .forEach { file ->
                        val rel = file.relativeTo(staging).invariantSeparatorsPath
                        val target = File(profile, rel)
                        target.parentFile?.mkdirs()
                        if (file.name.endsWith(".sqlite")) {
                            File(target.path + "-wal").delete()
                            File(target.path + "-shm").delete()
                        }
                        file.copyTo(target, overwrite = true)
                    }
                }
                staging.deleteRecursively()
            }
        }
    }
}
