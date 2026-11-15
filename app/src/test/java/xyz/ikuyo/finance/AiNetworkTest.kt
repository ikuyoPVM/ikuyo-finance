package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.data.ai.AiConfigCodec
import xyz.ikuyo.finance.data.ai.AiError
import xyz.ikuyo.finance.data.ai.AiErrorType
import xyz.ikuyo.finance.data.ai.AiRetryPolicy
import xyz.ikuyo.finance.data.ai.AiUrls
import xyz.ikuyo.finance.data.ai.ChatMessage
import xyz.ikuyo.finance.data.ai.ChatRequest
import xyz.ikuyo.finance.data.ai.OpenAiClient
import xyz.ikuyo.finance.domain.model.AiConfig
import xyz.ikuyo.finance.domain.model.AiEndpoint
import xyz.ikuyo.finance.domain.model.AiPersona

class AiNetworkTest {

    // ---- AiUrls：Base URL 规范化，避免重复拼 /chat/completions ----

    @Test
    fun `normalizeBaseUrl trims and strips trailing slash`() {
        assertEquals("https://api.openai.com/v1", AiUrls.normalizeBaseUrl("  https://api.openai.com/v1/  ").getOrThrow())
    }

    @Test
    fun `normalizeBaseUrl adds https when scheme missing`() {
        assertEquals("https://api.deepseek.com/v1", AiUrls.normalizeBaseUrl("api.deepseek.com/v1").getOrThrow())
    }

    @Test
    fun `normalizeBaseUrl strips pasted chat path`() {
        assertEquals("https://host.example.com/v1", AiUrls.normalizeBaseUrl("https://host.example.com/v1/chat/completions").getOrThrow())
        assertEquals("https://host.example.com", AiUrls.normalizeBaseUrl("https://host.example.com/chat/completions").getOrThrow())
    }

    @Test
    fun `normalizeBaseUrl collapses duplicated v1`() {
        assertEquals("https://host.example.com/v1", AiUrls.normalizeBaseUrl("https://host.example.com/v1/v1").getOrThrow())
    }

    @Test
    fun `normalizeBaseUrl rejects blank scheme-less host and query`() {
        assertTrue(AiUrls.normalizeBaseUrl("").isFailure)
        assertTrue(AiUrls.normalizeBaseUrl("localhost:8080").isFailure)
        assertTrue(AiUrls.normalizeBaseUrl("https://host.example.com/v1?x=1").isFailure)
        assertTrue(AiUrls.normalizeBaseUrl("ftp://host.example.com").isFailure)
    }

    @Test
    fun `chatUrl and displayHost`() {
        assertEquals("https://a.example.com/v1/chat/completions", AiUrls.chatUrl("https://a.example.com/v1"))
        assertEquals("a.example.com", AiUrls.displayHost("https://a.example.com/v1"))
    }

    // ---- Json：解析、抽取、序列化 ----

    @Test
    fun `json parses nested object and keeps number raw text`() {
        val root = Json.parseOrNull("""{"a":{"b":[1,2.50,"x"]},"ok":true,"none":null}""") as JsonValue.Obj
        val arr = root.obj("a")?.arr("b")
        assertEquals(3, arr?.size)
        assertEquals(1L, arr?.get(0)?.let { (it as JsonValue.Num).value.toLong() })
        assertEquals("2.50", (arr?.get(1) as JsonValue.Num).raw)
        assertEquals("x", (arr?.get(2) as JsonValue.Str)?.value)
        assertEquals(true, root.bool("ok"))
        assertNull(root.str("none"))
    }

    @Test
    fun `extractJson finds object inside fenced answer text`() {
        val text = "好的，我解析如下：\n```json\n{\"amount\":\"12.50\",\"type\":\"expense\"}\n```\n以上。"
        val value = Json.extractJson(text) as JsonValue.Obj
        assertEquals("12.50", value.str("amount"))
        assertEquals("expense", value.str("type"))
    }

    @Test
    fun `extractJson returns null for prose only`() {
        assertNull(Json.extractJson("今天花了点钱"))
    }

    @Test
    fun `dumps escapes quotes and newlines`() {
        val text = Json.dumps(Json.obj("note" to Json.str("他说\"贵\"\n然后走了")))
        assertTrue(text.contains("\\\""))
        assertTrue(text.contains("\\n"))
        val back = Json.parseOrNull(text) as JsonValue.Obj
        assertEquals("他说\"贵\"\n然后走了", back.str("note"))
    }

    // ---- AiConfigCodec：往返 + 越界纠正 + 坏数据不抛 ----

    @Test
    fun `config codec round trip keeps endpoint backup and persona`() {
        val config = AiConfig(
            enabled = false,
            primary = AiEndpoint("https://a.example.com/v1", "gpt-4o-mini"),
            backup = AiEndpoint("https://b.example.com/v1", "claude-3-haiku"),
            timeoutSeconds = 60,
            autoPostThreshold = 0.9,
            persona = AiPersona(name = "小 k", tone = "direct", length = "detailed", emoji = "none"),
        )
        val back = AiConfigCodec.decode(AiConfigCodec.encode(config))
        assertEquals(config, back)
    }

