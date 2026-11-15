package xyz.ikuyo.finance.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 预算变更历史（计划书 8.3）。
 *
 * 只记录「预算怎么改的」，不触碰任何历史账单；分类名做快照，
 * 分类被重命名或删除后历史依然可读。
 */
@Entity(tableName = "budget_changes")
data class BudgetChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val budgetId: Long = 0L,
    /** BudgetScope.dbValue */
    val scope: String,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    /** null 表示此前没有预算 */
    val oldAmountMinor: Long? = null,
    /** null 表示清除预算 */
    val newAmountMinor: Long? = null,
    /** BudgetPeriodType.dbValue */
    val periodType: String,
    val changedAt: Long,
)