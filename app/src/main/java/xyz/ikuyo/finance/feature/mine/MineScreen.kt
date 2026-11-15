package xyz.ikuyo.finance.feature.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle

@Composable
fun MineScreen(
    onOpenBills: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenBudget: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    viewModel: MineViewModel = viewModel(factory = MineViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        SectionCard {
            SectionTitle(title = "数据")
            Spacer(modifier = Modifier.height(4.dp))
            SettingRow(title = "全部账单", subtitle = "查看、筛选、删除账单") { onOpenBills() }
            DividerThin()
            SettingRow(
                title = "回收站",
                subtitle = if (state.trashCount > 0) "共 ${state.trashCount} 条待处理" else "已删除的账单不计入统计",
            ) { onOpenTrash() }
        }

        SectionCard {
            SectionTitle(title = "预算与分类")
            Spacer(modifier = Modifier.height(4.dp))
            SettingRow(title = "预算管理", subtitle = "月度总预算与分类预算") { onOpenBudget() }
            DividerThin()
            SettingRow(title = "分类管理", subtitle = "新增、重命名、删除自定义分类") { onOpenCategories() }
        }

        SectionCard {
            SectionTitle(title = "外观")
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip("跟随系统", SettingsRepository.THEME_SYSTEM, state.themeMode) {
                    viewModel.setThemeMode(it)
                }
                ThemeChip("浅色", SettingsRepository.THEME_LIGHT, state.themeMode) {
                    viewModel.setThemeMode(it)
                }
                ThemeChip("深色", SettingsRepository.THEME_DARK, state.themeMode) {
                    viewModel.setThemeMode(it)
                }
            }
        }

        SectionCard {
            SectionTitle(title = "关于")
            Spacer(modifier = Modifier.height(8.dp))
            InfoLine("应用", "ikuyo 记账（ikuyo Finance）")
            InfoLine("版本", "0.1.0-dev · 阶段 0/1")
            InfoLine("数据", "全部保存在本机，不上传云端")
            InfoLine("金额精度", "以「分」为整数单位，不使用浮点数")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AI 记账（阶段 3）与统计报表（阶段 4）将在后续版本接入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ThemeChip(
    label: String,
    value: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}