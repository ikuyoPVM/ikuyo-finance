package xyz.ikuyo.finance.domain.model

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time

/** 记账草稿：由 UI 或 AI 解析结果构造，经校验后才落库。 */
data class BillDraft(
    val type: BillType,
    val amountMinor: Long,
    val currency: String = Money.BASE_CURRENCY,
    val exchangeRateScaled: Long = Money.RATE_SCALE,
    /** 所用汇率的来源（手动填写 / 自动获取 / 缓存）。 */
    val rateSource: RateSource = RateSource.MANUAL,
    /** 所用汇率的写入时间，0 = 未知（如人民币或用户当场手填）。 */
    val rateUpdatedAt: Long = 0L,
    val categoryId: Long? = null,
    val paymentMethod: PaymentMethod,
    val note: String = "",
    val occurredAt: Long = Time.nowMillis(),
    val source: BillSource = BillSource.MANUAL,
    val refundOfBillId: Long? = null,
    /** 附加标签，仅作维度统计，不影响金额 */
    val tagIds: List<Long> = emptyList(),
)