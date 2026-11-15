package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.BudgetDao
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetScope
import java.time.YearMonth

/**
 * 预算仓储。第一版周期固定为自然月；调整预算只影响后续计算，
 * 不修改任何历史账单。
 */
class BudgetRepository(private val dao: BudgetDao) {

    fun observeActive(now: Long = Time.nowMillis()): Flow<List<Budget>> =
        dao.observeActive(now).map { rows -> rows.map { it.toDomain() } }

    suspend fun setTotal(
        amountMinor: Long,
        month: YearMonth = YearMonth.now(Time.zone),
    ) {
        val start = Time.startOfMonth(month)
        val end = Time.endOfMonthInclusive(month)
        val existing = dao.findTotal(start)
        if (existing != null) {
            dao.update(existing.copy(amountMinor = amountMinor, periodEnd = end))
        } else {
            dao.insert(
                BudgetEntity(
                    scope = BudgetScope.TOTAL.dbValue,
                    categoryId = null,
                    amountMinor = amountMinor,
                    periodStart = start,
                    periodEnd = end,
                    createdAt = Time.nowMillis(),
                )
            )
        }
    }

    /** 清除本月总预算。 */
    suspend fun clearTotal(month: YearMonth = YearMonth.now(Time.zone)) {
        val existing = dao.findTotal(Time.startOfMonth(month))
        if (existing != null) dao.delete(existing.id)
    }

    /** amountMinor 为 null 表示清除该分类预算。 */
    suspend fun setCategoryBudget(
        categoryId: Long,
        amountMinor: Long?,
        month: YearMonth = YearMonth.now(Time.zone),
    ) {
        val start = Time.startOfMonth(month)
        val end = Time.endOfMonthInclusive(month)
        val existing = dao.findCategory(categoryId, start)
        when {
            amountMinor == null || amountMinor <= 0L -> if (existing != null) dao.delete(existing.id)
            existing != null -> dao.update(existing.copy(amountMinor = amountMinor, periodEnd = end))
            else -> dao.insert(
                BudgetEntity(
                    scope = BudgetScope.CATEGORY.dbValue,
                    categoryId = categoryId,
                    amountMinor = amountMinor,
                    periodStart = start,
                    periodEnd = end,
                    createdAt = Time.nowMillis(),
                )
            )
        }
    }
}