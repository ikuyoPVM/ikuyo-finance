package xyz.ikuyo.finance.domain.model

import xyz.ikuyo.finance.core.Money

/**
 * 分期计划（阶段五第 3 步）。
 *
 * 阶段五硬原则：**计划本身不等于实际支出**。这里只描述「欠多少、分几期、每期多少、
 * 什么时候到期」，真正的支出要等用户确认某一期付款后，才会通过受控业务接口写入 bills。
 *
 * 金额一律用「分」的 Long 表示；本金 / 手续费 / 利息分开记录，便于统计里区分
 * 「真的是本金」与「为借钱付出的成本」。
 *
 * 不变量：[totalMinor] 必须等于各期金额之和（见 [scheduleTotalMinor]），
 * 由 [xyz.ikuyo.finance.domain.usecase.InstallmentSchedule.validate] 在保存前校验，
 * 避免出现「总金额和分期明细对不上」的账。
 */
data class Installment(
    val id: Long = 0L,
    /** 商品或事项名称，例如「笔记本电脑」。 */
    val name: String,
    /** 总还款金额（本金 + 手续费 + 利息），也等于各期金额之和。 */
    val totalMinor: Long,
    /** 本金。 */
    val principalMinor: Long,
    /** 手续费。 */
    val feeMinor: Long = 0L,
    /** 利息。 */
    val interestMinor: Long = 0L,
    /** 分期期数，至少 1 期。 */
    val periodCount: Int,
    /** 首期金额，允许与后续各期不同（例如平台要求首期多付一笔手续费）。 */
    val firstPeriodMinor: Long,
    /** 除首期之外每期的金额。 */
    val perPeriodMinor: Long,
    /** 首期到期日（毫秒）。后续各期按月顺延。 */
    val dueAt: Long,
    /** 首次到期日的「几号」，用于按月顺延时保持日期不漂移。 */
    val anchorDay: Int,
    val categoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    /** 暂停后不再提醒，但剩余期数仍然算「未来待还」。 */
    val enabled: Boolean = true,
    /** 提前结束的时间；非空表示用户主动终止，剩余期数不再算未来待还。 */
    val closedEarlyAt: Long? = null,
    val note: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 展示用分类名，来自 join，不参与持久化。 */
    val categoryName: String? = null,
    val categoryEmoji: String = "🏷️",
) {

    /** 第 [periodIndex] 期（0 期起）的应还金额。 */
    fun amountOf(periodIndex: Int): Long = if (periodIndex <= 0) firstPeriodMinor else perPeriodMinor

    /** 各期金额之和，必须与 [totalMinor] 一致。 */
    val scheduleTotalMinor: Long
        get() = firstPeriodMinor + perPeriodMinor * (periodCount - 1).coerceAtLeast(0)

    /** 已提前结束（不再产生新的待还）。 */
    val isClosed: Boolean get() = closedEarlyAt != null

    /** 计划是否还在生效：未提前结束。 */
    val isActive: Boolean get() = !isClosed

    /** 每期是否都一致（方便 UI 决定要不要展示「首期 X，其后 Y」）。 */
    val hasUnevenFirstPeriod: Boolean get() = firstPeriodMinor != perPeriodMinor

    fun amountText(): String = Money.format(totalMinor)
}

/**
 * 某一期已经付过款的记录。
 *
 * [periodIndex] 从 0 开始；[billId] 指向 bills 里真实生成的那笔支出。
 */
data class InstallmentPayment(
    val id: Long = 0L,
    val installmentId: Long,
    val periodIndex: Int,
    val billId: Long?,
    val amountMinor: Long,
    val dueAt: Long,
    val paidAt: Long,
    val note: String = "",
)

/** 某一期的展示信息（尚未付款时也存在于计划里）。 */
data class InstallmentPeriod(
    /** 0 期起。 */
    val index: Int,
    val dueAt: Long,
    val amountMinor: Long,
    /** 该期的付款记录，未付时为空。 */
    val payment: InstallmentPayment? = null,
) {
    val isPaid: Boolean get() = payment != null

    /** 第几期（1 期起，给 UI 用）。 */
    val periodNumber: Int get() = index + 1
}

/** 计划的还款进度汇总：已还金额 / 剩余金额 / 已还期数 / 剩余期数 / 未来待还。 */
data class InstallmentProgress(
    val installmentId: Long,
    val periodCount: Int,
    val paidCount: Int,
    val paidMinor: Long,
    /** 剩余未付金额；提前结束后为 0（不再算未来待还）。 */
    val remainingMinor: Long,
    /** 下一期待还；全部付清或提前结束后为 null。 */
    val nextPeriod: InstallmentPeriod?,
) {
    val remainingCount: Int get() = (periodCount - paidCount).coerceAtLeast(0)

    val isFinished: Boolean get() = paidCount >= periodCount

    /** 已还比例，万分比；没有总金额时为 0。 */
    val progressBp: Int
        get() {
            val total = paidMinor + remainingMinor
            return if (total <= 0L) 0 else ((paidMinor * 10_000L) / total).toInt()
        }
}