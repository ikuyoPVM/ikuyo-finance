package xyz.ikuyo.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 债务 / 借出表（v7 起）。
 *
 * 与分期一样，这张表本身不参与余额与统计：本金流转既不记收入也不记支出，
 * 只有利息与手续费才通过受控业务接口生成账单（见 DebtRepository.repay）。
 *
 * [principalMinor] 是本金（不含利息与手续费），[settledAt] 非空表示已了结。
 */
@Entity(
    tableName = "debts",
    indices = [Index("dueAt"), Index("enabled"), Index("settledAt")],
)
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** DebtType.dbValue */
    val type: String,
    val principalMinor: Long,
    /** 下次到期日（毫秒，当天中午）。 */
    val dueAt: Long,
    /** 月内锚点日 1..31。 */
    val anchorDay: Int = 1,
    val categoryId: Long? = null,
    /** PaymentMethod.dbValue */
    @ColumnInfo(defaultValue = "OTHER") val paymentMethod: String = "OTHER",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** 结清时间；非空表示这笔债务已经了结。 */
    val settledAt: Long? = null,
    val note: String = "",
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 债务还款 / 收款记录表。
 *
 * [principalMinor] 是本金部分（不生成账单），[extraMinor] 是利息或手续费
 * （生成账单：欠款方向记支出，借出方向记收入），[billId] 指向那笔账单。
 */
@Entity(
    tableName = "debt_payments",
    indices = [Index("debtId"), Index("paidAt")],
    foreignKeys = [
        ForeignKey(
            entity = DebtEntity::class,
            parentColumns = ["id"],
            childColumns = ["debtId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DebtPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val debtId: Long,
    val principalMinor: Long = 0L,
    @ColumnInfo(defaultValue = "0") val extraMinor: Long = 0L,
    val billId: Long? = null,
    val paidAt: Long,
    val note: String = "",
)