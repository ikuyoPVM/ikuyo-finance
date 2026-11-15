package xyz.ikuyo.finance.domain.model

/** 余额汇总。总余额 = 可用余额 + 储蓄余额（债务不混入）。 */
data class BalanceSummary(
    val availableMinor: Long = 0L,
    val savingsMinor: Long = 0L,
) {
    val totalMinor: Long get() = availableMinor + savingsMinor
}

/** 首页统计所需数据。 */
data class HomeStats(
    val todayExpenseMinor: Long = 0L,
    val weekExpenseMinor: Long = 0L,
    val monthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    val lastMonthExpenseMinor: Long = 0L,
    val topCategories: List<CategorySpend> = emptyList(),
    val dailyTrend: List<DailySpend> = emptyList(),
) {
    val monthNetMinor: Long get() = monthIncomeMinor - monthExpenseMinor

    /** 与上月同期对比（上月 1 日至今日对应的日）。 */
    val monthOverMonthRatio: Double?
        get() = if (lastMonthExpenseMinor > 0) {
            (monthExpenseMinor - lastMonthExpenseMinor).toDouble() / lastMonthExpenseMinor
        } else {
            null
        }
}

data class CategorySpend(
    val categoryId: Long?,
    val name: String,
    val emoji: String,
    val amountMinor: Long,
)

data class DailySpend(
    val epochDay: Long,
    val amountMinor: Long,
)

/** 预算执行情况。 */
data class BudgetStatus(
    val amountMinor: Long? = null,
    val spentMinor: Long = 0L,
    val periodStart: Long = 0L,
    val periodEnd: Long = 0L,
    val daysLeft: Int = 0,
) {
    val remainingMinor: Long get() = (amountMinor ?: 0L) - spentMinor
    val isOverBudget: Boolean get() = amountMinor != null && remainingMinor < 0

    val progress: Float
        get() {
            val total = amountMinor ?: return 0f
            if (total <= 0L) return 0f
            return (spentMinor.toFloat() / total.toFloat()).coerceAtLeast(0f)
        }

    /** 每日可用预算：剩余预算 / 剩余天数（含今日）；已超支时为 0。 */
    val dailyAvailableMinor: Long
        get() {
            if (amountMinor == null) return 0L
            if (remainingMinor <= 0L) return 0L
            val days = daysLeft.coerceAtLeast(1)
            return remainingMinor / days
        }
}