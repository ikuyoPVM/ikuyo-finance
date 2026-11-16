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
import xyz.ikuyo.finance.domain.model.CurrencyCatalog
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.RateSource
import xyz.ikuyo.finance.domain.model.Tag
import java.math.BigDecimal
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
    initialTagIds: List<Long> = emptyList(),
    /** 初始币种（编辑外币账单时用来回填）。 */
    initialCurrency: String = Money.BASE_CURRENCY,
    /** 初始汇率（缩放 1e6），默认 1。 */
    initialExchangeRateScaled: Long = Money.RATE_SCALE,
    /** 已确认的汇率表：选了币种就自动带出，允许当场手改。 */
    rates: Map<String, CurrencyRate>? = null,
    tags: List<Tag> = emptyList(),
    allowTypeSwitch: Boolean = true,
    confirmText: String = "保存",
    onConfirm: (BillDraft) -> Unit,
    extraActions: (@Composable () -> Unit)? = null,
) {
    var amountText by rememberSaveable {
        mutableStateOf(if (initialAmountMinor > 0) Money.format(initialAmountMinor, showSymbol = false) else "")
    }
    var type by remember { mutableStateOf(initialType) }
    var currency by remember { mutableStateOf(initialCurrency.uppercase()) }
    var rateText by rememberSaveable {
        mutableStateOf(
            if (initialCurrency.uppercase() == Money.BASE_CURRENCY) {
                ""
            } else {
                Money.rateFromScaled(initialExchangeRateScaled).stripTrailingZeros().toPlainString()
            }
        )
    }
    var rateTouched by remember { mutableStateOf(false) }
    var categoryId by remember { mutableStateOf(initialCategoryId) }
    var payment by remember { mutableStateOf(initialPayment ?: PaymentMethod.WECHAT) }
    var note by rememberSaveable { mutableStateOf(initialNote) }
    var occurredAt by remember { mutableLongStateOf(initialOccurredAt) }
    var selectedTagIds by remember { mutableStateOf(initialTagIds.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    // 金额折算预览：只做展示，真正入账的折算值仍由用例统一计算。
    val knownRates = rates ?: rememberCurrencyRates()
    val storedRate = CurrencyCatalog.rateOf(currency, knownRates)
    val currentRate: BigDecimal? = if (currency == Money.BASE_CURRENCY) {
        BigDecimal.ONE
    } else {
        rateText.toBigDecimalOrNull()
    }
    val previewCnyMinor = currentRate?.let { rate ->
        Money.parseToMinor(amountText, currency)?.let { amount ->
            Money.convertToCnyMinor(amount, currency, rate)
        }
    }

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
            prefix = { Text(Money.symbolOf(currency)) },
            singleLine = true,
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("币种", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrencyCatalog.supported.forEach { code ->
                FilterChip(
                    selected = currency == code,
                    onClick = {
                        currency = code
                        error = null
                        // 换币种时尽量带出已确认的汇率，用户仍可当场修改。
                        rateText = if (code == Money.BASE_CURRENCY) {
                            ""
                        } else {
                            CurrencyCatalog.rateOf(code, knownRates)
                                ?.rate?.stripTrailingZeros()?.toPlainString().orEmpty()
                        }
                        rateTouched = false
                    },
                    label = { Text(CurrencyCatalog.label(code)) },
                )
            }
        }

        if (currency != Money.BASE_CURRENCY) {
            OutlinedTextField(
                value = rateText,
                onValueChange = { raw ->
                    rateText = sanitizeRateInput(raw)
                    rateTouched = true
                    error = null
                },
                label = { Text("汇率（1 $currency 合多少人民币）") },
                prefix = { Text("¥") },
                singleLine = true,
                supportingText = {
                    val hint = when {
                        currentRate == null && storedRate == null ->
                            "没有可用汇率：请填写，或先到「汇率」页设置后再记账。"
                        currentRate == null -> "汇率不能为空。"
                        else -> buildString {
                            append("折算 ≈ ¥")
                            append(Money.format(previewCnyMinor ?: 0L, showSymbol = false))
                            append(" · ")
                            append(if (rateTouched || storedRate == null) RateSource.MANUAL.label else storedRate.source.label)
                            if (!rateTouched && storedRate != null && storedRate.updatedAt > 0L) {
                                append(" · 更新于 ")
                                append(Time.formatDateTime(storedRate.updatedAt))
                            }
                        }
                    }
                    Text(hint)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

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

        if (tags.isNotEmpty()) {
            Text("标签（可多选）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    val selected = tag.id in selectedTagIds
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedTagIds = if (selected) {
                                selectedTagIds - tag.id
                            } else {
                                selectedTagIds + tag.id
                            }
                        },
                        label = { Text("#${tag.name}") },
                    )
                }
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
                text = "消费日期：${Time.formatFullDate(occurredAt)}",
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
                    val amountMinor = Money.parseToMinor(amountText, currency)
                    if (amountMinor == null || amountMinor <= 0L) {
                        error = "请输入有效金额"
                        return@Button
                    }
                    val isBase = currency == Money.BASE_CURRENCY
                    val rate = if (isBase) BigDecimal.ONE else rateText.toBigDecimalOrNull()
                    if (rate == null || rate <= BigDecimal.ZERO) {
                        error = "请先确认 1 $currency 合多少人民币"
                        return@Button
                    }
                    // 汇率来源：用户改过就记手动，否则用汇率表里那条的来源与时间。
                    val stored = CurrencyCatalog.rateOf(currency, knownRates)
                    val source = when {
                        isBase -> RateSource.MANUAL
                        rateTouched || stored == null -> RateSource.MANUAL
                        else -> stored.source
                    }
                    val rateUpdatedAt = when {
                        isBase -> 0L
                        rateTouched || stored == null -> Time.nowMillis()
                        else -> stored.updatedAt
                    }
                    submitted = true
                    onConfirm(
                        BillDraft(
                            type = type,
                            amountMinor = amountMinor,
                            currency = currency,
                            exchangeRateScaled = Money.scaledFromRate(rate),
                            rateSource = source,
                            rateUpdatedAt = rateUpdatedAt,
                            categoryId = categoryId,
                            paymentMethod = payment,
                            note = note,
                            occurredAt = occurredAt,
                            tagIds = selectedTagIds.toList(),
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

/** DatePicker 返回 UTC 零点，这里只取日期：账单日期不记时分秒，统一落在当天本地零点。 */
private fun mergePickedDate(pickedUtcMillis: Long, originalMillis: Long): Long {
    val date: LocalDate = Instant.ofEpochMilli(pickedUtcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    return Time.startOfDay(date)
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

/** 汇率输入：保留数字与一个小数点，最多 6 位小数（与 RATE_SCALE 对齐）。 */
private fun sanitizeRateInput(raw: String): String {
    val cleaned = raw.filter { it.isDigit() || it == '.' || it == '．' || it == '。' }
        .replace('．', '.')
        .replace('。', '.')
    val firstDot = cleaned.indexOf('.')
    if (firstDot < 0) return cleaned
    val head = cleaned.substring(0, firstDot + 1)
    val tail = cleaned.substring(firstDot + 1).filter { it.isDigit() }.take(6)
    return head + tail
}