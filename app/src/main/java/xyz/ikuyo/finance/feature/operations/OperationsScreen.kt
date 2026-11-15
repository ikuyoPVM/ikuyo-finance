package xyz.ikuyo.finance.feature.operations

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard

@Composable
fun OperationsScreen(
    onBack: () -> Unit = {},
    viewModel: OperationsViewModel = viewModel(factory = OperationsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                            text = "操作历史",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "最近 100 条 · 可逐条撤销",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Text(
                        text = "这里记录每一次新增、修改、删除、恢复、彻底删除与撤销。" +
                            "撤销会按记录把账单还原到当时的状态，删除则从回收站恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.isEmpty) {
                item { EmptyHint("还没有操作记录，记一笔账就会出现在这里。") }
            } else {
                items(items = state.operations, key = { it.id }) { operation ->
                    OperationRow(operation = operation, onUndo = { viewModel.undo(operation) })
                }
            }

            item {
                SectionCard {
                    Text(
                        text = "历史本身不参与余额、预算与统计计算；撤销一条记录不会连带撤销更早的记录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationRow(operation: BillOperation, onUndo: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = operation.summary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${operation.type.label} · ${Time.formatDateTime(operation.operatedAt)}" +
                        if (operation.undone) " · 已撤销" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (operation.undone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (operation.isUndoable) {
                TextButton(onClick = onUndo) { Text("撤销") }
            }
        }
        if (operation.isUndoable) {
            Spacer(modifier = Modifier.height(4.dp))
            DividerThin()
        }
    }
}