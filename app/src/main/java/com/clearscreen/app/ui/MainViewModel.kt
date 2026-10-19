package com.clearscreen.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clearscreen.app.ClearScreenApp
import com.clearscreen.app.data.db.InterceptRecord
import com.clearscreen.app.data.repository.StatsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val repository: StatsRepository) : ViewModel() {

    val records: StateFlow<List<InterceptRecord>> = repository.allRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalInterceptCount: StateFlow<Int> = repository.totalInterceptCount
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalAppsCount: StateFlow<Int> = repository.totalAppsCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun clearStats() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    class Factory(private val repository: StatsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
