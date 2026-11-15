package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.InstallmentDao
import xyz.ikuyo.finance.data.local.entity.InstallmentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentPaymentEntity
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Installment
import xyz.ikuyo.finance.domain.model.InstallmentPayment
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase
import xyz.ikuyo.finance.domain.usecase.InstallmentSchedule

/**
 * 分期仓储。
 *
 * 关键约束（阶段五计划书第 3 / 7 章）：
 * - 计划金额不是账单：只有 [confirmPeriod] 才会写入 bills，其余操作都不产生账目；
 * - 未付款的期数只算「未来待还」，不进入余额、预算与统计；
 * - 同一计划的同一期按 (id, periodIndex) 做幂等：重复点确认只落一笔账；
 * - 提前结束要记下时间点，剩余期数不再算未来待还，但已付账单不受影响。
 */
class InstallmentRepository(
    private val dao: InstallmentDao,
    private val addBillUseCase: AddBillUseCase,
) {

    fun observeAll(): Flow<List<Installment>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    fun observePayments(): Flow<List<InstallmentPayment>> = dao.observePayments().map { list ->
        list.map { it.toDomain() }
    }

    fun observePaymentsOf(installmentId: Long): Flow<List<InstallmentPayment>> =
        dao.observePaymentsOf(installmentId).map { list -> list.map { it.toDomain() } }

    suspend fun findById(id: Long): Installment? = dao.findById(id)?.toDomain()

    suspend fun save(installment: Installment): Result<Long> {
        InstallmentSchedule.validate(installment)?.let { message ->
            return Result.failure(IllegalArgumentException(message))
        }
        return runCatching {
            if (installment.id > 0L) {
                dao.update(installment.toEntity(updatedAt = Time.nowMillis()))
                installment.id
            } else {
                val now = Time.nowMillis()
                dao.insert(installment.toEntity(createdAt = now, updatedAt = now))
            }
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): Result<Unit> = runCatching {
        dao.setEnabled(id, enabled, Time.nowMillis())
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * 确认某一期付款：生成一笔真实支出账单并记录该期已付。
     *
     * 幂等键 = `installment:{计划 id}:{期序号}`：同一期重复点击只会有一笔账单、一条付款记录。
     */
    suspend fun confirmPeriod(
        installment: Installment,
        periodIndex: Int,
        amountMinor: Long,
        paidAt: Long = Time.nowMillis(),
        note: String = "",
    ): Result<ConfirmOutcome> {
        if (amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("金额必须大于 0"))
        }
        // 已付就什么都不改：不写记录、不重复入账（保证重复点击安全）。
        dao.findPaymentOf(installment.id, periodIndex)?.let { existing ->
            return Result.success(
                ConfirmOutcome.AlreadyPaid(billId = existing.billId, paidAt = existing.paidAt),
            )
        }
        val periods = InstallmentSchedule.dueDates(installment)
        val dueAt = periods.getOrNull(periodIndex)
            ?: return Result.failure(IllegalArgumentException("期数超出范围"))
        val clientKey = clientKeyOf(installment.id, periodIndex)
        val draft = BillDraft(
            type = BillType.EXPENSE,
            amountMinor = amountMinor,
            categoryId = installment.categoryId,
            paymentMethod = installment.paymentMethod,
            note = billNoteOf(installment, periodIndex, dueAt),
            occurredAt = paidAt,
        )
        val billId = addBillUseCase(draft, clientKey = clientKey).getOrElse { error ->
            return Result.failure(error)
        }
        dao.insertPayment(
            InstallmentPaymentEntity(
                installmentId = installment.id,
                periodIndex = periodIndex,
                billId = billId,
                amountMinor = amountMinor,
                dueAt = dueAt,
                paidAt = paidAt,
                note = note.trim(),
            ),
        )
        return Result.success(ConfirmOutcome.Created(billId = billId))
    }

    /**
     * 提前结束：剩余期数不再算未来待还。
     *
     * @param extraFeeMinor 提前结清可能产生的手续费；大于 0 时生成一笔真实支出账单。
     */
    suspend fun finishEarly(
        installment: Installment,
        extraFeeMinor: Long = 0L,
        paidAt: Long = Time.nowMillis(),
    ): Result<Long?> {
        if (installment.isClosed) {
            return Result.success(installment.closedEarlyAt)
        }
        if (extraFeeMinor < 0L) {
            return Result.failure(IllegalArgumentException("费用不能为负"))
        }
        if (extraFeeMinor > 0L) {
            val draft = BillDraft(
                type = BillType.EXPENSE,
                amountMinor = extraFeeMinor,
                categoryId = installment.categoryId,
                paymentMethod = installment.paymentMethod,
                note = "${installment.name}（分期提前结清费用）",
                occurredAt = paidAt,
            )
            addBillUseCase(draft, clientKey = "installment:${installment.id}:fee")
                .getOrElse { return Result.failure(it) }
        }
        dao.closeEarly(installment.id, paidAt)
        return Result.success(paidAt)
    }

    private fun clientKeyOf(installmentId: Long, periodIndex: Int): String =
        "installment:$installmentId:$periodIndex"

    private fun billNoteOf(installment: Installment, periodIndex: Int, dueAt: Long): String =
        "${installment.name}（分期第 ${periodIndex + 1}/${installment.periodCount} 期 · ${Time.formatDate(dueAt)}）"
}

private fun InstallmentEntity.toDomain(): Installment = Installment(
    id = id,
    name = name,
    totalMinor = totalMinor,
    principalMinor = principalMinor,
    feeMinor = feeMinor,
    interestMinor = interestMinor,
    periodCount = periodCount,
    firstPeriodMinor = firstPeriodMinor,
    perPeriodMinor = perPeriodMinor,
    dueAt = dueAt,
    anchorDay = anchorDay,
    categoryId = categoryId,
    paymentMethod = PaymentMethod.fromDb(paymentMethod),
    enabled = enabled,
    closedEarlyAt = closedEarlyAt,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Installment.toEntity(
    createdAt: Long = this.createdAt,
    updatedAt: Long = this.updatedAt,
): InstallmentEntity = InstallmentEntity(
    id = id,
    name = name,
    totalMinor = totalMinor,
    principalMinor = principalMinor,
    feeMinor = feeMinor,
    interestMinor = interestMinor,
    periodCount = periodCount,
    firstPeriodMinor = firstPeriodMinor,
    perPeriodMinor = perPeriodMinor,
    dueAt = dueAt,
    anchorDay = anchorDay,
    categoryId = categoryId,
    paymentMethod = paymentMethod.dbValue,
    enabled = enabled,
    closedEarlyAt = closedEarlyAt,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun InstallmentPaymentEntity.toDomain(): InstallmentPayment = InstallmentPayment(
    id = id,
    installmentId = installmentId,
    periodIndex = periodIndex,
    billId = billId,
    amountMinor = amountMinor,
    dueAt = dueAt,
    paidAt = paidAt,
    note = note,
)