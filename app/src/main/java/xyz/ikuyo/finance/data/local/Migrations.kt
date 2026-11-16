package xyz.ikuyo.finance.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移登记处。
 *
 * 约定：任何表结构变更都必须提升 [IkuyoDatabase] 的 version 并在此登记 Migration，
 * 严禁使用 fallbackToDestructiveMigration 丢弃用户账本。
 *
 * 模板：
 * ```
 * val MIGRATION_2_3 = object : Migration(2, 3) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE bills ADD COLUMN tagCount INTEGER NOT NULL DEFAULT 0")
 *     }
 * }
 * ```
 */
object Migrations {

    /**
     * v1 -> v2（阶段 2 预算完善）：
     * 1. budgets 由「固定自然月区间」改为「预算规则」：新增 periodType / carryOver /
     *    customStart / customEnd / updatedAt，去掉 periodStart / periodEnd。
     *    旧数据一律迁移为 MONTHLY + 不结转，金额与创建时间保持原值。
     * 2. 新增 budget_changes 预算变更历史表（不修改任何历史账单）。
     *
     * 采用「建新表 -> 复制 -> 删旧表 -> 改名」，避免 ALTER 限制并保证列与 Room 校验一致。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `budgets_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `categoryId` INTEGER,
                    `amountMinor` INTEGER NOT NULL,
                    `periodType` TEXT NOT NULL,
                    `carryOver` INTEGER NOT NULL,
                    `customStart` INTEGER,
                    `customEnd` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `budgets_new`
                    (`id`, `scope`, `categoryId`, `amountMinor`, `periodType`, `carryOver`,
                     `customStart`, `customEnd`, `createdAt`, `updatedAt`)
                SELECT `id`, `scope`, `categoryId`, `amountMinor`, 'MONTHLY', 0,
                       NULL, NULL, `createdAt`, `createdAt`
                FROM `budgets`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `budgets`")
            db.execSQL("ALTER TABLE `budgets_new` RENAME TO `budgets`")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `budget_changes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `budgetId` INTEGER NOT NULL,
                    `scope` TEXT NOT NULL,
                    `categoryId` INTEGER,
                    `categoryName` TEXT,
                    `oldAmountMinor` INTEGER,
                    `newAmountMinor` INTEGER,
                    `periodType` TEXT NOT NULL,
                    `changedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /**
     * v2 -> v3（阶段 2 模块一/六）：
     * 1. budgets 补齐名称 / 标签维度 / 暂停开关 / 备注（专项预算与标签预算需要）。
     * 2. 新增 budget_templates 预算模板表。
     * 3. 新增 bill_operations 账单操作历史表。
     *
     * 全部是「加列 + 建表」，既有预算金额、周期与历史账单保持不变。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `budgets` ADD COLUMN `tagId` INTEGER")
            db.execSQL("ALTER TABLE `budgets` ADD COLUMN `name` TEXT")
            db.execSQL("ALTER TABLE `budgets` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `budgets` ADD COLUMN `note` TEXT")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `budget_templates` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `categoryId` INTEGER,
                    `tagId` INTEGER,
                    `amountMinor` INTEGER NOT NULL,
                    `periodType` TEXT NOT NULL,
                    `carryOver` INTEGER NOT NULL,
                    `note` TEXT,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bill_operations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `billId` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `summary` TEXT NOT NULL,
                    `beforeJson` TEXT,
                    `afterJson` TEXT,
                    `amountMinor` INTEGER NOT NULL,
                    `undone` INTEGER NOT NULL DEFAULT 0,
                    `operatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /**
     * v3 -> v4（阶段 4 第 7 步 月度总结）：
     * 新增 monthly_reports 月报表。只加表，不改动任何既有账单 / 预算 / 标签数据。
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `monthly_reports` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `yearMonth` TEXT NOT NULL,
                    `version` INTEGER NOT NULL,
                    `generatedAt` INTEGER NOT NULL,
                    `dataStart` INTEGER NOT NULL,
                    `dataEndInclusive` INTEGER NOT NULL,
                    `billCount` INTEGER NOT NULL,
                    `netExpenseMinor` INTEGER NOT NULL,
                    `incomeMinor` INTEGER NOT NULL,
                    `overspendMinor` INTEGER NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `narrative` TEXT DEFAULT NULL,
                    `superseded` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_monthly_reports_yearMonth_version` " +
                    "ON `monthly_reports` (`yearMonth`, `version`)"
            )
        }
    }

    /**
     * v4 -> v5（阶段 5 第 1 步 固定支出）：
     * 新增 fixed_expenses（计划）与 fixed_expense_payments（付款记录）两张表。
     *
     * 只加表，不改动 bills / budgets / categories 任何既有列，因此旧账本的金额、
     * 币种与统计口径完全不变——计划金额不会被当成已发生支出。
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `fixed_expenses` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `amountMinor` INTEGER NOT NULL,
                    `categoryId` INTEGER,
                    `paymentMethod` TEXT NOT NULL,
                    `periodType` TEXT NOT NULL,
                    `dueAt` INTEGER NOT NULL,
                    `anchorDay` INTEGER NOT NULL,
                    `customDays` INTEGER NOT NULL DEFAULT 30,
                    `isVariable` INTEGER NOT NULL DEFAULT 0,
                    `enabled` INTEGER NOT NULL DEFAULT 1,
                    `note` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_fixed_expenses_dueAt` ON `fixed_expenses` (`dueAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_fixed_expenses_enabled` ON `fixed_expenses` (`enabled`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `fixed_expense_payments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fixedExpenseId` INTEGER NOT NULL,
                    `billId` INTEGER,
                    `amountMinor` INTEGER NOT NULL,
                    `dueAt` INTEGER NOT NULL,
                    `paidAt` INTEGER NOT NULL,
                    `note` TEXT NOT NULL,
                    FOREIGN KEY(`fixedExpenseId`) REFERENCES `fixed_expenses`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_fixed_expense_payments_fixedExpenseId` " +
                    "ON `fixed_expense_payments` (`fixedExpenseId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_fixed_expense_payments_dueAt` " +
                    "ON `fixed_expense_payments` (`dueAt`)"
            )
        }
    }

    /**
     * v5 -> v6（阶段 5 第 3 步 分期管理）：
     * 新增 installments（分期计划）与 installment_payments（每期付款记录）两张表。
     *
     * 同样只加表、不动 bills：分期计划本身不是支出，只有确认付款才会写入 bills。
     * installment_payments 上 (installmentId, periodIndex) 唯一，保证同一期不会付两次。
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `totalMinor` INTEGER NOT NULL,
                    `principalMinor` INTEGER NOT NULL,
                    `feeMinor` INTEGER NOT NULL DEFAULT 0,
                    `interestMinor` INTEGER NOT NULL DEFAULT 0,
                    `periodCount` INTEGER NOT NULL,
                    `firstPeriodMinor` INTEGER NOT NULL,
                    `perPeriodMinor` INTEGER NOT NULL,
                    `dueAt` INTEGER NOT NULL,
                    `anchorDay` INTEGER NOT NULL,
                    `categoryId` INTEGER,
                    `paymentMethod` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL DEFAULT 1,
                    `closedEarlyAt` INTEGER,
                    `note` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_dueAt` ON `installments` (`dueAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_enabled` ON `installments` (`enabled`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installment_payments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `installmentId` INTEGER NOT NULL,
                    `periodIndex` INTEGER NOT NULL,
                    `billId` INTEGER,
                    `amountMinor` INTEGER NOT NULL,
                    `dueAt` INTEGER NOT NULL,
                    `paidAt` INTEGER NOT NULL,
                    `note` TEXT NOT NULL,
                    FOREIGN KEY(`installmentId`) REFERENCES `installments`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installment_payments_installmentId` " +
                    "ON `installment_payments` (`installmentId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installment_payments_dueAt` " +
                    "ON `installment_payments` (`dueAt`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_payments_installmentId_periodIndex` " +
                    "ON `installment_payments` (`installmentId`, `periodIndex`)"
            )
        }
    }

    /**
     * v6 → v7：新增债务与还款记录两张表。
     *
     * 本金流转不参与收支，所以这里只存「欠多少、还了多少」；
     * 利息与手续费对应的账单仍由 AddBillUseCase 写入 bills。
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `debts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `principalMinor` INTEGER NOT NULL,
                    `dueAt` INTEGER NOT NULL,
                    `anchorDay` INTEGER NOT NULL,
                    `categoryId` INTEGER,
                    `paymentMethod` TEXT NOT NULL DEFAULT 'OTHER',
                    `enabled` INTEGER NOT NULL DEFAULT 1,
                    `settledAt` INTEGER,
                    `note` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_dueAt` ON `debts` (`dueAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_enabled` ON `debts` (`enabled`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_settledAt` ON `debts` (`settledAt`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `debt_payments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `debtId` INTEGER NOT NULL,
                    `principalMinor` INTEGER NOT NULL,
                    `extraMinor` INTEGER NOT NULL DEFAULT 0,
                    `billId` INTEGER,
                    `paidAt` INTEGER NOT NULL,
                    `note` TEXT NOT NULL,
                    FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_payments_debtId` ON `debt_payments` (`debtId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_payments_paidAt` ON `debt_payments` (`paidAt`)")
        }
    }

    /**
     * 储蓄目标与储蓄流水。
     *
     * 储蓄是资金划转：`savings_entries` 只影响储蓄余额，不写 bills，
     * 所以「总余额 = 可用余额 + 储蓄余额」在读取时按两张表分别汇总得到。
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `savings_goals` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `targetMinor` INTEGER NOT NULL,
                    `dueAt` INTEGER NOT NULL,
                    `note` TEXT NOT NULL,
                    `categoryId` INTEGER,
                    `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_dueAt` ON `savings_goals` (`dueAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_status` ON `savings_goals` (`status`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `savings_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `goalId` INTEGER,
                    `type` TEXT NOT NULL,
                    `amountMinor` INTEGER NOT NULL,
                    `occurredAt` INTEGER NOT NULL,
                    `note` TEXT NOT NULL,
                    FOREIGN KEY(`goalId`) REFERENCES `savings_goals`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_entries_goalId` ON `savings_entries` (`goalId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_entries_occurredAt` ON `savings_entries` (`occurredAt`)")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 汇率快照的来源与写入时间：历史账单只保留保存当时的汇率，后续不再改写。
            db.execSQL("ALTER TABLE `bills` ADD COLUMN `rateSource` TEXT NOT NULL DEFAULT 'MANUAL'")
            db.execSQL("ALTER TABLE `bills` ADD COLUMN `rateUpdatedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 账单日期只精确到日：把既有 occurredAt 截断到「当地日历日的零点」。
            // 只保留原来的日历日，不偏移、不清空，所以账单仍落在同一天，金额与统计口径不变。
            db.execSQL(
                "UPDATE `bills` SET `occurredAt` = CAST(strftime('%s', datetime(`occurredAt` / 1000, 'unixepoch', 'localtime', 'start of day', 'utc')) AS INTEGER) * 1000 WHERE `occurredAt` > 0"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    )
}