package xyz.ikuyo.finance.di

import android.content.Context
import xyz.ikuyo.finance.data.ai.AiService
import xyz.ikuyo.finance.data.ai.AiSettingsRepository
import xyz.ikuyo.finance.data.ai.ChatRepository
import xyz.ikuyo.finance.data.backup.BackupSettingsPort
import xyz.ikuyo.finance.data.backup.RepoSettingsPort
import xyz.ikuyo.finance.data.local.IkuyoDatabase
import xyz.ikuyo.finance.data.repository.BackupRepository
import xyz.ikuyo.finance.data.remote.HttpRateFetcher
import xyz.ikuyo.finance.data.remote.RateFetcher
import xyz.ikuyo.finance.data.repository.AnomalyPreferenceRepository
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.BudgetAlertRepository
import xyz.ikuyo.finance.data.repository.BudgetRepository
import xyz.ikuyo.finance.data.repository.BudgetTemplateRepository
import xyz.ikuyo.finance.data.repository.CategoryRepository
import xyz.ikuyo.finance.data.repository.CurrencyRateRepository
import xyz.ikuyo.finance.data.repository.DebtRepository
import xyz.ikuyo.finance.data.repository.FixedExpenseRepository
import xyz.ikuyo.finance.data.repository.HomeLayoutRepository
import xyz.ikuyo.finance.data.repository.InstallmentRepository
import xyz.ikuyo.finance.data.repository.MonthlyReportRepository
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.data.repository.SavingsRepository
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase
import xyz.ikuyo.finance.domain.usecase.EditBillUseCase
import xyz.ikuyo.finance.domain.usecase.UndoUseCase

/**
 * 轻量服务定位器（阶段 0 不引入 Hilt，保持构建简单、启动快）。
 * 所有依赖在 Application 启动时一次性装配，View 层只依赖接口/仓储。
 */
object Graph {

    lateinit var database: IkuyoDatabase
        private set
    lateinit var billRepository: BillRepository
        private set
    lateinit var debtRepository: DebtRepository
        private set

    lateinit var savingsRepository: SavingsRepository
    lateinit var currencyRateRepository: CurrencyRateRepository
    lateinit var rateFetcher: RateFetcher
        private set
    lateinit var categoryRepository: CategoryRepository
        private set
    lateinit var tagRepository: TagRepository
        private set
    lateinit var budgetRepository: BudgetRepository
        private set
    lateinit var budgetTemplateRepository: BudgetTemplateRepository
        private set
    lateinit var operationRepository: OperationRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var homeLayoutRepository: HomeLayoutRepository
        private set
    lateinit var anomalyRepository: AnomalyPreferenceRepository
        private set
    lateinit var budgetAlertRepository: BudgetAlertRepository
        private set
    lateinit var monthlyReportRepository: MonthlyReportRepository
        private set
    lateinit var fixedExpenseRepository: FixedExpenseRepository
        private set
    lateinit var installmentRepository: InstallmentRepository
        private set
    lateinit var aiSettingsRepository: AiSettingsRepository
        private set
    lateinit var aiService: AiService
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var addBillUseCase: AddBillUseCase
        private set
    lateinit var editBillUseCase: EditBillUseCase
        private set
    lateinit var undoUseCase: UndoUseCase
        private set
    lateinit var backupSettingsPort: BackupSettingsPort
        private set
    lateinit var backupRepository: BackupRepository
        private set
    lateinit var appVersion: String
        private set
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        if (::database.isInitialized) return
        val appContext = context.applicationContext
        this.appContext = appContext
        appVersion = runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
        database = IkuyoDatabase.get(appContext)
        billRepository = BillRepository(database.billDao())
        categoryRepository = CategoryRepository(database.categoryDao())
        tagRepository = TagRepository(database.tagDao())
        budgetRepository = BudgetRepository(database.budgetDao())
        budgetTemplateRepository = BudgetTemplateRepository(database.budgetTemplateDao())
        operationRepository = OperationRepository(database.operationDao())
        settingsRepository = SettingsRepository(appContext)
        homeLayoutRepository = HomeLayoutRepository(appContext)
        anomalyRepository = AnomalyPreferenceRepository(appContext)
        budgetAlertRepository = BudgetAlertRepository(appContext)
        monthlyReportRepository = MonthlyReportRepository(
            dao = database.monthlyReportDao(),
            bills = billRepository,
            categories = categoryRepository,
            tags = tagRepository,
            budgets = budgetRepository,
        )
        aiSettingsRepository = AiSettingsRepository(appContext)
        aiService = AiService(aiSettingsRepository)
        chatRepository = ChatRepository(appContext)
        addBillUseCase = AddBillUseCase(billRepository, operationRepository, tagRepository)
        editBillUseCase = EditBillUseCase(billRepository, operationRepository, tagRepository)
        undoUseCase = UndoUseCase(billRepository, operationRepository)
        fixedExpenseRepository = FixedExpenseRepository(
            dao = database.fixedExpenseDao(),
            categoryRepository = categoryRepository,
            addBillUseCase = addBillUseCase,
        )
        installmentRepository = InstallmentRepository(
            dao = database.installmentDao(),
            addBillUseCase = addBillUseCase,
        )
        currencyRateRepository = CurrencyRateRepository(context = appContext)
        rateFetcher = HttpRateFetcher()
        savingsRepository = SavingsRepository(
            dao = database.savingsDao(),
            categoryRepository = categoryRepository,
        )
        backupSettingsPort = RepoSettingsPort(
            settings = settingsRepository,
            homeLayout = homeLayoutRepository,
            rates = currencyRateRepository,
        )
        backupRepository = BackupRepository(
            database = database,
            settingsPort = backupSettingsPort,
        )

        debtRepository = DebtRepository(
            dao = database.debtDao(),
            categoryRepository = categoryRepository,
            addBillUseCase = addBillUseCase,
        )
    }
}