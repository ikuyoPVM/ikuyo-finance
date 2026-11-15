package xyz.ikuyo.finance.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import xyz.ikuyo.finance.core.Money

/**
 * 账单表。
 *
 * - 金额使用最小货币单位 Long，禁止浮点。
 * - clientKey 唯一索引用于幂等，避免重复点击 / AI 重试造成重复入账。
 * - 删除使用软删除（deleted = 1），回收站记录不参与余额与统计。
 */
@Entity(
    tableName = "bills",
    indices = [
        Index("occurredAt"),
        Index("createdAt"),
        Index("categoryId"),
        Index(value = ["clientKey"], unique = true),
        Index("deleted"),
    ],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** BillType.dbValue */
    val type: String,
    /** 原币最小单位金额 */
    val amountMinor: Long,
    val currency: String = Money.BASE_CURRENCY,
    /** 1 单位原币对应的人民币金额，缩放 1e6 保存 */
    val exchangeRateScaled: Long = Money.RATE_SCALE,
    /** 保存时固定的人民币折算金额（最小单位） */
    val cnyAmountMinor: Long,
    val categoryId: Long? = null,
    /** PaymentMethod.dbValue */
    val paymentMethod: String,
    val note: String = "",
    /** 消费发生时间 */
    val occurredAt: Long,
    /** 录入时间 */
    val createdAt: Long,
    /** BillSource.dbValue */
    val source: String = "MANUAL",
    /** 退款关联的原始消费账单 */
    val refundOfBillId: Long? = null,
    /** RateSource.dbValue：保存时所用汇率的来源 */
    val rateSource: String = "MANUAL",
    /** 保存时所用汇率的写入时间 */
    val rateUpdatedAt: Long = 0L,
    /** 幂等键 */
    val clientKey: String,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)