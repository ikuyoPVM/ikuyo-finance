package xyz.ikuyo.finance.domain.model

/**
 * 预算周期类型（计划书 8.1：周期完全自定义，可跨自然月）。
 *
 * 预算规则（[Budget]）只保存「金额 + 周期类型」，具体区间由
 * [xyz.ikuyo.finance.domain.usecase.BudgetPeriods] 按当前日期推导，
 * 因此每周/每月预算会自动滚动到新周期，不需要用户重复录入。
 */
enum class BudgetPeriodType(val label: String, val dbValue: String) {
    MONTHLY("每月", "MONTHLY"),
    WEEKLY("每周", "WEEKLY"),
    DAILY("每日", "DAILY"),
    CUSTOM("自定义", "CUSTOM");

    companion object {
        fun fromDb(value: String): BudgetPeriodType =
            entries.firstOrNull { it.dbValue == value } ?: MONTHLY
    }
}

/** 一个具体的预算区间：start 含、endInclusive 含（毫秒）。 */
data class BudgetWindow(
    val start: Long,
    val endInclusive: Long,
) {
    /** 用于预警去重：同一区间、同一金额、同一级别只提醒一次。 */
    val key: String get() = "$start:$endInclusive"

    fun contains(millis: Long): Boolean = millis in start..endInclusive
}

/**
 * 预算预警级别（计划书 8.5：50% / 80% / 100% / 超支）。
 *
 * 每个级别在同一周期只提醒一次，进入下一级再提醒；预算金额变更后
 * 重新计算（去重键包含金额），新周期开始后自然重置（去重键包含区间）。
 */
enum class BudgetAlertLevel(val label: String) {
    NORMAL("正常"),
    HALF("已用 50%"),
    EIGHTY("已用 80%"),
    FULL("已达预算上限"),
    OVER("已超支");

    /** 是否需要在首页突出提示。 */
    val needsAttention: Boolean get() = this == EIGHTY || this == FULL || this == OVER

    companion object {
        /** availableMinor 为「预算金额 + 上期结转」，spentMinor 为本周期净支出。 */
        fun of(availableMinor: Long, spentMinor: Long): BudgetAlertLevel {
            if (availableMinor <= 0L) return NORMAL
            return when {
                spentMinor > availableMinor -> OVER
                spentMinor >= availableMinor -> FULL
                spentMinor * 10L >= availableMinor * 8L -> EIGHTY
                spentMinor * 10L >= availableMinor * 5L -> HALF
                else -> NORMAL
            }
        }
    }
}