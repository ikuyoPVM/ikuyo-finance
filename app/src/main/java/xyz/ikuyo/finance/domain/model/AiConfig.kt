package xyz.ikuyo.finance.domain.model

/**
 * 一个 OpenAI 兼容端点：Base URL + 模型名。
 * Base URL 只存到 `/v1` 这一层，具体路径由客户端拼 `/chat/completions`，
 * 避免「用户填了完整接口地址」和「只填域名」两种习惯各自拼出重复路径。
 */
data class AiEndpoint(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
        const val DEFAULT_MODEL: String = "gpt-4o-mini"
    }
}

/**
 * AI 人设。只影响表达方式，不进入任何财务计算，也不改变业务权限。
 */
data class AiPersona(
    val name: String = "ikuyo",
    val role: String = "",
    val tone: String = "friendly",
    val language: String = "zh-CN",
    val length: String = "short",
    val emoji: String = "little",
    val adviceStyle: String = "gentle",
    val systemPrompt: String = "",
) {
    val isDefault: Boolean get() = this == AiPersona()

    fun styleHint(): String = buildString {
        append("称呼你为「").append(name.ifBlank { "ikuyo" }).append("」。")
        if (role.isNotBlank()) append("角色：").append(role.trim()).append("。")
        append(
            when (tone) {
                "warm" -> "语气温和亲切。"
                "direct" -> "语气直接、少客套。"
                "playful" -> "语气轻松，可以适度玩笑。"
                else -> "语气自然友好。"
            }
        )
        append(
            when (this@AiPersona.length) {
                "detailed" -> "回复可以展开说明，但不超过 120 字。"
                else -> "回复尽量简短，控制在 60 字以内。"
            }
        )
        if (emoji == "none") append("不要使用表情符号。")
        append(
            when (adviceStyle) {
                "strict" -> "消费建议偏克制，明确点出不必要的开销。"
                "gentle" -> "消费建议温和，不指责用户。"
                else -> "只陈述事实，不强加建议。"
            }
        )
        append("始终使用").append(if (language == "en-US") "英文" else "简体中文").append("回答。")
        if (systemPrompt.isNotBlank()) append("额外要求：").append(systemPrompt.trim())
    }

    companion object {
        val TONES = listOf("friendly" to "自然", "warm" to "温和", "direct" to "直接", "playful" to "轻松")
        val LENGTHS = listOf("short" to "简短", "detailed" to "稍详细")
        val EMOJIS = listOf("little" to "少量", "none" to "不用")
        val ADVICE = listOf("gentle" to "温和", "strict" to "克制", "neutral" to "只陈述")
        val LANGUAGES = listOf("zh-CN" to "简体中文", "en-US" to "English")
    }
}

/**
 * AI 配置。密钥不在这里：Key 的密文单独存在 [xyz.ikuyo.finance.data.ai.ApiKeyStore]，
 * 这里只放可以明文保存、也可以安全导出的非敏感参数。
 */
data class AiConfig(
    val enabled: Boolean = true,
    val primary: AiEndpoint = AiEndpoint(),
    val backup: AiEndpoint? = null,
    val timeoutSeconds: Int = 45,
    val temperature: Double = 0.3,
    val maxTokens: Int = 700,
    /** 分类置信度达到该阈值且无冲突时才允许自动入账，否则要求确认。 */
    val autoPostThreshold: Double = 0.85,
    val persona: AiPersona = AiPersona(),
) {
    val hasBackup: Boolean get() = backup?.isConfigured == true

    fun endpoint(usePrimary: Boolean): AiEndpoint? =
        if (usePrimary) primary.takeIf { it.isConfigured } else backup?.takeIf { it.isConfigured }

    companion object {
        const val MIN_TIMEOUT_SECONDS = 5
        const val MAX_TIMEOUT_SECONDS = 180
        const val MIN_TOKENS = 64
        const val MAX_TOKENS = 4000
        const val MIN_THRESHOLD = 0.5
        const val MAX_THRESHOLD = 1.0
    }
}