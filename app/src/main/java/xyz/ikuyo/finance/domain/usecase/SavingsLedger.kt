package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.SavingsEntry
import xyz.ikuyo.finance.domain.model.SavingsEntryType
import xyz.ikuyo.finance.domain.model.SavingsGoal
import xyz.ikuyo.finance.domain.model.SavingsGoalStatus
import xyz.ikuyo.finance.domain.model.SavingsProgress
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 储蓄目标的纯计算层。
 *
 * 口径（计划书第 5 章）：
 * - 储蓄是资金划转，不是支出：存入减少可用余额、增加储蓄余额，取出相反，总余额不变。
 * - 储蓄存入/取出一律不计普通收入或消费支出，所以这里只按流水汇总储蓄余额，不碰账单。
 * - 取出允许随时进行，只校验金额不超过该目标已存金额。
 */
object SavingsLedger {

    /** 全部储蓄余额（所有目标合计）：流入减流出。 */
    fun balance(entries: List<SavingsEntry>): Long =
        entries.sumOf { if (it.type.isInflow) it.amountMinor else -it.amountMinor }

    /** 某个目标当前的储蓄金额。 */
    fun savedOf(goalId: Long, entries: List<SavingsEntry>): Long =
        balance(entries.filter { it.goalId == goalId })

    fun progressOf(
        goal: SavingsGoal,
        entries: List<SavingsEntry>,
        today: LocalDate = LocalDate.now(Time.zone),
    ): SavingsProgress {
        val saved = savedOf(goal.id, entries)
        val days = daysUntil(goal.dueAt, today)
        return SavingsProgress(
            goalId = goal.id,
            targetMinor = goal.targetMinor,
            savedMinor = saved,
            daysLeft = days,
            isOverdue = days < 0 && saved < goal.targetMinor,
        )
    }

    /** 距离截止日还有几天：今天 0，逾期为负。用 epochDay 差，不受跨时区偏移影响。 */
    fun daysUntil(dueAt: Long, today: LocalDate = LocalDate.now(Time.zone)): Int =
        (Time.toLocalDate(dueAt).toEpochDay() - today.toEpochDay()).toInt()

    fun validate(goal: SavingsGoal): String? = when {
        goal.name.isBlank() -> "请填写目标名称"
        goal.targetMinor <= 0L -> "目标金额必须大于 0"
        goal.dueAt <= 0L -> "请选择截止日期"
        else -> null
    }

    /** 存/取的金额校验。金额为分，必须为正。 */
    fun validateMovement(
        goal: SavingsGoal,
        entries: List<SavingsEntry>,
        type: SavingsEntryType,
        amountMinor: Long,
    ): String? {
        if (amountMinor <= 0L) return "金额必须大于 0"
        if (goal.status == SavingsGoalStatus.CANCELLED) return "目标已取消，无法再操作"
        if (type == SavingsEntryType.DEPOSIT && goal.status == SavingsGoalStatus.PAUSED) {
            return "目标已暂停，恢复后才能继续存入"
        }
        if (type == SavingsEntryType.WITHDRAW) {
            val saved = savedOf(goal.id, entries)
            if (amountMinor > saved) {
                return "取出金额超过已存金额（已存 ${Money.format(saved)}）"
            }
        }
        return null
    }

    fun validateTransfer(
        from: SavingsGoal,
        to: SavingsGoal,
        entries: List<SavingsEntry>,
        amountMinor: Long,
    ): String? {
        if (from.id == to.id) return "不能转到同一个目标"
        if (amountMinor <= 0L) return "金额必须大于 0"
        if (!from.status.isOpen || !to.status.isOpen) return "已关闭的目标不能划转"
        val saved = savedOf(from.id, entries)
        if (amountMinor > saved) {
            return "「${from.name}」可转出金额不足（已存 ${Money.format(saved)}）"
        }
        return null
    }

    /**
     * 按剩余时间摊平的每月建议存入金额；没有剩余时间或已达成时返回 null。
     * 只用于展示提示，AI 推荐金额也要走这里，不要各算一套。
     */
    fun requiredMonthly(
        goal: SavingsGoal,
        entries: List<SavingsEntry>,
        today: LocalDate = LocalDate.now(Time.zone),
    ): Long? {
        val saved = savedOf(goal.id, entries)
        val remaining = goal.targetMinor - saved
        if (remaining <= 0L) return null
        val days = daysUntil(goal.dueAt, today)
        val months = monthsBetween(today, Time.toLocalDate(goal.dueAt))
        return when {
            days <= 0 -> remaining
            months <= 0L -> remaining
            else -> ceilDiv(remaining, months)
        }
    }

    /** 目标之间按月数差：不足一个月按 1 个月算，避免出现除以 0。 */
    fun monthsBetween(from: LocalDate, to: LocalDate): Long {
        val days = ChronoUnit.DAYS.between(from, to)
        if (days <= 0L) return 0L
        return ceilDiv(days, 30L)
    }

    private fun ceilDiv(value: Long, divisor: Long): Long =
        if (divisor <= 0L) value else (value + divisor - 1L) / divisor
}