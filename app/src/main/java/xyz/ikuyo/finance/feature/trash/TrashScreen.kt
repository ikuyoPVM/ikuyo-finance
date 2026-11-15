package xyz.ikuyo.finance.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.ui.components.BillAmount
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard

@Composable
fun TrashScreen(
    onBack: () -> Unit = {},
    viewModel: TrashViewModel = viewModel(factory = TrashViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmBatchPurge by remember { mutableStateOf(false) }

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
        bottomBar = {
            if (state.selectionMode) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已选 ${state.selectedCount} 笔",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.restoreSelected() }) { Text("恢复") }
                        TextButton(onClick = { confirmBatchPurge = true }) {
                            Text("彻底删除", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { viewModel.clearSelection() }) { Text("取消") }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "回收站",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (!state.isEmpty) {
                    TextButton(onClick = { viewModel.toggleSelectAll() }) {
                        Text(if (state.allSelected) "取消全选" else "全选")
                    }
                    TextButton(onClick = { confirmClear = true }) { Text("清空") }
                }
            }

            Text(
                text = "回收站内的账单不计入余额与统计；勾选后可批量恢复或彻底删除。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (state.isEmpty) {
                EmptyHint("回收站是空的。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = state.bills, key = { it.id }) { bill ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = bill.id in state.selectedIds,
                                    onCheckedChange = { viewModel.toggleSelect(bill.id) },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bill.note.ifBlank { bill.categoryName ?: "未分类" },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = "${Time.formatDateTime(bill.occurredAt)} · ${bill.categoryName ?: "未分类"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                BillAmount(amountMinor = bill.cnyAmountMinor, type = bill.type)
                            }
                            DividerThin(modifier = Modifier.padding(vertical = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { viewModel.restore(bill) }) { Text("恢复") }
                                TextButton(onClick = { viewModel.purge(bill) }) {
                                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(
                            text = "恢复会退回原来的消费时间与分类；彻底删除后无法恢复，也不可撤销。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空回收站") },
            text = { Text("清空后无法恢复，确定继续吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.purgeAll()
                        confirmClear = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    if (confirmBatchPurge) {
        AlertDialog(
            onDismissRequest = { confirmBatchPurge = false },
            title = { Text("彻底删除所选的 ${state.selectedCount} 笔账单？") },
            text = { Text("彻底删除后无法恢复，也不可撤销；余额与统计会立即更新。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.purgeSelected()
                        confirmBatchPurge = false
                    },
                ) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchPurge = false }) { Text("取消") }
            },
        )
    }
}