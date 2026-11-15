package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.BudgetChangeEntity
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.local.entity.BudgetTemplateEntity

@Dao
interface BudgetDao {

    /** 全部预算规则（总预算 + 分类 + 标签 + 专项）。区间在运行时推导。 */
    @Query("SELECT * FROM budgets ORDER BY scope ASC, id ASC")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE scope = 'TOTAL' ORDER BY id DESC LIMIT 1")
    suspend fun findTotal(): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE scope = 'CATEGORY' AND categoryId = :categoryId ORDER BY id DESC LIMIT 1")
    suspend fun findCategory(categoryId: Long): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE scope = 'TAG' AND tagId = :tagId ORDER BY id DESC LIMIT 1")
    suspend fun findTag(tagId: Long): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE scope = 'SPECIAL' ORDER BY id DESC")
    suspend fun findSpecials(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: BudgetEntity): Long

    @Update
    suspend fun update(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- 变更历史 ----

    @Insert
    suspend fun insertChange(change: BudgetChangeEntity): Long

    @Query("SELECT * FROM budget_changes ORDER BY changedAt DESC, id DESC LIMIT :limit")
    fun observeChanges(limit: Int): Flow<List<BudgetChangeEntity>>

    @Query("DELETE FROM budget_changes")
    suspend fun clearChanges()
}

@Dao
interface BudgetTemplateDao {

    @Query("SELECT * FROM budget_templates ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<BudgetTemplateEntity>>

    @Query("SELECT * FROM budget_templates WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BudgetTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: BudgetTemplateEntity): Long

    @Query("DELETE FROM budget_templates WHERE id = :id")
    suspend fun delete(id: Long)
}