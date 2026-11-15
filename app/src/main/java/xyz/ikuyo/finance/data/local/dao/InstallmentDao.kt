package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.InstallmentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentPaymentEntity

@Dao
interface InstallmentDao {

    /** 启用中的计划排在前面，各自按最近到期日升序。 */
    @Query("SELECT * FROM installments ORDER BY enabled DESC, dueAt ASC")
    fun observeAll(): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installments WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): InstallmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: InstallmentEntity): Long

    @Update
    suspend fun update(entity: InstallmentEntity)

    @Query("UPDATE installments SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    /** 提前结束：记下时间点，剩余期数不再算未来待还。 */
    @Query("UPDATE installments SET closedEarlyAt = :closedAt, updatedAt = :closedAt WHERE id = :id")
    suspend fun closeEarly(id: Long, closedAt: Long)

    @Query("DELETE FROM installments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM installment_payments ORDER BY paidAt DESC")
    fun observePayments(): Flow<List<InstallmentPaymentEntity>>

    @Query("SELECT * FROM installment_payments WHERE installmentId = :installmentId ORDER BY periodIndex ASC")
    fun observePaymentsOf(installmentId: Long): Flow<List<InstallmentPaymentEntity>>

    /** 该计划某一期是否已经付过。配合唯一索引实现按期幂等。 */
    @Query("SELECT * FROM installment_payments WHERE installmentId = :installmentId AND periodIndex = :periodIndex LIMIT 1")
    suspend fun findPaymentOf(installmentId: Long, periodIndex: Int): InstallmentPaymentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(entity: InstallmentPaymentEntity): Long

    @Query("SELECT * FROM installment_payments WHERE billId = :billId LIMIT 1")
    suspend fun findByBillId(billId: Long): InstallmentPaymentEntity?
}