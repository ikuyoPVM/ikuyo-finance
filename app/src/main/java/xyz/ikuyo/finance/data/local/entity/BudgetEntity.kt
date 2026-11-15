package xyz.ikuyo.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 预算规则表（v2 起，v3 补齐名称/暂停/备注/标签维度）。
 *
 * v1 把周期写死成自然月（periodStart / periodEnd 固定区间）；v2 改成
 * 规则化存储：只保存金额 + 周期类型，具体区间运行时推导，因此每周/每日
 * 预算会自动滚动，无需用户重复录入。v1 数据由 MIGRATION_1_2 全部转为 MONTHLY。
 */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** BudgetScope.dbValue：TOTAL / CATEGORY / TAG / SPECIAL */
    val scope: String,
    val categoryId: Long? = null,
    /** 仅 TAG 维度使用：关联的标签 id。 */
    val tagId: Long? = null,
    /** 用户可读名称（专项预算必填，其余可空）。 */
    val name: String? = null,
    val amountMinor: Long,
    /** BudgetPeriodType.dbValue：MONTHLY / WEEKLY / DAILY / CUSTOM */
    val periodType: String = "MONTHLY",
    /** 是否结转上一周期结余（超支不结转） */
    val carryOver: Boolean = false,
    /** 仅 CUSTOM 使用：周期起（毫秒，含） */
    val customStart: Long? = null,
    /** 仅 CUSTOM 使用：周期止（毫秒，含） */
    val customEnd: Long? = null,
    /** false = 暂停（不再提醒），不影响任何历史账单。 */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long = 0L,
)