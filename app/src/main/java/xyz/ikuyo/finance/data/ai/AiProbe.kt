package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 连通性测试的独立结果类型。
 *
 * 与 [xyz.ikuyo.finance.domain.model.AiConfig] 无关，也与自然语言记账的解析链路无关：
 * 这里只回答「这次请求是否连上了、跑通了」，绝不把回复当成账单 JSON 去解析。
 */
sealed interface ProbeOutcome {

    data class Success(val reply: String) : ProbeOutcome

    data class Failure(val reason: ProbeFailure, val detail: String = "") : ProbeOutcome
}

/**
 * 连通性测试的失败原因。文案与计划书 3.4 的错误分类表逐条对应，
 * 不再把「内容无法解析」当作通用错误。
 */
enum class ProbeFailure(val userText: String) {

    NOT_CONFIGURED("还没有配置完整：Base URL、模型和 API Key 都要填。"),

    UNAUTHORIZED("API Key 无效或未获授权。"),

    FORBIDDEN("当前 API Key 没有调用该模型的权限。"),

    NOT_FOUND("接口地址或接口路径不存在，请检查 Base URL。"),

    RATE_LIMITED("请求过于频繁或额度不足，请稍后重试。"),

    SERVER_ERROR("AI 服务商暂时不可用，请稍后再试。"),

    TIMEOUT("连接超时，请检查网络或服务商状态。"),

    NETWORK("无法连接服务商，请检查 Base URL 与网络。"),

    INVALID_JSON("服务商返回格式异常，可能不是 OpenAI 兼容接口。"),

    EMPTY_REPLY("接口已响应，但模型没有返回可读取内容。"),

    MODEL_UNAVAILABLE("当前模型不可用，请重新获取模型列表并选择。"),

    EMPTY_LIST("模型列表为空，服务商没有返回任何可用模型。"),
}

/**
 * 把一次 HTTP 交互归类成 [ProbeOutcome]。纯函数，方便单测覆盖各类服务商行为。
 */
object AiProbe {

    fun classify(status: Int, body: String?): ProbeOutcome {
        val text = body.orEmpty()
        if (status !in 200..299) return classifyHttpError(status, text)
        val root = Json.parseOrNull(text)
            ?: return ProbeOutcome.Failure(ProbeFailure.INVALID_JSON, "http=$status not-json")
        val content = replyOf(root)
        if (content.isNullOrBlank()) {
            return ProbeOutcome.Failure(ProbeFailure.EMPTY_REPLY, "http=$status empty-content")
        }
        return ProbeOutcome.Success(content.trim())
    }

    /** 网络层异常归类；不打印任何请求头或密钥。 */
    fun classifyThrowable(error: Throwable): ProbeOutcome = when (error) {
        is SocketTimeoutException -> ProbeOutcome.Failure(ProbeFailure.TIMEOUT, "timeout")
        is UnknownHostException -> ProbeOutcome.Failure(ProbeFailure.NETWORK, "unknown-host")
        is SSLException -> ProbeOutcome.Failure(ProbeFailure.NETWORK, "tls")
        is ConnectException -> ProbeOutcome.Failure(ProbeFailure.NETWORK, "connect")
        is IOException -> ProbeOutcome.Failure(ProbeFailure.NETWORK, error.javaClass.simpleName)
        else -> ProbeOutcome.Failure(ProbeFailure.SERVER_ERROR, error.javaClass.simpleName)
    }

    /** 从响应体里取模型回复；兼容 message.content 与旧式 text。 */
    fun replyOf(root: JsonValue): String? {
        val obj = root as? JsonValue.Obj ?: return null
        val choice = obj.arr("choices")?.items?.firstOrNull() as? JsonValue.Obj ?: return null
        return choice.obj("message")?.str("content") ?: choice.str("text")
    }

    /** 服务商错误体里的 error.message / code / type，便于在详情里显示。 */
    fun providerMessage(body: String): String? {
        val obj = Json.parseOrNull(body) as? JsonValue.Obj ?: return null
        val error = obj.obj("error") ?: return null
        return error.str("message") ?: error.str("code") ?: error.str("type")
    }

    private fun classifyHttpError(status: Int, body: String): ProbeOutcome {
        val message = providerMessage(body).orEmpty()
        val detail = if (message.isBlank()) "http=$status" else "http=$status $message"
        val lower = message.lowercase()
        if (looksLikeModelProblem(lower)) {
            return ProbeOutcome.Failure(ProbeFailure.MODEL_UNAVAILABLE, detail)
        }
        return when {
            status == 401 -> ProbeOutcome.Failure(ProbeFailure.UNAUTHORIZED, detail)
            status == 403 -> ProbeOutcome.Failure(ProbeFailure.FORBIDDEN, detail)
            status == 404 -> ProbeOutcome.Failure(ProbeFailure.NOT_FOUND, detail)
            status == 429 || looksLikeQuota(lower) -> ProbeOutcome.Failure(ProbeFailure.RATE_LIMITED, detail)
            status in 500..599 -> ProbeOutcome.Failure(ProbeFailure.SERVER_ERROR, detail)
            status == 400 && Json.parseOrNull(body) == null ->
                ProbeOutcome.Failure(ProbeFailure.INVALID_JSON, detail)
            status == 400 -> ProbeOutcome.Failure(ProbeFailure.INVALID_JSON, detail)
            else -> ProbeOutcome.Failure(ProbeFailure.SERVER_ERROR, detail)
        }
    }

    private fun looksLikeModelProblem(lower: String): Boolean {
        if (!lower.contains("model")) return false
        return listOf("not found", "does not exist", "no access", "not supported", "invalid", "unknown")
            .any { lower.contains(it) }
    }

    private fun looksLikeQuota(lower: String): Boolean =
        listOf("quota", "billing", "insufficient", "exceeded").any { lower.contains(it) }
}