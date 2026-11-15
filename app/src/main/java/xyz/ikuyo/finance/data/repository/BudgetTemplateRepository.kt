package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.BudgetTemplateDao
import xyz.ikuyo.finance.data.local.entity.BudgetTemplateEntity
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.BudgetTemplate

/** 预算模板仓储（阶段 2 模块一）：保存一套规则，之后一键创建新预算。 */
class BudgetTemplateRepository(private val dao: BudgetTemplateDao) {

    fun observeAll(): Flow<List<BudgetTemplate>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun find(id: Long): BudgetTemplate? = dao.findById(id)?.toDomain()

    suspend fun create(template: BudgetTemplate): Long = dao.insert(
        BudgetTemplateEntity(
            name = template.name,
            scope = template.scope.dbValue,
            categoryId = template.categoryId,
            tagId = template.tagId,
            amountMinor = template.amountMinor,
            periodType = template.periodType.dbValue,
            carryOver = template.carryOver,
            note = template.note,
            createdAt = Time.nowMillis(),
        )
    )

    suspend fun delete(id: Long) = dao.delete(id)

    /** 把模板转成一条可编辑的新预算（id = 0，等待用户确认后落库）。 */
    fun instantiate(template: BudgetTemplate): Budget = Budget(
        id = 0L,
        scope = template.scope,
        categoryId = template.categoryId,
        tagId = template.tagId,
        name = template.name,
        amountMinor = template.amountMinor,
        periodType = template.periodType,
        carryOver = template.carryOver,
        note = template.note,
        enabled = true,
        createdAt = 0L,
        updatedAt = 0L,
    )

    companion object {
        /** 由一条已有预算生成模板（用于「保存为模板」）。 */
        fun fromBudget(budget: Budget, name: String): BudgetTemplate = BudgetTemplate(
            name = name,
            scope = if (budget.scope == BudgetScope.TOTAL) BudgetScope.SPECIAL else budget.scope,
            categoryId = budget.categoryId,
            tagId = budget.tagId,
            amountMinor = budget.amountMinor,
            periodType = budget.periodType,
            carryOver = budget.carryOver,
            note = budget.note,
        )
    }
}

/** 让 [BudgetPeriodType] 在模板界面里有统一显示文案时不必重复写 when。 */
internal fun BudgetPeriodType.displayName(): String = label