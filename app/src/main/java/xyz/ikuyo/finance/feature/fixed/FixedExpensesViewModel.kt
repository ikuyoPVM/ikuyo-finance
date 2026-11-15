package xyz.ikuyo.finance.feature.fixed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.repository.ConfirmOutcome
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.FixedExpense
import xyz.ikuyo.finance.domain.model.FixedExpensePayment
import xyz.ikuyo.finance.domain.usecase.FixedExpenseSchedule
import java.time.LocalDate

/**
 * 固定支出页状态。
 *
 * 核心约束（阶段五计划书第 2 章）：
 * - 计划金额不是已发生支出，只有「确认付款」才会写一条真实账单；
 * - 分组按「已到期 / 本周 / 以后 / 已暂停」给出，用户先处理到期的；
 * - 浮动金额的计划在确认时可以改金额，历史付款记录保留每次的实际金额。
 */
data class FixedExpensesUiState(
    val items: List<FixedExpense> = emptyList(),
    val payments: List<FixedExpensePayment> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** 非报错提示，例如「这一期已经付过了」。 */
    val notice: String? = null,
    /** 提前提醒天数（0 = 只有到期当天提醒）。 */
    val remindDays: Int = SettingsRepository.DEFAULT_FIXED_REMIND_DAYS,
    val today: LocalDate = LocalDate.now(Time.zone),
) {
    val overdue: List<FixedExpense>
        get() = items.filter { it.enabled && FixedExpenseSchedule.daysUntil(it.dueAt, today) <= 0 }

    val thisWeek: List<FixedExpense>
        get() = items.filter {
            val days = FixedExpenseSchedule.daysUntil(it.dueAt, today)
            it.enabled && days in 1..7
        }

    val later: List<FixedExpense>
        get() = items.filter {
            it.enabled && FixedExpenseSchedule.daysUntil(it.dueAt, today) > 7
        }

    val paused: List<FixedExpense>
        get() = items.filter { !it.enabled }

    val hasAny: Boolean get() = items.isNotEmpty()

    /**
     * 需要提醒的计划：已到期，或已进入用户设置的提前提醒窗口。
     *
     * 提醒天数只决定「什么时候开始提醒」，不改变分组与到期判断，
     * 所以把提醒调到 0 天也不会让今天的到期计划从「已到期」里消失。
     */
    val reminders: List<FixedExpense>
        get() = items.filter {
            it.enabled && FixedExpenseSchedule.shouldRemind(it.dueAt, remindDays, today)
        }

    /** 到期或即将到期需要处理的笔数，用于入口角标与顶部提示。 */
    val pendingCount: Int get() = reminders.size

    fun daysOf(expense: FixedExpense): Int = FixedExpenseSchedule.daysUntil(expense.dueAt, today)
}

class FixedExpensesViewModel : ViewModel() {

    private val repository = Graph.fixedExpenseRepository
    private val settingsRepository = Graph.settingsRepository

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    /** 计划与付款记录先合成一组，避免 combine 超参数上限。 */
    private val data = combine(
        repository.observeAll(),
        repository.observePayments(),
    ) { items, payments -> items to payments }

    val state: StateFlow<FixedExpensesUiState> =
        combine(
            data,
            busy,
            error,
            notice,
            settingsRepository.fixedReminderDays,
        ) { (items, payments), working, message, hint, remindDays ->
            FixedExpensesUiState(
                items = items,
                payments = payments,
                busy = working,
                error = message,
                notice = hint,
                remindDays = remindDays,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FixedExpensesUiState(),
        )

    fun save(expense: FixedExpense) = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { repository.save(expense).getOrThrow() }
                .onFailure { error.value = it.message ?: "保存失败" }
            busy.value = false
        }
    }

    /**
     * 确认付款：生成真实账单并把计划推进到下一期。
     *
     * @param amountMinor 浮动金额的计划由用户在这里给出实际金额。
     */
    fun confirmPayment(expense: FixedExpense, amountMinor: Long, note: String = "") = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            notice.value = null
            runCatching { repository.confirmPayment(expense, amountMinor = amountMinor, note = note).getOrThrow() }
                .onSuccess { outcome ->
                    notice.value = when (outcome) {
                        is ConfirmOutcome.Created ->
                            "已生成账单，${expense.name} 已推进到下一期。"
                        is ConfirmOutcome.AlreadyPaid ->
                            "这一期已经付过了（付款时间 ${Time.formatDateTime(outcome.paidAt)}），" +
                                "没有重复生成账单，到期日也没有变。"
                    }
                }
                .onFailure { error.value = it.message ?: "确认付款失败" }
            busy.value = false
        }
    }

    /**
     * 标记本期未付款：不生成账单、不记支出，只把计划推进到下一期。
     */
    fun markUnpaid(expense: FixedExpense) = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            notice.value = null
            runCatching { repository.skipPeriod(expense).getOrThrow() }
                .onSuccess { next ->
                    notice.value = "已标记这一期未付款，没有生成账单，${expense.name} 的到期日改到 " +
                        Time.formatMonthDay(next) + "。"
                }
                .onFailure { error.value = it.message ?: "标记未付款失败" }
            busy.value = false
        }
    }

    fun setReminderDays(days: Int) = run {
        viewModelScope.launch {
            runCatching { settingsRepository.setFixedReminderDays(days) }
                .onFailure { error.value = it.message ?: "保存提醒设置失败" }
        }
    }

    fun setEnabled(expense: FixedExpense, enabled: Boolean) = run {
        viewModelScope.launch {
            runCatching { repository.setEnabled(expense.id, enabled).getOrThrow() }
                .onFailure { error.value = it.message ?: "更新失败" }
        }
    }

    fun delete(expense: FixedExpense) = run {
        viewModelScope.launch {
            error.value = null
            runCatching { repository.delete(expense.id) }
                .onFailure { error.value = it.message ?: "删除失败" }
        }
    }

    fun clearError() {
        error.value = null
    }

    fun clearNotice() {
        notice.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { FixedExpensesViewModel() }
        }
    }
}