package xyz.ikuyo.finance.feature.rates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.CurrencyCatalog
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.model.RateSource
import xyz.ikuyo.finance.domain.usecase.RateQuote
import java.math.BigDecimal

data class RatesUiState(
    val rates: Map<String, CurrencyRate> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val now: Long = 0L,
) {

    /** 支持的币种按目录顺序展示，人民币固定排在最前。 */
    val rows: List<CurrencyRate?> get() = CurrencyCatalog.supported.map { CurrencyCatalog.rateOf(it, rates) }

    fun rateOf(currency: String): CurrencyRate? = CurrencyCatalog.rateOf(currency, rates)

    val configuredCount: Int get() = CurrencyCatalog.supported.count { it != Money.BASE_CURRENCY && rateOf(it) != null }

    /** 超过一天没更新、或者来源已经是缓存的币种。 */
    val staleCodes: List<String>
        get() = rates.values
            .filter { it.currency != Money.BASE_CURRENCY && it.rateScaled > 0L }
            .filter { it.source == RateSource.CACHE || RateQuote.isStale(it.updatedAt, now) }
            .map { it.currency }
            .sorted()

    /** 需要提示用户刷新时的文案；全部新鲜时为 null。 */
    val staleHint: String?
        get() = staleCodes.takeIf { it.isNotEmpty() }?.let { codes ->
            val label = codes.joinToString("、") { CurrencyCatalog.label(it) }
            "有 ${codes.size} 个币种的汇率不是最新的（$label），建议刷新一次再记账"
        }
}

/**
 * 汇率与币种设置。
 *
 * 这里只维护「1 外币 = 多少人民币」这一份本地汇率表；账单保存时会把当时的汇率快照写进账单，
 * 之后改汇率不会回头改写历史账单（计划书 6.2）。
 */
class RatesViewModel : ViewModel() {

    private val repository = Graph.currencyRateRepository

    private data class Message(
        val busy: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
    )

    private val message = MutableStateFlow(Message())

    val state: StateFlow<RatesUiState> = combine(repository.rates, message) { rates, msg ->
        RatesUiState(
            rates = rates,
            busy = msg.busy,
            error = msg.error,
            notice = msg.notice,
            now = Time.nowMillis(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RatesUiState(),
    )

    /**
     * 自动获取汇率（计划书 6.3）。
     *
     * 成功：标记为「自动」并更新时间；失败：明确报错，旧的汇率保持原样并降级为「缓存」，
     * 界面同时给出提示，不让用户误以为用的是最新行情。人民币记账完全不受影响。
     */
    fun refresh() {
        if (message.value.busy) return
        message.value = Message(busy = true)
        viewModelScope.launch {
            repository.refreshFrom(Graph.rateFetcher)
                .onSuccess { count ->
                    message.value = Message(
                        notice = if (count <= 0) {
                            "汇率服务没有返回需要的币种，已保留原有汇率"
                        } else {
                            "已更新 $count 个币种的汇率（来源：自动）"
                        },
                    )
                }
                .onFailure { error ->
                    message.value = Message(
                        error = "${error.message ?: "获取汇率失败"}；已保存的汇率仍按上次记录使用，并标记为缓存",
                    )
                }
        }
    }

    fun saveRate(currency: String, rateText: String) {
        val code = currency.uppercase()
        if (code == Money.BASE_CURRENCY) return
        if (!CurrencyCatalog.isSupported(code)) {
            message.value = Message(error = "不支持的币种：$code")
            return
        }
        val rate = rateText.trim().toBigDecimalOrNull()
        if (rate == null || rate <= BigDecimal.ZERO) {
            message.value = Message(error = "请输入大于 0 的汇率，例如 7.12")
            return
        }
        if (rate > BigDecimal.valueOf(100_000L)) {
            message.value = Message(error = "汇率看起来不对，请检查小数点")
            return
        }
        val scaled = Money.scaledFromRate(rate)
        if (scaled <= 0L) {
            message.value = Message(error = "汇率精度超出上限，请减少小数位")
            return
        }
        message.value = Message(busy = true)
        viewModelScope.launch {
            runCatching { repository.setRate(code, scaled, RateSource.MANUAL, Time.nowMillis()) }
                .onSuccess {
                    message.value = Message(notice = "已保存 ${CurrencyCatalog.label(code)} 的汇率")
                }
                .onFailure { message.value = Message(error = it.message ?: "保存失败") }
        }
    }

    fun removeRate(currency: String) {
        val code = currency.uppercase()
        if (code == Money.BASE_CURRENCY) return
        message.value = Message(busy = true)
        viewModelScope.launch {
            runCatching { repository.removeRate(code) }
                .onSuccess {
                    message.value = Message(notice = "已删除 ${CurrencyCatalog.label(code)} 的汇率，记账时需要重新填写")
                }
                .onFailure { message.value = Message(error = it.message ?: "删除失败") }
        }
    }

    fun clearMessage() {
        message.value = Message()
    }

    companion object {
        val Factory: androidx.lifecycle.ViewModelProvider.Factory
            get() = viewModelFactory { initializer { RatesViewModel() } }
    }
}