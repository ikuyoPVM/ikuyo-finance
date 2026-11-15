package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.BillEntity

/** 账单 + 分类名，用于列表展示（避免 N+1 查询）。 */
data class BillWithCategory(
    @androidx.room.Embedded val bill: BillEntity,
    val categoryName: String?,
    val categoryEmoji: String?,
)

@Dao
interface BillDao {

    @Query(
        """
        SELECT b.*, c.name AS categoryName, c.emoji AS categoryEmoji
        FROM bills b
        LEFT JOIN categories c ON c.id = b.categoryId
        WHERE b.deleted = 0
        ORDER BY b.occurredAt DESC, b.id DESC
        """
    )
    fun observeActive(): Flow<List<BillWithCategory>>

    @Query(
        """
        SELECT b.*, c.name AS categoryName, c.emoji AS categoryEmoji
        FROM bills b
        LEFT JOIN categories c ON c.id = b.categoryId
        WHERE b.deleted = 1
        ORDER BY b.deletedAt DESC, b.id DESC
        """
    )
    fun observeTrash(): Flow<List<BillWithCategory>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bill: BillEntity): Long

    @Update
    suspend fun update(bill: BillEntity)

    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BillEntity?

    @Query("SELECT * FROM bills WHERE clientKey = :clientKey LIMIT 1")
    suspend fun findByClientKey(clientKey: String): BillEntity?

    @Query("SELECT * FROM bills WHERE deleted = 0 ORDER BY id DESC LIMIT 1")
    suspend fun latestActive(): BillEntity?

    @Query("UPDATE bills SET deleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("UPDATE bills SET deleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun purge(id: Long)

    @Query("DELETE FROM bills WHERE deleted = 1")
    suspend fun purgeAllTrash()

    @Query("SELECT COUNT(*) FROM bills WHERE deleted = 1")
    suspend fun countTrash(): Int
}