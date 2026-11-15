package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Debt
import xyz.ikuyo.finance.domain.model.DebtPayment
import xyz.ikuyo.finance.domain.model.DebtProgress
import java.time.LocalDate
import java.time.YearMonth

/**
 * 债务与借出的算术与口径（阶段五第 4 步）。
 *
 * 计划书第 4.3 章的记账规则在这里落成三条断言：
 * 1. 本金不动收支：借入、归还、借出、收回本金都不生成账单；
 * 2. 利息与手续费才是收支：欠款方向付出去算支出，借出方向收进来算收入；
 * 3. 所有本金流转都必须挂在某笔债务上，靠 [DebtPayment.debtId] 保证不重复统计。
 *
 * 这个文件只做算术，方便单独测，不碰数据库也不碰 AI。
 */
object DebtLedger {

    /** 借入总额 / 已偿还 / 剩余欠款 / 借出总额 / 待收回 / 净债务。 */
    data class Totals(
        val borrowedTotalMinor: Long = 0L,
        val repaidPrincipalMinor: Long = 0L,
        val remainingOwedMinor: Long = 0L,
        val lentTotalMinor: Long = 0L,
        val receivedPrincipalMinor: Long = 0L,
        val toReceiveMinor: Long = 0L,
        /** 欠款方向已经付出去的利息与手续费。 */
        val extraPaidMinor: Long = 0L,
        /** 借出方向已经收到的利息。 */
        val extraReceivedMinor: Long = 0L,
    ) {
        /** 净债务 = 还欠的 − 待收回的；为负表示「别人欠我更多」。 */
        val netDebtMinor: Long get() = remainingOwedMinor - toReceiveMinor

        val hasData: Boolean get() = borrowedTotalMinor > 0L || lentTotalMinor > 0L
    }

    /** 单笔债务的进度。 */
    fun progressOf(debt: Debt, payments: List<DebtPayment>): DebtProgress {
        val rows = payments.filter { it.debtId == debt.id }
        val repaid = rows.sumOf { it.principalMinor }.coerceAtMost(debt.principalMinor)
        return DebtProgress(
            debtId = debt.id,
            type = debt.type,
            principalMinor = debt.principalMinor,
            repaidPrincipalMinor = repaid,
            extraPaidMinor = rows.sumOf { it.extraMinor },
            lastPaidAt = rows.maxOfOrNull { it.paidAt },
            isSettled = debt.isSettled || repaid >= debt.principalMinor,
        )
    }

    /** 汇总所有债务：只看未结清的本金余额，结清的历史仍计入「已偿还」。 */
    fun totalsOf(debts: List<Debt>, payments: List<DebtPayment>): Totals {
        var borrowed = 0L
        var repaid = 0L
        var remainingOwed = 0L
        var lent = 0L
        var received = 0L
        var toReceive = 0L
        var extraPaid = 0L
        var extraReceived = 0L
        debts.forEach { debt ->
            val progress = progressOf(debt, payments)
            if (debt.type.isOwed) {
                borrowed += debt.principalMinor
                repaid += progress.repaidPrincipalMinor
                remainingOwed += progress.remainingPrincipalMinor
                extraPaid += progress.extraPaidMinor
            } else {
                lent += debt.principalMinor
                received += progress.repaidPrincipalMinor
                toReceive += progress.remainingPrincipalMinor
                extraReceived += progress.extraPaidMinor
            }
        }
        return Totals(
            borrowedTotalMinor = borrowed,
            repaidPrincipalMinor = repaid,
            remainingOwedMinor = remainingOwed,
            lentTotalMinor = lent,
            receivedPrincipalMinor = received,
            toReceiveMinor = toReceive,
            extraPaidMinor = extraPaid,
            extraReceivedMinor = extraReceived,
        )
    }

    /** 保存前校验，返回错误文案；`null` 表示通过。 */
    fun validate(debt: Debt): String? {
        if (debt.name.isBlank()) return "请填写债务名称"
        if (debt.principalMinor <= 0L) return "本金必须大于 0"
        if (debt.dueAt <= 0L) return "请选择到期日"
        if (debt.anchorDay !in 1..31) return "每月到期日应在 1 到 31 号之间"
        return null
    }

    /** 还款金额校验：本金部分不能为负，也不能超过剩余本金。 */
    fun validateRepayment(debt: Debt, payments: List<DebtPayment>, principalMinor: Long, extraMinor: Long): String? {
        if (principalMinor < 0L) return "本金金额不能为负"
        if (extraMinor < 0L) return "利息与手续费不能为负"
        if (principalMinor == 0L && extraMinor == 0L) return "本金和利息不能都为 0"
        val remaining = progressOf(debt, payments).remainingPrincipalMinor
        if (principalMinor > remaining) {
            return "本金超出剩余欠款（还剩 ${Money.format(remaining)}）"
        }
        return null
    }

    /** 距离到期还有几天；负数表示已逾期。必须自己算 epochDay 差，避免闭区间语义漏掉当天。 */
    fun daysUntil(dueAt: Long, today: LocalDate = LocalDate.now(Time.zone)): Int =
        (Time.toLocalDate(dueAt).toEpochDay() - today.toEpochDay()).toInt()

    /** 是否进入提醒窗口（[remindDays] 天内到期或已逾期）。 */
    fun shouldRemind(dueAt: Long, remindDays: Int, today: LocalDate = LocalDate.now(Time.zone)): Boolean =
        daysUntil(dueAt, today) <= remindDays.coerceAtLeast(0)

    /** 按月顺延一期，锚点日回退到目标月最后一天但不改变锚点日。 */
    fun nextDue(fromDueAt: Long, anchorDay: Int): Long {
        val from: LocalDate = Time.toLocalDate(fromDueAt)
        val target: YearMonth = YearMonth.from(from).plusMonths(1)
        val day = anchorDay.coerceIn(1, target.lengthOfMonth())
        return Time.toMillis(target.atDay(day))
    }
}