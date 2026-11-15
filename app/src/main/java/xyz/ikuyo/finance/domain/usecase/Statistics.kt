package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 统一统计口径（纯函数，唯一的金额聚合入口）。
 *
 * 口径（与 FinanceCalculator 完全一致，禁止任何页面或提示词另算一套）：
 * - 只统计「消费发生时间」落在区间内的账单；已软删除（回收站）与已撤销的账单由
 *   调用方用 `observeActive()` 过滤，本服务拿到的就是「参与统计」的账单。
 * - 支出 = Σ expenseImpactMinor 中的正值；退款冲减消费，**不计入收入**。
 * - 净支出 = 支出 - 退款；净收支 = 收入 - 净支出。
 * - 金额单位一律为「分」（Long），百分比以万分比（basis point）整数表示，避免浮点误差。
 */
object Statistics {

    /** 统计维度。 */
    enum class RangeType(val label: String) {
        DAY("日"),
        WEEK("周"),
        MONTH("月"),
        CUSTOM("自定义"),
    }

    /** 统计区间，含起始、不含结束（毫秒）。 */
    data class Range(
        val type: RangeType,
        val startMillis: Long,
        val endMillisExclusive: Long,
        val label: String,
        val previousStartMillis: Long,
        val previousEndMillisExclusive: Long,
        val previousLabel: String,
    ) {
        val dayCount: Int
            get() = ((endMillisExclusive - startMillis) / DAY_MILLIS).toInt().coerceAtLeast(1)

        fun contains(millis: Long): Boolean = millis >= startMillis && millis < endMillisExclusive

        fun emptyWindow(): Boolean = endMillisExclusive <= startMillis
    }

    data class Totals(
        val expenseMinor: Long = 0L,
        val refundMinor: Long = 0L,
        val incomeMinor: Long = 0L,
        val billCount: Int = 0,
    ) {
        /** 退款冲减后的净支出。 */
        val netExpenseMinor: Long get() = expenseMinor - refundMinor

        /** 净收支 = 收入 - 净支出。 */
        val netMinor: Long get() = incomeMinor - netExpenseMinor

        val hasData: Boolean get() = billCount > 0
    }

    data class CategoryStat(
        val categoryId: Long?,
        val name: String,
        val emoji: String,
        val colorArgb: Int,
        val expenseMinor: Long,
        val refundMinor: Long,
        val netExpenseMinor: Long,
        val billCount: Int,
        /** 占本期净支出的万分比。 */
        val shareBp: Int,
        /** 上一周期该分类的净支出；上期完全没有这个分类时为 0。 */
        val previousNetExpenseMinor: Long = 0L,
    ) {
        /** 相对上期变化百分比；上期为 0 时返回 null（无法比较，不编造）。 */
        val changePercent: Int?
            get() = Statistics.changePercent(netExpenseMinor, previousNetExpenseMinor)
    }

    data class TagStat(
        val tagId: Long,
        val name: String,
        val colorArgb: Int,
        val netExpenseMinor: Long,
        val billCount: Int,
        val shareBp: Int,
        val previousNetExpenseMinor: Long = 0L,
    ) {
        val changePercent: Int?
            get() = Statistics.changePercent(netExpenseMinor, previousNetExpenseMinor)
    }

    data class DailyPoint(
        val date: LocalDate,
        val millis: Long,
        val expenseMinor: Long,
        val refundMinor: Long,
        val incomeMinor: Long,
    ) {
        val netExpenseMinor: Long get() = expenseMinor - refundMinor
    }

    data class BudgetExecution(
        val budgetId: Long,
        val name: String,
        val windowLabel: String,
        val amountMinor: Long,
        val spentMinor: Long,
        val availableMinor: Long,
        val carryOverMinor: Long,
        val usedPercent: Int,
        val daysLeft: Int,
        val alertLevel: BudgetAlertLevel,
        /** 周期时间进度（已过时长占整个周期的万分比），用于判断花得快不快。 */
        val timeProgressBp: Int = 0,
    ) {
        val overMinor: Long get() = (spentMinor - availableMinor).coerceAtLeast(0L)

        /** 按时间进度应花的金额。 */
        val expectedMinor: Long get() = availableMinor * timeProgressBp / 10_000L

        /** 相对时间进度超前（正）或落后（负）的金额。 */
        val paceMinor: Long get() = spentMinor - expectedMinor
    }

    data class Snapshot(
        val range: Range,
        val totals: Totals,
        val previousTotals: Totals,
        val categories: List<CategoryStat>,
        val tags: List<TagStat>,
        val daily: List<DailyPoint>,
        val budgets: List<BudgetExecution>,
        /** 有账单但没有任何支出/收入，仍视为无数据。 */
        val hasData: Boolean,
    ) {
        /** 净支出相对上期变化百分比；上期为 0 时返回 null（无法比较，不编造）。 */
        val expenseChangePercent: Int?
            get() = changePercent(totals.netExpenseMinor, previousTotals.netExpenseMinor)

        val incomeChangePercent: Int?
            get() = changePercent(totals.incomeMinor, previousTotals.incomeMinor)
    }

