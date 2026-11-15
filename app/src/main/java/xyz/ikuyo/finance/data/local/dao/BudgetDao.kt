package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.BudgetEntity

@Dao
interface BudgetDao {

    @Query(
        """
        SELECT * FROM budgets
        WHERE periodStart <= :now AND periodEnd >= :now
        ORDER BY scope ASC, id ASC
        """
    )
    fun observeActive(now: Long): Flow<List<BudgetEntity>>

    @Query(
        """
        SELECT * FROM budgets
        WHERE scope = 'TOTAL' AND periodStart = :periodStart
        ORDER BY id DESC LIMIT 1
        """
    )
    suspend fun findTotal(periodStart: Long): BudgetEntity?

    @Query(
        """
        SELECT * FROM budgets
        WHERE scope = 'CATEGORY' AND categoryId = :categoryId AND periodStart = :periodStart
        ORDER BY id DESC LIMIT 1
        """
    )
    suspend fun findCategory(categoryId: Long, periodStart: Long): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: BudgetEntity): Long

    @Update
    suspend fun update(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun delete(id: Long)
}