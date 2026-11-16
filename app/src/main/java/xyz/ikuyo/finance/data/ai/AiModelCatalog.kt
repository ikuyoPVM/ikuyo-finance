package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue

/**
 * 服务商返回的一个可选项。只保留 id，owner 仅用于展示补充信息。
 */
data class ModelOption(
    val id: String,
    val ownedBy: String? = null,
)

/**
 * 模型列表的获取结果。失败原因复用 [ProbeFailure]，避免两套文案。
 */
sealed interface ModelListOutcome {

    data class Success(val models: List<ModelOption>) : ModelListOutcome

    data class Failure(val reason: ProbeFailure, val detail: String = "") : ModelListOutcome
}

/**
 * 模型列表解析：纯函数，不碰网络，便于单测。
 *
 * 兼容两种常见形状：
 *  - `{"data":[{"id":"gpt-4o-mini","owned_by":"openai"}]}`（OpenAI 兼容）
 *  - `{"models":[{"name":"..."}]}` / `{"data":["gpt-4o-mini"]}`（少数网关）
 */
object ModelListParser {

    fun parse(status: Int, body: String?): ModelListOutcome {
        if (status !in 200..299) {
            val probe = AiProbe.classify(status, body)
            val reason = (probe as? ProbeOutcome.Failure)?.reason ?: ProbeFailure.SERVER_ERROR
            val detail = (probe as? ProbeOutcome.Failure)?.detail.orEmpty()
            // 列表接口不存在时，语义上更接近「不支持该接口」，但保留 404 原始归类便于用户排查地址。
            return ModelListOutcome.Failure(reason, detail)
        }
        val root = Json.parseOrNull(body.orEmpty())
            ?: return ModelListOutcome.Failure(ProbeFailure.INVALID_JSON, "http=$status not-json")
        val models = optionsOf(root)
        if (models.isEmpty()) {
            return ModelListOutcome.Failure(ProbeFailure.EMPTY_LIST, "http=$status empty-list")
        }
        return ModelListOutcome.Success(models)
    }

    /** 从响应体里抽取模型条目，按 id 去重并保持服务商返回顺序。 */
    fun optionsOf(root: JsonValue): List<ModelOption> {
        val obj = root as? JsonValue.Obj ?: return emptyList()
        val array = obj.arr("data") ?: obj.arr("models") ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val result = ArrayList<ModelOption>(array.items.size)
        for (item in array.items) {
            val option = when (item) {
                is JsonValue.Obj -> {
                    val id = item.str("id") ?: item.str("name") ?: item.str("model")
                    if (id == null) null else ModelOption(id.trim(), item.str("owned_by"))
                }
                is JsonValue.Str -> ModelOption(item.value.trim())
                else -> null
            }
            val id = option?.id?.takeIf { it.isNotBlank() } ?: continue
            if (seen.add(id)) result += option
        }
        return result
    }
}