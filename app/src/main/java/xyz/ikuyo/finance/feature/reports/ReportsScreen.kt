package xyz.ikuyo.finance.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.MonthlyReport
import xyz.ikuyo.finance.domain.model.SummarySection
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle
import xyz.ikuyo.finance.ui.theme.amountColorFor

/**
 * 月度总结页（阶段四第 7 步）。
 *
 * 只展示已落库的报告：数字来自 [xyz.ikuyo.finance.domain.usecase.MonthlySummaryBuilder]，
 * 页面不做二次计算，避免「图表一套口径、报告另一套口径」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit = {},
    viewModel: ReportsViewModel = viewModel(factory = ReportsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("月度总结") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = viewModel::generateCurrentMonth,
                        enabled = !state.generating,
                    ) { Text("生成本月") }
                    TextButton(
                        onClick = viewModel::generateLastMonth,
                        enabled = !state.generating,
                    ) { Text("生成上月") }
                    if (state.generating) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).padding(start = 4.dp))
                    }
                }
            }

            state.error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = amountColorFor(BillType.EXPENSE),
                    )
                }
            }

            if (!state.hasReports) {
                item {
                    EmptyHint("还没有月报。点上面的「生成本月」或「生成上月」，用已有账单总结一次。")
                }
            }

            // 版本列表：每个版本一行，选中的高亮说明。
            items(state.reports, key = { it.id }) { report ->
                ReportRow(
                    report = report,
                    selected = report.id == state.selected?.id,
                    onClick = { viewModel.select(report.id) },
                )
            }

            state.selected?.let { report ->
                item { Spacer(modifier = Modifier.height(4.dp)) }
                item { ReportHeader(report) }
                report.sections.forEach { section ->
                    item { SectionCardBlock(section) }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = viewModel::regenerateSelected,
                            enabled = !state.generating,
                        ) { Text("重新生成本月") }
                        TextButton(
                            onClick = { viewModel.delete(report.id) },
                            enabled = !state.generating,
                        ) { Text("删除这一版") }
                    }
                }
                item {
                    Text(
                        text = "重新生成会新增一个版本，这一版的原样记录会保留在列表里，不会被后来的账单改写。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ReportRow(report: MonthlyReport, selected: Boolean, onClick: () -> Unit) {
    SectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "${report.monthLabel} · 版本 ${report.version}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "生成于 ${Time.formatDateTime(report.generatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Money.format(report.netExpenseMinor),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = amountColorFor(BillType.EXPENSE),
                )
                Text(
                    text = if (report.superseded) "已被新版取代" else if (selected) "正在查看" else "点查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReportHeader(report: MonthlyReport) {
    Column {
        Text(
            text = "${report.monthLabel} 月度总结",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append("版本 ${report.version}")
                append(" · 生成时间 ${Time.formatDateTime(report.generatedAt)}")
                append(" · 数据范围 ${Time.formatDate(report.dataStart)} 至 ${Time.formatDate(report.dataEndInclusive)}")
                append(" · ${report.billCount} 笔")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!report.hasData) {
            Text(
                text = "这个月没有符合条件的账单，以下内容只说明数据不足。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        report.narrative?.takeIf { it.isNotBlank() }?.let { narrative ->
            Text(
                text = narrative,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionCardBlock(section: SummarySection) {
    if (!section.hasContent) return
    SectionCard {
        SectionTitle(section.title)
        section.items.forEach { line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
        DividerThin()
    }
}