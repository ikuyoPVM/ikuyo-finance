package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.Category
import java.time.LocalDate
import java.time.YearMonth

/**
 * 异常消费检测（计划书第 6 章）。
 *
 * 判断标准是「相对于你自己的历史」，不是固定金额阈值：
 * 同一笔 80 元，对从不喝咖啡的人可疑，对每天两杯的人很正常。
 * 这里只产出候选与依据，绝不改动账单；提醒可以标记正常或整类忽略。
 */
object AnomalyDetector {

    /** 单笔达到该分类历史单笔中位数的多少倍才算偏大。 */
    const val SINGLE_FACTOR = 3L

    /** 一个分类至少要有这么多历史账单，中位数才有意义。 */
    const val MIN_HISTORY_BILLS = 20

    /** 近 7 天某分类笔数达到历史周均的多少倍算频率突增。 */
    const val FREQUENCY_FACTOR = 2.0

    /** 短时重复消费：同一分类、金额接近、24 小时内至少这么多笔。 */
    const val REPEAT_COUNT = 3

    /** 金额接近的容忍度（万分比）：±5%。 */
    const val REPEAT_TOLERANCE_BP = 500

    /** 分类占比变化超过多少个百分点才提示。 */
    const val STRUCTURE_DELTA_BP = 2_000

    /** 非常规时段：23 点之后到次日 5 点。 */
    const val NIGHT_START_HOUR = 23
    const val NIGHT_END_HOUR = 5

    private const val DAY_MILLIS = 86_400_000L

    enum class Kind(val label: String) {
        SINGLE_LARGE("单笔金额偏高"),
        FREQUENCY("消费频率突增"),
        REPEATED("短时重复消费"),
        STRUCTURE("分类结构变化"),
        BUDGET_PACE("预算消耗快于时间"),
        LATE_NIGHT("非常规时段消费"),
    }

    /**
     * @param id 稳定标识：同一笔账单、同一个分类重复计算时不变，用来记住「已标记正常」。
     */
    data class Candidate(
        val id: String,
        val kind: Kind,
        val title: String,
        val detail: String,
        val evidence: List<String>,
        val severity: Int,
    )

