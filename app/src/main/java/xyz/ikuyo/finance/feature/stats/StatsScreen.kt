package xyz.ikuyo.finance.feature.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import xyz.ikuyo.finance.data.ai.AiErrorType
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.usecase.AnomalyDetector
import xyz.ikuyo.finance.domain.usecase.SpendingFacts
import xyz.ikuyo.finance.domain.usecase.SpendingForecast
import xyz.ikuyo.finance.domain.usecase.Statistics
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle
import xyz.ikuyo.finance.ui.components.StatTile
import xyz.ikuyo.finance.ui.theme.amountColorFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 从统计页下钻到明细时携带的过滤条件。 */
data class StatsDrillDown(
    val title: String,
    val startMillis: Long,
    val endMillisExclusive: Long,
    val categoryId: Long? = null,
    val tagId: Long? = null,
)

/**
 * 统计分析页：所有金额都来自 [Statistics]（唯一口径来源），页面只负责展示与下钻。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit = {},
    onDrillDown: (StatsDrillDown) -> Unit = {},
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "统计分析", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = snapshot?.range?.label ?: "正在统计…",
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
            item {
                RangeSelector(
                    rangeType = state.rangeType,
                    range = snapshot?.range ?: state.range,
                    onTypeChange = { viewModel.setRangeType(it) },
                    onPickCustom = { showStartPicker = true },
                    onClearCustom = { viewModel.clearCustomRange() },
                )
            }

            if (state.isEmpty) {
                item { EmptyHint("这个区间还没有账单。先记几笔，这里就会出现真实统计。") }
            } else if (snapshot != null) {
                item { TotalsCard(snapshot) }
                item {
                    SectionCard {
                        Text(
                            text = "消费趋势",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (snapshot.daily.size > 1) "点某一天可以查看当天账单" else "只有一天的数据，没有可比趋势",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (snapshot.daily.size > 1) {
                            TrendLineChart(
                                points = snapshot.daily,
                                onPointClick = { point ->
                                    onDrillDown(
                                        StatsDrillDown(
                                            title = Time.formatMonthDay(point.millis),
                                            startMillis = Time.startOfDay(point.date),
                                            endMillisExclusive = Time.endOfDayExclusive(point.date),
                                        ),
                                    )
                                },
                            )
                            TrendAxisLabels(points = snapshot.daily)
                        } else {
                            Text(
                                text = "当天净支出 ${Money.format(snapshot.totals.netExpenseMinor)}，明细见下方账单与分类占比。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (snapshot.categories.isNotEmpty()) {
                    item {
                        SectionCard {
                            Text(
                                text = "分类占比",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryDonut(
                                    stats = snapshot.categories.take(6),
                                    totalNetExpenseMinor = snapshot.totals.netExpenseMinor,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    snapshot.categories.take(6).forEach { stat ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(text = stat.emoji, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = stat.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = "${stat.shareBp / 100}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SectionCard {
                            Text(
                                text = "分类排名",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "点分类查看该分类的账单明细",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val max = snapshot.categories.maxOfOrNull { it.netExpenseMinor } ?: 0L
                            snapshot.categories.forEachIndexed { index, stat ->
                                CategoryRankRow(
                                    rank = index + 1,
                                    stat = stat,
                                    maxNetExpenseMinor = max,
                                    changeText = changeTextOf(stat.changePercent),
                                    onClick = {
                                        onDrillDown(
                                            StatsDrillDown(
                                                title = "${stat.name} · ${snapshot.range.label}",
                                                startMillis = snapshot.range.startMillis,
                                                endMillisExclusive = snapshot.range.endMillisExclusive,
                                                categoryId = stat.categoryId,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (snapshot.tags.isNotEmpty()) {
                    item {
                        SectionCard {
                            Text(
                                text = "标签统计",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "一笔账单可以挂多个标签，所以标签合计可能大于总支出。点标签查看该标签的账单",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            snapshot.tags.forEach { tag ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onDrillDown(
                                                StatsDrillDown(
                                                    title = "#${tag.name} · ${snapshot.range.label}",
                                                    startMillis = snapshot.range.startMillis,
                                                    endMillisExclusive = snapshot.range.endMillisExclusive,
                                                    tagId = tag.tagId,
                                                ),
                                            )
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(text = "#${tag.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${tag.billCount} 笔 · ${tag.shareBp / 100}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(text = Money.format(tag.netExpenseMinor), style = MaterialTheme.typography.bodyMedium)
                                }
                                DividerThin()
                            }
                        }
                    }
                }
                if (snapshot.budgets.isNotEmpty()) {
                    item {
                        SectionCard {
                            Text(
                                text = "预算执行",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "按当前周期计算，超支不会截断百分比",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            snapshot.budgets.forEach { execution ->
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = execution.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = "${execution.usedPercent}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (execution.overMinor > 0) amountColorFor(BillType.EXPENSE) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = buildString {
                                            append("已用 ")
                                            append(Money.format(execution.spentMinor))
                                            append(" / ")
                                            append(Money.format(execution.amountMinor))
                                            if (execution.overMinor > 0) {
                                                append(" · 超支 ")
                                                append(Money.format(execution.overMinor))
                                            } else {
                                                append(" · 剩余 ")
                                                append(Money.format(execution.availableMinor - execution.spentMinor))
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "时间进度 ${execution.timeProgressBp / 100}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = paceTextOf(execution.paceMinor),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (execution.paceMinor > 50L) {
                                                amountColorFor(BillType.EXPENSE)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { (execution.usedPercent / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (execution.overMinor > 0) amountColorFor(BillType.EXPENSE) else MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    val forecast = state.forecast
                    if (forecast != null) ForecastCard(forecast)
                }
                item {
                    AnomalyCard(
                        anomalies = state.anomalies,
                        onMarkNormal = viewModel::markAnomalyNormal,
                        onMuteKind = viewModel::muteAnomalyKind,
                    )
                }
                item {
                    val analysis = state.analysis
                    val currentKey = "${snapshot.range.startMillis}-${snapshot.range.endMillisExclusive}"
                    SectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "AI 消费分析",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (analysis.running) {
                                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                            } else {
                                TextButton(onClick = { viewModel.analyze() }) {
                                    Text(text = if (analysis.hasResult) "重新分析" else "生成分析")
                                }
                            }
                        }
                        Text(
                            text = "数字来自本地统计，AI 只负责解释；建议不会改动任何账单或预算。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (analysis.isStale(currentKey)) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "区间已变化，下面的结论属于旧区间，请重新分析。",
                                style = MaterialTheme.typography.labelSmall,
                                color = amountColorFor(BillType.EXPENSE),
                            )
                        }
                        analysis.error?.let { error ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "分析未完成：${error.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = amountColorFor(BillType.EXPENSE),
                            )
                            if (error.type == AiErrorType.NOT_CONFIGURED) {
                                Text(
                                    text = "请先在「我的 → AI 设置」里配置模型与 API Key。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        analysis.insight?.let { insight ->
                            Spacer(modifier = Modifier.height(8.dp))
                            AnalysisSection(title = "事实", lines = insight.facts)
                            AnalysisSection(title = "估算", lines = insight.estimates)
                            AnalysisSection(title = "建议", lines = insight.suggestions)
                            AnalysisSection(title = "说明", lines = insight.caveats)
                        }
                        analysis.insight?.let {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "数据充分性：${(analysis.facts?.sufficiency ?: SpendingFacts.from(snapshot).sufficiency).label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    SectionCard {
                        Text(
                            text = "账单明细",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "区间内共 ${snapshot.totals.billCount} 笔，图表数字与这里的明细一致",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                onDrillDown(
                                    StatsDrillDown(
                                        title = snapshot.range.label,
                                        startMillis = snapshot.range.startMillis,
                                        endMillisExclusive = snapshot.range.endMillisExclusive,
                                    ),
                                )
                            },
                        ) {
                            Text(text = "查看全部区间账单")
                        }
                    }
                }
                item {
                    SectionCard {
                        Text(
                            text = "统计口径",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "支出只算支出账单；退款冲减原支出、不计收入；净支出 = 支出 − 退款。分类与标签占比的分母都是区间净支出；已删除账单不参与统计；金额以「分」为整数单位，不用浮点数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showStartPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.customStart
                ?.let { Time.startOfDay(it) }
                ?: Time.startOfDay(LocalDate.now(Time.zone)),
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val start = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            viewModel.setCustomRange(start, state.customEnd ?: start)
                        }
                        showStartPicker = false
                        showEndPicker = true
                    },
                ) { Text(text = "下一步") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text(text = "取消") }
            },
        ) {
            DatePicker(state = pickerState, title = { Text(text = "选择开始日期", modifier = Modifier.padding(16.dp)) })
        }
    }

    if (showEndPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.customEnd
                ?.let { Time.startOfDay(it) }
                ?: state.customStart?.let { Time.startOfDay(it) }
                ?: Time.startOfDay(LocalDate.now(Time.zone)),
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val end = pickerState.selectedDateMillis
                            ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                            ?: state.customStart
                            ?: LocalDate.now(Time.zone)
                        viewModel.setCustomRange(state.customStart ?: end, end)
                        showEndPicker = false
                    },
                ) { Text(text = "确定") }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) { Text(text = "取消") }
            },
        ) {
            DatePicker(state = pickerState, title = { Text(text = "选择结束日期", modifier = Modifier.padding(16.dp)) })
        }
    }
}

@Composable
private fun RangeSelector(
    rangeType: Statistics.RangeType,
    range: Statistics.Range,
    onTypeChange: (Statistics.RangeType) -> Unit,
    onPickCustom: () -> Unit,
    onClearCustom: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Statistics.RangeType.entries.forEach { type ->
                FilterChip(
                    selected = rangeType == type,
                    onClick = {
                        if (type == Statistics.RangeType.CUSTOM) onPickCustom() else onTypeChange(type)
                    },
                    label = { Text(text = type.label) },
                )
            }
        }
        if (rangeType == Statistics.RangeType.CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${range.label} · ${Time.formatDate(range.startMillis)} 至 ${Time.formatDate(range.endMillisExclusive - 1)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickCustom) { Text(text = "改日期") }
                TextButton(onClick = onClearCustom) { Text(text = "清除") }
            }
        }
    }
}

@Composable
private fun TotalsCard(snapshot: Statistics.Snapshot) {
    SectionCard {
        Text(
            text = "收支总览",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "支出",
                value = Money.format(snapshot.totals.expenseMinor),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "退款",
                value = Money.format(snapshot.totals.refundMinor),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "收入",
                value = Money.format(snapshot.totals.incomeMinor),
                modifier = Modifier.weight(1f),
                valueColor = amountColorFor(BillType.INCOME),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "净支出 ${Money.format(snapshot.totals.netExpenseMinor)} · ${snapshot.totals.billCount} 笔",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "净收支 ${Money.format(snapshot.totals.netMinor)}",
                style = MaterialTheme.typography.bodySmall,
                color = amountColorFor(BillType.INCOME),
            )
        }
        DividerThin()
        Text(
            text = buildString {
                append("与")
                append(snapshot.range.previousLabel)
                append("对比：支出 ")
                append(changeText(snapshot.expenseChangePercent))
                append("，收入 ")
                append(changeText(snapshot.incomeChangePercent))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun changeText(percent: Int?): String = when {
    percent == null -> "无基期数据"
    percent > 0 -> "+$percent%"
    else -> "$percent%"
}

/** 分类/标签的「较上期」文案；没有上期数据时不显示，避免把 0 当成真实变化。 */
private fun changeTextOf(percent: Int?): String? =
    if (percent == null) null else "较上期 ${changeText(percent)}"

