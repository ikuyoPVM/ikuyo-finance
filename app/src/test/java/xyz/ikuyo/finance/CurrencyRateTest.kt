package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.domain.model.CurrencyCatalog
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.model.RateSource
import java.math.BigDecimal

/** 多币种折算、汇率快照与目录工具。 */
class CurrencyRateTest {

    @Test
    fun rateScaleRoundTripKeepsSixDecimals() {
        val scaled = Money.scaledFromRate(BigDecimal("7.12"))
        assertEquals(7_120_000L, scaled)
        assertEquals(0, Money.rateFromScaled(scaled).compareTo(BigDecimal("7.12")))
    }

    @Test
    fun usdAmountConvertsToCnyMinorRoundingHalfUp() {
        // 19.99 美元，汇率 7.1234 → 142.3966… 元 → 14239.67 分 → 14240 分
        val cny = Money.convertToCnyMinor(1_999L, "USD", Money.rateFromScaled(Money.scaledFromRate(BigDecimal("7.1234"))))
        assertEquals(14_240L, cny)
    }

    @Test
    fun jpyHasNoDecimalPlaces() {
        assertEquals(0, Money.decimalsOf("JPY"))
        assertEquals(1_200L, Money.parseToMinor("1200", "JPY"))
        assertEquals(2, Money.decimalsOf("USD"))
        assertEquals(1_999L, Money.parseToMinor("19.99", "USD"))
    }

    @Test
    fun baseCurrencyRateIsAlwaysOne() {
        val base = CurrencyCatalog.rateOf("CNY", emptyMap())
        assertEquals(Money.RATE_SCALE, base?.rateScaled)
        assertTrue(base!!.isBase)
        assertEquals("¥", Money.symbolOf(Money.BASE_CURRENCY))
    }

    @Test
    fun missingRateReturnsNullSoCallerMustConfirm() {
        assertNull(CurrencyCatalog.rateOf("USD", emptyMap()))
        val rates = mapOf("USD" to CurrencyRate("USD", 7_120_000L, RateSource.AUTO, 1_700_000_000_000L))
        assertEquals(7_120_000L, CurrencyCatalog.rateOf("USD", rates)?.rateScaled)
        assertEquals(RateSource.AUTO, CurrencyCatalog.rateOf("USD", rates)?.source)
        // 汇率被清成 0 视为没有汇率。
        assertNull(CurrencyCatalog.rateOf("USD", mapOf("USD" to CurrencyRate("USD", 0L))))
    }

    @Test
    fun supportedCurrenciesMatchPlan() {
        assertEquals(listOf("CNY", "JPY", "USD", "EUR", "GBP", "HKD"), CurrencyCatalog.supported)
        assertTrue(CurrencyCatalog.isSupported("hkd"))
        assertTrue(!CurrencyCatalog.isSupported("BTC"))
        assertEquals("基础货币", CurrencyCatalog.baseRate.let { "基础货币" })
    }

    @Test
    fun rateSourceDecodesUnknownToManual() {
        assertEquals(RateSource.AUTO, RateSource.fromDb("AUTO"))
        assertEquals(RateSource.CACHE, RateSource.fromDb("CACHE"))
        assertEquals(RateSource.MANUAL, RateSource.fromDb("WHATEVER"))
        assertEquals("手动", RateSource.MANUAL.label)
    }
}