package xyz.ikuyo.finance.domain.model

/** 会话消息角色。system 不进入列表展示，只在请求时拼到最前面。 */
enum class ChatRole(val dbValue: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromDb(value: String): ChatRole =
            entries.firstOrNull { it.dbValue == value } ?: ASSISTANT
    }
}

/** 一条会话消息；助手消息里可以挂候选账单。 */
data class AiChatMessage(
    val localId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val candidates: List<BillCandidate> = emptyList(),
    /** 生成失败或被用户停止时保留原文，便于重试 */
    val failed: Boolean = false,
)