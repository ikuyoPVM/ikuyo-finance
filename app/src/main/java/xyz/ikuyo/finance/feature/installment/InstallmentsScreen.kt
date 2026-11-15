package xyz.ikuyo.finance.feature.installment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Installment
import xyz.ikuyo.finance.domain.model.InstallmentPeriod
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.InstallmentSchedule
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.theme.amountColorFor
import java.time.LocalDate
import java.time.LocalTime

/**
 * 分期付款页。
 *
 * 页面只负责展示与收集输入，所有写入都走 [InstallmentViewModel] → 仓储 → AddBillUseCase。
 * 口径（阶段五计划书第 3 章）：分期计划本身不是支出；只有「确认付款」才生成一笔真实支出，
 * 未付期数只是「未来待还」，不进余额、预算和统计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentsScreen(
    onBack: () -> Unit = {},
    viewModel: InstallmentViewModel = viewModel(factory = InstallmentViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Installment?>(null) }
    var creating by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<Installment?>(null) }
    var deleting by remember { mutableStateOf<Installment?>(null) }
    var closingEarly by remember { mutableStateOf<Installment?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分期") },
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
                            text = "分期计划不是支出",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "确认某一期付款后才会生成一笔真实账单，并计入余额、预算与统计。" +
                                "剩余期数只是「未来待还」，不会算进本月支出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.active.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "在用 ${state.active.size} 笔，未来待还合计 " +
                                    Money.format(state.remainingTotalMinor),
                                style = MaterialTheme.typography.bodySmall,
                                color = amountColorFor(BillType.EXPENSE),
                            )
                        }
                    }
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
                        text = "还没有分期计划。点右下角「新增」添加花呗分期、信用卡分期这类按揭还款。",
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            groupItem(
                key = "active",
                title = "在用",
                items = state.active,
                state = state,
                onPay = { paying = it },
                onEdit = { editing = it },
                onToggle = { viewModel.setEnabled(it, !it.enabled) },
                onClose = { closingEarly = it },
                onDelete = { deleting = it },
            )
            groupItem(
                key = "finished",
                title = "已结清",
                items = state.finished,
                state = state,
                onPay = { paying = it },
                onEdit = { editing = it },
                onToggle = { viewModel.setEnabled(it, !it.enabled) },
                onClose = { closingEarly = it },
                onDelete = { deleting = it },
            )

            item(key = "footer") {
                Text(
                    text = "首期日期在短月份按当月最后一天推算，之后仍回到原来的日期，不会越推越早。" +
                        "提前结束后剩余期数不再算未来待还，已生成的账单照样保留。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (creating || editing != null) {
        InstallmentEditorDialog(
            initial = editing,
            categories = state.categories,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { installment ->
                viewModel.save(installment)
                creating = false
                editing = null
            },
        )
    }

    val payingTarget = paying
    if (payingTarget != null) {
        val next = state.nextPeriodOf(payingTarget)
        if (next != null) {
            ConfirmPeriodDialog(
                installment = payingTarget,
                period = next,
                onDismiss = { paying = null },
                onConfirm = { amountMinor, note ->
                    viewModel.confirmPeriod(
                        installment = payingTarget,
                        periodIndex = next.index,
                        amountMinor = amountMinor,
                        note = note,
                    )
                    paying = null
                },
            )
        }
    }

    val closingTarget = closingEarly
    if (closingTarget != null) {
        FinishEarlyDialog(
            installment = closingTarget,
            remainingMinor = state.progressOf(closingTarget).remainingMinor,
            onDismiss = { closingEarly = null },
            onConfirm = { fee ->
                viewModel.finishEarly(closingTarget, extraFeeMinor = fee)
                closingEarly = null
            },
        )
    }

    val deletingTarget = deleting
    if (deletingTarget != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除分期计划") },
            text = {
                Text(
                    text = "只删除「${deletingTarget.name}」这条计划。已经生成的账单会保留在账本里，" +
                        "这个计划的付款记录会随计划一起清掉。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(deletingTarget)
                    deleting = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.groupItem(
    key: String,
    title: String,
    items: List<Installment>,
    state: InstallmentUiState,
    onPay: (Installment) -> Unit,
    onEdit: (Installment) -> Unit,
    onToggle: (Installment) -> Unit,
    onClose: (Installment) -> Unit,
    onDelete: (Installment) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$key") {
        Text(
            text = "$title（${items.size}）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(items, key = { "$key-${it.id}" }) { installment ->
        InstallmentRow(
            installment = installment,
            state = state,
            onPay = { onPay(installment) },
            onEdit = { onEdit(installment) },
            onToggle = { onToggle(installment) },
            onClose = { onClose(installment) },
            onDelete = { onDelete(installment) },
        )
    }
}

@Composable
private fun InstallmentRow(
    installment: Installment,
    state: InstallmentUiState,
    onPay: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    val progress = state.progressOf(installment)
    val next = progress.nextPeriod
    val days = state.daysUntilNext(installment)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = installment.categoryEmoji, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = installment.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = buildString {
                        append(installment.periodCount)
                        append(" 期 · 共 ")
                        append(Money.format(installment.totalMinor))
                        if (installment.hasUnevenFirstPeriod) {
                            append(" · 首期 ")
                            append(Money.format(installment.firstPeriodMinor))
                        }
                        if (!installment.enabled && installment.isActive) append(" · 已暂停")
                        if (installment.isClosed) append(" · 已提前结束")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append("已还 ")
                        append(Money.format(progress.paidMinor))
                        append(" / ")
                        append(progress.paidCount)
                        append(" 期")
                        if (progress.remainingMinor > 0L) {
                            append(" · 还差 ")
                            append(Money.format(progress.remainingMinor))
                            append(" / ")
                            append(progress.remainingCount)
                            append(" 期")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = Money.format(installment.totalMinor),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.progressBp / 10_000f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                installment.isClosed -> "已提前结束，剩余期数不再算未来待还"
                progress.isFinished -> "已全部付清"
                next == null -> "没有待还期数"
                days == null -> "下一期 ${Time.formatDate(next.dueAt)}"
                days < 0 -> "第 ${next.periodNumber} 期已逾期 ${-days} 天（${Money.format(next.amountMinor)}）"
                days == 0 -> "第 ${next.periodNumber} 期今天到期（${Money.format(next.amountMinor)}）"
                else -> "第 ${next.periodNumber} 期 ${Time.formatDate(next.dueAt)} 到期" +
                    "（${Money.format(next.amountMinor)}，$days 天后）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (days != null && days <= 0 && !progress.isFinished && !installment.isClosed) {
                amountColorFor(BillType.EXPENSE)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        DividerThin(modifier = Modifier.padding(top = 6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!progress.isFinished && !installment.isClosed) {
                TextButton(onClick = onPay, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("确认付款")
                }
            }
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("编辑")
            }
            if (!progress.isFinished && !installment.isClosed) {
                TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (installment.enabled) "暂停" else "恢复")
                }
                TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("提前结束")
                }
            }
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConfirmPeriodDialog(
    installment: Installment,
    period: InstallmentPeriod,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    var amountText by remember(period.index) {
        mutableStateOf(Money.format(period.amountMinor, showSymbol = false))
    }
    var note by remember(period.index) { mutableStateOf("") }
    val amountMinor = Money.parseToMinor(amountText)
    val canSave = amountMinor != null && amountMinor > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认第 ${period.periodNumber} 期付款") },
        text = {
            Column {
                Text(
                    text = "${installment.name} · ${Time.formatDate(period.dueAt)} 到期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("本期金额") },
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
                    text = "确认后会生成一笔真实支出账单，并把这一期记为已付。同一期重复确认不会重复记账。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onConfirm(amountMinor ?: 0L, note.trim()) },
            ) {
                Text("确认付款")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun FinishEarlyDialog(
    installment: Installment,
    remainingMinor: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var feeText by remember(installment.id) { mutableStateOf("") }
    val feeMinor = if (feeText.isBlank()) 0L else (Money.parseToMinor(feeText) ?: -1L)
    val canSave = feeMinor >= 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提前结束分期") },
        text = {
            Column {
                Text(
                    text = "剩余待还 ${Money.format(remainingMinor)}。结束后这些期数不再算未来待还，" +
                        "已经生成的账单不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it },
                    label = { Text("结清费用（可选，留空表示没有）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "填了费用会额外生成一笔「结清费用」支出账单。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onConfirm(feeMinor.coerceAtLeast(0L)) }) {
                Text("提前结束")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallmentEditorDialog(
    initial: Installment?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Installment) -> Unit,
) {
    val today = LocalDate.now(Time.zone)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var totalText by remember {
        mutableStateOf(initial?.let { Money.format(it.totalMinor, showSymbol = false) } ?: "")
    }
    var principalText by remember {
        mutableStateOf(initial?.let { Money.format(it.principalMinor, showSymbol = false) } ?: "")
    }
    var feeText by remember {
        mutableStateOf(initial?.let { Money.format(it.feeMinor, showSymbol = false) } ?: "")
    }
    var interestText by remember {
        mutableStateOf(initial?.let { Money.format(it.interestMinor, showSymbol = false) } ?: "")
    }
    var periodCountText by remember { mutableStateOf((initial?.periodCount ?: 12).toString()) }
    var firstText by remember {
        mutableStateOf(initial?.let { Money.format(it.firstPeriodMinor, showSymbol = false) } ?: "")
    }
    var perText by remember {
        mutableStateOf(initial?.let { Money.format(it.perPeriodMinor, showSymbol = false) } ?: "")
    }
    var dueAt by remember { mutableStateOf(initial?.dueAt ?: Time.toMillis(today, LocalTime.NOON)) }
    var anchorDay by remember { mutableStateOf(initial?.anchorDay ?: today.dayOfMonth) }
    var categoryId by remember { mutableStateOf(initial?.categoryId) }
    var paymentMethod by remember { mutableStateOf(initial?.paymentMethod ?: PaymentMethod.OTHER) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var pickingDate by remember { mutableStateOf(false) }

    val totalMinor = Money.parseToMinor(totalText)
    val principalMinor = Money.parseToMinor(principalText) ?: 0L
    val feeMinor = if (feeText.isBlank()) 0L else (Money.parseToMinor(feeText) ?: 0L)
    val interestMinor = if (interestText.isBlank()) 0L else (Money.parseToMinor(interestText) ?: 0L)
    val periodCount = periodCountText.toIntOrNull() ?: 0
    val firstMinor = Money.parseToMinor(firstText) ?: 0L
    val perMinor = Money.parseToMinor(perText) ?: 0L

    // 自动补全：填了总额与期数后，可以用均分建议一键填好首期与每期金额。
    val suggested = if (totalMinor != null && totalMinor > 0L && periodCount >= 1) {
        InstallmentSchedule.evenSplit(totalMinor, periodCount)
    } else {
        null
    }
    val scheduleTotal = firstMinor + perMinor * (periodCount - 1).coerceAtLeast(0)
    val mismatch = totalMinor != null && totalMinor > 0L && scheduleTotal != totalMinor
    val canSave = name.isNotBlank() && totalMinor != null && totalMinor > 0L &&
        periodCount in 1..60 && firstMinor > 0L && !mismatch

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增分期" else "编辑分期") },
        text = {
            Column {
                Text(
                    text = "只记录计划，不会立刻产生支出。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（例如 笔记本电脑）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it },
                    label = { Text("总还款金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = principalText,
                        onValueChange = { principalText = it },
                        label = { Text("本金") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = feeText,
                        onValueChange = { feeText = it },
                        label = { Text("手续费") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = interestText,
                        onValueChange = { interestText = it },
                        label = { Text("利息") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = periodCountText,
                        onValueChange = { periodCountText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("期数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (principalMinor + feeMinor + interestMinor != (totalMinor ?: 0L)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "本金 + 手续费 + 利息应等于总还款金额",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = firstText,
                        onValueChange = { firstText = it },
                        label = { Text("首期金额") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = perText,
                        onValueChange = { perText = it },
                        label = { Text("其后每期") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (suggested != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "按均分建议填：首期 ${Money.format(suggested.first)}，其后每期 " +
                            Money.format(suggested.second),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            firstText = Money.format(suggested.first, showSymbol = false)
                            perText = Money.format(suggested.second, showSymbol = false)
                        },
                    )
                }
                if (mismatch) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "各期金额之和 ${Money.format(scheduleTotal)} 与总金额不一致",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "首期还款日", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${Time.formatDate(dueAt)} · 每月 $anchorDay 号",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { pickingDate = true }) { Text("修改") }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "分类", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = categoryId == null,
                        onClick = { categoryId = null },
                        label = { Text("未分类") },
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = categoryId == category.id,
                            onClick = { categoryId = category.id },
                            label = { Text("${category.emoji} ${category.name}") },
                        )
                    }
                }
                Text(text = "支付方式", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PaymentMethod.entries.forEach { method ->
                        FilterChip(
                            selected = paymentMethod == method,
                            onClick = { paymentMethod = method },
                            label = { Text(method.label) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
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
                    val safeDate = Time.toLocalDate(dueAt)
                    val draft = (initial ?: Installment(
                        name = name.trim(),
                        totalMinor = 0L,
                        principalMinor = 0L,
                        periodCount = 1,
                        firstPeriodMinor = 0L,
                        perPeriodMinor = 0L,
                        dueAt = dueAt,
                        anchorDay = safeDate.dayOfMonth,
                    )).copy(
                        name = name.trim(),
                        totalMinor = totalMinor ?: 0L,
                        principalMinor = principalMinor,
                        feeMinor = feeMinor,
                        interestMinor = interestMinor,
                        periodCount = periodCount,
                        firstPeriodMinor = firstMinor,
                        perPeriodMinor = perMinor,
                        dueAt = dueAt,
                        anchorDay = safeDate.dayOfMonth,
                        categoryId = categoryId,
                        paymentMethod = paymentMethod,
                        note = note.trim(),
                    )
                    onSave(draft)
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