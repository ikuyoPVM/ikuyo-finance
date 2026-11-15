package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Debt
import xyz.ikuyo.finance.domain.model.DebtPayment
import xyz.ikuyo.finance.domain.model.DebtType
import xyz.ikuyo.finance.domain.usecase.DebtLedger
import java.time.LocalDate
import java.time.LocalTime

/**
 * 阶段五第 4 步：债务口径测试。
 *
 * 这里守住三条底线：本金不动收支、只有利息进收支、本金流转挂在债务上不重复统计。
 */
class DebtLedgerTest {

    private val today = LocalDate.of(2026, 3, 15)

    private fun at(month: Int, day: Int, hour: Int = 12): Long =
        Time.toMillis(LocalDate.of(2026, month, day), LocalTime.of(hour, 0))

    private fun debt(
        id: Long,
        type: DebtType,
        principalMinor: Long,
        dueAt: Long = at(3, 20),
        anchorDay: Int = 20,
        name: String = "借款$id",
        settledAt: Long? = null,
    ) = Debt(
        id = id,
        name = name,
        type = type,
        principalMinor = principalMinor,
        dueAt = dueAt,
        anchorDay = anchorDay,
        settledAt = settledAt,
    )

    private fun payment(debtId: Long, principalMinor: Long, extraMinor: Long = 0L, paidAt: Long = at(3, 10)) =
        DebtPayment(
            debtId = debtId,
            principalMinor = principalMinor,
            extraMinor = extraMinor,
            paidAt = paidAt,
        )

    @Test
    fun `归还本金只减少欠款不算支出`() {
        val target = debt(1, DebtType.BORROWED, 100_000L)
        val payments = listOf(payment(1, principalMinor = 30_000L))

        val progress = DebtLedger.progressOf(target, payments)
        assertEquals(30_000L, progress.repaidPrincipalMinor)
        assertEquals(70_000L, progress.remainingPrincipalMinor)
        assertFalse(progress.isSettled)
        // 本金流转不产生任何利息金额，账单层不会因此出现支出。
        assertEquals(0L, progress.extraPaidMinor)

        val totals = DebtLedger.totalsOf(listOf(target), payments)
        assertEquals(100_000L, totals.borrowedTotalMinor)
        assertEquals(30_000L, totals.repaidPrincipalMinor)
        assertEquals(70_000L, totals.remainingOwedMinor)
        assertEquals(70_000L, totals.netDebtMinor)
        assertEquals(0L, totals.extraPaidMinor)
    }

    @Test
    fun `利息与手续费单独累计`() {
        val target = debt(1, DebtType.CONSUMER_CREDIT, 200_000L)
        val payments = listOf(
            payment(1, principalMinor = 100_000L, extraMinor = 500L),
            payment(1, principalMinor = 0L, extraMinor = 300L, paidAt = at(3, 12)),
        )

        val progress = DebtLedger.progressOf(target, payments)
        assertEquals(100_000L, progress.repaidPrincipalMinor)
        assertEquals(800L, progress.extraPaidMinor)

        val totals = DebtLedger.totalsOf(listOf(target), payments)
        assertEquals(800L, totals.extraPaidMinor)
        assertEquals(0L, totals.extraReceivedMinor)
        assertEquals(100_000L, totals.remainingOwedMinor)
    }

    @Test
    fun `借出方向单独统计且利息算已收`() {
        val lent = debt(2, DebtType.LENT, 50_000L, name = "借给小王")
        val payments = listOf(payment(2, principalMinor = 20_000L, extraMinor = 300L))

        val totals = DebtLedger.totalsOf(listOf(lent), payments)
        assertEquals(0L, totals.borrowedTotalMinor)
        assertEquals(50_000L, totals.lentTotalMinor)
        assertEquals(20_000L, totals.receivedPrincipalMinor)
        assertEquals(30_000L, totals.toReceiveMinor)
        assertEquals(300L, totals.extraReceivedMinor)
        assertEquals(0L, totals.extraPaidMinor)
    }

    @Test
    fun `净债务在借出更多时为负`() {
        val owed = debt(1, DebtType.BORROWED, 10_000L)
        val lent = debt(2, DebtType.LENT, 30_000L)

        val totals = DebtLedger.totalsOf(listOf(owed, lent), emptyList())
        assertEquals(10_000L, totals.remainingOwedMinor)
        assertEquals(30_000L, totals.toReceiveMinor)
        assertEquals(-20_000L, totals.netDebtMinor)
        assertTrue(totals.hasData)
    }

