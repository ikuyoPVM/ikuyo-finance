package xyz.ikuyo.finance.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.remote.RateFetcher
import xyz.ikuyo.finance.domain.model.CurrencyCatalog
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.model.RateSource

private val Context.rateDataStore: DataStore<Preferences> by preferencesDataStore("ikuyo_currency_rates")

/**
 * 汇率存储（DataStore）。
 *
 * 只保存「用户已确认的汇率」：自动获取成功或手动填写都会落到这里，
 * 每条都带来源与更新时间，账单保存时把当时的快照写进账单，历史账单不随后续汇率变动。
 * 人民币恒为 1，不需要存储。
 */
class CurrencyRateRepository(private val context: Context) {

    val rates: Flow<Map<String, CurrencyRate>> = context.rateDataStore.data.map { prefs ->
        parse(prefs[KEY_RATES].orEmpty())
    }

    suspend fun snapshot(): Map<String, CurrencyRate> = rates.first()

    /** 写入一条汇率；rateScaled <= 0 视为清除。 */
    suspend fun setRate(
        currency: String,
        rateScaled: Long,
        source: RateSource = RateSource.MANUAL,
        updatedAt: Long = Time.nowMillis(),
    ) {
        val code = currency.uppercase()
        if (!CurrencyCatalog.isSupported(code) || code == Money.BASE_CURRENCY) return
        context.rateDataStore.edit { prefs ->
            val current = parse(prefs[KEY_RATES].orEmpty()).toMutableMap()
            if (rateScaled <= 0L) {
                current.remove(code)
            } else {
                current[code] = CurrencyRate(code, rateScaled, source, updatedAt)
            }
            prefs[KEY_RATES] = format(current)
        }
    }

    suspend fun removeRate(currency: String) = setRate(currency, 0L, RateSource.MANUAL, 0L)

    /**
     * 自动获取一次汇率（计划书 6.3）。
     *
     * 成功：把拿到的币种写成 [RateSource.AUTO] 并更新时间，没拿到的币种保持原样。
     * 失败：**不写任何汇率**，把失败原因原样抛给调用方，界面必须明确提示，
     * 不允许静默把旧汇率当成最新汇率使用；同时把原本标记为自动的汇率降级为缓存，
     * 让界面能看出「这份汇率是上次取到的」。
     *
     * @return 成功更新的币种数量。
     */
    suspend fun refreshFrom(fetcher: RateFetcher, now: Long = Time.nowMillis()): Result<Int> {
        val fetched = fetcher.fetch()
        fetched.exceptionOrNull()?.let { error ->
            markAutoAsCache(now)
            return Result.failure(error)
        }
        val rates = fetched.getOrThrow()
        if (rates.isEmpty()) {
            markAutoAsCache(now)
            return Result.failure(IllegalStateException("汇率服务没有返回可用币种，请稍后再试"))
        }
        context.rateDataStore.edit { prefs ->
            val current = parse(prefs[KEY_RATES].orEmpty()).toMutableMap()
            rates.forEach { (code, scaled) ->
                if (CurrencyCatalog.isSupported(code) && code != Money.BASE_CURRENCY && scaled > 0L) {
                    current[code] = CurrencyRate(code, scaled, RateSource.AUTO, now)
                }
            }
            prefs[KEY_RATES] = format(current)
        }
        return Result.success(rates.size)
    }

    /** 自动汇率降级为缓存（保留数值与时间，只换来源标签）。 */
    suspend fun markAutoAsCache(now: Long = Time.nowMillis()) {
        context.rateDataStore.edit { prefs ->
            val current = parse(prefs[KEY_RATES].orEmpty())
            if (current.values.none { it.source == RateSource.AUTO }) return@edit
            val updated = current.mapValues { (_, rate) ->
                if (rate.source == RateSource.AUTO) rate.copy(source = RateSource.CACHE) else rate
            }
            prefs[KEY_RATES] = format(updated)
        }
    }

    private fun parse(raw: String): Map<String, CurrencyRate> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(';')
            .mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size < 4) return@mapNotNull null
                val code = parts[0].uppercase()
                val scaled = parts[1].toLongOrNull() ?: return@mapNotNull null
                if (scaled <= 0L || !CurrencyCatalog.isSupported(code)) return@mapNotNull null
                CurrencyRate(
                    currency = code,
                    rateScaled = scaled,
                    source = RateSource.fromDb(parts[2]),
                    updatedAt = parts[3].toLongOrNull() ?: 0L,
                )
            }
            .associateBy { it.currency }
    }

    private fun format(rates: Map<String, CurrencyRate>): String = rates.values
        .sortedBy { it.currency }
        .joinToString(";") { "${it.currency}:${it.rateScaled}:${it.source.dbValue}:${it.updatedAt}" }

    private companion object {
        val KEY_RATES = stringPreferencesKey("rates")
    }
}