    private const val DAY_MILLIS = 86_400_000L

    // ---------------------------------------------------------------- 区间推导

    fun range(
        type: RangeType,
        anchor: LocalDate = LocalDate.now(Time.zone),
        customStart: Long? = null,
        customEndInclusive: Long? = null,
    ): Range = when (type) {
        RangeType.DAY -> {
            val start = Time.startOfDay(anchor)
            val end = Time.endOfDayExclusive(anchor)
            val prevDate = anchor.minusDays(1)
            Range(
                type = type,
                startMillis = start,
                endMillisExclusive = end,
                label = Time.formatFullDate(start),
                previousStartMillis = Time.startOfDay(prevDate),
                previousEndMillisExclusive = Time.endOfDayExclusive(prevDate),
                previousLabel = Time.formatFullDate(Time.startOfDay(prevDate)),
            )
        }

        RangeType.WEEK -> {
            val start = Time.startOfWeek(anchor)
            val end = Time.endOfWeekExclusive(anchor)
            val prevAnchor = anchor.minusWeeks(1)
            Range(
                type = type,
                startMillis = start,
                endMillisExclusive = end,
                label = "${Time.formatMonthDay(start)} - ${Time.formatMonthDay(end - 1)}",
                previousStartMillis = Time.startOfWeek(prevAnchor),
                previousEndMillisExclusive = Time.endOfWeekExclusive(prevAnchor),
                previousLabel = "上周",
            )
        }

        RangeType.MONTH -> {
            val month = YearMonth.from(anchor)
            val prev = month.minusMonths(1)
            Range(
                type = type,
                startMillis = Time.startOfMonth(month),
                endMillisExclusive = Time.endOfMonthExclusive(month),
                label = Time.formatMonth(month),
                previousStartMillis = Time.startOfMonth(prev),
                previousEndMillisExclusive = Time.endOfMonthExclusive(prev),
                previousLabel = Time.formatMonth(prev),
            )
        }

        RangeType.CUSTOM -> {
            val a = customStart ?: Time.startOfMonth(YearMonth.from(anchor))
            val b = customEndInclusive ?: Time.endOfDayInclusive(anchor)
            val start = if (a <= b) a else b
            val endInclusive = if (a <= b) b else a
            val endExclusive = Time.endOfDayExclusive(Time.toLocalDate(endInclusive))
            val span = (endExclusive - start).coerceAtLeast(DAY_MILLIS)
            Range(
                type = type,
                startMillis = start,
                endMillisExclusive = endExclusive,
                label = "${Time.formatMonthDay(start)} - ${Time.formatMonthDay(endInclusive)}",
                previousStartMillis = start - span,
                previousEndMillisExclusive = start,
                previousLabel = "上一段同期",
            )
        }
    }

    // ---------------------------------------------------------------- 聚合

    fun filterInRange(bills: List<Bill>, range: Range): List<Bill> =
        bills.filter { range.contains(it.occurredAt) }

    fun totals(bills: List<Bill>, range: Range): Totals {
        var expense = 0L
        var refund = 0L
        var income = 0L
        var count = 0
        for (bill in bills) {
            if (!range.contains(bill.occurredAt)) continue
            count++
            when {
                bill.isIncome -> income += bill.cnyAmountMinor
                bill.isRefund -> refund += bill.cnyAmountMinor
                else -> expense += bill.cnyAmountMinor
            }
        }
        return Totals(expenseMinor = expense, refundMinor = refund, incomeMinor = income, billCount = count)
    }

