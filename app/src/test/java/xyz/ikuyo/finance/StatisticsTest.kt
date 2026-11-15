package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.Statistics
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * 统一统计口径的单元测试。
 *
 * 固定 today = 2026-03-15（周日），便于验证周/月/自定义区间边界。
 */
class StatisticsTest {

    private val today = LocalDate.of(2026, 3, 15)

    private fun at(month: Int, day: Int, hour: Int = 12): Long =
        Time.toMillis(LocalDate.of(2026, month, day), LocalTime.of(hour, 0))

    private fun bill(
        id: Long,
        type: BillType,
        amountMinor: Long,
        categoryId: Long? = 1L,
        categoryName: String? = "餐饮",
        occurredAt: Long = at(3, 15),
    ) = Bill(
        id = id,
        type = type,
        amountMinor = amountMinor,
        exchangeRateScaled = 1_000_000L,
        cnyAmountMinor = amountMinor,
        categoryId = categoryId,
        categoryName = categoryName,
        paymentMethod = PaymentMethod.WECHAT,
        occurredAt = occurredAt,
        createdAt = occurredAt,
    )

    private val categories = listOf(
        Category(id = 1L, name = "餐饮", emoji = "🍜", sortOrder = 1),
        Category(id = 2L, name = "交通出行", emoji = "🚇", sortOrder = 2),
    )

    // ------------------------------------------------------------ 区间

    @Test
    fun `月区间是自然月且带上一月对比区间`() {
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        assertEquals(Time.startOfMonth(YearMonth.of(2026, 3)), range.startMillis)
        assertEquals(Time.endOfMonthExclusive(YearMonth.of(2026, 3)), range.endMillisExclusive)
        assertEquals(31, range.dayCount)
        assertEquals(Time.startOfMonth(YearMonth.of(2026, 2)), range.previousStartMillis)
        assertEquals(Time.endOfMonthExclusive(YearMonth.of(2026, 2)), range.previousEndMillisExclusive)
        assertTrue(range.contains(at(3, 1, 0)))
        assertTrue(range.contains(at(3, 31, 23)))
        assertFalse(range.contains(at(4, 1, 0)))
    }

    @Test
    fun `周区间从周一开始共七天`() {
        val range = Statistics.range(Statistics.RangeType.WEEK, today)
        assertEquals(LocalDate.of(2026, 3, 9), Time.toLocalDate(range.startMillis))
        assertEquals(7, range.dayCount)
        assertEquals(LocalDate.of(2026, 3, 2), Time.toLocalDate(range.previousStartMillis))
        assertEquals(7, (range.previousEndMillisExclusive - range.previousStartMillis) / 86_400_000L)
    }

    @Test
    fun `日区间只含当天并以上一天为对比`() {
        val range = Statistics.range(Statistics.RangeType.DAY, today)
        assertEquals(1, range.dayCount)
        assertTrue(range.contains(at(3, 15, 0)))
        assertFalse(range.contains(at(3, 14, 23)))
        assertEquals(LocalDate.of(2026, 3, 14), Time.toLocalDate(range.previousStartMillis))
    }

    @Test
    fun `自定义区间会自动纠正起止顺序且上一段等长`() {
        val start = Time.startOfDay(LocalDate.of(2026, 3, 10))
        val end = Time.endOfDayInclusive(LocalDate.of(2026, 3, 12))
        val range = Statistics.range(Statistics.RangeType.CUSTOM, today, customStart = end, customEndInclusive = start)
        assertEquals(start, range.startMillis)
        assertEquals(Time.endOfDayExclusive(LocalDate.of(2026, 3, 12)), range.endMillisExclusive)
        assertEquals(3, range.dayCount)
        assertEquals(start - (range.endMillisExclusive - start), range.previousStartMillis)
        assertEquals(start, range.previousEndMillisExclusive)
    }

    // ------------------------------------------------------------ 汇总口径

