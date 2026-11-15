package xyz.ikuyo.finance.feature.debt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import xyz.ikuyo.finance.domain.model.Debt
import xyz.ikuyo.finance.domain.model.DebtProgress
import xyz.ikuyo.finance.domain.model.DebtType
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import java.time.LocalDate
import java.time.LocalTime

/**
 * 债务与借出（阶段五第 4 步）。
 *
 * 这一页只处理「本金流转」和「利息」，本金永远不会变成本月支出或收入：
 * 借入 ¥1000 不会让本月收入变多，归还本金也不会让本月支出变多，
 * 只有利息与手续费会以账单形式进账本（欠款方向记支出，借出方向记收入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtsScreen(
    onBack: () -> Unit = {},
    viewModel: DebtViewModel = viewModel(factory = DebtViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Debt?>(null) }
    var repaying by remember { mutableStateOf<Debt?>(null) }
    var deleting by remember { mutableStateOf<Debt?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("债务") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Text("＋") },
                text = { Text("新增") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DebtIntroCard() }

            item {
                DebtSummaryCard(
                    state = state,
                )
            }

            state.error?.let { message ->
                item {
                    NoticeRow(
                        text = message,
                        isError = true,
                        onClick = viewModel::clearError,
                    )
                }
            }

            state.notice?.let { message ->
                item {
                    NoticeRow(
                        text = message,
                        isError = false,
                        onClick = viewModel::clearNotice,
                    )
                }
            }

            if (!state.hasAny) {
                item {
                    EmptyHint("还没有债务记录。借入、借出都可以记在这里，只有确认还款或收回时才更新本金。")
                }
            } else {
                groupItem(
                    title = "欠款",
                    hint = "借入或消费信贷，尚未还清。",
                    items = state.openOwed,
                    state = state,
                    onRepay = { repaying = it },
                    onEdit = { editing = it },
                    onToggle = { debt -> viewModel.setEnabled(debt, !debt.enabled) },
                    onSettle = { debt -> viewModel.settleNow(debt) },
                    onDelete = { deleting = it },
                )
                groupItem(
                    title = "借出",
                    hint = "借给别人的钱，等待收回。",
                    items = state.openLent,
                    state = state,
                    onRepay = { repaying = it },
                    onEdit = { editing = it },
                    onToggle = { debt -> viewModel.setEnabled(debt, !debt.enabled) },
                    onSettle = { debt -> viewModel.settleNow(debt) },
                    onDelete = { deleting = it },
                )
                groupItem(
                    title = "已结清",
                    hint = "本金已经全部了结，只保留记录。",
                    items = state.settled,
                    state = state,
                    onRepay = null,
                    onEdit = { editing = it },
                    onToggle = { debt -> viewModel.setEnabled(debt, !debt.enabled) },
                    onSettle = null,
                    onDelete = { deleting = it },
                )
            }

            item {
                Text(
                    text = "本金流转不进出收支统计；只有利息和手续费会生成账单。已结清的债务不会出现在提醒里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (creating) {
        DebtEditorDialog(
            initial = null,
            categories = state.categories,
            onDismiss = { creating = false },
            onSave = { debt ->
                creating = false
                viewModel.save(debt)
            },
        )
    }

    editing?.let { target ->
        DebtEditorDialog(
            initial = target,
            categories = state.categories,
            onDismiss = { editing = null },
            onSave = { debt ->
                editing = null
                viewModel.save(debt)
            },
        )
    }

    repaying?.let { target ->
        val progress = state.progressOf(target)
        RepayDialog(
            debt = target,
            progress = progress,
            onDismiss = { repaying = null },
            onConfirm = { principal, extra, note ->
                repaying = null
                viewModel.repay(target, principalMinor = principal, extraMinor = extra, note = note)
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条债务记录？") },
            text = {
                Text(
                    "「${target.name}」的还款历史会一起删除；已经生成的利息账单不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(target)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groupItem(
    title: String,
    hint: String,
    items: List<Debt>,
    state: DebtUiState,
    onRepay: ((Debt) -> Unit)?,
    onEdit: (Debt) -> Unit,
    onToggle: (Debt) -> Unit,
    onSettle: ((Debt) -> Unit)?,
    onDelete: (Debt) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$title（${items.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(items = items, key = { "debt-${it.id}" }) { debt ->
        DebtRow(
            debt = debt,
            progress = state.progressOf(debt),
            days = state.daysUntil(debt),
            onRepay = onRepay?.let { action -> { action(debt) } },
            onEdit = { onEdit(debt) },
            onToggle = { onToggle(debt) },
            onSettle = onSettle?.let { action -> { action(debt) } },
            onDelete = { onDelete(debt) },
        )
    }
}

@Composable
private fun DebtIntroCard() {
    SectionCard {
        Text("债务与借出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "借入本金不算收入，归还本金不算支出；只有利息和手续费才进账本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DebtSummaryCard(state: DebtUiState) {
    SectionCard {
        Text("总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("借入总额", state.borrowedTotalMinor, Modifier.weight(1f))
            SummaryCell("已偿还", state.repaidMinor, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("剩余欠款", state.remainingOwedMinor, Modifier.weight(1f), emphasize = true)
            SummaryCell("借出总额", state.lentTotalMinor, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell("待收回", state.toReceiveMinor, Modifier.weight(1f))
            SummaryCell("净债务", state.netDebtMinor, Modifier.weight(1f), emphasize = true)
        }
        if (state.extraPaidMinor > 0L || state.extraReceivedMinor > 0L) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryCell("已付利息", state.extraPaidMinor, Modifier.weight(1f))
                SummaryCell("已收利息", state.extraReceivedMinor, Modifier.weight(1f))
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "还没有利息记录；利息与手续费会以账单形式出现在账本里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCell(
    label: String,
    amountMinor: Long,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.format(amountMinor),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasize) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NoticeRow(text: String, isError: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(
            text = "$text（点一下关闭）",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun DebtRow(
    debt: Debt,
    progress: DebtProgress,
    days: Int?,
    onRepay: (() -> Unit)?,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onSettle: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = debt.type.label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = debt.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (!debt.enabled && !progress.isSettled) {
                Text(
                    text = "已暂停",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${if (debt.type.isOwed) "剩余欠款" else "待收回"} " +
                "${Money.format(progress.remainingPrincipalMinor)} / 共 ${Money.format(debt.principalMinor)}",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.progressBp / 10_000f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )

        Spacer(Modifier.height(6.dp))
        val dueLine = buildString {
            append(if (debt.type.isOwed) "到期 " else "约定归还 ")
            append(Time.formatDate(debt.dueAt))
            if (days != null) {
                append(" · ")
                append(
                    when {
                        days < 0 -> "已逾期 ${-days} 天"
                        days == 0 -> "今天到期"
                        else -> "还有 $days 天"
                    }
                )
            }
        }
        Text(
            text = dueLine,
            style = MaterialTheme.typography.bodySmall,
            color = if (days != null && days < 0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (progress.isSettled) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "本金已了结" + (debt.settledAt?.let { "（${Time.formatDate(it)}）" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onRepay != null) {
                TextButton(onClick = onRepay) {
                    Text(if (debt.type.isOwed) "还款" else "收回")
                }
            }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onToggle) { Text(if (debt.enabled) "暂停" else "恢复") }
            if (onSettle != null) {
                TextButton(onClick = onSettle) { Text("结清") }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebtEditorDialog(
    initial: Debt?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Debt) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: DebtType.BORROWED) }
    var principal by remember {
        mutableStateOf(initial?.principalMinor?.takeIf { it > 0L }?.let { Money.format(it, showSymbol = false) }.orEmpty())
    }
    var dueAt by remember { mutableStateOf(initial?.dueAt ?: Time.toMillis(LocalDate.now(Time.zone), LocalTime.NOON)) }
    var anchorDay by remember { mutableStateOf(initial?.anchorDay ?: LocalDate.now(Time.zone).dayOfMonth) }
    var categoryId by remember { mutableStateOf(initial?.categoryId) }
    var paymentMethod by remember { mutableStateOf(initial?.paymentMethod ?: PaymentMethod.OTHER) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }

    val principalMinor = Money.parseToMinor(principal) ?: 0L
    val canSave = name.isNotBlank() && principalMinor > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增债务" else "编辑债务") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("类型", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DebtType.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { option ->
                                FilterChip(
                                    selected = type == option,
                                    onClick = { type = option },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                }
                Text(
                    text = if (type.isOwed) "方向：欠别人的钱，还款减少欠款。" else "方向：别人欠你的钱，收回减少待收回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = principal,
                    onValueChange = { principal = it },
                    label = { Text(if (type.isOwed) "借入本金" else "借出本金") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "到期日 ${Time.formatDate(dueAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { pickingDate = true },
                    )
                    TextButton(onClick = { pickingDate = true }) { Text("选择") }
                }

                OutlinedTextField(
                    value = anchorDay.toString(),
                    onValueChange = { input ->
                        anchorDay = input.filter { it.isDigit() }.take(2).toIntOrNull() ?: 1
                    },
                    label = { Text("每月几号（1-31，用于下一期到期日）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("分类（利息账单会用这个分类）", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = categoryId == null,
                        onClick = { categoryId = null },
                        label = { Text("未分类") },
                    )
                    categories.take(3).forEach { category ->
                        FilterChip(
                            selected = categoryId == category.id,
                            onClick = { categoryId = category.id },
                            label = { Text("${category.emoji} ${category.name}") },
                        )
                    }
                }

                Text("支付方式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaymentMethod.entries.take(4).forEach { method ->
                        FilterChip(
                            selected = paymentMethod == method,
                            onClick = { paymentMethod = method },
                            label = { Text(method.label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!canSave) {
                    Text(
                        text = "请填写名称，本金需要大于 0。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        (initial ?: Debt(name = name, principalMinor = principalMinor, dueAt = dueAt))
                            .copy(
                                name = name.trim(),
                                type = type,
                                principalMinor = principalMinor,
                                dueAt = dueAt,
                                anchorDay = anchorDay.coerceIn(1, 31),
                                categoryId = categoryId,
                                paymentMethod = paymentMethod,
                                note = note.trim(),
                            )
                    )
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueAt)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.selectedDateMillis
                    if (picked != null) {
                        dueAt = Time.toMillis(Time.toLocalDate(picked), LocalTime.NOON)
                        anchorDay = Time.toLocalDate(picked).dayOfMonth
                    }
                    pickingDate = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun RepayDialog(
    debt: Debt,
    progress: DebtProgress,
    onDismiss: () -> Unit,
    onConfirm: (principalMinor: Long, extraMinor: Long, note: String) -> Unit,
) {
    var principal by remember(debt.id) {
        mutableStateOf(Money.format(progress.remainingPrincipalMinor, showSymbol = false))
    }
    var extra by remember(debt.id) { mutableStateOf("") }
    var note by remember(debt.id) { mutableStateOf("") }

    val principalMinor = Money.parseToMinor(principal) ?: 0L
    val extraMinor = if (extra.isBlank()) 0L else (Money.parseToMinor(extra) ?: 0L)
    val canSave = principalMinor >= 0L && (principalMinor > 0L || extraMinor > 0L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (debt.type.isOwed) "记一次还款" else "记一次收回") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "剩余本金 ${Money.format(progress.remainingPrincipalMinor)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = principal,
                    onValueChange = { principal = it },
                    label = { Text("本次本金") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = extra,
                    onValueChange = { extra = it },
                    label = { Text("利息 / 手续费（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "本金不计" + (if (debt.type.isOwed) "支出" else "收入") +
                        "；只有利息会生成一笔账单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canSave) {
                    Text(
                        text = "本金和利息不能都为 0。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onConfirm(principalMinor, extraMinor, note.trim()) },
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}