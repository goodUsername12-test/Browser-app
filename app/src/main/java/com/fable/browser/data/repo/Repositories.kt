package com.fable.browser.data.repo

import com.fable.browser.data.db.BookmarkDao
import com.fable.browser.data.db.DownloadDao
import com.fable.browser.data.db.HistoryDao
import com.fable.browser.data.db.SavedTabDao
import com.fable.browser.data.db.SitePermissionDao
import com.fable.browser.data.model.Bookmark
import com.fable.browser.data.model.BookmarkFolder
import com.fable.browser.data.model.DownloadEntry
import com.fable.browser.data.model.DownloadStatus
import com.fable.browser.data.model.HistoryEntry
import com.fable.browser.data.model.TopSite
import com.fable.browser.data.model.PermissionDecision
import com.fable.browser.data.model.PermissionKind
import com.fable.browser.data.model.SavedTab
import com.fable.browser.data.model.SitePermission
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(private val dao: BookmarkDao) {
    fun bookmarksInFolder(folderId: Long?): Flow<List<Bookmark>> = dao.bookmarksInFolder(folderId)
    fun allBookmarks(): Flow<List<Bookmark>> = dao.allBookmarks()
    fun search(query: String): Flow<List<Bookmark>> = dao.search(query)
    suspend fun searchOnce(query: String, limit: Int = 5): List<Bookmark> = dao.searchOnce(query, limit)
    fun isBookmarked(url: String): Flow<Boolean> = dao.isBookmarked(url)
    suspend fun toggle(title: String, url: String) {
        val existing = dao.byUrl(url)
        if (existing != null) dao.delete(existing) else dao.insert(Bookmark(title = title.ifBlank { url }, url = url))
    }
    suspend fun add(bookmark: Bookmark): Long = dao.insert(bookmark)
    suspend fun update(bookmark: Bookmark) = dao.update(bookmark)
    suspend fun delete(bookmark: Bookmark) = dao.delete(bookmark)

    fun foldersIn(parentId: Long?): Flow<List<BookmarkFolder>> = dao.foldersIn(parentId)
    fun allFolders(): Flow<List<BookmarkFolder>> = dao.allFolders()
    suspend fun addFolder(name: String, parentId: Long?): Long =
        dao.insertFolder(BookmarkFolder(name = name, parentId = parentId))
    suspend fun renameFolder(folder: BookmarkFolder, name: String) = dao.updateFolder(folder.copy(name = name))
    suspend fun deleteFolder(folder: BookmarkFolder) {
        dao.orphanBookmarksOf(folder.id)
        dao.deleteFolder(folder)
    }
}

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {
    fun recent(): Flow<List<HistoryEntry>> = dao.recent()
    fun search(query: String): Flow<List<HistoryEntry>> = dao.search(query)
    suspend fun searchOnce(query: String, limit: Int = 5): List<HistoryEntry> = dao.searchOnce(query, limit)
    fun topSites(limit: Int = 8): Flow<List<TopSite>> = dao.topSites(limit)

    suspend fun record(title: String, url: String) {
        val last = dao.lastVisitOf(url)
        if (last != null && System.currentTimeMillis() - last.visitedAt < 5_000) return
        dao.insert(HistoryEntry(title = title.ifBlank { url }, url = url))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun clearLastHour() = dao.clearSince(System.currentTimeMillis() - 3_600_000)
    suspend fun clearToday() {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        dao.clearSince(cal.timeInMillis)
    }
    suspend fun clearAll() = dao.clearAll()
}

@Singleton
class DownloadRepository @Inject constructor(private val dao: DownloadDao) {
    fun all(): Flow<List<DownloadEntry>> = dao.all()
    suspend fun start(entry: DownloadEntry): Long = dao.insert(entry)
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long) = dao.updateProgress(id, downloaded, total)
    suspend fun setStatus(id: Long, status: DownloadStatus) = dao.updateStatus(id, status)
    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun byId(id: Long): DownloadEntry? = dao.byId(id)
}

@Singleton
class TabPersistenceRepository @Inject constructor(private val dao: SavedTabDao) {
    suspend fun load(): List<SavedTab> = dao.all()
    suspend fun save(tabs: List<SavedTab>) {
        dao.clear()
        if (tabs.isNotEmpty()) dao.insertAll(tabs)
    }
    suspend fun clear() = dao.clear()
}

@Singleton
class SitePermissionRepository @Inject constructor(private val dao: SitePermissionDao) {
    fun all(): Flow<List<SitePermission>> = dao.all()
    suspend fun decisionFor(origin: String, kind: PermissionKind): PermissionDecision =
        dao.decisionFor(origin, kind)?.decision ?: PermissionDecision.ASK
    suspend fun set(origin: String, kind: PermissionKind, decision: PermissionDecision) =
        dao.upsert(SitePermission(origin = origin, permission = kind, decision = decision))
    suspend fun remove(id: Long) = dao.deleteById(id)
    suspend fun clearOrigin(origin: String) = dao.deleteAllForOrigin(origin)
}
