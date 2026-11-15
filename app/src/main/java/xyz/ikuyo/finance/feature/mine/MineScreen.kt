package xyz.ikuyo.finance.feature.mine

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.annotation.StringRes
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.R
import xyz.ikuyo.finance.core.AppLanguage
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.notify.DailyReminder
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(
    onOpenBills: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenBudget: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onOpenTags: () -> Unit = {},
    onOpenOperations: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    onOpenFixedExpenses: () -> Unit = {},
    onOpenInstallments: () -> Unit = {},
    onOpenDebts: () -> Unit = {},
    onOpenSavings: () -> Unit = {},
    onOpenRates: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    viewModel: MineViewModel = viewModel(factory = MineViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 提醒时间选择与通知权限：权限被拒时保持关闭，并说明原因，不假装成功。
    var showTimePicker by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            viewModel.setReminderEnabled(true)
        } else {
            permissionDenied = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mine_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        SectionCard {
            SectionTitle(title = stringResource(R.string.section_data))
            Spacer(modifier = Modifier.height(4.dp))
            SettingRow(R.string.mine_stats, R.string.mine_stats_sub) { onOpenStats() }
            DividerThin()
            SettingRow(R.string.mine_reports, R.string.mine_reports_sub) { onOpenReports() }
            DividerThin()
            SettingRow(R.string.mine_fixed, R.string.mine_fixed_sub) { onOpenFixedExpenses() }
            DividerThin()
            SettingRow(R.string.mine_installments, R.string.mine_installments_sub) { onOpenInstallments() }
            DividerThin()
            SettingRow(R.string.mine_debts, R.string.mine_debts_sub) { onOpenDebts() }
            DividerThin()
            SettingRow(R.string.mine_savings, R.string.mine_savings_sub) { onOpenSavings() }
            DividerThin()
            SettingRow(R.string.mine_rates, R.string.mine_rates_sub) { onOpenRates() }
            DividerThin()
            SettingRow(R.string.mine_backup, R.string.mine_backup_sub) { onOpenBackup() }
            DividerThin()
            SettingRow(R.string.mine_export, R.string.mine_export_sub) { onOpenExport() }
            DividerThin()
            SettingRow(R.string.mine_import, R.string.mine_import_sub) { onOpenImport() }
            DividerThin()
            SettingRow(R.string.mine_bills, R.string.mine_bills_sub) { onOpenBills() }
            DividerThin()
            val trashSubtitle = if (state.trashCount > 0) {
                stringResource(R.string.mine_trash_sub_count, state.trashCount)
            } else {
                stringResource(R.string.mine_trash_sub)
            }
            SettingRow(title = stringResource(R.string.mine_trash), subtitle = trashSubtitle) { onOpenTrash() }
        }

        SectionCard {
            SectionTitle(title = stringResource(R.string.section_budget))
            Spacer(modifier = Modifier.height(4.dp))
            SettingRow(R.string.mine_budget, R.string.mine_budget_sub) { onOpenBudget() }
            DividerThin()
            SettingRow(R.string.mine_categories, R.string.mine_categories_sub) { onOpenCategories() }
            DividerThin()
            SettingRow(R.string.mine_tags, R.string.mine_tags_sub) { onOpenTags() }
        }

        SectionCard {
            SectionTitle(title = stringResource(R.string.section_record))
            Spacer(modifier = Modifier.height(4.dp))
            SettingRow(R.string.mine_operations, R.string.mine_operations_sub) { onOpenOperations() }
            DividerThin()
            SettingRow(R.string.mine_ai_settings, R.string.mine_ai_settings_sub) { onOpenAiSettings() }
        }

        SectionCard {
            SectionTitle(title = stringResource(R.string.section_reminder))
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { if (state.reminderEnabled) showTimePicker = true },
                ) {
                    Text(
                        text = stringResource(R.string.mine_reminder),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (state.reminderEnabled) {
                            stringResource(
                                R.string.mine_reminder_on,
                                "%02d:%02d".format(state.reminderHour, state.reminderMinute),
                            )
                        } else {
                            stringResource(R.string.mine_reminder_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.reminderEnabled,
                    onCheckedChange = { checked ->
                        when {
                            !checked -> viewModel.setReminderEnabled(false)
                            DailyReminder.canNotify(context) -> viewModel.setReminderEnabled(true)
                            // Android 13+ 需要运行时权限，申请结果回到 launcher 里处理。
                            else -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
            if (permissionDenied) {
                Text(
                    text = stringResource(R.string.reminder_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard {
            SectionTitle(title = stringResource(R.string.section_appearance))
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(
                    label = stringResource(R.string.theme_system),
                    value = SettingsRepository.THEME_SYSTEM,
                    current = state.themeMode,
                ) { viewModel.setThemeMode(it) }
                ThemeChip(
                    label = stringResource(R.string.theme_light),
                    value = SettingsRepository.THEME_LIGHT,
                    current = state.themeMode,
                ) { viewModel.setThemeMode(it) }
                ThemeChip(
                    label = stringResource(R.string.theme_dark),
                    value = SettingsRepository.THEME_DARK,
                    current = state.themeMode,
                ) { viewModel.setThemeMode(it) }
            }
            val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.appearance_dynamic),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(
                            if (supportsDynamic) {
                                R.string.appearance_dynamic_sub
                            } else {
                                R.string.appearance_dynamic_unsupported
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.dynamicColor && supportsDynamic,
                    onCheckedChange = { viewModel.setDynamicColor(it) },
                    enabled = supportsDynamic,
                )
            }
        }

        SectionCard {
            SectionTitle(title = stringResource(R.string.section_language))
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.all.forEach { mode ->
                    FilterChip(
                        selected = state.languageMode == mode,
                        onClick = {
                            if (state.languageMode != mode) {
                                viewModel.setLanguage(mode)
                                // 资源按 Context 解析，切换语言后必须重建界面才会生效。
                                (context as? Activity)?.recreate()
                            }
                        },
                        label = { Text(stringResource(languageLabelRes(mode))) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionCard {
            SectionTitle(title = stringResource(R.string.section_about))
            Spacer(modifier = Modifier.height(4.dp))
            InfoLine(
                label = stringResource(R.string.about_app),
                value = stringResource(R.string.about_app_value),
            )
            DividerThin()
            InfoLine(
                label = stringResource(R.string.about_version),
                value = stringResource(R.string.about_version_value),
            )
            DividerThin()
            InfoLine(
                label = stringResource(R.string.about_data),
                value = stringResource(R.string.about_data_value),
            )
            DividerThin()
            InfoLine(
                label = stringResource(R.string.about_precision),
                value = stringResource(R.string.about_precision_value),
            )
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = state.reminderHour,
            initialMinute = state.reminderMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.reminder_dialog_title)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setReminderTime(pickerState.hour, pickerState.minute)
                        showTimePicker = false
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingRow(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    onClick: () -> Unit,
) = SettingRow(
    title = stringResource(title),
    subtitle = stringResource(subtitle),
    onClick = onClick,
)

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 语言名走资源，避免在英文/日文界面里出现写死的中文标签。 */
@StringRes
private fun languageLabelRes(mode: String): Int = when (mode) {
    AppLanguage.SYSTEM -> R.string.lang_system
    AppLanguage.JAPANESE -> R.string.lang_ja
    AppLanguage.ENGLISH -> R.string.lang_en
    else -> R.string.lang_zh
}