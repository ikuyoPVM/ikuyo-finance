package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.DebtEntity
import xyz.ikuyo.finance.data.local.entity.DebtPaymentEntity

/**
 * 债务 / 借出（v7）。
 *
 * 与分期一致：这里没有任何会写 bills 的语句，账单只由仓储层走 AddBillUseCase 生成。
 */
@Dao
interface DebtDao {

    @Query("SELECT * FROM debts ORDER BY settledAt IS NOT NULL ASC, dueAt ASC, id ASC")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun findById(id: Long): DebtEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DebtEntity): Long

    @Query("UPDATE debts SET name = :name, type = :type, principalMinor = :principalMinor, dueAt = :dueAt, anchorDay = :anchorDay, categoryId = :categoryId, paymentMethod = :paymentMethod, note = :note, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(
        id: Long,
        name: String,
        type: String,
        principalMinor: Long,
        dueAt: Long,
        anchorDay: Int,
        categoryId: Long?,
        paymentMethod: String,
        note: String,
        updatedAt: Long,
    )

    @Query("UPDATE debts SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE debts SET settledAt = :settledAt, updatedAt = :settledAt WHERE id = :id")
    suspend fun settle(id: Long, settledAt: Long)

    @Query("UPDATE debts SET dueAt = :dueAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDueAt(id: Long, dueAt: Long, updatedAt: Long)

    @Query("DELETE FROM debts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM debt_payments ORDER BY paidAt DESC, id DESC")
    fun observePayments(): Flow<List<DebtPaymentEntity>>

    @Query("SELECT * FROM debt_payments WHERE debtId = :debtId ORDER BY paidAt ASC, id ASC")
    fun observePaymentsOf(debtId: Long): Flow<List<DebtPaymentEntity>>

    @Query("SELECT * FROM debt_payments WHERE debtId = :debtId ORDER BY paidAt ASC, id ASC")
    suspend fun paymentsOf(debtId: Long): List<DebtPaymentEntity>

    @Query("SELECT * FROM debt_payments WHERE debtId = :debtId AND paidAt = :paidAt LIMIT 1")
    suspend fun findPaymentAt(debtId: Long, paidAt: Long): DebtPaymentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(entity: DebtPaymentEntity): Long

    @Query("SELECT * FROM debt_payments WHERE billId = :billId LIMIT 1")
    suspend fun findByBillId(billId: Long): DebtPaymentEntity?
}