    fun categoryStats(
        bills: List<Bill>,
        range: Range,
        categories: List<Category>,
        topLimit: Int = Int.MAX_VALUE,
    ): List<CategoryStat> {
        val byCategory = LinkedHashMap<Long?, MutableAccumulator>()
        for (bill in bills) {
            if (!range.contains(bill.occurredAt)) continue
            if (bill.isIncome) continue
            val acc = byCategory.getOrPut(bill.categoryId) { MutableAccumulator() }
            if (bill.isRefund) {
                acc.refund += bill.cnyAmountMinor
            } else {
                acc.expense += bill.cnyAmountMinor
            }
            acc.count++
            if (acc.name == null && bill.categoryName != null) acc.name = bill.categoryName
            if (acc.emoji == null && bill.categoryEmoji.isNotBlank()) acc.emoji = bill.categoryEmoji
        }
        val totalNet = byCategory.values.sumOf { it.expense - it.refund }
        val categoryById = categories.associateBy { it.id }
        return byCategory.entries
            .map { (id, acc) ->
                val meta = id?.let { categoryById[it] }
                CategoryStat(
                    categoryId = id,
                    name = meta?.name ?: acc.name ?: "未分类",
                    emoji = meta?.emoji ?: acc.emoji ?: "🏷️",
                    colorArgb = meta?.colorArgb ?: DEFAULT_COLOR,
                    expenseMinor = acc.expense,
                    refundMinor = acc.refund,
                    netExpenseMinor = acc.expense - acc.refund,
                    billCount = acc.count,
                    shareBp = shareOf(acc.expense - acc.refund, totalNet),
                )
            }
            .filter { it.netExpenseMinor > 0 || it.expenseMinor > 0 }
            .sortedByDescending { it.netExpenseMinor }
            .let { if (topLimit >= it.size) it else it.take(topLimit) }
    }

    fun tagStats(
        bills: List<Bill>,
        range: Range,
        tags: List<Tag>,
        tagBillIds: Map<Long, Set<Long>>,
        topLimit: Int = Int.MAX_VALUE,
    ): List<TagStat> {
        if (tagBillIds.isEmpty()) return emptyList()
        val inRange = filterInRange(bills, range)
        val tagById = tags.associateBy { it.id }
        val rows = ArrayList<TagStat>()
        for ((tagId, billIds) in tagBillIds) {
            if (billIds.isEmpty()) continue
            var net = 0L
            var count = 0
            for (bill in inRange) {
                if (bill.id !in billIds) continue
                if (bill.isIncome) continue
                net += if (bill.isRefund) -bill.cnyAmountMinor else bill.cnyAmountMinor
                count++
            }
            if (count == 0) continue
            val tag = tagById[tagId]
            rows += TagStat(
                tagId = tagId,
                name = tag?.name ?: "标签 #$tagId",
                colorArgb = tag?.colorArgb ?: DEFAULT_COLOR,
                netExpenseMinor = net,
                billCount = count,
                shareBp = 0,
            )
        }
        val total = totals(bills, range).netExpenseMinor
        return rows
            .map { it.copy(shareBp = shareOf(it.netExpenseMinor, total)) }
            .sortedWith(compareByDescending<TagStat> { it.netExpenseMinor }.thenBy { it.name })
            .let { if (topLimit >= it.size) it else it.take(topLimit) }
    }

    fun dailySeries(bills: List<Bill>, range: Range): List<DailyPoint> {
        val startDate = Time.toLocalDate(range.startMillis)
        val endDate = Time.toLocalDate(range.endMillisExclusive - 1)
        val days = ArrayList<DailyPoint>()
        var date = startDate
        val byDate = HashMap<LocalDate, MutableAccumulator>()
        for (bill in bills) {
            if (!range.contains(bill.occurredAt)) continue
            val d = Time.toLocalDate(bill.occurredAt)
            val acc = byDate.getOrPut(d) { MutableAccumulator() }
            when {
                bill.isIncome -> acc.income += bill.cnyAmountMinor
                bill.isRefund -> acc.refund += bill.cnyAmountMinor
                else -> acc.expense += bill.cnyAmountMinor
            }
        }
        val maxPoints = MAX_DAILY_POINTS
        val step = if ((endDate.toEpochDay() - startDate.toEpochDay() + 1) > maxPoints) {
            ((endDate.toEpochDay() - startDate.toEpochDay() + 1) / maxPoints + 1).toInt()
        } else {
            1
        }
        while (!date.isAfter(endDate)) {
            var expense = 0L
            var refund = 0L
            var income = 0L
            var cursor = date
            repeat(step) {
                val acc = byDate[cursor]
                if (acc != null) {
                    expense += acc.expense
                    refund += acc.refund
                    income += acc.income
                }
                cursor = cursor.plusDays(1)
            }
            days += DailyPoint(
                date = date,
                millis = Time.startOfDay(date),
                expenseMinor = expense,
                refundMinor = refund,
                incomeMinor = income,
            )
            date = date.plusDays(step.toLong())
        }
        return days
    }

