package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 对话消息。role 只用 OpenAI 的三种取值。 */
data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"

        fun system(text: String) = ChatMessage(SYSTEM, text)
        fun user(text: String) = ChatMessage(USER, text)
        fun assistant(text: String) = ChatMessage(ASSISTANT, text)
    }
}

data class ChatRequest(
    val endpoint: xyz.ikuyo.finance.domain.model.AiEndpoint,
    val apiKey: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val maxTokens: Int,
    val timeoutSeconds: Int,
)

/** 传输层返回：HTTP 状态 + 原始响应体；网络异常直接抛 IOException 交给上层归类。 */
data class TransportResponse(val status: Int, val body: String)

interface ChatTransport {
    suspend fun post(url: String, apiKey: String, body: String, timeoutSeconds: Int): TransportResponse
}

/**
 * 基于 HttpURLConnection 的最小实现：不引入额外网络库，请求体只有 chat/completions 一种形状。
 * 注意：API Key 只进 Authorization 头，不拼进 URL、不写日志。
 * 网络读写必须切到 IO 线程，否则会抛 NetworkOnMainThreadException（该异常不是 IOException，
 * 会在上层被误判成「返回格式异常」，所以这里显式 withContext(Dispatchers.IO)）。
 */
class HttpChatTransport : ChatTransport {

    override suspend fun post(
        url: String,
        apiKey: String,
        body: String,
        timeoutSeconds: Int,
    ): TransportResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutSeconds * 1000
            readTimeout = timeoutSeconds * 1000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            TransportResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * OpenAI 兼容协议客户端：请求体构造、响应抽取、错误归类都是纯函数式，
 * 便于在没有网络的情况下单测。
 */
class OpenAiClient(private val transport: ChatTransport = HttpChatTransport()) {

    suspend fun complete(request: ChatRequest): Result<String> {
        val url = AiUrls.chatUrl(request.endpoint.baseUrl)
        val body = buildBody(request)
        val response = try {
            transport.post(url, request.apiKey, body, request.timeoutSeconds)
        } catch (e: SocketTimeoutException) {
            return Result.failure(AiException(AiError(AiErrorType.TIMEOUT, "socket timeout")))
        } catch (e: IOException) {
            return e.toAiError().let { Result.failure(AiException(it)) }
        } catch (e: Exception) {
            return e.toAiError().let { Result.failure(AiException(it)) }
        }

        if (response.status !in 200..299) {
            return Result.failure(AiException(mapError(response.status, response.body)))
        }
        val content = extractContent(response.body)
            ?: return Result.failure(AiException(AiError(AiErrorType.BAD_RESPONSE, "no content in choices")))
        return Result.success(content)
    }

    /** 组装请求体。只放协议标准字段，不掺任何厂商专有参数。 */
    fun buildBody(request: ChatRequest): String {
        val messages = Json.arr(
            request.messages.map { message ->
                Json.obj("role" to Json.str(message.role), "content" to Json.str(message.content))
            }
        )
        return Json.dumps(
            Json.obj(
                "model" to Json.str(request.endpoint.model),
                "messages" to messages,
                "temperature" to Json.num(request.temperature),
                "max_tokens" to Json.num(request.maxTokens.toLong()),
                "stream" to Json.bool(false),
            )
        )
    }

    fun extractContent(body: String): String? {
        val root = Json.parseOrNull(body) as? JsonValue.Obj ?: return null
        val choice = root.arr("choices")?.get(0) as? JsonValue.Obj ?: return null
        val message = choice.obj("message")
        val content = message?.str("content") ?: choice.str("text")
        return content?.takeIf { it.isNotBlank() }
    }

    /**
     * HTTP 状态 + 响应体 → 错误类型。判定顺序很重要：先看 provider 的 error.code，
     * 再看状态码，避免把「模型不存在」误报成「Key 无效」。
     */
    fun mapError(status: Int, body: String): AiError {
        val root = Json.parseOrNull(body) as? JsonValue.Obj
        val errorObj = root?.obj("error")
        val code = errorObj?.str("code") ?: root?.str("code")
        val providerType = errorObj?.str("type")
        val providerMessage = errorObj?.str("message").orEmpty()

        val looksLikeModelMissing =
            code?.contains("model", ignoreCase = true) == true ||
                providerMessage.contains("model", ignoreCase = true) &&
                (providerMessage.contains("not found", ignoreCase = true) ||
                    providerMessage.contains("does not exist", ignoreCase = true) ||
                    providerMessage.contains("no access", ignoreCase = true))

        val looksLikeQuota = code?.contains("quota", ignoreCase = true) == true ||
            providerMessage.contains("quota", ignoreCase = true) ||
            providerMessage.contains("billing", ignoreCase = true)

        val type = when {
            status == 401 || status == 403 -> AiErrorType.UNAUTHORIZED
            status == 404 -> AiErrorType.MODEL_NOT_FOUND
            status == 429 -> AiErrorType.RATE_LIMITED
            looksLikeModelMissing -> AiErrorType.MODEL_NOT_FOUND
            looksLikeQuota -> AiErrorType.RATE_LIMITED
            status in 500..599 -> AiErrorType.SERVER_ERROR
            status == 400 && providerType == "invalid_request_error" -> AiErrorType.BAD_RESPONSE
            status in 400..499 -> AiErrorType.BAD_RESPONSE
            else -> AiErrorType.SERVER_ERROR
        }
        val safeDetail = buildString {
            append("http=").append(status)
            if (!code.isNullOrBlank()) append(" code=").append(code)
            if (!providerType.isNullOrBlank()) append(" type=").append(providerType)
        }
        return AiError(type, safeDetail, status, code)
    }
}