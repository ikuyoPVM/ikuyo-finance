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

/** 连通性测试成功时的结果。reply 只用于「看到了什么」，不做业务解析。 */
data class AiProbeOutcome(
    val model: String,
    val usedBackup: Boolean,
    val latencyMillis: Long,
    val reply: String,
)

/** 连通性测试失败：带上结构化原因，界面直接用 reason.userText。 */
class ProbeException(val reason: ProbeFailure) : Exception(reason.userText)

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

    /**
     * 连通性测试：独立于账单解析链路。
     *
     * 只判断「地址、密钥、模型能不能用」，不要求回复符合任何业务结构，
     * 因此不会再出现「AI 返回的内容无法解析」这类误导性提示。
     */
    suspend fun probe(): Result<AiProbeOutcome> {
        val config = settings.config.first()
        val apiKey = settings.apiKey()
            ?: return Result.failure(ProbeException(ProbeFailure.NOT_CONFIGURED))
        if (!config.primary.isConfigured && !config.hasBackup) {
            return Result.failure(ProbeException(ProbeFailure.NOT_CONFIGURED))
        }

        var lastReason = ProbeFailure.SERVER_ERROR
        for (usePrimary in endpointOrder(config)) {
            val endpoint = config.endpoint(usePrimary) ?: continue
            if (!endpoint.isConfigured) continue
            val started = System.currentTimeMillis()
            val outcome = client.probe(
                ChatRequest(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    messages = listOf(ChatMessage.user(PROBE_PROMPT)),
                    temperature = 0.0,
                    maxTokens = PROBE_MAX_TOKENS,
                    timeoutSeconds = config.timeoutSeconds,
                )
            )
            when (outcome) {
                is ProbeOutcome.Success -> return Result.success(
                    AiProbeOutcome(
                        model = endpoint.model,
                        usedBackup = !usePrimary,
                        latencyMillis = System.currentTimeMillis() - started,
                        reply = outcome.reply,
                    )
                )

                is ProbeOutcome.Failure -> {
                    lastReason = outcome.reason
                    // 鉴权/配置问题换备用端点也没意义，直接结束。
                    if (outcome.reason == ProbeFailure.UNAUTHORIZED ||
                        outcome.reason == ProbeFailure.FORBIDDEN ||
                        outcome.reason == ProbeFailure.NOT_CONFIGURED
                    ) {
                        break
                    }
                }
            }
        }
        return Result.failure(ProbeException(lastReason))
    }

    /** 获取模型列表：失败不清空任何配置，只把原因带回去。 */
    suspend fun fetchModels(baseUrl: String): ModelListOutcome {
        val apiKey = settings.apiKey()
            ?: return ModelListOutcome.Failure(ProbeFailure.NOT_CONFIGURED)
        if (baseUrl.isBlank()) {
            return ModelListOutcome.Failure(ProbeFailure.NOT_CONFIGURED)
        }
        return client.listModels(baseUrl, apiKey, settings.config.first().timeoutSeconds)
    }

    /**
     * 带工具的一轮对话：模型可以先查数据再回答。
     *
     * 约束（计划书 3.2）：
     *  - 工具名走白名单，参数与返回大小由 [AiToolExecutor] 自己限制；
     *  - 最多 [MAX_TOOL_ROUNDS] 轮，超过就以最后一轮的文本作答；
     *  - 总时长超过 [TOOL_TOTAL_TIMEOUT_MS] 直接失败，避免无限调用；
     *  - AI 全程只能读，不接触 Room、不生成 SQL。
     */
    suspend fun chatWithTools(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        executor: AiToolExecutor,
        onToolCall: (String) -> Unit = {},
    ): Result<AiReply> {
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
        }.toMutableList()

        val started = System.currentTimeMillis()
        var lastError: AiError? = null
        var totalAttempts = 0

        repeat(MAX_TOOL_ROUNDS) { round ->
            if (System.currentTimeMillis() - started > TOOL_TOTAL_TIMEOUT_MS) {
                return Result.failure(AiException(AiError(AiErrorType.TIMEOUT, "tool loop timeout")))
            }
            var turn: ChatTurn? = null
            var usedEndpoint: xyz.ikuyo.finance.domain.model.AiEndpoint? = null
            var usedBackup = false

            endpointLoop@ for (usePrimary in endpointOrder(config)) {
                val endpoint = config.endpoint(usePrimary) ?: continue
                val request = ChatRequest(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    messages = payload.toList(),
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    timeoutSeconds = config.timeoutSeconds,
                )
                var attempt = 0
                while (attempt < AiRetryPolicy.MAX_ATTEMPTS) {
                    attempt++
                    totalAttempts++
                    val result = client.completeTurn(request, AiTools.schemasJson())
                    val ok = result.getOrNull()
                    if (ok != null) {
                        turn = ok
                        usedEndpoint = endpoint
                        usedBackup = !usePrimary
                        break@endpointLoop
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

            val currentTurn = turn ?: return Result.failure(AiException(lastError ?: noEndpoints))

            if (!currentTurn.hasToolCalls) {
                val text = currentTurn.content.orEmpty()
                if (text.isBlank()) {
                    return Result.failure(AiException(AiError(AiErrorType.BAD_RESPONSE, "empty final answer")))
                }
                return Result.success(
                    AiReply(
                        content = text,
                        usedBackup = usedBackup,
                        attempts = totalAttempts,
                        model = usedEndpoint?.model.orEmpty(),
                    )
                )
            }

            // 把模型的调用意图写回历史，再逐条回填工具结果。
            payload += ChatMessage.assistantToolCalls(currentTurn.toolCalls)
            for (call in currentTurn.toolCalls) {
                onToolCall(call.name)
                val result = runCatching { executor.execute(call.name, call.arguments) }
                    .getOrElse { error -> """{"tool":"${call.name}","error":"查询失败"}""" }
                payload += ChatMessage.toolResult(call.id, result)
            }
            if (round == MAX_TOOL_ROUNDS - 1) {
                // 最后一轮仍然想调工具：不再放行，要求模型用已有数据直接作答。
                payload += ChatMessage.user(TOOL_BUDGET_EXHAUSTED)
            }
        }
        return Result.failure(
            AiException(AiError(AiErrorType.BAD_RESPONSE, "tool rounds exhausted"))
        )
    }

    /** 主端点优先；配了备用才追加。 */
    private fun endpointOrder(config: AiConfig): List<Boolean> =
        if (config.hasBackup) listOf(true, false) else listOf(true)

    companion object {
        /** 测试消息要短，但要让推理类模型也有足够 token 回一个可见字符。 */
        const val PROBE_PROMPT = "请只回复 OK 两个字符"

        const val PROBE_MAX_TOKENS = 64

        /** 一次提问最多几轮工具调用：够查摘要+分类+趋势，又不至于无限循环。 */
        const val MAX_TOOL_ROUNDS = 4

        /** 工具链路总时长上限，超过就报超时而不是让用户干等。 */
        const val TOOL_TOTAL_TIMEOUT_MS = 90_000L

        /** 轮次用完时的收口指令，逼模型用已有数据作答。 */
        const val TOOL_BUDGET_EXHAUSTED = "工具调用次数已用完，请直接用已查到的数据回答，不要再调用工具。"
    }
}