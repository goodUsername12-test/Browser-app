package com.fable.browser.ui.bookmarks

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.model.Bookmark
import com.fable.browser.data.model.BookmarkFolder
import com.fable.browser.data.repo.BookmarkRepository
import com.fable.browser.util.ActivityBridge
import com.fable.browser.util.BookmarkHtml
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BookmarksUiState(
    val folders: List<BookmarkFolder> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList()
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BookmarkRepository,
    private val activityBridge: ActivityBridge
) : ViewModel() {
    private val _currentFolder = MutableStateFlow<BookmarkFolder?>(null)
    val currentFolder: StateFlow<BookmarkFolder?> = _currentFolder.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<BookmarksUiState> =
        combine(_currentFolder, _query) { folder, query -> folder to query }
            .flatMapLatest { (folder, query) ->
                if (query.isBlank()) {
                    combine(
                        repository.foldersIn(folder?.id),
                        repository.bookmarksInFolder(folder?.id)
                    ) { folders, bookmarks -> BookmarksUiState(folders, bookmarks) }
                } else {
                    combine(
                        repository.foldersIn(folder?.id),
                        repository.search(query)
                    ) { _, bookmarks -> BookmarksUiState(emptyList(), bookmarks) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarksUiState())

    fun onQueryChange(value: String) { _query.value = value }

    fun openFolder(folder: BookmarkFolder?) {
        _query.value = ""
        _currentFolder.value = folder
    }

    fun addFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addFolder(name.trim(), _currentFolder.value?.id) }
    }

    fun renameFolder(folder: BookmarkFolder, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameFolder(folder, name.trim()) }
    }

    fun deleteFolder(folder: BookmarkFolder) {
        viewModelScope.launch {
            repository.deleteFolder(folder)
            if (_currentFolder.value?.id == folder.id) _currentFolder.value = null
        }
    }

    fun updateBookmark(bookmark: Bookmark, title: String, url: String, folderId: Long?) {
        viewModelScope.launch {
            repository.update(bookmark.copy(title = title.trim(), url = url.trim(), folderId = folderId))
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.delete(bookmark) }
    }

    fun export(destination: Uri) {
        viewModelScope.launch {
            val all = repository.allBookmarks().first()
            val html = BookmarkHtml.export(all)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination)?.use { out ->
                        out.write(html.toByteArray())
                    } ?: error("no stream")
                }.isSuccess
            }
            activityBridge.toast(
                if (ok) "Exported ${all.size} bookmarks" else "Export failed"
            )
        }
    }

    fun import(source: Uri) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val html = context.contentResolver.openInputStream(source)
                        ?.bufferedReader()?.use { it.readText() } ?: error("no stream")
                    BookmarkHtml.parse(html)
                }.getOrElse { emptyList() }
            }
            val folderId = _currentFolder.value?.id
            parsed.forEach { repository.add(it.copy(folderId = folderId)) }
            activityBridge.toast(
                if (parsed.isEmpty()) "Nothing to import" else "Imported ${parsed.size} bookmarks"
            )
        }
    }
}
