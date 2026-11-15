package xyz.ikuyo.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 储蓄目标。金额一律为分（Long），`status` 存 [xyz.ikuyo.finance.domain.model.SavingsGoalStatus.dbValue]。
 */
@Entity(
    tableName = "savings_goals",
    indices = [Index("dueAt"), Index("status")],
)
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val targetMinor: Long,
    val dueAt: Long,
    val note: String = "",
    val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "ACTIVE") val status: String = "ACTIVE",
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 储蓄流水：存入/取出/目标间转入转出。这些记录不生成账单，只影响储蓄余额。
 */
@Entity(
    tableName = "savings_entries",
    indices = [Index("goalId"), Index("occurredAt")],
    foreignKeys = [
        ForeignKey(
            entity = SavingsGoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SavingsEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val goalId: Long?,
    /** [xyz.ikuyo.finance.domain.model.SavingsEntryType.dbValue] */
    val type: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String = "",
)