package xyz.ikuyo.finance.domain.model

import xyz.ikuyo.finance.core.Money

/**
 * 储蓄目标状态。
 *
 * 「未达成」不是独立状态：只要截止日过了而余额没到目标，就由 [SavingsProgress.isOverdue] 派生，
 * 避免状态与日期两条真值来源互相打架。
 */
enum class SavingsGoalStatus(val label: String, val dbValue: String) {
    ACTIVE("进行中", "ACTIVE"),
    PAUSED("已暂停", "PAUSED"),
    COMPLETED("已达成", "COMPLETED"),
    CANCELLED("已取消", "CANCELLED");

    val isOpen: Boolean get() = this == ACTIVE || this == PAUSED

    companion object {
        fun fromDb(value: String): SavingsGoalStatus =
            entries.firstOrNull { it.dbValue == value } ?: ACTIVE
    }
}

/** 储蓄流水类型。存入与取出都是资金划转，不计普通收入或支出。 */
enum class SavingsEntryType(val label: String, val dbValue: String) {
    DEPOSIT("存入", "DEPOSIT"),
    WITHDRAW("取出", "WITHDRAW"),
    TRANSFER_IN("转入", "TRANSFER_IN"),
    TRANSFER_OUT("转出", "TRANSFER_OUT");

    /** 对储蓄余额是增加还是减少。 */
    val isInflow: Boolean get() = this == DEPOSIT || this == TRANSFER_IN

    companion object {
        fun fromDb(value: String): SavingsEntryType =
            entries.firstOrNull { it.dbValue == value } ?: DEPOSIT
    }
}

data class SavingsGoal(
    val id: Long = 0L,
    val name: String,
    val targetMinor: Long,
    val dueAt: Long,
    val note: String = "",
    val categoryId: Long? = null,
    val status: SavingsGoalStatus = SavingsGoalStatus.ACTIVE,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val categoryName: String? = null,
    val categoryEmoji: String = "🏷️",
) {
    val isOpen: Boolean get() = status.isOpen

    fun targetText(): String = Money.format(targetMinor)
}

data class SavingsEntry(
    val id: Long = 0L,
    val goalId: Long?,
    val type: SavingsEntryType,
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String = "",
)

/**
 * 单个目标的进度。金额一律由 [xyz.ikuyo.finance.domain.usecase.SavingsLedger] 算出，
 * 不在这里做持久化，避免和流水两次记账。
 */
data class SavingsProgress(
    val goalId: Long,
    val targetMinor: Long,
    val savedMinor: Long,
    val daysLeft: Int?,
    val isOverdue: Boolean = false,
) {
    val remainingMinor: Long get() = (targetMinor - savedMinor).coerceAtLeast(0L)

    val isReached: Boolean get() = savedMinor >= targetMinor

    /** 万分比，超额时不截断（例如 12000 = 120%）。 */
    val progressBp: Int
        get() = if (targetMinor <= 0L) 0 else ((savedMinor * 10_000L) / targetMinor).toInt()
}