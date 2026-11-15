package xyz.ikuyo.finance.feature.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperationType
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.data.mapper.sortedBy
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator

data class BillsUiState(
    val isLoading: Boolean = true,
    val bills: List<Bill> = emptyList(),
    val filterType: BillType? = null,
    val sortOrder: BillSortOrder = BillSortOrder.OCCURRED_DESC,
    val totalExpenseMinor: Long = 0L,
    val totalIncomeMinor: Long = 0L,
    val refundedMinor: Long = 0L,
) {
    val isEmpty: Boolean get() = !isLoading && bills.isEmpty()
}

class BillsViewModel : ViewModel() {

    private val billRepository = Graph.billRepository
    private val settingsRepository = Graph.settingsRepository
    private val operationRepository = Graph.operationRepository

    private val filterType = MutableStateFlow<BillType?>(null)
    private val localSort = MutableStateFlow<BillSortOrder?>(null)
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var lastDeletedId: Long? = null

    val state: StateFlow<BillsUiState> = combine(
        billRepository.observeActive(),
        settingsRepository.sortOrder,
        filterType,
        localSort,
    ) { bills, persistedSort, type, local ->
        val order = local ?: persistedSort
        val filtered = if (type == null) bills else bills.filter { it.type == type }
        val sorted = filtered.sortedBy(order)
        BillsUiState(
            isLoading = false,
            bills = sorted,
            filterType = type,
            sortOrder = order,
            totalExpenseMinor = filtered.filter { it.type == BillType.EXPENSE }.sumOf { it.cnyAmountMinor },
            totalIncomeMinor = filtered.filter { it.type == BillType.INCOME }.sumOf { it.cnyAmountMinor },
            refundedMinor = filtered.filter { it.type == BillType.REFUND }.sumOf { it.cnyAmountMinor },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillsUiState())

    fun setFilter(type: BillType?) {
        filterType.value = type
    }

    fun setSortOrder(order: BillSortOrder) {
        localSort.value = order
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    /** 软删除，保留撤销能力；同时写入操作历史，供「操作历史」页逐条撤销。 */
    fun delete(bill: Bill) {
        viewModelScope.launch {
            billRepository.softDelete(bill.id)
            lastDeletedId = bill.id
            operationRepository.record(
                billId = bill.id,
                type = BillOperationType.DELETE,
                summary = OperationRepository.summaryOf(BillOperationType.DELETE, bill),
                amountMinor = bill.cnyAmountMinor,
                before = bill,
                operatedAt = Time.nowMillis(),
            )
            _events.emit(UiEvent.Message("已移入回收站：${bill.displayTitle()}", undoable = true))
        }
    }

    fun undoDelete() {
        val id = lastDeletedId ?: return
        lastDeletedId = null
        viewModelScope.launch {
            billRepository.restore(id)
            operationRepository.record(
                billId = id,
                type = BillOperationType.RESTORE,
                summary = "恢复：已从回收站还原一笔账单",
                operatedAt = Time.nowMillis(),
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BillsViewModel() }
        }
    }
}

internal fun Bill.displayTitle(): String =
    note.ifBlank { categoryName ?: type.label }