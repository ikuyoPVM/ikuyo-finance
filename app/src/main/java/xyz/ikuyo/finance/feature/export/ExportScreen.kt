package xyz.ikuyo.finance.feature.export

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle

/**
 * CSV 导出页。
 *
 * 流程刻意做成「先看预览再保存」：预览里的条数、列名、样例与实际导出走同一条筛选路径，
 * 避免用户在不知情的情况下导出一份空表或范围错误的表。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit = {},
    viewModel: ExportViewModel = viewModel(factory = ExportViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingCsv by remember { mutableStateOf<String?>(null) }
    var pendingName by remember { mutableStateOf("ikuyo-export.csv") }
    var pendingXlsx by remember { mutableStateOf<ByteArray?>(null) }
    var pendingXlsxName by remember { mutableStateOf("ikuyo-report.xlsx") }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val text = pendingCsv
        if (uri == null || text == null) {
            pendingCsv = null
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选位置")
        }
        pendingCsv = null
        scope.launch {
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "已导出 ${text.length} 字符，可直接用表格软件打开" },
                    onFailure = { it.message ?: "导出失败" },
                ),
            )
        }
    }

    val excelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    ) { uri ->
        val bytes = pendingXlsx
        if (uri == null || bytes == null) {
            pendingXlsx = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(bytes)
                    } ?: error("无法写入所选位置")
                }
            }
            pendingXlsx = null
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "已导出 Excel 报告（${bytes.size} 字节），含四个工作表" },
                    onFailure = { it.message ?: "导出失败" },
                ),
            )
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbar.showSnackbar(message)
            viewModel.dismissNotice()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbar.showSnackbar(message)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出数据") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Text(
                        text = "导出的 CSV 用 UTF-8 编码，金额为纯数字，日期为 ISO 格式；以 = + - @ 开头的文本会自动加前缀，防止被表格软件当作公式执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard {
                    SectionTitle("导出内容")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportContent.entries.forEach { content ->
                            FilterChip(
                                selected = state.content == content,
                                onClick = { viewModel.setContent(content) },
                                label = { Text(content.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "预览范围：${state.rangeLabel} · 命中 ${state.preview.count} 行",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state.content == ExportContent.BILLS) {
                item {
                    SectionCard {
                        SectionTitle("时间范围")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExportRangeType.entries.forEach { type ->
                                FilterChip(
                                    selected = state.rangeType == type,
                                    onClick = { viewModel.setRangeType(type) },
                                    label = { Text(type.label) },
                                )
                            }
                        }
                        if (state.hasCustomRange) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showStartPicker = true }) {
                                    Text("开始：" + (state.customStart?.let { Time.toLocalDate(it).toString() } ?: "未选"))
                                }
                                TextButton(onClick = { showEndPicker = true }) {
                                    Text("结束：" + (state.customEndInclusive?.let { Time.toLocalDate(it).toString() } ?: "未选"))
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        SectionTitle("收支类型（不选＝全部）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BillType.entries.forEach { type ->
                                FilterChip(
                                    selected = type in state.types,
                                    onClick = { viewModel.toggleType(type) },
                                    label = { Text(type.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("包含已删除账单", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "回收站里的账单会带「是否已删除 = 是」出现在导出结果中",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.includeDeleted,
                                onCheckedChange = { viewModel.setIncludeDeleted(it) },
                            )
                        }
                    }
                }

                item {
                    SectionCard {
                        SectionTitle("分类（不选＝全部分类）")
                        if (viewModel.categories.isEmpty()) {
                            EmptyHint("还没有自定义分类")
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(viewModel.categories, key = { it.id }) { category ->
                                    FilterChip(
                                        selected = category.id in state.selectedCategoryIds,
                                        onClick = { viewModel.toggleCategory(category.id) },
                                        label = { Text(category.emoji + " " + category.name) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SectionTitle("标签（不选＝全部标签）")
                        if (viewModel.tags.isEmpty()) {
                            EmptyHint("还没有标签")
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(viewModel.tags, key = { it.id }) { tag ->
                                    FilterChip(
                                        selected = tag.id in state.selectedTagIds,
                                        onClick = { viewModel.toggleTag(tag.id) },
                                        label = { Text("#" + tag.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    SectionTitle("列结构与样例")
                    Text(
                        text = state.preview.columns.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.preview.sample.isEmpty()) {
                        EmptyHint("当前条件下没有数据")
                    } else {
                        state.preview.sample.forEach { line ->
                            Text(text = line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        pendingName = viewModel.suggestedFileName()
                        viewModel.buildCsv { text ->
                            pendingCsv = text
                            saveLauncher.launch(pendingName)
                        }
                    },
                    enabled = !state.busy && state.preview.count > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.busy) "正在生成…" else "选择保存位置并导出")
                }
            }

            item {
                Button(
                    onClick = {
                        pendingXlsxName = viewModel.suggestedExcelName()
                        viewModel.buildWorkbook { data ->
                            pendingXlsx = data
                            excelLauncher.launch(pendingXlsxName)
                        }
                    },
                    enabled = !state.busy && state.preview.count > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.busy) "正在生成…" else "导出 Excel 报告（四个工作表）")
                }
            }

            item {
                Text(
                    text = "说明：CSV 只导出所选内容与筛选结果，不会修改任何本地数据；预算执行数字与统计页共用同一份统计服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showStartPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.customStart ?: Time.nowMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.selectedDateMillis
                    if (picked != null) {
                        val start = Time.startOfDay(Time.toLocalDate(picked))
                        viewModel.setCustomRange(start, state.customEndInclusive ?: Time.endOfDayInclusive(Time.toLocalDate(picked)))
                    }
                    showStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showEndPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.customEndInclusive ?: Time.nowMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.selectedDateMillis
                    if (picked != null) {
                        val end = Time.endOfDayInclusive(Time.toLocalDate(picked))
                        viewModel.setCustomRange(state.customStart ?: Time.startOfDay(Time.toLocalDate(picked)), end)
                    }
                    showEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}