package xyz.ikuyo.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 固定支出计划表（v5 起）。
 *
 * 计划只是「预计会发生什么」，不是账单：用户确认付款后才会写入 bills 表。
 * 因此这张表的金额永远不参与余额、预算与统计。
 */
@Entity(
    tableName = "fixed_expenses",
    indices = [Index("dueAt"), Index("enabled")],
)
data class FixedExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val amountMinor: Long,
    val categoryId: Long? = null,
    /** PaymentMethod.dbValue */
    val paymentMethod: String,
    /** FixedPeriodType.dbValue */
    val periodType: String = "MONTHLY",
    /** 下一次到期日（毫秒，当天中午） */
    val dueAt: Long,
    /** 月内锚点日 1..31，用于月末回退时不漂移 */
    val anchorDay: Int,
    /** 仅 CUSTOM 使用：间隔天数 */
    @ColumnInfo(defaultValue = "30") val customDays: Int = 30,
    /** 金额浮动（确认付款时可改写） */
    @ColumnInfo(defaultValue = "0") val isVariable: Boolean = false,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val note: String = "",
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 固定支出付款记录表（v5 起）。
 *
 * [billId] 指向确认付款时生成的真实账单。计划删除后付款记录保留：
 * 「这笔钱确实付过」的事实不因删计划而消失，历史账单也不受影响。
 */
@Entity(
    tableName = "fixed_expense_payments",
    indices = [Index("fixedExpenseId"), Index("dueAt")],
    foreignKeys = [
        ForeignKey(
            entity = FixedExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["fixedExpenseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FixedExpensePaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fixedExpenseId: Long,
    val billId: Long? = null,
    val amountMinor: Long,
    val dueAt: Long,
    val paidAt: Long,
    val note: String = "",
)