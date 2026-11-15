package xyz.ikuyo.finance.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import xyz.ikuyo.finance.domain.model.AiConfig

/** 一次成功调用的结果。[usedBackup] 为 true 时界面要显示「当前使用备用模型」。 */
data class AiReply(
    val content: String,
    val usedBackup: Boolean,
    val attempts: Int,
    val model: String,
)

data class AiTestOutcome(
    val model: String,
    val usedBackup: Boolean,
    val latencyMillis: Long,
)

/**
 * AI 统一入口：负责「主备选择 + 有限次重试 + 错误归类」，业务层只跟它打交道。
 *
 * 计划书 3.3 的硬约束都在这里：
 *  - 鉴权失败、模型不存在不重试（[AiErrorType.retryable]）；
 *  - 网络类最多 [AiRetryPolicy.MAX_ATTEMPTS] 次，间隔递增；
 *  - 主端点失败且错误可回退时，用备用端点再走一轮；
 *  - 任何失败都保留类型，由界面决定「重试 / 去设置 / 换备用」。
 */
class AiService(
    private val settings: AiSettingsRepository,
    private val client: OpenAiClient = OpenAiClient(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    private val noEndpoints = AiError(
        AiErrorType.NOT_CONFIGURED,
        "no endpoint or api key",
    )

    suspend fun reply(messages: List<ChatMessage>, systemPrompt: String? = null): Result<AiReply> {
        val config = settings.config.first()
        if (!config.enabled) {
            return Result.failure(AiException(AiError(AiErrorType.NOT_CONFIGURED, "ai disabled")))
        }
        val apiKey = settings.apiKey()
            ?: return Result.failure(AiException(AiError(AiErrorType.NOT_CONFIGURED, "no api key")))
        if (!config.primary.isConfigured && !config.hasBackup) {
            return Result.failure(AiException(noEndpoints))
        }

        val payload = buildList {
            systemPrompt?.takeIf { it.isNotBlank() }?.let { add(ChatMessage.system(it)) }
            addAll(messages)
        }

        var lastError: AiError? = null
        var totalAttempts = 0

        for (usePrimary in endpointOrder(config)) {
            val endpoint = config.endpoint(usePrimary) ?: continue
            val request = ChatRequest(
                endpoint = endpoint,
                apiKey = apiKey,
                messages = payload,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                timeoutSeconds = config.timeoutSeconds,
            )
            var attempt = 0
            while (attempt < AiRetryPolicy.MAX_ATTEMPTS) {
                attempt++
                totalAttempts++
                val result = client.complete(request)
                val content = result.getOrNull()
                if (content != null) {
                    return Result.success(AiReply(content, usedBackup = !usePrimary, attempts = totalAttempts, model = endpoint.model))
                }
                val error = (result.exceptionOrNull() as? AiException)?.error
                    ?: result.exceptionOrNull()?.toAiError()
                    ?: noEndpoints
                lastError = error
                if (attempt >= AiRetryPolicy.attemptsFor(error)) break
                sleeper(AiRetryPolicy.delayMillis(attempt))
            }
            if (lastError?.type?.canFallback != true) break
        }

        return Result.failure(AiException(lastError ?: noEndpoints))
    }

    /** 连通性测试：发一条极短的用户消息，把结果分类成结构化对象给设置页展示。 */
    suspend fun testConnection(): Result<AiTestOutcome> {
        val config = settings.config.first()
        val apiKey = settings.apiKey()
            ?: return Result.failure(AiException(AiError(AiErrorType.NOT_CONFIGURED, "no api key")))
        if (!config.primary.isConfigured) {
            return Result.failure(AiException(AiError(AiErrorType.NOT_CONFIGURED, "no primary endpoint")))
        }
        var lastError: AiError? = null
        for (usePrimary in endpointOrder(config)) {
            val endpoint = config.endpoint(usePrimary) ?: continue
            val started = System.currentTimeMillis()
            val result = client.complete(
                ChatRequest(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    messages = listOf(ChatMessage.user("ping")),
                    temperature = 0.0,
                    maxTokens = 16,
                    timeoutSeconds = config.timeoutSeconds,
                )
            )
            if (result.isSuccess) {
                return Result.success(
                    AiTestOutcome(endpoint.model, usedBackup = !usePrimary, System.currentTimeMillis() - started)
                )
            }
            lastError = (result.exceptionOrNull() as? AiException)?.error
                ?: result.exceptionOrNull()?.toAiError()
                ?: noEndpoints
            if (lastError.type.canFallback.not()) break
        }
        return Result.failure(AiException(lastError ?: noEndpoints))
    }

    /** 主端点优先；配了备用才追加。 */
    private fun endpointOrder(config: AiConfig): List<Boolean> =
        if (config.hasBackup) listOf(true, false) else listOf(true)
}