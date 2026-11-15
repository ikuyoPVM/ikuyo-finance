package xyz.ikuyo.finance.feature.imports

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.import.CsvImporter
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle

/**
 * CSV 导入页。
 *
 * 流程严格按计划书 §5：选文件 → 解析（只读，不写库）→ 预览新增/重复/冲突/无效 →
 * 用户确认 → 逐条写入 → 结果报告。未确认前不会产生任何账单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit = {},
    viewModel: ImportViewModel = viewModel(factory = ImportViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                viewModel.loadFile("", null)
            } else {
                // content:// 的 lastPathSegment 只是文档 id，用户看到的文件名要靠 DISPLAY_NAME。
                val name = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    }.getOrNull()
                } ?: uri.lastPathSegment
                viewModel.loadFile(text, name)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入数据") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.notice?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            SectionTitle(title = "选择文件")
            SectionCard {
                Text(
                    text = "支持本应用导出的 CSV，也支持按下方列名自行整理的表格。" +
                        "导入前会先解析并给出预览，不会直接写入账本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                        Text("选择 CSV 文件")
                    }
                    if (state.plan != null || state.result != null) {
                        OutlinedButton(onClick = viewModel::reset) {
                            Text("重新开始")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "可用列名：" + CsvImporter.TEMPLATE_HEADER.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "必填：日期、类型、原币金额。外币账单还需要汇率。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val plan = state.plan
            if (plan != null) {
                SectionTitle(title = "导入预览")
                SectionCard {
                    Text(
                        text = "文件：" + (state.fileName ?: "（未命名）") +
                            " · 共 " + plan.rows.size + " 行",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CountTile("新增", plan.newCount, MaterialTheme.colorScheme.primary)
                        CountTile("重复", plan.duplicateCount, MaterialTheme.colorScheme.outline)
                        CountTile("冲突", plan.conflictCount, MaterialTheme.colorScheme.tertiary)
                        CountTile("无效", plan.invalidCount, MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "可导入 " + plan.importCount + " 行（新增 + 冲突）；" +
                            "重复与无效行不会写入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (plan.mapping.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("列映射", style = MaterialTheme.typography.labelLarge)
                        plan.mapping.entries.forEach { (field, column) ->
                            Text(
                                text = field + " ← " + column,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (plan.ignoredColumns.isNotEmpty()) {
                            Text(
                                text = "忽略的列：" + plan.ignoredColumns.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (plan.rows.isNotEmpty()) {
                    SectionTitle(title = "明细（前 20 行）")
                    SectionCard {
                        plan.rows.take(20).forEach { row ->
                            RowPreview(row)
                            HorizontalDivider()
                        }
                        if (plan.rows.size > 20) {
                            Text(
                                text = "还有 " + (plan.rows.size - 20) + " 行未展示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Button(
                    onClick = viewModel::askConfirm,
                    enabled = state.canImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (state.busy) {
                            "正在导入…"
                        } else {
                            "确认导入 " + plan.importCount + " 行"
                        },
                    )
                }
            }

            state.result?.let { result ->
                SectionTitle(title = "导入结果")
                SectionCard {
                    Text(result.message, style = MaterialTheme.typography.bodyMedium)
                    if (result.failures.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        result.failures.forEach { failure ->
                            Text(
                                text = failure,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "导入的账单已进入账本，可在账单列表与统计中查看；重复与无效行已跳过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "说明：导入只新增账单，不修改或删除已有账单；重复判定结合日期、金额、" +
                    "类型、分类与备注，不只看金额。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.showConfirm) {
        val importCount = state.plan?.importCount ?: 0
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            title = { Text("确认导入") },
            text = {
                Text(
                    "即将向账本新增 " + importCount + " 笔账单，" +
                        "重复与无效行不会写入。导入不会删除或修改现有账单，导入后可在账单列表核对。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text("开始导入") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirm) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CountTile(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowPreview(row: CsvImporter.Row) {
    val statusColor = when (row.status) {
        CsvImporter.Status.NEW -> MaterialTheme.colorScheme.primary
        CsvImporter.Status.DUPLICATE -> MaterialTheme.colorScheme.outline
        CsvImporter.Status.CONFLICT -> MaterialTheme.colorScheme.tertiary
        CsvImporter.Status.INVALID -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 " + row.lineNumber + " 行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.status.label,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = Time.formatDate(row.occurredAt) + " · " + row.type.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = Money.format(row.amountMinor, row.currency),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = row.categoryName + if (row.note.isBlank()) "" else " · " + row.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Default,
        )
        row.reason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}