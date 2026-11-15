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
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.BudgetPeriods
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator
import java.time.LocalDate
import java.time.YearMonth

class BudgetPeriodsTest {

    /** 2026-03-15 是周日，便于验证「每周」以周一为首日的边界。 */
    private val today: LocalDate = LocalDate.of(2026, 3, 15)

    private fun bill(
        amountMinor: Long,
        date: LocalDate,
        type: BillType = BillType.EXPENSE,
    ): Bill = Bill(
        type = type,
        amountMinor = amountMinor,
        exchangeRateScaled = Money.RATE_SCALE,
        cnyAmountMinor = amountMinor,
        categoryId = 1L,
        categoryName = "餐饮",
        categoryEmoji = "🍜",
        paymentMethod = PaymentMethod.WECHAT,
        note = "",
        occurredAt = Time.toMillis(date),
        createdAt = Time.toMillis(date),
        source = BillSource.MANUAL,
    )

    private fun budget(
        amountMinor: Long,
        periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
        carryOver: Boolean = false,
        customStart: Long? = null,
        customEnd: Long? = null,
    ): Budget = Budget(
        id = 1L,
        scope = BudgetScope.TOTAL,
        categoryId = null,
        amountMinor = amountMinor,
        periodType = periodType,
        carryOver = carryOver,
        customStart = customStart,
        customEnd = customEnd,
    )

    @Test
    fun `monthly window covers the whole natural month`() {
        val window = BudgetPeriods.window(BudgetPeriodType.MONTHLY, today)
        assertEquals(LocalDate.of(2026, 3, 1), Time.toLocalDate(window.start))
        assertEquals(LocalDate.of(2026, 3, 31), Time.toLocalDate(window.endInclusive))
        assertEquals(Time.startOfMonth(YearMonth.of(2026, 3)), window.start)
        assertEquals(Time.endOfMonthInclusive(YearMonth.of(2026, 3)), window.endInclusive)
        assertTrue(window.contains(Time.toMillis(today)))
    }

    @Test
    fun `weekly window starts on monday and ends on sunday`() {
        val window = BudgetPeriods.window(BudgetPeriodType.WEEKLY, today)
        assertEquals(LocalDate.of(2026, 3, 9), Time.toLocalDate(window.start))
        assertEquals(LocalDate.of(2026, 3, 15), Time.toLocalDate(window.endInclusive))
        assertEquals(java.time.DayOfWeek.MONDAY, Time.toLocalDate(window.start).dayOfWeek)
    }

    @Test
    fun `daily window covers exactly one day`() {
        val window = BudgetPeriods.window(BudgetPeriodType.DAILY, today)
        assertEquals(Time.startOfDay(today), window.start)
        assertEquals(Time.endOfDayInclusive(today), window.endInclusive)
        assertEquals(1, Time.daysLeft(window.endInclusive, today))
    }

    @Test
    fun `custom window keeps given range and swaps reversed input`() {
        val start = Time.startOfDay(LocalDate.of(2026, 2, 20))
        val end = Time.endOfDayInclusive(LocalDate.of(2026, 4, 10))
        val window = BudgetPeriods.window(BudgetPeriodType.CUSTOM, today, start, end)
        assertEquals(start, window.start)
        assertEquals(end, window.endInclusive)

        val swapped = BudgetPeriods.window(BudgetPeriodType.CUSTOM, today, end, start)
        assertEquals(start, swapped.start)
        assertEquals(end, swapped.endInclusive)
    }

    @Test
    fun `custom window without range falls back to natural month`() {
        val window = BudgetPeriods.window(BudgetPeriodType.CUSTOM, today)
        assertEquals(Time.startOfMonth(YearMonth.of(2026, 3)), window.start)
        assertEquals(Time.endOfMonthInclusive(YearMonth.of(2026, 3)), window.endInclusive)
    }

    @Test
    fun `previous window steps back one period`() {
        val monthly = BudgetPeriods.previous(
            type = BudgetPeriodType.MONTHLY,
            current = BudgetPeriods.window(BudgetPeriodType.MONTHLY, today),
        )
        assertNotNull(monthly)
        assertEquals(Time.startOfMonth(YearMonth.of(2026, 2)), monthly!!.start)
        assertEquals(Time.endOfMonthInclusive(YearMonth.of(2026, 2)), monthly.endInclusive)
        assertEquals(LocalDate.of(2026, 2, 1), Time.toLocalDate(monthly.start))
        assertEquals(LocalDate.of(2026, 2, 28), Time.toLocalDate(monthly.endInclusive))

        val weekly = BudgetPeriods.previous(
            type = BudgetPeriodType.WEEKLY,
            current = BudgetPeriods.window(BudgetPeriodType.WEEKLY, today),
        )!!
        assertEquals(LocalDate.of(2026, 3, 2), Time.toLocalDate(weekly.start))
        assertEquals(LocalDate.of(2026, 3, 8), Time.toLocalDate(weekly.endInclusive))

        val daily = BudgetPeriods.previous(
            type = BudgetPeriodType.DAILY,
            current = BudgetPeriods.window(BudgetPeriodType.DAILY, today),
        )!!
        assertEquals(LocalDate.of(2026, 3, 14), Time.toLocalDate(daily.start))
        assertEquals(LocalDate.of(2026, 3, 14), Time.toLocalDate(daily.endInclusive))
    }

