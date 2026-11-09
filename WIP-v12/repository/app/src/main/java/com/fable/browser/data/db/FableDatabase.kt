package com.fable.browser.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.fable.browser.data.model.Bookmark
import com.fable.browser.data.model.BookmarkFolder
import com.fable.browser.data.model.DownloadEntry
import com.fable.browser.data.model.DownloadStatus
import com.fable.browser.data.model.HistoryEntry
import com.fable.browser.data.model.PermissionDecision
import com.fable.browser.data.model.PermissionKind
import com.fable.browser.data.model.SavedTab
import com.fable.browser.data.model.SitePermission

class EnumConverters {
    @TypeConverter fun fromDownloadStatus(v: DownloadStatus): String = v.name
    @TypeConverter fun toDownloadStatus(v: String): DownloadStatus = DownloadStatus.valueOf(v)
    @TypeConverter fun fromPermissionKind(v: PermissionKind): String = v.name
    @TypeConverter fun toPermissionKind(v: String): PermissionKind = PermissionKind.valueOf(v)
    @TypeConverter fun fromPermissionDecision(v: PermissionDecision): String = v.name
    @TypeConverter fun toPermissionDecision(v: String): PermissionDecision = PermissionDecision.valueOf(v)
}

@Database(
    entities = [
        Bookmark::class,
        BookmarkFolder::class,
        HistoryEntry::class,
        DownloadEntry::class,
        SavedTab::class,
        SitePermission::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(EnumConverters::class)
abstract class FableDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun savedTabDao(): SavedTabDao
    abstract fun sitePermissionDao(): SitePermissionDao
}
