package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.ikuyo.finance.core.Money
import java.math.BigDecimal

class MoneyTest {

    @Test
    fun `format cny uses two decimals and thousands separator`() {
        assertEquals("¥1,234.50", Money.format(123450L))
        assertEquals("¥0.00", Money.format(0L))
        assertEquals("¥0.05", Money.format(5L))
    }

    @Test
    fun `formatSigned adds explicit sign`() {
        assertEquals("+¥12.00", Money.formatSigned(1200L))
        assertEquals("-¥12.00", Money.formatSigned(-1200L))
    }

    @Test
    fun `parseToMinor accepts symbols commas and decimals`() {
        assertEquals(2850L, Money.parseToMinor("28.50"))
        assertEquals(2850L, Money.parseToMinor("¥28.50"))
        assertEquals(120000L, Money.parseToMinor("1,200"))
        assertEquals(7L, Money.parseToMinor("0.07"))
    }

    @Test
    fun `parseToMinor rejects invalid input`() {
        assertNull(Money.parseToMinor(""))
        assertNull(Money.parseToMinor("   "))
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor("."))
    }

    @Test
    fun `parseToMinor rounds half up to cents`() {
        assertEquals(1L, Money.parseToMinor("0.005"))
        assertEquals(1L, Money.parseToMinor("0.006"))
    }

    @Test
    fun `parseToMinor tolerates trailing dot and repeated dots`() {
        // 手机上误触打出的 "28.50." 不能变成「请输入有效金额」
        assertEquals(2850L, Money.parseToMinor("28.50."))
        assertEquals(2800L, Money.parseToMinor("28."))
        assertEquals(120L, Money.parseToMinor("1.2.3"))
        assertEquals(120_050L, Money.parseToMinor("￥1,200.5"))
        assertEquals(2850L, Money.parseToMinor("28．50"))
        assertNull(Money.parseToMinor("."))
        assertNull(Money.parseToMinor("。"))
    }

    @Test
    fun `convertToCnyMinor keeps base currency untouched`() {
        assertEquals(10000L, Money.convertToCnyMinor(10000L, "CNY", BigDecimal.ONE))
    }

    @Test
    fun `convertToCnyMinor applies saved rate for foreign currency`() {
        // 100.00 USD @ 7.20 => 720.00 CNY
        val rate = BigDecimal("7.20")
        assertEquals(72000L, Money.convertToCnyMinor(10000L, "USD", rate))
        // 1000 JPY (0 decimals) @ 0.048 => 48.00 CNY
        assertEquals(4800L, Money.convertToCnyMinor(1000L, "JPY", BigDecimal("0.048")))
    }

    @Test
    fun `rate scaled round trip keeps six decimals`() {
        val scaled = Money.scaledFromRate(BigDecimal("7.123456"))
        assertEquals(7_123_456L, scaled)
        assertEquals(BigDecimal("7.123456"), Money.rateFromScaled(scaled))
    }
}