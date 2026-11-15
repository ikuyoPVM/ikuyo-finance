package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.DebtDao
import xyz.ikuyo.finance.data.local.entity.DebtEntity
import xyz.ikuyo.finance.data.local.entity.DebtPaymentEntity
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Debt
import xyz.ikuyo.finance.domain.model.DebtPayment
import xyz.ikuyo.finance.domain.model.DebtType
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase
import xyz.ikuyo.finance.domain.usecase.DebtLedger

/**
 * 一次还款 / 收款的结果。
 *
 * - [Created]：[billId] 是利息或手续费生成的那笔账单；本金不动收支，所以可能为 null。
 * - [Duplicate]：同一个时间点已经记过一次，本次什么都没写（避免重复统计）。
 */
sealed interface RepayOutcome {
    data class Created(val billId: Long?, val settled: Boolean) : RepayOutcome
    data class Duplicate(val billId: Long?, val paidAt: Long) : RepayOutcome
}

/**
 * 债务 / 借出仓储（阶段五第 4 步）。
 *
 * 记账规则严格按计划书 4.3：
 * - 本金流转（借入、归还、借出、收回）不写任何账单，不计收入也不计支出；
 * - 只有 [DebtPayment.extraMinor]（利息 / 手续费）走 AddBillUseCase 生成账单，
 *   欠款方向记支出，借出方向记收入；
 * - 每笔本金流转都挂在本条债务上（`debtId`），因此不会被重复统计。
 */
