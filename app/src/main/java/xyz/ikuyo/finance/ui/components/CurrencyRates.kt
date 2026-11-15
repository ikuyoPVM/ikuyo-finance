package xyz.ikuyo.finance.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.CurrencyRate

/**
 * 读取已确认的汇率表。
 *
 * 记账表单只需要「当前可用汇率」这一个能力，没必要让每个页面的 ViewModel 都转发一份，
 * 否则汇率一改就要改五处状态；汇率落库后这里会自动刷新。
 */
@Composable
fun rememberCurrencyRates(): Map<String, CurrencyRate> =
    Graph.currencyRateRepository.rates.collectAsStateWithLifecycle(initialValue = emptyMap()).value