package xyz.ikuyo.finance.domain.model

/**
 * 账单业务模型。
 *
 * amountMinor 为原币最小单位；cnyAmountMinor 为保存时按固定汇率折算的人民币最小单位。
 * 历史账单不随市场汇率变化。
 */
data class Bill(
    val id: Long = 0L,
    val type: BillType,
    val amountMinor: Long,
    val currency: String = "CNY",
    val exchangeRateScaled: Long,
    val cnyAmountMinor: Long,
    val categoryId: Long?,
    val categoryName: String? = null,
    val categoryEmoji: String = "🏷️",
    val paymentMethod: PaymentMethod,
    val note: String = "",
    val occurredAt: Long,
    val createdAt: Long,
    val source: BillSource = BillSource.MANUAL,
    val refundOfBillId: Long? = null,
) {
    /** 对余额/统计的影响（人民币最小单位）：收入为正，支出为负，退款为正。 */
    val signedImpactMinor: Long
        get() = when (type) {
            BillType.INCOME -> cnyAmountMinor
            BillType.EXPENSE -> -cnyAmountMinor
            BillType.REFUND -> cnyAmountMinor
        }

    /** 计入「支出」口径的金额：支出为正，退款为负，收入为 0。 */
    val expenseImpactMinor: Long
        get() = when (type) {
            BillType.EXPENSE -> cnyAmountMinor
            BillType.REFUND -> -cnyAmountMinor
            BillType.INCOME -> 0L
        }
}

data class Category(
    val id: Long = 0L,
    val name: String,
    val emoji: String = "🏷️",
    val colorArgb: Int = 0xFF6750A4.toInt(),
    val isDefault: Boolean = true,
    val sortOrder: Int = 0,
)

data class Tag(
    val id: Long = 0L,
    val name: String,
    val colorArgb: Int = 0xFF6750A4.toInt(),
)

enum class BudgetScope(val label: String, val dbValue: String) {
    TOTAL("总预算", "TOTAL"),
    CATEGORY("分类预算", "CATEGORY");

    companion object {
        fun fromDb(value: String): BudgetScope =
            entries.firstOrNull { it.dbValue == value } ?: TOTAL
    }
}

data class Budget(
    val id: Long = 0L,
    val scope: BudgetScope,
    val categoryId: Long? = null,
    val amountMinor: Long,
    val periodStart: Long,
    val periodEnd: Long,
)