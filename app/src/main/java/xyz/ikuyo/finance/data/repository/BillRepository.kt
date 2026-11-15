package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.BillDao
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.domain.model.Bill

class BillRepository(private val dao: BillDao) {

    fun observeActive(): Flow<List<Bill>> =
        dao.observeActive().map { rows -> rows.map { it.toDomain() } }

    fun observeTrash(): Flow<List<Bill>> =
        dao.observeTrash().map { rows -> rows.map { it.toDomain() } }

    suspend fun insert(entity: BillEntity): Long = dao.insert(entity)

    suspend fun update(entity: BillEntity) = dao.update(entity)

    suspend fun findById(id: Long): BillEntity? = dao.findById(id)

    suspend fun findDomain(id: Long): Bill? = dao.findById(id)?.toDomain()

    /** 连分类名一起读，供操作历史摘要与详情页展示使用（列表查询已 JOIN）。 */
    suspend fun findDomainWithCategory(id: Long): Bill? = dao.findWithCategory(id)?.toDomain()

    suspend fun latestActive(): BillEntity? = dao.latestActive()

    /** AI 记账幂等查询：同一个 clientKey 只应存在一笔账单。 */
    suspend fun findByClientKey(clientKey: String): Bill? =
        dao.findByClientKey(clientKey)?.toDomain()

    suspend fun softDelete(id: Long) = dao.softDelete(id, Time.nowMillis())

    suspend fun restore(id: Long) = dao.restore(id)

    suspend fun purge(id: Long) = dao.purge(id)

    suspend fun purgeAllTrash() = dao.purgeAllTrash()

    suspend fun countTrash(): Int = dao.countTrash()

    /**
     * 用快照写回既有账单（撤销修改用）。
     * 保留幂等键 clientKey 与删除标记，避免破坏唯一索引或把已删除记录复活。
     */
    suspend fun applySnapshot(bill: Bill) {
        val existing = dao.findById(bill.id) ?: return
        dao.update(
            existing.copy(
                type = bill.type.dbValue,
                amountMinor = bill.amountMinor,
                currency = bill.currency,
                exchangeRateScaled = bill.exchangeRateScaled,
                cnyAmountMinor = bill.cnyAmountMinor,
                categoryId = bill.categoryId,
                paymentMethod = bill.paymentMethod.dbValue,
                note = bill.note,
                occurredAt = bill.occurredAt,
                source = bill.source.dbValue,
                refundOfBillId = bill.refundOfBillId,
            )
        )
    }
}