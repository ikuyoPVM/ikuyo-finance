package xyz.ikuyo.finance.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType

/** 明细页状态：金额与统计页同源口径（支出/退款分开，净支出 = 支出 − 退款）。 */
data class StatsBillsUiState(
    val bills: List<Bill> = emptyList(),
    val expenseMinor: Long = 0L,
    val refundMinor: Long = 0L,
    val incomeMinor: Long = 0L,
) {
    val netExpenseMinor: Long get() = expenseMinor - refundMinor
    val isEmpty: Boolean get() = bills.isEmpty()
}

class StatsBillsViewModel(
    private val startMillis: Long,
    private val endMillisExclusive: Long,
    private val categoryId: Long? = null,
    private val tagId: Long? = null,
) : ViewModel() {

    private val billIdsOfTag = tagId?.let { Graph.tagRepository.observeBillIdsOf(it) }

    val state: StateFlow<StatsBillsUiState> = combine(
        Graph.billRepository.observeActive(),
        billIdsOfTag ?: kotlinx.coroutines.flow.flowOf(null),
    ) { bills, tagIds ->
        val filtered = bills
            .filter { it.occurredAt >= startMillis && it.occurredAt < endMillisExclusive }
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { tagIds == null || it.id in tagIds }
            .sortedByDescending { it.occurredAt }

        StatsBillsUiState(
            bills = filtered,
            expenseMinor = filtered.sumOf { if (it.type == BillType.EXPENSE) it.cnyAmountMinor else 0L },
            refundMinor = filtered.sumOf { if (it.type == BillType.REFUND) it.cnyAmountMinor else 0L },
            incomeMinor = filtered.sumOf { if (it.type == BillType.INCOME) it.cnyAmountMinor else 0L },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsBillsUiState())

    companion object {
        fun factory(
            startMillis: Long,
            endMillisExclusive: Long,
            categoryId: Long? = null,
            tagId: Long? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StatsBillsViewModel(startMillis, endMillisExclusive, categoryId, tagId) as T
        }
    }
}