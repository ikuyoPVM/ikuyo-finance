package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.data.local.entity.TagEntity

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id AND isDefault = 0")
    suspend fun deleteCustom(id: Long)
}

/** 标签使用次数（按标签统计关联账单数）。 */
data class TagUsage(
    val tagId: Long,
    val count: Int,
)

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    /** 删除标签只解除关联，账单本身不受影响。 */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(crossRef: BillTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(crossRefs: List<BillTagCrossRef>)

    @Query("DELETE FROM bill_tags WHERE billId = :billId AND tagId = :tagId")
    suspend fun unlink(billId: Long, tagId: Long)

    @Query("DELETE FROM bill_tags WHERE billId = :billId")
    suspend fun clearBillLinks(billId: Long)

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN bill_tags bt ON bt.tagId = t.id
        WHERE bt.billId = :billId
        ORDER BY t.name ASC
        """
    )
    suspend fun tagsOf(billId: Long): List<TagEntity>

    @Query("SELECT tagId FROM bill_tags WHERE billId = :billId")
    suspend fun tagIdsOf(billId: Long): List<Long>

    @Query("SELECT * FROM bill_tags")
    fun observeAllLinks(): Flow<List<BillTagCrossRef>>

    @Query("SELECT billId FROM bill_tags WHERE tagId = :tagId")
    fun observeBillIdsOf(tagId: Long): Flow<List<Long>>

    @Query("SELECT tagId, COUNT(*) AS count FROM bill_tags GROUP BY tagId")
    fun observeUsage(): Flow<List<TagUsage>>
}