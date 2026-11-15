package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.FixedExpenseDao
import xyz.ikuyo.finance.data.local.entity.FixedExpenseEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpensePaymentEntity
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.FixedExpense
import xyz.ikuyo.finance.domain.model.FixedExpensePayment
import xyz.ikuyo.finance.domain.model.FixedPeriodType
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase
import xyz.ikuyo.finance.domain.usecase.FixedExpenseSchedule

/**
 * 确认付款的结果。
 *
 * - [Created]：本期第一次确认，已经写入一笔真实支出账单并推进到下一个周期。
 * - [AlreadyPaid]：这一期之前已经付过，本次什么都没改（幂等，重复点击不会多出账单或跳期）。
 */
sealed interface ConfirmOutcome {
    data class Created(val billId: Long) : ConfirmOutcome
    data class AlreadyPaid(val billId: Long?, val paidAt: Long) : ConfirmOutcome
}

/**
 * 固定支出仓储。
 *
 * 关键约束（阶段五计划书第 2 / 7 章）：
 * - 计划金额不是账单：只有 [confirmPayment] 才会写入 bills 表，其余操作都不产生任何账目。
 * - 确认付款按「计划 id + 到期日」做幂等：同一期重复点确认只落一笔账。
 * - 确认成功后计划自动推进到下一个周期，用户不需要手动改日期。
 */
