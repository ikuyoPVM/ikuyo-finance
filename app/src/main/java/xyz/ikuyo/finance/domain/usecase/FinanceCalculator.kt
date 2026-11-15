package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.BalanceSummary
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.BudgetWindow
import xyz.ikuyo.finance.domain.model.CategorySpend
import xyz.ikuyo.finance.domain.model.DailySpend
import xyz.ikuyo.finance.domain.model.HomeStats
import java.time.LocalDate
import java.time.YearMonth

/**
 * 财务计算核心。全部为纯函数，金额使用最小单位 Long，便于单元测试。
 *
 * 口径约定：
 * - 退款冲减原消费（expenseImpact 为负），不计普通收入。
 * - 余额 = 收入 - 支出 + 退款，储蓄划转不计普通收支。
 * - 统计只看 occurredAt（消费发生时间）。
 */
object FinanceCalculator {

    /**
     * 余额口径：储蓄是资金划转，不产生账单。
     * - 净额 = 收入 − 支出 + 退款（只由账单决定）
     * - 储蓄余额 = 已划入储蓄的金额（≥ 0）
     * - 可用余额 = 净额 − 储蓄余额
     * - 总余额 = 可用余额 + 储蓄余额 = 净额（存入/取出都不改变总余额）
     */
    fun balance(bills: List<Bill>, savingsMinor: Long = 0L): BalanceSummary {
        val net = bills.sumOf { it.signedImpactMinor }
        val savings = savingsMinor.coerceAtLeast(0L)
        return BalanceSummary(availableMinor = net - savings, savingsMinor = savings)
    }

    fun homeStats(bills: List<Bill>, today: LocalDate = LocalDate.now(Time.zone)): HomeStats {
        val todayStart = Time.startOfDay(today)
        val tomorrowStart = Time.endOfDayExclusive(today)
        val weekStart = Time.startOfWeek(today)
        val weekEnd = Time.endOfWeekExclusive(today)

        val month = YearMonth.from(today)
        val monthStart = Time.startOfMonth(month)
        val monthEnd = Time.endOfMonthExclusive(month)

        fun inRange(bill: Bill, from: Long, to: Long): Boolean =
            bill.occurredAt >= from && bill.occurredAt < to

        val todayExpense = bills.filter { inRange(it, todayStart, tomorrowStart) }
            .sumOf { it.expenseImpactMinor }
        val weekExpense = bills.filter { inRange(it, weekStart, weekEnd) }
            .sumOf { it.expenseImpactMinor }

        val monthBills = bills.filter { inRange(it, monthStart, monthEnd) }
        val monthExpense = monthBills.sumOf { it.expenseImpactMinor }
        val monthIncome = monthBills.filter { it.type == BillType.INCOME }
            .sumOf { it.cnyAmountMinor }

        // 与上月同期对比：上月 1 日至「今日对应日」
        val lastMonth = month.minusMonths(1)
        val lastMonthStart = Time.startOfMonth(lastMonth)
        val compareDay = minOf(today.dayOfMonth, lastMonth.lengthOfMonth())
        val lastMonthEnd = Time.startOfDay(lastMonth.atDay(compareDay).plusDays(1))
        val lastMonthExpense = bills.filter { inRange(it, lastMonthStart, lastMonthEnd) }
            .sumOf { it.expenseImpactMinor }

        val topCategories = monthBills
            .filter { it.type != BillType.INCOME }
            .groupBy { it.categoryId }
            .map { (categoryId, group) ->
                CategorySpend(
                    categoryId = categoryId,
                    name = group.firstOrNull()?.categoryName ?: "未分类",
                    emoji = group.firstOrNull()?.categoryEmoji ?: "🏷️",
                    amountMinor = group.sumOf { it.expenseImpactMinor },
                )
            }
            .filter { it.amountMinor > 0L }
            .sortedByDescending { it.amountMinor }
            .take(3)

        val trend = (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val from = Time.startOfDay(day)
            val to = Time.endOfDayExclusive(day)
            DailySpend(
                epochDay = day.toEpochDay(),
                amountMinor = bills.filter { inRange(it, from, to) }.sumOf { it.expenseImpactMinor },
            )
        }

        return HomeStats(
            todayExpenseMinor = todayExpense,
            weekExpenseMinor = weekExpense,
            monthExpenseMinor = monthExpense,
            monthIncomeMinor = monthIncome,
            lastMonthExpenseMinor = lastMonthExpense,
            topCategories = topCategories,
            dailyTrend = trend,
        )
    }

    fun budgetStatus(
        bills: List<Bill>,
        budgetAmountMinor: Long?,
        periodStart: Long,
        periodEnd: Long,
        today: LocalDate = LocalDate.now(Time.zone),
        carryOverMinor: Long = 0L,
        windowLabel: String = "",
    ): BudgetStatus {
        val spent = bills
            .filter { it.occurredAt in periodStart..periodEnd }
            .sumOf { it.expenseImpactMinor }
        val available = (budgetAmountMinor ?: 0L) + carryOverMinor
        return BudgetStatus(
            amountMinor = budgetAmountMinor,
            carryOverMinor = carryOverMinor,
            spentMinor = spent,
            periodStart = periodStart,
            periodEnd = periodEnd,
            daysLeft = Time.daysLeft(periodEnd, today),
            windowLabel = windowLabel,
            alertLevel = if (budgetAmountMinor == null) {
                BudgetAlertLevel.NORMAL
            } else {
                BudgetAlertLevel.of(available, spent)
            },
        )
    }

    /**
     * 按「预算规则 + 当前区间」计算预算执行情况。
     *
     * 结转规则（计划书 8.1）：用户开启结转时，上一周期的结余计入本周期；
     * 上一周期超支则不结转（超支不追加上去，保持周期独立）。
     */
    fun budgetStatusOf(
        bills: List<Bill>,
        budget: Budget?,
        window: BudgetWindow,
        today: LocalDate = LocalDate.now(Time.zone),
        tagBillIds: Set<Long>? = null,
    ): BudgetStatus {
        // 标签预算只统计带该标签的账单；其余维度统计全部账单。
        val relevant = if (budget?.scope == BudgetScope.TAG && tagBillIds != null) {
            bills.filter { it.id in tagBillIds }
        } else {
            bills
        }
        val carry = if (budget != null && budget.carryOver) {
            val previous = BudgetPeriods.previous(
                type = budget.periodType,
                current = window,
                customStart = budget.customStart,
                customEnd = budget.customEnd,
            )
            if (previous == null) {
                0L
            } else {
                val previousSpent = relevant
                    .filter { it.occurredAt in previous.start..previous.endInclusive }
                    .sumOf { it.expenseImpactMinor }
                (budget.amountMinor - previousSpent).coerceAtLeast(0L)
            }
        } else {
            0L
        }
        return budgetStatus(
            bills = relevant,
            budgetAmountMinor = budget?.amountMinor,
            periodStart = window.start,
            periodEnd = window.endInclusive,
            today = today,
            carryOverMinor = carry,
            windowLabel = budget?.let { BudgetPeriods.label(it.periodType, window) } ?: "",
        )
    }

    /** 某分类在周期内的净支出（退款冲减）。 */
    fun categorySpent(
        bills: List<Bill>,
        categoryId: Long,
        periodStart: Long,
        periodEnd: Long,
    ): Long = bills
        .filter { it.categoryId == categoryId && it.occurredAt in periodStart..periodEnd }
        .sumOf { it.expenseImpactMinor }
}