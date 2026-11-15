package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.SummarySection
import xyz.ikuyo.finance.domain.model.Tag
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月度总结生成器（阶段四第 7 步）。
 *
 * 硬约束（计划书第 7 章）：
 * 1. 九段内容里的**每一个数字**都必须由 [Statistics] 算出，模型只负责解释，不许编造。
 * 2. 数据不足时明确说明「数据不足」，不硬凑结论。
 * 3. 输出只包含分类与周期的汇总，不含账单备注等敏感明细（隐私最小化）。
 */
object MonthlySummaryBuilder {

    /** 生成结果：可直接落库的九段正文 + 头部数字。 */
    data class Draft(
        val yearMonth: String,
        val dataStart: Long,
        val dataEndInclusive: Long,
        val billCount: Int,
        val netExpenseMinor: Long,
        val incomeMinor: Long,
        val overspendMinor: Long,
        val sections: List<SummarySection>,
    ) {
        val hasData: Boolean get() = billCount > 0
    }

    /** 数据太少时不做预测/建议的阈值：账单笔数。 */
    private const val MIN_BILLS_FOR_ADVICE = 3

    fun build(
        yearMonth: YearMonth,
        bills: List<Bill>,
        categories: List<Category>,
        tags: List<Tag> = emptyList(),
        tagBillIds: Map<Long, Set<Long>> = emptyMap(),
        budgets: List<Budget> = emptyList(),
        today: LocalDate = LocalDate.now(Time.zone),
    ): Draft {
        val anchor = yearMonth.atDay(1)
        val range = Statistics.range(Statistics.RangeType.MONTH, anchor)
        val snapshot = Statistics.snapshot(
            bills = bills,
            range = range,
            categories = categories,
            tags = tags,
            tagBillIds = tagBillIds,
            budgets = budgets,
            today = today,
        )
        val totals = snapshot.totals

        val sections = listOf(
            overview(snapshot),
            categories(snapshot),
            compare(snapshot),
            budgetExecution(snapshot),
            trend(snapshot, bills, categories, today),
            habits(snapshot),
            forecast(snapshot, yearMonth),
            budgetAdvice(snapshot, bills, categories),
            saving(snapshot),
        )

        return Draft(
            yearMonth = "%04d-%02d".format(yearMonth.year, yearMonth.monthValue),
            dataStart = range.startMillis,
            dataEndInclusive = range.endMillisExclusive - 1,
            billCount = totals.billCount,
            netExpenseMinor = totals.netExpenseMinor,
            incomeMinor = totals.incomeMinor,
            overspendMinor = snapshot.budgets.sumOf { it.overMinor },
            sections = sections,
        )
    }

    // ── 1. 收支总结 ─────────────────────────────────────────────
    private fun overview(snapshot: Statistics.Snapshot): SummarySection {
        val totals = snapshot.totals
        if (!snapshot.hasData) {
            return SummarySection(SummarySection.TITLE_OVERVIEW, listOf("本月没有符合条件的账单，数据不足以总结。"))
        }
        val items = mutableListOf(
            "支出 ${Money.format(totals.expenseMinor)}，退款 ${Money.format(totals.refundMinor)}，净支出 ${Money.format(totals.netExpenseMinor)}。",
            "收入 ${Money.format(totals.incomeMinor)}，净收支 ${signed(totals.netMinor)}。",
            "共 ${totals.billCount} 笔账单，净支出日均 ${Money.format(totals.netExpenseMinor / snapshot.range.dayCount.coerceAtLeast(1))}。",
        )
        if (totals.billCount < MIN_BILLS_FOR_ADVICE) {
            items += "本月账单只有 ${totals.billCount} 笔，以下结论仅供参考。"
        }
        return SummarySection(SummarySection.TITLE_OVERVIEW, items)
    }

