package xyz.ikuyo.finance.di

import android.content.Context
import xyz.ikuyo.finance.data.local.IkuyoDatabase
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.BudgetRepository
import xyz.ikuyo.finance.data.repository.CategoryRepository
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase

/**
 * 轻量服务定位器（阶段 0 不引入 Hilt，保持构建简单、启动快）。
 * 所有依赖在 Application 启动时一次性装配，View 层只依赖接口/仓储。
 */
object Graph {

    lateinit var database: IkuyoDatabase
        private set
    lateinit var billRepository: BillRepository
        private set
    lateinit var categoryRepository: CategoryRepository
        private set
    lateinit var budgetRepository: BudgetRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var addBillUseCase: AddBillUseCase
        private set

    fun init(context: Context) {
        if (::database.isInitialized) return
        val appContext = context.applicationContext
        database = IkuyoDatabase.get(appContext)
        billRepository = BillRepository(database.billDao())
        categoryRepository = CategoryRepository(database.categoryDao())
        budgetRepository = BudgetRepository(database.budgetDao())
        settingsRepository = SettingsRepository(appContext)
        addBillUseCase = AddBillUseCase(billRepository)
    }
}