package xyz.ikuyo.finance.feature.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.ui.components.BillRow
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.StatTile
import xyz.ikuyo.finance.ui.theme.amountColorFor
import java.time.LocalDate

@Composable
fun BillsScreen(
    onOpenTrash: () -> Unit = {},
    onOpenDetail: (Long) -> Unit = {},
    viewModel: BillsViewModel = viewModel(factory = BillsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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
                        viewModel.undoDelete()
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "全部账单",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenTrash) { Text("回收站") }
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "排序方式")
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("按消费时间") },
                        onClick = {
                            viewModel.setSortOrder(BillSortOrder.OCCURRED_DESC)
                            sortMenuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("按录入时间") },
                        onClick = {
                            viewModel.setSortOrder(BillSortOrder.CREATED_DESC)
                            sortMenuOpen = false
                        },
                    )
                }
            }

            SectionCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatTile(
                        label = "支出",
                        value = Money.format(state.totalExpenseMinor),
                        valueColor = amountColorFor(BillType.EXPENSE),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "收入",
                        value = Money.format(state.totalIncomeMinor),
                        valueColor = amountColorFor(BillType.INCOME),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "退款",
                        value = Money.format(state.refundedMinor),
                        valueColor = amountColorFor(BillType.REFUND),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filterType == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = state.filterType == BillType.EXPENSE,
                    onClick = { viewModel.setFilter(BillType.EXPENSE) },
                    label = { Text("支出") },
                )
                FilterChip(
                    selected = state.filterType == BillType.INCOME,
                    onClick = { viewModel.setFilter(BillType.INCOME) },
                    label = { Text("收入") },
                )
                FilterChip(
                    selected = state.filterType == BillType.REFUND,
                    onClick = { viewModel.setFilter(BillType.REFUND) },
                    label = { Text("退款") },
                )
            }

            if (state.isEmpty) {
                EmptyHint("该筛选条件下没有账单。")
            } else {
                val grouped = remember(state.bills) {
                    state.bills.groupBy { Time.toLocalDate(it.occurredAt) }
                        .toSortedMap(compareByDescending<LocalDate> { it })
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    grouped.forEach { (day, bills) ->
                        item(key = "day-$day") {
                            DayHeader(day = day, bills = bills)
                        }
                        items(items = bills, key = { "bill-${it.id}" }) { bill ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BillRow(
                                    bill = bill,
                                    onClick = { onOpenDetail(bill.id) },
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { viewModel.delete(bill) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "footer") { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, bills: List<Bill>) {
    val expense = bills.filter { it.type == BillType.EXPENSE }.sumOf { it.cnyAmountMinor }
    val income = bills.filter { it.type == BillType.INCOME }.sumOf { it.cnyAmountMinor }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Time.describeDay(Time.toMillis(day)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (expense > 0) {
            Text(
                text = "支出 ${Money.format(expense)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (income > 0) {
            Text(
                text = "收入 ${Money.format(income)}",
                style = MaterialTheme.typography.labelSmall,
                color = amountColorFor(BillType.INCOME),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}