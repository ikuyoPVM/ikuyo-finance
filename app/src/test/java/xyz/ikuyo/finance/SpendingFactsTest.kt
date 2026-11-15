package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.ai.SpendingAnalysis
import xyz.ikuyo.finance.data.ai.SpendingInsight
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.SpendingFacts
import xyz.ikuyo.finance.domain.usecase.Statistics
import java.time.LocalDate
import java.time.LocalTime

/**
 * 消费分析的本地事实层与提示词解析。
 *
 * 这些用例守的是「AI 不许算数」这条线：事实必须由统计服务算出，
 * 提示词只能携带聚合结果，解析失败要能被上层识别。
 */
class SpendingFactsTest {

    private val today = LocalDate.of(2026, 3, 15)
    private val range = Statistics.range(Statistics.RangeType.MONTH, today)
    private val categories = listOf(
        Category(id = 1L, name = "餐饮", emoji = "🍜", sortOrder = 1),
    )
    private val tags = listOf(Tag(id = 7L, name = "work", colorArgb = 0xFF00FF00.toInt()))

    private fun at(month: Int, day: Int, hour: Int = 12): Long =
        Time.toMillis(LocalDate.of(2026, month, day), LocalTime.of(hour, 0))

    private fun bill(
        id: Long,
        amountMinor: Long,
        type: BillType = BillType.EXPENSE,
        occurredAt: Long = at(3, 10),
        categoryId: Long? = 1L,
    ) = Bill(
        id = id,
        type = type,
        amountMinor = amountMinor,
        exchangeRateScaled = xyz.ikuyo.finance.core.Money.RATE_SCALE,
        cnyAmountMinor = amountMinor,
        categoryId = categoryId,
        categoryName = if (categoryId == 1L) "餐饮" else null,
        paymentMethod = PaymentMethod.WECHAT,
        note = "备注不该出现在提示词里",
        occurredAt = occurredAt,
        createdAt = occurredAt,
        source = BillSource.MANUAL,
    )

    private fun snapshot(bills: List<Bill>, budgets: List<Budget> = emptyList(), tagBillIds: Map<Long, Set<Long>> = emptyMap()) =
        Statistics.snapshot(
            bills = bills,
            range = range,
            categories = categories,
            tags = tags,
            tagBillIds = tagBillIds,
            budgets = budgets,
            today = today,
        )

    @Test
    fun `事实层从统计快照取数并给出每日均值与峰值日`() {
        val bills = listOf(
            bill(1, 3_000L, occurredAt = at(3, 10)),
            bill(2, 2_000L, occurredAt = at(3, 12)),
            bill(3, 5_000L, occurredAt = at(3, 12)),
        )
        val facts = SpendingFacts.from(snapshot(bills))

        assertEquals(10_000L, facts.netExpenseMinor)
        assertEquals(0L, facts.refundMinor)
        assertEquals(3, facts.billCount)
        assertEquals(31, facts.dayCount)
        // 10000 / 31 = 322（向下取整，避免日均虚高）
        assertEquals(322L, facts.averageDailyMinor)
        assertEquals("2026年3月12日", facts.peakDayLabel)
        assertEquals(7_000L, facts.peakDayNetExpenseMinor)
        assertEquals(SpendingFacts.Sufficiency.THIN, facts.sufficiency)
        assertTrue(facts.hasData)
    }

    @Test
    fun `没有账单时不构造可分析事实`() {
        val facts = SpendingFacts.from(snapshot(emptyList()))
        assertEquals(SpendingFacts.Sufficiency.NONE, facts.sufficiency)
        assertFalse(facts.hasData)
        assertEquals(0L, facts.averageDailyMinor)
        assertNull(facts.peakDayLabel)
    }

    @Test
    fun `账单足够多时标记为数据充分`() {
        val bills = (1L..6L).map { bill(it, 1_000L, occurredAt = at(3, 10)) }
        assertEquals(SpendingFacts.Sufficiency.OK, SpendingFacts.from(snapshot(bills)).sufficiency)
    }

