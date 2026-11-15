package xyz.ikuyo.finance

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.SavingsEntry
import xyz.ikuyo.finance.domain.model.SavingsEntryType
import xyz.ikuyo.finance.domain.model.SavingsGoal
import xyz.ikuyo.finance.domain.model.SavingsGoalStatus
import xyz.ikuyo.finance.domain.usecase.SavingsLedger

/**
 * 储蓄口径：存入/取出一律不计普通收入或消费支出，只改变储蓄余额；
 * 总余额（可用 + 储蓄）不变。
 */
class SavingsLedgerTest {

    private val today = LocalDate.of(2026, 3, 15)

    private fun at(month: Int, day: Int): Long =
        Time.toMillis(LocalDate.of(2026, month, day), LocalTime.NOON)

    private fun goal(
        id: Long = 1L,
        name: String = "旅行",
        targetMinor: Long = 100_000L,
        dueAt: Long = at(6, 30),
        status: SavingsGoalStatus = SavingsGoalStatus.ACTIVE,
    ) = SavingsGoal(
        id = id,
        name = name,
        targetMinor = targetMinor,
        dueAt = dueAt,
        status = status,
    )

    private fun entry(
        id: Long = 0L,
        goalId: Long? = 1L,
        type: SavingsEntryType = SavingsEntryType.DEPOSIT,
        amountMinor: Long = 10_000L,
        occurredAt: Long = at(3, 15),
    ) = SavingsEntry(id = id, goalId = goalId, type = type, amountMinor = amountMinor, occurredAt = occurredAt)

    @Test
    fun `存入只增加储蓄余额`() {
        val entries = listOf(
            entry(id = 1L, amountMinor = 10_000L),
            entry(id = 2L, amountMinor = 5_000L),
        )
        assertEquals(15_000L, SavingsLedger.balance(entries))
        assertEquals(15_000L, SavingsLedger.savedOf(1L, entries))
    }

    @Test
    fun `取出减少储蓄余额且不会变成负数以外的口径`() {
        val entries = listOf(
            entry(id = 1L, amountMinor = 10_000L),
            entry(id = 2L, type = SavingsEntryType.WITHDRAW, amountMinor = 3_000L),
        )
        assertEquals(7_000L, SavingsLedger.balance(entries))
    }

    @Test
    fun `总余额不变`() {
        // 储蓄是资金划转：可用余额减少多少，储蓄余额就增加多少，两者之和不变。
        val available = 50_000L
        val entries = listOf(entry(id = 1L, amountMinor = 10_000L))
        val savings = SavingsLedger.balance(entries)
        assertEquals(available, (available - savings) + savings)
        assertEquals(40_000L, available - savings)
    }

    @Test
    fun `目标之间划转会记录转入与转出两笔等额流水`() {
        val entries = listOf(
            entry(id = 1L, goalId = 1L, type = SavingsEntryType.TRANSFER_OUT, amountMinor = 4_000L),
            entry(id = 2L, goalId = 2L, type = SavingsEntryType.TRANSFER_IN, amountMinor = 4_000L),
        )
        // 划转不改变总储蓄，只是把份额从一个目标挪到另一个。
        assertEquals(0L, SavingsLedger.balance(entries))
        assertEquals(-4_000L, SavingsLedger.savedOf(1L, entries))
        assertEquals(4_000L, SavingsLedger.savedOf(2L, entries))
    }

    @Test
    fun `进度按已存金额计算且超额不截断`() {
        val entries = listOf(entry(id = 1L, amountMinor = 120_000L))
        val progress = SavingsLedger.progressOf(goal(targetMinor = 100_000L), entries, today)
        assertEquals(120_000L, progress.savedMinor)
        // progressBp 超额不截断（120%），由界面自行收敛到 0..1 的进度条。
        assertEquals(12_000, progress.progressBp)
        assertTrue(progress.isReached)
        assertEquals(0L, progress.remainingMinor)
    }

    @Test
    fun `部分存入的剩余金额与进度`() {
        val entries = listOf(entry(id = 1L, amountMinor = 33_700L))
        val progress = SavingsLedger.progressOf(goal(targetMinor = 101_200L), entries, today)
        assertEquals(67_500L, progress.remainingMinor)
        assertEquals(3330, progress.progressBp)
        assertFalse(progress.isReached)
        assertEquals(107, progress.daysLeft)
        assertFalse(progress.isOverdue)
    }

    @Test
    fun `逾期且未达成才标记为未达成`() {
        val entries = listOf(entry(id = 1L, amountMinor = 10_000L))
        val overdue = SavingsLedger.progressOf(goal(dueAt = at(3, 14)), entries, today)
        assertEquals(-1, overdue.daysLeft)
        assertTrue(overdue.isOverdue)

        val reached = SavingsLedger.progressOf(
            goal(targetMinor = 10_000L, dueAt = at(3, 14)),
            entries,
            today,
        )
        assertFalse(reached.isOverdue)
    }

