package xyz.ikuyo.finance.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.HomeStats
import xyz.ikuyo.finance.ui.components.BillRow
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle
import xyz.ikuyo.finance.ui.components.StatTile
import xyz.ikuyo.finance.ui.theme.amountColorFor
import java.time.LocalDate

@Composable
fun HomeScreen(
    onOpenBills: () -> Unit = {},
    onOpenBudget: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var quickEntryType by remember { mutableStateOf<BillType?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.text,
                        actionLabel = if (event.undoable) "撤销" else null,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed && event.undoable) {
                        viewModel.undoLastInsert()
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HomeHeader(monthLabel = state.monthLabel) }
            item { BalanceCard(state) }
            item { QuickActions(onQuickAdd = { quickEntryType = it }) }
            item {
                StatsGrid(
                    stats = state.stats,
                    onOpenBills = onOpenBills,
                )
            }
            item { BudgetOverview(budget = state.budget, onOpenBudget = onOpenBudget) }
            item {
                RecentBills(
                    bills = state.recentBills,
                    onOpenAll = onOpenBills,
                    onBillClick = { quickEntryType = null },
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    val entryType = quickEntryType
    if (entryType != null) {
        QuickEntrySheet(
            type = entryType,
            categories = state.categories,
            lastPaymentMethod = state.lastPaymentMethod,
            onDismiss = { quickEntryType = null },
            onSave = { draft ->
                viewModel.addBill(draft)
                quickEntryType = null
            },
        )
    }
}

@Composable
private fun HomeHeader(monthLabel: String) {
    Column {
        Text(
            text = "ikuyo 记账",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (monthLabel.isBlank()) "账目一目了然" else "$monthLabel · 账目一目了然",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BalanceCard(state: HomeUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "总余额",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = Money.format(state.balance.totalMinor),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "总余额 = 可用余额 + 储蓄余额",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniBalance(
                    label = "可用余额",
                    value = Money.format(state.balance.availableMinor),
                    modifier = Modifier.weight(1f),
                )
                MiniBalance(
                    label = "储蓄余额",
                    value = Money.format(state.balance.savingsMinor),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MiniBalance(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun QuickActions(onQuickAdd: (BillType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = { onQuickAdd(BillType.EXPENSE) },
            modifier = Modifier.weight(1f),
        ) { Text("记支出") }
        FilledTonalButton(
            onClick = { onQuickAdd(BillType.INCOME) },
            modifier = Modifier.weight(1f),
        ) { Text("记收入") }
        FilledTonalButton(
            onClick = { onQuickAdd(BillType.REFUND) },
            modifier = Modifier.weight(1f),
        ) { Text("记退款") }
    }
}

@Composable
private fun StatsGrid(stats: HomeStats, onOpenBills: () -> Unit) {
    SectionCard {
        SectionTitle(title = "消费统计") {
            TextButton(onClick = onOpenBills) { Text("全部账单") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatTile(
                label = "今日支出",
                value = Money.format(stats.todayExpenseMinor),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "本周支出",
                value = Money.format(stats.weekExpenseMinor),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "本月支出",
                value = Money.format(stats.monthExpenseMinor),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatTile(
                label = "本月收入",
                value = Money.format(stats.monthIncomeMinor),
                valueColor = amountColorFor(BillType.INCOME),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "本月净收支",
                value = Money.formatSigned(stats.monthNetMinor),
                valueColor = if (stats.monthNetMinor >= 0) {
                    amountColorFor(BillType.INCOME)
                } else {
                    amountColorFor(BillType.EXPENSE)
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "与上月同期",
                value = stats.monthOverMonthRatio?.let { ratio ->
                    if (ratio >= 0) "+${"%.0f".format(ratio * 100)}%" else "${"%.0f".format(ratio * 100)}%"
                } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }

        if (stats.topCategories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            DividerThin()
            Spacer(modifier = Modifier.height(12.dp))
            Text("本月分类 TOP 3", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            stats.topCategories.forEach { spend ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = spend.emoji, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = spend.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Money.format(spend.amountMinor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        if (stats.dailyTrend.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            DividerThin()
            Spacer(modifier = Modifier.height(12.dp))
            Text("近 7 天消费趋势", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            TrendBars(stats)
        }
    }
}

@Composable
private fun TrendBars(stats: HomeStats) {
    val maxValue = stats.dailyTrend.maxOfOrNull { it.amountMinor }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        stats.dailyTrend.forEach { day ->
            val fraction = (day.amountMinor.toFloat() / maxValue.toFloat()).coerceIn(0.03f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((fraction * 56f).dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = LocalDate.ofEpochDay(day.epochDay).dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BudgetOverview(budget: BudgetStatus, onOpenBudget: () -> Unit) {
    SectionCard {
        SectionTitle(title = "预算概览") {
            TextButton(onClick = onOpenBudget) { Text("管理预算") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (budget.amountMinor == null) {
            Text(
                text = "尚未设置本月预算，设置后可查看每日可用预算。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        LinearProgressIndicator(
            progress = { budget.progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatTile(
                label = "已用",
                value = Money.format(budget.spentMinor),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = if (budget.isOverBudget) "超支" else "剩余",
                value = Money.format(if (budget.isOverBudget) -budget.remainingMinor else budget.remainingMinor),
                valueColor = if (budget.isOverBudget) {
                    amountColorFor(BillType.EXPENSE)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "每日可用",
                value = Money.format(budget.dailyAvailableMinor),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "剩余 ${budget.daysLeft} 天 · 预算 ${Money.format(budget.amountMinor)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentBills(
    bills: List<Bill>,
    onOpenAll: () -> Unit,
    onBillClick: (Bill) -> Unit,
) {
    SectionCard {
        SectionTitle(title = "最近账单") {
            TextButton(onClick = onOpenAll) { Text("查看全部") }
        }
        if (bills.isEmpty()) {
            EmptyHint("还没有账单，点击上方按钮记一笔。")
            return@SectionCard
        }
        bills.forEachIndexed { index, bill ->
            if (index > 0) DividerThin()
            BillRow(bill = bill, onClick = onBillClick)
        }
    }
}