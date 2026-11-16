package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 模型请求调用的一次工具：id 由服务商给出，回填时原样带回。 */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** 对话消息。role 只用 OpenAI 的四种取值（tool 用于回填工具结果）。 */
data class ChatMessage(
    val role: String,
    val content: String,
    /** assistant 消息里模型请求的工具调用，序列化时写成 tool_calls。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** role=tool 时对应哪一次调用。 */
    val toolCallId: String? = null,
) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"

        fun system(text: String) = ChatMessage(SYSTEM, text)
        fun user(text: String) = ChatMessage(USER, text)
        fun assistant(text: String) = ChatMessage(ASSISTANT, text)
        fun assistantToolCalls(calls: List<ToolCall>) =
            ChatMessage(ASSISTANT, "", toolCalls = calls)

        fun toolResult(callId: String, text: String) =
            ChatMessage(TOOL, text, toolCallId = callId)
    }
}

/** 一轮回复：要么是文本，要么是一批工具调用，也可能两者都有。 */
data class ChatTurn(val content: String?, val toolCalls: List<ToolCall>) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
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

    /** 模型列表是 GET；默认实现委托给 post，方便测试替身只实现一个方法。 */
    suspend fun get(url: String, apiKey: String, timeoutSeconds: Int): TransportResponse =
        post(url, apiKey, "", timeoutSeconds)
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
    ): TransportResponse = execute("POST", url, apiKey, body, timeoutSeconds)

    override suspend fun get(
        url: String,
        apiKey: String,
        timeoutSeconds: Int,
    ): TransportResponse = execute("GET", url, apiKey, null, timeoutSeconds)

    /** post 与 get 共用的执行体：只有写 body 的请求才 setDoOutput。 */
    private suspend fun execute(
        method: String,
        url: String,
        apiKey: String,
        body: String?,
        timeoutSeconds: Int,
    ): TransportResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutSeconds * 1000
            readTimeout = timeoutSeconds * 1000
            doOutput = body != null
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
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

    /**
     * 连通性测试专用：只判断「能不能连上、能不能拿到模型回复」，
     * 不要求回复是账单 JSON，也不走 [complete] 的账单文本链路。
     */
    suspend fun probe(request: ChatRequest): ProbeOutcome {
        val url = AiUrls.chatUrl(request.endpoint.baseUrl)
        val body = buildBody(request)
        val response = try {
            transport.post(url, request.apiKey, body, request.timeoutSeconds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return AiProbe.classifyThrowable(e)
        }
        return AiProbe.classify(response.status, response.body)
    }

    /**
     * 获取服务商模型列表。Base URL 已含 /v1 时不会重复拼接（见 [AiUrls.modelsUrl]）。
     */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        timeoutSeconds: Int,
    ): ModelListOutcome {
        val response = try {
            transport.get(AiUrls.modelsUrl(baseUrl), apiKey, timeoutSeconds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val probe = AiProbe.classifyThrowable(e)
            val reason = (probe as? ProbeOutcome.Failure)?.reason ?: ProbeFailure.SERVER_ERROR
            val detail = (probe as? ProbeOutcome.Failure)?.detail.orEmpty()
            return ModelListOutcome.Failure(reason, detail)
        }
        return ModelListParser.parse(response.status, response.body)
    }

    /** 组装请求体。只放协议标准字段，不掺任何厂商专有参数。 */
    fun buildBody(request: ChatRequest, tools: JsonValue.Arr? = null): String {
        val messages = Json.arr(request.messages.map { messageJson(it) })
        val fields = mutableListOf<Pair<String, JsonValue?>>(
            "model" to Json.str(request.endpoint.model),
            "messages" to messages,
            "temperature" to Json.num(request.temperature),
            "max_tokens" to Json.num(request.maxTokens.toLong()),
            "stream" to Json.bool(false),
        )
        if (tools != null && tools.items.isNotEmpty()) {
            fields += "tools" to tools
            fields += "tool_choice" to Json.str("auto")
        }
        return Json.dumps(Json.obj(*fields.toTypedArray()))
    }

    /** 单条消息的 JSON：tool 角色带 tool_call_id，assistant 带 tool_calls。 */
    private fun messageJson(message: ChatMessage): JsonValue.Obj {
        val fields = mutableListOf<Pair<String, JsonValue?>>(
            "role" to Json.str(message.role),
        )
        if (message.role == ChatMessage.ASSISTANT && message.toolCalls.isNotEmpty()) {
            // 带工具调用的 assistant 轮次，content 允许为空字符串，部分服务商要求字段存在。
            fields += "content" to Json.str(message.content)
            fields += "tool_calls" to Json.arr(
                message.toolCalls.map { call ->
                    Json.obj(
                        "id" to Json.str(call.id),
                        "type" to Json.str("function"),
                        "function" to Json.obj(
                            "name" to Json.str(call.name),
                            "arguments" to Json.str(call.arguments),
                        ),
                    )
                }
            )
        } else {
            fields += "content" to Json.str(message.content)
            message.toolCallId?.let { fields += "tool_call_id" to Json.str(it) }
        }
        return Json.obj(*fields.toTypedArray())
    }

    /** 一轮对话：可能是纯文本，也可能要求调用工具。 */
    suspend fun completeTurn(request: ChatRequest, tools: JsonValue.Arr? = null): Result<ChatTurn> {
        val url = AiUrls.chatUrl(request.endpoint.baseUrl)
        val body = buildBody(request, tools)
        val response = try {
            transport.post(url, request.apiKey, body, request.timeoutSeconds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            return Result.failure(AiException(AiError(AiErrorType.TIMEOUT, "socket timeout")))
        } catch (e: Exception) {
            return e.toAiError().let { Result.failure(AiException(it)) }
        }
        if (response.status !in 200..299) {
            return Result.failure(AiException(mapError(response.status, response.body)))
        }
        val turn = extractTurn(response.body)
            ?: return Result.failure(AiException(AiError(AiErrorType.BAD_RESPONSE, "no message in choices")))
        return Result.success(turn)
    }

    /** 从响应体抽出这一轮的文本与工具调用；两者都为空视为格式异常。 */
    fun extractTurn(body: String): ChatTurn? {
        val root = Json.parseOrNull(body) as? JsonValue.Obj ?: return null
        val choice = root.arr("choices")?.get(0) as? JsonValue.Obj ?: return null
        val message = choice.obj("message")
        val content = message?.str("content") ?: choice.str("text")
        val calls = message?.arr("tool_calls")?.items.orEmpty().mapNotNull { item ->
            val obj = item as? JsonValue.Obj ?: return@mapNotNull null
            val function = obj.obj("function") ?: return@mapNotNull null
            val name = function.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ToolCall(
                id = obj.str("id").orEmpty().ifBlank { name },
                name = name,
                arguments = function.str("arguments").orEmpty(),
            )
        }
        if (content.isNullOrBlank() && calls.isEmpty()) return null
        return ChatTurn(content?.takeIf { it.isNotBlank() }, calls)
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