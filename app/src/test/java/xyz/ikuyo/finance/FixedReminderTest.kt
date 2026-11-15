package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.FixedExpense
import xyz.ikuyo.finance.domain.model.FixedExpensePayment
import xyz.ikuyo.finance.domain.model.FixedPeriodType
import xyz.ikuyo.finance.domain.usecase.FixedExpenseSchedule
import xyz.ikuyo.finance.feature.fixed.FixedExpensesUiState
import java.time.LocalDate
import java.time.LocalTime

/**
 * 固定支出的提醒口径。
 *
 * 提醒只决定「什么时候开始提示」，不改变到期判断，所以调整提醒天数不能让
 * 已经到期的计划从待处理列表里消失（否则用户会以为账单没了）。
 */
class FixedReminderTest {

    private val today = LocalDate.of(2026, 3, 15)

    private fun at(year: Int, month: Int, day: Int): Long =
        Time.toMillis(LocalDate.of(year, month, day), LocalTime.NOON)

    private fun plan(
        id: Long,
        name: String,
        dueAt: Long,
        enabled: Boolean = true,
        periodType: FixedPeriodType = FixedPeriodType.MONTHLY,
        anchorDay: Int = 15,
    ): FixedExpense = FixedExpense(
        id = id,
        name = name,
        amountMinor = 3_500L,
        periodType = periodType,
        dueAt = dueAt,
        anchorDay = anchorDay,
        enabled = enabled,
    )

    private fun state(remindDays: Int, items: List<FixedExpense>): FixedExpensesUiState =
        FixedExpensesUiState(
            items = items,
            payments = emptyList<FixedExpensePayment>(),
            remindDays = remindDays,
            today = today,
        )

    @Test
    fun `提醒天数为零时只列出今天到期与已逾期`() {
        val items = listOf(
            plan(1, "逾期", at(2026, 3, 10)),
            plan(2, "今天", at(2026, 3, 15)),
            plan(3, "两天后", at(2026, 3, 17)),
            plan(4, "十天后", at(2026, 3, 25)),
        )

        val reminders = state(remindDays = 0, items = items).reminders

        assertEquals(listOf("逾期", "今天"), reminders.map { it.name })
        assertEquals(2, state(remindDays = 0, items = items).pendingCount)
    }

    @Test
    fun `提前三天时把三天内到期的计划也纳入提醒`() {
        val items = listOf(
            plan(1, "逾期", at(2026, 3, 10)),
            plan(2, "今天", at(2026, 3, 15)),
            plan(3, "两天后", at(2026, 3, 17)),
            plan(4, "三天后", at(2026, 3, 18)),
            plan(5, "四天后", at(2026, 3, 19)),
        )

        val reminders = state(remindDays = 3, items = items).reminders

        assertEquals(listOf("逾期", "今天", "两天后", "三天后"), reminders.map { it.name })
        assertEquals(4, state(remindDays = 3, items = items).pendingCount)
    }

    @Test
    fun `暂停的计划既不提醒也不计入待处理笔数`() {
        val items = listOf(
            plan(1, "今天", at(2026, 3, 15)),
            plan(2, "已暂停", at(2026, 3, 15), enabled = false),
        )

        val uiState = state(remindDays = 7, items = items)

        assertEquals(1, uiState.reminders.size)
        assertEquals(1, uiState.pendingCount)
        assertEquals(listOf("已暂停"), uiState.paused.map { it.name })
    }

    @Test
    fun `提醒天数只影响提醒范围不影响分组`() {
        val items = listOf(
            plan(1, "今天", at(2026, 3, 15)),
            plan(2, "五天后", at(2026, 3, 20)),
            plan(3, "二十天后", at(2026, 4, 4)),
        )

        val tight = state(remindDays = 0, items = items)
        val loose = state(remindDays = 7, items = items)

        // 分组与到期判断不受提醒天数影响。
        assertEquals(tight.overdue.map { it.name }, loose.overdue.map { it.name })
        assertEquals(tight.thisWeek.map { it.name }, loose.thisWeek.map { it.name })
        assertEquals(tight.later.map { it.name }, loose.later.map { it.name })
        // 只有提醒范围不同。
        assertTrue(loose.reminders.size > tight.reminders.size)
    }

    @Test
    fun `跳过一期只推进到期日不产生金额`() {
        val plan = plan(1, "房租", at(2026, 3, 15))

        val next = FixedExpenseSchedule.nextDue(plan)

        // 推进一期后到期日变成下个月同一天，且计划金额本身不变（未作废）。
        assertEquals(LocalDate.of(2026, 4, 15), Time.toLocalDate(next))
        assertEquals(3_500L, plan.amountMinor)
        assertEquals(LocalDate.of(2026, 3, 15), Time.toLocalDate(plan.dueAt))
    }

    @Test
    fun `跳过一期后该计划不再落在今天的提醒里`() {
        val before = plan(1, "房租", at(2026, 3, 15))
        val after = before.copy(dueAt = FixedExpenseSchedule.nextDue(before))

        assertEquals(1, state(remindDays = 0, items = listOf(before)).pendingCount)
        assertEquals(0, state(remindDays = 0, items = listOf(after)).pendingCount)
    }
}