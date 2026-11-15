package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.domain.model.BillDraft
import java.util.UUID

/**
 * 新增账单用例。
 *
 * - 校验金额与币种，人民币折算在本地完成（AI 不参与金额计算）。
 * - 每次写入生成唯一 clientKey，配合数据库唯一索引实现幂等，避免重复点击或重试重复入账。
 */
class AddBillUseCase(private val repository: BillRepository) {

    suspend operator fun invoke(draft: BillDraft): Result<Long> {
        if (draft.amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("金额必须大于 0"))
        }
        val cnyAmount = Money.convertToCnyMinor(
            amountMinor = draft.amountMinor,
            currency = draft.currency,
            rate = Money.rateFromScaled(draft.exchangeRateScaled),
        )
        val entity = BillEntity(
            type = draft.type.dbValue,
            amountMinor = draft.amountMinor,
            currency = draft.currency,
            exchangeRateScaled = draft.exchangeRateScaled,
            cnyAmountMinor = cnyAmount,
            categoryId = draft.categoryId,
            paymentMethod = draft.paymentMethod.dbValue,
            note = draft.note.trim(),
            occurredAt = draft.occurredAt,
            createdAt = Time.nowMillis(),
            source = draft.source.dbValue,
            refundOfBillId = draft.refundOfBillId,
            clientKey = UUID.randomUUID().toString(),
        )
        val id = repository.insert(entity)
        return if (id > 0L) {
            Result.success(id)
        } else {
            Result.failure(IllegalStateException("账单已存在，未重复写入"))
        }
    }
}