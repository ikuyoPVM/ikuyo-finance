package xyz.ikuyo.finance.feature.fixed

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.FixedExpense
import xyz.ikuyo.finance.domain.model.FixedExpensePayment
import xyz.ikuyo.finance.domain.model.FixedPeriodType
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle
import xyz.ikuyo.finance.ui.theme.amountColorFor
import java.time.LocalDate
import java.time.LocalTime

/**
 * 固定支出页。
 *
 * 页面只负责展示与收集输入，所有写入都走 [FixedExpensesViewModel] → 仓储 → AddBillUseCase。
 * 关键口径：计划本身不是账单，只有「确认付款」才写一条真实支出，也只把计划推进一期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedExpensesScreen(
    onBack: () -> Unit = {},
    viewModel: FixedExpensesViewModel = viewModel(factory = FixedExpensesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<FixedExpense?>(null) }
    var creating by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<FixedExpense?>(null) }
    var historyOf by remember { mutableStateOf<FixedExpense?>(null) }
    var deleting by remember { mutableStateOf<FixedExpense?>(null) }
    var reminderPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("固定支出") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }) {
                Text("新增")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "计划本身不是账单",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "固定支出只记录计划。确认付款后才会生成一条真实账单，并计入余额、预算与统计。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.pendingCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "有 ${state.pendingCount} 笔需要处理" +
                                    if (state.remindDays > 0) "（已到期或 ${state.remindDays} 天内到期）。"
                                    else "（今天到期或已逾期）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = amountColorFor(BillType.EXPENSE),
                            )
                        }
                    }
                }
            }

            item(key = "reminder-setting") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { reminderPicker = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "提前提醒",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (state.remindDays > 0) {
                                "到期前 ${state.remindDays} 天开始提醒"
                            } else {
                                "只在到期当天提醒"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "修改",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            val error = state.error
            if (error != null) {
                item(key = "error") {
                    Text(
                        text = "$error（点这里关闭）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { viewModel.clearError() },
                    )
                }
            }

            val notice = state.notice
            if (notice != null) {
                item(key = "notice") {
                    Text(
                        text = "$notice（点这里关闭）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { viewModel.clearNotice() },
                    )
                }
            }

            if (!state.hasAny) {
                item(key = "empty") {
                    EmptyHint(
                        text = "还没有固定支出计划。点右下角「新增」添加房租、会员、话费这类周期性支出。",
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            groupItem(
                key = "overdue",
                title = "已到期",
                items = state.overdue,
                state = state,
                onPay = { paying = it },
                onEdit = { editing = it },
                onToggle = { viewModel.setEnabled(it, !it.enabled) },
                onHistory = { historyOf = it },
                onDelete = { deleting = it },
            )
            groupItem(
                key = "week",
                title = "本周到期",
                items = state.thisWeek,
                state = state,
                onPay = { paying = it },
                onEdit = { editing = it },
                onToggle = { viewModel.setEnabled(it, !it.enabled) },
                onHistory = { historyOf = it },
                onDelete = { deleting = it },
            )
            groupItem(
                key = "later",
                title = "以后",
                items = state.later,
                state = state,
                onPay = { paying = it },
                onEdit = { editing = it },
                onToggle = { viewModel.setEnabled(it, !it.enabled) },
                onHistory = { historyOf = it },
                onDelete = { deleting = it },
            )
            groupItem(
                key = "paused",
                title = "已暂停",
                items = state.paused,
                state = state,
                onPay = { paying = it },
                onEdit = { editing = it },
                onToggle = { viewModel.setEnabled(it, !it.enabled) },
                onHistory = { historyOf = it },
                onDelete = { deleting = it },
            )

            item(key = "footer") {
                Text(
                    text = "29 / 30 / 31 日这类日期在短月份按当月最后一天推算，之后仍回到原来的日期，不会越推越早。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (creating || editing != null) {
        FixedExpenseEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { expense ->
                viewModel.save(expense)
                creating = false
                editing = null
            },
        )
    }

    val payingTarget = paying
    if (payingTarget != null) {
        ConfirmPaymentDialog(
            expense = payingTarget,
            onDismiss = { paying = null },
            onConfirm = { amountMinor, note ->
                viewModel.confirmPayment(payingTarget, amountMinor = amountMinor, note = note)
                paying = null
            },
            onMarkUnpaid = {
                viewModel.markUnpaid(payingTarget)
                paying = null
            },
        )
    }

    if (reminderPicker) {
        ReminderDaysDialog(
            current = state.remindDays,
            onDismiss = { reminderPicker = false },
            onPick = { days ->
                viewModel.setReminderDays(days)
                reminderPicker = false
            },
        )
    }

    val historyTarget = historyOf
    if (historyTarget != null) {
        PaymentHistoryDialog(
            expense = historyTarget,
            payments = state.payments.filter { it.fixedExpenseId == historyTarget.id },
            onDismiss = { historyOf = null },
        )
    }

    val deletingTarget = deleting
    if (deletingTarget != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除计划") },
            text = {
                Text(
                    text = "只删除「${deletingTarget.name}」这个计划。已经生成的账单会保留在账本里，" +
                            "这个计划的付款记录会随计划一起清掉。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(deletingTarget)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

private fun LazyListScope.groupItem(
    key: String,
    title: String,
    items: List<FixedExpense>,
    state: FixedExpensesUiState,
    onPay: (FixedExpense) -> Unit,
    onEdit: (FixedExpense) -> Unit,
    onToggle: (FixedExpense) -> Unit,
    onHistory: (FixedExpense) -> Unit,
    onDelete: (FixedExpense) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key) {
        FixedExpenseGroupCard(
            title = title,
            items = items,
            state = state,
            onPay = onPay,
            onEdit = onEdit,
            onToggle = onToggle,
            onHistory = onHistory,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun FixedExpenseGroupCard(
    title: String,
    items: List<FixedExpense>,
    state: FixedExpensesUiState,
    onPay: (FixedExpense) -> Unit,
    onEdit: (FixedExpense) -> Unit,
    onToggle: (FixedExpense) -> Unit,
    onHistory: (FixedExpense) -> Unit,
    onDelete: (FixedExpense) -> Unit,
) {
    SectionCard {
        SectionTitle(title = title, action = { Text("${items.size} 笔") })
        items.forEachIndexed { index, expense ->
            if (index > 0) DividerThin()
            FixedExpenseRow(
                expense = expense,
                days = state.daysOf(expense),
                onPay = { onPay(expense) },
                onEdit = { onEdit(expense) },
                onToggle = { onToggle(expense) },
                onHistory = { onHistory(expense) },
                onDelete = { onDelete(expense) },
            )
        }
    }
}

@Composable
private fun FixedExpenseRow(
    expense: FixedExpense,
    days: Int,
    onPay: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = expense.categoryEmoji, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = buildString {
                        append(expense.scheduleLabel())
                        append(" · ")
                        append(Time.formatDate(expense.dueAt))
                        if (expense.isVariable) append(" · 浮动金额")
                        if (!expense.enabled) append(" · 已暂停")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (expense.enabled) dueText(days) else "暂停中，不参与到期提醒",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (expense.enabled && days <= 0) {
                        amountColorFor(BillType.EXPENSE)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = Money.format(expense.amountMinor),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (expense.enabled) {
                TextButton(onClick = onPay, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("确认付款")
                }
            }
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("编辑")
            }
            TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (expense.enabled) "暂停" else "恢复")
            }
            TextButton(onClick = onHistory, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("记录")
            }
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun dueText(days: Int): String = when {
    days < 0 -> "已逾期 ${-days} 天"
    days == 0 -> "今天到期"
    days == 1 -> "明天到期"
    else -> "$days 天后到期"
}

@Composable
private fun FixedExpenseEditorDialog(
    initial: FixedExpense?,
    onDismiss: () -> Unit,
    onSave: (FixedExpense) -> Unit,
) {
    val today = LocalDate.now(Time.zone)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var amountText by remember {
        mutableStateOf(initial?.let { Money.format(it.amountMinor, showSymbol = false) } ?: "")
    }
    var periodType by remember { mutableStateOf(initial?.periodType ?: FixedPeriodType.MONTHLY) }
    var anchorDay by remember { mutableStateOf((initial?.anchorDay ?: today.dayOfMonth).toString()) }
    var customDays by remember { mutableStateOf((initial?.customDays ?: 30).toString()) }
    var isVariable by remember { mutableStateOf(initial?.isVariable ?: false) }
    var note by remember { mutableStateOf(initial?.note ?: "") }

    val amountMinor = Money.parseToMinor(amountText)
    val canSave = name.isNotBlank() && amountMinor != null && amountMinor > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增固定支出" else "编辑固定支出") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "周期", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    periodOptions.take(4).forEach { type ->
                        FilterChip(
                            selected = periodType == type,
                            onClick = { periodType = type },
                            label = { Text(type.label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    periodOptions.drop(4).forEach { type ->
                        FilterChip(
                            selected = periodType == type,
                            onClick = { periodType = type },
                            label = { Text(type.label) },
                        )
                    }
                }
                if (periodType == FixedPeriodType.CUSTOM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customDays,
                        onValueChange = { customDays = it.filter { ch -> ch.isDigit() } },
                        label = { Text("每几天一次") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (periodType.isMonthBased) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = anchorDay,
                        onValueChange = { anchorDay = it.filter { ch -> ch.isDigit() } },
                        label = { Text("每月几号（1-31）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "金额会变动", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "确认付款时可以填实际金额",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isVariable, onCheckedChange = { isVariable = it })
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val safeDay = (anchorDay.toIntOrNull() ?: today.dayOfMonth).coerceIn(1, 31)
                    val firstDue = Time.toMillis(
                        LocalDate.of(today.year, today.month, safeDay.coerceAtMost(today.lengthOfMonth())),
                        LocalTime.NOON,
                    )
                    val draft = initial?.copy(
                        name = name.trim(),
                        amountMinor = amountMinor ?: 0L,
                        periodType = periodType,
                        anchorDay = safeDay,
                        customDays = customDays.toIntOrNull() ?: 30,
                        isVariable = isVariable,
                        note = note.trim(),
                    ) ?: FixedExpense(
                        name = name.trim(),
                        amountMinor = amountMinor ?: 0L,
                        periodType = periodType,
                        dueAt = firstDue,
                        anchorDay = safeDay,
                        customDays = customDays.toIntOrNull() ?: 30,
                        isVariable = isVariable,
                        note = note.trim(),
                    )
                    onSave(draft)
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val periodOptions: List<FixedPeriodType> = listOf(
    FixedPeriodType.MONTHLY,
    FixedPeriodType.WEEKLY,
    FixedPeriodType.BIWEEKLY,
    FixedPeriodType.BIMONTHLY,
    FixedPeriodType.QUARTERLY,
    FixedPeriodType.HALF_YEARLY,
    FixedPeriodType.YEARLY,
    FixedPeriodType.CUSTOM,
)

@Composable
private fun ConfirmPaymentDialog(
    expense: FixedExpense,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
    onMarkUnpaid: () -> Unit,
) {
    var amountText by remember(expense.id) {
        mutableStateOf(Money.format(expense.amountMinor, showSymbol = false))
    }
    var note by remember(expense.id) { mutableStateOf("") }
    val amountMinor = Money.parseToMinor(amountText)
    val canConfirm = amountMinor != null && amountMinor > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认付款") },
        text = {
            Column {
                Text(
                    text = "${expense.name} · ${expense.scheduleLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "到期日 ${Time.formatDate(expense.dueAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(if (expense.isVariable) "实际金额" else "金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "确认后会生成一笔真实支出账单，并把计划推进到下一期。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "这一期不付了？点「本期未付」：不生成账单、不算支出，只把计划推进到下一期。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(amountMinor ?: expense.amountMinor, note.trim()) },
            ) { Text("确认付款") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onMarkUnpaid) { Text("本期未付") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun PaymentHistoryDialog(
    expense: FixedExpense,
    payments: List<FixedExpensePayment>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${expense.name} · 付款记录") },
        text = {
            if (payments.isEmpty()) {
                Text(
                    text = "还没有付款记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    payments.forEachIndexed { index, payment ->
                        if (index > 0) DividerThin()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = Time.formatDate(payment.paidAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = payment.note.ifBlank { "到期日 ${Time.formatDate(payment.dueAt)}" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = Money.format(payment.amountMinor),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun ReminderDaysDialog(
    current: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提前提醒") },
        text = {
            Column {
                Text(
                    text = "固定支出会在到期前多少天进入提醒范围。0 天表示只在到期当天提醒；" +
                            "已逾期的计划始终会被列出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                reminderOptions.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { days ->
                            FilterChip(
                                selected = days == current,
                                onClick = { onPick(days) },
                                label = { Text(if (days == 0) "当天" else "$days 天") },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private val reminderOptions = listOf(0, 1, 3, 5, 7, 14)