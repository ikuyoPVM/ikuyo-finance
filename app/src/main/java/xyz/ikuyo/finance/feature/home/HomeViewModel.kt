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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.BudgetAlertRepository
import xyz.ikuyo.finance.data.repository.BudgetRepository
import xyz.ikuyo.finance.data.repository.CategoryRepository
import xyz.ikuyo.finance.data.repository.HomeLayoutRepository
import xyz.ikuyo.finance.data.repository.SavingsRepository
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.HomeLayout
import xyz.ikuyo.finance.domain.model.HomeModule
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase
import xyz.ikuyo.finance.domain.usecase.BudgetPeriods
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator
import java.time.LocalDate
import java.time.YearMonth

class HomeViewModel(
    private val billRepository: BillRepository,
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
    private val addBillUseCase: AddBillUseCase,
    private val budgetAlertRepository: BudgetAlertRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    private val tagRepository: TagRepository,
    private val savingsRepository: SavingsRepository,
) : ViewModel() {

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** 最近一次新增的账单，用于 Snackbar 撤销。 */
    private var lastInsertedId: Long? = null
    private var saving: Boolean = false

    /** 把「排序 / 上次支付方式 / 首页布局」三个轻量偏好打成一个包，避免 combine 超参数上限。 */
    private val preferences = combine(
        settingsRepository.sortOrder,
        settingsRepository.lastPaymentMethod,
        homeLayoutRepository.layout,
        savingsRepository.balance,
    ) { sortOrder, lastPayment, layout, savings ->
        Preferences(sortOrder, lastPayment, layout, savings)
    }

    val state: StateFlow<HomeUiState> = combine(
        billRepository.observeActive(),
        budgetRepository.observeAll(),
        categoryRepository.observeAll(),
        tagRepository.observeAll(),
        preferences,
    ) { bills, budgets, categories, tags, prefs ->
        buildState(bills, budgets, categories, tags, prefs)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        observeBudgetAlerts()
    }

    /**
     * 预算预警（计划书 8.5）：同一周期、同一金额、同一级别只提醒一次。
     * 去重键包含周期起点与额度，因此调整预算会重新计算，新周期开始后自动重置。
     */
    private fun observeBudgetAlerts() {
        viewModelScope.launch {
            combine(
                billRepository.observeActive(),
                budgetRepository.observeAll(),
            ) { bills, budgets ->
                val today = LocalDate.now(Time.zone)
                val total = budgets.firstOrNull { it.scope == BudgetScope.TOTAL }
                    ?: return@combine null
                if (total.amountMinor <= 0L) return@combine null
                val window = BudgetPeriods.windowOf(total, today)
                val status = FinanceCalculator.budgetStatusOf(bills, total, window, today)
                if (!status.alertLevel.needsAttention) return@combine null
                AlertCandidate(
                    key = BudgetAlertRepository.keyOf(
                        scope = BudgetScope.TOTAL,
                        categoryId = null,
                        windowStart = window.start,
                        amountMinor = status.availableMinor,
                        level = status.alertLevel,
                    ),
                    text = alertText(status),
                )
            }
                .distinctUntilChanged { old, new -> old?.key == new?.key }
                .collect { candidate ->
                    if (candidate == null) return@collect
                    if (budgetAlertRepository.isAcknowledged(candidate.key)) return@collect
                    budgetAlertRepository.acknowledge(candidate.key)
                    _events.emit(UiEvent.Message(candidate.text))
                }
        }
    }

    private fun alertText(status: BudgetStatus): String {
        val scope = status.windowLabel.ifBlank { "本周期" }
        return when (status.alertLevel) {
            BudgetAlertLevel.OVER -> "$scope 预算已超支 ${Money.format(-status.remainingMinor)}"
            BudgetAlertLevel.FULL -> "$scope 预算额度已用完（已用 ${status.usedPercent}%）"
            BudgetAlertLevel.EIGHTY,
            BudgetAlertLevel.HALF,
            -> "$scope 预算已用 ${status.usedPercent}%，剩余 ${Money.format(status.remainingMinor)}"

            BudgetAlertLevel.NORMAL -> ""
        }
    }

    private data class Preferences(
        val sortOrder: BillSortOrder,
        val lastPayment: PaymentMethod?,
        val layout: HomeLayout,
        val savingsMinor: Long,
    )

    private fun buildState(
        bills: List<Bill>,
        budgets: List<Budget>,
        categories: List<Category>,
        tags: List<Tag>,
        prefs: Preferences,
    ): HomeUiState {
        val sortOrder = prefs.sortOrder
        val today = LocalDate.now(Time.zone)
        val month = YearMonth.from(today)
        val totalBudget = budgets.firstOrNull { it.scope == BudgetScope.TOTAL }
        val window = totalBudget?.let { BudgetPeriods.windowOf(it, today) }
            ?: BudgetPeriods.window(BudgetPeriodType.MONTHLY, today)
        val budgetStatus = if (totalBudget != null) {
            FinanceCalculator.budgetStatusOf(bills, totalBudget, window, today)
        } else {
            FinanceCalculator.budgetStatus(
                bills = bills,
                budgetAmountMinor = null,
                periodStart = window.start,
                periodEnd = window.endInclusive,
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

        val stats = FinanceCalculator.homeStats(bills, today)
        val trendBills = stats.dailyTrend.associate { point ->
            val day = LocalDate.ofEpochDay(point.epochDay)
            day to bills
                .filter { Time.toLocalDate(it.occurredAt) == day }
                .sortedByDescending { it.occurredAt }
        }

        return HomeUiState(
            isLoading = false,
            monthLabel = Time.formatMonth(month),
            sortOrder = sortOrder,
            balance = FinanceCalculator.balance(bills, prefs.savingsMinor),
            stats = stats,
            budget = budgetStatus,
            recentBills = sorted.take(5),
            categories = categories,
            tags = tags,
            lastPaymentMethod = prefs.lastPayment,
            layout = prefs.layout,
            trendBills = trendBills,
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

fun moveModuleUp(module: HomeModule) = updateLayout { it.moveUp(module) }

    fun moveModuleDown(module: HomeModule) = updateLayout { it.moveDown(module) }

    fun toggleModuleVisible(module: HomeModule) = updateLayout { it.toggleHidden(module) }

    fun toggleModuleCollapsed(module: HomeModule) = updateLayout { it.toggleCollapsed(module) }

    fun resetLayout() {
        viewModelScope.launch {
            homeLayoutRepository.reset()
            _events.emit(UiEvent.Message("首页布局已恢复默认"))
        }
    }

    private fun updateLayout(transform: (HomeLayout) -> HomeLayout) {
        viewModelScope.launch { homeLayoutRepository.update(transform(state.value.layout)) }
    }

    private data class AlertCandidate(val key: String, val text: String)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    billRepository = Graph.billRepository,
                    budgetRepository = Graph.budgetRepository,
                    categoryRepository = Graph.categoryRepository,
                    settingsRepository = Graph.settingsRepository,
                    addBillUseCase = Graph.addBillUseCase,
                    budgetAlertRepository = Graph.budgetAlertRepository,
                    homeLayoutRepository = Graph.homeLayoutRepository,
                    tagRepository = Graph.tagRepository,
                    savingsRepository = Graph.savingsRepository,
                )
            }
        }
    }
}