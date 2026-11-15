package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Installment
import xyz.ikuyo.finance.domain.model.InstallmentPayment
import xyz.ikuyo.finance.domain.usecase.InstallmentSchedule
import java.time.LocalDate
import java.time.LocalTime

/**
 * 分期计划的算术与口径测试。
 *
 * 重点覆盖会真实出账的三件事：
 * 1. 到期日按月顺延时短月份回退但不漂移（1/31 → 2/28 → 3/31）；
 * 2. 各期金额之和必须等于总金额（哪怕除不尽也不能丢分钱）；
 * 3. 未付款金额只是「未来待还」，提前结束后不再算。
 */
class InstallmentScheduleTest {

    private fun plan(
        totalMinor: Long = 12_000L,
        principalMinor: Long = 12_000L,
        feeMinor: Long = 0L,
        interestMinor: Long = 0L,
        periodCount: Int = 12,
        firstPeriodMinor: Long = 1_000L,
        perPeriodMinor: Long = 1_000L,
        dueAt: Long = at(1, 31),
        anchorDay: Int = 31,
        closedEarlyAt: Long? = null,
        name: String = "笔记本电脑",
    ) = Installment(
        id = 1L,
        name = name,
        totalMinor = totalMinor,
        principalMinor = principalMinor,
        feeMinor = feeMinor,
        interestMinor = interestMinor,
        periodCount = periodCount,
        firstPeriodMinor = firstPeriodMinor,
        perPeriodMinor = perPeriodMinor,
        dueAt = dueAt,
        anchorDay = anchorDay,
        closedEarlyAt = closedEarlyAt,
    )

    private fun payment(periodIndex: Int, amountMinor: Long = 1_000L, installmentId: Long = 1L) =
        InstallmentPayment(
            installmentId = installmentId,
            periodIndex = periodIndex,
            billId = 100L + periodIndex,
            amountMinor = amountMinor,
            dueAt = at(1, 31),
            paidAt = at(1, 31, hour = 9),
        )

    private fun at(month: Int, day: Int, hour: Int = 12): Long =
        Time.toMillis(LocalDate.of(2026, month, day), LocalTime.of(hour, 0))

    @Test
    fun `到期日按月顺延时短月份回退但不漂移`() {
        val dates = InstallmentSchedule.dueDates(plan(periodCount = 3, dueAt = at(1, 31), anchorDay = 31))
        assertEquals(3, dates.size)
        assertEquals(LocalDate.of(2026, 1, 31), Time.toLocalDate(dates[0]))
        assertEquals(LocalDate.of(2026, 2, 28), Time.toLocalDate(dates[1]))
        // 关键：3 月回到 31 日，而不是一路退化成 28 日。
        assertEquals(LocalDate.of(2026, 3, 31), Time.toLocalDate(dates[2]))
    }

    @Test
    fun `每期金额按首期与后续两档展开`() {
        val p = plan(periodCount = 3, firstPeriodMinor = 4_000L, perPeriodMinor = 4_000L)
        assertEquals(4_000L, p.amountOf(0))
        assertEquals(4_000L, p.amountOf(1))
        assertEquals(4_000L, p.amountOf(2))
        assertFalse(p.hasUnevenFirstPeriod)

        val uneven = plan(totalMinor = 12_000L, periodCount = 3, firstPeriodMinor = 4_500L, perPeriodMinor = 3_750L)
        assertEquals(4_500L, uneven.amountOf(0))
        assertEquals(3_750L, uneven.amountOf(2))
        assertTrue(uneven.hasUnevenFirstPeriod)
        assertEquals(12_000L, uneven.scheduleTotalMinor)
    }

    @Test
    fun `每期金额之和必须等于总还款金额`() {
        // 10000 分 3 期，均分余数进首期：3334 + 3333 + 3333 = 10000，一分不丢。
        val (first, per) = InstallmentSchedule.evenSplit(10_000L, 3)
        assertEquals(3_334L, first)
        assertEquals(3_333L, per)
        val p = plan(
            totalMinor = 10_000L,
            principalMinor = 10_000L,
            periodCount = 3,
            firstPeriodMinor = first,
            perPeriodMinor = per,
        )
        assertEquals(10_000L, p.scheduleTotalMinor)
        assertNull(InstallmentSchedule.validate(p))
    }

    @Test
    fun `单期分期不需要后续每期金额`() {
        val (first, per) = InstallmentSchedule.evenSplit(9_900L, 1)
        assertEquals(9_900L, first)
        assertEquals(0L, per)
        val p = plan(
            totalMinor = 9_900L,
            principalMinor = 9_900L,
            periodCount = 1,
            firstPeriodMinor = 9_900L,
            perPeriodMinor = 0L,
        )
        assertEquals(9_900L, p.scheduleTotalMinor)
        assertNull(InstallmentSchedule.validate(p))
    }

