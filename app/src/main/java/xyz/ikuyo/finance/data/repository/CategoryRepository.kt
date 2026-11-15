package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.data.local.dao.CategoryDao
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.data.mapper.toDomain
import xyz.ikuyo.finance.domain.model.Category

class CategoryRepository(private val dao: CategoryDao) {

    fun observeAll(): Flow<List<Category>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getAll(): List<Category> = dao.getAll().map { it.toDomain() }

    suspend fun findById(id: Long): Category? = dao.findById(id)?.toDomain()

    suspend fun add(name: String, emoji: String, colorArgb: Int): Result<Long> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("分类名称不能为空"))
        if (dao.findByName(trimmed) != null) return Result.failure(IllegalStateException("分类已存在"))
        val order = dao.count() + 1
        val id = dao.insert(
            CategoryEntity(
                name = trimmed,
                emoji = emoji,
                colorArgb = colorArgb,
                isDefault = false,
                sortOrder = order,
            )
        )
        return if (id > 0) Result.success(id) else Result.failure(IllegalStateException("分类已存在"))
    }

    suspend fun rename(id: Long, name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("分类名称不能为空"))
        val existing = dao.findById(id) ?: return Result.failure(IllegalStateException("分类不存在"))
        val duplicated = dao.findByName(trimmed)
        if (duplicated != null && duplicated.id != id) {
            return Result.failure(IllegalStateException("分类已存在"))
        }
        dao.update(existing.copy(name = trimmed))
        return Result.success(Unit)
    }

    /** 只允许删除自定义分类；默认分类保留。 */
    suspend fun deleteCustom(id: Long): Result<Unit> {
        val existing = dao.findById(id) ?: return Result.failure(IllegalStateException("分类不存在"))
        if (existing.isDefault) return Result.failure(IllegalStateException("默认分类不可删除"))
        dao.deleteCustom(id)
        return Result.success(Unit)
    }
}