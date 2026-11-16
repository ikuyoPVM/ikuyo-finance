package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillOperationType

/**
 * 修改既有账单。
 *
 * - 金额重新校验并重新折算人民币，历史账单不因汇率变动而改数。
 * - 保留 clientKey 与 createdAt，避免破坏幂等与录入时间口径。
 * - 写入一条 UPDATE 历史，携带修改前后快照，供统一撤销使用。
 */
class EditBillUseCase(
    private val repository: BillRepository,
    private val operationRepository: OperationRepository,
    private val tagRepository: TagRepository,
) {

    suspend operator fun invoke(billId: Long, draft: BillDraft): Result<Long> {
        if (draft.amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("金额必须大于 0"))
        }
        val existing = repository.findById(billId)
            ?: return Result.failure(IllegalStateException("账单不存在，可能已被删除"))
        // 摘要与快照都要带上分类名，否则历史里会显示成「未分类」。
        val before = repository.findDomainWithCategory(billId) ?: existing.toDomain()
        val cnyAmount = Money.convertToCnyMinor(
            amountMinor = draft.amountMinor,
            currency = draft.currency,
            rate = Money.rateFromScaled(draft.exchangeRateScaled),
        )
        val updated = existing.copy(
            type = draft.type.dbValue,
            amountMinor = draft.amountMinor,
            currency = draft.currency,
            exchangeRateScaled = draft.exchangeRateScaled,
            cnyAmountMinor = cnyAmount,
            categoryId = draft.categoryId,
            paymentMethod = draft.paymentMethod.dbValue,
            note = draft.note.trim(),
            occurredAt = Time.startOfDayMillis(draft.occurredAt),
            source = draft.source.dbValue,
            refundOfBillId = draft.refundOfBillId,
            // 汇率没变时保留原来的来源与时间，只有改了汇率才写入新的快照信息。
            rateSource = if (draft.exchangeRateScaled == existing.exchangeRateScaled) {
                existing.rateSource
            } else {
                draft.rateSource.dbValue
            },
            rateUpdatedAt = if (draft.exchangeRateScaled == existing.exchangeRateScaled) {
                existing.rateUpdatedAt
            } else {
                draft.rateUpdatedAt
            },
        )
        repository.update(updated)
        tagRepository.setBillTags(billId, draft.tagIds)
        val after = repository.findDomainWithCategory(billId) ?: updated.toDomain()
        operationRepository.record(
            billId = billId,
            type = BillOperationType.UPDATE,
            summary = OperationRepository.summaryOf(BillOperationType.UPDATE, after),
            amountMinor = cnyAmount,
            before = before,
            after = after,
        )
        return Result.success(billId)
    }
}