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

    suspend fun latestActive(): BillEntity? = dao.latestActive()

    suspend fun softDelete(id: Long) = dao.softDelete(id, Time.nowMillis())

    suspend fun restore(id: Long) = dao.restore(id)

    suspend fun purge(id: Long) = dao.purge(id)

    suspend fun purgeAllTrash() = dao.purgeAllTrash()

    suspend fun countTrash(): Int = dao.countTrash()
}