    @Test
    fun `取出超过已存金额会被拦截`() {
        val entries = listOf(entry(id = 1L, amountMinor = 5_000L))
        val message = SavingsLedger.validateMovement(
            goal = goal(),
            entries = entries,
            type = SavingsEntryType.WITHDRAW,
            amountMinor = 6_000L,
        )
        assertEquals("取出金额超过已存金额（已存 ¥50.00）", message)
    }

    @Test
    fun `暂停的目标不能继续存入但可以取出`() {
        val paused = goal(status = SavingsGoalStatus.PAUSED)
        val entries = listOf(entry(id = 1L, amountMinor = 5_000L))
        assertEquals(
            "目标已暂停，恢复后才能继续存入",
            SavingsLedger.validateMovement(paused, entries, SavingsEntryType.DEPOSIT, 1_000L),
        )
        assertNull(SavingsLedger.validateMovement(paused, entries, SavingsEntryType.WITHDRAW, 1_000L))
    }

    @Test
    fun `已取消的目标不能操作`() {
        val cancelled = goal(status = SavingsGoalStatus.CANCELLED)
        assertEquals(
            "目标已取消，无法再操作",
            SavingsLedger.validateMovement(cancelled, emptyList(), SavingsEntryType.DEPOSIT, 1_000L),
        )
    }

    @Test
    fun `金额必须大于零`() {
        assertEquals(
            "金额必须大于 0",
            SavingsLedger.validateMovement(goal(), emptyList(), SavingsEntryType.DEPOSIT, 0L),
        )
        assertEquals(
            "金额必须大于 0",
            SavingsLedger.validateTransfer(goal(id = 1L), goal(id = 2L), emptyList(), 0L),
        )
    }

    @Test
    fun `划转校验各分支`() {
        val from = goal(id = 1L, name = "旅行")
        val to = goal(id = 2L, name = "换手机")
        assertEquals(
            "不能转到同一个目标",
            SavingsLedger.validateTransfer(from, from, emptyList(), 1_000L),
        )
        assertEquals(
            "已关闭的目标不能划转",
            SavingsLedger.validateTransfer(from, goal(id = 2L, status = SavingsGoalStatus.COMPLETED), emptyList(), 1_000L),
        )
        assertEquals(
            "「旅行」可转出金额不足（已存 ¥10.00）",
            SavingsLedger.validateTransfer(from, to, listOf(entry(id = 1L, amountMinor = 1_000L)), 2_000L),
        )
        assertNull(
            SavingsLedger.validateTransfer(from, to, listOf(entry(id = 1L, amountMinor = 1_000L)), 1_000L),
        )
    }

    @Test
    fun `目标字段校验`() {
        assertEquals("请填写目标名称", SavingsLedger.validate(goal(name = " ")))
        assertEquals("目标金额必须大于 0", SavingsLedger.validate(goal(targetMinor = 0L)))
        assertEquals("请选择截止日期", SavingsLedger.validate(goal(dueAt = 0L)))
        assertNull(SavingsLedger.validate(goal()))
    }

    @Test
    fun `按月摊平建议金额`() {
        // 还差 ¥1000，剩 107 天 ≈ 4 个月 → 每月 250。
        val entries = listOf(entry(id = 1L, amountMinor = 0L))
        assertEquals(25_000L, SavingsLedger.requiredMonthly(goal(targetMinor = 100_000L), entries, today))
    }

    @Test
    fun `已达成时没有每月建议金额`() {
        val entries = listOf(entry(id = 1L, amountMinor = 100_000L))
        assertNull(SavingsLedger.requiredMonthly(goal(targetMinor = 100_000L), entries, today))
    }

    @Test
    fun `剩余时间不足一个月时按月建议等于剩余金额`() {
        val entries = emptyList<SavingsEntry>()
        assertEquals(
            30_000L,
            SavingsLedger.requiredMonthly(goal(targetMinor = 30_000L, dueAt = at(3, 20)), entries, today),
        )
    }

    @Test
    fun `月数差不足一个月按一个月算`() {
        assertEquals(0L, SavingsLedger.monthsBetween(today, today))
        assertEquals(1L, SavingsLedger.monthsBetween(today, today.plusDays(1)))
        assertEquals(1L, SavingsLedger.monthsBetween(today, today.plusDays(30)))
        assertEquals(2L, SavingsLedger.monthsBetween(today, today.plusDays(31)))
    }

    @Test
    fun `截止日当天算今天到期`() {
        assertEquals(0, SavingsLedger.daysUntil(at(3, 15), today))
        assertEquals(-2, SavingsLedger.daysUntil(at(3, 13), today))
        assertEquals(5, SavingsLedger.daysUntil(at(3, 20), today))
    }
}