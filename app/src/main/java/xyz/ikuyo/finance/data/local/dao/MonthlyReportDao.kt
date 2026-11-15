package xyz.ikuyo.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.ikuyo.finance.data.local.entity.MonthlyReportEntity

@Dao
interface MonthlyReportDao {

    @Insert
    suspend fun insert(report: MonthlyReportEntity): Long

    /** 全部报告，最新月份、最高版本在前。 */
    @Query("SELECT * FROM monthly_reports ORDER BY yearMonth DESC, version DESC")
    fun observeAll(): Flow<List<MonthlyReportEntity>>

    /** 某个月份的所有版本（含被取代的旧版本）。 */
    @Query("SELECT * FROM monthly_reports WHERE yearMonth = :yearMonth ORDER BY version DESC")
    fun observeMonth(yearMonth: String): Flow<List<MonthlyReportEntity>>

    /** 某个月份当前的生效版本。 */
    @Query("SELECT * FROM monthly_reports WHERE yearMonth = :yearMonth AND superseded = 0 ORDER BY version DESC LIMIT 1")
    suspend fun findCurrent(yearMonth: String): MonthlyReportEntity?

    @Query("SELECT MAX(version) FROM monthly_reports WHERE yearMonth = :yearMonth")
    suspend fun maxVersion(yearMonth: String): Int?

    @Query("UPDATE monthly_reports SET superseded = 1 WHERE yearMonth = :yearMonth")
    suspend fun markAllSuperseded(yearMonth: String)

    /** 删除当前版本后，把剩下的最高版本恢复为生效版本。 */
    @Query(
        "UPDATE monthly_reports SET superseded = 0 WHERE id = (" +
            "SELECT id FROM monthly_reports WHERE yearMonth = :yearMonth ORDER BY version DESC LIMIT 1)",
    )
    suspend fun promoteLatest(yearMonth: String)

    @Query("UPDATE monthly_reports SET narrative = :narrative WHERE id = :id")
    suspend fun updateNarrative(id: Long, narrative: String?)

    @Query("SELECT * FROM monthly_reports WHERE id = :id")
    suspend fun findById(id: Long): MonthlyReportEntity?

    @Query("DELETE FROM monthly_reports WHERE id = :id")
    suspend fun delete(id: Long)
}