    @Test
    fun `previous window for custom range uses the same length`() {
        val start = Time.startOfDay(LocalDate.of(2026, 3, 10))
        val end = Time.endOfDayInclusive(LocalDate.of(2026, 3, 19))
        val current = BudgetPeriods.window(BudgetPeriodType.CUSTOM, today, start, end)
        val previous = BudgetPeriods.previous(BudgetPeriodType.CUSTOM, current, start, end)!!
        assertEquals(LocalDate.of(2026, 2, 28), Time.toLocalDate(previous.start))
        assertEquals(LocalDate.of(2026, 3, 9), Time.toLocalDate(previous.endInclusive))
    }

    @Test
    fun `previous window without custom range is unavailable`() {
        val current = BudgetPeriods.window(BudgetPeriodType.CUSTOM, today)
        assertNull(BudgetPeriods.previous(BudgetPeriodType.CUSTOM, current))
    }

    @Test
    fun `alert level follows 50 80 full and over thresholds`() {
        assertEquals(BudgetAlertLevel.NORMAL, BudgetAlertLevel.of(400_000L, 0L))
        assertEquals(BudgetAlertLevel.NORMAL, BudgetAlertLevel.of(400_000L, 199_999L))
        assertEquals(BudgetAlertLevel.HALF, BudgetAlertLevel.of(400_000L, 200_000L))
        assertEquals(BudgetAlertLevel.HALF, BudgetAlertLevel.of(400_000L, 319_999L))
        assertEquals(BudgetAlertLevel.EIGHTY, BudgetAlertLevel.of(400_000L, 320_000L))
        assertEquals(BudgetAlertLevel.EIGHTY, BudgetAlertLevel.of(400_000L, 399_999L))
        assertEquals(BudgetAlertLevel.FULL, BudgetAlertLevel.of(400_000L, 400_000L))
        assertEquals(BudgetAlertLevel.OVER, BudgetAlertLevel.of(400_000L, 400_001L))
        assertTrue(BudgetAlertLevel.EIGHTY.needsAttention)
        assertTrue(BudgetAlertLevel.FULL.needsAttention)
        assertTrue(BudgetAlertLevel.OVER.needsAttention)
        assertFalse(BudgetAlertLevel.HALF.needsAttention)
        assertFalse(BudgetAlertLevel.NORMAL.needsAttention)
    }

    @Test
    fun `budget status uses the rule window and marks alert level`() {
        val rule = budget(400_000L)
        val bills = listOf(
            bill(320_000L, LocalDate.of(2026, 3, 5)),
            bill(50_000L, LocalDate.of(2026, 2, 20)),
        )
        val window = BudgetPeriods.windowOf(rule, today)
        val status = FinanceCalculator.budgetStatusOf(bills, rule, window, today)
        assertEquals(400_000L, status.amountMinor)
        assertEquals(320_000L, status.spentMinor)
        assertEquals(80_000L, status.remainingMinor)
        assertEquals(80, status.usedPercent)
        assertEquals(BudgetAlertLevel.EIGHTY, status.alertLevel)
        assertEquals(0L, status.carryOverMinor)
        assertTrue(status.windowLabel.isNotBlank())
    }

    @Test
    fun `carry over adds previous period savings`() {
        val rule = budget(400_000L, carryOver = true)
        val bills = listOf(
            bill(300_000L, LocalDate.of(2026, 2, 10)),
            bill(50_000L, LocalDate.of(2026, 3, 10)),
        )
        val window = BudgetPeriods.windowOf(rule, today)
        val status = FinanceCalculator.budgetStatusOf(bills, rule, window, today)
        assertEquals(100_000L, status.carryOverMinor)
        assertEquals(500_000L, status.availableMinor)
        assertEquals(50_000L, status.spentMinor)
        assertEquals(450_000L, status.remainingMinor)
        assertEquals(BudgetAlertLevel.NORMAL, status.alertLevel)
    }

    @Test
    fun `carry over ignores previous period overspend`() {
        val rule = budget(400_000L, carryOver = true)
        val bills = listOf(
            bill(500_000L, LocalDate.of(2026, 2, 10)),
            bill(50_000L, LocalDate.of(2026, 3, 10)),
        )
        val window = BudgetPeriods.windowOf(rule, today)
        val status = FinanceCalculator.budgetStatusOf(bills, rule, window, today)
        assertEquals(0L, status.carryOverMinor)
        assertEquals(400_000L, status.availableMinor)
        assertEquals(350_000L, status.remainingMinor)
    }

    @Test
    fun `no budget keeps spent only`() {
        val bills = listOf(bill(30_000L, today))
        val window = BudgetPeriods.window(BudgetPeriodType.MONTHLY, today)
        val status = FinanceCalculator.budgetStatusOf(bills, null, window, today)
        assertNull(status.amountMinor)
        assertFalse(status.hasBudget)
        assertEquals(30_000L, status.spentMinor)
        assertEquals(BudgetAlertLevel.NORMAL, status.alertLevel)
        assertEquals(0L, status.dailyAvailableMinor)
    }

    @Test
    fun `refund offsets spending inside the window`() {
        val bills = listOf(
            bill(30_000L, LocalDate.of(2026, 3, 11)),
            bill(12_000L, LocalDate.of(2026, 3, 12), BillType.REFUND),
        )
        val window = BudgetPeriods.window(BudgetPeriodType.MONTHLY, today)
        val status = FinanceCalculator.budgetStatusOf(bills, budget(400_000L), window, today)
        assertEquals(18_000L, status.spentMinor)
        assertEquals(382_000L, status.remainingMinor)
    }
}