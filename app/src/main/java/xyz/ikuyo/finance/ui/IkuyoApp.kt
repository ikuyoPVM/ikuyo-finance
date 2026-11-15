package xyz.ikuyo.finance.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import xyz.ikuyo.finance.R
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.feature.ai.AiScreen
import xyz.ikuyo.finance.feature.ai.AiSettingsScreen
import xyz.ikuyo.finance.feature.backup.BackupScreen
import xyz.ikuyo.finance.feature.export.ExportScreen
import xyz.ikuyo.finance.feature.imports.ImportScreen
import xyz.ikuyo.finance.feature.bills.BillsScreen
import xyz.ikuyo.finance.feature.budget.BudgetScreen
import xyz.ikuyo.finance.feature.categories.CategoriesScreen
import xyz.ikuyo.finance.feature.detail.BillDetailScreen
import xyz.ikuyo.finance.feature.detail.BillEditScreen
import xyz.ikuyo.finance.feature.debt.DebtsScreen
import xyz.ikuyo.finance.feature.rates.RatesScreen
import xyz.ikuyo.finance.feature.savings.SavingsScreen
import xyz.ikuyo.finance.feature.fixed.FixedExpensesScreen
import xyz.ikuyo.finance.feature.installment.InstallmentsScreen
import xyz.ikuyo.finance.feature.home.HomeScreen
import xyz.ikuyo.finance.feature.mine.MineScreen
import xyz.ikuyo.finance.feature.operations.OperationsScreen
import xyz.ikuyo.finance.feature.reports.ReportsScreen
import xyz.ikuyo.finance.feature.stats.StatsBillsScreen
import xyz.ikuyo.finance.feature.stats.StatsDrillDown
import xyz.ikuyo.finance.feature.stats.StatsScreen
import xyz.ikuyo.finance.feature.tags.TagsScreen
import xyz.ikuyo.finance.feature.trash.TrashScreen
import xyz.ikuyo.finance.ui.theme.IkuyoTheme

private data class TabItem(
    val route: String,
    val label: Int,
    val icon: ImageVector,
)

private val Tabs = listOf(
    TabItem(Routes.HOME, R.string.nav_home, Icons.Default.Home),
    TabItem(Routes.AI, R.string.nav_accounting, Icons.Default.Star),
    TabItem(Routes.MINE, R.string.nav_mine, Icons.Default.Person),
)

@Composable
fun IkuyoApp() {
    val themeMode by Graph.settingsRepository.themeMode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)

    val dynamicColor by Graph.settingsRepository.dynamicColor
        .collectAsStateWithLifecycle(initialValue = true)

    val darkTheme = when (themeMode) {
        SettingsRepository.THEME_LIGHT -> false
        SettingsRepository.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    IkuyoTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            bottomBar = {
                if (currentRoute == null || currentRoute in Routes.TABS) {
                    NavigationBar {
                        Tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { navController.switchTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenBills = { navController.navigate(Routes.BILLS) },
                        onOpenBudget = { navController.navigate(Routes.BUDGET) },
                        onOpenDetail = { billId -> navController.navigate(Routes.billDetail(billId)) },
                    )
                }
                composable(Routes.AI) {
                    AiScreen(
                        onBackToHome = { navController.switchTab(Routes.HOME) },
                        onOpenBills = { navController.navigate(Routes.BILLS) },
                        onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    )
                }
                composable(Routes.MINE) {
                    MineScreen(
                        onOpenBills = { navController.navigate(Routes.BILLS) },
                        onOpenTrash = { navController.navigate(Routes.TRASH) },
                        onOpenBudget = { navController.navigate(Routes.BUDGET) },
                        onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                        onOpenTags = { navController.navigate(Routes.TAGS) },
                        onOpenOperations = { navController.navigate(Routes.OPERATIONS) },
                        onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                        onOpenStats = { navController.navigate(Routes.STATS) },
                        onOpenReports = { navController.navigate(Routes.REPORTS) },
                        onOpenFixedExpenses = { navController.navigate(Routes.FIXED_EXPENSES) },
                        onOpenInstallments = { navController.navigate(Routes.INSTALLMENTS) },
                        onOpenDebts = { navController.navigate(Routes.DEBTS) },
                        onOpenSavings = { navController.navigate(Routes.SAVINGS) },
                        onOpenRates = { navController.navigate(Routes.RATES) },
                        onOpenBackup = { navController.navigate(Routes.BACKUP) },
                        onOpenExport = { navController.navigate(Routes.EXPORT) },
                        onOpenImport = { navController.navigate(Routes.IMPORT) },
                    )
                }
                composable(Routes.REPORTS) {
                    ReportsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.FIXED_EXPENSES) {
                    FixedExpensesScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.INSTALLMENTS) {
                    InstallmentsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.DEBTS) {
                    DebtsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.SAVINGS) {
                    SavingsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.RATES) {
                    RatesScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.BACKUP) {
                    BackupScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.EXPORT) {
                    ExportScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.IMPORT) {
                    ImportScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.STATS) {
                    StatsScreen(
                        onBack = { navController.popBackStack() },
                        onDrillDown = { drillDown: StatsDrillDown ->
                            navController.navigate(
                                Routes.statsBills(
                                    title = drillDown.title,
                                    startMillis = drillDown.startMillis,
                                    endMillisExclusive = drillDown.endMillisExclusive,
                                    categoryId = drillDown.categoryId,
                                    tagId = drillDown.tagId,
                                ),
                            )
                        },
                    )
                }
                composable(
                    route = Routes.STATS_BILLS,
                    arguments = listOf(
                        navArgument("title") { type = NavType.StringType; defaultValue = "账单明细" },
                        navArgument("start") { type = NavType.LongType; defaultValue = 0L },
                        navArgument("end") { type = NavType.LongType; defaultValue = 0L },
                        navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("tagId") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) { entry ->
                    val args = entry.arguments
                    val categoryId = args?.getLong("categoryId")?.takeIf { it > 0L }
                    val tagId = args?.getLong("tagId")?.takeIf { it > 0L }
                    StatsBillsScreen(
                        title = java.net.URLDecoder.decode(args?.getString("title") ?: "", "UTF-8"),
                        startMillis = args?.getLong("start") ?: 0L,
                        endMillisExclusive = args?.getLong("end") ?: 0L,
                        categoryId = categoryId,
                        tagId = tagId,
                        onBack = { navController.popBackStack() },
                        onOpenBill = { billId -> navController.navigate(Routes.billDetail(billId)) },
                    )
                }
                composable(Routes.BILLS) {
                    BillsScreen(
                        onOpenTrash = { navController.navigate(Routes.TRASH) },
                        onOpenDetail = { billId -> navController.navigate(Routes.billDetail(billId)) },
                    )
                }
                composable(Routes.TRASH) {
                    TrashScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.BUDGET) {
                    BudgetScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.CATEGORIES) {
                    CategoriesScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.TAGS) {
                    TagsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.OPERATIONS) {
                    OperationsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.AI_SETTINGS) {
                    AiSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Routes.BILL_DETAIL,
                    arguments = listOf(navArgument("billId") { type = NavType.LongType }),
                ) { entry ->
                    val billId = entry.arguments?.getLong("billId") ?: 0L
                    BillDetailScreen(
                        billId = billId,
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate(Routes.billEdit(id)) },
                    )
                }
                composable(
                    route = Routes.BILL_EDIT,
                    arguments = listOf(navArgument("billId") { type = NavType.LongType }),
                ) { entry ->
                    val billId = entry.arguments?.getLong("billId") ?: 0L
                    BillEditScreen(
                        billId = billId,
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}