class DebtRepository(
    private val dao: DebtDao,
    private val categoryRepository: CategoryRepository,
    private val addBillUseCase: AddBillUseCase,
) {

    fun observeAll(): Flow<List<Debt>> =
        combine(dao.observeAll(), categoryRepository.observeAll()) { debts, categories ->
            val byId = categories.associateBy { it.id }
            debts.map { it.toDomain(byId[it.categoryId]) }
        }

    fun observePayments(): Flow<List<DebtPayment>> =
        dao.observePayments().map { list -> list.map { it.toDomain() } }

    fun observePaymentsOf(debtId: Long): Flow<List<DebtPayment>> =
        dao.observePaymentsOf(debtId).map { list -> list.map { it.toDomain() } }

    suspend fun findById(id: Long): Debt? = dao.findById(id)?.toDomain(null)

    /** 新增或更新一笔债务。本金必须为正，名称不能为空。 */
    suspend fun save(debt: Debt): Result<Long> {
        val normalized = debt.copy(name = debt.name.trim(), note = debt.note.trim())
        DebtLedger.validate(normalized)?.let { return Result.failure(IllegalArgumentException(it)) }
        val now = Time.nowMillis()
        if (debt.id > 0L) {
            dao.update(
                id = debt.id,
                name = normalized.name,
                type = normalized.type.dbValue,
                principalMinor = normalized.principalMinor,
                dueAt = normalized.dueAt,
                anchorDay = normalized.anchorDay.coerceIn(1, 31),
                categoryId = normalized.categoryId,
                paymentMethod = normalized.paymentMethod.dbValue,
                note = normalized.note,
                updatedAt = now,
            )
            return Result.success(debt.id)
        }
        val entity = DebtEntity(
            name = normalized.name,
            type = normalized.type.dbValue,
            principalMinor = normalized.principalMinor,
            dueAt = normalized.dueAt,
            anchorDay = normalized.anchorDay.coerceIn(1, 31),
            categoryId = normalized.categoryId,
            paymentMethod = normalized.paymentMethod.dbValue,
            enabled = normalized.enabled,
            note = normalized.note,
            createdAt = now,
            updatedAt = now,
        )
        return Result.success(dao.insert(entity))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): Result<Unit> {
        dao.setEnabled(id, enabled, Time.nowMillis())
        return Result.success(Unit)
    }

    /** 删除债务。历史账单不受影响（本金本来就没生成账单）。 */
    suspend fun delete(id: Long) {
        dao.delete(id)
    }

    /**
     * 记一次还款（欠款方向）或一次收回（借出方向）。
     *
     * @param principalMinor 本金部分：不生成账单。
     * @param extraMinor 利息或手续费：大于 0 时生成一笔账单（欠款记支出，借出记收入）。
     */
    suspend fun repay(
        debt: Debt,
        principalMinor: Long,
        extraMinor: Long = 0L,
        paidAt: Long = Time.nowMillis(),
        note: String = "",
    ): Result<RepayOutcome> {
        if (debt.id <= 0L) return Result.failure(IllegalStateException("债务尚未保存"))
        if (debt.isSettled) return Result.failure(IllegalStateException("这笔债务已经结清"))
        val existing = dao.paymentsOf(debt.id).map { it.toDomain() }
        DebtLedger.validateRepayment(debt, existing, principalMinor, extraMinor)
            ?.let { return Result.failure(IllegalArgumentException(it)) }

        // 同一时间点重复提交只认第一次，避免重复统计本金与利息。
        dao.findPaymentAt(debt.id, paidAt)?.let {
            return Result.success(RepayOutcome.Duplicate(billId = it.billId, paidAt = it.paidAt))
        }

        var billId: Long? = null
        if (extraMinor > 0L) {
            val draft = BillDraft(
                type = if (debt.type.isOwed) BillType.EXPENSE else BillType.INCOME,
                amountMinor = extraMinor,
                categoryId = debt.categoryId,
                paymentMethod = debt.paymentMethod,
                note = extraNoteOf(debt, paidAt),
                occurredAt = paidAt,
            )
            billId = addBillUseCase(draft, clientKey = "debt:${debt.id}:extra:$paidAt")
                .getOrElse { return Result.failure(it) }
        }

        dao.insertPayment(
            DebtPaymentEntity(
                debtId = debt.id,
                principalMinor = principalMinor,
                extraMinor = extraMinor,
                billId = billId,
                paidAt = paidAt,
                note = note.trim(),
            )
        )

        val progress = DebtLedger.progressOf(debt, existing + DebtPayment(
            debtId = debt.id,
            principalMinor = principalMinor,
            extraMinor = extraMinor,
            billId = billId,
            paidAt = paidAt,
        ))
        val remaining = (debt.principalMinor - progress.repaidPrincipalMinor).coerceAtLeast(0L)
        if (remaining == 0L) {
            dao.settle(debt.id, paidAt)
        } else if (paidAt >= debt.dueAt) {
            // 到期后还了一部分：只推进一期，不因为一次还款跳过好几期。
            dao.updateDueAt(debt.id, DebtLedger.nextDue(debt.dueAt, debt.anchorDay), Time.nowMillis())
        }
        return Result.success(RepayOutcome.Created(billId = billId, settled = remaining == 0L))
    }

    /** 一次性结清：把剩余本金全部还掉。 */
    suspend fun settleNow(
        debt: Debt,
        extraMinor: Long = 0L,
        paidAt: Long = Time.nowMillis(),
        note: String = "",
    ): Result<RepayOutcome> {
        val existing = dao.paymentsOf(debt.id).map { it.toDomain() }
        val remaining = DebtLedger.progressOf(debt, existing).remainingPrincipalMinor
        if (remaining <= 0L) return Result.failure(IllegalStateException("这笔债务已经结清"))
        return repay(debt, principalMinor = remaining, extraMinor = extraMinor, paidAt = paidAt, note = note)
    }

    private fun extraNoteOf(debt: Debt, paidAt: Long): String {
        val label = Time.formatFullDate(paidAt)
        val kind = if (debt.type.isOwed) "债务利息" else "借出利息"
        return "${debt.name}（$kind · $label）"
    }
}

private fun DebtEntity.toDomain(category: Category?): Debt = Debt(
    id = id,
    name = name,
    type = DebtType.fromDb(type),
    principalMinor = principalMinor,
    dueAt = dueAt,
    anchorDay = anchorDay,
    categoryId = categoryId,
    paymentMethod = xyz.ikuyo.finance.domain.model.PaymentMethod.fromDb(paymentMethod),
    enabled = enabled,
    settledAt = settledAt,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    categoryName = category?.name,
    categoryEmoji = category?.emoji ?: "🏷️",
)

private fun DebtPaymentEntity.toDomain(): DebtPayment = DebtPayment(
    id = id,
    debtId = debtId,
    principalMinor = principalMinor,
    extraMinor = extraMinor,
    billId = billId,
    paidAt = paidAt,
    note = note,
)