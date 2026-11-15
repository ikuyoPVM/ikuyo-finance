package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 预算周期推导（纯函数）。
 *
 * - 每月：自然月（1 日 00:00 ~ 月末 23:59:59.999）
 * - 每周：周一 ~ 周日
 * - 每日：当天 00:00 ~ 23:59:59.999
 * - 自定义：使用规则里保存的起止日期，可跨自然月
 */
object BudgetPeriods {

    fun window(
        type: BudgetPeriodType,
        anchor: LocalDate = LocalDate.now(Time.zone),
        customStart: Long? = null,
        customEnd: Long? = null,
    ): BudgetWindow = when (type) {
        BudgetPeriodType.MONTHLY -> monthWindow(YearMonth.from(anchor))
        BudgetPeriodType.WEEKLY -> weekWindow(anchor)
        BudgetPeriodType.DAILY -> dayWindow(anchor)
        BudgetPeriodType.CUSTOM -> customWindow(anchor, customStart, customEnd)
    }

    /** 规则对应的当前区间。 */
    fun windowOf(budget: Budget, anchor: LocalDate = LocalDate.now(Time.zone)): BudgetWindow =
        window(budget.periodType, anchor, budget.customStart, budget.customEnd)

    /**
     * 上一个周期；自定义周期没有「上一个」的概念，返回 null（不结转）。
     */
    fun previous(
        type: BudgetPeriodType,
        current: BudgetWindow,
        customStart: Long? = null,
        customEnd: Long? = null,
    ): BudgetWindow? = when (type) {
        BudgetPeriodType.MONTHLY ->
            monthWindow(YearMonth.from(Time.toLocalDate(current.start)).minusMonths(1))

        BudgetPeriodType.WEEKLY -> weekWindow(Time.toLocalDate(current.start).minusWeeks(1))
        BudgetPeriodType.DAILY -> dayWindow(Time.toLocalDate(current.start).minusDays(1))
        BudgetPeriodType.CUSTOM -> {
            val start = customStart
            val end = customEnd
            if (start == null || end == null) null
            else {
                val span = end - start + 1
                BudgetWindow(start - span, start - 1)
            }
        }
    }

    fun label(type: BudgetPeriodType, window: BudgetWindow): String = when (type) {
        BudgetPeriodType.MONTHLY ->
            Time.formatMonth(YearMonth.from(Time.toLocalDate(window.start)))

        BudgetPeriodType.WEEKLY ->
            "${Time.formatMonthDay(window.start)} - ${Time.formatMonthDay(window.endInclusive)}"

        BudgetPeriodType.DAILY -> Time.formatMonthDay(window.start)

        BudgetPeriodType.CUSTOM ->
            "${Time.formatMonthDay(window.start)} - ${Time.formatMonthDay(window.endInclusive)}（自定义）"
    }

    private fun monthWindow(month: YearMonth): BudgetWindow = BudgetWindow(
        start = Time.startOfMonth(month),
        endInclusive = Time.endOfMonthInclusive(month),
    )

    private fun weekWindow(anchor: LocalDate): BudgetWindow {
        val monday = anchor.with(DayOfWeek.MONDAY)
        return BudgetWindow(
            start = Time.startOfDay(monday),
            endInclusive = Time.endOfDayInclusive(monday.plusDays(6)),
        )
    }

    private fun dayWindow(anchor: LocalDate): BudgetWindow = BudgetWindow(
        start = Time.startOfDay(anchor),
        endInclusive = Time.endOfDayInclusive(anchor),
    )

    private fun customWindow(
        anchor: LocalDate,
        customStart: Long?,
        customEnd: Long?,
    ): BudgetWindow {
        val month = YearMonth.from(anchor)
        val start = customStart ?: Time.startOfMonth(month)
        val end = customEnd ?: Time.endOfMonthInclusive(month)
        return if (end >= start) BudgetWindow(start, end) else BudgetWindow(end, start)
    }
}