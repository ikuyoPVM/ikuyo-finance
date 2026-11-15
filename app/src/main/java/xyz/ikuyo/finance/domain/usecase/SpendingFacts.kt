package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time

/**
 * 消费分析的「本地事实」。
 *
 * 设计原则（计划书第 4 章）：
 * - 所有数字先由 [Statistics] 算好，AI 只负责解释。模型看到的是这里序列化出来的事实，
 *   不允许它自己算、自己编。
 * - 区分三类内容：事实（[facts] 来自统计）、估算（趋势推断）、建议（可执行动作）。
 * - 隐私最小化：只发聚合结果（分类/标签/预算/日均），不发单笔账单、不发备注。
 * - 数据不足时明确标注（[sufficiency]），让上层降低结论强度。
 */
data class SpendingFacts(
    val rangeLabel: String,
    val startDate: String,
    val endDate: String,
    val dayCount: Int,
    val expenseMinor: Long,
    val refundMinor: Long,
    val netExpenseMinor: Long,
    val incomeMinor: Long,
    val netMinor: Long,
    val billCount: Int,
    val previousLabel: String?,
    val previousNetExpenseMinor: Long,
    val expenseChangePercent: Int?,
    val incomeChangePercent: Int?,
    val categories: List<CategoryFact>,
    val tags: List<TagFact>,
    val budgets: List<BudgetFact>,
    val averageDailyMinor: Long,
    val peakDayLabel: String?,
    val peakDayNetExpenseMinor: Long,
    val notableCategories: List<CategoryFact>,
    val sufficiency: Sufficiency,
) {

    data class CategoryFact(
        val name: String,
        val netExpenseMinor: Long,
        val shareBp: Int,
        val billCount: Int,
        val changePercent: Int?,
    )

    data class TagFact(
        val name: String,
        val netExpenseMinor: Long,
        val shareBp: Int,
        val billCount: Int,
    )

    data class BudgetFact(
        val name: String,
        val amountMinor: Long,
        val spentMinor: Long,
        val usedPercent: Int,
        val timeProgressBp: Int,
        val paceMinor: Long,
        val daysLeft: Int,
    )

    /** 数据充分性：决定分析能不能给结论、要不要附「数据不足」说明。 */
    enum class Sufficiency(val label: String) {
        NONE("没有数据"),
        THIN("数据较少"),
        OK("数据充分"),
        ;

        val isUsable: Boolean get() = this != NONE
    }

    val hasData: Boolean get() = sufficiency.isUsable

    /** 供 UI 直接展示的本地事实行（不依赖 AI 也能看懂）。 */
    fun localLines(): List<String> = buildList {
        add("本期净支出 ${yuan(netExpenseMinor)}，共 $billCount 笔")
        categories.firstOrNull()?.let { top ->
            add("最大分类「${top.name}」${yuan(top.netExpenseMinor)}，占 ${percent(top.shareBp)}")
        }
        expenseChangePercent?.let { change ->
            add("与${previousLabel ?: "上期"}相比，支出${if (change >= 0) "增加" else "减少"} ${kotlin.math.abs(change)}%")
        } ?: add("${previousLabel ?: "上期"}没有可比数据，无法计算变化")
        add("日均净支出 ${yuan(averageDailyMinor)}（按 $dayCount 天计）")
        budgets.firstOrNull()?.let { budget ->
            add("预算「${budget.name}」已用 ${budget.usedPercent}%，时间进度 ${budget.timeProgressBp / 100}%")
        }
    }

    /** 序列化成提示词里的事实块；金额统一为「元」。 */
    fun toPromptJson(): String {
        val root = Json.obj(
            "范围" to Json.str("$startDate 至 $endDate（$rangeLabel，共 $dayCount 天）"),
            "收支" to Json.obj(
                "支出元" to Json.num(yuanNumber(expenseMinor)),
                "退款元" to Json.num(yuanNumber(refundMinor)),
                "净支出元" to Json.num(yuanNumber(netExpenseMinor)),
                "收入元" to Json.num(yuanNumber(incomeMinor)),
                "净收支元" to Json.num(yuanNumber(netMinor)),
                "笔数" to Json.num(billCount.toLong()),
            ),
            "上期" to Json.obj(
                "名称" to Json.str(previousLabel ?: "无"),
                "净支出元" to Json.num(yuanNumber(previousNetExpenseMinor)),
                "支出变化百分比" to expenseChangePercent?.let { Json.num(it.toLong()) },
                "收入变化百分比" to incomeChangePercent?.let { Json.num(it.toLong()) },
            ),
            "分类" to Json.arr(
                categories.map { item ->
                    Json.obj(
                        "名称" to Json.str(item.name),
                        "净支出元" to Json.num(yuanNumber(item.netExpenseMinor)),
                        "占比百分比" to Json.num(item.shareBp / 100L),
                        "笔数" to Json.num(item.billCount.toLong()),
                        "较上期百分比" to item.changePercent?.let { Json.num(it.toLong()) },
                    )
                }
            ),
            "标签" to Json.arr(
                tags.map { item ->
                    Json.obj(
                        "名称" to Json.str(item.name),
                        "净支出元" to Json.num(yuanNumber(item.netExpenseMinor)),
                        "占比百分比" to Json.num(item.shareBp / 100L),
                        "笔数" to Json.num(item.billCount.toLong()),
                    )
                }
            ),
            "预算" to Json.arr(
                budgets.map { item ->
                    Json.obj(
                        "名称" to Json.str(item.name),
                        "额度元" to Json.num(yuanNumber(item.amountMinor)),
                        "已用元" to Json.num(yuanNumber(item.spentMinor)),
                        "已用百分比" to Json.num(item.usedPercent.toLong()),
                        "时间进度百分比" to Json.num((item.timeProgressBp / 100).toLong()),
                        "节奏偏差元" to Json.num(yuanNumber(item.paceMinor)),
                        "剩余天数" to Json.num(item.daysLeft.toLong()),
                    )
                }
            ),
            "趋势" to Json.obj(
                "日均净支出元" to Json.num(yuanNumber(averageDailyMinor)),
                "峰值日" to Json.str(peakDayLabel ?: "无"),
                "峰值日净支出元" to Json.num(yuanNumber(peakDayNetExpenseMinor)),
            ),
            "明显变化分类" to Json.arr(
                notableCategories.map { item ->
                    Json.obj(
                        "名称" to Json.str(item.name),
                        "净支出元" to Json.num(yuanNumber(item.netExpenseMinor)),
                        "较上期百分比" to item.changePercent?.let { Json.num(it.toLong()) },
                    )
                }
            ),
            "数据充分性" to Json.str(sufficiency.label),
        )
        return Json.dumps(root)
    }

    companion object {

        /** 判断「明显变化」的门槛：相对变化 ≥ 20% 且绝对变化 ≥ 5 元。 */
        const val NOTABLE_PERCENT = 20
        const val NOTABLE_MINOR = 500L

        /** 少于 5 笔或净支出为 0 视为数据较少。 */
        const val THIN_BILLS = 5

        fun from(snapshot: Statistics.Snapshot): SpendingFacts {
            val range = snapshot.range
            val totals = snapshot.totals
            val categories = snapshot.categories.map {
                CategoryFact(
                    name = it.name,
                    netExpenseMinor = it.netExpenseMinor,
                    shareBp = it.shareBp,
                    billCount = it.billCount,
                    changePercent = it.changePercent,
                )
            }
            val tags = snapshot.tags.map {
                TagFact(
                    name = it.name,
                    netExpenseMinor = it.netExpenseMinor,
                    shareBp = it.shareBp,
                    billCount = it.billCount,
                )
            }
            val budgets = snapshot.budgets.map {
                BudgetFact(
                    name = it.name,
                    amountMinor = it.amountMinor,
                    spentMinor = it.spentMinor,
                    usedPercent = it.usedPercent,
                    timeProgressBp = it.timeProgressBp,
                    paceMinor = it.paceMinor,
                    daysLeft = it.daysLeft,
                )
            }
            val dayCount = range.dayCount
            val peak = snapshot.daily.filter { it.netExpenseMinor > 0 }.maxByOrNull { it.netExpenseMinor }
            val sufficiency = when {
                totals.billCount <= 0 -> Sufficiency.NONE
                totals.netExpenseMinor <= 0L -> Sufficiency.THIN
                totals.billCount < THIN_BILLS -> Sufficiency.THIN
                else -> Sufficiency.OK
            }
            return SpendingFacts(
                rangeLabel = range.label,
                startDate = Time.formatFullDate(range.startMillis),
                endDate = Time.formatFullDate(range.endMillisExclusive - 1),
                dayCount = dayCount,
                expenseMinor = totals.expenseMinor,
                refundMinor = totals.refundMinor,
                netExpenseMinor = totals.netExpenseMinor,
                incomeMinor = totals.incomeMinor,
                netMinor = totals.netMinor,
                billCount = totals.billCount,
                previousLabel = snapshot.range.previousLabel.takeIf { snapshot.previousTotals.billCount > 0 },
                previousNetExpenseMinor = snapshot.previousTotals.netExpenseMinor,
                expenseChangePercent = snapshot.expenseChangePercent,
                incomeChangePercent = snapshot.incomeChangePercent,
                categories = categories,
                tags = tags,
                budgets = budgets,
                averageDailyMinor = if (dayCount <= 0) 0L else totals.netExpenseMinor / dayCount,
                peakDayLabel = peak?.let { Time.formatFullDate(it.millis) },
                peakDayNetExpenseMinor = peak?.netExpenseMinor ?: 0L,
                notableCategories = categories.filter {
                    val change = it.changePercent ?: return@filter false
                    kotlin.math.abs(change) >= NOTABLE_PERCENT &&
                        kotlin.math.abs(it.netExpenseMinor) >= NOTABLE_MINOR
                },
                sufficiency = sufficiency,
            )
        }

        private fun yuanNumber(minor: Long): Double = minor / 100.0

        private fun yuan(minor: Long): String = Money.format(minor, showSymbol = false)

        private fun percent(bp: Int): String = "${bp / 100}%"
    }
}