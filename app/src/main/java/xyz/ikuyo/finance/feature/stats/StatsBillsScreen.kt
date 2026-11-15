package xyz.ikuyo.finance.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.ui.components.BillRow
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.StatTile

/**
 * 统计下钻明细：按时间区间 + 可选分类/标签过滤，金额与图表同源（[Statistics] 的区间口径）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsBillsScreen(
    title: String,
    startMillis: Long,
    endMillisExclusive: Long,
    categoryId: Long? = null,
    tagId: Long? = null,
    onBack: () -> Unit = {},
    onOpenBill: (Long) -> Unit = {},
    viewModel: StatsBillsViewModel = viewModel(
        factory = StatsBillsViewModel.factory(startMillis, endMillisExclusive, categoryId, tagId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${Time.formatDate(startMillis)} 至 ${Time.formatDate(endMillisExclusive - 1)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.bills.isEmpty()) {
                item { EmptyHint("这个范围内没有符合条件的账单。") }
            } else {
                item {
                    SectionCard {
                        Text(
                            text = "合计",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatTile(
                                label = "支出",
                                value = Money.format(state.expenseMinor),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "退款",
                                value = Money.format(state.refundMinor),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "净支出",
                                value = Money.format(state.netExpenseMinor),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = "共 ${state.bills.size} 笔",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.bills, key = { it.id }) { bill ->
                    BillRow(bill = bill, onClick = { onOpenBill(it.id) })
                }
            }
        }
    }
}

