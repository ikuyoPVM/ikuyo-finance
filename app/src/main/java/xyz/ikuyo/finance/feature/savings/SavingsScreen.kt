package xyz.ikuyo.finance.feature.savings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.SavingsGoal
import xyz.ikuyo.finance.domain.model.SavingsGoalStatus
import xyz.ikuyo.finance.domain.model.SavingsProgress
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle
import xyz.ikuyo.finance.ui.components.StatTile
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsScreen(
    onBack: () -> Unit = {},
    viewModel: SavingsViewModel = viewModel(factory = SavingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editorFor by remember { mutableStateOf<SavingsGoal?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var depositFor by remember { mutableStateOf<SavingsGoal?>(null) }
    var withdrawFor by remember { mutableStateOf<SavingsGoal?>(null) }
    var transferFrom by remember { mutableStateOf<SavingsGoal?>(null) }
    var deleteFor by remember { mutableStateOf<SavingsGoal?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("储蓄目标") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editorFor = null
                    showEditor = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("新增目标")
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SavingsIntroCard() }
            item { SavingsSummaryCard(state) }
            if (state.error != null) {
                item { NoticeRow(text = state.error!!, isError = true, onDismiss = viewModel::clearError) }
            }
            if (state.notice != null) {
                item { NoticeRow(text = state.notice!!, isError = false, onDismiss = viewModel::clearNotice) }
            }
            if (!state.hasAny) {
                item {
                    EmptyHint(
                        "还没有储蓄目标。存钱不等于消费：存入会减少可用余额、增加储蓄余额，总余额不变。"
                    )
                }
            } else {
                groupItem("进行中", state.active, state) { goal ->
                    SavingsGoalRow(
                        goal = goal,
                        progress = state.progressOf(goal),
                        suggestedMonthly = state.suggestedMonthly(goal),
                        onDeposit = { depositFor = goal },
                        onWithdraw = { withdrawFor = goal },
                        onTransfer = { transferFrom = goal },
                        onEdit = {
                            editorFor = goal
                            showEditor = true
                        },
                        onPause = { viewModel.setStatus(goal, SavingsGoalStatus.PAUSED) },
                        onResume = { viewModel.setStatus(goal, SavingsGoalStatus.ACTIVE) },
                        onComplete = { viewModel.setStatus(goal, SavingsGoalStatus.COMPLETED) },
                        onCancel = { viewModel.setStatus(goal, SavingsGoalStatus.CANCELLED) },
                        onDelete = { deleteFor = goal },
                    )
                }
                groupItem("已暂停", state.paused, state) { goal ->
                    SavingsGoalRow(
                        goal = goal,
                        progress = state.progressOf(goal),
                        suggestedMonthly = state.suggestedMonthly(goal),
                        onDeposit = { depositFor = goal },
                        onWithdraw = { withdrawFor = goal },
                        onTransfer = { transferFrom = goal },
                        onEdit = {
                            editorFor = goal
                            showEditor = true
                        },
                        onPause = { viewModel.setStatus(goal, SavingsGoalStatus.PAUSED) },
                        onResume = { viewModel.setStatus(goal, SavingsGoalStatus.ACTIVE) },
                        onComplete = { viewModel.setStatus(goal, SavingsGoalStatus.COMPLETED) },
                        onCancel = { viewModel.setStatus(goal, SavingsGoalStatus.CANCELLED) },
                        onDelete = { deleteFor = goal },
                    )
                }
                groupItem("已结束", state.closed, state) { goal ->
                    SavingsGoalRow(
                        goal = goal,
                        progress = state.progressOf(goal),
                        suggestedMonthly = state.suggestedMonthly(goal),
                        onDeposit = { depositFor = goal },
                        onWithdraw = { withdrawFor = goal },
                        onTransfer = { transferFrom = goal },
                        onEdit = {
                            editorFor = goal
                            showEditor = true
                        },
                        onPause = { viewModel.setStatus(goal, SavingsGoalStatus.COMPLETED) },
                        onResume = { viewModel.setStatus(goal, SavingsGoalStatus.ACTIVE) },
                        onComplete = { viewModel.setStatus(goal, SavingsGoalStatus.COMPLETED) },
                        onCancel = { viewModel.setStatus(goal, SavingsGoalStatus.CANCELLED) },
                        onDelete = { deleteFor = goal },
                    )
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showEditor) {
        SavingsEditorDialog(
            initial = editorFor,
            categories = state.categories,
            today = state.today,
            onDismiss = { showEditor = false },
            onSave = { goal ->
                showEditor = false
                viewModel.save(goal)
            },
        )
    }

    depositFor?.let { goal ->
        MovementDialog(
            title = "存入「${goal.name}」",
            hint = "存入不会生成账单：可用余额减少、储蓄余额增加，总余额不变。",
            initial = goal.targetMinor - state.progressOf(goal).savedMinor,
            confirmText = "存入",
            onDismiss = { depositFor = null },
            onConfirm = { amount, note ->
                depositFor = null
                viewModel.deposit(goal, amount, note)
            },
        )
    }

    withdrawFor?.let { goal ->
        MovementDialog(
            title = "从「${goal.name}」取出",
            hint = "取出随时可以进行，但要记录金额与原因；取出不计收入，总余额不变。",
            initial = state.progressOf(goal).savedMinor.coerceAtLeast(0L),
            confirmText = "取出",
            onDismiss = { withdrawFor = null },
            onConfirm = { amount, note ->
                withdrawFor = null
                viewModel.withdraw(goal, amount, note)
            },
        )
    }

    transferFrom?.let { from ->
        val targets = state.openGoals.filter { it.id != from.id }
        TransferDialog(
            from = from,
            targets = targets,
            saved = state.progressOf(from).savedMinor,
            onDismiss = { transferFrom = null },
            onConfirm = { to, amount ->
                transferFrom = null
                viewModel.transfer(from, to, amount)
            },
        )
    }

    deleteFor?.let { goal ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除目标") },
            text = { Text("删除「${goal.name}」会同时清除它的储蓄流水，已存入的金额不再计入储蓄余额。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    viewModel.delete(goal)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("取消") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groupItem(
    title: String,
    goals: List<SavingsGoal>,
    state: SavingsUiState,
    row: @Composable (SavingsGoal) -> Unit,
) {
    if (goals.isEmpty()) return
    item(key = "header-$title") {
        SectionTitle(title = "$title（${goals.size}）")
    }
    items(goals, key = { "goal-${it.id}" }) { goal ->
        row(goal)
    }
}

@Composable
private fun SavingsIntroCard() {
    SectionCard {
        Text("储蓄是资金划转，不是支出", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "存入：储蓄余额增加、可用余额减少；取出相反。两种情况总余额都不变，" +
                "也不计入收入或消费支出，账本里不会多出账单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavingsSummaryCard(state: SavingsUiState) {
    SectionCard {
        Text("储蓄余额", style = MaterialTheme.typography.labelMedium)
        Text(
            Money.format(state.totalSavedMinor),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "进行中",
                value = "${state.active.size} 个",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "已暂停",
                value = "${state.paused.size} 个",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "已结束",
                value = "${state.closed.size} 个",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NoticeRow(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val color = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    SectionCard(modifier = Modifier.clickable(onClick = onDismiss)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        Text(
            "点一下关闭",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavingsGoalRow(
    goal: SavingsGoal,
    progress: SavingsProgress,
    suggestedMonthly: Long?,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onTransfer: () -> Unit,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(goal.categoryEmoji, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(goal.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = buildString {
                        append(Money.format(goal.targetMinor))
                        append(" · 截止 ")
                        append(Time.formatDate(goal.dueAt))
                        progress.daysLeft?.let { days ->
                            append(
                                when {
                                    days > 0 -> "（还有 $days 天）"
                                    days == 0 -> "（今天到期）"
                                    else -> "（已超期 ${-days} 天）"
                                }
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                goal.status.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (goal.status == SavingsGoalStatus.CANCELLED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (progress.progressBp / 10_000f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "已存 ${Money.format(progress.savedMinor)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (progress.isReached) {
                    "已达成目标"
                } else {
                    "还差 ${Money.format(progress.remainingMinor)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (suggestedMonthly != null && !progress.isReached && goal.isOpen) {
            Text(
                "按剩余时间摊平，每月约需存入 ${Money.format(suggestedMonthly)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (goal.isOpen) {
                TextButton(onClick = onDeposit) { Text("存入") }
                if (progress.savedMinor > 0L) {
                    TextButton(onClick = onWithdraw) { Text("取出") }
                }
                TextButton(onClick = onTransfer) { Text("划转") }
            }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (goal.status) {
                SavingsGoalStatus.ACTIVE -> {
                    TextButton(onClick = onPause) { Text("暂停") }
                    TextButton(onClick = onComplete) { Text("标记达成") }
                }

                SavingsGoalStatus.PAUSED -> {
                    TextButton(onClick = onResume) { Text("恢复") }
                    TextButton(onClick = onCancel) { Text("取消目标") }
                }

                SavingsGoalStatus.COMPLETED -> {
                    TextButton(onClick = onResume) { Text("重新开启") }
                }

                SavingsGoalStatus.CANCELLED -> {
                    TextButton(onClick = onResume) { Text("重新开启") }
                }
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
        if (goal.note.isNotBlank()) {
            Text(
                goal.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavingsEditorDialog(
    initial: SavingsGoal?,
    categories: List<Category>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (SavingsGoal) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var targetText by remember(initial) {
        mutableStateOf(initial?.let { plainText(it.targetMinor) } ?: "")
    }
    var note by remember(initial) { mutableStateOf(initial?.note ?: "") }
    var dueAt by remember(initial) {
        mutableStateOf(initial?.dueAt ?: Time.toMillis(today.plusMonths(6)))
    }
    var categoryId by remember(initial) { mutableStateOf(initial?.categoryId) }
    var showPicker by remember { mutableStateOf(false) }

    val targetMinor = Money.parseToMinor(targetText)
    val canSave = name.isNotBlank() && targetMinor != null && targetMinor > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增储蓄目标" else "编辑储蓄目标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("目标名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("目标金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = Time.formatDate(dueAt),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("截止日期") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPicker = true },
                )
                if (categories.isNotEmpty()) {
                    SectionTitle(title = "分类（可选）")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = categoryId == null,
                            onClick = { categoryId = null },
                            label = { Text("不关联") },
                        )
                        categories.take(3).forEach { category ->
                            FilterChip(
                                selected = categoryId == category.id,
                                onClick = { categoryId = category.id },
                                label = { Text("${category.emoji} ${category.name}") },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "目标金额是「要存到多少」，不是消费金额，不会生成账单。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        (initial ?: SavingsGoal(id = 0L, name = name, targetMinor = 0L, dueAt = dueAt))
                            .copy(
                                name = name.trim(),
                                targetMinor = targetMinor ?: 0L,
                                dueAt = dueAt,
                                note = note.trim(),
                                categoryId = categoryId,
                            )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueAt)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        dueAt = Time.toMillis(
                            LocalDate.ofEpochDay(millis / 86_400_000L)
                        )
                    }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun MovementDialog(
    title: String,
    hint: String,
    initial: Long,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (amountMinor: Long, note: String) -> Unit,
) {
    var amountText by remember { mutableStateOf(if (initial > 0L) plainText(initial) else "") }
    var note by remember { mutableStateOf("") }
    val amountMinor = Money.parseToMinor(amountText)
    val canConfirm = amountMinor != null && amountMinor > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("原因 / 备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(amountMinor ?: 0L, note.trim()) },
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun TransferDialog(
    from: SavingsGoal,
    targets: List<SavingsGoal>,
    saved: Long,
    onDismiss: () -> Unit,
    onConfirm: (target: SavingsGoal, amountMinor: Long) -> Unit,
) {
    var selected by remember { mutableStateOf(targets.firstOrNull()) }
    var amountText by remember { mutableStateOf("") }
    val amountMinor = Money.parseToMinor(amountText)
    val canConfirm = selected != null && amountMinor != null && amountMinor > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从「${from.name}」划转") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (targets.isEmpty()) {
                    Text("至少需要另一个进行中的目标才能划转。")
                } else {
                    SectionTitle(title = "转到")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targets.forEach { target ->
                            FilterChip(
                                selected = selected?.id == target.id,
                                onClick = { selected = target },
                                label = { Text(target.name) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("金额（已存 ${Money.format(saved)}）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "划转会各记一笔转出与转入，总储蓄余额不变。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { selected?.let { onConfirm(it, amountMinor ?: 0L) } },
            ) { Text("划转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 输入框里的纯数字元文本，避免把千分位逗号带进解析。 */
private fun plainText(minorUnits: Long): String =
    java.math.BigDecimal.valueOf(minorUnits).movePointLeft(2).stripTrailingZeros().toPlainString()
