package xyz.ikuyo.finance.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.ui.components.BillEntryForm
import xyz.ikuyo.finance.ui.components.EmptyHint

@Composable
fun BillEditScreen(
    billId: Long,
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    viewModel: BillEditViewModel = viewModel(factory = BillEditViewModel.factory(billId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    LaunchedEffect(saved) {
        if (saved) onSaved()
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "编辑账单",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            val bill = state.bill
            if (state.isMissing || bill == null) {
                EmptyHint("这笔账单不存在，可能已被彻底删除。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        BillEntryForm(
                            categories = state.categories,
                            initialType = bill.type,
                            initialAmountMinor = bill.amountMinor,
                            initialCategoryId = bill.categoryId,
                            initialPayment = bill.paymentMethod,
                            initialNote = bill.note,
                            initialOccurredAt = bill.occurredAt,
            initialCurrency = bill.currency,
            initialExchangeRateScaled = bill.exchangeRateScaled,
                            allowTypeSwitch = true,
                            confirmText = "保存修改",
                            onConfirm = { draft -> viewModel.save(draft) },
                            tags = state.allTags,
                            initialTagIds = state.tagIds,
                        )
                    }
                    item {
                        Text(
                            text = "修改会写入操作历史，可在「我的 → 操作历史」撤销；历史账单的汇率不会被后来的汇率改写。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}