    @Test
    fun `还清后进入已结清且进度为满`() {
        val target = debt(1, DebtType.BORROWED, 20_000L)
        val payments = listOf(
            payment(1, principalMinor = 8_000L),
            payment(1, principalMinor = 12_000L, paidAt = at(3, 14)),
        )

        val progress = DebtLedger.progressOf(target, payments)
        assertTrue(progress.isSettled)
        assertEquals(0L, progress.remainingPrincipalMinor)
        assertEquals(10_000, progress.progressBp)
        assertEquals(at(3, 14), progress.lastPaidAt)
    }

    @Test
    fun `进度按万分比四舍五入向下`() {
        val target = debt(1, DebtType.BORROWED, 30_000L)
        val payments = listOf(payment(1, principalMinor = 10_000L))

        assertEquals(3_333, DebtLedger.progressOf(target, payments).progressBp)
    }

    @Test
    fun `保存校验拦住名称本金与到期日`() {
        assertEquals("请填写债务名称", DebtLedger.validate(debt(0, DebtType.BORROWED, 100L, name = " ")))
        assertEquals("本金必须大于 0", DebtLedger.validate(debt(0, DebtType.BORROWED, 0L)))
        assertEquals("请选择到期日", DebtLedger.validate(debt(0, DebtType.BORROWED, 100L, dueAt = 0L)))
        assertEquals("每月到期日应在 1 到 31 号之间", DebtLedger.validate(debt(0, DebtType.BORROWED, 100L, anchorDay = 32)))
        assertNull(DebtLedger.validate(debt(0, DebtType.BORROWED, 100L)))
    }

    @Test
    fun `还款金额校验拦住负数与超额`() {
        val target = debt(1, DebtType.BORROWED, 10_000L)
        val payments = listOf(payment(1, principalMinor = 4_000L))

        assertEquals(
            "本金金额不能为负",
            DebtLedger.validateRepayment(target, payments, principalMinor = -1L, extraMinor = 0L),
        )
        assertEquals(
            "利息与手续费不能为负",
            DebtLedger.validateRepayment(target, payments, principalMinor = 0L, extraMinor = -1L),
        )
        assertEquals(
            "本金和利息不能都为 0",
            DebtLedger.validateRepayment(target, payments, principalMinor = 0L, extraMinor = 0L),
        )
        val over = DebtLedger.validateRepayment(target, payments, principalMinor = 7_000L, extraMinor = 0L)
        assertTrue(over != null && over.startsWith("本金超出剩余欠款"))
        assertNull(DebtLedger.validateRepayment(target, payments, principalMinor = 6_000L, extraMinor = 0L))
    }

    @Test
    fun `到期天数当天为零逾期为负`() {
        assertEquals(0, DebtLedger.daysUntil(Time.toMillis(today, LocalTime.NOON), today))
        assertEquals(-3, DebtLedger.daysUntil(Time.toMillis(LocalDate.of(2026, 3, 12), LocalTime.NOON), today))
        assertEquals(6, DebtLedger.daysUntil(Time.toMillis(LocalDate.of(2026, 3, 21), LocalTime.NOON), today))
    }

    @Test
    fun `提醒窗口包含逾期与当天`() {
        val dueToday = Time.toMillis(today, LocalTime.NOON)
        assertTrue(DebtLedger.shouldRemind(dueToday, 0, today))
        assertTrue(DebtLedger.shouldRemind(dueToday, 3, today))
        assertTrue(DebtLedger.shouldRemind(Time.toMillis(LocalDate.of(2026, 3, 1), LocalTime.NOON), 0, today))
        assertFalse(DebtLedger.shouldRemind(Time.toMillis(LocalDate.of(2026, 3, 25), LocalTime.NOON), 7, today))
        assertTrue(DebtLedger.shouldRemind(Time.toMillis(LocalDate.of(2026, 3, 20), LocalTime.NOON), 7, today))
    }

    @Test
    fun `月末到期日回退但不漂移`() {
        val jan31 = Time.toMillis(LocalDate.of(2026, 1, 31), LocalTime.NOON)
        val feb = DebtLedger.nextDue(jan31, anchorDay = 31)
        assertEquals(LocalDate.of(2026, 2, 28), Time.toLocalDate(feb))
        val mar = DebtLedger.nextDue(feb, anchorDay = 31)
        assertEquals(LocalDate.of(2026, 3, 31), Time.toLocalDate(mar))

        val mar15 = Time.toMillis(LocalDate.of(2026, 3, 15), LocalTime.NOON)
        assertEquals(LocalDate.of(2026, 4, 15), Time.toLocalDate(DebtLedger.nextDue(mar15, anchorDay = 15)))
    }
}