package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import java.time.LocalDate

/**
 * 消费预测（计划书第 5 章）。
 *
 * 只做本地确定性推算，不调用模型：模型一旦参与算数，就无法保证「预计支出」和账单一致。
 * 口径与 [Statistics] 完全一致（净支出 = 支出 − 退款），并且：
 *  - 不用固定的单日金额外推：先用中位数判断异常日，异常日不参与日均，避免一次大额消费把整月预测抬高；
 *  - 数据太少时降低可信度而不是硬给结论；
 *  - 永远标记 [isForecast] = true，界面必须显示「预测」字样。
 */
object SpendingForecast {

    /** 可信度等级：由账期已过天数与账单笔数决定，不给「精确概率」。 */
    enum class Confidence(val label: String) {
        HIGH("较高"),
        MEDIUM("中等"),
        LOW("较低"),
    }

    data class Forecast(
        val monthLabel: String,
        val startDate: String,
        val endDate: String,
        val daysTotal: Int,
        val daysElapsed: Int,
        val daysLeft: Int,
        /** 本月已发生的净支出。 */
        val spentMinor: Long,
        /** 本月日均净支出（含所有已过天数）。 */
        val dailyAverageMinor: Long,
        /** 去掉异常日后得到的「典型一天」，用于外推。 */
        val typicalDailyMinor: Long,
        /** 预测本月总净支出。 */
        val projectedTotalMinor: Long,
        /** 预算额度（没有预算时为 null）。 */
        val budgetAmountMinor: Long?,
        /** 按预测总额算出的预算剩余，负数表示会超支。 */
        val projectedRemainingMinor: Long?,
        /** 预计超支金额，不超支为 0。 */
        val projectedOverMinor: Long,
        val confidence: Confidence,
        /** 推算依据，逐条可核对。 */
        val basis: List<String>,
        /** 主要影响因素，来自真实数据。 */
        val factors: List<String>,
        val isForecast: Boolean = true,
    ) {
        val hasBudget: Boolean get() = budgetAmountMinor != null
        val willOverrun: Boolean get() = projectedOverMinor > 0L
    }

    /** 预测时被视为「异常日」的倍数：超过典型日 [OUTLIER_MULTIPLIER] 倍且绝对值过线。 */
    private const val OUTLIER_MULTIPLIER = 3L
    private const val OUTLIER_MIN_MINOR = 20_000L
    private const val THIN_DAYS = 3
    private const val THIN_BILLS = 5

