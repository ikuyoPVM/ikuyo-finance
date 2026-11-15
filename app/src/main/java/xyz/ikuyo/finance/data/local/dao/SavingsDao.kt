package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.SavingsEntryEntity
import xyz.ikuyo.finance.data.local.entity.SavingsGoalEntity

@Dao
interface SavingsDao {

    @Query("SELECT * FROM savings_goals ORDER BY status IN ('ACTIVE','PAUSED') DESC, dueAt ASC, id ASC")
    fun observeGoals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun findById(id: Long): SavingsGoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SavingsGoalEntity): Long

    @Query(
        """
        UPDATE savings_goals SET name = :name, targetMinor = :targetMinor, dueAt = :dueAt,
            note = :note, categoryId = :categoryId, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: Long,
        name: String,
        targetMinor: Long,
        dueAt: Long,
        note: String,
        categoryId: Long?,
        updatedAt: Long,
    )

    @Query("UPDATE savings_goals SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, updatedAt: Long)

    @Query("DELETE FROM savings_goals WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM savings_entries ORDER BY occurredAt DESC, id DESC")
    fun observeEntries(): Flow<List<SavingsEntryEntity>>

    @Query("SELECT * FROM savings_entries ORDER BY occurredAt DESC, id DESC")
    suspend fun entries(): List<SavingsEntryEntity>

    @Query("SELECT * FROM savings_entries WHERE goalId = :goalId ORDER BY occurredAt DESC, id DESC")
    suspend fun entriesOf(goalId: Long): List<SavingsEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entity: SavingsEntryEntity): Long

    @Query("DELETE FROM savings_entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("DELETE FROM savings_entries WHERE goalId = :goalId")
    suspend fun deleteEntriesOf(goalId: Long)
}