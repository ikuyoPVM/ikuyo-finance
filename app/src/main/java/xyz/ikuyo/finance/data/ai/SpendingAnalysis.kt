package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.domain.usecase.SpendingFacts

/**
 * 消费分析：把 [SpendingFacts] 交给模型，只让它解释，不让它算。
 *
 * 三条硬约束（计划书第 4 章）：
 * 1. 事实段只能用给定数字，且必须与本地统计一致；模型不得引入新数字。
 * 2. 估算段必须写成推断口径（「按目前节奏」「如果保持」），不得当事实说。
 * 3. 建议段必须具体可执行，且不得让 App 自动改预算或账单。
 */
data class SpendingInsight(
    val facts: List<String>,
    val estimates: List<String>,
    val suggestions: List<String>,
    val caveats: List<String>,
) {
    val isEmpty: Boolean
        get() = facts.isEmpty() && estimates.isEmpty() && suggestions.isEmpty() && caveats.isEmpty()
}

object SpendingAnalysis {

    /** 提示词版本号：口径或格式变更时递增，便于排查历史结果。 */
    const val PROMPT_VERSION = 1

    private const val MAX_ITEMS = 5

    fun systemPrompt(): String = buildString {
        appendLine("你是一个记账 App 的消费分析助手，只负责解释用户提供的统计数据。")
        appendLine()
        appendLine("【必须遵守】")
        appendLine("1. 你拿到的所有数字都来自 App 的本地统计，直接引用即可；禁止自己加减、换算或编造任何新数字。")
        appendLine("2. facts 只写能由给定数据直接验证的结论（金额、占比、笔数、变化百分比、预算进度）。")
        appendLine("3. estimates 写推断性内容（趋势、可能原因、下期大致方向），必须带上「可能」「按目前节奏」「如果保持」这类限定词。")
        appendLine("4. suggestions 必须是用户可立即执行的具体动作（例如「把外卖控制在每周两次」），不要空话。")
        appendLine("5. caveats 写数据局限：样本少、周期未结束、缺少上期数据、分类不全等；没有局限就返回空数组。")
        appendLine("6. 不要把相关性说成因果；不要下评价性人格判断。")
        appendLine("7. 你不能修改任何账单、预算或余额，也不要建议 App 自动修改。")
        appendLine("8. 金额单位是元，保留两位小数，不带货币符号。")
        appendLine()
        appendLine("【输出格式】只输出一个 JSON 对象，不要 Markdown 围栏：")
        appendLine("""{"facts":["..."],"estimates":["..."],"suggestions":["..."],"caveats":["..."]}""")
        appendLine("每个数组最多 $MAX_ITEMS 条，每条一句话，中文。")
    }.trim()

    fun userMessage(facts: SpendingFacts): String = buildString {
        appendLine("请分析下面这段时间的消费情况。")
        appendLine()
        appendLine("【统计数据（JSON，金额单位为元）】")
        appendLine(facts.toPromptJson())
        appendLine()
        appendLine("按约定输出 JSON：facts 只引用上面的数字，estimates 用推断口径，suggestions 具体可执行，caveats 说明数据局限。")
    }.trim()

    /** 解析模型回复；格式不符返回 null，由上层提示重试。 */
    fun parse(content: String?): SpendingInsight? {
        val root = Json.extractJson(content) as? JsonValue.Obj ?: return null
        fun list(key: String): List<String> =
            root.arr(key)?.items.orEmpty()
                .mapNotNull { (it as? JsonValue.Str)?.value?.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_ITEMS)
        val insight = SpendingInsight(
            facts = list("facts"),
            estimates = list("estimates"),
            suggestions = list("suggestions"),
            caveats = list("caveats"),
        )
        return if (insight.isEmpty) null else insight
    }
}

/** 把分析请求接到既有 [AiService]（主备轮换、重试、错误归类都在里面）。 */
class SpendingAnalyst(private val aiService: AiService) {

    suspend fun analyze(facts: SpendingFacts): Result<SpendingInsight> {
        if (!facts.hasData) {
            return Result.failure(
                AiException(
                    AiError(AiErrorType.NOT_CONFIGURED, "no local statistics to analyze")
                )
            )
        }
        return aiService
            .reply(
                messages = listOf(ChatMessage.user(SpendingAnalysis.userMessage(facts))),
                systemPrompt = SpendingAnalysis.systemPrompt(),
            )
            .mapCatching { reply ->
                SpendingAnalysis.parse(reply.content)
                    ?: throw AiException(
                        AiError(AiErrorType.BAD_RESPONSE, "analysis is not valid json")
                    )
            }
    }
}