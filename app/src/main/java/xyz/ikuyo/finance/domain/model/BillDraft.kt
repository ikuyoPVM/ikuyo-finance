package xyz.ikuyo.finance.domain.model

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time

/** 记账草稿：由 UI 或 AI 解析结果构造，经校验后才落库。 */
data class BillDraft(
    val type: BillType,
    val amountMinor: Long,
    val currency: String = Money.BASE_CURRENCY,
    val exchangeRateScaled: Long = Money.RATE_SCALE,
    val categoryId: Long? = null,
    val paymentMethod: PaymentMethod,
    val note: String = "",
    val occurredAt: Long = Time.nowMillis(),
    val source: BillSource = BillSource.MANUAL,
    val refundOfBillId: Long? = null,
)