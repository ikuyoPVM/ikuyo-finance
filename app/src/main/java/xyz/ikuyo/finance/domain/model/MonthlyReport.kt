package xyz.ikuyo.finance.domain.model

/**
 * 月度总结报告（阶段四第 7 步）。
 *
 * 所有数字在生成时由本地统计服务算出并随报告落库，展示时不再重算：
 * 账单变化后旧报告保持原样，需要新数字只能「重新生成」，生成新的版本。
 */
data class MonthlyReport(
    val id: Long = 0L,
    /** yyyy-MM。 */
    val yearMonth: String,
    /** 同月版本号，从 1 开始；重新生成会新增版本而不是覆盖。 */
    val version: Int,
    val generatedAt: Long,
    val dataStart: Long,
    val dataEndInclusive: Long,
    val billCount: Int,
    val netExpenseMinor: Long,
    val incomeMinor: Long,
    val overspendMinor: Long,
    val sections: List<SummarySection>,
    /** AI 写的解读，可为空：没有 AI 时报告依然完整。 */
    val narrative: String? = null,
    val superseded: Boolean = false,
) {
    /** 「2026年11月」。 */
    val monthLabel: String
        get() {
            val parts = yearMonth.split('-')
            return if (parts.size == 2) {
                "${parts[0]}年${parts[1].trimStart('0')}月"
            } else {
                yearMonth
            }
        }

    val hasData: Boolean get() = billCount > 0
}

/** 报告中的一段：标题 + 若干条已算好的文字。 */
data class SummarySection(
    val title: String,
    val items: List<String>,
) {
    val hasContent: Boolean get() = items.isNotEmpty()

    companion object {
        const val TITLE_OVERVIEW = "收支总结"
        const val TITLE_CATEGORIES = "分类金额与占比"
        const val TITLE_COMPARE = "与上月对比"
        const val TITLE_BUDGET = "预算执行与超支"
        const val TITLE_TREND = "趋势与异常变化"
        const val TITLE_HABITS = "消费习惯观察"
        const val TITLE_FORECAST = "下月预测"
        const val TITLE_BUDGET_ADVICE = "下月预算建议"
        const val TITLE_SAVING = "节省建议"
    }
}