package xyz.ikuyo.finance.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 预算模板表（v3 起）：保存一套可复用规则，一键创建新预算。 */
@Entity(tableName = "budget_templates")
data class BudgetTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** BudgetScope.dbValue */
    val scope: String = "SPECIAL",
    val categoryId: Long? = null,
    val tagId: Long? = null,
    val amountMinor: Long,
    /** BudgetPeriodType.dbValue */
    val periodType: String = "MONTHLY",
    val carryOver: Boolean = false,
    val note: String? = null,
    val createdAt: Long,
)