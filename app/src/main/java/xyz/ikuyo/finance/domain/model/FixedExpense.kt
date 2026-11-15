package xyz.ikuyo.finance.domain.model

import xyz.ikuyo.finance.core.Time

/**
 * 固定支出周期。
 *
 * 计划本身不是账单：只有用户确认付款后才生成真实账单（见 FixedExpenseRepository.confirmPayment）。
 */
enum class FixedPeriodType(val label: String, val dbValue: String) {
    WEEKLY("每周", "WEEKLY"),
    BIWEEKLY("每两周", "BIWEEKLY"),
    MONTHLY("每月", "MONTHLY"),
    BIMONTHLY("每两个月", "BIMONTHLY"),
    QUARTERLY("每季度", "QUARTERLY"),
    HALF_YEARLY("每半年", "HALF_YEARLY"),
    YEARLY("每年", "YEARLY"),
    CUSTOM("自定义天数", "CUSTOM"),
    ;

    /** 是否按月推进（按月推进的周期需要「月内锚点日」来避免 29/30/31 漂移）。 */
    val isMonthBased: Boolean
        get() = this == MONTHLY || this == BIMONTHLY || this == QUARTERLY ||
            this == HALF_YEARLY || this == YEARLY

    /** 按月推进时的月数；非按月周期为 1。 */
    val monthsPerPeriod: Int
        get() = when (this) {
            MONTHLY -> 1
            BIMONTHLY -> 2
            QUARTERLY -> 3
            HALF_YEARLY -> 6
            YEARLY -> 12
            else -> 1
        }

    companion object {
        fun fromDb(value: String): FixedPeriodType =
            entries.firstOrNull { it.dbValue == value } ?: MONTHLY
    }
}

/**
 * 固定支出计划（v5 起）。
 *
 * - [anchorDay] 是「月内锚点日」，一旦确定就不再随到期日变化：1 月 31 日创建的月度计划，
 *   2 月到期日会回退到 2 月 28 日，3 月仍回到 31 日，不会一路漂移成 28 日。
 * - [isVariable] 为 true 表示金额浮动（水电费一类），确认付款时允许改写金额。
 * - [enabled] 为 false 表示暂停：不再提醒、不再出现在待处理列表，历史付款记录保留。
 */
data class FixedExpense(
    val id: Long = 0L,
    val name: String,
    val amountMinor: Long,
    val categoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.WECHAT,
    val periodType: FixedPeriodType = FixedPeriodType.MONTHLY,
    /** 下一次到期日（毫秒，当天中午，避免时区/夏令时把日期挪到前一天）。 */
    val dueAt: Long = Time.nowMillis(),
    /** 月内锚点日 1..31。 */
    val anchorDay: Int = Time.toLocalDate(dueAt).dayOfMonth,
    /** 仅 [FixedPeriodType.CUSTOM] 使用：间隔天数。 */
    val customDays: Int = 30,
    val isVariable: Boolean = false,
    val enabled: Boolean = true,
    val note: String = "",
    val createdAt: Long = Time.nowMillis(),
    val updatedAt: Long = 0L,
    /** 连表带出的分类名（实体本身不存）。 */
    val categoryName: String? = null,
    val categoryEmoji: String = "🏷️",
) {

    /** 供 UI 显示的一行摘要，例如「每月 15 日 · 自动扣款」。 */
    fun scheduleLabel(): String = scheduleLabel(periodType, anchorDay, customDays)

    companion object {
        fun scheduleLabel(periodType: FixedPeriodType, anchorDay: Int, customDays: Int): String =
            when (periodType) {
                FixedPeriodType.WEEKLY -> "每周"
                FixedPeriodType.BIWEEKLY -> "每两周"
                FixedPeriodType.CUSTOM -> "每 $customDays 天"
                else -> "${periodType.label} $anchorDay 日"
            }
    }
}

/**
 * 固定支出的一次付款记录（v5 起）。
 *
 * [billId] 指向确认付款后生成的真实账单；计划被删除时付款记录仍然保留，
 * 因为「这笔钱确实付过」这一事实不能因为删计划而消失。
 */
data class FixedExpensePayment(
    val id: Long = 0L,
    val fixedExpenseId: Long,
    val billId: Long? = null,
    val amountMinor: Long,
    val dueAt: Long,
    val paidAt: Long,
    val note: String = "",
)