/** 预算节奏：实际支出与「按时间进度应花」的差额。 */
private fun paceTextOf(paceMinor: Long): String = when {
    kotlin.math.abs(paceMinor) < 50L -> "与时间进度基本同步"
    paceMinor > 0L -> "较节奏超前 ${Money.format(paceMinor)}"
    else -> "较节奏落后 ${Money.format(kotlin.math.abs(paceMinor))}"
}

/** AI 分析的一段：标题 + 逐条结论；空段不占位。 */
@Composable
private fun AnalysisSection(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
    lines.forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 消费预测卡（计划书第 5 章）。
 *
 * 明确写出「预测」与可信度、依据、影响因素：让用户看得出这个数字是怎么来的，
 * 而不是一个无法核对的黑箱结论。
 */
@Composable
private fun ForecastCard(forecast: SpendingForecast.Forecast) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(title = "消费预测", modifier = Modifier.weight(1f))
            Text(
                text = "可信度 ${forecast.confidence.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "按本月已发生的真实账单推算，只是预测，不是已发生金额。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (!forecast.hasBudget && forecast.spentMinor == 0L) {
            Text(
                text = "本月还没有账单，暂时不给预测。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "预计本月支出",
                value = Money.format(forecast.projectedTotalMinor),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = if (forecast.willOverrun) "可能超支" else "预计剩余",
                value = Money.format(
                    if (forecast.willOverrun) forecast.projectedOverMinor else (forecast.projectedRemainingMinor ?: 0L),
                ),
                modifier = Modifier.weight(1f),
                valueColor = if (forecast.willOverrun) {
                    amountColorFor(BillType.EXPENSE)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "已花 ${Money.format(forecast.spentMinor)} · 已过 ${forecast.daysElapsed}/${forecast.daysTotal} 天 · 剩 ${forecast.daysLeft} 天",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnalysisSection(title = "推算依据", lines = forecast.basis)
        AnalysisSection(title = "主要影响因素", lines = forecast.factors)
    }
}


/**
 * 异常提醒卡：只提示，不动账单。每条都能标记「正常消费」或整类忽略，避免反复打扰。
 */
@Composable
private fun AnomalyCard(
    anomalies: List<AnomalyDetector.Candidate>,
    onMarkNormal: (String) -> Unit,
    onMuteKind: (AnomalyDetector.Kind) -> Unit,
) {
    SectionCard {
        SectionTitle(
            title = "异常提醒",
            action = {
                Text(
                    text = "${anomalies.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Text(
            text = "依据是你自己的历史，不是固定金额阈值。只提醒，不会修改或删除账单。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (anomalies.isEmpty()) {
            Text(
                text = "最近没有发现值得提醒的变化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        anomalies.forEach { candidate ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = candidate.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = candidate.kind.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = candidate.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                candidate.evidence.forEach { line ->
                    Text(
                        text = "· $line",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    TextButton(onClick = { onMarkNormal(candidate.id) }) {
                        Text(text = "这是正常消费", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { onMuteKind(candidate.kind) }) {
                        Text(text = "忽略这类", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            DividerThin()
        }
    }
}