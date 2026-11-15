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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.feature.ai.AiScreen
import xyz.ikuyo.finance.feature.bills.BillsScreen
import xyz.ikuyo.finance.feature.budget.BudgetScreen
import xyz.ikuyo.finance.feature.categories.CategoriesScreen
import xyz.ikuyo.finance.feature.home.HomeScreen
import xyz.ikuyo.finance.feature.mine.MineScreen
import xyz.ikuyo.finance.feature.trash.TrashScreen
import xyz.ikuyo.finance.ui.theme.IkuyoTheme

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val Tabs = listOf(
    TabItem(Routes.HOME, "首页", Icons.Default.Home),
    TabItem(Routes.AI, "AI 记账", Icons.Default.Star),
    TabItem(Routes.MINE, "我的", Icons.Default.Person),
)

@Composable
fun IkuyoApp() {
    val themeMode by Graph.settingsRepository.themeMode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)

    val darkTheme = when (themeMode) {
        SettingsRepository.THEME_LIGHT -> false
        SettingsRepository.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    IkuyoTheme(darkTheme = darkTheme) {
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
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
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
                    )
                }
                composable(Routes.AI) {
                    AiScreen(
                        onBackToHome = { navController.switchTab(Routes.HOME) },
                        onOpenBills = { navController.navigate(Routes.BILLS) },
                    )
                }
                composable(Routes.MINE) {
                    MineScreen(
                        onOpenBills = { navController.navigate(Routes.BILLS) },
                        onOpenTrash = { navController.navigate(Routes.TRASH) },
                        onOpenBudget = { navController.navigate(Routes.BUDGET) },
                        onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                    )
                }
                composable(Routes.BILLS) {
                    BillsScreen(onOpenTrash = { navController.navigate(Routes.TRASH) })
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