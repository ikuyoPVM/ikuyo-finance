package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.BillOperationEntity

@Dao
interface OperationDao {

    @Insert
    suspend fun insert(operation: BillOperationEntity): Long

    @Query("SELECT * FROM bill_operations ORDER BY operatedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BillOperationEntity>>

    @Query("SELECT * FROM bill_operations WHERE billId = :billId ORDER BY operatedAt DESC, id DESC")
    fun observeForBill(billId: Long): Flow<List<BillOperationEntity>>

    /** 最近一条还允许撤销的历史（新增/修改/删除且未被撤销）。 */
    @Query(
        """
        SELECT * FROM bill_operations
        WHERE undone = 0 AND type IN ('CREATE', 'UPDATE', 'DELETE')
        ORDER BY operatedAt DESC, id DESC LIMIT 1
        """
    )
    suspend fun findLastUndoable(): BillOperationEntity?

    @Query("SELECT * FROM bill_operations WHERE id = :id")
    suspend fun findById(id: Long): BillOperationEntity?

    @Query("UPDATE bill_operations SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query("DELETE FROM bill_operations")
    suspend fun clear()
}