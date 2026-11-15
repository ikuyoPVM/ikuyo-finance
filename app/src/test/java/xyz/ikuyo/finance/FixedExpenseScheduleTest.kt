package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.FixedExpense
import xyz.ikuyo.finance.domain.model.FixedPeriodType
import xyz.ikuyo.finance.domain.usecase.FixedExpenseSchedule
import java.time.LocalDate
import java.time.LocalTime

/**
 * 固定支出周期推算：重点是「月末日期回退后不能漂移」。
 *
 * 用户设置「每月 31 日」时，2 月没有 31 日 → 回退到 2 月最后一天；3 月必须回到 31 日，
 * 不能一路从 28 日往下漂。所有断言都用毫秒时间戳，和运行时完全一致。
 */
class FixedExpenseScheduleTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Time.toMillis(LocalDate.of(year, month, day), LocalTime.NOON)

    private fun expense(
        periodType: FixedPeriodType,
        dueAt: Long,
        anchorDay: Int = Time.toLocalDate(dueAt).dayOfMonth,
        customDays: Int = 30,
    ) = FixedExpense(
        name = "测试",
        amountMinor = 1_000L,
        periodType = periodType,
        dueAt = dueAt,
        anchorDay = anchorDay,
        customDays = customDays,
    )

    private fun dateOf(millis: Long): LocalDate = Time.toLocalDate(millis)

    @Test
    fun `每月三十一日在短月份回退但下个月回到三十一日`() {
        val e = expense(FixedPeriodType.MONTHLY, at(2026, 1, 31), anchorDay = 31)

        val feb = FixedExpenseSchedule.nextDue(e)
        assertEquals(LocalDate.of(2026, 2, 28), dateOf(feb))

        val mar = FixedExpenseSchedule.nextDue(e, fromDueAt = feb)
        assertEquals(LocalDate.of(2026, 3, 31), dateOf(mar))

        val apr = FixedExpenseSchedule.nextDue(e, fromDueAt = mar)
        assertEquals(LocalDate.of(2026, 4, 30), dateOf(apr))

        val may = FixedExpenseSchedule.nextDue(e, fromDueAt = apr)
        assertEquals(LocalDate.of(2026, 5, 31), dateOf(may))
    }

    @Test
    fun `闰年二月按二十九天回退`() {
        val e = expense(FixedPeriodType.MONTHLY, at(2028, 1, 31), anchorDay = 31)
        val feb = FixedExpenseSchedule.nextDue(e)
        assertEquals(LocalDate.of(2028, 2, 29), dateOf(feb))
        assertEquals(LocalDate.of(2028, 3, 31), dateOf(FixedExpenseSchedule.nextDue(e, fromDueAt = feb)))
    }

    @Test
    fun `三十日锚点在二月回退后仍按三十日推进`() {
        val e = expense(FixedPeriodType.MONTHLY, at(2026, 1, 30), anchorDay = 30)
        val feb = FixedExpenseSchedule.nextDue(e)
        assertEquals(LocalDate.of(2026, 2, 28), dateOf(feb))
        assertEquals(LocalDate.of(2026, 3, 30), dateOf(FixedExpenseSchedule.nextDue(e, fromDueAt = feb)))
    }

    @Test
    fun `每两周与自定义天数按天推进`() {
        val biweekly = expense(FixedPeriodType.BIWEEKLY, at(2026, 3, 2))
        assertEquals(LocalDate.of(2026, 3, 16), dateOf(FixedExpenseSchedule.nextDue(biweekly)))

        val weekly = expense(FixedPeriodType.WEEKLY, at(2026, 3, 2))
        assertEquals(LocalDate.of(2026, 3, 9), dateOf(FixedExpenseSchedule.nextDue(weekly)))

        val custom = expense(FixedPeriodType.CUSTOM, at(2026, 3, 2), customDays = 45)
        assertEquals(LocalDate.of(2026, 4, 16), dateOf(FixedExpenseSchedule.nextDue(custom)))
    }

    @Test
    fun `季度半年与每年按月份推进`() {
        val quarterly = expense(FixedPeriodType.QUARTERLY, at(2026, 1, 15), anchorDay = 15)
        assertEquals(LocalDate.of(2026, 4, 15), dateOf(FixedExpenseSchedule.nextDue(quarterly)))

        val halfYearly = expense(FixedPeriodType.HALF_YEARLY, at(2026, 1, 15), anchorDay = 15)
        assertEquals(LocalDate.of(2026, 7, 15), dateOf(FixedExpenseSchedule.nextDue(halfYearly)))

        val yearly = expense(FixedPeriodType.YEARLY, at(2026, 6, 1), anchorDay = 1)
        assertEquals(LocalDate.of(2027, 6, 1), dateOf(FixedExpenseSchedule.nextDue(yearly)))

        // 闰日设置的每年计划，在平年回退到 2 月 28 日，不报错也不跳到 3 月。
        val leapDay = expense(FixedPeriodType.YEARLY, at(2028, 2, 29), anchorDay = 29)
        assertEquals(LocalDate.of(2029, 2, 28), dateOf(FixedExpenseSchedule.nextDue(leapDay)))
    }

    @Test
    fun `到期与提醒按当天结束时间比较`() {
        val today = LocalDate.of(2026, 3, 15)
        val dueToday = at(2026, 3, 15)

        assertTrue(FixedExpenseSchedule.isDue(dueToday, today))
        assertFalse(FixedExpenseSchedule.isDue(at(2026, 3, 16), today))
        // 昨天到期的仍然算「已到期」，需要出现在待处理里。
        assertTrue(FixedExpenseSchedule.isDue(at(2026, 3, 14), today))

        assertEquals(0, FixedExpenseSchedule.daysUntil(dueToday, today))
        assertEquals(3, FixedExpenseSchedule.daysUntil(at(2026, 3, 18), today))
        assertEquals(-2, FixedExpenseSchedule.daysUntil(at(2026, 3, 13), today))

        assertTrue(FixedExpenseSchedule.shouldRemind(dueToday, remindDays = 0, today = today))
        assertTrue(FixedExpenseSchedule.shouldRemind(at(2026, 3, 18), remindDays = 3, today = today))
        assertFalse(FixedExpenseSchedule.shouldRemind(at(2026, 3, 19), remindDays = 3, today = today))
        // 已过期也应当提醒。
        assertTrue(FixedExpenseSchedule.shouldRemind(at(2026, 3, 1), remindDays = 3, today = today))
    }

    @Test
    fun `首次到期日不早于今天且保持锚点`() {
        val today = LocalDate.of(2026, 3, 15)

        val sameMonth = FixedExpenseSchedule.firstDueOnOrAfter(
            start = at(2026, 1, 31),
            periodType = FixedPeriodType.MONTHLY,
            anchorDay = 31,
            customDays = 30,
            today = today,
        )
        assertEquals(LocalDate.of(2026, 3, 31), dateOf(sameMonth))

        val past = FixedExpenseSchedule.firstDueOnOrAfter(
            start = at(2026, 3, 10),
            periodType = FixedPeriodType.MONTHLY,
            anchorDay = 10,
            customDays = 30,
            today = today,
        )
        assertEquals(LocalDate.of(2026, 4, 10), dateOf(past))
    }

    @Test
    fun `周期文案可读`() {
        assertEquals("每月 31 日", FixedExpense.scheduleLabel(FixedPeriodType.MONTHLY, 31, 30))
        assertEquals("每两周", FixedExpense.scheduleLabel(FixedPeriodType.BIWEEKLY, 5, 30))
        assertEquals("每 45 天", FixedExpense.scheduleLabel(FixedPeriodType.CUSTOM, 5, 45))
        assertEquals("每年 1 日", FixedExpense.scheduleLabel(FixedPeriodType.YEARLY, 1, 30))
    }
}