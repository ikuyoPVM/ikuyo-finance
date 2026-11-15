package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.BudgetDao
import xyz.ikuyo.finance.data.local.entity.BudgetChangeEntity
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetChange
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope

/**
 * 预算仓储（阶段 2）。
 *
 * 预算以「规则」形式保存：金额 + 周期类型（每月/每周/每日/自定义）+ 是否结转 +
 * 是否启用。具体区间由 [xyz.ikuyo.finance.domain.usecase.BudgetPeriods] 运行时
 * 推导，因此规则会自动滚动到新周期；每次金额变化都会写入 budget_changes 历史，
 * 且永远不会修改任何历史账单。
 */
class BudgetRepository(private val dao: BudgetDao) {

    fun observeAll(): Flow<List<Budget>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeRecentChanges(limit: Int = 20): Flow<List<BudgetChange>> =
        dao.observeChanges(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun total(): Budget? = dao.findTotal()?.toDomain()

    suspend fun category(categoryId: Long): Budget? = dao.findCategory(categoryId)?.toDomain()

    suspend fun tag(tagId: Long): Budget? = dao.findTag(tagId)?.toDomain()

    suspend fun specials(): List<Budget> = dao.findSpecials().map { it.toDomain() }

    suspend fun findById(id: Long): Budget? = dao.findById(id)?.toDomain()

    /** 设置/更新总预算；金额变化会写一条变更历史。 */
    suspend fun setTotal(
        amountMinor: Long,
        periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
        carryOver: Boolean = false,
        customStart: Long? = null,
        customEnd: Long? = null,
        note: String? = null,
    ): Long {
        val now = Time.nowMillis()
        val existing = dao.findTotal()
        val id = if (existing == null) {
            dao.insert(
                BudgetEntity(
                    scope = BudgetScope.TOTAL.dbValue,
                    categoryId = null,
                    amountMinor = amountMinor,
                    periodType = periodType.dbValue,
                    carryOver = carryOver,
                    customStart = customStart,
                    customEnd = customEnd,
                    note = note,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else {
            dao.update(
                existing.copy(
                    amountMinor = amountMinor,
                    periodType = periodType.dbValue,
                    carryOver = carryOver,
                    customStart = customStart,
                    customEnd = customEnd,
                    note = note ?: existing.note,
                    updatedAt = now,
                )
            )
            existing.id
        }
        recordChange(
            budgetId = id,
            scope = BudgetScope.TOTAL,
            categoryId = null,
            targetName = null,
            oldAmountMinor = existing?.amountMinor,
            newAmountMinor = amountMinor,
            periodType = periodType,
            at = now,
        )
        return id
    }

    /** 清除总预算（历史账单与其他预算不受影响）。 */
    suspend fun clearTotal() {
        val existing = dao.findTotal() ?: return
        val now = Time.nowMillis()
        dao.delete(existing.id)
        recordChange(
            budgetId = existing.id,
            scope = BudgetScope.TOTAL,
            categoryId = null,
            targetName = null,
            oldAmountMinor = existing.amountMinor,
            newAmountMinor = null,
            periodType = BudgetPeriodType.fromDb(existing.periodType),
            at = now,
        )
    }

    /**
     * 设置/清除分类预算。[amountMinor] 为 null 或 <= 0 时清除。
     * [categoryName] 仅用于历史快照，分类改名后旧记录仍可读。
     */
    suspend fun setCategoryBudget(
        categoryId: Long,
        categoryName: String?,
        amountMinor: Long?,
        periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
        note: String? = null,
    ) {
        val now = Time.nowMillis()
        val existing = dao.findCategory(categoryId)
        if (amountMinor == null || amountMinor <= 0L) {
            if (existing == null) return
            dao.delete(existing.id)
            recordChange(
                budgetId = existing.id,
                scope = BudgetScope.CATEGORY,
                categoryId = categoryId,
                targetName = categoryName,
                oldAmountMinor = existing.amountMinor,
                newAmountMinor = null,
                periodType = BudgetPeriodType.fromDb(existing.periodType),
                at = now,
            )
            return
        }
        val id = if (existing == null) {
            dao.insert(
                BudgetEntity(
                    scope = BudgetScope.CATEGORY.dbValue,
                    categoryId = categoryId,
                    amountMinor = amountMinor,
                    periodType = periodType.dbValue,
                    note = note,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else {
            dao.update(
                existing.copy(
                    amountMinor = amountMinor,
                    periodType = periodType.dbValue,
                    customStart = null,
                    customEnd = null,
                    note = note ?: existing.note,
                    updatedAt = now,
                )
            )
            existing.id
        }
        recordChange(
            budgetId = id,
            scope = BudgetScope.CATEGORY,
            categoryId = categoryId,
            targetName = categoryName,
            oldAmountMinor = existing?.amountMinor,
            newAmountMinor = amountMinor,
            periodType = periodType,
            at = now,
        )
    }

    /** 设置/清除标签预算。[amountMinor] 为 null 或 <= 0 时清除。 */
    suspend fun setTagBudget(
        tagId: Long,
        tagName: String?,
        amountMinor: Long?,
        periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    ) {
        val now = Time.nowMillis()
        val existing = dao.findTag(tagId)
        if (amountMinor == null || amountMinor <= 0L) {
            if (existing == null) return
            dao.delete(existing.id)
            recordChange(
                budgetId = existing.id,
                scope = BudgetScope.TAG,
                categoryId = null,
                targetName = tagName,
                oldAmountMinor = existing.amountMinor,
                newAmountMinor = null,
                periodType = BudgetPeriodType.fromDb(existing.periodType),
                at = now,
            )
            return
        }
        val id = if (existing == null) {
            dao.insert(
                BudgetEntity(
                    scope = BudgetScope.TAG.dbValue,
                    tagId = tagId,
                    amountMinor = amountMinor,
                    periodType = periodType.dbValue,
                    name = tagName,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else {
            dao.update(
                existing.copy(
                    amountMinor = amountMinor,
                    periodType = periodType.dbValue,
                    customStart = null,
                    customEnd = null,
                    name = tagName ?: existing.name,
                    updatedAt = now,
                )
            )
            existing.id
        }
        recordChange(
            budgetId = id,
            scope = BudgetScope.TAG,
            categoryId = null,
            targetName = tagName,
            oldAmountMinor = existing?.amountMinor,
            newAmountMinor = amountMinor,
            periodType = periodType,
            at = now,
        )
    }

    /**
     * 新建或更新一条「专项预算」（带名称、自定义区间，可暂停）。
     * 返回预算 id；[budget.id] 为 0 时视为新建。
     */
    suspend fun saveSpecial(budget: Budget): Long {
        val now = Time.nowMillis()
        val existing = if (budget.id > 0L) dao.findById(budget.id) else null
        val id = if (existing == null) {
            dao.insert(
                BudgetEntity(
                    scope = BudgetScope.SPECIAL.dbValue,
                    categoryId = budget.categoryId,
                    tagId = budget.tagId,
                    name = budget.name,
                    amountMinor = budget.amountMinor,
                    periodType = budget.periodType.dbValue,
                    carryOver = budget.carryOver,
                    customStart = budget.customStart,
                    customEnd = budget.customEnd,
                    enabled = budget.enabled,
                    note = budget.note,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else {
            dao.update(
                existing.copy(
                    categoryId = budget.categoryId,
                    tagId = budget.tagId,
                    name = budget.name ?: existing.name,
                    amountMinor = budget.amountMinor,
                    periodType = budget.periodType.dbValue,
                    carryOver = budget.carryOver,
                    customStart = budget.customStart,
                    customEnd = budget.customEnd,
                    enabled = budget.enabled,
                    note = budget.note,
                    updatedAt = now,
                )
            )
            existing.id
        }
        recordChange(
            budgetId = id,
            scope = BudgetScope.SPECIAL,
            categoryId = budget.categoryId,
            targetName = budget.name,
            oldAmountMinor = existing?.amountMinor,
            newAmountMinor = budget.amountMinor,
            periodType = budget.periodType,
            at = now,
        )
        return id
    }

    /** 暂停 / 恢复某个预算：暂停后不再产生任何提醒。 */
    suspend fun setEnabled(budgetId: Long, enabled: Boolean) {
        val existing = dao.findById(budgetId) ?: return
        dao.update(existing.copy(enabled = enabled, updatedAt = Time.nowMillis()))
    }

    /** 删除预算规则（不触碰任何历史账单；保留一条变更历史以便追溯）。 */
    suspend fun deleteBudget(budgetId: Long) {
        val existing = dao.findById(budgetId) ?: return
        val now = Time.nowMillis()
        dao.delete(existing.id)
        recordChange(
            budgetId = existing.id,
            scope = BudgetScope.fromDb(existing.scope),
            categoryId = existing.categoryId,
            targetName = existing.name,
            oldAmountMinor = existing.amountMinor,
            newAmountMinor = null,
            periodType = BudgetPeriodType.fromDb(existing.periodType),
            at = now,
        )
    }

    private suspend fun recordChange(
        budgetId: Long,
        scope: BudgetScope,
        categoryId: Long?,
        targetName: String?,
        oldAmountMinor: Long?,
        newAmountMinor: Long?,
        periodType: BudgetPeriodType,
        at: Long,
    ) {
        if (oldAmountMinor == newAmountMinor) return
        dao.insertChange(
            BudgetChangeEntity(
                budgetId = budgetId,
                scope = scope.dbValue,
                categoryId = categoryId,
                categoryName = targetName,
                oldAmountMinor = oldAmountMinor,
                newAmountMinor = newAmountMinor,
                periodType = periodType.dbValue,
                changedAt = at,
            )
        )
    }
}