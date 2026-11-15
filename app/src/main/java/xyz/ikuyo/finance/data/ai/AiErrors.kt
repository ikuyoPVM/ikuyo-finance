package xyz.ikuyo.finance.data.ai

/**
 * AI 调用失败的分类。界面文案由 [AiErrorType.userText] 生成，
 * 业务层只根据类型决定「能不能重试」「要不要切备用」。
 */
enum class AiErrorType {
    NOT_CONFIGURED,
    NETWORK_UNAVAILABLE,
    DNS_OR_TLS,
    UNAUTHORIZED,
    MODEL_NOT_FOUND,
    RATE_LIMITED,
    TIMEOUT,
    SERVER_ERROR,
    BAD_RESPONSE,
    CANCELLED,
    ;

    /** 网络类失败才值得重试；鉴权、模型不存在、参数问题重试也不会变好。 */
    val retryable: Boolean
        get() = when (this) {
            NETWORK_UNAVAILABLE, DNS_OR_TLS, TIMEOUT, SERVER_ERROR, RATE_LIMITED -> true
            else -> false
        }

    /** 主模型失败后是否值得换备用端点再试一次。 */
    val canFallback: Boolean
        get() = when (this) {
            NETWORK_UNAVAILABLE, DNS_OR_TLS, TIMEOUT, SERVER_ERROR, RATE_LIMITED, MODEL_NOT_FOUND -> true
            else -> false
        }

    val userText: String
        get() = when (this) {
            NOT_CONFIGURED -> "还没有配置 AI 服务，先到「我的 → AI 设置」填写 Base URL、模型与 API Key。"
            NETWORK_UNAVAILABLE -> "网络不可用，检查一下 Wi-Fi 或移动数据。"
            DNS_OR_TLS -> "连不上 AI 服务（域名或证书有问题），确认 Base URL 是否正确。"
            UNAUTHORIZED -> "API Key 无效或已过期，请到设置里更新。"
            MODEL_NOT_FOUND -> "模型不存在或当前 Key 无权使用该模型。"
            RATE_LIMITED -> "请求太频繁或额度不足，稍后再试。"
            TIMEOUT -> "AI 响应超时，可以重试一次。"
            SERVER_ERROR -> "AI 服务暂时出错，稍后再试。"
            BAD_RESPONSE -> "AI 返回的内容无法解析，换成更明确的一句话再试。"
            CANCELLED -> "已停止生成。"
        }
}

/**
 * 带类型的失败结果。[detail] 只给开发者看，永远不包含 API Key 与请求头。
 * [httpStatus] 与 [providerCode] 用于区分「模型不存在」与「鉴权失败」。
 */
data class AiError(
    val type: AiErrorType,
    val detail: String = "",
    val httpStatus: Int = 0,
    val providerCode: String? = null,
) {
    val message: String get() = type.userText

    fun withDetail(extra: String): AiError = copy(detail = if (detail.isBlank()) extra else "$detail; $extra")
}

/** 结果包装：调用方拿 Result 处理成功/失败，不抛异常穿越 ViewModel。 */
typealias AiResult<T> = Result<T>

internal fun <T> AiError.toFailure(): Result<T> = Result.failure(AiException(this))

class AiException(val error: AiError) : Exception(error.message)

/** 把内部异常安全地转成 [AiError]，不泄漏密钥与完整请求体。 */
internal fun Throwable.toAiError(): AiError = when (this) {
    is AiException -> error
    is java.net.UnknownHostException -> AiError(AiErrorType.DNS_OR_TLS, "UnknownHost")
    is javax.net.ssl.SSLException -> AiError(AiErrorType.DNS_OR_TLS, "SSL")
    is java.net.SocketTimeoutException -> AiError(AiErrorType.TIMEOUT, "timeout")
    is java.net.ConnectException -> AiError(AiErrorType.NETWORK_UNAVAILABLE, "connect")
    is java.io.IOException -> AiError(AiErrorType.NETWORK_UNAVAILABLE, "io")
    else -> AiError(AiErrorType.BAD_RESPONSE, this::class.simpleName ?: "unknown")
}

/**
 * 有限次重试 + 递增等待。计划书要求「网络类重试有上限、鉴权失败不重试」。
 * 纯函数，单测可直接覆盖。
 */
object AiRetryPolicy {

    const val MAX_ATTEMPTS = 3
    private const val BASE_DELAY_MS = 800L
    private const val MAX_DELAY_MS = 4_000L

    fun attemptsFor(error: AiError): Int = if (error.type.retryable) MAX_ATTEMPTS else 1

    /** 第 [attempt] 次失败后的等待时间（attempt 从 1 开始）。 */
    fun delayMillis(attempt: Int): Long {
        if (attempt <= 0) return 0L
        var delay = BASE_DELAY_MS
        repeat(attempt - 1) { delay *= 2 }
        return delay.coerceAtMost(MAX_DELAY_MS)
    }
}