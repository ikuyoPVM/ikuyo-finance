package xyz.ikuyo.finance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import xyz.ikuyo.finance.data.local.dao.BillDao
import xyz.ikuyo.finance.data.local.dao.BudgetDao
import xyz.ikuyo.finance.data.local.dao.MonthlyReportDao
import xyz.ikuyo.finance.data.local.dao.BudgetTemplateDao
import xyz.ikuyo.finance.data.local.dao.FixedExpenseDao
import xyz.ikuyo.finance.data.local.dao.InstallmentDao
import xyz.ikuyo.finance.data.local.dao.OperationDao
import xyz.ikuyo.finance.data.local.dao.CategoryDao
import xyz.ikuyo.finance.data.local.dao.DebtDao
import xyz.ikuyo.finance.data.local.dao.SavingsDao
import xyz.ikuyo.finance.data.local.dao.TagDao
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.DebtEntity
import xyz.ikuyo.finance.data.local.entity.DebtPaymentEntity
import xyz.ikuyo.finance.data.local.entity.SavingsEntryEntity
import xyz.ikuyo.finance.data.local.entity.SavingsGoalEntity
import xyz.ikuyo.finance.data.local.entity.BillOperationEntity
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.data.local.entity.BudgetChangeEntity
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.local.entity.BudgetTemplateEntity
import xyz.ikuyo.finance.data.local.entity.MonthlyReportEntity
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpenseEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpensePaymentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentPaymentEntity
import xyz.ikuyo.finance.data.local.entity.TagEntity

@Database(
    entities = [
        BillEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        BillTagCrossRef::class,
        BudgetEntity::class,
        BudgetChangeEntity::class,
        BudgetTemplateEntity::class,
        BillOperationEntity::class,
        MonthlyReportEntity::class,
        FixedExpenseEntity::class,
        FixedExpensePaymentEntity::class,
        InstallmentEntity::class,
        InstallmentPaymentEntity::class,
        DebtEntity::class,
        DebtPaymentEntity::class,
        SavingsGoalEntity::class,
        SavingsEntryEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class IkuyoDatabase : RoomDatabase() {

    abstract fun billDao(): BillDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun budgetDao(): BudgetDao
    abstract fun budgetTemplateDao(): BudgetTemplateDao
    abstract fun operationDao(): OperationDao
    abstract fun monthlyReportDao(): MonthlyReportDao
    abstract fun fixedExpenseDao(): FixedExpenseDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun debtDao(): DebtDao

    abstract fun savingsDao(): SavingsDao

    companion object {
        private const val DB_NAME = "ikuyo_finance.db"

        @Volatile
        private var instance: IkuyoDatabase? = null

        fun get(context: Context): IkuyoDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): IkuyoDatabase =
            Room.databaseBuilder(context, IkuyoDatabase::class.java, DB_NAME)
                .addCallback(SeedCallback)
                .addMigrations(*Migrations.ALL)
                .build()
    }

    /** 首次创建数据库时写入默认分类，保证默认分类不会因升级或导入丢失。 */
    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            DefaultCategories.forEachIndexed { index, category ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (name, emoji, colorArgb, isDefault, sortOrder) " +
                        "VALUES (?, ?, ?, 1, ?)",
                    arrayOf(category.first, category.second, category.third, index),
                )
            }
        }
    }
}

/** name / emoji / colorArgb —— 与计划书 7.2 的 12 个默认分类一致。 */
internal val DefaultCategories: List<Triple<String, String, Int>> = listOf(
    Triple("餐饮", "🍜", 0xFFEF6C00.toInt()),
    Triple("零食饮料", "🧋", 0xFFD81B60.toInt()),
    Triple("交通出行", "🚇", 0xFF1E88E5.toInt()),
    Triple("购物", "🛍️", 0xFF8E24AA.toInt()),
    Triple("娱乐休闲", "🎬", 0xFF5E35B1.toInt()),
    Triple("生活用品", "🧴", 0xFF00897B.toInt()),
    Triple("学习教育", "📚", 0xFF3949AB.toInt()),
    Triple("通讯网络", "📶", 0xFF0097A7.toInt()),
    Triple("医疗健康", "💊", 0xFF43A047.toInt()),
    Triple("住宿水电", "🏠", 0xFF6D4C41.toInt()),
    Triple("人情社交", "🎁", 0xFFC62828.toInt()),
    Triple("其他", "📦", 0xFF757575.toInt()),
)