    @Test
    fun `建议每期金额在给定首期时按剩余期数均分`() {
        assertEquals(1_000L, InstallmentSchedule.suggestPerPeriod(12_000L, 12))
        // 首期多付 2000，剩下的 10000 分 11 期 → 909（余数由用户决定要不要调）。
        assertEquals(909L, InstallmentSchedule.suggestPerPeriod(12_000L, 12, firstPeriodMinor = 2_000L))
        assertEquals(0L, InstallmentSchedule.suggestPerPeriod(12_000L, 1))
    }

    @Test
    fun `还款进度给出已还金额与下一期`() {
        val p = plan(periodCount = 3, firstPeriodMinor = 1_000L, perPeriodMinor = 1_000L, totalMinor = 3_000L, principalMinor = 3_000L)
        val progress = InstallmentSchedule.progress(p, listOf(payment(0), payment(1)))
        assertEquals(3, progress.periodCount)
        assertEquals(2, progress.paidCount)
        assertEquals(2_000L, progress.paidMinor)
        assertEquals(1_000L, progress.remainingMinor)
        assertEquals(1, progress.remainingCount)
        assertFalse(progress.isFinished)
        val next = progress.nextPeriod
        assertNotNull(next)
        assertEquals(2, next!!.index)
        assertEquals(3, next.periodNumber)
        assertEquals(LocalDate.of(2026, 3, 31), Time.toLocalDate(next.dueAt))
        // 已还 2000 / 总 3000 → 6666‱。
        assertEquals(6_666, progress.progressBp)
    }

    @Test
    fun `全部付清后没有下一期`() {
        val p = plan(periodCount = 2, totalMinor = 2_000L, principalMinor = 2_000L)
        val progress = InstallmentSchedule.progress(p, listOf(payment(0), payment(1)))
        assertTrue(progress.isFinished)
        assertEquals(0L, progress.remainingMinor)
        assertNull(progress.nextPeriod)
        assertEquals(10_000, progress.progressBp)
    }

    @Test
    fun `提前结束后剩余期数不再算未来待还`() {
        val p = plan(periodCount = 6, totalMinor = 6_000L, principalMinor = 6_000L, closedEarlyAt = at(2, 10))
        assertTrue(p.isClosed)
        assertFalse(p.isActive)
        val progress = InstallmentSchedule.progress(p, listOf(payment(0)))
        assertEquals(1, progress.paidCount)
        // 已付的照旧，未付的不再计入余额压力。
        assertEquals(1_000L, progress.paidMinor)
        assertEquals(0L, progress.remainingMinor)
        assertNull(progress.nextPeriod)
        assertFalse(progress.isFinished)
    }

    @Test
    fun `校验拦住金额对不上的计划`() {
        assertEquals("请填写分期名称", InstallmentSchedule.validate(plan(name = "  ")))
        assertEquals("分期期数至少要 1 期", InstallmentSchedule.validate(plan(periodCount = 0)))
        assertEquals("分期期数不能超过 60 期", InstallmentSchedule.validate(plan(periodCount = 61)))
        assertEquals("首期金额必须大于 0", InstallmentSchedule.validate(plan(firstPeriodMinor = 0L)))
        assertEquals("每期金额不能为负", InstallmentSchedule.validate(plan(perPeriodMinor = -1L)))
        assertEquals(
            "本金 + 手续费 + 利息应等于总还款金额",
            InstallmentSchedule.validate(plan(totalMinor = 12_000L, principalMinor = 11_000L)),
        )
        assertEquals(
            "各期金额之和应等于总还款金额",
            InstallmentSchedule.validate(
                plan(
                    totalMinor = 12_000L,
                    principalMinor = 12_000L,
                    periodCount = 12,
                    firstPeriodMinor = 1_000L,
                    perPeriodMinor = 1_100L,
                ),
            ),
        )
        assertEquals("请选择首期还款日期", InstallmentSchedule.validate(plan(dueAt = 0L)))
    }

    @Test
    fun `手续费与利息计入总金额但与本金分开`() {
        val p = plan(
            totalMinor = 12_600L,
            principalMinor = 12_000L,
            feeMinor = 300L,
            interestMinor = 300L,
            periodCount = 12,
            firstPeriodMinor = 1_050L,
            perPeriodMinor = 1_050L,
        )
        assertEquals(12_600L, p.scheduleTotalMinor)
        assertNull(InstallmentSchedule.validate(p))
    }

    @Test
    fun `提醒窗口按到期日与今天的天数判断`() {
        val today = LocalDate.of(2026, 1, 25)
        val soon = InstallmentSchedule.periods(
            plan(periodCount = 2, dueAt = at(1, 31), anchorDay = 31),
            emptyList(),
        ).first()
        assertTrue(InstallmentSchedule.shouldRemind(soon, remindDays = 7, today = today))
        assertFalse(InstallmentSchedule.shouldRemind(soon, remindDays = 3, today = today))
        val overdue = InstallmentSchedule.periods(
            plan(periodCount = 2, dueAt = at(1, 20), anchorDay = 20),
            emptyList(),
        ).first()
        assertTrue(InstallmentSchedule.shouldRemind(overdue, remindDays = 0, today = today))
    }
}