package xyz.ikuyo.finance.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 预算表。第一版以「自然月」为周期写入 periodStart / periodEnd，
 * 后续可扩展为跨自然月的自定义周期（同一套字段即可表达）。
 */
@Entity(
    tableName = "budgets",
    indices = [Index(value = ["scope", "categoryId", "periodStart"])],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** BudgetScope.dbValue：TOTAL / CATEGORY */
    val scope: String,
    val categoryId: Long? = null,
    val amountMinor: Long,
    val periodStart: Long,
    /** 周期的最后一天 23:59:59.999 */
    val periodEnd: Long,
    val createdAt: Long,
)