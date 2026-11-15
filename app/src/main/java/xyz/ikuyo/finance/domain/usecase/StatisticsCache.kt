package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag
import java.time.LocalDate

/**
 * 统计结果的单条缓存（计划书第 9 章）。
 *
 * 统计页每次重组、每次切换范围都会重算一遍快照；账单多的时候这是最贵的本地计算。
 * 这里只记住「上一次的输入 + 结果」：输入完全一样就直接复用，任何一个账单、分类、
 * 标签、预算或区间发生变化都会让 key 变了，缓存自动失效 —— 不需要调用方手动清理。
 *
 * 只缓存一份，内存占用可忽略；也不做跨线程共享的可变状态之外的假设（调用方需在同一线程使用）。
 */
object StatisticsCache {

    private var lastKey: String? = null
    private var lastResult: Statistics.Snapshot? = null
    private var hits: Int = 0
    private var misses: Int = 0

    /** 命中次数与未命中次数，供测试与排查用。 */
    val hitCount: Int get() = hits
    val missCount: Int get() = misses

    fun snapshot(
        bills: List<Bill>,
        range: Statistics.Range,
        categories: List<Category>,
        tags: List<Tag>,
        tagBillIds: Map<Long, Set<Long>>,
        budgets: List<Budget>,
        today: LocalDate,
        topCategoryLimit: Int = 8,
    ): Statistics.Snapshot {
        val key = keyOf(bills, range, categories, tags, tagBillIds, budgets, today, topCategoryLimit)
        val cached = lastResult
        if (cached != null && key == lastKey) {
            hits++
            return cached
        }
        val computed = Statistics.snapshot(
            bills = bills,
            range = range,
            categories = categories,
            tags = tags,
            tagBillIds = tagBillIds,
            budgets = budgets,
            today = today,
            topCategoryLimit = topCategoryLimit,
        )
        lastKey = key
        lastResult = computed
        misses++
        return computed
    }

    fun clear() {
        lastKey = null
        lastResult = null
        hits = 0
        misses = 0
    }

    /**
     * 输入指纹。用各列表的 hashCode（内容相同即相同）而不是对象引用，
     * 这样 Room 每次新查一遍数据也能命中缓存。
     */
    private fun keyOf(
        bills: List<Bill>,
        range: Statistics.Range,
        categories: List<Category>,
        tags: List<Tag>,
        tagBillIds: Map<Long, Set<Long>>,
        budgets: List<Budget>,
        today: LocalDate,
        topCategoryLimit: Int,
    ): String = buildString {
        append(range.type).append(':')
        append(range.startMillis).append(':')
        append(range.endMillisExclusive).append(':')
        append(range.previousStartMillis).append(':')
        append(bills.size).append(':').append(bills.hashCode()).append(':')
        append(categories.size).append(':').append(categories.hashCode()).append(':')
        append(tags.size).append(':').append(tags.hashCode()).append(':')
        append(tagBillIds.hashCode()).append(':')
        append(budgets.size).append(':').append(budgets.hashCode()).append(':')
        append(today).append(':').append(topCategoryLimit)
    }
}