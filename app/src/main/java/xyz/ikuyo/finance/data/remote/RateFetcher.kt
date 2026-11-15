package xyz.ikuyo.finance.data.remote

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.ikuyo.finance.domain.usecase.RateQuote

/**
 * 汇率行情来源。
 *
 * 只负责「拿到一份以人民币为基准的汇率表」；写入、缓存与提示都由
 * [xyz.ikuyo.finance.data.repository.CurrencyRateRepository] 负责，
 * 这样自动获取失败时旧汇率不会被覆盖，也不会静默当成最新汇率使用（计划书 6.3）。
 */
interface RateFetcher {

    /** @return 成功时返回「1 外币 = 多少人民币」的缩放汇率；失败时带可读原因。 */
    suspend fun fetch(): Result<Map<String, Long>>
}

/**
 * 通过公开汇率接口获取行情。
 *
 * 失败一律返回可读中文原因，不在内部吞掉异常，也不回退到旧汇率——
 * 由界面明确告诉用户「这次没拿到最新汇率」。
 */
class HttpRateFetcher(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val timeoutMillis: Int = 10_000,
) : RateFetcher {

    override suspend fun fetch(): Result<Map<String, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("汇率服务返回 HTTP $status，请稍后再试")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val rates = RateQuote.parseCnyBase(body)
                if (rates.isEmpty()) {
                    throw IllegalStateException("汇率服务返回的数据无法识别，请稍后再试")
                }
                rates
            } finally {
                connection.disconnect()
            }
        }.recoverCatching { error ->
            throw IllegalStateException(friendlyMessage(error), error)
        }
    }

    private fun friendlyMessage(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.startsWith("汇率服务") -> text
            text.contains("Unable to resolve host", ignoreCase = true) ||
                text.contains("UnknownHost", ignoreCase = true) ->
                "连不上汇率服务（网络不可用或域名解析失败），可以手动填写汇率"
            text.contains("timeout", ignoreCase = true) ||
                text.contains("timed out", ignoreCase = true) ->
                "汇率服务响应超时，可以手动填写汇率"
            text.contains("SSL", ignoreCase = true) || text.contains("certificate", ignoreCase = true) ->
                "汇率服务证书校验失败，可以手动填写汇率"
            else -> "获取汇率失败：${text.ifBlank { error.javaClass.simpleName }}"
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT: String =
            "https://api.frankfurter.app/latest?from=CNY&to=USD,JPY,EUR,GBP,HKD"
    }
}