class FixedExpenseRepository(
    private val dao: FixedExpenseDao,
    private val categoryRepository: CategoryRepository,
    private val addBillUseCase: AddBillUseCase,
) {

    /** 计划列表（含分类名，便于列表直接显示）。 */
    fun observeAll(): Flow<List<FixedExpense>> =
        combine(dao.observeAll(), categoryRepository.observeAll()) { expenses, categories ->
            val byId = categories.associateBy { it.id }
            expenses.map { it.toDomain(byId[it.categoryId]) }
        }

    fun observePayments(): Flow<List<FixedExpensePayment>> =
        dao.observePayments().map { list -> list.map { it.toDomain() } }

    fun observePaymentsOf(expenseId: Long): Flow<List<FixedExpensePayment>> =
        dao.observePaymentsOf(expenseId).map { list -> list.map { it.toDomain() } }

    suspend fun findById(id: Long): FixedExpense? = dao.findById(id)?.toDomain(null)

    /** 新增或更新计划。金额必须为正，名称不能为空。 */
    suspend fun save(expense: FixedExpense): Result<Long> {
        val name = expense.name.trim()
        if (name.isEmpty()) {
            return Result.failure(IllegalArgumentException("名称不能为空"))
        }
        if (expense.amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("金额必须大于 0"))
        }
        val now = Time.nowMillis()
        val entity = FixedExpenseEntity(
            id = expense.id,
            name = name,
            amountMinor = expense.amountMinor,
            categoryId = expense.categoryId,
            paymentMethod = expense.paymentMethod.dbValue,
            periodType = expense.periodType.dbValue,
            dueAt = expense.dueAt,
            anchorDay = expense.anchorDay.coerceIn(1, 31),
            customDays = expense.customDays.coerceAtLeast(1),
            isVariable = expense.isVariable,
            enabled = expense.enabled,
            note = expense.note.trim(),
            createdAt = if (expense.id == 0L) now else expense.createdAt,
            updatedAt = now,
        )
        val id = dao.insert(entity)
        return Result.success(if (id > 0L) id else expense.id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): Result<Unit> {
        if (dao.findById(id) == null) {
            return Result.failure(IllegalStateException("固定支出不存在"))
        }
        dao.setEnabled(id, enabled, Time.nowMillis())
        return Result.success(Unit)
    }

    /** 删除计划。历史账单不受影响；付款记录随计划一起删除（账单本身仍在账单列表里）。 */
    suspend fun delete(id: Long) {
        dao.delete(id)
    }

    /**
     * 确认某一期已付款：生成真实账单 + 记录付款 + 推进到期日。
     *
     * @param amountMinor 实际支付金额，浮动金额计划可在这里改写。
     * @param paidAt 付款时间（默认现在）。
     * @return 生成的账单 id。
     */
    suspend fun confirmPayment(
        expense: FixedExpense,
        amountMinor: Long = expense.amountMinor,
        paidAt: Long = Time.nowMillis(),
        note: String = "",
    ): Result<ConfirmOutcome> {
        if (amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("金额必须大于 0"))
        }
        val dueAt = expense.dueAt
        val clientKey = clientKeyOf(expense.id, dueAt)

        // 按期幂等：一个账期只允许确认一次。已经付过就原样返回，不写付款记录、不推进到期日，
        // 否则重复点击会多出一条付款记录，还会把计划跳到下一期、漏掉本来该付的那一期。
        dao.findPaymentOf(expense.id, dueAt)?.let { existing ->
            return Result.success(
                ConfirmOutcome.AlreadyPaid(billId = existing.billId, paidAt = existing.paidAt)
            )
        }

        val draft = BillDraft(
            type = BillType.EXPENSE,
            amountMinor = amountMinor,
            categoryId = expense.categoryId,
            paymentMethod = expense.paymentMethod,
            note = billNoteOf(expense, dueAt),
            occurredAt = paidAt,
        )
        val billId = addBillUseCase(draft, clientKey = clientKey)
            .getOrElse { return Result.failure(it) }

        dao.insertPayment(
            FixedExpensePaymentEntity(
                fixedExpenseId = expense.id,
                billId = billId,
                amountMinor = amountMinor,
                dueAt = dueAt,
                paidAt = paidAt,
                note = note.trim(),
            )
        )
        // 推进到期日：即使这一期是补记的，也只推进一期，避免一次确认跳过多期。
        val next = FixedExpenseSchedule.nextDue(expense)
        dao.update(
            FixedExpenseEntity(
                id = expense.id,
                name = expense.name,
                amountMinor = expense.amountMinor,
                categoryId = expense.categoryId,
                paymentMethod = expense.paymentMethod.dbValue,
                periodType = expense.periodType.dbValue,
                dueAt = next,
                anchorDay = expense.anchorDay,
                customDays = expense.customDays,
                isVariable = expense.isVariable,
                enabled = expense.enabled,
                note = expense.note,
                createdAt = expense.createdAt,
                updatedAt = Time.nowMillis(),
            )
        )
        return Result.success(ConfirmOutcome.Created(billId = billId))
    }

    /**
     * 标记本期未付款：不生成账单、不记支出，只把计划推进到下一期。
     *
     * 「这个月不续费了」「这期免了」这类情况用它。计划本身不是支出，所以跳过一期
     * 不该在账本里留下任何金额——否则等于凭空记了一笔消费。
     *
     * @return 推进后的下一次到期日。
     */
    suspend fun skipPeriod(expense: FixedExpense): Result<Long> {
        val dueAt = expense.dueAt
        dao.findPaymentOf(expense.id, dueAt)?.let {
            return Result.failure(IllegalStateException("这一期已经付过了，不能再标记未付款"))
        }
        val entity = dao.findById(expense.id)
            ?: return Result.failure(IllegalStateException("计划不存在"))
        val next = FixedExpenseSchedule.nextDue(expense)
        dao.update(entity.copy(dueAt = next, updatedAt = Time.nowMillis()))
        return Result.success(next)
    }

    private fun billNoteOf(expense: FixedExpense, dueAt: Long): String {
        val label = Time.formatFullDate(dueAt)
        return if (expense.note.isBlank()) {
            "${expense.name}（固定支出 · $label）"
        } else {
            "${expense.name}（固定支出 · $label）· ${expense.note}"
        }
    }

    private fun clientKeyOf(expenseId: Long, dueAt: Long): String = "fixed:$expenseId:$dueAt"
}

private fun FixedExpenseEntity.toDomain(category: Category?): FixedExpense = FixedExpense(
    id = id,
    name = name,
    amountMinor = amountMinor,
    categoryId = categoryId,
    paymentMethod = PaymentMethod.fromDb(paymentMethod),
    periodType = FixedPeriodType.fromDb(periodType),
    dueAt = dueAt,
    anchorDay = anchorDay,
    customDays = customDays,
    isVariable = isVariable,
    enabled = enabled,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    categoryName = category?.name,
    categoryEmoji = category?.emoji ?: "🏷️",
)

private fun FixedExpensePaymentEntity.toDomain(): FixedExpensePayment = FixedExpensePayment(
    id = id,
    fixedExpenseId = fixedExpenseId,
    billId = billId,
    amountMinor = amountMinor,
    dueAt = dueAt,
    paidAt = paidAt,
    note = note,
)

/** 供 UI 显示的金额文本（分 -> ¥x.xx）。 */
fun FixedExpense.amountText(): String = Money.format(amountMinor)