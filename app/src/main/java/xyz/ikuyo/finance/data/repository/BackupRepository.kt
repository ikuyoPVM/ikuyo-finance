package xyz.ikuyo.finance.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.backup.BackupCodec
import xyz.ikuyo.finance.data.backup.BackupData
import xyz.ikuyo.finance.data.backup.BackupFormat
import xyz.ikuyo.finance.data.backup.BackupFormatException
import xyz.ikuyo.finance.data.backup.BackupMeta
import xyz.ikuyo.finance.data.backup.BackupSettingsPort
import xyz.ikuyo.finance.data.backup.ParsedBackup
import xyz.ikuyo.finance.data.local.IkuyoDatabase
import xyz.ikuyo.finance.data.local.DefaultCategories
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.CategoryEntity

/** 恢复后给用户看的结果（计划书 §3 第 9 步）。 */
data class RestoreReport(
    val counts: Map<String, Int>,
    val billCount: Int,
    val categoryCount: Int,
    val netExpenseMinor: Long,
    /** 支出、收入、退款分别汇总：只看一个合计会把收入和支出混在一起，无法核对。 */
    val expenseMinor: Long = 0L,
    val incomeMinor: Long = 0L,
    val refundMinor: Long = 0L,
    val settingsRestored: Boolean,
    val settingsWarning: String?,
    val warnings: List<String>,
    val durationMillis: Long,
)

/**
 * 备份与恢复的落库实现。
 *
 * 两条铁律（计划书 §3）：
 * 1. 恢复只在同一个事务里做「清空 + 重建」，中途失败整笔回滚，不会留下半套数据。
 * 2. 恢复前先跑结构检查、恢复后先跑自检，两者都在提交前完成。
 */