    @Test
    fun `config codec coerces out of range values`() {
        val back = AiConfigCodec.decode(
            """{"timeoutSeconds":9999,"maxTokens":1,"autoPostThreshold":5.0,"temperature":9}"""
        )
        assertEquals(AiConfig.MAX_TIMEOUT_SECONDS, back.timeoutSeconds)
        assertEquals(AiConfig.MIN_TOKENS, back.maxTokens)
        assertEquals(AiConfig.MAX_THRESHOLD, back.autoPostThreshold, 0.0)
        assertEquals(1.5, back.temperature, 0.0)
    }

    @Test
    fun `config codec falls back to defaults on garbage`() {
        assertEquals(AiConfig(), AiConfigCodec.decode("not json at all"))
        assertEquals(AiConfig(), AiConfigCodec.decode(null))
        assertEquals(AiConfig(), AiConfigCodec.decode("""{"primary":{"baseUrl":"","model":""}}"""))
    }

    @Test
    fun `config codec never serializes an api key`() {
        val encoded = AiConfigCodec.encode(AiConfig())
        assertTrue(!encoded.contains("sk-", ignoreCase = true))
        assertTrue(!encoded.contains("apiKey", ignoreCase = true))
    }

    // ---- OpenAiClient：请求体、内容抽取、错误归类 ----

    private fun request(endpoint: AiEndpoint = AiEndpoint("https://a.example.com/v1", "gpt-4o-mini")) =
        ChatRequest(
            endpoint = endpoint,
            apiKey = "sk-test",
            messages = listOf(ChatMessage.system("sys"), ChatMessage.user("花了 12.5 吃饭")),
            temperature = 0.3,
            maxTokens = 700,
            timeoutSeconds = 45,
        )

    @Test
    fun `buildBody has only protocol fields and no api key`() {
        val body = OpenAiClient().buildBody(request())
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"max_tokens\":700"))
        assertTrue(!body.contains("sk-test"))
        val parsed = Json.parseOrNull(body) as JsonValue.Obj
        assertEquals(2, parsed.arr("messages")?.size)
    }

    @Test
    fun `extractContent reads message content and legacy text`() {
        val client = OpenAiClient()
        assertEquals(
            "你好",
            client.extractContent("""{"choices":[{"message":{"role":"assistant","content":"你好"}}]}"""),
        )
        assertEquals("legacy", client.extractContent("""{"choices":[{"text":"legacy"}]}"""))
        assertNull(client.extractContent("""{"choices":[]}"""))
        assertNull(client.extractContent("oops"))
    }

    @Test
    fun `mapError classifies auth model quota timeout and server errors`() {
        val client = OpenAiClient()
        assertEquals(AiErrorType.UNAUTHORIZED, client.mapError(401, """{"error":{"code":"invalid_api_key"}}""").type)
        assertEquals(AiErrorType.MODEL_NOT_FOUND, client.mapError(404, "{}").type)
        assertEquals(AiErrorType.RATE_LIMITED, client.mapError(429, "{}").type)
        assertEquals(AiErrorType.SERVER_ERROR, client.mapError(503, "{}").type)
        assertEquals(
            AiErrorType.RATE_LIMITED,
            client.mapError(400, """{"error":{"code":"insufficient_quota","message":"You exceeded your quota"}}""").type,
        )
        val detail = client.mapError(401, """{"error":{"code":"invalid_api_key","type":"invalid_request_error"}}""").detail
        assertTrue(detail.contains("http=401"))
        assertTrue(!detail.contains("Bearer"))
    }

    @Test
    fun `error types drive retry and fallback decisions`() {
        assertTrue(AiErrorType.NETWORK_UNAVAILABLE.retryable)
        assertTrue(AiErrorType.TIMEOUT.retryable)
        assertTrue(AiErrorType.SERVER_ERROR.retryable)
        assertTrue(!AiErrorType.UNAUTHORIZED.retryable)
        assertTrue(!AiErrorType.MODEL_NOT_FOUND.retryable)
        assertTrue(AiErrorType.MODEL_NOT_FOUND.canFallback)
        assertTrue(!AiErrorType.UNAUTHORIZED.canFallback)
        assertEquals(3, AiRetryPolicy.attemptsFor(AiError(AiErrorType.NETWORK_UNAVAILABLE)))
        assertEquals(1, AiRetryPolicy.attemptsFor(AiError(AiErrorType.UNAUTHORIZED)))
        assertTrue(AiRetryPolicy.delayMillis(3) <= 4_000L)
        assertTrue(AiRetryPolicy.delayMillis(3) > AiRetryPolicy.delayMillis(1))
        assertTrue(AiError(AiErrorType.TIMEOUT).message.contains("超时"))
    }
}