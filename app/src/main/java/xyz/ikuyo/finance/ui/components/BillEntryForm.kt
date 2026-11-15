package xyz.ikuyo.finance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 记账表单（新增与编辑共用）。
 *
 * 只负责收集输入并做基础校验；金额解析、汇率折算与落库由 [xyz.ikuyo.finance.domain.usecase.AddBillUseCase] 完成。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BillEntryForm(
    categories: List<Category>,
    initialType: BillType = BillType.EXPENSE,
    initialAmountMinor: Long = 0L,
    initialCategoryId: Long? = null,
    initialPayment: PaymentMethod? = null,
    initialNote: String = "",
    initialOccurredAt: Long = Time.nowMillis(),
    allowTypeSwitch: Boolean = true,
    confirmText: String = "保存",
    onConfirm: (BillDraft) -> Unit,
    extraActions: (@Composable () -> Unit)? = null,
) {
    var amountText by rememberSaveable {
        mutableStateOf(if (initialAmountMinor > 0) Money.format(initialAmountMinor, showSymbol = false) else "")
    }
    var type by remember { mutableStateOf(initialType) }
    var categoryId by remember { mutableStateOf(initialCategoryId) }
    var payment by remember { mutableStateOf(initialPayment ?: PaymentMethod.WECHAT) }
    var note by rememberSaveable { mutableStateOf(initialNote) }
    var occurredAt by remember { mutableLongStateOf(initialOccurredAt) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = amountText,
            onValueChange = { raw ->
                amountText = sanitizeAmountInput(raw)
                error = null
            },
            label = { Text("金额") },
            prefix = { Text("¥") },
            singleLine = true,
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        if (allowTypeSwitch) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BillType.entries.forEach { candidate ->
                    FilterChip(
                        selected = type == candidate,
                        onClick = { type = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }
        }

        Text("分类", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                FilterChip(
                    selected = categoryId == category.id,
                    onClick = {
                        categoryId = if (categoryId == category.id) null else category.id
                    },
                    label = { Text("${category.emoji} ${category.name}") },
                )
            }
            if (categories.isEmpty()) {
                Text("暂无分类", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text("支付方式", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaymentMethod.entries.forEach { method ->
                FilterChip(
                    selected = payment == method,
                    onClick = { payment = method },
                    label = { Text(method.label) },
                )
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("备注") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "消费时间：${Time.formatDateTime(occurredAt)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { showDatePicker = true }) { Text("改日期") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            extraActions?.invoke()
            Button(
                onClick = {
                    if (submitted) return@Button
                    val amountMinor = Money.parseToMinor(amountText)
                    if (amountMinor == null || amountMinor <= 0L) {
                        error = "请输入有效金额"
                        return@Button
                    }
                    submitted = true
                    onConfirm(
                        BillDraft(
                            type = type,
                            amountMinor = amountMinor,
                            categoryId = categoryId,
                            paymentMethod = payment,
                            note = note,
                            occurredAt = occurredAt,
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(confirmText)
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = occurredAt)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = pickerState.selectedDateMillis
                        if (picked != null) {
                            occurredAt = mergePickedDate(picked, occurredAt)
                        }
                        showDatePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** DatePicker 返回 UTC 零点，这里只取日期，保留原有的时分。 */
private fun mergePickedDate(pickedUtcMillis: Long, originalMillis: Long): Long {
    val date: LocalDate = Instant.ofEpochMilli(pickedUtcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    val time = Time.toLocalDateTime(originalMillis).toLocalTime()
    return Time.toMillis(date, time)
}
/**
 * 金额输入的防呆处理：只保留数字与一个小数点，小数最多两位。
 * 用户在手机上误触很容易打出 "28.50." 或 "1.2.3"，这里直接挡掉，
 * 避免点保存时出现「请输入有效金额」这种让人摸不着头脑的提示。
 */
private fun sanitizeAmountInput(raw: String): String {
    val digitsAndDots = raw.filter { it.isDigit() || it == '.' || it == '．' || it == '。' }
        .replace('．', '.')
        .replace('。', '.')
    val firstDot = digitsAndDots.indexOf('.')
    if (firstDot < 0) return digitsAndDots
    val head = digitsAndDots.substring(0, firstDot + 1)
    val tail = digitsAndDots.substring(firstDot + 1)
        .filter { it.isDigit() }
        .take(2)
    return head + tail
}