    // ── 2. 分类金额与占比 ──────────────────────────────────────
    private fun categories(snapshot: Statistics.Snapshot): SummarySection {
        if (snapshot.categories.isEmpty()) {
            return SummarySection(SummarySection.TITLE_CATEGORIES, listOf("本月没有可归类的支出。"))
        }
        val items = snapshot.categories.take(5).map { stat ->
            buildString {
                append("${stat.emoji} ${stat.name} ${Money.format(stat.netExpenseMinor)}")
                append("，占 ${stat.shareBp / 100}%")
                append("，${stat.billCount} 笔")
                changeSuffix(stat.changePercent)?.let { append("，$it") }
            }
        }.toMutableList()
        val rest = snapshot.categories.drop(5)
        if (rest.isNotEmpty()) {
            val amount = rest.sumOf { it.netExpenseMinor }
            items += "其余 ${rest.size} 个分类合计 ${Money.format(amount)}，占 ${rest.sumOf { it.shareBp } / 100}%。"
        }
        return SummarySection(SummarySection.TITLE_CATEGORIES, items)
    }

    // ── 3. 与上月对比 ──────────────────────────────────────────
    private fun compare(snapshot: Statistics.Snapshot): SummarySection {
        val previous = snapshot.previousTotals
        if (previous.billCount == 0) {
            return SummarySection(
                SummarySection.TITLE_COMPARE,
                listOf("上月没有账单，无法对比。"),
            )
        }
        val items = mutableListOf<String>()
        val expenseChange = Statistics.changePercent(snapshot.totals.netExpenseMinor, previous.netExpenseMinor)
        items += if (expenseChange == null) {
            "上月净支出为 0，无法计算支出变化比例。"
        } else {
            "净支出 ${Money.format(snapshot.totals.netExpenseMinor)}，较上月 ${Money.format(previous.netExpenseMinor)} ${signedPercent(expenseChange)}。"
        }
        val incomeChange = Statistics.changePercent(snapshot.totals.incomeMinor, previous.incomeMinor)
        if (incomeChange != null && previous.incomeMinor > 0L) {
            items += "收入 ${Money.format(snapshot.totals.incomeMinor)}，较上月 ${signedPercent(incomeChange)}。"
        }
        val grew = snapshot.categories.filter { (it.changePercent ?: 0) >= 20 }.sortedByDescending { it.changePercent }
        val shrank = snapshot.categories.filter { (it.changePercent ?: 0) <= -20 }.sortedBy { it.changePercent }
        if (grew.isNotEmpty()) {
            items += "明显增加：" + grew.take(3).joinToString("、") {
                "${it.name} ${signedPercent(it.changePercent ?: 0)}"
            } + "。"
        }
        if (shrank.isNotEmpty()) {
            items += "明显减少：" + shrank.take(3).joinToString("、") {
                "${it.name} ${signedPercent(it.changePercent ?: 0)}"
            } + "。"
        }
        if (grew.isEmpty() && shrank.isEmpty()) items += "各分类与上月相比变化不大。"
        return SummarySection(SummarySection.TITLE_COMPARE, items)
    }

    // ── 4. 预算执行与超支 ──────────────────────────────────────
    private fun budgetExecution(snapshot: Statistics.Snapshot): SummarySection {
        if (snapshot.budgets.isEmpty()) {
            return SummarySection(SummarySection.TITLE_BUDGET, listOf("本月没有启用中的预算。"))
        }
        val items = snapshot.budgets.map { execution ->
            buildString {
                append("${execution.name}：已用 ${Money.format(execution.spentMinor)} / ${Money.format(execution.amountMinor)}")
                append("（${execution.usedPercent}%）")
                if (execution.overMinor > 0L) {
                    append("，超支 ${Money.format(execution.overMinor)}")
                } else {
                    append("，剩余 ${Money.format((execution.availableMinor - execution.spentMinor).coerceAtLeast(0L))}")
                }
            }
        }
        val over = snapshot.budgets.filter { it.overMinor > 0L }
        val head = if (over.isEmpty()) {
            "全部预算都在额度内。"
        } else {
            "有 ${over.size} 个预算超支，合计 ${Money.format(over.sumOf { it.overMinor })}。"
        }
        return SummarySection(SummarySection.TITLE_BUDGET, listOf(head) + items)
    }

