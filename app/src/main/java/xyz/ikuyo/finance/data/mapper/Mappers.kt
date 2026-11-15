package xyz.ikuyo.finance.data.mapper

import xyz.ikuyo.finance.data.local.dao.BillWithCategory
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.BillOperationEntity
import xyz.ikuyo.finance.data.local.entity.BudgetChangeEntity
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.local.entity.BudgetTemplateEntity
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.model.BillOperationType
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetChange
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.BudgetTemplate
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.RateSource

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
    rateSource = RateSource.fromDb(bill.rateSource),
    rateUpdatedAt = bill.rateUpdatedAt,
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
    rateSource = RateSource.fromDb(rateSource),
    rateUpdatedAt = rateUpdatedAt,
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
    tagId = tagId,
    name = name,
    amountMinor = amountMinor,
    periodType = BudgetPeriodType.fromDb(periodType),
    carryOver = carryOver,
    customStart = customStart,
    customEnd = customEnd,
    enabled = enabled,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun BudgetTemplateEntity.toDomain(): BudgetTemplate = BudgetTemplate(
    id = id,
    name = name,
    scope = BudgetScope.fromDb(scope),
    categoryId = categoryId,
    tagId = tagId,
    amountMinor = amountMinor,
    periodType = BudgetPeriodType.fromDb(periodType),
    carryOver = carryOver,
    note = note,
    createdAt = createdAt,
)

fun BillOperationEntity.toDomain(): BillOperation = BillOperation(
    id = id,
    billId = billId,
    type = BillOperationType.fromDb(type),
    summary = summary,
    beforeJson = beforeJson,
    afterJson = afterJson,
    amountMinor = amountMinor,
    undone = undone,
    operatedAt = operatedAt,
)

fun BudgetChangeEntity.toDomain(): BudgetChange = BudgetChange(
    id = id,
    budgetId = budgetId,
    scope = BudgetScope.fromDb(scope),
    categoryId = categoryId,
    categoryName = categoryName,
    oldAmountMinor = oldAmountMinor,
    newAmountMinor = newAmountMinor,
    periodType = BudgetPeriodType.fromDb(periodType),
    changedAt = changedAt,
)

/** 列表排序：默认按消费时间，其次按录入时间。 */
fun List<Bill>.sortedBy(order: BillSortOrder): List<Bill> = when (order) {
    BillSortOrder.OCCURRED_DESC -> sortedWith(compareByDescending<Bill> { it.occurredAt }.thenByDescending { it.id })
    BillSortOrder.CREATED_DESC -> sortedWith(compareByDescending<Bill> { it.createdAt }.thenByDescending { it.id })
}