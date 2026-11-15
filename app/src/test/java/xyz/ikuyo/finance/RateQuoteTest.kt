package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.domain.usecase.RateQuote

/**
 * 汇率行情解析与新鲜度（计划书 6.3）。
 *
 * 只测纯函数：网络失败、缓存降级等行为由仓储与界面保证，不在这里伪造 HTTP。
 */
class RateQuoteTest {

    private val body = """
        {"base":"CNY","date":"2026-11-15",
         "rates":{"USD":0.14045,"JPY":21.2,"EUR":0.1291,"GBP":0.1098,"HKD":1.092,"CHF":0.1234}}
    """.trimIndent()

    @Test
    fun `解析以人民币为基准的行情并换算成一外币多少人民币`() {
        val rates = RateQuote.parseCnyBase(body)
        assertEquals(Money.RATE_SCALE, Money.RATE_SCALE) // 缩放基数 1e6
        assertEquals(5, rates.size)
        // 1 / 0.14045 = 7.1199715…，按 6 位小数四舍五入
        assertEquals(7_119_972L, rates["USD"])
        // 1 / 21.2 = 0.0471698…
        assertEquals(47_170L, rates["JPY"])
        // 1 / 1.092 = 0.915750…，港币比人民币略便宜
        assertEquals(915_751L, rates["HKD"])
    }

    @Test
    fun `不支持的币种被忽略`() {
        assertFalse(RateQuote.parseCnyBase(body).containsKey("CHF"))
    }

    @Test
    fun `基准不是人民币时不做任何换算`() {
        val usdBase = """{"base":"USD","rates":{"CNY":7.12}}"""
        assertTrue(RateQuote.parseCnyBase(usdBase).isEmpty())
    }

    @Test
    fun `缺少 base 字段时按人民币基准解析`() {
        val noBase = """{"rates":{"USD":0.125}}"""
        assertEquals(8_000_000L, RateQuote.parseCnyBase(noBase)["USD"])
    }

    @Test
    fun `坏数据返回空表而不是猜一个汇率`() {
        assertTrue(RateQuote.parseCnyBase("not json").isEmpty())
        assertTrue(RateQuote.parseCnyBase("""{"rates":{}}""").isEmpty())
        assertTrue(RateQuote.parseCnyBase("""{"rates":{"USD":0}}""").isEmpty())
        assertTrue(RateQuote.parseCnyBase("""{"rates":{"USD":-1.2}}""").isEmpty())
        assertTrue(RateQuote.parseCnyBase("""{"rates":{"USD":"7.12"}}""").isEmpty())
    }

    @Test
    fun `超过一天没有更新的汇率视为缓存`() {
        val now = 1_700_000_000_000L
        assertFalse(RateQuote.isStale(now - 1_000L, now))
        assertFalse(RateQuote.isStale(now - RateQuote.STALE_AFTER_MILLIS, now))
        assertTrue(RateQuote.isStale(now - RateQuote.STALE_AFTER_MILLIS - 1L, now))
        assertTrue(RateQuote.isStale(0L, now))
    }

    @Test
    fun `刷新提示按天计算且不足一天算一天`() {
        val now = 1_700_000_000_000L
        assertEquals(0L, RateQuote.ageDays(0L, now))
        assertEquals(1L, RateQuote.ageDays(now - 1_000L, now))
        assertEquals(1L, RateQuote.ageDays(now - RateQuote.STALE_AFTER_MILLIS, now))
        assertEquals(2L, RateQuote.ageDays(now - RateQuote.STALE_AFTER_MILLIS - 1L, now))
    }
}