    // ── 5. 趋势与异常变化 ──────────────────────────────────────
    private fun trend(
        snapshot: Statistics.Snapshot,
        bills: List<Bill>,
        categories: List<Category>,
        today: LocalDate,
    ): SummarySection {
        val items = mutableListOf<String>()
        val daily = snapshot.daily
        val peak = daily.maxByOrNull { it.netExpenseMinor }
        val activeDays = daily.count { it.netExpenseMinor > 0L }
        if (peak != null && peak.netExpenseMinor > 0L) {
            items += "支出最高的 ${Time.formatMonthDay(peak.millis)} 花了 ${Money.format(peak.netExpenseMinor)}。"
        }
        items += "本月有 $activeDays 天产生支出，其余 ${daily.size - activeDays} 天没有支出。"
        val biggest = Statistics.filterInRange(bills, snapshot.range)
            .filter { it.isExpense }
            .maxByOrNull { it.cnyAmountMinor }
        if (biggest != null) {
            items += "最大单笔 ${Money.format(biggest.cnyAmountMinor)}" +
                (biggest.categoryName?.let { "（$it）" } ?: "") + "。"
        }
        val candidates = AnomalyDetector.detect(bills = bills, categories = categories, budgets = snapshot.budgets, today = today)
        if (candidates.isEmpty()) {
            items += "没有检测到明显的异常变化。"
        } else {
            items += "检测到 ${candidates.size} 项值得留意：" +
                candidates.take(3).joinToString("；") { "${it.kind.label}（${it.title}）" } + "。"
        }
        // 有异常时把候选的触发原因带上，便于判断是否正常消费
        return SummarySection(SummarySection.TITLE_TREND, items)
    }

    // ── 6. 消费习惯观察 ────────────────────────────────────────
    private fun habits(snapshot: Statistics.Snapshot): SummarySection {
        if (!snapshot.hasData) {
            return SummarySection(SummarySection.TITLE_HABITS, listOf("本月数据不足，暂无习惯观察。"))
        }
        val items = mutableListOf<String>()
        val top = snapshot.categories.maxByOrNull { it.netExpenseMinor }
        if (top != null) {
            items += "花费最多的分类是 ${top.name}，占 ${top.shareBp / 100}%。"
        }
        val totals = snapshot.totals
        if (totals.billCount > 0) {
            items += "平均每笔 ${Money.format(totals.netExpenseMinor / totals.billCount)}，共 ${totals.billCount} 笔。"
        }
        val tagTop = snapshot.tags.maxByOrNull { it.netExpenseMinor }
        if (tagTop != null) {
            items += "标签里「#${tagTop.name}」最多，${Money.format(tagTop.netExpenseMinor)} / ${tagTop.billCount} 笔。"
        }
        return SummarySection(SummarySection.TITLE_HABITS, items)
    }

    // ── 7. 下月预测 ────────────────────────────────────────────
    private fun forecast(
        snapshot: Statistics.Snapshot,
        yearMonth: YearMonth,
    ): SummarySection {
        if (!snapshot.hasData) {
            return SummarySection(SummarySection.TITLE_FORECAST, listOf("本月没有支出，无法预测下月。"))
        }
        val dailyValues = snapshot.daily.map { it.netExpenseMinor }.filter { it > 0L }
        val days = snapshot.daily.size.coerceAtLeast(1)
        val typical = if (dailyValues.isEmpty()) 0L else snapshot.totals.netExpenseMinor / days
        val nextMonth = yearMonth.plusMonths(1)
        val nextDays = nextMonth.lengthOfMonth()
        val projected = typical * nextDays
        val confidence = when {
            snapshot.totals.billCount >= 15 -> "中等"
            snapshot.totals.billCount >= MIN_BILLS_FOR_ADVICE -> "偏低"
            else -> "低"
        }
        val items = mutableListOf(
            "按本月日均 ${Money.format(typical)} 推算，下月（${nextMonth.monthValue} 月，$nextDays 天）预计支出 ${Money.format(projected)}。",
            "这是基于本月账单的推算，不是承诺值；可信度：$confidence（依据：本月 ${snapshot.totals.billCount} 笔、$days 天）。",
        )
        if (typical == 0L) items += "本月日均支出为 0，预测仅供参考。"
        return SummarySection(SummarySection.TITLE_FORECAST, items)
    }

