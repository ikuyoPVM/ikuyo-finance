package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.data.local.dao.TagDao
import xyz.ikuyo.finance.data.local.dao.TagUsage
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.data.local.entity.TagEntity
import xyz.ikuyo.finance.domain.model.Tag

/**
 * 标签仓储。
 *
 * 设计口径：标签是账单的附加维度，不影响金额；删除标签只解除关联，绝不删除账单。
 */
class TagRepository(private val dao: TagDao) {

    fun observeAll(): Flow<List<Tag>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getAll(): List<Tag> = dao.getAll().map { it.toDomain() }

    suspend fun findById(id: Long): Tag? = dao.findById(id)?.toDomain()

    suspend fun add(name: String, colorArgb: Int): Result<Long> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("标签名称不能为空"))
        if (trimmed.length > 12) return Result.failure(IllegalArgumentException("标签名称最多 12 个字"))
        if (dao.findByName(trimmed) != null) {
            return Result.failure(IllegalArgumentException("标签「$trimmed」已存在"))
        }
        val id = dao.insert(TagEntity(name = trimmed, colorArgb = colorArgb))
        return if (id > 0L) Result.success(id) else Result.failure(IllegalStateException("标签写入失败"))
    }

    suspend fun rename(id: Long, name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("标签名称不能为空"))
        val existing = dao.findById(id) ?: return Result.failure(IllegalStateException("标签不存在"))
        val duplicated = dao.findByName(trimmed)
        if (duplicated != null && duplicated.id != id) {
            return Result.failure(IllegalArgumentException("标签「$trimmed」已存在"))
        }
        dao.update(existing.copy(name = trimmed))
        return Result.success(Unit)
    }

    suspend fun setColor(id: Long, colorArgb: Int): Result<Unit> {
        val existing = dao.findById(id) ?: return Result.failure(IllegalStateException("标签不存在"))
        dao.update(existing.copy(colorArgb = colorArgb))
        return Result.success(Unit)
    }

    /** 删除标签：仅解除与账单的关联。 */
    suspend fun delete(id: Long): Result<Unit> {
        dao.delete(id)
        return Result.success(Unit)
    }

    suspend fun tagIdsOf(billId: Long): List<Long> = dao.tagIdsOf(billId)

    suspend fun tagsOf(billId: Long): List<Tag> = dao.tagsOf(billId).map { it.toDomain() }

    /** 覆盖式写入某笔账单的标签集合，保证与 UI 选择一致。 */
    suspend fun setBillTags(billId: Long, tagIds: Collection<Long>) {
        dao.clearBillLinks(billId)
        if (tagIds.isNotEmpty()) {
            dao.linkAll(tagIds.distinct().map { BillTagCrossRef(billId = billId, tagId = it) })
        }
    }

    fun observeAllLinks(): Flow<List<BillTagCrossRef>> = dao.observeAllLinks()

    fun observeBillIdsOf(tagId: Long): Flow<Set<Long>> =
        dao.observeBillIdsOf(tagId).map { it.toSet() }

    fun observeUsage(): Flow<Map<Long, Int>> =
        dao.observeUsage().map { rows -> rows.associate { it.tagId to it.count } }

    fun observeUsageCounts(): Flow<List<TagUsage>> = dao.observeUsage()
}

private fun TagEntity.toDomain(): Tag = Tag(id = id, name = name, colorArgb = colorArgb)