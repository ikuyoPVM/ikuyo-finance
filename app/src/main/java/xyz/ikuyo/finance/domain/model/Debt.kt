package xyz.ikuyo.finance.domain.model

import xyz.ikuyo.finance.core.Money

/**
 * 债务类型（阶段五第 4 步）。
 *
 * 「欠款」与「借出」方向不同，记账规则也不同，所以方向直接写在类型里，
 * 避免界面上再让用户选一次而选错：
 * - 欠款类（消费信贷 / 向亲友借入 / 自定义欠款）：本金不计收入，归还本金不计支出；
 * - 借出类（借给亲友 / 自定义借出）：本金不计支出，收回本金不计收入；
 * - 两个方向只有利息与手续费进收支：欠款付出的利息是支出，借出收到的利息是收入。
 */
enum class DebtType(val label: String, val dbValue: String, val isOwed: Boolean) {
    CONSUMER_CREDIT("消费信贷", "CONSUMER_CREDIT", true),
    BORROWED("向亲友借入", "BORROWED", true),
    LENT("借给亲友", "LENT", false),
    CUSTOM_OWED("自定义欠款", "CUSTOM_OWED", true),
    CUSTOM_LENT("自定义借出", "CUSTOM_LENT", false),
    ;

    /** 我欠别人的钱（展示用「剩余欠款」）。 */
    val isDebt: Boolean get() = isOwed

    /** 别人欠我的钱（展示用「待收回」）。 */
    val isReceivable: Boolean get() = !isOwed

    companion object {
        fun fromDb(value: String): DebtType =
            entries.firstOrNull { it.dbValue == value } ?: CUSTOM_OWED
    }
}

/**
 * 一笔债务 / 借出。
 *
 * 阶段五硬原则：本金流转不算收入也不算支出，只有利息与手续费才写账单。
 * 因此这里只记录「本金多少、还了多少、下次什么时候到期」，
 * 真正的账单要等用户确认某次还款后，由 [xyz.ikuyo.finance.data.repository.DebtRepository]
 * 按受控规则生成（且只生成利息/手续费那部分）。
 */
data class Debt(
    val id: Long = 0L,
    val name: String,
    val type: DebtType = DebtType.CONSUMER_CREDIT,
    /** 本金（不含利息与手续费）。 */
    val principalMinor: Long,
    /** 首次到期日（毫秒）；还清或结清后不再使用。 */
    val dueAt: Long,
    /** 月内锚点日 1..31，按月顺延时保持日期不漂移。 */
    val anchorDay: Int = 1,
    val categoryId: Long? = null,
    /** 利息 / 手续费生成账单时使用的支付方式。 */
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    /** 暂停后不再提醒，但仍算未结清。 */
    val enabled: Boolean = true,
    /** 结清时间；非空表示这笔债务已经了结。 */
    val settledAt: Long? = null,
    val note: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 只读：分类名与图标，方便列表直接显示。 */
    val categoryName: String? = null,
    val categoryEmoji: String = "🏷️",
) {

    val isSettled: Boolean get() = settledAt != null

    fun principalText(): String = Money.format(principalMinor)
}

/**
 * 一次还款（欠款方向）或一次收回（借出方向）。
 *
 * [principalMinor] 是本金部分，[extraMinor] 是利息或手续费，
 * 只有后者会产生真实账单：欠款方向记支出，借出方向记收入。
 */
data class DebtPayment(
    val id: Long = 0L,
    val debtId: Long,
    val principalMinor: Long,
    val extraMinor: Long = 0L,
    /** 利息/手续费生成的那笔账单 id；没有额外费用时为 null。 */
    val billId: Long? = null,
    val paidAt: Long,
    val note: String = "",
)

/** 一笔债务的进度汇总。 */
data class DebtProgress(
    val debtId: Long,
    val type: DebtType,
    val principalMinor: Long,
    /** 已还（或已收回）本金。 */
    val repaidPrincipalMinor: Long,
    /** 已付利息与手续费。 */
    val extraPaidMinor: Long,
    val lastPaidAt: Long? = null,
    val isSettled: Boolean = false,
) {
    /** 剩余本金；不会为负（多还的部分视作提前结清）。 */
    val remainingPrincipalMinor: Long
        get() = (principalMinor - repaidPrincipalMinor).coerceAtLeast(0L)

    /** 已还比例，万分比。 */
    val progressBp: Int
        get() = if (principalMinor <= 0L) 0 else ((repaidPrincipalMinor * 10_000L) / principalMinor).toInt()
}