package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.BillSnapshot
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.OperationDao
import xyz.ikuyo.finance.data.local.entity.BillOperationEntity
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.model.BillOperationType
import xyz.ikuyo.finance.domain.model.BillType

/**
 * 账单操作历史仓储（阶段 2 模块六）。
 *
 * 只记录、不计算：历史条目永远不会影响余额、预算或统计。
 */
class OperationRepository(private val dao: OperationDao) {

    fun observeRecent(limit: Int = 50): Flow<List<BillOperation>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeForBill(billId: Long): Flow<List<BillOperation>> =
        dao.observeForBill(billId).map { rows -> rows.map { it.toDomain() } }

    suspend fun record(
        billId: Long,
        type: BillOperationType,
        summary: String,
        amountMinor: Long = 0L,
        before: Bill? = null,
        after: Bill? = null,
        operatedAt: Long = Time.nowMillis(),
    ): Long = dao.insert(
        BillOperationEntity(
            billId = billId,
            type = type.dbValue,
            summary = summary,
            beforeJson = before?.let { BillSnapshot.encode(it) },
            afterJson = after?.let { BillSnapshot.encode(it) },
            amountMinor = amountMinor,
            operatedAt = operatedAt,
        )
    )

    suspend fun lastUndoable(): BillOperation? = dao.findLastUndoable()?.toDomain()

    suspend fun find(id: Long): BillOperation? = dao.findById(id)?.toDomain()

    suspend fun markUndone(operationId: Long) = dao.markUndone(operationId)

    suspend fun clear() = dao.clear()

    companion object {
        /** 「新增 ¥28.50 · 餐饮」这类一句话摘要。 */
        fun summaryOf(type: BillOperationType, bill: Bill): String {
            val sign = when (bill.type) {
                BillType.INCOME -> "+"
                else -> "-"
            }
            val amount = Money.format(bill.cnyAmountMinor, Money.BASE_CURRENCY)
            val category = bill.categoryName ?: "未分类"
            return "${type.label} $sign$amount · $category"
        }
    }
}