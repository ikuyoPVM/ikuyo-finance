package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.AiPersona
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 上下文拼装。
 *
 * 隐私最小化是硬约束：默认只发「分类清单 + 必要汇总（今日/本月支出、本月收入、预算剩余）」，
 * 绝不把完整账本塞进请求。需要明细时由用户在对话里点名（如「查今天」），
 * 由 [AiContextBuilder.billDigest] 手工拼接少量匹配账单。
 */
object AiContextBuilder {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 财务汇总：只保留回答「我还有多少钱 / 今天花了多少」所需的最少字段。 */
    data class FinanceSummary(
        val todayExpenseMinor: Long = 0L,
        val monthExpenseMinor: Long = 0L,
        val monthIncomeMinor: Long = 0L,
        val balanceMinor: Long = 0L,
        val budgetName: String? = null,
        val budgetAmountMinor: Long = 0L,
        val budgetSpentMinor: Long = 0L,
        val budgetRemainingMinor: Long = 0L,
    ) {
        val hasBudget: Boolean get() = budgetName != null && budgetAmountMinor > 0
    }

    fun systemPrompt(
        persona: AiPersona,
        categories: List<Category>,
        tags: List<Tag>,
        summary: FinanceSummary,
        today: LocalDate = LocalDate.now(Time.zone),
    ): String {
        val categoryList = categories.joinToString("、") { "${it.name}(id=${it.id})" }
        val tagList = if (tags.isEmpty()) "（暂无标签）" else tags.joinToString("、") { it.name }
        return buildString {
            appendLine("你是「${persona.name}」，一个只负责记账的助手。")
            if (persona.role.isNotBlank()) appendLine("角色设定：${persona.role}")
            appendLine(persona.styleHint())
            appendLine()
            appendLine("【必须遵守】")
            appendLine("1. 你不能直接改余额、预算或历史账单；只能用候选账单（bills）表达要记的账，由用户确认后才入账。")
            appendLine("2. 金额一律用「元」为单位的数字，不带货币符号或单位；不要自己换算汇率。")
            appendLine("3. 时间不确定就用今天；支付方式不确定就用 OTHER，并在 reasons 里说明。")
            appendLine("4. 分类只能从下面的清单里选，选不到就把 category 留空并在 reasons 里写「分类不确定」。")
            appendLine("5. 一句话里有多笔消费就拆成多条 bills，每笔单独一条。")
            appendLine("6. 用户只是聊天、问问题、问建议时，bills 返回空数组。")
            appendLine("7. 退款（退回钱）类型是 refund，不是 income。")
            appendLine("8. 日期只写到「日」，不要带时分秒。")
            appendLine()
            appendLine("【数据查询】你可以调用工具查询 App 内的真实数据，不要凭记忆猜数字：")
            appendLine("- 收支摘要 get_account_summary；账单明细 query_transactions；关键词搜索 search_transactions")
            appendLine("- 分类统计 get_category_statistics；标签统计 get_tag_statistics；趋势 get_spending_trend")
            appendLine("- 预算 get_budget_status；固定支出 get_recurring_expenses；分期 get_installment_summary")
            appendLine("- 债务 get_debt_summary；储蓄目标 get_savings_goals")
            appendLine("回答财务问题时：先用工具拿到数据，再依据工具结果作答；金额必须与工具结果一致；")
            appendLine("必须在回答里说明统计的时间范围；数据不足就直说，不要编造；不要一次查询与问题无关的全部数据。")
            appendLine()
            appendLine("【输出格式】只输出一个 JSON 对象，不要 Markdown 围栏：")
            appendLine("""{"reply":"给用户看的中文回答","bills":[{"type":"expense|income|refund","amount":28.5,"category":"餐饮","date":"${today.format(DATE)}","payment":"WECHAT|ALIPAY|BANK_CARD|CASH|OTHER","note":"午饭","tags":["工作"],"confidence":0.92,"reasons":[]}]}""")
            appendLine()
            appendLine("【可用分类】$categoryList")
            appendLine("【可用标签】$tagList")
            appendLine("【今天】${today.format(DATE)}")
            appendLine("【账本汇总（人民币，元）】")
            appendLine("- 总余额 ${yuan(summary.balanceMinor)}")
            appendLine("- 今日支出 ${yuan(summary.todayExpenseMinor)}")
            appendLine("- 本月支出 ${yuan(summary.monthExpenseMinor)}，本月收入 ${yuan(summary.monthIncomeMinor)}")
            if (summary.hasBudget) {
                appendLine(
                    "- 当前预算「${summary.budgetName}」：额度 ${yuan(summary.budgetAmountMinor)}，" +
                        "已用 ${yuan(summary.budgetSpentMinor)}，剩余 ${yuan(summary.budgetRemainingMinor)}"
                )
            }
        }.trim()
    }

    /** 用户点名要明细时，只拼匹配到的那几条，并明确告知条数上限。 */
    fun billDigest(lines: List<String>, limit: Int = 10): String {
        if (lines.isEmpty()) return "（没有匹配的账单）"
        return buildString {
            appendLine("【匹配账单（最多 $limit 条，已截断）】")
            lines.take(limit).forEach { appendLine("- $it") }
        }.trim()
    }

    private fun yuan(minor: Long): String =
        Money.format(minor, Money.BASE_CURRENCY, showSymbol = false)
}