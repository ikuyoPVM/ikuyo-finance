package xyz.ikuyo.finance.data.ai

/**
 * Base URL 规范化与端点拼接。
 *
 * 规则（对应计划书 3.1「避免重复拼接路径」）：
 *  - 允许用户只填域名、填到 `/v1`、或直接粘贴 `https://host/v1/chat/completions`；
 *  - 缺协议时默认补 `https://`；
 *  - 去掉结尾的 `/`，也去掉误粘贴的 `/chat/completions` 与 `/v1/chat/completions`；
 *  - 只要 http/https，其它协议、含空白或空串一律拒绝。
 */
object AiUrls {

    const val CHAT_PATH = "chat/completions"

    fun normalizeBaseUrl(raw: String): Result<String> {
        val input = raw.trim()
        if (input.isEmpty()) return Result.failure(AiConfigException("Base URL 不能为空"))
        if (input.any { it.isWhitespace() }) return Result.failure(AiConfigException("Base URL 不能包含空格"))
        if (input.contains('?') || input.contains('#')) {
            return Result.failure(AiConfigException("Base URL 不要带查询参数或片段"))
        }

        val withScheme = when {
            input.startsWith("https://", ignoreCase = true) -> "https://" + input.substring(8)
            input.startsWith("http://", ignoreCase = true) -> "http://" + input.substring(7)
            input.contains("://") -> return Result.failure(AiConfigException("只支持 http / https"))
            else -> "https://$input"
        }

        var rest = withScheme.substringAfter("://").trimEnd('/')
        if (rest.isEmpty() || !rest.contains('.')) {
            return Result.failure(AiConfigException("Base URL 缺少有效主机名"))
        }
        // 用户可能直接把完整接口地址粘进来
        for (suffix in listOf("/$CHAT_PATH", "/v1/$CHAT_PATH")) {
            if (rest.endsWith(suffix, ignoreCase = true)) {
                rest = rest.dropLast(suffix.length)
                if (suffix.startsWith("/v1")) rest += "/v1"
                break
            }
        }
        rest = rest.trimEnd('/')
        if (rest.isEmpty()) return Result.failure(AiConfigException("Base URL 缺少有效主机名"))
        if (rest.endsWith("/v1/v1")) rest = rest.dropLast(3)

        val scheme = if (withScheme.startsWith("http://", ignoreCase = true)) "http://" else "https://"
        return Result.success(scheme + rest)
    }

    fun chatUrl(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl).getOrElse { return baseUrl.trimEnd('/') + "/" + CHAT_PATH }
        return normalized.trimEnd('/') + "/" + CHAT_PATH
    }

    /** 只用于界面展示，绝不带密钥。 */
    fun displayHost(baseUrl: String): String =
        baseUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
}

class AiConfigException(message: String) : IllegalArgumentException(message)