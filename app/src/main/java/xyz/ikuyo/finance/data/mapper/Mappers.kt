package xyz.ikuyo.finance.data.mapper

import xyz.ikuyo.finance.data.local.dao.BillWithCategory
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod

fun BillWithCategory.toDomain(): Bill = Bill(
    id = bill.id,
    type = BillType.fromDb(bill.type),
    amountMinor = bill.amountMinor,
    currency = bill.currency,
    exchangeRateScaled = bill.exchangeRateScaled,
    cnyAmountMinor = bill.cnyAmountMinor,
    categoryId = bill.categoryId,
    categoryName = categoryName,
    categoryEmoji = categoryEmoji ?: "🏷️",
    paymentMethod = PaymentMethod.fromDb(bill.paymentMethod),
    note = bill.note,
    occurredAt = bill.occurredAt,
    createdAt = bill.createdAt,
    source = BillSource.fromDb(bill.source),
    refundOfBillId = bill.refundOfBillId,
)

fun BillEntity.toDomain(categoryName: String? = null, categoryEmoji: String? = null): Bill = Bill(
    id = id,
    type = BillType.fromDb(type),
    amountMinor = amountMinor,
    currency = currency,
    exchangeRateScaled = exchangeRateScaled,
    cnyAmountMinor = cnyAmountMinor,
    categoryId = categoryId,
    categoryName = categoryName,
    categoryEmoji = categoryEmoji ?: "🏷️",
    paymentMethod = PaymentMethod.fromDb(paymentMethod),
    note = note,
    occurredAt = occurredAt,
    createdAt = createdAt,
    source = BillSource.fromDb(source),
    refundOfBillId = refundOfBillId,
)

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    emoji = emoji,
    colorArgb = colorArgb,
    isDefault = isDefault,
    sortOrder = sortOrder,
)

fun BudgetEntity.toDomain(): Budget = Budget(
    id = id,
    scope = BudgetScope.fromDb(scope),
    categoryId = categoryId,
    amountMinor = amountMinor,
    periodStart = periodStart,
    periodEnd = periodEnd,
)

/** 列表排序：默认按消费时间，其次按录入时间。 */
fun List<Bill>.sortedBy(order: BillSortOrder): List<Bill> = when (order) {
    BillSortOrder.OCCURRED_DESC -> sortedWith(compareByDescending<Bill> { it.occurredAt }.thenByDescending { it.id })
    BillSortOrder.CREATED_DESC -> sortedWith(compareByDescending<Bill> { it.createdAt }.thenByDescending { it.id })
}