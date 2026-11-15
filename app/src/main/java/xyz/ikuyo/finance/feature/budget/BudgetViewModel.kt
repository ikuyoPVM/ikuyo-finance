package xyz.ikuyo.finance.feature.budget

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator
import java.time.YearMonth

data class CategoryBudgetRow(
    val category: Category,
    val amountMinor: Long?,
    val spentMinor: Long,
) {
    val progress: Float
        get() {
            val amount = amountMinor ?: return 0f
            if (amount <= 0L) return 0f
            return (spentMinor.toFloat() / amount.toFloat()).coerceIn(0f, 1f)
        }

    val isOverBudget: Boolean
        get() = amountMinor != null && spentMinor > amountMinor
}

data class BudgetUiState(
    val isLoading: Boolean = true,
    val monthLabel: String = "",
    val total: BudgetStatus = BudgetStatus(),
    val categories: List<CategoryBudgetRow> = emptyList(),
    val totalBudgetMinor: Long? = null,
)

class BudgetViewModel : ViewModel() {

    private val billRepository = Graph.billRepository
    private val budgetRepository = Graph.budgetRepository
    private val categoryRepository = Graph.categoryRepository

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val month: YearMonth = YearMonth.now(Time.zone)

    val state: StateFlow<BudgetUiState> = combine(
        billRepository.observeActive(),
        budgetRepository.observeActive(),
        categoryRepository.observeAll(),
    ) { bills, budgets, categories ->
        val monthStart = Time.startOfMonth(month)
        val monthEnd = Time.endOfMonthInclusive(month)
        val totalBudget = budgets.firstOrNull { it.categoryId == null }?.amountMinor
        val totalStatus = FinanceCalculator.budgetStatus(
            bills = bills,
            budgetAmountMinor = totalBudget,
            periodStart = monthStart,
            periodEnd = monthEnd,
        )
        val categoryRows = categories.map { category ->
            CategoryBudgetRow(
                category = category,
                amountMinor = budgets.firstOrNull { it.categoryId == category.id }?.amountMinor,
                spentMinor = FinanceCalculator.categorySpent(
                    bills = bills,
                    categoryId = category.id,
                    periodStart = monthStart,
                    periodEnd = monthEnd,
                ),
            )
        }.sortedWith(
            compareByDescending<CategoryBudgetRow> { it.amountMinor != null }
                .thenBy { it.category.sortOrder }
        )

        BudgetUiState(
            isLoading = false,
            monthLabel = Time.formatMonth(month),
            total = totalStatus,
            categories = categoryRows,
            totalBudgetMinor = totalBudget,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun saveTotal(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch {
                budgetRepository.clearTotal(month)
                _events.emit(UiEvent.Message("已清除本月总预算"))
            }
            return
        }
        val amount = Money.parseToMinor(trimmed, Money.BASE_CURRENCY)
        if (amount == null || amount <= 0L) {
            viewModelScope.launch { _events.emit(UiEvent.Message("请输入有效的预算金额")) }
            return
        }
        viewModelScope.launch {
            budgetRepository.setTotal(amount, month)
            _events.emit(UiEvent.Message("本月预算已更新为 ${Money.format(amount)}"))
        }
    }

    fun saveCategoryBudget(categoryId: Long, input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch {
                budgetRepository.setCategoryBudget(categoryId, null, month)
                _events.emit(UiEvent.Message("已清除该分类预算"))
            }
            return
        }
        val amount = Money.parseToMinor(trimmed, Money.BASE_CURRENCY)
        if (amount == null || amount <= 0L) {
            viewModelScope.launch { _events.emit(UiEvent.Message("请输入有效的预算金额")) }
            return
        }
        viewModelScope.launch {
            budgetRepository.setCategoryBudget(categoryId, amount, month)
            _events.emit(UiEvent.Message("分类预算已保存"))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BudgetViewModel() }
        }
    }
}