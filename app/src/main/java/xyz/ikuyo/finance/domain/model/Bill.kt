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
    /** 保存时所用汇率的来源（计划书 6.2：汇率记录来源）。 */
    val rateSource: RateSource = RateSource.MANUAL,
    /** 保存时所用汇率的写入时间（毫秒，0 = 未知）。 */
    val rateUpdatedAt: Long = 0L,
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

    val isIncome: Boolean get() = type == BillType.INCOME

    /** 退款冲减原消费，不计入收入。 */
    val isRefund: Boolean get() = type == BillType.REFUND

    val isExpense: Boolean get() = type == BillType.EXPENSE
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
    CATEGORY("分类预算", "CATEGORY"),
    TAG("标签预算", "TAG"),
    SPECIAL("专项预算", "SPECIAL");

    companion object {
        fun fromDb(value: String): BudgetScope =
            entries.firstOrNull { it.dbValue == value } ?: TOTAL
    }
}

/**
 * 预算规则。一条规则对应「总预算 / 分类预算 / 标签预算 / 专项预算」之一，
 * 周期由 [periodType] 描述（每月/每周/每日/自定义区间），
 * 具体区间在运行时推导，因此规则会自动滚动到新周期。
 *
 * [enabled] = false 表示暂停：仍然显示金额与已用，但不再产生任何提醒；
 * 暂停/删除都不会影响任何历史账单与统计口径。
 */
data class Budget(
    val id: Long = 0L,
    val scope: BudgetScope,
    val categoryId: Long? = null,
    /** 仅 [BudgetScope.TAG] 使用。 */
    val tagId: Long? = null,
    /** 用户可读名称；为空时由 [displayName] 兜底。 */
    val name: String? = null,
    val amountMinor: Long,
    val periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    /** 是否把上一周期的结余结转到本周期（超支不结转）。 */
    val carryOver: Boolean = false,
    /** 仅 [BudgetPeriodType.CUSTOM] 使用：周期起（毫秒，含）。 */
    val customStart: Long? = null,
    /** 仅 [BudgetPeriodType.CUSTOM] 使用：周期止（毫秒，含）。 */
    val customEnd: Long? = null,
    /** false = 暂停（不提醒）。 */
    val enabled: Boolean = true,
    val note: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 列表与提醒里的展示名：[name] 优先，其次按维度兜底。 */
    fun displayName(categoryName: String? = null, tagName: String? = null): String = when {
        !name.isNullOrBlank() -> name
        scope == BudgetScope.TOTAL -> "总预算"
        scope == BudgetScope.CATEGORY -> categoryName ?: "分类预算"
        scope == BudgetScope.TAG -> tagName ?: "标签预算"
        else -> "专项预算"
    }
}

/** 预算模板：保存一套规则，供后续一键创建新的周期/专项预算。 */
data class BudgetTemplate(
    val id: Long = 0L,
    val name: String,
    val scope: BudgetScope = BudgetScope.SPECIAL,
    val categoryId: Long? = null,
    val tagId: Long? = null,
    val amountMinor: Long,
    val periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    val carryOver: Boolean = false,
    val note: String? = null,
    val createdAt: Long = 0L,
)

/** 预算变更历史（计划书 8.3：调整预算记录历史，不修改历史账单）。 */
data class BudgetChange(
    val id: Long = 0L,
    val budgetId: Long = 0L,
    val scope: BudgetScope,
    val categoryId: Long? = null,
    /** 分类名快照，保证分类被重命名/删除后历史仍可读。 */
    val categoryName: String? = null,
    val oldAmountMinor: Long? = null,
    val newAmountMinor: Long? = null,
    val periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    val changedAt: Long,
) {
    val targetLabel: String
        get() = when (scope) {
            BudgetScope.TOTAL -> "总预算"
            // 分类预算/标签预算/专项预算都把目标名快照写在 categoryName 里，
            // 因此目标被改名或删除后，历史记录仍然可读。
            BudgetScope.CATEGORY -> categoryName ?: "分类预算"
            BudgetScope.TAG -> categoryName ?: "标签预算"
            BudgetScope.SPECIAL -> categoryName ?: "专项预算"
        }
}