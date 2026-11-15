package xyz.ikuyo.finance.feature.debt

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
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.repository.RepayOutcome
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Debt
import xyz.ikuyo.finance.domain.model.DebtPayment
import xyz.ikuyo.finance.domain.model.DebtProgress
import xyz.ikuyo.finance.domain.usecase.DebtLedger
import java.time.LocalDate

/**
 * 债务页状态（阶段五第 4 步）。
 *
 * 口径：本金流转不进出收支，所以这里的「剩余欠款 / 待收回」只是余额视图，
 * 不参与本月支出、预算与统计；只有利息与手续费会以账单形式落进账本。
 */
data class DebtUiState(
    val items: List<Debt> = emptyList(),
    val payments: List<DebtPayment> = emptyList(),
    val categories: List<Category> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val today: LocalDate = LocalDate.now(Time.zone),
) {

    private val ledger: DebtLedger.Totals get() = DebtLedger.totalsOf(items, payments)

    /** 借入总额。 */
    val borrowedTotalMinor: Long get() = ledger.borrowedTotalMinor

    /** 已偿还本金。 */
    val repaidMinor: Long get() = ledger.repaidPrincipalMinor

    /** 剩余欠款。 */
    val remainingOwedMinor: Long get() = ledger.remainingOwedMinor

    /** 借出总额。 */
    val lentTotalMinor: Long get() = ledger.lentTotalMinor

    /** 待收回本金。 */
    val toReceiveMinor: Long get() = ledger.toReceiveMinor

    /** 净债务 = 剩余欠款 − 待收回。 */
    val netDebtMinor: Long get() = ledger.netDebtMinor

    /** 已付利息与手续费（会出现在账本里）。 */
    val extraPaidMinor: Long get() = ledger.extraPaidMinor

    /** 已收利息（会作为收入出现在账本里）。 */
    val extraReceivedMinor: Long get() = ledger.extraReceivedMinor

    val hasAny: Boolean get() = items.isNotEmpty()

    /** 未结清的欠款与借出，界面按方向分两组。 */
    val openOwed: List<Debt> get() = items.filter { it.type.isOwed && !progressOf(it).isSettled }
    val openLent: List<Debt> get() = items.filter { !it.type.isOwed && !progressOf(it).isSettled }
    val settled: List<Debt> get() = items.filter { progressOf(it).isSettled }

    fun progressOf(debt: Debt): DebtProgress = DebtLedger.progressOf(debt, payments)

    /** 到期还剩几天；已结清返回 null。 */
    fun daysUntil(debt: Debt): Int? =
        if (progressOf(debt).isSettled) null else DebtLedger.daysUntil(debt.dueAt, today)
}

class DebtViewModel : ViewModel() {

    private val repository = Graph.debtRepository
    private val categoryRepository = Graph.categoryRepository

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private val data = combine(
        repository.observeAll(),
        repository.observePayments(),
    ) { items, payments -> items to payments }

    private val withCategories = combine(
        data,
        categoryRepository.observeAll(),
    ) { pair, categories -> Triple(pair.first, pair.second, categories) }

    val state: StateFlow<DebtUiState> =
        combine(
            withCategories,
            busy,
            error,
            notice,
        ) { (items, payments, categories), working, message, hint ->
            DebtUiState(
                items = items,
                payments = payments,
                categories = categories,
                busy = working,
                error = message,
                notice = hint,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DebtUiState(),
        )

    fun save(debt: Debt) = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { repository.save(debt).getOrThrow() }
                .onSuccess { notice.value = "债务已保存。" }
                .onFailure { error.value = it.message ?: "保存失败" }
            busy.value = false
        }
    }

    /**
     * 记一次还款（欠款方向）或收回（借出方向）。
     *
     * 本金不生成账单，利息与手续费才生成；重复提交同一时间点只落一次。
     */
    fun repay(
        debt: Debt,
        principalMinor: Long,
        extraMinor: Long = 0L,
        note: String = "",
    ) = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            notice.value = null
            runCatching { repository.repay(debt, principalMinor, extraMinor, note = note).getOrThrow() }
                .onSuccess { outcome ->
                    notice.value = when (outcome) {
                        is RepayOutcome.Duplicate -> "这个时间点已经记过一次，没有重复记账。"
                        is RepayOutcome.Created -> successNotice(debt, principalMinor, extraMinor, outcome)
                    }
                }
                .onFailure { error.value = it.message ?: "记录失败" }
            busy.value = false
        }
    }

    /** 一次性结清剩余本金。 */
    fun settleNow(debt: Debt, extraMinor: Long = 0L) = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            notice.value = null
            runCatching { repository.settleNow(debt, extraMinor = extraMinor).getOrThrow() }
                .onSuccess { outcome ->
                    notice.value = when (outcome) {
                        is RepayOutcome.Duplicate -> "这个时间点已经记过一次，没有重复记账。"
                        is RepayOutcome.Created -> buildString {
                            append("已结清")
                            if (debt.type.isOwed) append("，本金不计支出") else append("，本金不计收入")
                            if (extraMinor > 0L) append("；利息 ${Money.format(extraMinor)} 已生成账单")
                            append("。")
                        }
                    }
                }
                .onFailure { error.value = it.message ?: "结清失败" }
            busy.value = false
        }
    }

    fun setEnabled(debt: Debt, enabled: Boolean) = run {
        viewModelScope.launch {
            error.value = null
            runCatching { repository.setEnabled(debt.id, enabled).getOrThrow() }
                .onSuccess { notice.value = if (enabled) "已恢复提醒。" else "已暂停提醒。" }
                .onFailure { error.value = it.message ?: "更新失败" }
        }
    }

    fun delete(debt: Debt) = run {
        viewModelScope.launch {
            error.value = null
            runCatching { repository.delete(debt.id) }
                .onSuccess { notice.value = "已删除（利息账单不受影响）。" }
                .onFailure { error.value = it.message ?: "删除失败" }
        }
    }

    fun clearError() {
        error.value = null
    }

    fun clearNotice() {
        notice.value = null
    }

    private fun successNotice(
        debt: Debt,
        principalMinor: Long,
        extraMinor: Long,
        outcome: RepayOutcome.Created,
    ): String = buildString {
        val verb = if (debt.type.isOwed) "已记录还款" else "已记录收回"
        append(verb).append(' ').append(Money.format(principalMinor))
        if (debt.type.isOwed) append("（本金不计支出）") else append("（本金不计收入）")
        if (extraMinor > 0L) {
            append("；利息 ").append(Money.format(extraMinor))
                .append(if (debt.type.isOwed) " 已记为支出" else " 已记为收入")
        }
        if (outcome.settled) append("；这笔债务已结清")
        append('。')
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DebtViewModel() }
        }
    }
}