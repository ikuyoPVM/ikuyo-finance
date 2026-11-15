package xyz.ikuyo.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.data.ai.SpendingInsight
import xyz.ikuyo.finance.feature.stats.StatsAnalysisState

/**
 * 分析结果与区间的绑定关系：结论必须标明它是哪个区间算出来的，
 * 换了区间还没重新分析时要能显示「区间已变化」。
 */
class AnalysisRangeStateTest {

    private val insight = SpendingInsight(
        facts = listOf("本期净支出 146.70 元，共 8 笔"),
        estimates = listOf("按目前节奏，本月餐饮可能继续集中在午餐"),
        suggestions = listOf("把外卖控制在每周两次，可省下约 60 元"),
        caveats = listOf("样本只有 8 笔，结论强度有限"),
    )

    @Test
    fun `没有结论时不算过期`() {
        val state = StatsAnalysisState(rangeKey = "a-b")
        assertFalse(state.hasResult)
        assertFalse(state.isStale("c-d"))
    }

    @Test
    fun `同一区间的结论不过期`() {
        val state = StatsAnalysisState(insight = insight, rangeKey = "a-b")
        assertTrue(state.hasResult)
        assertFalse(state.isStale("a-b"))
    }

    @Test
    fun `换区间后旧结论被标记为过期`() {
        val state = StatsAnalysisState(insight = insight, rangeKey = "月-起-止")
        assertTrue(state.isStale("周-起-止"))
    }

    @Test
    fun `失败只清掉结论并保留错误信息`() {
        val failed = StatsAnalysisState(insight = null, error = null, rangeKey = "a-b")
        assertFalse(failed.hasResult)
        assertFalse(failed.isStale("a-b"))
        assertFalse(failed.running)
    }
}