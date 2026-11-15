package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
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
import xyz.ikuyo.finance.domain.usecase.AnomalyDetector
import xyz.ikuyo.finance.domain.usecase.Statistics
import java.time.LocalDate
import java.time.LocalTime

/**
 * 异常检测的规则测试：所有判断都必须相对「个人历史」，不能退化成固定金额阈值。
 */
class AnomalyDetectorTest {

    private val today = LocalDate.of(2026, 3, 15)

    private val categories = listOf(
        Category(id = 1L, name = "餐饮", emoji = "🍜", sortOrder = 1),
        Category(id = 2L, name = "交通出行", emoji = "🚇", sortOrder = 2),
    )

    private fun expense(
        id: Long,
        minor: Long,
        categoryId: Long?,
        date: LocalDate,
        hour: Int = 12,
        minute: Int = 0,
    ): Bill = Bill(
        id = id,
        type = BillType.EXPENSE,
        amountMinor = minor,
        exchangeRateScaled = Money.RATE_SCALE,
        cnyAmountMinor = minor,
        categoryId = categoryId,
        occurredAt = Time.toMillis(date, LocalTime.of(hour, minute)),
        createdAt = Time.toMillis(date, LocalTime.of(hour, minute)),
        source = BillSource.MANUAL,
        paymentMethod = PaymentMethod.WECHAT,
    )

    /** 上个月的 20 笔小额餐饮，用来建立「历史中位数」。 */
    private fun history(minor: Long = 1_000L, categoryId: Long = 1L): List<Bill> =
        (1..20).map { index ->
            expense(
                id = index.toLong(),
                minor = minor,
                categoryId = categoryId,
                date = LocalDate.of(2026, 2, 1).plusDays((index % 20).toLong()),
            )
        }

    @Test
    fun `历史不足二十笔时不提示大额`() {
        val bills = (1..5).map { index ->
            expense(index.toLong(), 1_000L, 1L, LocalDate.of(2026, 2, 3).plusDays(index.toLong()))
        } + expense(99L, 90_000L, 1L, LocalDate.of(2026, 3, 14))

        val found = AnomalyDetector.detect(bills, categories, emptyList(), today)

        assertTrue(found.none { it.kind == AnomalyDetector.Kind.SINGLE_LARGE })
    }

    @Test
    fun `单笔金额远超该分类历史中位数时提示`() {
        val bills = history() + expense(99L, 8_000L, 1L, LocalDate.of(2026, 3, 14))

        val found = AnomalyDetector.detect(bills, categories, emptyList(), today)

        val single = found.firstOrNull { it.kind == AnomalyDetector.Kind.SINGLE_LARGE }
        assertTrue(single != null)
        assertEquals("single:1:99", single!!.id)
        assertTrue(single.title.contains("餐饮"))
        assertTrue(single.evidence.first().contains("20 笔"))
    }

    @Test
    fun `近七天笔数明显高于历史周均时提示`() {
        // 八周里每周一笔 —— 周均 1 笔。
        val history = (0..7).map { week ->
            expense(
                id = (week + 1).toLong(),
                minor = 2_000L,
                categoryId = 1L,
                date = LocalDate.of(2026, 1, 5).plusWeeks(week.toLong()),
            )
        }
        val recent = listOf(10L, 11L, 12L).map { id ->
            expense(id, 2_000L, 1L, LocalDate.of(2026, 3, 10).plusDays(id - 10L))
        }

        val found = AnomalyDetector.detect(history + recent, categories, emptyList(), today)

        val frequency = found.firstOrNull { it.kind == AnomalyDetector.Kind.FREQUENCY }
        assertTrue(frequency != null)
        assertTrue(frequency!!.detail.contains("近 7 天 3 笔"))
    }

    @Test
    fun `二十四小时内三笔金额接近的同类消费提示重复消费`() {
        val bills = listOf(
            expense(1L, 2_000L, 2L, LocalDate.of(2026, 3, 14), hour = 12),
            expense(2L, 2_000L, 2L, LocalDate.of(2026, 3, 14), hour = 12, minute = 30),
            expense(3L, 2_000L, 2L, LocalDate.of(2026, 3, 14), hour = 13),
        )

        val found = AnomalyDetector.detect(bills, categories, emptyList(), today)

        val repeated = found.firstOrNull { it.kind == AnomalyDetector.Kind.REPEATED }
        assertTrue(repeated != null)
        assertEquals("repeat:2:1", repeated!!.id)
        assertTrue(repeated.detail.contains("3 笔"))
    }

