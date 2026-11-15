package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Installment
import xyz.ikuyo.finance.domain.model.InstallmentPayment
import xyz.ikuyo.finance.domain.model.InstallmentPeriod
import xyz.ikuyo.finance.domain.model.InstallmentProgress
import java.time.LocalDate
import java.time.YearMonth

/**
 * 分期计划的期数、到期日与还款进度推算。
 *
 * 这里只做算术，不碰数据库也不碰 AI，方便单独测。
 *
 * 口径（阶段五计划书第 3 章）：
 * 1. 各期金额之和必须等于总还款金额（[Installment.scheduleTotalMinor] == [Installment.totalMinor]）。
 * 2. 首期金额可以与后续各期不同；其余各期金额相同。
 * 3. 到期日按月顺延，沿用固定支出的月末回退规则：锚点日超过当月天数时回退到月末，
 *    但锚点日本身不变，所以 1/31 → 2/28 → 3/31 不会一路退化成 28 日。
 * 4. 未付款金额是「未来待还」，**不是已发生支出**；提前结束后不再算未付款。
 */
object InstallmentSchedule {

    /** 该计划每一期的到期日（毫秒），按顺序返回 [Installment.periodCount] 个。 */
    fun dueDates(installment: Installment): List<Long> {
        val count = installment.periodCount.coerceAtLeast(1)
        val dates = ArrayList<Long>(count)
        var due = installment.dueAt
        dates += due
        var index = 1
        while (index < count) {
            due = nextDue(due, installment.anchorDay)
            dates += due
            index++
        }
        return dates
    }

    /** 按月顺延一期，锚点日回退到目标月份的最后一天，但不改变锚点日。 */
    fun nextDue(fromDueAt: Long, anchorDay: Int): Long {
        val from: LocalDate = Time.toLocalDate(fromDueAt)
        val target: YearMonth = YearMonth.from(from).plusMonths(1)
        val day = anchorDay.coerceIn(1, target.lengthOfMonth())
        return Time.toMillis(target.atDay(day))
    }

    /**
     * 把计划展开成每一期的展示数据，付款记录按 [InstallmentPayment.periodIndex] 对应。
     */
    fun periods(
        installment: Installment,
        payments: List<InstallmentPayment>,
    ): List<InstallmentPeriod> {
        val paid = payments.filter { it.installmentId == installment.id }.associateBy { it.periodIndex }
        return dueDates(installment).mapIndexed { index, dueAt ->
            InstallmentPeriod(
                index = index,
                dueAt = dueAt,
                amountMinor = installment.amountOf(index),
                payment = paid[index],
            )
        }
    }

    /** 还款进度：已还金额 / 剩余金额 / 已还期数 / 剩余期数 / 下一期。 */
    fun progress(
        installment: Installment,
        payments: List<InstallmentPayment>,
    ): InstallmentProgress {
        val rows = periods(installment, payments)
        val paidRows = rows.filter { it.isPaid }
        val remainingRows = rows.filter { !it.isPaid }
        val paidMinor = paidRows.sumOf { it.payment?.amountMinor ?: it.amountMinor }
        // 提前结束后剩余期数不再算「未来待还」，但期数统计仍保留原值，便于追溯。
        val remainingMinor = if (installment.isClosed) 0L else remainingRows.sumOf { it.amountMinor }
        return InstallmentProgress(
            installmentId = installment.id,
            periodCount = installment.periodCount,
            paidCount = paidRows.size,
            paidMinor = paidMinor,
            remainingMinor = remainingMinor,
            nextPeriod = if (installment.isClosed) null else remainingRows.firstOrNull(),
        )
    }

    /** 下一步该付的那一期（已提前结束或全部付清时为 null）。 */
    fun nextUnpaid(
        installment: Installment,
        payments: List<InstallmentPayment>,
    ): InstallmentPeriod? = progress(installment, payments).nextPeriod

    /**
     * 校验计划金额是否自洽，返回错误文案，`null` 表示通过。
     *
     * 保存前必须调用：宁可让用户改数字，也不能存下一条「总金额和分期明细对不上」的计划。
     */
    fun validate(installment: Installment): String? {
        if (installment.name.isBlank()) return "请填写分期名称"
        if (installment.periodCount < 1) return "分期期数至少要 1 期"
        if (installment.periodCount > MAX_PERIODS) return "分期期数不能超过 $MAX_PERIODS 期"
        if (installment.firstPeriodMinor <= 0L) return "首期金额必须大于 0"
        if (installment.perPeriodMinor < 0L) return "每期金额不能为负"
        if (installment.principalMinor < 0L || installment.feeMinor < 0L || installment.interestMinor < 0L) {
            return "本金、手续费和利息都不能为负"
        }
        if (installment.totalMinor <= 0L) return "总金额必须大于 0"
        val parts = installment.principalMinor + installment.feeMinor + installment.interestMinor
        if (parts != installment.totalMinor) {
            return "本金 + 手续费 + 利息应等于总还款金额"
        }
        if (installment.scheduleTotalMinor != installment.totalMinor) {
            return "各期金额之和应等于总还款金额"
        }
        if (installment.dueAt <= 0L) return "请选择首期还款日期"
        return null
    }

    /**
     * 给定总金额与期数，算出「除首期之外每期」的金额，余数补进首期，
     * 保证各期之和严格等于总金额（不产生四舍五入丢失的 1 分钱）。
     */
    fun suggestPerPeriod(totalMinor: Long, periodCount: Int, firstPeriodMinor: Long? = null): Long {
        val count = periodCount.coerceAtLeast(1)
        if (count <= 1) return 0L
        val first = firstPeriodMinor ?: (totalMinor / count)
        val rest = totalMinor - first
        return if (rest <= 0L) 0L else rest / (count - 1)
    }

    /** 按「每期均分、余数进首期」生成一套默认金额，UI 新建计划时可直接用。 */
    fun evenSplit(totalMinor: Long, periodCount: Int): Pair<Long, Long> {
        val count = periodCount.coerceAtLeast(1)
        if (count <= 1) return totalMinor to 0L
        val base = totalMinor / count
        val first = totalMinor - base * (count - 1)
        return first to base
    }

    /** 该期是否进入提醒窗口（[remindDays] 天内到期或已逾期）。 */
    fun shouldRemind(
        period: InstallmentPeriod,
        remindDays: Int,
        today: LocalDate = LocalDate.now(Time.zone),
    ): Boolean = FixedExpenseSchedule.shouldRemind(period.dueAt, remindDays, today)

    private const val MAX_PERIODS = 60
}