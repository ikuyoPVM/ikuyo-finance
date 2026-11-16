package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillOperationType
import java.util.UUID

/**
 * 新增账单用例。
 *
 * - 校验金额与币种，人民币折算在本地完成（AI 不参与金额计算）。
 * - 每次写入生成唯一 clientKey，配合数据库唯一索引实现幂等，避免重复点击或重试重复入账。
 * - 入库成功后补写标签关联与操作历史（历史只记录，不参与任何金额计算）。
 */
class AddBillUseCase(
    private val repository: BillRepository,
    private val operationRepository: OperationRepository,
    private val tagRepository: TagRepository,
) {

    /**
     * @param clientKey 幂等键。手动记账可省略（每次新生成）；AI 记账必须传入候选卡片上固定的键，
     * 这样重试、重复点「确认」、超时后重发都只会落一笔账。
     */
    suspend operator fun invoke(
        draft: BillDraft,
        clientKey: String = UUID.randomUUID().toString(),
    ): Result<Long> {
        if (draft.amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("金额必须大于 0"))
        }
        // 先按幂等键查一次：命中说明这笔账已经写过，直接返回既有 id，不重复入账也不重复记历史。
        repository.findByClientKey(clientKey)?.let { existing ->
            if (draft.tagIds.isNotEmpty()) {
                tagRepository.setBillTags(existing.id, draft.tagIds)
            }
            return Result.success(existing.id)
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
            occurredAt = Time.startOfDayMillis(draft.occurredAt),
            createdAt = Time.nowMillis(),
            source = draft.source.dbValue,
            refundOfBillId = draft.refundOfBillId,
            rateSource = draft.rateSource.dbValue,
            rateUpdatedAt = draft.rateUpdatedAt,
            clientKey = clientKey,
        )
        val id = repository.insert(entity)
        if (id <= 0L) {
            // 极端并发下唯一索引拦住了第二次写入：仍按「已入账」处理，避免用户看到失败。
            val existingId = repository.findByClientKey(clientKey)?.id
            return if (existingId != null && existingId > 0L) {
                Result.success(existingId)
            } else {
                Result.failure(IllegalStateException("账单已存在，未重复写入"))
            }
        }
        if (draft.tagIds.isNotEmpty()) {
            tagRepository.setBillTags(id, draft.tagIds)
        }
        // 摘要要带分类名：实体本身不含分类名，必须走连表查询（否则历史里显示「未分类」）。
        val created = repository.findDomainWithCategory(id) ?: entity.copy(id = id).toDomain()
        operationRepository.record(
            billId = id,
            type = BillOperationType.CREATE,
            summary = OperationRepository.summaryOf(BillOperationType.CREATE, created),
            amountMinor = cnyAmount,
            after = created,
        )
        return Result.success(id)
    }
}