    @Test
    fun `本月某分类占比明显高于上月时提示结构变化`() {
        val bills = listOf(
            expense(1L, 9_000L, 2L, LocalDate.of(2026, 2, 10)),
            expense(2L, 9_000L, 1L, LocalDate.of(2026, 3, 5)),
            expense(3L, 1_000L, 2L, LocalDate.of(2026, 3, 6)),
        )

        val found = AnomalyDetector.detect(bills, categories, emptyList(), today)

        val structure = found.firstOrNull { it.kind == AnomalyDetector.Kind.STRUCTURE }
        assertTrue(structure != null)
        assertTrue(structure!!.title.contains("餐饮"))
        assertTrue(structure.detail.contains("上月 0%"))
    }

    @Test
    fun `预算消耗快于时间进度时提示`() {
        val budgets = listOf(
            Statistics.BudgetExecution(
                budgetId = 7L,
                name = "餐饮",
                windowLabel = "2026年3月",
                amountMinor = 10_000L,
                spentMinor = 6_000L,
                availableMinor = 10_000L,
                carryOverMinor = 0L,
                usedPercent = 60,
                daysLeft = 16,
                alertLevel = BudgetAlertLevel.NORMAL,
                timeProgressBp = 5_000,
            ),
        )

        // 用一笔本月餐饮账单制造支出，预算数字本身取自统计服务。
        val bills = listOf(expense(1L, 6_000L, 1L, LocalDate.of(2026, 3, 4)))

        val found = AnomalyDetector.detect(bills, categories, budgets, today)

        val pace = found.firstOrNull { it.kind == AnomalyDetector.Kind.BUDGET_PACE }
        assertTrue(pace != null)
        assertEquals("pace:7", pace!!.id)
        assertTrue(pace.detail.contains("超前"))
    }

    @Test
    fun `历史几乎没有深夜消费而近期出现两笔时提示`() {
        val bills = history() + listOf(
            expense(90L, 3_000L, 1L, LocalDate.of(2026, 3, 14), hour = 23, minute = 30),
            expense(91L, 3_000L, 1L, LocalDate.of(2026, 3, 15), hour = 0, minute = 20),
        )

        val found = AnomalyDetector.detect(bills, categories, emptyList(), today)

        assertTrue(found.any { it.kind == AnomalyDetector.Kind.LATE_NIGHT })
    }

    @Test
    fun `检测只产出候选不改动账单`() {
        val bills = history() + expense(99L, 8_000L, 1L, LocalDate.of(2026, 3, 14))
        val snapshot = bills.toList()

        val first = AnomalyDetector.detect(bills, categories, emptyList(), today)
        val second = AnomalyDetector.detect(bills, categories, emptyList(), today)

        assertEquals(snapshot, bills)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.all { it.evidence.isNotEmpty() })
    }

    @Test
    fun `历史样本稀少时深夜消费仍然提示`() {
        val day = LocalDate.of(2026, 11, 15)
        val sparse = listOf("2026-09-16", "2026-09-23", "2026-09-30", "2026-10-07", "2026-10-14", "2026-10-21", "2026-10-28")
            .mapIndexed { index, text ->
                expense(
                    id = 200L + index,
                    minor = 2_000L,
                    categoryId = 2L,
                    date = LocalDate.parse(text),
                )
            }
        val bills = sparse + listOf(
            expense(300L, 1_500L, 2L, LocalDate.of(2026, 11, 13), hour = 12),
            expense(301L, 1_500L, 2L, LocalDate.of(2026, 11, 14), hour = 23, minute = 30),
            expense(302L, 1_500L, 2L, LocalDate.of(2026, 11, 15), hour = 0, minute = 20),
        )

        val found = AnomalyDetector.detect(bills, categories, emptyList(), day)

        assertTrue(found.any { it.kind == AnomalyDetector.Kind.LATE_NIGHT })
    }

    @Test
    fun `没有账单时没有任何提醒`() {
        assertTrue(AnomalyDetector.detect(emptyList(), categories, emptyList(), today).isEmpty())
    }
}