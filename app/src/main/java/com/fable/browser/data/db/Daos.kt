package com.fable.browser.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE folderId IS :folderId ORDER BY createdAt DESC")
    fun bookmarksInFolder(folderId: Long?): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun allBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchOnce(query: String, limit: Int): List<Bookmark>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Update
    suspend fun update(bookmark: Bookmark)

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    // Folders
    @Query("SELECT * FROM bookmark_folders WHERE parentId IS :parentId ORDER BY name")
    fun foldersIn(parentId: Long?): Flow<List<BookmarkFolder>>

    @Query("SELECT * FROM bookmark_folders ORDER BY name")
    fun allFolders(): Flow<List<BookmarkFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: BookmarkFolder): Long

    @Update
    suspend fun updateFolder(folder: BookmarkFolder)

    @Delete
    suspend fun deleteFolder(folder: BookmarkFolder)

    @Query("UPDATE bookmarks SET folderId = NULL WHERE folderId = :folderId")
    suspend fun orphanBookmarksOf(folderId: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 1000")
    fun recent(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY visitedAt DESC LIMIT 500")
    fun search(query: String): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun searchOnce(query: String, limit: Int): List<HistoryEntry>

    @Insert
    suspend fun insert(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history WHERE visitedAt >= :since")
    suspend fun clearSince(since: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("SELECT * FROM history WHERE url = :url ORDER BY visitedAt DESC LIMIT 1")
    suspend fun lastVisitOf(url: String): HistoryEntry?

    @Query("SELECT url, MAX(title) AS title, COUNT(*) AS visits FROM history WHERE url LIKE 'http%' GROUP BY url ORDER BY visits DESC, MAX(visitedAt) DESC LIMIT :limit")
    fun topSites(limit: Int): Flow<List<TopSite>>
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY startedAt DESC")
    fun all(): Flow<List<DownloadEntry>>

    @Insert
    suspend fun insert(entry: DownloadEntry): Long

    @Update
    suspend fun update(entry: DownloadEntry)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadEntry?
}

@Dao
interface SavedTabDao {
    @Query("SELECT * FROM saved_tabs ORDER BY position")
    suspend fun all(): List<SavedTab>

    @Insert
    suspend fun insertAll(tabs: List<SavedTab>)

    @Query("DELETE FROM saved_tabs")
    suspend fun clear()
}

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM site_permissions ORDER BY origin")
    fun all(): Flow<List<SitePermission>>

    @Query("SELECT * FROM site_permissions WHERE origin = :origin")
    suspend fun forOrigin(origin: String): List<SitePermission>

    @Query("SELECT * FROM site_permissions WHERE origin = :origin AND permission = :kind LIMIT 1")
    suspend fun decisionFor(origin: String, kind: PermissionKind): SitePermission?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(permission: SitePermission)

    @Query("DELETE FROM site_permissions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM site_permissions WHERE origin = :origin")
    suspend fun deleteAllForOrigin(origin: String)
}
