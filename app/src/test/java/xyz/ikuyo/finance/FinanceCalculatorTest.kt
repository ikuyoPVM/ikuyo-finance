package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator
import java.time.LocalDate
import java.time.YearMonth

class FinanceCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 15)

    private fun bill(
        type: BillType,
        amountMinor: Long,
        date: LocalDate,
        categoryId: Long? = 1L,
        note: String = "",
    ): Bill = Bill(
        type = type,
        amountMinor = amountMinor,
        exchangeRateScaled = Money.RATE_SCALE,
        cnyAmountMinor = amountMinor,
        categoryId = categoryId,
        categoryName = "餐饮",
        categoryEmoji = "🍜",
        paymentMethod = PaymentMethod.WECHAT,
        note = note,
        occurredAt = Time.toMillis(date),
        createdAt = Time.toMillis(date),
        source = BillSource.MANUAL,
    )

    @Test
    fun `balance counts income minus expense plus refund`() {
        val bills = listOf(
            bill(BillType.EXPENSE, 10_000L, today),
            bill(BillType.INCOME, 50_000L, today),
            bill(BillType.REFUND, 3_000L, today),
        )
        val summary = FinanceCalculator.balance(bills)
        assertEquals(43_000L, summary.availableMinor)
        assertEquals(0L, summary.savingsMinor)
        assertEquals(43_000L, summary.totalMinor)
    }

    @Test
    fun `balance moves savings out of available without changing total`() {
        val bills = listOf(bill(BillType.EXPENSE, 20_000L, today))
        val summary = FinanceCalculator.balance(bills, savingsMinor = 5_000L)
        // 可用余额 = 账单净额 − 储蓄余额；储蓄是划转，总余额不变。
        assertEquals(-25_000L, summary.availableMinor)
        assertEquals(5_000L, summary.savingsMinor)
        assertEquals(-20_000L, summary.totalMinor)
    }

    @Test
    fun `refund offsets expense instead of counting as income`() {
        val bills = listOf(
            bill(BillType.EXPENSE, 30_000L, today),
            bill(BillType.REFUND, 12_000L, today),
        )
        val stats = FinanceCalculator.homeStats(bills, today)
        assertEquals(18_000L, stats.todayExpenseMinor)
        assertEquals(0L, stats.monthIncomeMinor)
        assertEquals(-18_000L, stats.monthNetMinor)
    }

    @Test
    fun `homeStats splits today week and month windows`() {
        val bills = listOf(
            bill(BillType.EXPENSE, 1_000L, today),
            bill(BillType.EXPENSE, 2_000L, today.minusDays(1)),
            bill(BillType.EXPENSE, 4_000L, today.minusDays(7)),
            bill(BillType.INCOME, 9_000L, today.minusDays(2)),
        )
        val stats = FinanceCalculator.homeStats(bills, today)
        assertEquals(1_000L, stats.todayExpenseMinor)
        // 2026-03-15 为周日，本周从周一 3/9 起算：3/14、3/15 计入本周
        assertEquals(3_000L, stats.weekExpenseMinor)
        assertEquals(7_000L, stats.monthExpenseMinor)
        assertEquals(9_000L, stats.monthIncomeMinor)
        assertEquals(2_000L, stats.monthNetMinor)
    }

    @Test
    fun `homeStats excludes bills outside current month`() {
        val bills = listOf(
            bill(BillType.EXPENSE, 5_000L, YearMonth.of(2026, 2).atDay(10)),
            bill(BillType.EXPENSE, 8_000L, today),
        )
        val stats = FinanceCalculator.homeStats(bills, today)
        assertEquals(8_000L, stats.monthExpenseMinor)
        assertEquals(5_000L, stats.lastMonthExpenseMinor)
        assertEquals(0.6, stats.monthOverMonthRatio ?: 0.0, 0.0001)
    }

    @Test
    fun `homeStats returns null ratio when last month has no expense`() {
        val bills = listOf(bill(BillType.EXPENSE, 5_000L, today))
        val stats = FinanceCalculator.homeStats(bills, today)
        assertEquals(null, stats.monthOverMonthRatio)
    }

    @Test
    fun `homeStats trend covers seven days ending today`() {
        val bills = listOf(
            bill(BillType.EXPENSE, 1_000L, today),
            bill(BillType.EXPENSE, 2_000L, today.minusDays(6)),
            bill(BillType.EXPENSE, 9_000L, today.minusDays(7)),
        )
        val stats = FinanceCalculator.homeStats(bills, today)
        assertEquals(7, stats.dailyTrend.size)
        assertEquals(today.toEpochDay(), stats.dailyTrend.last().epochDay)
        assertEquals(1_000L, stats.dailyTrend.last().amountMinor)
        assertEquals(2_000L, stats.dailyTrend.first().amountMinor)
    }

    @Test
    fun `budgetStatus reports remaining and overspend`() {
        val start = Time.startOfMonth(YearMonth.of(2026, 3))
        val end = Time.endOfMonthInclusive(YearMonth.of(2026, 3))
        val bills = listOf(bill(BillType.EXPENSE, 320_000L, today))

        val ok = FinanceCalculator.budgetStatus(bills, 400_000L, start, end, today)
        assertEquals(80_000L, ok.remainingMinor)
        assertTrue(!ok.isOverBudget)
        assertEquals(1, ok.progress.compareTo(0f))
        assertEquals(17, ok.daysLeft)
        assertEquals(4_705L, ok.dailyAvailableMinor)

        val over = FinanceCalculator.budgetStatus(bills, 300_000L, start, end, today)
        assertTrue(over.isOverBudget)
        assertEquals(-20_000L, over.remainingMinor)
        assertEquals(0L, over.dailyAvailableMinor)
    }

    @Test
    fun `budgetStatus without budget only reports spending`() {
        val start = Time.startOfMonth(YearMonth.of(2026, 3))
        val end = Time.endOfMonthInclusive(YearMonth.of(2026, 3))
        val bills = listOf(bill(BillType.EXPENSE, 12_000L, today))
        val status = FinanceCalculator.budgetStatus(bills, null, start, end, today)
        assertEquals(12_000L, status.spentMinor)
        assertEquals(null, status.amountMinor)
        assertEquals(0L, status.dailyAvailableMinor)
    }

    @Test
    fun `categorySpent nets refunds and ignores income`() {
        val start = Time.startOfMonth(YearMonth.of(2026, 3))
        val end = Time.endOfMonthInclusive(YearMonth.of(2026, 3))
        val bills = listOf(
            bill(BillType.EXPENSE, 20_000L, today, categoryId = 1L),
            bill(BillType.REFUND, 5_000L, today, categoryId = 1L),
            bill(BillType.INCOME, 99_000L, today, categoryId = 1L),
            bill(BillType.EXPENSE, 7_000L, today, categoryId = 2L),
        )
        assertEquals(15_000L, FinanceCalculator.categorySpent(bills, 1L, start, end))
        assertEquals(7_000L, FinanceCalculator.categorySpent(bills, 2L, start, end))
    }
}