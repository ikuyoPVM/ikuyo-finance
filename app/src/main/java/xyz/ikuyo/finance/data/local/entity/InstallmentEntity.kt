package xyz.ikuyo.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分期计划表（v6 起）。
 *
 * 计划只是「欠多少、分几期、什么时候还」，不是账单：用户确认某一期付款后，
 * 才会通过受控业务接口写入 bills。因此这张表的金额永远不参与余额、预算与统计。
 *
 * 金额口径：
 * - [totalMinor] = 本金 + 手续费 + 利息，也等于各期金额之和；
 * - [firstPeriodMinor] 允许与 [perPeriodMinor] 不同（首期多付手续费这类情况）；
 * - 各期之和与总金额的一致性由 `InstallmentSchedule.validate` 在保存前校验。
 */
@Entity(
    tableName = "installments",
    indices = [Index("dueAt"), Index("enabled")],
)
data class InstallmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val totalMinor: Long,
    val principalMinor: Long = 0L,
    @ColumnInfo(defaultValue = "0") val feeMinor: Long = 0L,
    @ColumnInfo(defaultValue = "0") val interestMinor: Long = 0L,
    val periodCount: Int,
    val firstPeriodMinor: Long,
    val perPeriodMinor: Long,
    /** 首期到期日（毫秒，当天中午）。 */
    val dueAt: Long,
    /** 月内锚点日 1..31，用于月末回退时不漂移。 */
    val anchorDay: Int,
    val categoryId: Long? = null,
    /** PaymentMethod.dbValue */
    val paymentMethod: String,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** 提前结束时间；非空表示用户主动终止，剩余期数不再算未来待还。 */
    val closedEarlyAt: Long? = null,
    val note: String = "",
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 分期付款记录表：一行代表「第 N 期已经付过」。
 *
 * [billId] 指向确认付款时生成的真实账单；[periodIndex] 从 0 开始。
 * 同一个计划的同一期只允许一条记录（仓储层据此实现按期幂等）。
 */
@Entity(
    tableName = "installment_payments",
    indices = [Index("installmentId"), Index("dueAt"), Index(value = ["installmentId", "periodIndex"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = InstallmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["installmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InstallmentPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val installmentId: Long,
    val periodIndex: Int,
    val billId: Long? = null,
    val amountMinor: Long,
    val dueAt: Long,
    val paidAt: Long,
    val note: String = "",
)