package xyz.ikuyo.finance.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.StatTile
import xyz.ikuyo.finance.ui.theme.amountColorFor
import xyz.ikuyo.finance.domain.model.BillType

@Composable
fun BudgetScreen(
    onBack: () -> Unit = {},
    viewModel: BudgetViewModel = viewModel(factory = BudgetViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var totalInput by remember(state.totalBudgetMinor) {
        mutableStateOf(state.totalBudgetMinor?.let { Money.format(it, showSymbol = false) } ?: "")
    }
    var editingCategory by remember { mutableStateOf<CategoryBudgetRow?>(null) }
    var categoryInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column {
                        Text(
                            text = "预算管理",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = state.monthLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Text("本月总预算", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = totalInput,
                            onValueChange = { totalInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("预算金额") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        Button(onClick = { viewModel.saveTotal(totalInput) }) { Text("保存") }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (state.totalBudgetMinor == null) {
                        Text(
                            text = "尚未设置本月总预算。填写金额后保存，首页会显示每日可用预算。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { state.total.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatTile(
                                label = "已用",
                                value = Money.format(state.total.spentMinor),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = if (state.total.isOverBudget) "超支" else "剩余",
                                value = Money.format(
                                    if (state.total.isOverBudget) {
                                        -state.total.remainingMinor
                                    } else {
                                        state.total.remainingMinor
                                    }
                                ),
                                valueColor = if (state.total.isOverBudget) {
                                    amountColorFor(BillType.EXPENSE)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "每日可用",
                                value = Money.format(state.total.dailyAvailableMinor),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "分类预算",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(items = state.categories, key = { it.category.id }) { row ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.category.emoji, style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(0.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        ) {
                            Text(row.category.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (row.amountMinor == null) {
                                    "本月已花 ${Money.format(row.spentMinor)} · 未设预算"
                                } else {
                                    "本月已花 ${Money.format(row.spentMinor)} / ${Money.format(row.amountMinor)}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.isOverBudget) {
                                    amountColorFor(BillType.EXPENSE)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(
                            onClick = {
                                editingCategory = row
                                categoryInput = row.amountMinor?.let {
                                    Money.format(it, showSymbol = false)
                                } ?: ""
                            },
                        ) { Text(if (row.amountMinor == null) "设置" else "修改") }
                    }
                    if (row.amountMinor != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { row.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Text("说明", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "· 预算按自然月计算，只统计本月支出（退款自动抵扣）。\n" +
                            "· 修改预算不会影响任何历史账单。\n" +
                            "· 每日可用 = 剩余预算 ÷ 本月剩余天数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DividerThin()
                }
            }
        }
    }

    val editing = editingCategory
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editingCategory = null },
            title = { Text("${editing.category.emoji} ${editing.category.name} 预算") },
            text = {
                Column(modifier = Modifier.imePadding()) {
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text("留空表示清除预算") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveCategoryBudget(editing.category.id, categoryInput)
                        editingCategory = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingCategory = null }) { Text("取消") }
            },
        )
    }
}