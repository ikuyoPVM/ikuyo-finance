package xyz.ikuyo.finance.data.backup

import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.BillOperationEntity
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.data.local.entity.BudgetChangeEntity
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.local.entity.BudgetTemplateEntity
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.data.local.entity.DebtEntity
import xyz.ikuyo.finance.data.local.entity.DebtPaymentEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpenseEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpensePaymentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentPaymentEntity
import xyz.ikuyo.finance.data.local.entity.MonthlyReportEntity
import xyz.ikuyo.finance.data.local.entity.SavingsEntryEntity
import xyz.ikuyo.finance.data.local.entity.SavingsGoalEntity
import xyz.ikuyo.finance.data.local.entity.TagEntity

/**
 * 备份文件格式定义（阶段六）。
 *
 * 设计原则（对应计划书第 2 章）：
 * 1. 文件必须自带「格式版本 + 数据版本」，未来加字段时可以按版本给出兼容策略，
 *    而不是靠猜字段存在与否。
 * 2. 数据内容与元信息分离：`data` 子树是唯一被完整性校验覆盖的部分，
 *    因此密文/明文只影响外层包装，不影响校验结论。
 * 3. 只导出「账本身份」数据：API Key 等设备密钥类材料一律不进来，
 *    恢复后要求用户重新配置（见 [BackupSettings] 注释）。
 */
object BackupFormat {

    /** 备份文件格式版本。加字段但保持向后兼容时不必加，改语义时才加。 */
    const val FORMAT_VERSION = 1

    /** 文件魔数，用来在打开文件的第一时间判断「这不是本应用的备份」。 */
    const val MAGIC = "IKUYO-BACKUP"

    /** 应用数据版本，与 Room 的 version 对齐：恢复时要判断能否直接导入。 */
    const val DATA_VERSION = 9

    const val FILE_SUFFIX = ".ikuyobak"
    const val MIME_JSON = "application/json"

    /**
     * 保存备份时用于 CreateDocument 的 MIME。
     *
     * 用 application/json 会让系统文件面板按 MIME 追加 .json，把 .ikuyobak 变成 .ikuyobak.json；
     * octet-stream 不做扩展名映射，用户看到的就是完整文件名。
     */
    const val MIME_BACKUP = "application/octet-stream"
}

/**
 * 账本相关设置。这里只保存「可以迁移、且不涉及密钥」的偏好：
 * - `fixedReminderDays`：固定支出提前提醒天数
 * - `homeLayout`：首页模块布局 JSON
 * - `budgetAlerts`：预算提醒去重状态 JSON
 * - `currencyRates`：汇率表（含来源与更新时间，属于历史汇率快照的一部分）
 *
 * 明确不包含：AI API Key（Keystore 加密保存，不随备份迁移）、
 * 备份密码（只用于加密本文件，从不落盘）。
 */
data class BackupSettings(val values: Map<String, String> = emptyMap()) {
    fun value(key: String): String? = values[key]
}

/** 备份文件的全部内容。集合顺序与数据库读出一致，保证同一账本导出结果可复现。 */
data class BackupData(
    val bills: List<BillEntity> = emptyList(),
    val billTags: List<BillTagCrossRef> = emptyList(),
    val operations: List<BillOperationEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val budgetTemplates: List<BudgetTemplateEntity> = emptyList(),
    val budgetChanges: List<BudgetChangeEntity> = emptyList(),
    val fixedExpenses: List<FixedExpenseEntity> = emptyList(),
    val fixedExpensePayments: List<FixedExpensePaymentEntity> = emptyList(),
    val installments: List<InstallmentEntity> = emptyList(),
    val installmentPayments: List<InstallmentPaymentEntity> = emptyList(),
    val debts: List<DebtEntity> = emptyList(),
    val debtPayments: List<DebtPaymentEntity> = emptyList(),
    val savingsGoals: List<SavingsGoalEntity> = emptyList(),
    val savingsEntries: List<SavingsEntryEntity> = emptyList(),
    val monthlyReports: List<MonthlyReportEntity> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
) {
    /** 恢复预览与结果报告共用的计数口径：键名稳定，直接显示给用户。 */
    fun counts(): Map<String, Int> = linkedMapOf(
        "bills" to bills.size,
        "billTags" to billTags.size,
        "operations" to operations.size,
        "categories" to categories.size,
        "tags" to tags.size,
        "budgets" to budgets.size,
        "budgetTemplates" to budgetTemplates.size,
        "budgetChanges" to budgetChanges.size,
        "fixedExpenses" to fixedExpenses.size,
        "fixedExpensePayments" to fixedExpensePayments.size,
        "installments" to installments.size,
        "installmentPayments" to installmentPayments.size,
        "debts" to debts.size,
        "debtPayments" to debtPayments.size,
        "savingsGoals" to savingsGoals.size,
        "savingsEntries" to savingsEntries.size,
        "monthlyReports" to monthlyReports.size,
    )

    /** 是否真的没有任何账本数据（用于提示「这个备份是空的」）。 */
    fun isEmpty(): Boolean =
        bills.isEmpty() && categories.isEmpty() && tags.isEmpty() &&
            budgets.isEmpty() && fixedExpenses.isEmpty() && installments.isEmpty() &&
            debts.isEmpty() && savingsGoals.isEmpty()
}

/** 备份元信息。恢复前用它给用户看「这个备份是什么时候、什么版本、装了什么」。 */
data class BackupMeta(
    val formatVersion: Int = BackupFormat.FORMAT_VERSION,
    val dataVersion: Int = BackupFormat.DATA_VERSION,
    val appVersion: String = "",
    val createdAt: Long = 0L,
    val baseCurrency: String = "CNY",
    val counts: Map<String, Int> = emptyMap(),
    val checksum: String = "",
)

/** 一份已经解析完成、可以拿去执行恢复的备份。 */
data class ParsedBackup(
    val meta: BackupMeta,
    val data: BackupData,
    val encrypted: Boolean,
)

/** 结构检查结果：preview 阶段给用户看的「这份备份健康吗」。 */
data class BackupIssues(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val isBlocking: Boolean get() = errors.isNotEmpty()

    fun summary(): String = when {
        errors.isNotEmpty() -> "发现 ${errors.size} 个问题，需要处理后才能恢复"
        warnings.isNotEmpty() -> "结构检查通过，有 ${warnings.size} 条提示"
        else -> "结构检查通过"
    }
}