    fun detect(
        bills: List<Bill>,
        categories: List<Category>,
        budgets: List<Statistics.BudgetExecution>,
        today: LocalDate = LocalDate.now(Time.zone),
    ): List<Candidate> {
        val names = categories.associate { it.id to it.name }
        fun nameOf(categoryId: Long?): String =
            categoryId?.let { names[it] } ?: "未分类"

        val monthStart = Time.startOfMonth(YearMonth.from(today))
        val recentStart = Time.startOfDay(today.minusDays(6))
        val history = bills.filter { it.isExpense && it.occurredAt < monthStart }
        val recent = bills.filter { it.isExpense && it.occurredAt >= recentStart }
        val out = mutableListOf<Candidate>()

        // 1) 单笔金额相对该分类历史偏高。
        recent.groupBy { it.categoryId }.forEach { (categoryId, group) ->
            val past = history.filter { it.categoryId == categoryId }.map { it.cnyAmountMinor }
            if (past.size < MIN_HISTORY_BILLS) return@forEach
            val median = medianOf(past)
            if (median <= 0L) return@forEach
            val biggest = group.maxByOrNull { it.cnyAmountMinor } ?: return@forEach
            if (biggest.cnyAmountMinor < median * SINGLE_FACTOR) return@forEach
            val times = biggest.cnyAmountMinor.toDouble() / median
            out += Candidate(
                id = "single:${categoryId ?: -1L}:${biggest.id}",
                kind = Kind.SINGLE_LARGE,
                title = "「${nameOf(categoryId)}」出现较大单笔",
                detail = "${Money.format(biggest.cnyAmountMinor)}（${Time.formatDateTime(biggest.occurredAt)}），" +
                    "约为该分类历史单笔中位数 ${Money.format(median)} 的 ${oneDecimal(times)} 倍",
                evidence = listOf(
                    "该分类历史 ${past.size} 笔的中位数 ${Money.format(median)}",
                    "历史最大单笔 ${Money.format(past.max())}",
                    "近 7 天该分类共 ${group.size} 笔",
                ),
                severity = 2,
            )
        }

        // 2) 近 7 天某分类笔数明显高于自己的历史周均。
        recent.groupBy { it.categoryId }.forEach { (categoryId, group) ->
            val weekly = weeklyCounts(history, categoryId, today)
            if (weekly.isEmpty()) return@forEach
            val average = weekly.average()
            if (average <= 0.0) return@forEach
            if (group.size < 3 || group.size <= average * FREQUENCY_FACTOR) return@forEach
            out += Candidate(
                id = "freq:${categoryId ?: -1L}:${today}",
                kind = Kind.FREQUENCY,
                title = "「${nameOf(categoryId)}」最近花得比平时频繁",
                detail = "近 7 天 ${group.size} 笔，你过去 8 周平均每周 ${oneDecimal(average)} 笔",
                evidence = listOf(
                    "近 7 天合计 ${Money.format(group.sumOf { it.cnyAmountMinor })}",
                    "历史周均 ${oneDecimal(average)} 笔",
                ),
                severity = 1,
            )
        }

        // 3) 24 小时内多笔金额接近的同类消费。
        recent.groupBy { it.categoryId }.forEach { (categoryId, group) ->
            val sorted = group.sortedBy { it.occurredAt }
            for (i in sorted.indices) {
                val anchor = sorted[i]
                val window = sorted.filter {
                    it.occurredAt >= anchor.occurredAt &&
                        it.occurredAt - anchor.occurredAt <= DAY_MILLIS &&
                        closeAmount(anchor.cnyAmountMinor, it.cnyAmountMinor)
                }
                if (window.size < REPEAT_COUNT) continue
                out += Candidate(
                    id = "repeat:${categoryId ?: -1L}:${anchor.id}",
                    kind = Kind.REPEATED,
                    title = "「${nameOf(categoryId)}」短时间内重复消费",
                    detail = "${Time.formatDateTime(window.first().occurredAt)} 起 24 小时内 ${window.size} 笔，" +
                        "合计 ${Money.format(window.sumOf { it.cnyAmountMinor })}",
                    evidence = listOf(
                        "同一分类、金额相差不超过 5%",
                        "涉及账单 ${window.size} 笔",
                    ),
                    severity = 2,
                )
                break
            }
        }

        // 4) 分类结构变化：本月占比明显高于上月。
        run {
            val current = Statistics.categoryStats(bills, Statistics.range(Statistics.RangeType.MONTH, today), categories)
            val previous = Statistics.categoryStats(
                bills,
                Statistics.range(Statistics.RangeType.MONTH, today.minusMonths(1)),
                categories,
            )
            val previousShare = previous.associate { it.categoryId to it.shareBp }
            current.forEach { stat ->
                val delta = stat.shareBp - (previousShare[stat.categoryId] ?: 0)
                if (stat.shareBp < 1_500 || delta < STRUCTURE_DELTA_BP) return@forEach
                out += Candidate(
                    id = "structure:${stat.categoryId ?: -1L}:${YearMonth.from(today)}",
                    kind = Kind.STRUCTURE,
                    title = "「${stat.name}」占比明显上升",
                    detail = "本月占 ${percent(stat.shareBp)}，上月 ${percent(previousShare[stat.categoryId] ?: 0)}",
                    evidence = listOf(
                        "本月 ${Money.format(stat.netExpenseMinor)} · ${stat.billCount} 笔",
                        "占比变化 ${percent(delta)}",
                    ),
                    severity = 1,
                )
            }
        }

        // 5) 预算消耗速度快于时间进度。
        budgets.forEach { execution ->
            if (execution.usedPercent >= 100) return@forEach
            if (execution.timeProgressBp >= 9_000) return@forEach
            if (execution.paceMinor <= 0) return@forEach
            out += Candidate(
                id = "pace:${execution.budgetId}",
                kind = Kind.BUDGET_PACE,
                title = "「${execution.name}」花得比时间快",
                detail = "时间过去 ${percent(execution.timeProgressBp)}，预算已用 ${execution.usedPercent}%，" +
                    "较节奏超前 ${Money.format(execution.paceMinor)}",
                evidence = listOf(
                    "已用 ${Money.format(execution.spentMinor)} / ${Money.format(execution.amountMinor)}",
                    "剩余 ${execution.daysLeft} 天",
                ),
                severity = 1,
            )
        }

        // 6) 非常规时段消费：个人历史几乎没有深夜消费，最近却出现多笔。
        run {
            val nightRecent = recent.filter { isNight(it.occurredAt) }
            if (nightRecent.size >= 2) {
                val historyNight = history.count { isNight(it.occurredAt) }
                val ratio = if (history.isEmpty()) 0.0 else historyNight.toDouble() / history.size
                if (ratio < 0.1) {
                    out += Candidate(
                        id = "night:${today}",
                        kind = Kind.LATE_NIGHT,
                        title = "出现非常规时段的消费",
                        detail = "近 7 天 23 点后有 ${nightRecent.size} 笔，占你历史账单的比重是 ${percent((ratio * 10_000).toInt())}",
                        evidence = listOf(
                            "涉及金额 ${Money.format(nightRecent.sumOf { it.cnyAmountMinor })}",
                            "历史深夜消费 $historyNight 笔",
                        ),
                        severity = 1,
                    )
                }
            }
        }

        return out.sortedWith(compareByDescending<Candidate> { it.severity }.thenBy { it.kind.ordinal })
    }

    private fun weeklyCounts(history: List<Bill>, categoryId: Long?, today: LocalDate): List<Int> {
        val weeks = mutableListOf<Int>()
        for (w in 1..8) {
            val end = Time.startOfDay(today.minusDays(7L * w))
            val start = end - 7 * DAY_MILLIS
            val count = history.count {
                it.categoryId == categoryId && it.occurredAt in start until end
            }
            if (count > 0) weeks += count
        }
        return weeks
    }

    private fun closeAmount(a: Long, b: Long): Boolean {
        if (a <= 0L || b <= 0L) return a == b
        val diff = kotlin.math.abs(a - b)
        val base = if (a > b) a else b
        return diff * 10_000L <= base * REPEAT_TOLERANCE_BP
    }

    private fun isNight(millis: Long): Boolean {
        val hour = Time.toLocalDateTime(millis).hour
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    private fun medianOf(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun percent(bp: Int): String = "${bp / 100}%"

    private fun oneDecimal(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}" else rounded.toString()
    }
}