    /**
     * 按当前自然月预测。
     *
     * @param budgets 当前月的预算执行结果，用来给出「预计剩余 / 可能超支」；没有预算时只给预测总额。
     */
    fun currentMonth(
        bills: List<Bill>,
        today: LocalDate = LocalDate.now(Time.zone),
        budgets: List<Statistics.BudgetExecution> = emptyList(),
        categories: List<xyz.ikuyo.finance.domain.model.Category> = emptyList(),
    ): Forecast {
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val inRange = Statistics.filterInRange(bills, range)
        val totals = Statistics.totals(bills, range)
        val daily = Statistics.dailySeries(bills, range)

        val daysTotal = range.dayCount
        val daysElapsed = (today.dayOfMonth).coerceIn(1, daysTotal)
        val daysLeft = (daysTotal - daysElapsed).coerceAtLeast(0)

        val spentMinor = totals.netExpenseMinor
        val dailyAverageMinor = spentMinor / daysElapsed

        // 用中位数判断异常日，避免一次大额消费被当成长期节奏。
        val values = daily.map { it.netExpenseMinor }.filter { it > 0L }
        val median = medianOf(values)
        val outliers = if (median > 0L) {
            values.filter { it > median * OUTLIER_MULTIPLIER && it >= OUTLIER_MIN_MINOR }
        } else {
            emptyList()
        }
        val typicalDailyMinor = if (values.size >= 3) {
            val regularDays = (daysElapsed - outliers.size).coerceAtLeast(1)
            ((spentMinor - outliers.sum()).coerceAtLeast(0L)) / regularDays
        } else {
            dailyAverageMinor
        }

        val projectedTotalMinor = spentMinor + daysLeft * typicalDailyMinor

        val budgetAmountMinor = pickBudgetAmount(budgets)
        val projectedRemainingMinor = budgetAmountMinor?.let { it - projectedTotalMinor }
        val projectedOverMinor = projectedRemainingMinor?.let { (-it).coerceAtLeast(0L) } ?: 0L

        val confidence = when {
            totals.billCount < THIN_BILLS || daysElapsed < THIN_DAYS -> Confidence.LOW
            daysLeft == 0 -> Confidence.LOW
            daysElapsed < 10 || totals.billCount < 15 -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }

        val basis = buildList {
            add("统计范围：${range.label}（${Time.formatFullDate(range.startMillis)} 至 ${Time.formatDate(range.endMillisExclusive - 1)}）")
            add("已过 $daysElapsed / $daysTotal 天，已花 ${Money.format(spentMinor)}，共 ${totals.billCount} 笔")
            add("本月日均 ${Money.format(dailyAverageMinor)}；外推用的典型一天 ${Money.format(typicalDailyMinor)}")
            if (outliers.isNotEmpty()) {
                add("有 ${outliers.size} 天明显高于日常（合计 ${Money.format(outliers.sum())}），已从日均中剔除，避免把偶发大额当常态")
            }
            if (daysLeft > 0) {
                add("剩余 $daysLeft 天按典型一天外推：${Money.format(spentMinor)} + $daysLeft × ${Money.format(typicalDailyMinor)} = ${Money.format(projectedTotalMinor)}")
            } else {
                add("本月已结束，预测值等于实际值 ${Money.format(projectedTotalMinor)}")
            }
            budgetAmountMinor?.let {
                add("预算 ${Money.format(it)}，${if (projectedOverMinor > 0L) "预计超支 ${Money.format(projectedOverMinor)}" else "预计剩余 ${Money.format(projectedRemainingMinor ?: 0L)}"}")
            }
        }

        val factors = buildList {
            val top = Statistics.categoryStats(bills, range, categories, topLimit = 1).firstOrNull()
            if (top != null) add("最大分类「${top.name}」占本期净支出 ${Statistics.shareOf(top.netExpenseMinor, totals.netExpenseMinor) / 100}%")
            totals.refundMinor.takeIf { it > 0L }?.let { add("已发生退款 ${Money.format(it)}，已从预测中冲减") }
            budgets.maxByOrNull { it.usedPercent }?.let {
                add("预算「${it.name}」已用 ${it.usedPercent}%，时间进度 ${it.timeProgressBp / 100}%")
            }
            if (confidence != Confidence.HIGH) add("数据天数或笔数偏少，预测区间可能明显偏离实际")
        }

        return Forecast(
            monthLabel = Time.formatMonth(Time.monthOf(range.startMillis)),
            startDate = Time.formatFullDate(range.startMillis),
            endDate = Time.formatDate(range.endMillisExclusive - 1),
            daysTotal = daysTotal,
            daysElapsed = daysElapsed,
            daysLeft = daysLeft,
            spentMinor = spentMinor,
            dailyAverageMinor = dailyAverageMinor,
            typicalDailyMinor = typicalDailyMinor,
            projectedTotalMinor = projectedTotalMinor,
            budgetAmountMinor = budgetAmountMinor,
            projectedRemainingMinor = projectedRemainingMinor,
            projectedOverMinor = projectedOverMinor,
            confidence = confidence,
            basis = basis,
            factors = factors,
        )
    }

    /** 优先用总预算；没有总预算时用额度最大的那条，避免分类预算被误当成整体预算。 */
    private fun pickBudgetAmount(budgets: List<Statistics.BudgetExecution>): Long? {
        if (budgets.isEmpty()) return null
        val total = budgets.firstOrNull { it.name == TOTAL_BUDGET_NAME }
        return (total ?: budgets.maxByOrNull { it.amountMinor })?.amountMinor
    }

    const val TOTAL_BUDGET_NAME = "总预算"

    private fun medianOf(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }
}