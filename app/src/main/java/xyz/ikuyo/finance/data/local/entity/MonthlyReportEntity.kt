package xyz.ikuyo.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 月度总结 / 历史月报（v4 起）。
 *
 * 设计要点：
 * 1. 一条记录 = 某年某月的一版报告；重新生成不覆盖旧记录，只把旧记录标记 `superseded`，
 *    版本号自增，因此「历史上某月当时是怎么写的」永远可追溯，不会被后续账单静默改写。
 * 2. 报告里的所有数字都在生成时由本地统计服务算出并落库（[payloadJson]），
 *    展示时不再重新计算，避免账单变化后旧报告数字漂移。
 * 3. [narrative] 只存 AI 写的解读文字，可选；没有 AI 时报告依然完整可用。
 */
@Entity(
    tableName = "monthly_reports",
    indices = [Index(value = ["yearMonth", "version"], unique = true)],
)
data class MonthlyReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 目标月份，格式 yyyy-MM。 */
    val yearMonth: String,
    /** 同月份的版本号，从 1 开始。 */
    val version: Int,
    val generatedAt: Long,
    /** 统计区间（含首尾），用于展示「数据范围」。 */
    val dataStart: Long,
    val dataEndInclusive: Long,
    val billCount: Int,
    val netExpenseMinor: Long,
    val incomeMinor: Long,
    val overspendMinor: Long,
    /** 九段正文的结构化 JSON。 */
    val payloadJson: String,
    /** AI 解读文本，可为空。 */
    @ColumnInfo(defaultValue = "NULL") val narrative: String? = null,
    /** 已被同月更新版本取代的旧报告。 */
    @ColumnInfo(defaultValue = "0") val superseded: Boolean = false,
)