    // ── 8. 下月预算建议 ────────────────────────────────────────
    private fun budgetAdvice(
        snapshot: Statistics.Snapshot,
        bills: List<Bill>,
        categories: List<Category>,
    ): SummarySection {
        if (snapshot.categories.isEmpty()) {
            return SummarySection(SummarySection.TITLE_BUDGET_ADVICE, listOf("没有可参考的分类支出，暂不给出预算建议。"))
        }
        val previousRange = Statistics.previousRange(snapshot.range)
        val previous = Statistics.categoryStats(bills, previousRange, categories)
            .associate { it.categoryId to it.netExpenseMinor }
        val items = snapshot.categories.take(3).map { stat ->
            val last = previous[stat.categoryId] ?: 0L
            val average = if (last > 0L) (stat.netExpenseMinor + last) / 2 else stat.netExpenseMinor
            val suggested = roundToTenYuan(average)
            "「${stat.name}」近两月平均 ${Money.format(average)}，建议下月预算 ${Money.format(suggested)}。"
        }.toMutableList()
        items += "建议值按月内已发生的真实支出推算，可按需要自行上下调整。"
        return SummarySection(SummarySection.TITLE_BUDGET_ADVICE, items)
    }

    // ── 9. 节省建议 ────────────────────────────────────────────
    private fun saving(snapshot: Statistics.Snapshot): SummarySection {
        val items = mutableListOf<String>()
        val grew = snapshot.categories
            .filter { (it.changePercent ?: 0) > 0 && it.netExpenseMinor > 0 }
            .maxByOrNull { it.changePercent ?: 0 }
        if (grew != null) {
            items += "「${grew.name}」较上月增加 ${signedPercent(grew.changePercent ?: 0)}，是本月增幅最大的分类，" +
                "可以先从这一项压缩。"
        }
        val small = snapshot.categories.filter { it.billCount >= 5 && it.netExpenseMinor / it.billCount <= 2_000L }
        if (small.isNotEmpty()) {
            items += "小额多笔：" + small.joinToString("、") {
                "${it.name}（${it.billCount} 笔，笔均 ${Money.format(it.netExpenseMinor / it.billCount)}）"
            } + "，少买几次就能省下来。"
        }
        if (items.isEmpty()) {
            items += "本月没有发现明显可压缩的支出。"
        }
        items += "以上是依据账单结构的建议，是否执行由你决定；应用不会自动改预算或账单。"
        return SummarySection(SummarySection.TITLE_SAVING, items)
    }

    // ── 工具 ──────────────────────────────────────────────────
    private fun signed(minor: Long): String =
        if (minor >= 0) "+${Money.format(minor)}" else "-${Money.format(-minor)}"

    private fun signedPercent(percent: Int): String =
        if (percent >= 0) "增加 $percent%" else "减少 ${-percent}%"

    private fun changeSuffix(percent: Int?): String? = when {
        percent == null -> null
        percent >= 0 -> "较上月 +$percent%"
        else -> "较上月 $percent%"
    }

    /** 预算建议取整到 10 元，便于用户记住与调整。 */
    private fun roundToTenYuan(minor: Long): Long {
        val unit = 1_000L
        return ((minor + unit / 2) / unit) * unit
    }

    /** 供 UI 判断颜色等用途。 */
    fun typeOf(section: SummarySection): BillType? = when (section.title) {
        SummarySection.TITLE_BUDGET -> BillType.EXPENSE
        else -> null
    }
}