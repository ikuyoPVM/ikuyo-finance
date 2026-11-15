package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.FixedExpense
import xyz.ikuyo.finance.domain.model.FixedPeriodType
import java.time.LocalDate
import java.time.YearMonth

/**
 * 固定支出周期推算。
 *
 * 这里只做日期算术，不碰金额、不碰数据库，方便单独测。
 *
 * ## 月末回退规则（阶段五计划书第 2 章）
 *
 * 按月推进的周期一律以计划的「月内锚点日」为基准：
 * 锚点日大于目标月份天数时回退到该月最后一天，**但锚点日本身不变**。
 * 因此「1 月 31 日」的月度计划序列是 1/31 → 2/28 → 3/31 → 4/30 → 5/31，
 * 不会像「在上一次到期日上 +1 月」那样一路退化成 28 日。
 */
object FixedExpenseSchedule {

    /** 由计划当前到期日推进一个周期。 */
    fun nextDue(expense: FixedExpense, fromDueAt: Long = expense.dueAt): Long = nextDue(
        fromDueAt = fromDueAt,
        periodType = expense.periodType,
        anchorDay = expense.anchorDay,
        customDays = expense.customDays,
    )

    fun nextDue(
        fromDueAt: Long,
        periodType: FixedPeriodType,
        anchorDay: Int,
        customDays: Int,
    ): Long {
        val from: LocalDate = Time.toLocalDate(fromDueAt)
        return when (periodType) {
            FixedPeriodType.WEEKLY -> Time.toMillis(from.plusDays(7))
            FixedPeriodType.BIWEEKLY -> Time.toMillis(from.plusDays(14))
            FixedPeriodType.CUSTOM -> Time.toMillis(from.plusDays(customDays.coerceAtLeast(1).toLong()))
            else -> {
                val target: YearMonth = YearMonth.from(from).plusMonths(periodType.monthsPerPeriod.toLong())
                val day = anchorDay.coerceIn(1, target.lengthOfMonth())
                Time.toMillis(target.atDay(day))
            }
        }
    }

    /** 到期日是否已到（含当天）。 */
    fun isDue(dueAt: Long, today: LocalDate = LocalDate.now(Time.zone)): Boolean =
        dueAt <= Time.endOfDayInclusive(today)

    /**
     * 距到期还有几天：今天到期返回 0，未来为正，已过期为负。
     *
     * 这里不能用 [Time.daysLeft]——那是预算周期的「闭区间剩余天数」语义（当天返回 1、
     * 过期返回 0），套过来会让「提前 0 天」的计划在到期当天漏掉提醒。
     */
    fun daysUntil(dueAt: Long, today: LocalDate = LocalDate.now(Time.zone)): Int =
        (Time.toLocalDate(dueAt).toEpochDay() - today.toEpochDay()).toInt()

    /**
     * 是否进入提醒窗口。
     *
     * @param remindDays 提前天数，0 表示只有到期当天才提醒。
     */
    fun shouldRemind(
        dueAt: Long,
        remindDays: Int,
        today: LocalDate = LocalDate.now(Time.zone),
    ): Boolean = daysUntil(dueAt, today) <= remindDays.coerceAtLeast(0)

    /** 把新计划的下一次到期日对齐到「今天或之后的第一个周期终点」。 */
    fun firstDueOnOrAfter(
        start: Long,
        periodType: FixedPeriodType,
        anchorDay: Int,
        customDays: Int,
        today: LocalDate = LocalDate.now(Time.zone),
    ): Long {
        var due = start
        var guard = 0
        while (Time.toLocalDate(due) < today && guard < MAX_ADVANCE_STEPS) {
            due = nextDue(due, periodType, anchorDay, customDays)
            guard++
        }
        return due
    }

    private const val MAX_ADVANCE_STEPS = 600
}