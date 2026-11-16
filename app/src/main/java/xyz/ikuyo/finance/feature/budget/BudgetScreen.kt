package xyz.ikuyo.finance.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.ui.notice.AppSnackbarHost
import xyz.ikuyo.finance.ui.notice.showEvent
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetChange
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.BudgetTemplate
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.StatTile
import xyz.ikuyo.finance.ui.theme.amountColorFor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(
    onBack: () -> Unit = {},
    viewModel: BudgetViewModel = viewModel(factory = BudgetViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var totalInput by remember(state.totalBudgetMinor) {
        mutableStateOf(state.totalBudgetMinor?.let { Money.format(it, showSymbol = false) } ?: "")
    }
    var editingCategory by remember { mutableStateOf<CategoryBudgetRow?>(null) }
    var categoryInput by remember { mutableStateOf("") }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    // 标签预算
    var showTagPicker by remember { mutableStateOf(false) }
    var tagTarget by remember { mutableStateOf<Tag?>(null) }
    var tagInput by remember { mutableStateOf("") }

    // 专项预算
    var editingSpecial by remember { mutableStateOf<SpecialBudgetRow?>(null) }
    var specialDialogOpen by remember { mutableStateOf(false) }
    var specialName by remember { mutableStateOf("") }
    var specialAmount by remember { mutableStateOf("") }
    var specialPeriod by remember { mutableStateOf(BudgetPeriodType.MONTHLY) }
    var specialNote by remember { mutableStateOf("") }

    // 模板
    var showSaveTemplate by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }

    // 删除确认
    var pendingDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showEvent(event)
            }
        }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
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
                            text = "预算管理",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "本周期：${state.windowLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("总预算", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        if (state.hasTotalBudget) {
                            Text("启用", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(0.dp))
                            Switch(
                                checked = state.totalEnabled,
                                onCheckedChange = { viewModel.setTotalEnabled(it) },
                            )
                        }
                    }
                    if (state.hasTotalBudget && !state.totalEnabled) {
                        Text(
                            text = "已暂停：暂停期间不计入提醒，统计照常显示。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "预算周期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BudgetPeriodType.entries.forEach { type ->
                            FilterChip(
                                selected = state.periodType == type,
                                onClick = { viewModel.selectPeriod(type) },
                                label = { Text(type.label) },
                            )
                        }
                    }

                    if (state.periodType == BudgetPeriodType.CUSTOM) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showStartPicker = true }) {
                                Text(
                                    "起 " + (state.customStart?.let { Time.formatFullDate(it) } ?: "选择日期")
                                )
                            }
                            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { showEndPicker = true }) {
                                Text(
                                    "止 " + (state.customEnd?.let { Time.formatFullDate(it) } ?: "选择日期")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = totalInput,
                            onValueChange = { totalInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("预算金额") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        Button(onClick = { viewModel.saveTotal(totalInput) }) { Text("保存") }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("结转上期结余", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "上期剩余计入本周期；上期超支不结转",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.carryOver,
                            onCheckedChange = { viewModel.setCarryOver(it) },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    if (!state.hasTotalBudget) {
                        Text(
                            text = "尚未设置总预算。设置后首页会显示进度、每日可用预算与预警。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        BudgetProgressBlock(state.total)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showSaveTemplate = true }) { Text("存为模板") }
                            TextButton(onClick = {
                                pendingDelete = "总预算" to { viewModel.clearTotal() }
                            }) {
                                Text("删除预算", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "分类预算",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                    )
                    Text(
                        text = "周期同总预算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(items = state.categories, key = { "cat-${it.category.id}" }) { row ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.category.emoji, style = MaterialTheme.typography.titleLarge)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        ) {
                            Text(row.category.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (row.amountMinor == null) {
                                    "本周期已花 ${Money.format(row.spentMinor)} · 未设预算"
                                } else {
                                    "本周期已花 ${Money.format(row.spentMinor)} / ${Money.format(row.amountMinor)}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.isOverBudget) {
                                    amountColorFor(BillType.EXPENSE)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(
                            onClick = {
                                editingCategory = row
                                categoryInput = row.amountMinor?.let {
                                    Money.format(it, showSymbol = false)
                                } ?: ""
                            },
                        ) { Text(if (row.amountMinor == null) "设置" else "修改") }
                    }
                    if (row.amountMinor != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { row.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "标签预算",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                    )
                    TextButton(onClick = {
                        tagTarget = null
                        tagInput = ""
                        showTagPicker = true
                    }) { Text("添加") }
                }
            }

            if (state.tagBudgets.isEmpty()) {
                item {
                    EmptyHint("按标签单独设预算，例如「聚餐」每月 ¥800。先到「标签管理」创建标签。")
                }
            }

            items(items = state.tagBudgets, key = { "tag-${it.tag.id}" }) { row ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${row.tag.name}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "本周期已花 ${Money.format(row.spentMinor)} / ${Money.format(row.amountMinor ?: 0L)}" +
                                    if (row.enabled) "" else " · 已暂停",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = row.enabled,
                            onCheckedChange = { viewModel.setTagBudgetEnabled(row.tag.id, it) },
                        )
                        IconButton(onClick = {
                            pendingDelete = "#${row.tag.name}" to { viewModel.deleteTagBudget(row.tag.id) }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { row.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "专项预算",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                    )
                    TextButton(onClick = {
                        editingSpecial = null
                        specialName = ""
                        specialAmount = ""
                        specialPeriod = BudgetPeriodType.MONTHLY
                        specialNote = ""
                        specialDialogOpen = true
                    }) { Text("新建") }
                }
            }

            if (state.specials.isEmpty()) {
                item { EmptyHint("给一次性支出定额度，例如「双十一 ¥2000」「旅行 ¥5000」。") }
            }

            items(items = state.specials, key = { "special-${it.budget.id}" }) { row ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.budget.displayName(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${row.windowLabel} · ${row.budget.periodType.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = row.budget.enabled,
                            onCheckedChange = { viewModel.setBudgetEnabled(row.budget.id, it) },
                        )
                        IconButton(onClick = {
                            pendingDelete = row.budget.displayName() to { viewModel.deleteBudget(row.budget.id) }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (!row.budget.enabled) {
                        Text(
                            text = "已暂停：暂停期间不再提醒。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    BudgetProgressBlock(row.status)
                    if (!row.budget.note.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = row.budget.note.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = {
                        editingSpecial = row
                        specialName = row.budget.name.orEmpty()
                        specialAmount = Money.format(row.budget.amountMinor, showSymbol = false)
                        specialPeriod = row.budget.periodType
                        specialNote = row.budget.note.orEmpty()
                        specialDialogOpen = true
                    }) { Text("编辑") }
                }
            }

            if (state.templates.isNotEmpty()) {
                item {
                    Text(
                        text = "预算模板",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item {
                    SectionCard {
                        state.templates.forEachIndexed { index, template ->
                            if (index > 0) DividerThin()
                            TemplateRow(
                                template = template,
                                onApply = { viewModel.applyTemplate(template) },
                                onDelete = { viewModel.deleteTemplate(template) },
                            )
                        }
                    }
                }
            }

            if (state.changes.isNotEmpty()) {
                item {
                    Text(
                        text = "预算变更历史",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item {
                    SectionCard {
                        state.changes.forEachIndexed { index, change ->
                            if (index > 0) DividerThin()
                            ChangeRow(change)
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text("说明", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "· 预算周期可选每月 / 每周 / 每日 / 自定义区间，自定义可跨自然月。\n" +
                            "· 只统计本周期支出（退款自动抵扣），分类预算与总预算共用同一周期。\n" +
                            "· 标签预算统计该标签下账单的净支出；专项预算各自拥有独立周期。\n" +
                            "· 开启结转后，上期剩余额度计入本期；上期超支不结转。\n" +
                            "· 修改预算不会影响任何历史账单，变更会记录在「预算变更历史」中。\n" +
                            "· 每日可用 = 剩余额度 ÷ 本周期剩余天数（含今天）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DividerThin()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "预警：已用 50% / 80% / 达上限 / 超支时各提醒一次，" +
                            "进入下一级别会再次提醒；暂停或删除预算后不再提醒，新周期开始后自动重置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val editing = editingCategory
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editingCategory = null },
            title = { Text("${editing.category.emoji} ${editing.category.name} 预算") },
            text = {
                Column(modifier = Modifier.imePadding()) {
                    Text(
                        text = "周期跟随总预算（${state.periodType.label}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text("留空表示清除预算") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveCategoryBudget(editing.category.id, categoryInput)
                        editingCategory = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingCategory = null }) { Text("取消") }
            },
        )
    }

    if (showTagPicker) {
        AlertDialog(
            onDismissRequest = { showTagPicker = false },
            title = { Text("标签预算") },
            text = {
                Column(modifier = Modifier.imePadding()) {
                    if (tagTarget == null) {
                        Text(
                            text = "选择标签",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.availableTags.forEach { tag ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        tagTarget = tag
                                        tagInput = state.tagBudgets
                                            .firstOrNull { it.tag.id == tag.id }
                                            ?.amountMinor
                                            ?.let { Money.format(it, showSymbol = false) }
                                            ?: ""
                                    },
                                    label = { Text("#${tag.name}") },
                                )
                            }
                        }
                        if (state.availableTags.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "还没有标签，请先到「标签管理」创建。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    } else {
                        Text(
                            text = "#${tagTarget?.name} · 周期跟随总预算（${state.periodType.label}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { tagInput = it },
                            label = { Text("留空表示清除预算") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = tagTarget
                        if (target != null) {
                            viewModel.saveTagBudget(target.id, tagInput)
                            showTagPicker = false
                            tagTarget = null
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTagPicker = false
                    tagTarget = null
                }) { Text("取消") }
            },
        )
    }

    if (specialDialogOpen) {
        AlertDialog(
            onDismissRequest = { specialDialogOpen = false },
            title = { Text(if (editingSpecial == null) "新建专项预算" else "编辑专项预算") },
            text = {
                Column(modifier = Modifier.imePadding()) {
                    OutlinedTextField(
                        value = specialName,
                        onValueChange = { specialName = it.take(16) },
                        label = { Text("名称（如 双十一、旅行）") },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = specialAmount,
                        onValueChange = { specialAmount = it },
                        label = { Text("金额") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "周期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BudgetPeriodType.entries.forEach { type ->
                            FilterChip(
                                selected = specialPeriod == type,
                                onClick = { specialPeriod = type },
                                label = { Text(type.label) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = specialNote,
                        onValueChange = { specialNote = it.take(60) },
                        label = { Text("备注（可选）") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveSpecial(
                            budgetId = editingSpecial?.budget?.id ?: 0L,
                            name = specialName,
                            input = specialAmount,
                            periodType = specialPeriod,
                            note = specialNote,
                        )
                        specialDialogOpen = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { specialDialogOpen = false }) { Text("取消") }
            },
        )
    }

    if (showSaveTemplate) {
        AlertDialog(
            onDismissRequest = { showSaveTemplate = false },
            title = { Text("保存为模板") },
            text = {
                Column(modifier = Modifier.imePadding()) {
                    Text(
                        text = "模板会保存金额、周期与结转设置，之后可一键套用到总预算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = templateName,
                        onValueChange = { templateName = it.take(16) },
                        label = { Text("模板名称（可留空）") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveTemplate(templateName)
                        showSaveTemplate = false
                        templateName = ""
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplate = false }) { Text("取消") }
            },
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除预算？") },
            text = {
                Text("将删除「${deleteTarget.first}」的预算额度与提醒；已发生的账单、标签与实际支出都不受影响，删除后可在「预算变更历史」看到记录。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        deleteTarget.second()
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showStartPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.customStart ?: Time.startOfMonth(),
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = pickerState.selectedDateMillis
                        if (picked != null) {
                            val start = Time.startOfDay(Time.toLocalDate(picked))
                            val end = state.customEnd ?: Time.endOfDayInclusive(Time.toLocalDate(picked))
                            viewModel.setCustomRange(start, end)
                        }
                        showStartPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text("取消") }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (showEndPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.customEnd ?: Time.endOfMonthInclusive(),
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = pickerState.selectedDateMillis
                        if (picked != null) {
                            val end = Time.endOfDayInclusive(Time.toLocalDate(picked))
                            val start = state.customStart ?: Time.startOfDay(Time.toLocalDate(picked))
                            viewModel.setCustomRange(start, end)
                        }
                        showEndPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) { Text("取消") }
            },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun BudgetProgressBlock(status: BudgetStatus) {
    LinearProgressIndicator(
        progress = { status.progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatTile(
            label = "已用",
            value = Money.format(status.spentMinor),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = if (status.isOverBudget) "超支" else "剩余",
            value = Money.format(
                if (status.isOverBudget) -status.remainingMinor else status.remainingMinor
            ),
            valueColor = if (status.isOverBudget) {
                amountColorFor(BillType.EXPENSE)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "每日可用",
            value = Money.format(status.dailyAvailableMinor),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "已用 ${status.usedPercent}% · ${status.alertLevel.label}" +
            if (status.carryOverMinor > 0L) " · 含结转 ${Money.format(status.carryOverMinor)}" else "",
        style = MaterialTheme.typography.labelSmall,
        color = if (status.alertLevel.needsAttention) {
            amountColorFor(BillType.EXPENSE)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "剩余 ${status.daysLeft} 天 · 额度 ${Money.format(status.availableMinor)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TemplateRow(
    template: BudgetTemplate,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(template.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${template.periodType.label} · ${Money.format(template.amountMinor)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onApply) { Text("应用") }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除模板",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ChangeRow(change: BudgetChange) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${change.targetLabel} · ${change.periodType.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = Time.formatDateTime(change.changedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = changeAmountText(change),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun changeAmountText(change: BudgetChange): String {
    val from = change.oldAmountMinor?.let { Money.format(it) } ?: "未设置"
    val to = change.newAmountMinor?.let { Money.format(it) } ?: "已清除"
    return "$from → $to"
}

@Suppress("unused")
private val alertLevels = BudgetAlertLevel.entries