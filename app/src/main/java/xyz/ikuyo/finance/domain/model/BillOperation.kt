package xyz.ikuyo.finance.domain.model

/** 账单操作类型（计划书第 9 章：新增/修改/删除/恢复/撤销/永久删除）。 */
enum class BillOperationType(val label: String, val dbValue: String) {
    CREATE("新增", "CREATE"),
    UPDATE("修改", "UPDATE"),
    DELETE("删除", "DELETE"),
    RESTORE("恢复", "RESTORE"),
    PURGE("彻底删除", "PURGE"),
    UNDO("撤销", "UNDO");

    companion object {
        fun fromDb(value: String): BillOperationType =
            entries.firstOrNull { it.dbValue == value } ?: CREATE
    }
}

/**
 * 一条账单操作历史。
 *
 * [beforeJson] / [afterJson] 保存变动前后的快照（内部格式，仅用于撤销与审计，
 * 不参与统计），[summary] 是给用户看的一句话说明。
 */
data class BillOperation(
    val id: Long = 0L,
    val billId: Long,
    val type: BillOperationType,
    val summary: String,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val amountMinor: Long = 0L,
    val undone: Boolean = false,
    val operatedAt: Long,
) {
    /** 只有新增/修改/删除可以被撤销回去（恢复与永久删除不提供撤销）。 */
    val isUndoable: Boolean
        get() = !undone && when (type) {
            BillOperationType.CREATE,
            BillOperationType.UPDATE,
            BillOperationType.DELETE,
            -> true
            else -> false
        }
}