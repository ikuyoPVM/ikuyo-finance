package xyz.ikuyo.finance.feature.installment

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
import xyz.ikuyo.finance.data.repository.ConfirmOutcome
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Installment
import xyz.ikuyo.finance.domain.model.InstallmentPayment
import xyz.ikuyo.finance.domain.model.InstallmentProgress
import xyz.ikuyo.finance.domain.usecase.InstallmentSchedule
import java.time.LocalDate

/**
 * 分期页状态。
 *
 * 口径（阶段五计划书第 3 章）：
 * - 分期计划本身不是支出，只有「确认付款」才会写入一笔真实账单；
 * - 未付期数只是「未来待还」，不进余额、预算和统计，也不参与任何 AI 消费分析；
 * - 提前结束后剩余期数不再算待还，但已付账单原样保留。
 */
data class InstallmentUiState(
    val items: List<Installment> = emptyList(),
    val payments: List<InstallmentPayment> = emptyList(),
    val categories: List<Category> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val today: LocalDate = LocalDate.now(Time.zone),
) {

    /** 还在还的计划：未提前结束，且仍有未付期数。 */
    val active: List<Installment> get() = items.filter { it.isActive && !progressOf(it).isFinished }

    /** 已结清：全部付完，或提前结束。 */
    val finished: List<Installment> get() = items.filter { !it.isActive || progressOf(it).isFinished }

    val hasAny: Boolean get() = items.isNotEmpty()

    /** 所有在用计划的「未来待还」合计，仅用于展示，不进收支。 */
    val remainingTotalMinor: Long get() = active.sumOf { progressOf(it).remainingMinor }

    fun progressOf(installment: Installment): InstallmentProgress =
        InstallmentSchedule.progress(installment, payments)

    fun nextPeriodOf(installment: Installment) = progressOf(installment).nextPeriod

    /** 下一期在几天后到期；已结清或没有下一期时为 null。 */
    fun daysUntilNext(installment: Installment): Int? {
        val next = nextPeriodOf(installment) ?: return null
        return FixedDays.until(next.dueAt, today)
    }
}

/** 到期日与今天相差的天数（正数=未来，0=今天，负数=已过期）。 */
private object FixedDays {
    fun until(dueAt: Long, today: LocalDate): Int {
        val due = Time.toLocalDate(dueAt).toEpochDay()
        return (due - today.toEpochDay()).toInt()
    }
}

class InstallmentViewModel : ViewModel() {

    private val repository = Graph.installmentRepository
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

    val state: StateFlow<InstallmentUiState> =
        combine(
            withCategories,
            busy,
            error,
            notice,
        ) { (items, payments, categories), working, message, hint ->
            InstallmentUiState(
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
            initialValue = InstallmentUiState(),
        )

    fun save(installment: Installment) = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { repository.save(installment).getOrThrow() }
                .onSuccess { notice.value = "分期计划已保存。" }
                .onFailure { error.value = it.message ?: "保存失败" }
            busy.value = false
        }
    }

    /**
     * 确认某一期付款：生成真实支出账单 + 记一条付款记录。
     *
     * 同一期重复点击只会有一笔账单，界面用 notice 告知「这一期已经付过了」。
     */
    fun confirmPeriod(installment: Installment, periodIndex: Int, amountMinor: Long, note: String = "") = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            notice.value = null
            runCatching {
                repository.confirmPeriod(
                    installment = installment,
                    periodIndex = periodIndex,
                    amountMinor = amountMinor,
                    note = note,
                ).getOrThrow()
            }.onSuccess { outcome ->
                val period = periodIndex + 1
                notice.value = when (outcome) {
                    is ConfirmOutcome.Created ->
                        "已生成账单，${installment.name} 第 $period 期记为已付。"
                    is ConfirmOutcome.AlreadyPaid ->
                        "第 $period 期已经付过了（付款时间 ${Time.formatDateTime(outcome.paidAt)}），" +
                            "没有重复生成账单。"
                }
            }.onFailure { error.value = it.message ?: "确认付款失败" }
            busy.value = false
        }
    }

    fun setEnabled(installment: Installment, enabled: Boolean) = run {
        viewModelScope.launch {
            error.value = null
            runCatching { repository.setEnabled(installment.id, enabled).getOrThrow() }
                .onSuccess { notice.value = if (enabled) "已恢复提醒。" else "已暂停，剩余期数仍算未来待还。" }
                .onFailure { error.value = it.message ?: "更新失败" }
        }
    }

    fun finishEarly(installment: Installment, extraFeeMinor: Long = 0L) = run {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            notice.value = null
            runCatching { repository.finishEarly(installment, extraFeeMinor = extraFeeMinor).getOrThrow() }
                .onSuccess {
                    notice.value = if (extraFeeMinor > 0L) {
                        "已提前结束，并记录了 ${Money.format(extraFeeMinor)} 结清费用。"
                    } else {
                        "已提前结束，剩余期数不再算未来待还。"
                    }
                }
                .onFailure { error.value = it.message ?: "提前结束失败" }
            busy.value = false
        }
    }

    fun delete(installment: Installment) = run {
        viewModelScope.launch {
            error.value = null
            runCatching { repository.delete(installment.id) }
                .onSuccess { notice.value = "已删除分期计划（已生成的账单不受影响）。" }
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
            initializer { InstallmentViewModel() }
        }
    }
}