    @Test
    fun `明显变化分类只看超过去比与绝对额门槛的项`() {
        val bills = listOf(
            bill(1, 6_000L, occurredAt = at(3, 10)),
            bill(2, 6_000L, occurredAt = at(3, 11)),
        )
        val facts = SpendingFacts.from(snapshot(bills))
        // 上期无数据 → changePercent 为 null → 不进「明显变化」
        assertTrue(facts.notableCategories.isEmpty())
        assertEquals(1, facts.categories.size)
        assertNull(facts.categories.first().changePercent)
    }

    @Test
    fun `提示词只带聚合事实不带备注`() {
        val bills = listOf(bill(1, 4_000L, occurredAt = at(3, 10)), bill(2, 1_000L, occurredAt = at(3, 11)))
        val facts = SpendingFacts.from(
            snapshot(
                bills,
                budgets = listOf(
                    Budget(
                        id = 1L,
                        name = "总预算",
                        amountMinor = 100_000L,
                        periodType = BudgetPeriodType.MONTHLY,
                        scope = BudgetScope.TOTAL,
                        enabled = true,
                    ),
                ),
            ),
        )
        val json = facts.toPromptJson()
        val root = Json.parseOrNull(json) as? JsonValue.Obj
        assertNotNull(root)
        val expense = root!!.obj("收支")!!.num("净支出元")!!
        assertEquals(50.0, expense, 0.001)
        // 5000 分 / 31 天 = 161 分 = 1.61 元
        assertEquals(1.61, root.obj("趋势")!!.num("日均净支出元")!!, 0.001)
        assertTrue(root.arr("分类")!!.size == 1)
        assertTrue(root.arr("预算")!!.size == 1)
        // 隐私最小化：备注、单笔金额都不进提示词
        assertFalse(json.contains("备注不该出现"))
        assertFalse(json.contains("4_000"))
    }

    @Test
    fun `本地事实行不依赖 AI 也能说明情况`() {
        val facts = SpendingFacts.from(snapshot(listOf(bill(1, 4_000L))))
        val lines = facts.localLines()
        assertTrue(lines.any { it.contains("本期净支出") })
        assertTrue(lines.any { it.contains("最大分类") })
        assertTrue(lines.any { it.contains("没有可比数据") || it.contains("相比") })
    }

    @Test
    fun `解析四段式回复并在有围栏时也能取到`() {
        val content = """
            这是分析结果：
            ```json
            {"facts":["本月支出 100.00 元"],"estimates":["按目前节奏可能略高"],"suggestions":["把外卖控制在每周两次"],"caveats":["样本较少"]}
            ```
        """.trimIndent()
        val insight = SpendingAnalysis.parse(content)
        assertNotNull(insight)
        assertEquals(listOf("本月支出 100.00 元"), insight!!.facts)
        assertEquals(listOf("按目前节奏可能略高"), insight.estimates)
        assertEquals(listOf("把外卖控制在每周两次"), insight.suggestions)
        assertEquals(listOf("样本较少"), insight.caveats)
        assertFalse(insight.isEmpty)
    }

    @Test
    fun `解析失败或全空时返回空`() {
        assertNull(SpendingAnalysis.parse("模型今天不想说话"))
        assertNull(SpendingAnalysis.parse("""{"facts":[],"estimates":[],"suggestions":[],"caveats":[]}"""))
        assertNull(SpendingAnalysis.parse(null))
    }

    @Test
    fun `每段最多保留五条`() {
        val many = (1..9).joinToString(",") { "\"第 $it 条\"" }
        val insight = SpendingAnalysis.parse("""{"facts":[$many],"estimates":[],"suggestions":[],"caveats":[]}""")
        assertEquals(5, insight!!.facts.size)
        assertEquals("第 5 条", insight.facts.last())
    }

    @Test
    fun `系统提示词声明不得计算与不得改账`() {
        val prompt = SpendingAnalysis.systemPrompt()
        assertTrue(prompt.contains("禁止"))
        assertTrue(prompt.contains("caveats"))
        assertEquals(1, SpendingAnalysis.PROMPT_VERSION)
        assertFalse(SpendingInsight(emptyList(), emptyList(), emptyList(), emptyList()).let { !it.isEmpty })
    }
}