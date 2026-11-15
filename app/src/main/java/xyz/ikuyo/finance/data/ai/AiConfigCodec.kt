package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.domain.model.AiConfig
import xyz.ikuyo.finance.domain.model.AiEndpoint
import xyz.ikuyo.finance.domain.model.AiPersona

/**
 * 配置的持久化编解码。刻意不存 Key：Key 走 [ApiKeyStore] 的密文。
 * 解析容错——旧版本缺字段、数值越界都回落到安全默认值，绝不让坏配置挡住启动。
 */
object AiConfigCodec {

    fun encode(config: AiConfig): String = Json.dumps(
        Json.obj(
            "enabled" to Json.bool(config.enabled),
            "primary" to endpoint(config.primary),
            "backup" to config.backup?.let { endpoint(it) },
            "timeoutSeconds" to Json.num(config.timeoutSeconds.toLong()),
            "temperature" to Json.num(config.temperature),
            "maxTokens" to Json.num(config.maxTokens.toLong()),
            "autoPostThreshold" to Json.num(config.autoPostThreshold),
            "persona" to persona(config.persona),
        )
    )

    fun decode(raw: String?): AiConfig {
        val root = Json.parseOrNull(raw) as? JsonValue.Obj ?: return AiConfig()
        val d = AiConfig()
        return AiConfig(
            enabled = root.bool("enabled") ?: d.enabled,
            primary = endpointOf(root.obj("primary")) ?: d.primary,
            backup = endpointOf(root.obj("backup")),
            timeoutSeconds = (root.int("timeoutSeconds") ?: d.timeoutSeconds).coerceIn(
                AiConfig.MIN_TIMEOUT_SECONDS,
                AiConfig.MAX_TIMEOUT_SECONDS,
            ),
            temperature = (root.num("temperature") ?: d.temperature).coerceIn(0.0, 1.5),
            maxTokens = (root.int("maxTokens") ?: d.maxTokens).coerceIn(
                AiConfig.MIN_TOKENS,
                AiConfig.MAX_TOKENS,
            ),
            autoPostThreshold = (root.num("autoPostThreshold") ?: d.autoPostThreshold).coerceIn(
                AiConfig.MIN_THRESHOLD,
                AiConfig.MAX_THRESHOLD,
            ),
            persona = personaOf(root.obj("persona")),
        )
    }

    private fun endpoint(value: AiEndpoint): JsonValue.Obj = Json.obj(
        "baseUrl" to Json.str(value.baseUrl),
        "model" to Json.str(value.model),
    )

    private fun endpointOf(obj: JsonValue.Obj?): AiEndpoint? {
        if (obj == null) return null
        val baseUrl = obj.str("baseUrl")?.takeIf { it.isNotBlank() } ?: return null
        val model = obj.str("model")?.takeIf { it.isNotBlank() } ?: return null
        return AiEndpoint(baseUrl = baseUrl, model = model)
    }

    private fun persona(value: AiPersona): JsonValue.Obj = Json.obj(
        "name" to Json.str(value.name),
        "role" to Json.str(value.role),
        "tone" to Json.str(value.tone),
        "language" to Json.str(value.language),
        "length" to Json.str(value.length),
        "emoji" to Json.str(value.emoji),
        "adviceStyle" to Json.str(value.adviceStyle),
        "systemPrompt" to Json.str(value.systemPrompt),
    )

    private fun personaOf(obj: JsonValue.Obj?): AiPersona {
        val d = AiPersona()
        if (obj == null) return d
        return AiPersona(
            name = obj.str("name")?.takeIf { it.isNotBlank() } ?: d.name,
            role = obj.str("role") ?: d.role,
            tone = obj.str("tone") ?: d.tone,
            language = obj.str("language") ?: d.language,
            length = obj.str("length") ?: d.length,
            emoji = obj.str("emoji") ?: d.emoji,
            adviceStyle = obj.str("adviceStyle") ?: d.adviceStyle,
            systemPrompt = obj.str("systemPrompt") ?: d.systemPrompt,
        )
    }
}