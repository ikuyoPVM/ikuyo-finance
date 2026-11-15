package xyz.ikuyo.finance.domain.model

import java.math.BigDecimal
import xyz.ikuyo.finance.core.Money

/**
 * 汇率来源。
 *
 * 账单表会保存「保存当时使用的汇率」以及这条汇率的来源与更新时间，
 * 后续汇率变化不会改写历史账单（计划书 6.2）。
 */
enum class RateSource(val label: String, val dbValue: String) {
    /** 用户手动填写或修改 */
    MANUAL("手动", "MANUAL"),

    /** 从汇率服务自动获取 */
    AUTO("自动", "AUTO"),

    /** 自动获取失败后使用的本地缓存汇率 */
    CACHE("缓存", "CACHE"),
    ;

    companion object {
        fun fromDb(value: String): RateSource =
            entries.firstOrNull { it.dbValue == value } ?: MANUAL
    }
}

/**
 * 单个币种对人民币的汇率快照。
 *
 * @param currency ISO 币种代码（CNY / JPY / USD / EUR / GBP / HKD）。
 * @param rateScaled 1 单位该币种可兑换的人民币金额，缩放 1e6 保存。
 * @param source 汇率来源。
 * @param updatedAt 该汇率最近一次写入的时间（毫秒）。
 */
data class CurrencyRate(
    val currency: String,
    val rateScaled: Long,
    val source: RateSource = RateSource.MANUAL,
    val updatedAt: Long = 0L,
) {
    val rate: BigDecimal get() = Money.rateFromScaled(rateScaled)

    val isBase: Boolean get() = currency == Money.BASE_CURRENCY
}

/** 支持的币种清单（计划书 6.1：基础货币 CNY，首版支持 6 种）。 */
object CurrencyCatalog {

    const val CNY: String = "CNY"
    const val JPY: String = "JPY"
    const val USD: String = "USD"
    const val EUR: String = "EUR"
    const val GBP: String = "GBP"
    const val HKD: String = "HKD"

    val supported: List<String> = listOf(CNY, JPY, USD, EUR, GBP, HKD)

    /** 人民币自身的汇率恒为 1。 */
    val baseRate: CurrencyRate = CurrencyRate(
        currency = CNY,
        rateScaled = Money.RATE_SCALE,
        source = RateSource.MANUAL,
        updatedAt = 0L,
    )

    fun isSupported(currency: String): Boolean = supported.any { it.equals(currency, ignoreCase = true) }

    fun label(currency: String): String = when (currency.uppercase()) {
        CNY -> "人民币 CNY"
        JPY -> "日元 JPY"
        USD -> "美元 USD"
        EUR -> "欧元 EUR"
        GBP -> "英镑 GBP"
        HKD -> "港币 HKD"
        else -> currency.uppercase()
    }

    /** 取汇率快照；CNY 永远可用，其它币种没有记录时返回 null（要求用户先确认汇率）。 */
    fun rateOf(currency: String, rates: Map<String, CurrencyRate>): CurrencyRate? {
        val code = currency.uppercase()
        if (code == CNY) return baseRate
        return rates[code]?.takeIf { it.rateScaled > 0L }
    }
}