class BackupRepository(
    private val database: IkuyoDatabase,
    private val settingsPort: BackupSettingsPort,
) {

    // ------------------------------------------------------------- export

    /** 把当前账本的全部数据收成一个备份对象（含软删除账单与操作历史）。 */
    suspend fun collect(): BackupData {
        val billDao = database.billDao()
        val tagDao = database.tagDao()
        val budgetDao = database.budgetDao()
        val reportDao = database.monthlyReportDao()

        return BackupData(
            bills = billDao.observeActive().first().map { it.bill } +
                billDao.observeTrash().first().map { it.bill },
            billTags = tagDao.observeAllLinks().first(),
            operations = database.operationDao().observeRecent(OPERATION_LIMIT).first(),
            categories = database.categoryDao().getAll(),
            tags = tagDao.getAll(),
            budgets = budgetDao.observeAll().first(),
            budgetTemplates = database.budgetTemplateDao().observeAll().first(),
            budgetChanges = budgetDao.observeChanges(CHANGE_LIMIT).first(),
            fixedExpenses = database.fixedExpenseDao().observeAll().first(),
            fixedExpensePayments = database.fixedExpenseDao().observePayments().first(),
            installments = database.installmentDao().observeAll().first(),
            installmentPayments = database.installmentDao().observePayments().first(),
            debts = database.debtDao().observeAll().first(),
            debtPayments = database.debtDao().observePayments().first(),
            savingsGoals = database.savingsDao().observeGoals().first(),
            savingsEntries = database.savingsDao().entries(),
            monthlyReports = reportDao.observeAll().first(),
            settings = settingsPort.export(),
        )
    }

    /** 生成可直接保存的备份文本（未加密）。 */
    suspend fun exportJson(appVersion: String): String {
        val data = collect()
        val meta = BackupMeta(
            appVersion = appVersion,
            createdAt = Time.nowMillis(),
            counts = data.counts(),
        )
        return BackupCodec.encode(data, meta)
    }

    // ------------------------------------------------------------ restore

    /**
     * 用备份内容替换当前账本。
     *
     * 结构检查不通过直接拒绝；写入在事务内，任何异常都会回滚到恢复前的状态。
     */
    suspend fun restore(parsed: ParsedBackup): Result<RestoreReport> = runCatching {
        val issues = BackupCodec.issuesOf(parsed.data)
        if (issues.isBlocking) {
            throw BackupFormatException("备份结构检查未通过：" + issues.errors.joinToString("；"))
        }
        val startedAt = Time.nowMillis()
        val expected = expectedSummary(parsed.data)

        database.withTransaction {
            clearAllTables()
            insertAll(parsed.data)
            val actual = currentSummary()
            if (actual != expected) {
                throw BackupFormatException(
                    "恢复自检未通过：写入后账单 $actual 与备份记录 $expected 不一致，已回滚",
                )
            }
        }

        // 设置存在 DataStore 里，不能进数据库事务；失败只作为提示，不影响账本数据。
        val settingsError = runCatching { settingsPort.restore(parsed.data.settings.values) }
            .exceptionOrNull()

        RestoreReport(
            counts = parsed.data.counts(),
            billCount = parsed.data.bills.size,
            categoryCount = parsed.data.categories.size,
            netExpenseMinor = parsed.data.bills.sumOf { it.cnyAmountMinor },
            expenseMinor = sumByType(parsed.data.bills, "EXPENSE"),
            incomeMinor = sumByType(parsed.data.bills, "INCOME"),
            refundMinor = sumByType(parsed.data.bills, "REFUND"),
            settingsRestored = settingsError == null,
            settingsWarning = settingsError?.message,
            warnings = issues.warnings,
            durationMillis = Time.nowMillis() - startedAt,
        )
    }

    /**
     * 清空账本数据（计划书 §7「清空数据必须二次确认」）。
     *
     * 同样在一个事务里完成：清空后立刻写回默认分类，避免清空后无法记账。
     */
    suspend fun wipe(): Result<Unit> = runCatching {
        database.withTransaction {
            clearAllTables()
            database.categoryDao().insertAll(
                DefaultCategories.mapIndexed { index, category ->
                    CategoryEntity(
                        name = category.first,
                        emoji = category.second,
                        colorArgb = category.third,
                        isDefault = true,
                        sortOrder = index,
                    )
                }
            )
        }
    }

    private fun expectedSummary(data: BackupData): String = summaryOf(
        bills = data.bills.size,
        amount = data.bills.sumOf { it.cnyAmountMinor },
        categories = data.categories.size,
        operations = data.operations.size,
    )

    private suspend fun currentSummary(): String {
        val bills = database.billDao().observeActive().first().map { it.bill } +
            database.billDao().observeTrash().first().map { it.bill }
        return summaryOf(
            bills = bills.size,
            amount = bills.sumOf { it.cnyAmountMinor },
            categories = database.categoryDao().count(),
            operations = database.operationDao().observeRecent(OPERATION_LIMIT).first().size,
        )
    }

    private fun summaryOf(bills: Int, amount: Long, categories: Int, operations: Int): String =
        "账单 $bills 笔 / 金额合计 $amount / 分类 $categories 个 / 操作 $operations 条"

    // ----------------------------------------------------------- db write

    /**
     * 清空所有表。
     *
     * 用裸 SQL 而不是 `clearAllTables()`：后者不能在事务里调用，
     * 而且我们要靠事务回滚保证「失败不留半套数据」。
     */
    private fun clearAllTables() {
        val db = database.openHelper.writableDatabase
        // 先删子表再删父表，避免外键约束报错。
        DELETION_ORDER.forEach { db.execSQL("DELETE FROM $it") }
    }

    private suspend fun insertAll(data: BackupData) {
        val categoryDao = database.categoryDao()
        val tagDao = database.tagDao()
        val billDao = database.billDao()
        val budgetDao = database.budgetDao()

        categoryDao.insertAll(data.categories)
        data.tags.forEach { tagDao.insert(it) }
        data.bills.forEach { billDao.insert(it) }
        if (data.billTags.isNotEmpty()) tagDao.linkAll(data.billTags)
        data.budgets.forEach { budgetDao.insert(it) }
        data.budgetTemplates.forEach { database.budgetTemplateDao().insert(it) }
        data.budgetChanges.forEach { budgetDao.insertChange(it) }
        val fixedDao = database.fixedExpenseDao()
        data.fixedExpenses.forEach { fixedDao.insert(it) }
        data.fixedExpensePayments.forEach { fixedDao.insertPayment(it) }
        val installmentDao = database.installmentDao()
        data.installments.forEach { installmentDao.insert(it) }
        data.installmentPayments.forEach { installmentDao.insertPayment(it) }
        val debtDao = database.debtDao()
        data.debts.forEach { debtDao.insert(it) }
        data.debtPayments.forEach { debtDao.insertPayment(it) }
        val savingsDao = database.savingsDao()
        data.savingsGoals.forEach { savingsDao.insert(it) }
        data.savingsEntries.forEach { savingsDao.insertEntry(it) }
        data.monthlyReports.forEach { database.monthlyReportDao().insert(it) }
        data.operations.forEach { database.operationDao().insert(it) }
    }

    private companion object {
        /** 备份保留的历史条数上限，避免超大账本把内存打爆。 */
        const val OPERATION_LIMIT = 100_000
        const val CHANGE_LIMIT = 100_000

        val DELETION_ORDER = listOf(
            "bill_tags",
            "bill_operations",
            "budget_changes",
            "installment_payments",
            "fixed_expense_payments",
            "debt_payments",
            "savings_entries",
            "bills",
            "budget_templates",
            "budgets",
            "installments",
            "fixed_expenses",
            "debts",
            "savings_goals",
            "monthly_reports",
            "tags",
            "categories",
        )
    }

    private fun sumByType(bills: List<BillEntity>, type: String): Long =
        bills.filter { it.type == type }.sumOf { it.cnyAmountMinor }
}