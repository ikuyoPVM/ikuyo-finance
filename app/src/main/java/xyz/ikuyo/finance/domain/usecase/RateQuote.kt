package xyz.ikuyo.finance.domain.usecase

import java.math.BigDecimal
import java.math.RoundingMode
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.domain.model.CurrencyCatalog

/**
 * 汇率行情解析与新鲜度判断（计划书 6.3）。
 *
 * 汇率服务一般以「1 基础货币 = 多少目标币种」报价，而本地需要的是
 * 「1 目标币种 = 多少人民币」，所以这里统一做一次倒数换算，并把结果缩放到 1e6。
 * 纯函数，方便单测；网络与缓存策略分别在 [xyz.ikuyo.finance.data.remote.RateFetcher]
 * 与 [xyz.ikuyo.finance.data.repository.CurrencyRateRepository] 里。
 */
object RateQuote {

    /** 自动汇率超过这个时长就视为缓存（仍可使用，但界面要提示需要刷新）。 */
    const val STALE_AFTER_MILLIS: Long = 24L * 60L * 60L * 1000L

    /**
     * 解析以人民币为基准的行情 JSON：
     * `{"base":"CNY","rates":{"USD":0.1404,"JPY":21.28}}`。
     *
     * 返回「1 外币 = 多少人民币」的缩放汇率（1e6）；解析不出来或数值不可用的币种直接跳过，
     * 不做任何猜测。空结果由调用方视为获取失败。
     */
    fun parseCnyBase(body: String): Map<String, Long> {
        val root = Json.parseOrNull(body) as? JsonValue.Obj ?: return emptyMap()
        val base = root.str("base")?.uppercase()
        if (base != null && base != Money.BASE_CURRENCY) return emptyMap()
        val rates = root.obj("rates") as? JsonValue.Obj ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        CurrencyCatalog.supported
            .filter { it != Money.BASE_CURRENCY }
            .forEach { code ->
                val quote = (rates.get(code) as? JsonValue.Num)?.value ?: return@forEach
                if (quote <= 0.0 || quote.isNaN() || quote.isInfinite()) return@forEach
                val cnyPerUnit = BigDecimal.ONE.divide(
                    BigDecimal.valueOf(quote),
                    DECIMALS,
                    RoundingMode.HALF_UP,
                )
                val scaled = cnyPerUnit.movePointRight(RATE_SCALE_DIGITS).setScale(0, RoundingMode.HALF_UP)
                if (scaled > BigDecimal.ZERO) out[code] = scaled.toLong()
            }
        return out
    }

    /** 该汇率是否已经过期到需要提示用户刷新。 */
    fun isStale(
        updatedAt: Long,
        now: Long,
        maxAgeMillis: Long = STALE_AFTER_MILLIS,
    ): Boolean {
        if (updatedAt <= 0L) return true
        return now - updatedAt > maxAgeMillis
    }

    /** 距离上次更新多少天（不足一天按 1 天算，用于提示文案）。 */
    fun ageDays(updatedAt: Long, now: Long): Long {
        if (updatedAt <= 0L) return 0L
        val diff = (now - updatedAt).coerceAtLeast(0L)
        val days = diff / STALE_AFTER_MILLIS
        return if (diff % STALE_AFTER_MILLIS == 0L) days else days + 1L
    }

    private const val DECIMALS = 8
    private const val RATE_SCALE_DIGITS = 6
}