package xyz.ikuyo.finance.core

import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.RateSource

/**
 * 账单快照编解码 —— 操作历史用它保存「改动前 / 改动后」的真实字段，
 * 这样撤销一次修改可以精确回到旧值，而不是猜。
 */
object BillSnapshot {

    fun encode(bill: Bill): String = FlatJson.encode(
        linkedMapOf(
            "id" to bill.id.toString(),
            "type" to bill.type.dbValue,
            "amountMinor" to bill.amountMinor.toString(),
            "currency" to bill.currency,
            "exchangeRateScaled" to bill.exchangeRateScaled.toString(),
            "cnyAmountMinor" to bill.cnyAmountMinor.toString(),
            "categoryId" to bill.categoryId?.toString(),
            // 分类名/emoji 是展示用的快照字段：分类被改名或删除后，历史记录仍能还原当时的显示。
            "categoryName" to bill.categoryName,
            "categoryEmoji" to bill.categoryEmoji,
            "paymentMethod" to bill.paymentMethod.dbValue,
            "note" to bill.note,
            "occurredAt" to bill.occurredAt.toString(),
            "createdAt" to bill.createdAt.toString(),
            "source" to bill.source.dbValue,
            "refundOfBillId" to bill.refundOfBillId?.toString(),
            "rateSource" to bill.rateSource.dbValue,
            "rateUpdatedAt" to bill.rateUpdatedAt.toString(),
        )
    )

    fun decode(json: String?): Bill? {
        val map = json?.let { FlatJson.decode(it) } ?: return null
        return Bill(
            id = map["id"]?.toLongOrNull() ?: return null,
            type = map["type"]?.let { BillType.fromDb(it) } ?: return null,
            amountMinor = map["amountMinor"]?.toLongOrNull() ?: return null,
            currency = map["currency"] ?: Money.BASE_CURRENCY,
            exchangeRateScaled = map["exchangeRateScaled"]?.toLongOrNull() ?: Money.RATE_SCALE,
            cnyAmountMinor = map["cnyAmountMinor"]?.toLongOrNull() ?: 0L,
            categoryId = map["categoryId"]?.toLongOrNull(),
            categoryName = map["categoryName"],
            categoryEmoji = map["categoryEmoji"] ?: "🏷️",
            paymentMethod = map["paymentMethod"]?.let { PaymentMethod.fromDb(it) } ?: PaymentMethod.OTHER,
            note = map["note"] ?: "",
            occurredAt = map["occurredAt"]?.toLongOrNull() ?: 0L,
            createdAt = map["createdAt"]?.toLongOrNull() ?: 0L,
            source = map["source"]?.let { BillSource.fromDb(it) } ?: BillSource.MANUAL,
            refundOfBillId = map["refundOfBillId"]?.toLongOrNull(),
            rateSource = map["rateSource"]?.let { RateSource.fromDb(it) } ?: RateSource.MANUAL,
            rateUpdatedAt = map["rateUpdatedAt"]?.toLongOrNull() ?: 0L,
        )
    }
}