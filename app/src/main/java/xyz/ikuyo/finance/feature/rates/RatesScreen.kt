package xyz.ikuyo.finance.feature.rates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.CurrencyCatalog
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle

/**
 * 汇率与币种：只维护本地「1 外币 = 多少人民币」。
 *
 * 记账时汇率会写进账单快照，这里改汇率不会回头改写历史账单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatesScreen(
    onBack: () -> Unit = {},
    viewModel: RatesViewModel = viewModel(factory = RatesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<CurrencyRate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("汇率与币种") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "汇率以人民币为基准",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "记账时保存的是「当时使用的汇率」，之后改汇率不会改写历史账单。" +
                                "没有设置汇率的币种，记账时需要当场填写，否则不能保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "已设置 ${state.configuredCount} 种外币汇率",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "自动获取汇率",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "从公开汇率服务取「1 外币 = 多少人民币」，成功后标记为自动并记录时间。" +
                                "取不到时会明确提示，已保存的汇率降级为缓存，不会被当成最新汇率。人民币记账不受影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { viewModel.refresh() }, enabled = !state.busy) {
                            Text(if (state.busy) "正在获取…" else "刷新汇率")
                        }
                        state.staleHint?.let { hint ->
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }

            state.error?.let { message ->
                item {
                    SectionCard {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            state.notice?.let { message ->
                item {
                    SectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { viewModel.clearMessage() }) { Text("知道了") }
                        }
                    }
                }
            }

            item { SectionTitle("币种") }

            items(CurrencyCatalog.supported) { code ->
                val rate = state.rateOf(code)
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = CurrencyCatalog.label(code),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (code == Money.BASE_CURRENCY) "基础货币" else rate?.source?.label ?: "未设置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = if (code == Money.BASE_CURRENCY) {
                                "1 CNY = ¥1.00（固定）"
                            } else if (rate == null) {
                                "还没有这个币种的汇率，记账时需要手填或用「设置汇率」保存一份。"
                            } else {
                                "1 $code = ¥${Money.formatRate(rate.rateScaled)}" +
                                    if (rate.updatedAt > 0L) " · 更新于 ${Time.formatDateTime(rate.updatedAt)}" else ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (code != Money.BASE_CURRENCY) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editing = code }) {
                                    Text(if (rate == null) "设置汇率" else "修改汇率")
                                }
                                if (rate != null) {
                                    TextButton(onClick = { deleting = rate }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                EmptyHint("汇率来源与更新时间会一起记进账单，便于事后核对折算口径。")
            }
        }
    }

    editing?.let { code ->
        val existing = state.rateOf(code)
        RateEditorDialog(
            currency = code,
            initial = existing?.rate?.stripTrailingZeros()?.toPlainString().orEmpty(),
            onDismiss = { editing = null },
            onConfirm = { text ->
                viewModel.saveRate(code, text)
                editing = null
            },
        )
    }

    deleting?.let { rate ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除汇率") },
            text = {
                Text(
                    "删除${CurrencyCatalog.label(rate.currency)}的汇率后，之前的账单仍然保留当时的汇率快照，" +
                        "只是新记账时需要重新填写。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeRate(rate.currency)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RateEditorDialog(
    currency: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${CurrencyCatalog.label(currency)} 汇率") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "填写 1 $currency 合多少人民币，最多 6 位小数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw.filter { it.isDigit() || it == '.' }.take(12)
                    },
                    label = { Text("汇率") },
                    prefix = { Text("¥") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}