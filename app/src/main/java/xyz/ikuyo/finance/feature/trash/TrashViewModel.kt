package xyz.ikuyo.finance.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill

data class TrashUiState(
    val isLoading: Boolean = true,
    val bills: List<Bill> = emptyList(),
) {
    val isEmpty: Boolean get() = !isLoading && bills.isEmpty()
}

class TrashViewModel : ViewModel() {

    private val billRepository = Graph.billRepository

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val state: StateFlow<TrashUiState> = billRepository.observeTrash()
        .map { bills -> TrashUiState(isLoading = false, bills = bills) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun restore(bill: Bill) {
        viewModelScope.launch {
            billRepository.restore(bill.id)
            _events.emit(UiEvent.Message("已恢复：${bill.note.ifBlank { bill.categoryName ?: "账单" }}"))
        }
    }

    fun purge(bill: Bill) {
        viewModelScope.launch {
            billRepository.purge(bill.id)
            _events.emit(UiEvent.Message("已彻底删除"))
        }
    }

    fun purgeAll() {
        viewModelScope.launch {
            billRepository.purgeAllTrash()
            _events.emit(UiEvent.Message("回收站已清空"))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel() }
        }
    }
}