package xyz.ikuyo.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 账单操作历史表（v3 起）。
 *
 * 所有会改变账单状态的动作（新增/修改/删除/恢复/撤销/永久删除）都写一条，
 * 便于审计与统一撤销；历史只读，不参与任何统计计算。
 */
@Entity(tableName = "bill_operations")
data class BillOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 相关账单 id；账单被永久删除后该 id 不会再出现在 bills 表。 */
    val billId: Long,
    /** BillOperationType.dbValue */
    val type: String,
    /** 用户可读摘要，例如「新增 ¥28.50 · 餐饮」 */
    val summary: String,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val amountMinor: Long = 0L,
    /** 该条历史本身是否已被撤销（撤销后不再允许重复撤销）。 */
    @ColumnInfo(defaultValue = "0") val undone: Boolean = false,
    val operatedAt: Long,
)