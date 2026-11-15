package xyz.ikuyo.finance.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.BudgetRepository
import xyz.ikuyo.finance.data.repository.CategoryRepository
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator
import java.time.LocalDate
import java.time.YearMonth

class HomeViewModel(
    private val billRepository: BillRepository,
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
    private val addBillUseCase: AddBillUseCase,
) : ViewModel() {

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** 最近一次新增的账单，用于 Snackbar 撤销。 */
    private var lastInsertedId: Long? = null
    private var saving: Boolean = false

    val state: StateFlow<HomeUiState> = combine(
        billRepository.observeActive(),
        budgetRepository.observeActive(),
        categoryRepository.observeAll(),
        settingsRepository.sortOrder,
        settingsRepository.lastPaymentMethod,
    ) { bills, budgets, categories, sortOrder, lastPayment ->
        buildState(bills, budgets, categories, sortOrder, lastPayment)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    private fun buildState(
        bills: List<Bill>,
        budgets: List<Budget>,
        categories: List<Category>,
        sortOrder: BillSortOrder,
        lastPayment: PaymentMethod?,
    ): HomeUiState {
        val today = LocalDate.now(Time.zone)
        val month = YearMonth.from(today)
        val totalBudget = budgets.firstOrNull { it.scope == BudgetScope.TOTAL }
        val budgetStatus = if (totalBudget != null) {
            FinanceCalculator.budgetStatus(
                bills = bills,
                budgetAmountMinor = totalBudget.amountMinor,
                periodStart = totalBudget.periodStart,
                periodEnd = totalBudget.periodEnd,
                today = today,
            )
        } else {
            FinanceCalculator.budgetStatus(
                bills = bills,
                budgetAmountMinor = null,
                periodStart = Time.startOfMonth(month),
                periodEnd = Time.endOfMonthInclusive(month),
                today = today,
            )
        }

        val sorted = when (sortOrder) {
            BillSortOrder.OCCURRED_DESC -> bills.sortedWith(
                compareByDescending<Bill> { it.occurredAt }.thenByDescending { it.id }
            )

            BillSortOrder.CREATED_DESC -> bills.sortedWith(
                compareByDescending<Bill> { it.createdAt }.thenByDescending { it.id }
            )
        }

        return HomeUiState(
            isLoading = false,
            monthLabel = Time.formatMonth(month),
            sortOrder = sortOrder,
            balance = FinanceCalculator.balance(bills),
            stats = FinanceCalculator.homeStats(bills, today),
            budget = budgetStatus,
            recentBills = sorted.take(5),
            categories = categories,
            lastPaymentMethod = lastPayment,
        )
    }

    fun addBill(draft: BillDraft) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            val result = addBillUseCase(draft)
            saving = false
            result.onSuccess { id ->
                lastInsertedId = id
                settingsRepository.setLastPaymentMethod(draft.paymentMethod)
                _events.emit(
                    UiEvent.Message(
                        text = "已记录 ${Money.format(draft.amountMinor, draft.currency)}",
                        undoable = true,
                    )
                )
            }.onFailure { error ->
                _events.emit(UiEvent.Message(error.message ?: "保存失败，请重试"))
            }
        }
    }

    fun undoLastInsert() {
        val id = lastInsertedId ?: return
        lastInsertedId = null
        viewModelScope.launch {
            billRepository.purge(id)
            _events.emit(UiEvent.Message("已撤销上一笔"))
        }
    }

    fun deleteBill(id: Long) {
        viewModelScope.launch {
            billRepository.softDelete(id)
            _events.emit(UiEvent.Message("已移入回收站"))
        }
    }

    fun setSortOrder(order: BillSortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    billRepository = Graph.billRepository,
                    budgetRepository = Graph.budgetRepository,
                    categoryRepository = Graph.categoryRepository,
                    settingsRepository = Graph.settingsRepository,
                    addBillUseCase = Graph.addBillUseCase,
                )
            }
        }
    }
}