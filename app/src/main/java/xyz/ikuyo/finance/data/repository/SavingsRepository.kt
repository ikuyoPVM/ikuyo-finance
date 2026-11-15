package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.SavingsDao
import xyz.ikuyo.finance.data.local.entity.SavingsEntryEntity
import xyz.ikuyo.finance.data.local.entity.SavingsGoalEntity
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.SavingsEntry
import xyz.ikuyo.finance.domain.model.SavingsEntryType
import xyz.ikuyo.finance.domain.model.SavingsGoal
import xyz.ikuyo.finance.domain.model.SavingsGoalStatus
import xyz.ikuyo.finance.domain.usecase.SavingsLedger

/**
 * 储蓄目标与流水的读写。
 *
 * 这一层刻意不碰 [xyz.ikuyo.finance.domain.usecase.AddBillUseCase]：储蓄是资金划转，
 * 不应该产生账单。金额合法性全部走 [SavingsLedger]，避免界面和仓储各判一套。
 */
class SavingsRepository(
    private val dao: SavingsDao,
    private val categoryRepository: CategoryRepository,
) {

    fun observeGoals(): Flow<List<SavingsGoal>> =
        combine(dao.observeGoals(), categoryRepository.observeAll()) { rows, categories ->
            val byId = categories.associateBy { it.id }
            rows.map { it.toDomain(byId[it.categoryId]) }
        }

    fun observeEntries(): Flow<List<SavingsEntry>> =
        dao.observeEntries().map { rows -> rows.map { it.toDomain() } }

    /** 储蓄余额（所有目标合计），首页「储蓄余额」直接用这个，不再单独存一份数。 */
    val balance: Flow<Long> =
        dao.observeEntries().map { rows -> SavingsLedger.balance(rows.map { it.toDomain() }) }

    suspend fun findById(id: Long): SavingsGoal? {
        val row = dao.findById(id) ?: return null
        return row.toDomain(categoryOf(row.categoryId))
    }

    suspend fun entriesOf(goalId: Long): List<SavingsEntry> =
        dao.entriesOf(goalId).map { it.toDomain() }

    suspend fun save(goal: SavingsGoal): Result<Long> {
        return runCatching {
            SavingsLedger.validate(goal)?.let { throw IllegalArgumentException(it) }
            val now = Time.nowMillis()
            if (goal.id > 0L) {
                dao.update(
                    id = goal.id,
                    name = goal.name.trim(),
                    targetMinor = goal.targetMinor,
                    dueAt = goal.dueAt,
                    note = goal.note.trim(),
                    categoryId = goal.categoryId,
                    updatedAt = now,
                )
                goal.id
            } else {
                dao.insert(goal.toEntity(createdAt = now, updatedAt = now))
            }
        }
    }

    suspend fun setStatus(goal: SavingsGoal, status: SavingsGoalStatus): Result<Unit> = runCatching {
        dao.setStatus(goal.id, status.dbValue, Time.nowMillis())
    }

    suspend fun delete(goal: SavingsGoal): Result<Unit> = runCatching {
        dao.deleteEntriesOf(goal.id)
        dao.delete(goal.id)
    }

    /** 存入：可用余额减少、储蓄余额增加，总余额不变；不生成账单。 */
    suspend fun deposit(
        goal: SavingsGoal,
        amountMinor: Long,
        occurredAt: Long = Time.nowMillis(),
        note: String = "",
    ): Result<Long> = runCatching {
        val entries = dao.entries().map { it.toDomain() }
        SavingsLedger.validateMovement(goal, entries, SavingsEntryType.DEPOSIT, amountMinor)
            ?.let { throw IllegalArgumentException(it) }
        val id = dao.insertEntry(
            SavingsEntryEntity(
                goalId = goal.id,
                type = SavingsEntryType.DEPOSIT.dbValue,
                amountMinor = amountMinor,
                occurredAt = occurredAt,
                note = note.trim(),
            )
        )
        if (goal.status == SavingsGoalStatus.ACTIVE) {
            val saved = SavingsLedger.savedOf(goal.id, dao.entries().map { it.toDomain() })
            if (saved >= goal.targetMinor) {
                dao.setStatus(goal.id, SavingsGoalStatus.COMPLETED.dbValue, Time.nowMillis())
            }
        }
        id
    }

    /** 取出：储蓄余额减少、可用余额增加；计划书允许随时取出，只校验不超过已存金额。 */
    suspend fun withdraw(
        goal: SavingsGoal,
        amountMinor: Long,
        occurredAt: Long = Time.nowMillis(),
        note: String = "",
    ): Result<Long> = runCatching {
        val entries = dao.entries().map { it.toDomain() }
        SavingsLedger.validateMovement(goal, entries, SavingsEntryType.WITHDRAW, amountMinor)
            ?.let { throw IllegalArgumentException(it) }
        if (goal.status == SavingsGoalStatus.COMPLETED) {
            dao.setStatus(goal.id, SavingsGoalStatus.ACTIVE.dbValue, Time.nowMillis())
        }
        dao.insertEntry(
            SavingsEntryEntity(
                goalId = goal.id,
                type = SavingsEntryType.WITHDRAW.dbValue,
                amountMinor = amountMinor,
                occurredAt = occurredAt,
                note = note.trim(),
            )
        )
    }

    /** 目标之间划转：一边转出、一边转入，两笔流水同额同时刻。 */
    suspend fun transfer(
        from: SavingsGoal,
        to: SavingsGoal,
        amountMinor: Long,
        occurredAt: Long = Time.nowMillis(),
        note: String = "",
    ): Result<Unit> = runCatching {
        val entries = dao.entries().map { it.toDomain() }
        SavingsLedger.validateTransfer(from, to, entries, amountMinor)
            ?.let { throw IllegalArgumentException(it) }
        dao.insertEntry(
            SavingsEntryEntity(
                goalId = from.id,
                type = SavingsEntryType.TRANSFER_OUT.dbValue,
                amountMinor = amountMinor,
                occurredAt = occurredAt,
                note = note.ifBlank { "转到「${to.name}」" },
            )
        )
        dao.insertEntry(
            SavingsEntryEntity(
                goalId = to.id,
                type = SavingsEntryType.TRANSFER_IN.dbValue,
                amountMinor = amountMinor,
                occurredAt = occurredAt,
                note = note.ifBlank { "来自「${from.name}」" },
            )
        )
        val fromSaved = SavingsLedger.savedOf(from.id, dao.entries().map { it.toDomain() })
        if (fromSaved >= from.targetMinor && from.status == SavingsGoalStatus.ACTIVE) {
            dao.setStatus(from.id, SavingsGoalStatus.COMPLETED.dbValue, Time.nowMillis())
        }
        val toSaved = SavingsLedger.savedOf(to.id, dao.entries().map { it.toDomain() })
        if (toSaved >= to.targetMinor && to.status == SavingsGoalStatus.ACTIVE) {
            dao.setStatus(to.id, SavingsGoalStatus.COMPLETED.dbValue, Time.nowMillis())
        }
    }

    private suspend fun categoryOf(categoryId: Long?): Category? {
        if (categoryId == null) return null
        return categoryRepository.findById(categoryId)
    }
}

private fun SavingsGoalEntity.toDomain(category: Category?): SavingsGoal = SavingsGoal(
    id = id,
    name = name,
    targetMinor = targetMinor,
    dueAt = dueAt,
    note = note,
    categoryId = categoryId,
    status = SavingsGoalStatus.fromDb(status),
    createdAt = createdAt,
    updatedAt = updatedAt,
    categoryName = category?.name,
    categoryEmoji = category?.emoji ?: "🏷️",
)

private fun SavingsGoal.toEntity(createdAt: Long, updatedAt: Long): SavingsGoalEntity = SavingsGoalEntity(
    id = id,
    name = name.trim(),
    targetMinor = targetMinor,
    dueAt = dueAt,
    note = note.trim(),
    categoryId = categoryId,
    status = status.dbValue,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun SavingsEntryEntity.toDomain(): SavingsEntry = SavingsEntry(
    id = id,
    goalId = goalId,
    type = SavingsEntryType.fromDb(type),
    amountMinor = amountMinor,
    occurredAt = occurredAt,
    note = note,
)