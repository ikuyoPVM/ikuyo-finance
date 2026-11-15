package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.FixedExpenseEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpensePaymentEntity

@Dao
interface FixedExpenseDao {

    /** 全部计划：启用的排前面，再按到期日。 */
    @Query("SELECT * FROM fixed_expenses ORDER BY enabled DESC, dueAt ASC, id ASC")
    fun observeAll(): Flow<List<FixedExpenseEntity>>

    @Query("SELECT * FROM fixed_expenses WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): FixedExpenseEntity?

    @Query("SELECT * FROM fixed_expenses WHERE enabled = 1 AND dueAt <= :untilMillis ORDER BY dueAt ASC")
    suspend fun findDueBefore(untilMillis: Long): List<FixedExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: FixedExpenseEntity): Long

    @Update
    suspend fun update(expense: FixedExpenseEntity)

    @Query("UPDATE fixed_expenses SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM fixed_expenses WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- 付款记录 ----

    @Query("SELECT * FROM fixed_expense_payments ORDER BY paidAt DESC, id DESC")
    fun observePayments(): Flow<List<FixedExpensePaymentEntity>>

    @Query("SELECT * FROM fixed_expense_payments WHERE fixedExpenseId = :expenseId ORDER BY paidAt DESC, id DESC")
    fun observePaymentsOf(expenseId: Long): Flow<List<FixedExpensePaymentEntity>>

    @Query("SELECT * FROM fixed_expense_payments WHERE fixedExpenseId = :expenseId ORDER BY paidAt DESC, id DESC LIMIT 1")
    suspend fun latestPayment(expenseId: Long): FixedExpensePaymentEntity?

    @Insert
    suspend fun insertPayment(payment: FixedExpensePaymentEntity): Long

    @Query("SELECT * FROM fixed_expense_payments WHERE billId = :billId LIMIT 1")
    suspend fun findByBillId(billId: Long): FixedExpensePaymentEntity?

    /** 该计划的某一次应付日期是否已经付过。一个账期只允许一条付款记录。 */
    @Query("SELECT * FROM fixed_expense_payments WHERE fixedExpenseId = :expenseId AND dueAt = :dueAt LIMIT 1")
    suspend fun findPaymentOf(expenseId: Long, dueAt: Long): FixedExpensePaymentEntity?
}