    /**
     * 预算执行：每个启用中的预算用自己的周期窗口计算，口径与预算页一致。
     */
    fun budgetExecutions(
        bills: List<Bill>,
        budgets: List<Budget>,
        today: LocalDate = LocalDate.now(Time.zone),
        tagBillIds: Map<Long, Set<Long>> = emptyMap(),
        categoryNames: Map<Long, String> = emptyMap(),
        tagNames: Map<Long, String> = emptyMap(),
    ): List<BudgetExecution> = budgets
        .filter { it.enabled }
        .mapNotNull { budget ->
            val window = BudgetPeriods.windowOf(budget, today)
            if (window.start > window.endInclusive) return@mapNotNull null
            // 分类预算只看该分类的账单，口径与预算页一致。
            val scoped = if (budget.scope == BudgetScope.CATEGORY) {
                bills.filter { it.categoryId == budget.categoryId }
            } else {
                bills
            }
            val status = FinanceCalculator.budgetStatusOf(
                bills = scoped,
                budget = budget,
                window = window,
                today = today,
                tagBillIds = budget.tagId?.let { tagBillIds[it] },
            )
            BudgetExecution(
                budgetId = budget.id,
                name = budget.displayName(
                    categoryName = budget.categoryId?.let { categoryNames[it] },
                    tagName = budget.tagId?.let { tagNames[it] },
                ),
                windowLabel = status.windowLabel,
                amountMinor = budget.amountMinor,
                spentMinor = status.spentMinor,
                availableMinor = status.availableMinor,
                carryOverMinor = status.carryOverMinor,
                usedPercent = status.usedPercent,
                daysLeft = status.daysLeft,
                alertLevel = status.alertLevel,
                timeProgressBp = timeProgressBp(window.start, window.endInclusive, today),
            )
        }
        .sortedByDescending { it.usedPercent }

    fun snapshot(
        bills: List<Bill>,
        range: Range,
        categories: List<Category> = emptyList(),
        tags: List<Tag> = emptyList(),
        tagBillIds: Map<Long, Set<Long>> = emptyMap(),
        budgets: List<Budget> = emptyList(),
        today: LocalDate = LocalDate.now(Time.zone),
        topCategoryLimit: Int = 8,
    ): Snapshot {
        val totals = totals(bills, range)
        val previousTotals = totals(bills, previousRange(range))
        val previous = previousRange(range)
        // 分类/标签的「较上期」必须用同一口径（净支出）算出来的上期数，不能各算一套。
        val previousCategoryNet = categoryStats(bills, previous, categories)
            .associate { it.categoryId to it.netExpenseMinor }
        val previousTagNet = tagStats(bills, previous, tags, tagBillIds)
            .associate { it.tagId to it.netExpenseMinor }
        val categoryStats = categoryStats(bills, range, categories, topCategoryLimit)
            .map { it.copy(previousNetExpenseMinor = previousCategoryNet[it.categoryId] ?: 0L) }
        val tagStats = tagStats(bills, range, tags, tagBillIds, topCategoryLimit)
            .map { it.copy(previousNetExpenseMinor = previousTagNet[it.tagId] ?: 0L) }
        return Snapshot(
            range = range,
            totals = totals,
            previousTotals = previousTotals,
            categories = categoryStats,
            tags = tagStats,
            daily = dailySeries(bills, range),
            budgets = budgetExecutions(
                bills = bills,
                budgets = budgets,
                today = today,
                tagBillIds = tagBillIds,
                categoryNames = categories.associate { it.id to it.name },
                tagNames = tags.associate { it.id to it.name },
            ),
            hasData = totals.billCount > 0 && (totals.expenseMinor != 0L || totals.incomeMinor != 0L),
        )
    }

    /** 周期时间进度（万分比）：还没开始为 0，已结束为 10000。 */
    fun timeProgressBp(startMillis: Long, endInclusive: Long, today: LocalDate): Int {
        if (endInclusive < startMillis) return 0
        val start = Time.toLocalDate(startMillis)
        val end = Time.toLocalDate(endInclusive)
        val totalDays = ChronoUnit.DAYS.between(start, end) + 1
        if (totalDays <= 0L) return 0
        val elapsedDays = (ChronoUnit.DAYS.between(start, today) + 1).coerceIn(0L, totalDays)
        return ((elapsedDays * 10_000L) / totalDays).toInt()
    }

    fun previousRange(range: Range): Range = range.copy(
        startMillis = range.previousStartMillis,
        endMillisExclusive = range.previousEndMillisExclusive,
        label = range.previousLabel,
    )

    /** 变化百分比（四舍五入）；基期为 0 时返回 null，表示无法比较。 */
    fun changePercent(current: Long, previous: Long): Int? {
        if (previous == 0L) return null
        val bp = (current - previous) * 10_000.0 / previous
        return Math.round(bp / 100.0).toInt()
    }

    fun shareOf(part: Long, total: Long): Int {
        if (total <= 0L || part <= 0L) return 0
        return ((part * 10_000.0) / total).toInt()
    }

    private class MutableAccumulator {
        var expense: Long = 0L
        var refund: Long = 0L
        var income: Long = 0L
        var count: Int = 0
        var name: String? = null
        var emoji: String? = null
    }

    private const val MAX_DAILY_POINTS = 31
    private const val DEFAULT_COLOR = 0xFF6750A4.toInt()
}