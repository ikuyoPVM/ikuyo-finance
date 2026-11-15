package xyz.ikuyo.finance.domain.model

/**
 * AI 解析出来的候选账单。
 *
 * 候选账单只是「待确认的草稿」：在用户点「确认入账」之前，它不影响余额、预算与统计，
 * 也不存在于账单表里。真正写入必须经过 AddBillUseCase（同一套校验与幂等键）。
 */
data class BillCandidate(
    /** 会话内唯一标识，用于列表 key 与状态更新 */
    val localId: String,
    /** 幂等键：同一张候选卡片重复确认只会入账一次 */
    val clientKey: String,
    val type: BillType,
    val amountMinor: Long,
    val currency: String = "CNY",
    /**
     * 折算用汇率（1 外币 = 多少人民币，放大 1e6）。
     *
     * 一律由本地汇率表解析得到，**不接受模型给出的汇率**（计划书 6.4）；
     * 本地没有该币种汇率时保持 1.0 且追加「需要确认」理由，不允许自动入账。
     */
    val exchangeRateScaled: Long = 1_000_000L,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val occurredAt: Long,
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val note: String = "",
    val tagNames: List<String> = emptyList(),
    /** AI 自评分 0..1，仅用于决定是否需要用户确认 */
    val confidence: Double = 0.0,
    /** 需要确认的具体原因；为空且置信度足够才可以自动入账 */
    val reasons: List<String> = emptyList(),
    /** 已经写入账单表 */
    val applied: Boolean = false,
    val appliedBillId: Long? = null,
    /** 入账对应的操作历史 id，撤销时按它精确定位 */
    val appliedOperationId: Long? = null,
) {
    /** 是否需要用户确认（置信度不足或有明确歧义） */
    fun needsConfirm(threshold: Double): Boolean =
        reasons.isNotEmpty() || confidence < threshold
}