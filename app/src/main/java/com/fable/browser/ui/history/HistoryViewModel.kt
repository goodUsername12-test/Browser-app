package com.fable.browser.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fable.browser.data.model.HistoryEntry
import com.fable.browser.data.repo.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ClearRange(val label: String) {
    LAST_HOUR("Last hour"),
    TODAY("Today"),
    ALL_TIME("All time")
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<HistoryEntry>> = _query
        .flatMapLatest { q -> if (q.isBlank()) repository.recent() else repository.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { _query.value = value }

    fun delete(entry: HistoryEntry) {
        viewModelScope.launch { repository.delete(entry.id) }
    }

    fun clear(range: ClearRange) {
        viewModelScope.launch {
            when (range) {
                ClearRange.LAST_HOUR -> repository.clearLastHour()
                ClearRange.TODAY -> repository.clearToday()
                ClearRange.ALL_TIME -> repository.clearAll()
            }
        }
    }
}
