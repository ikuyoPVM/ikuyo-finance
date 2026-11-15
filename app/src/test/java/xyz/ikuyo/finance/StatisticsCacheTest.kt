package xyz.ikuyo.finance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.Statistics
import xyz.ikuyo.finance.domain.usecase.StatisticsCache
import java.time.LocalDate
import java.time.LocalTime

/**
 * 统计缓存的回归测试（计划书第 9 章）：同样输入只算一次，任何输入变化都自动失效。
 */
class StatisticsCacheTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 15)

    private val categories = listOf(
        Category(id = 1L, name = "餐饮", emoji = "🍜", sortOrder = 1),
        Category(id = 2L, name = "交通出行", emoji = "🚇", sortOrder = 2),
    )
    private val tags = listOf(Tag(id = 1L, name = "work"))
    private val budgets = listOf(
        Budget(
            id = 1L,
            name = "餐饮",
            amountMinor = 50_000L,
            periodType = BudgetPeriodType.MONTHLY,
            scope = BudgetScope.CATEGORY,
            categoryId = 1L,
        ),
    )

    @Before
    fun setUp() = StatisticsCache.clear()

    @After
    fun tearDown() = StatisticsCache.clear()

    @Test
    fun `相同输入只计算一次并返回同一个快照`() {
        val bills = listOf(expense(1L, 3_000L, 1L, 3, 10))

        val first = snapshot(bills)
        val second = snapshot(bills)

        assertSame(first, second)
        assertEquals(1, StatisticsCache.hitCount)
        assertEquals(1, StatisticsCache.missCount)
    }

    @Test
    fun `账单变化后缓存失效并给出新结果`() {
        val before = snapshot(listOf(expense(1L, 3_000L, 1L, 3, 10)))
        val after = snapshot(
            listOf(expense(1L, 3_000L, 1L, 3, 10), expense(2L, 2_000L, 1L, 3, 11)),
        )

        assertEquals(3_000L, before.totals.netExpenseMinor)
        assertEquals(5_000L, after.totals.netExpenseMinor)
        assertEquals(2, StatisticsCache.missCount)
        assertEquals(0, StatisticsCache.hitCount)
    }

    @Test
    fun `切换区间或预算变化都会重新计算`() {
        val bills = listOf(expense(1L, 3_000L, 1L, 3, 10))
        val month = Statistics.range(Statistics.RangeType.MONTH, today)
        val day = Statistics.range(Statistics.RangeType.DAY, today)

        StatisticsCache.snapshot(bills, month, categories, tags, emptyMap(), budgets, today)
        StatisticsCache.snapshot(bills, day, categories, tags, emptyMap(), budgets, today)
        StatisticsCache.snapshot(bills, month, categories, tags, emptyMap(), emptyList(), today)

        assertEquals(3, StatisticsCache.missCount)
    }

    @Test
    fun `大量账单能在本地算完且日序列不超过三十一点`() {
        val bills = ArrayList<Bill>(5_000)
        for (index in 0 until 5_000) {
            val day = index % 28 + 1
            bills += expense(
                id = index.toLong() + 1,
                amountMinor = 100L + index % 900,
                categoryId = if (index % 3 == 0) 2L else 1L,
                month = 3,
                day = day,
                hour = index % 24,
            )
        }

        val startedAt = System.nanoTime()
        val result = StatisticsCache.snapshot(
            bills = bills,
            range = Statistics.range(Statistics.RangeType.MONTH, today),
            categories = categories,
            tags = tags,
            tagBillIds = emptyMap(),
            budgets = budgets,
            today = today,
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(5_000, result.totals.billCount)
        assertTrue("日序列应聚合到 31 点以内，实际 ${result.daily.size}", result.daily.size <= 31)
        assertEquals(
            result.totals.expenseMinor,
            result.categories.sumOf { it.expenseMinor },
        )
        // 五千笔在中端机上也在几百毫秒内；这里只拦住数量级退化（例如误开了 O(n²)）。
        assertTrue("5000 笔聚合耗时 ${elapsedMillis}ms，超出预期", elapsedMillis < 3_000L)
    }

    private fun snapshot(bills: List<Bill>): Statistics.Snapshot = StatisticsCache.snapshot(
        bills = bills,
        range = Statistics.range(Statistics.RangeType.MONTH, today),
        categories = categories,
        tags = tags,
        tagBillIds = emptyMap(),
        budgets = budgets,
        today = today,
    )

    private fun expense(
        id: Long,
        amountMinor: Long,
        categoryId: Long,
        month: Int,
        day: Int,
        hour: Int = 12,
    ): Bill {
        val occurredAt = Time.toMillis(LocalDate.of(2026, month, day), LocalTime.of(hour, 0))
        return Bill(
            id = id,
            type = BillType.EXPENSE,
            amountMinor = amountMinor,
            exchangeRateScaled = 100_000L,
            cnyAmountMinor = amountMinor,
            categoryId = categoryId,
            categoryName = if (categoryId == 1L) "餐饮" else "交通出行",
            paymentMethod = PaymentMethod.WECHAT,
            occurredAt = occurredAt,
            createdAt = occurredAt,
        )
    }
}