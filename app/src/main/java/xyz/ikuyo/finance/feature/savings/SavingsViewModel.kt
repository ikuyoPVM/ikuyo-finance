package xyz.ikuyo.finance.feature.savings

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
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.SavingsEntry
import xyz.ikuyo.finance.domain.model.SavingsGoal
import xyz.ikuyo.finance.domain.model.SavingsGoalStatus
import xyz.ikuyo.finance.domain.model.SavingsProgress
import xyz.ikuyo.finance.domain.usecase.SavingsLedger
import java.time.LocalDate

data class SavingsUiState(
    val goals: List<SavingsGoal> = emptyList(),
    val entries: List<SavingsEntry> = emptyList(),
    val categories: List<Category> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val today: LocalDate = LocalDate.now(Time.zone),
) {

    /** 储蓄余额：所有目标合计，不含账单（储蓄是资金划转）。 */
    val totalSavedMinor: Long get() = SavingsLedger.balance(entries)

    val hasAny: Boolean get() = goals.isNotEmpty()

    val active: List<SavingsGoal> get() = goals.filter { it.status == SavingsGoalStatus.ACTIVE }

    val paused: List<SavingsGoal> get() = goals.filter { it.status == SavingsGoalStatus.PAUSED }

    val closed: List<SavingsGoal>
        get() = goals.filter {
            it.status == SavingsGoalStatus.COMPLETED || it.status == SavingsGoalStatus.CANCELLED
        }

    val openGoals: List<SavingsGoal> get() = goals.filter { it.isOpen }

    fun progressOf(goal: SavingsGoal): SavingsProgress =
        SavingsLedger.progressOf(goal, entries, today)

    fun suggestedMonthly(goal: SavingsGoal): Long? = SavingsLedger.requiredMonthly(goal, entries, today)
}

class SavingsViewModel : ViewModel() {

    private val repository = Graph.savingsRepository
    private val categoryRepository = Graph.categoryRepository

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private val base = combine(
        repository.observeGoals(),
        repository.observeEntries(),
        categoryRepository.observeAll(),
    ) { goals, entries, categories -> Triple(goals, entries, categories) }

    val state: StateFlow<SavingsUiState> = combine(
        base,
        busy,
        error,
        notice,
    ) { (goals, entries, categories), isBusy, message, tip ->
        SavingsUiState(
            goals = goals,
            entries = entries,
            categories = categories,
            busy = isBusy,
            error = message,
            notice = tip,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SavingsUiState(),
    )

    fun clearError() {
        error.value = null
    }

    fun clearNotice() {
        notice.value = null
    }

    fun save(goal: SavingsGoal) = run(
        success = { if (goal.id > 0L) "目标已更新" else "目标已创建" }
    ) {
        repository.save(goal)
    }

    fun deposit(goal: SavingsGoal, amountMinor: Long, note: String = "") = run(
        success = { "已存入，储蓄余额增加但总余额不变（不计支出）" }
    ) {
        repository.deposit(goal, amountMinor, note = note)
    }

    fun withdraw(goal: SavingsGoal, amountMinor: Long, note: String = "") = run(
        success = { "已取出，储蓄余额减少但总余额不变（不计收入）" }
    ) {
        repository.withdraw(goal, amountMinor, note = note)
    }

    fun transfer(from: SavingsGoal, to: SavingsGoal, amountMinor: Long) = run(
        success = { "已在目标之间划转，总储蓄余额不变" }
    ) {
        repository.transfer(from, to, amountMinor)
    }

    fun setStatus(goal: SavingsGoal, status: SavingsGoalStatus) = run(
        success = { if (status == SavingsGoalStatus.PAUSED) "目标已暂停" else "目标已恢复" }
    ) {
        repository.setStatus(goal, status)
    }

    fun delete(goal: SavingsGoal) = run(success = { "目标已删除" }) {
        repository.delete(goal)
    }

    private fun <T> run(success: (T) -> String, block: suspend () -> Result<T>) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { block().getOrThrow() }
                .onSuccess { notice.value = success(it) }
                .onFailure { error.value = it.message ?: "操作失败，请重试" }
            busy.value = false
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SavingsViewModel() }
        }
    }
}