    @Test
    fun `退款冲减支出且不计收入`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 10_000L),
            bill(2, BillType.EXPENSE, 5_000L),
            bill(3, BillType.REFUND, 3_000L),
            bill(4, BillType.INCOME, 100_000L),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val totals = Statistics.totals(bills, range)
        assertEquals(15_000L, totals.expenseMinor)
        assertEquals(3_000L, totals.refundMinor)
        assertEquals(12_000L, totals.netExpenseMinor)
        assertEquals(100_000L, totals.incomeMinor)
        assertEquals(88_000L, totals.netMinor)
        assertEquals(4, totals.billCount)
        assertTrue(totals.hasData)
    }

    @Test
    fun `区间外的账单不参与统计`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 1_000L, occurredAt = at(2, 28)),
            bill(2, BillType.EXPENSE, 2_000L, occurredAt = at(3, 15)),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val totals = Statistics.totals(bills, range)
        assertEquals(2_000L, totals.netExpenseMinor)
        assertEquals(1, totals.billCount)
    }

    @Test
    fun `上期对比给出变化百分比且基期为零时为未知`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 30_000L, occurredAt = at(3, 10)),
            bill(2, BillType.EXPENSE, 10_000L, occurredAt = at(2, 10)),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val snapshot = Statistics.snapshot(bills, range, categories = categories, today = today)
        assertEquals(200, snapshot.expenseChangePercent)
        assertNull(snapshot.incomeChangePercent)
        assertNull(Statistics.changePercent(100L, 0L))
        assertEquals(0, Statistics.changePercent(100L, 100L))
    }

    // ------------------------------------------------------------ 分类统计

    @Test
    fun `分类统计按净支出排序并给出占比`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 6_000L, categoryId = 1L),
            bill(2, BillType.EXPENSE, 2_000L, categoryId = 2L, categoryName = "交通出行"),
            bill(3, BillType.EXPENSE, 2_000L, categoryId = 2L, categoryName = "交通出行"),
            bill(4, BillType.REFUND, 1_000L, categoryId = 1L),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val stats = Statistics.categoryStats(bills, range, categories)
        assertEquals(2, stats.size)
        // 餐饮：支出 6000 − 退款 1000 = 5000，高于交通出行的 4000。
        assertEquals("餐饮", stats[0].name)
        assertEquals(2, stats[0].billCount)
        assertEquals("🍜", stats[0].emoji)
        val transport = stats.first { it.categoryId == 2L }
        assertEquals(4_000L, transport.netExpenseMinor)
        assertEquals(2, transport.billCount)
        val dining = stats.first { it.categoryId == 1L }
        assertEquals(5_000L, dining.netExpenseMinor)
        assertEquals(1_000L, dining.refundMinor)
        assertEquals(5_555, dining.shareBp)
        assertEquals(4_444, transport.shareBp)
        assertEquals(9_000L, stats.sumOf { it.netExpenseMinor })
    }

    @Test
    fun `没有分类的账单归入未分类`() {
        val bills = listOf(bill(1, BillType.EXPENSE, 800L, categoryId = null, categoryName = null))
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val stats = Statistics.categoryStats(bills, range, categories)
        assertEquals(1, stats.size)
        assertEquals("未分类", stats[0].name)
        assertNull(stats[0].categoryId)
        assertEquals(10_000, stats[0].shareBp)
    }

    // ------------------------------------------------------------ 标签统计

    @Test
    fun `标签统计按标签汇总净支出并支持一账多标签`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 3_000L),
            bill(2, BillType.EXPENSE, 1_000L),
            bill(3, BillType.REFUND, 500L),
        )
        val tags = listOf(Tag(id = 10L, name = "work"), Tag(id = 11L, name = "出差"))
        val links = mapOf(10L to setOf(1L, 2L, 3L), 11L to setOf(1L))
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val stats = Statistics.tagStats(bills, range, tags, links)
        assertEquals(2, stats.size)
        val work = stats.first { it.tagId == 10L }
        assertEquals(3_500L, work.netExpenseMinor)
        assertEquals(3, work.billCount)
        assertEquals("work", work.name)
        assertEquals(10_000, work.shareBp)
    }

    // ------------------------------------------------------------ 日序列

    @Test
    fun `日序列覆盖区间每一天且支出与汇总一致`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 1_000L, occurredAt = at(3, 1)),
            bill(2, BillType.EXPENSE, 2_000L, occurredAt = at(3, 1)),
            bill(3, BillType.REFUND, 500L, occurredAt = at(3, 2)),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val series = Statistics.dailySeries(bills, range)
        assertEquals(31, series.size)
        assertEquals(3_000L, series[0].expenseMinor)
        assertEquals(500L, series[1].refundMinor)
        assertEquals(2_500L, series.sumOf { it.netExpenseMinor })
        assertEquals(Statistics.totals(bills, range).netExpenseMinor, series.sumOf { it.netExpenseMinor })
    }

    @Test
    fun `超长区间的日序列会合桶且不超过上限`() {
        val start = Time.startOfDay(LocalDate.of(2025, 1, 1))
        val end = Time.endOfDayInclusive(LocalDate.of(2026, 3, 15))
        val range = Statistics.range(Statistics.RangeType.CUSTOM, today, customStart = start, customEndInclusive = end)
        val series = Statistics.dailySeries(emptyList(), range)
        assertTrue(series.isNotEmpty())
        assertTrue(series.size <= 31)
    }

    // ------------------------------------------------------------ 预算执行

    @Test
    fun `预算执行只取启用中的预算并按用量排序`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 6_000L),
            bill(2, BillType.EXPENSE, 1_000L, categoryId = 2L, categoryName = "交通出行"),
        )
        val budgets = listOf(
            Budget(id = 1L, scope = BudgetScope.TOTAL, amountMinor = 100_000L),
            Budget(id = 2L, scope = BudgetScope.CATEGORY, categoryId = 2L, amountMinor = 50_000L),
            Budget(id = 3L, scope = BudgetScope.SPECIAL, name = "旅行", amountMinor = 200_000L, enabled = false),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val rows = Statistics.budgetExecutions(
            bills = bills,
            budgets = budgets,
            today = today,
            categoryNames = categories.associate { it.id to it.name },
        )
        assertEquals(2, rows.size)
        // 按用量降序：总预算 7000/100000 = 7%，高于交通出行 1000/50000 = 2%。
        assertEquals("总预算", rows[0].name)
        assertEquals(7_000L, rows[0].spentMinor)
        assertEquals(7, rows[0].usedPercent)
        assertEquals(0L, rows[0].overMinor)
        assertEquals("交通出行", rows[1].name)
        // 分类预算只统计该分类的账单。
        assertEquals(1_000L, rows[1].spentMinor)
        assertEquals(2, rows[1].usedPercent)
        // availableMinor 是「额度 + 结转」，剩余额度由 50000 - 1000 推得。
        assertEquals(50_000L, rows[1].availableMinor)
    }

    @Test
    fun `超支时给出超出金额`() {
        val bills = listOf(bill(1, BillType.EXPENSE, 12_000L))
        val budgets = listOf(Budget(id = 1L, scope = BudgetScope.TOTAL, amountMinor = 10_000L))
        val rows = Statistics.budgetExecutions(bills, budgets, today = today)
        assertEquals(1, rows.size)
        assertEquals(2_000L, rows[0].overMinor)
        // 超支时用量按实际比例给出，不做截断，界面据此显示「已用 120%」。
        assertEquals(120, rows[0].usedPercent)
    }

    // ------------------------------------------------------------ 快照

    @Test
    fun `快照把区间内的账单放在一起且空数据不伪造`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 4_000L),
            bill(2, BillType.INCOME, 50_000L, occurredAt = at(3, 14)),
            bill(3, BillType.EXPENSE, 9_999L, occurredAt = at(2, 20)),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val snapshot = Statistics.snapshot(bills, range, categories = categories, today = today)
        assertTrue(snapshot.hasData)
        assertEquals(4_000L, snapshot.totals.netExpenseMinor)
        assertEquals(50_000L, snapshot.totals.incomeMinor)
        assertEquals(9_999L, snapshot.previousTotals.netExpenseMinor)
        assertEquals(1, snapshot.categories.size)

        val empty = Statistics.snapshot(emptyList(), range, today = today)
        assertFalse(empty.hasData)
        assertTrue(empty.categories.isEmpty())
        assertTrue(empty.tags.isEmpty())
        assertTrue(empty.budgets.isEmpty())
        assertEquals(0L, empty.totals.netExpenseMinor)
    }

    @Test
    fun `快照的分类合计与明细一致`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 1_234L, categoryId = 1L),
            bill(2, BillType.EXPENSE, 4_321L, categoryId = 2L, categoryName = "交通出行"),
            bill(3, BillType.REFUND, 234L, categoryId = 1L),
        )
        val range = Statistics.range(Statistics.RangeType.DAY, today)
        val snapshot = Statistics.snapshot(bills, range, categories = categories, today = today)
        assertEquals(snapshot.totals.netExpenseMinor, snapshot.categories.sumOf { it.netExpenseMinor })
        assertEquals(1_000L + 4_321L, snapshot.totals.netExpenseMinor)
    }

    @Test
    fun `万分比在合计为零时返回零`() {
        assertEquals(0, Statistics.shareOf(0L, 0L))
        assertEquals(0, Statistics.shareOf(100L, 0L))
        assertEquals(10_000, Statistics.shareOf(500L, 500L))
        assertEquals(5_000, Statistics.shareOf(1L, 2L))
    }

    @Test
    fun `分类给出与上期对比的变化百分比`() {
        val bills = listOf(
            // 本期（3 月）：餐饮 6000。
            bill(1, BillType.EXPENSE, 6_000L, categoryId = 1L, occurredAt = at(3, 10)),
            // 上期（2 月）：餐饮 3000，交通 1000。
            bill(2, BillType.EXPENSE, 3_000L, categoryId = 1L, occurredAt = at(2, 10)),
            bill(3, BillType.EXPENSE, 1_000L, categoryId = 2L, categoryName = "交通出行", occurredAt = at(2, 11)),
        )
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val snapshot = Statistics.snapshot(bills, range, categories = categories, today = today)

        val dining = snapshot.categories.first { it.categoryId == 1L }
        assertEquals(6_000L, dining.netExpenseMinor)
        assertEquals(3_000L, dining.previousNetExpenseMinor)
        // 3000 → 6000 是 +100%。
        assertEquals(100, dining.changePercent)

        // 交通本期没有支出，不进当期排名，所以不会出现在列表里。
        assertTrue(snapshot.categories.none { it.categoryId == 2L })
    }

    @Test
    fun `上期没有同类支出时变化百分比为未知`() {
        val bills = listOf(bill(1, BillType.EXPENSE, 2_000L, categoryId = 1L))
        val range = Statistics.range(Statistics.RangeType.MONTH, today)
        val snapshot = Statistics.snapshot(bills, range, categories = categories, today = today)
        val dining = snapshot.categories.first { it.categoryId == 1L }
        assertEquals(0L, dining.previousNetExpenseMinor)
        // 基期为 0 不能算出百分比，返回 null 表示未知，而不是 0%。
        assertNull(dining.changePercent)
    }

    @Test
    fun `周期时间进度按天计算并夹在两端`() {
        val start = Time.startOfDay(LocalDate.of(2026, 3, 1))
        val endInclusive = Time.endOfDayInclusive(LocalDate.of(2026, 3, 31))
        // 3 月共 31 天，第 15 天 → 15/31 ≈ 48.38%。
        assertEquals(4_838, Statistics.timeProgressBp(start, endInclusive, today))
        // 还没开始 → 0。
        assertEquals(0, Statistics.timeProgressBp(start, endInclusive, LocalDate.of(2026, 2, 20)))
        // 已经结束 → 100%。
        assertEquals(10_000, Statistics.timeProgressBp(start, endInclusive, LocalDate.of(2026, 4, 2)))
    }

    @Test
    fun `预算节奏给出超前与落后金额`() {
        val behind = Statistics.BudgetExecution(
            budgetId = 1L,
            name = "总预算",
            windowLabel = "2026年3月",
            amountMinor = 10_000L,
            spentMinor = 3_000L,
            availableMinor = 10_000L,
            carryOverMinor = 0L,
            usedPercent = 30,
            daysLeft = 16,
            alertLevel = BudgetAlertLevel.NORMAL,
            timeProgressBp = 5_000,
        )
        // 时间过半应花 5000，实际花 3000 → 落后 2000。
        assertEquals(5_000L, behind.expectedMinor)
        assertEquals(-2_000L, behind.paceMinor)

        val ahead = behind.copy(spentMinor = 8_000L, usedPercent = 80)
        assertEquals(3_000L, ahead.paceMinor)
    }
}