package com.fable.browser.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.model.DownloadEntry
import com.fable.browser.data.model.DownloadStatus
import com.fable.browser.data.repo.DownloadRepository
import com.fable.browser.engine.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Download manager state. The list is Room-backed, so progress written by the
 * IO worker in [Downloader] streams straight into the UI without polling.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val downloader: Downloader
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntry>> = repository.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeCount: StateFlow<Int> = downloader.activeCount

    fun open(entry: DownloadEntry) {
        if (entry.status != DownloadStatus.COMPLETED) return
        viewModelScope.launch { downloader.open(entry) }
    }

    /** Removes the record only; the saved file in Downloads/Fable is left alone. */
    fun remove(entry: DownloadEntry) {
        viewModelScope.launch { repository.delete(entry.id) }
    }

    fun clearFinished() {
        viewModelScope.launch {
            downloads.value
                .filter { it.status != DownloadStatus.IN_PROGRESS }
                .forEach { repository.delete(it.id) }
        }
    }
}
