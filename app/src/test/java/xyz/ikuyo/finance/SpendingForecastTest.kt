package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.SpendingForecast
import xyz.ikuyo.finance.domain.usecase.Statistics
import java.time.LocalDate
import java.time.LocalTime

/**
 * 消费预测（计划书第 5 章）。
 *
 * 这些用例守的是「预测必须可核对」：金额要用给定的账单算得出来，
 * 单日大额不能被当成长期节奏，数据太少必须降可信度。
 */
class SpendingForecastTest {

    private val today = LocalDate.of(2026, 3, 15)
    private val categories = listOf(Category(id = 1L, name = "餐饮", emoji = "🍜", sortOrder = 1))

    private fun at(day: Int, hour: Int = 12): Long =
        Time.toMillis(LocalDate.of(2026, 3, day), LocalTime.of(hour, 0))

    private fun bill(
        id: Long,
        amountMinor: Long,
        day: Int,
        type: BillType = BillType.EXPENSE,
        categoryId: Long? = 1L,
    ) = Bill(
        id = id,
        type = type,
        amountMinor = amountMinor,
        exchangeRateScaled = Money.RATE_SCALE,
        cnyAmountMinor = amountMinor,
        categoryId = categoryId,
        categoryName = if (categoryId == 1L) "餐饮" else null,
        paymentMethod = PaymentMethod.WECHAT,
        occurredAt = at(day),
        createdAt = at(day),
        source = BillSource.MANUAL,
    )

    private fun budget(
        name: String = SpendingForecast.TOTAL_BUDGET_NAME,
        amountMinor: Long = 100_000L,
        usedPercent: Int = 50,
        timeProgressBp: Int = 4_838,
    ) = Statistics.BudgetExecution(
        budgetId = 1L,
        name = name,
        windowLabel = "2026年3月",
        amountMinor = amountMinor,
        spentMinor = amountMinor * usedPercent / 100,
        availableMinor = amountMinor,
        carryOverMinor = 0L,
        usedPercent = usedPercent,
        daysLeft = 16,
        alertLevel = BudgetAlertLevel.NORMAL,
        timeProgressBp = timeProgressBp,
    )

    @Test
    fun `按已发生天数与剩余天数外推本月支出`() {
        // 1 日到 15 日每天 1000 分 = 15000 分，剩余 16 天按同样节奏 → 15000 + 16000 = 31000。
        val bills = (1..15).map { day -> bill(day.toLong(), 1_000L, day) }
        val forecast = SpendingForecast.currentMonth(bills, today, emptyList(), categories)

        assertEquals(31, forecast.daysTotal)
        assertEquals(15, forecast.daysElapsed)
        assertEquals(16, forecast.daysLeft)
        assertEquals(15_000L, forecast.spentMinor)
        assertEquals(1_000L, forecast.dailyAverageMinor)
        assertEquals(1_000L, forecast.typicalDailyMinor)
        assertEquals(31_000L, forecast.projectedTotalMinor)
        assertTrue(forecast.isForecast)
        assertFalse(forecast.hasBudget)
        assertEquals(SpendingForecast.Confidence.HIGH, forecast.confidence)
        assertTrue(forecast.basis.any { it.contains("剩余 16 天") })
    }

    @Test
    fun `单日大额消费不会被当成长期节奏外推`() {
        val bills = (1..14).map { day -> bill(day.toLong(), 500L, day) } + bill(99L, 40_000L, 10)
        val forecast = SpendingForecast.currentMonth(bills, today, emptyList(), categories)

        // 已花 500*14 + 40000 = 47000；10 日那天是 40500 的异常日，剔除后典型一天回到 464
        // （其余 14 天共 6500），不会被 40500 这种量级带偏。
        assertEquals(47_000L, forecast.spentMinor)
        assertEquals(464L, forecast.typicalDailyMinor)
        assertTrue(forecast.typicalDailyMinor < 1_000L)
        assertEquals(47_000L + 16 * 464L, forecast.projectedTotalMinor)
        assertTrue(forecast.basis.any { it.contains("明显高于日常") })
    }

    @Test
    fun `有预算时给出预计剩余或超支金额`() {
        val bills = (1..15).map { day -> bill(day.toLong(), 1_000L, day) }
        val forecast = SpendingForecast.currentMonth(
            bills = bills,
            today = today,
            budgets = listOf(budget(amountMinor = 20_000L, usedPercent = 75)),
            categories = categories,
        )

        assertEquals(20_000L, forecast.budgetAmountMinor)
        assertEquals(31_000L, forecast.projectedTotalMinor)
        assertTrue(forecast.willOverrun)
        assertEquals(11_000L, forecast.projectedOverMinor)
        // 20_000 - 31_000 = -11_000，剩余为负表示会超支。
        assertEquals(-11_000L, forecast.projectedRemainingMinor)
    }

    @Test
    fun `预算足够时预计剩余为正且不标超支`() {
        val bills = (1..15).map { day -> bill(day.toLong(), 1_000L, day) }
        val forecast = SpendingForecast.currentMonth(
            bills = bills,
            today = today,
            budgets = listOf(budget(amountMinor = 50_000L, usedPercent = 30)),
        )

        assertFalse(forecast.willOverrun)
        assertEquals(19_000L, forecast.projectedRemainingMinor)
        assertEquals(0L, forecast.projectedOverMinor)
    }

    @Test
    fun `退款冲减预测基数且不重复计算`() {
        val bills = listOf(
            bill(1L, 10_000L, 3),
            bill(2L, 3_000L, 4, type = BillType.REFUND),
        )
        val forecast = SpendingForecast.currentMonth(bills, today, emptyList(), categories)

        assertEquals(7_000L, forecast.spentMinor)
        assertTrue(forecast.factors.any { it.contains("退款") })
    }

    @Test
    fun `账单太少时降低可信度而不是硬给结论`() {
        val bills = listOf(bill(1L, 8_000L, 14), bill(2L, 2_000L, 15))
        val forecast = SpendingForecast.currentMonth(bills, today, emptyList(), categories)

        assertEquals(SpendingForecast.Confidence.LOW, forecast.confidence)
        assertTrue(forecast.factors.any { it.contains("偏少") })
    }

    @Test
    fun `本月结束时预测等于实际且不再外推`() {
        val endOfMonth = LocalDate.of(2026, 3, 31)
        val bills = (1..31).map { day -> bill(day.toLong(), 300L, day) }
        val forecast = SpendingForecast.currentMonth(bills, endOfMonth, emptyList(), categories)

        assertEquals(0, forecast.daysLeft)
        assertEquals(9_300L, forecast.spentMinor)
        assertEquals(9_300L, forecast.projectedTotalMinor)
        assertEquals(SpendingForecast.Confidence.LOW, forecast.confidence)
        assertTrue(forecast.basis.any { it.contains("本月已结束") })
    }

    @Test
    fun `收入不影响预测的支出总额`() {
        val bills = listOf(
            bill(1L, 4_000L, 5),
            bill(2L, 50_000L, 6, type = BillType.INCOME),
        )
        val forecast = SpendingForecast.currentMonth(bills, today, emptyList(), categories)

        assertEquals(4_000L, forecast.spentMinor)
        assertEquals(4_000L + 16 * (4_000L / 15), forecast.projectedTotalMinor)
    }
}