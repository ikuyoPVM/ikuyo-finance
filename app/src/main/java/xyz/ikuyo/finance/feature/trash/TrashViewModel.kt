package xyz.ikuyo.finance.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperationType

data class TrashUiState(
    val isLoading: Boolean = true,
    val bills: List<Bill> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean get() = !isLoading && bills.isEmpty()
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = bills.isNotEmpty() && selectedIds.size == bills.size
    val selectedCount: Int get() = selectedIds.size
}

/** 回收站：软删除账单的查看、单条/批量恢复与彻底删除。 */
class TrashViewModel : ViewModel() {

    private val billRepository = Graph.billRepository
    private val operationRepository = Graph.operationRepository

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<TrashUiState> = combine(
        billRepository.observeTrash(),
        selectedIds,
    ) { bills, selected ->
        val alive = selected.filter { id -> bills.any { it.id == id } }.toSet()
        TrashUiState(isLoading = false, bills = bills, selectedIds = alive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun toggleSelect(billId: Long) {
        selectedIds.value = selectedIds.value.let { current ->
            if (billId in current) current - billId else current + billId
        }
    }

    fun toggleSelectAll() {
        val all = state.value.bills.map { it.id }.toSet()
        selectedIds.value = if (selectedIds.value.size == all.size && all.isNotEmpty()) emptySet() else all
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun restore(bill: Bill) {
        viewModelScope.launch {
            billRepository.restore(bill.id)
            recordRestore(bill)
            _events.emit(UiEvent.Message("已恢复：${bill.note.ifBlank { bill.categoryName ?: "账单" }}"))
        }
    }

    fun purge(bill: Bill) {
        viewModelScope.launch {
            billRepository.purge(bill.id)
            recordPurge(listOf(bill))
            _events.emit(UiEvent.Message("已彻底删除"))
        }
    }

    fun restoreSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val targets = state.value.bills.filter { it.id in ids }
            targets.forEach { bill ->
                billRepository.restore(bill.id)
                recordRestore(bill)
            }
            selectedIds.value = emptySet()
            _events.emit(UiEvent.Message("已恢复 ${targets.size} 笔账单"))
        }
    }

    fun purgeSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val targets = state.value.bills.filter { it.id in ids }
            targets.forEach { billRepository.purge(it.id) }
            recordPurge(targets)
            selectedIds.value = emptySet()
            _events.emit(UiEvent.Message("已彻底删除 ${targets.size} 笔账单"))
        }
    }

    /** 清空回收站：逐条彻底删除，并记录每条的历史。 */
    fun purgeAll() {
        viewModelScope.launch {
            val all = state.value.bills
            all.forEach { billRepository.purge(it.id) }
            recordPurge(all)
            selectedIds.value = emptySet()
            _events.emit(UiEvent.Message("回收站已清空（${all.size} 笔）"))
        }
    }

    private suspend fun recordRestore(bill: Bill) {
        operationRepository.record(
            billId = bill.id,
            type = BillOperationType.RESTORE,
            summary = OperationRepository.summaryOf(BillOperationType.RESTORE, bill),
            amountMinor = bill.cnyAmountMinor,
            after = bill,
            operatedAt = Time.nowMillis(),
        )
    }

    private suspend fun recordPurge(bills: List<Bill>) {
        bills.forEach { bill ->
            operationRepository.record(
                billId = bill.id,
                type = BillOperationType.PURGE,
                summary = OperationRepository.summaryOf(BillOperationType.PURGE, bill),
                amountMinor = bill.cnyAmountMinor,
                before = bill,
                operatedAt = Time.nowMillis(),
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel() }
        }
    }
}