package com.fable.browser.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved bookmark. folderId == null means root folder. */
@Entity(
    tableName = "bookmarks",
    indices = [Index("folderId"), Index("url")]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val folderId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** A bookmark folder. parentId == null means root. */
@Entity(tableName = "bookmark_folders", indices = [Index("parentId")])
data class BookmarkFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** Normal-mode history entry. NEVER written from incognito sessions. */
@Entity(tableName = "history", indices = [Index("url"), Index("visitedAt")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

data class TopSite(
    val url: String,
    val title: String,
    val visits: Int
)

/** A download record (normal mode only; incognito downloads require confirmation and are still recorded only if the user opts in). */
@Entity(tableName = "downloads", indices = [Index("startedAt")])
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val url: String,
    val filePath: String,
    val mimeType: String?,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.IN_PROGRESS,
    val startedAt: Long = System.currentTimeMillis(),
    val fromIncognito: Boolean = false
)

enum class DownloadStatus { IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

/** Persisted normal tabs for restore-after-kill. Incognito tabs are NEVER persisted. */
@Entity(tableName = "saved_tabs")
data class SavedTab(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val position: Int,
    val isActive: Boolean = false
)

/** Per-site permission decisions, revocable in Settings -> Site settings. */
@Entity(
    tableName = "site_permissions",
    indices = [Index(value = ["origin", "permission"], unique = true)]
)
data class SitePermission(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val origin: String,
    val permission: PermissionKind,
    val decision: PermissionDecision,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PermissionKind { CAMERA, MICROPHONE, LOCATION, NOTIFICATIONS, AUTOPLAY, XR }

enum class PermissionDecision { ALLOW, BLOCK, ASK }

/** Search engines the omnibox can use. */
enum class SearchEngine(val label: String, val queryUrl: String, val suggestUrl: String?) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", null),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", null),
    BING("Bing", "https://www.bing.com/search?q=%s", null),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s", null),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s", null),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s", null)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class DohProvider(val label: String, val url: String) {
    CLOUDFLARE("Cloudflare", "https://mozilla.cloudflare-dns.com/dns-query"),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query"),
    CUSTOM("Custom", ""),
    OFF("Off", "")
}

enum class IncognitoAutoWipe(val label: String, val minutes: Int) {
    OFF("Off", 0),
    ONE_MIN("1 minute", 1),
    FIVE_MIN("5 minutes", 5),
    FIFTEEN_MIN("15 minutes", 15),
    SIXTY_MIN("60 minutes", 60)
}

enum class PerformanceProfile(val label: String, val summary: String) {
    MAX_PRIVACY(
        "Maximum privacy",
        "Fingerprint shield and header cleaning in every tab, strict tracking protection and filter-list blocking. Some games may run slower."
    ),
    BALANCED(
        "Balanced",
        "Filter-list blocking with standard tracking protection. Games get full WebGL, gamepad and timer access."
    ),
    MAX_SPEED(
        "Maximum speed",
        "Only Gecko's built-in tracking protection in normal tabs. No extra work per request."
    ),
    CUSTOM("Custom", "Your own mix of the switches below.")
}

enum class TabUnloadMode(val label: String, val summary: String) {
    SMART(
        "Smart",
        "Keeps the current tab, your most recent tabs, pinned tabs and tabs playing media loaded."
    ),
    KEEP_ALL("Keep all loaded", "Tabs are never unloaded. Uses more memory."),
    AGGRESSIVE("Only current tab", "Every background tab is unloaded. Lowest memory use.")
}
