package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.SummarySection
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.MonthlySummaryBuilder
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * 月度总结生成器：九段是否齐全、数字是否与统计服务一致、数据不足时是否如实说明。
 */
class MonthlySummaryBuilderTest {

    private val categories = listOf(
        Category(id = 1L, name = "餐饮", emoji = "🍜", sortOrder = 1),
        Category(id = 2L, name = "交通出行", emoji = "🚇", sortOrder = 2),
    )

    private fun at(month: Int, day: Int, hour: Int = 12): Long =
        Time.toMillis(LocalDate.of(2026, month, day), LocalTime.of(hour, 0))

    private fun bill(
        id: Long,
        type: BillType,
        amountMinor: Long,
        categoryId: Long = 1L,
        month: Int = 3,
        day: Int = 10,
    ): Bill = Bill(
        id = id,
        type = type,
        amountMinor = amountMinor,
        exchangeRateScaled = 100_000L,
        cnyAmountMinor = amountMinor,
        categoryId = categoryId,
        categoryName = categories.firstOrNull { it.id == categoryId }?.name,
        paymentMethod = PaymentMethod.WECHAT,
        occurredAt = at(month, day),
        createdAt = at(month, day),
    )

    private fun budget(id: Long, amountMinor: Long, spentTarget: Long): Budget = Budget(
        id = id,
        name = "餐饮",
        amountMinor = amountMinor,
        periodType = BudgetPeriodType.MONTHLY,
        scope = BudgetScope.CATEGORY,
        categoryId = 1L,
    )

    @Test
    fun `九段标题齐全且顺序固定`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 5_000L),
            bill(2, BillType.EXPENSE, 3_000L, categoryId = 2L),
            bill(3, BillType.INCOME, 200_000L),
            bill(4, BillType.REFUND, 1_000L),
        )
        val draft = MonthlySummaryBuilder.build(
            yearMonth = YearMonth.of(2026, 3),
            bills = bills,
            categories = categories,
            today = LocalDate.of(2026, 3, 20),
        )

        assertEquals("2026-03", draft.yearMonth)
        assertEquals(4, draft.billCount)
        assertEquals(
            listOf(
                SummarySection.TITLE_OVERVIEW,
                SummarySection.TITLE_CATEGORIES,
                SummarySection.TITLE_COMPARE,
                SummarySection.TITLE_BUDGET,
                SummarySection.TITLE_TREND,
                SummarySection.TITLE_HABITS,
                SummarySection.TITLE_FORECAST,
                SummarySection.TITLE_BUDGET_ADVICE,
                SummarySection.TITLE_SAVING,
            ),
            draft.sections.map { it.title },
        )
        assertTrue(draft.sections.all { it.hasContent })
    }

    @Test
    fun `收支总结的数字与统计服务一致`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 5_000L),
            bill(2, BillType.EXPENSE, 3_000L, categoryId = 2L),
            bill(3, BillType.INCOME, 200_000L),
            bill(4, BillType.REFUND, 1_000L),
        )
        val draft = MonthlySummaryBuilder.build(
            yearMonth = YearMonth.of(2026, 3),
            bills = bills,
            categories = categories,
            today = LocalDate.of(2026, 3, 20),
        )

        // 支出 8000 − 退款 1000 = 净支出 7000；收入 200000 不计入净支出。
        assertEquals(7_000L, draft.netExpenseMinor)
        assertEquals(200_000L, draft.incomeMinor)

        val overview = draft.sections.first { it.title == SummarySection.TITLE_OVERVIEW }
        assertTrue(overview.items.any { it.contains("¥70.00") })
        assertTrue(overview.items.any { it.contains("¥2,000.00") || it.contains("2,000.00") })

        val catSection = draft.sections.first { it.title == SummarySection.TITLE_CATEGORIES }
        assertTrue(catSection.items.any { it.contains("餐饮") })
    }

    @Test
    fun `没有账单时如实说明数据不足`() {
        val draft = MonthlySummaryBuilder.build(
            yearMonth = YearMonth.of(2026, 3),
            bills = emptyList(),
            categories = categories,
            today = LocalDate.of(2026, 3, 20),
        )

        assertEquals(0, draft.billCount)
        assertEquals(0L, draft.netExpenseMinor)
        val overview = draft.sections.first { it.title == SummarySection.TITLE_OVERVIEW }
        assertEquals("本月没有符合条件的账单，数据不足以总结。", overview.items.first())
        assertEquals(
            "上月没有账单，无法对比。",
            draft.sections.first { it.title == SummarySection.TITLE_COMPARE }.items.first(),
        )
    }

    @Test
    fun `账单少于三笔时提醒仅供参考`() {
        val bills = listOf(bill(1, BillType.EXPENSE, 5_000L))
        val draft = MonthlySummaryBuilder.build(
            yearMonth = YearMonth.of(2026, 3),
            bills = bills,
            categories = categories,
            today = LocalDate.of(2026, 3, 20),
        )

        val overview = draft.sections.first { it.title == SummarySection.TITLE_OVERVIEW }
        assertTrue(overview.items.any { it.contains("仅供参考") })
    }

    @Test
    fun `预算超支在总结里给出合计金额`() {
        val bills = listOf(
            bill(1, BillType.EXPENSE, 30_000L),
            bill(2, BillType.EXPENSE, 20_000L),
            bill(3, BillType.EXPENSE, 10_000L),
        )
        val draft = MonthlySummaryBuilder.build(
            yearMonth = YearMonth.of(2026, 3),
            bills = bills,
            categories = categories,
            budgets = listOf(budget(1L, 40_000L, 60_000L)),
            today = LocalDate.of(2026, 3, 20),
        )

        // 已花 60000 超过额度 40000，超支 20000。
        assertEquals(20_000L, draft.overspendMinor)
        val budgetSection = draft.sections.first { it.title == SummarySection.TITLE_BUDGET }
        assertTrue(budgetSection.items.first().contains("超支"))
        assertTrue(budgetSection.items.first().contains("¥200.00"))
    }

    @Test
    fun `分类金额与占比只列前五并汇总其余`() {
        val many = List(7) { index ->
            Category(id = (index + 1).toLong(), name = "分类${index + 1}", emoji = "🏷️", sortOrder = index)
        }
        val bills = many.mapIndexed { index, category ->
            Bill(
                id = (index + 1).toLong(),
                type = BillType.EXPENSE,
                amountMinor = (10_000L - index * 1_000L),
                exchangeRateScaled = 100_000L,
                cnyAmountMinor = (10_000L - index * 1_000L),
                categoryId = category.id,
                categoryName = category.name,
                paymentMethod = PaymentMethod.WECHAT,
                occurredAt = at(3, 10),
                createdAt = at(3, 10),
            )
        }
        val draft = MonthlySummaryBuilder.build(
            yearMonth = YearMonth.of(2026, 3),
            bills = bills,
            categories = many,
            today = LocalDate.of(2026, 3, 20),
        )

        val catSection = draft.sections.first { it.title == SummarySection.TITLE_CATEGORIES }
        assertTrue(catSection.items.any { it.startsWith("其余") })
        assertEquals(6, catSection.items.size)
    }
}