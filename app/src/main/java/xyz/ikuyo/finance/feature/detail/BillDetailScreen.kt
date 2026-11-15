package xyz.ikuyo.finance.feature.detail

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.ui.components.BillAmount
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle
import xyz.ikuyo.finance.ui.theme.amountColorFor

@Composable
fun BillDetailScreen(
    billId: Long,
    onBack: () -> Unit = {},
    onEdit: (Long) -> Unit = {},
    viewModel: BillDetailViewModel = viewModel(factory = BillDetailViewModel.factory(billId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这笔账单？") },
            text = { Text("账单会移入回收站，可在回收站恢复；删除后不再计入余额、预算与统计。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
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
                    Text(
                        text = "账单详情",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val bill = state.bill
            if (state.isMissing || bill == null) {
                item { EmptyHint("这笔账单不存在，可能已被彻底删除。") }
            } else {
                item {
                    SectionCard {
                        Column {
                            Text(
                                text = "${bill.type.label} · ${bill.categoryEmoji} ${bill.categoryName ?: "未分类"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // 主金额统一展示人民币折算口径（与统计、余额一致），原币另行标注。
                            BillAmount(amountMinor = bill.cnyAmountMinor, type = bill.type)
                            if (bill.currency != Money.BASE_CURRENCY) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "原币 ${Money.format(bill.amountMinor, bill.currency)} · " +
                                        "汇率 ${Money.formatRate(bill.exchangeRateScaled)} · " +
                                        "入账 ${Money.format(bill.cnyAmountMinor)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "汇率快照 ${bill.rateSource.label}" +
                                        if (bill.rateUpdatedAt > 0L) " · ${Time.formatDateTime(bill.rateUpdatedAt)}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("信息")
                            DetailLine("支付方式", bill.paymentMethod.label)
                            DetailLine("消费时间", Time.formatDateTime(bill.occurredAt))
                            DetailLine("录入时间", Time.formatDateTime(bill.createdAt))
                            DetailLine("来源", if (bill.source == xyz.ikuyo.finance.domain.model.BillSource.AI) "AI 识别" else "手动录入")
                            DetailLine("备注", bill.note.ifBlank { "—" })
                            DetailLine(
                                "标签",
                                if (state.billTags.isEmpty()) "—" else state.billTags.joinToString(" ") { "#${it.name}" },
                            )
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { onEdit(bill.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text("编辑") }
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                item {
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle("这笔账单的操作记录")
                            if (state.operations.isEmpty()) {
                                Text(
                                    text = "暂无记录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                state.operations.forEach { operation ->
                                    Text(
                                        text = "${operation.summary} · ${Time.formatDateTime(operation.operatedAt)}" +
                                            if (operation.undone) " · 已撤销" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        Text(
                            text = "账单金额以「分」为整数单位保存，显示时才格式化；" +
                                "退款会抵扣原支出，不计入收入。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    DividerThin()
}