package xyz.ikuyo.finance.data.local

import androidx.room.migration.Migration

/**
 * 数据库迁移登记处。
 *
 * 约定：任何表结构变更都必须提升 [IkuyoDatabase] 的 version 并在此登记 Migration，
 * 严禁使用 fallbackToDestructiveMigration 丢弃用户账本。
 *
 * 模板：
 * ```
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE bills ADD COLUMN tagCount INTEGER NOT NULL DEFAULT 0")
 *     }
 * }
 * ```
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}