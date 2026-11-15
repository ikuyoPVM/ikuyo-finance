package xyz.ikuyo.finance.data.import

import xyz.ikuyo.finance.core.Csv
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.PaymentMethod
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * CSV 导入的解析与校验。
 *
 * 这里只做纯计算：把文本变成「可导入 / 重复 / 冲突 / 无效」的行清单，任何写库动作都由上层
 * 在用户确认后执行。重复判定必须结合日期、金额、类型、分类与备注，不能只看金额。
 */
object CsvImporter {

    /** 导入可识别的字段。required 的字段缺失时整份文件都无法导入。 */
    data class Field(val key: String, val label: String, val required: Boolean, val aliases: List<String>)

    val FIELDS: List<Field> = listOf(
        Field("date", "日期", true, listOf("日期", "date", "time", "交易时间")),
        Field("time", "时间", false, listOf("时间", "clock")),
        Field("type", "类型", true, listOf("类型", "type", "收支类型")),
        Field("amount", "原币金额", true, listOf("原币金额", "金额", "amount", "money")),
        Field("currency", "币种", false, listOf("币种", "currency")),
        Field("rate", "汇率", false, listOf("汇率", "rate")),
        Field("category", "分类", false, listOf("分类", "category")),
        Field("note", "备注", false, listOf("备注", "note", "memo")),
        Field("payment", "支付方式", false, listOf("支付方式", "payment", "paymentmethod")),
    )

    enum class Status(val label: String) {
        NEW("新增"),
        DUPLICATE("重复"),
        CONFLICT("冲突"),
        INVALID("无效"),
    }

    data class Row(
        val lineNumber: Int,
        val occurredAt: Long,
        val type: BillType,
        val amountMinor: Long,
        val currency: String,
        val rateScaled: Long,
        val cnyAmountMinor: Long,
        val categoryId: Long?,
        val categoryName: String,
        val paymentMethod: PaymentMethod,
        val note: String,
        val status: Status,
        val reason: String,
    ) {
        val importable: Boolean get() = status == Status.NEW || status == Status.CONFLICT
    }

    data class Plan(
        val rows: List<Row> = emptyList(),
        val error: String? = null,
        val headers: List<String> = emptyList(),
        val mapping: Map<String, Int> = emptyMap(),
        val ignoredColumns: List<String> = emptyList(),
    ) {
        val newCount: Int get() = rows.count { it.status == Status.NEW }
        val conflictCount: Int get() = rows.count { it.status == Status.CONFLICT }
        val duplicateCount: Int get() = rows.count { it.status == Status.DUPLICATE }
        val invalidCount: Int get() = rows.count { it.status == Status.INVALID }
        val importCount: Int get() = rows.count { it.importable }
        val hasData: Boolean get() = rows.isNotEmpty()
    }

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy.MM.dd"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"),
    )
    private val TIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("H:mm"),
        DateTimeFormatter.ofPattern("H:mm:ss"),
        DateTimeFormatter.ofPattern("HH:mm"),
        DateTimeFormatter.ofPattern("HH:mm:ss"),
    )

    /** 找出表头里每个字段对应的列号；缺失 required 字段时返回错误说明。 */
    fun plan(
        text: String,
        existing: List<Bill>,
        categories: List<xyz.ikuyo.finance.domain.model.Category>,
        rates: Map<String, BigDecimal> = emptyMap(),
    ): Plan {
        val table = Csv.parse(text)
        if (table.isEmpty()) return Plan(error = "文件是空的，没有任何行。")
        val headers = table.first().map { it.trim() }
        if (headers.isEmpty()) return Plan(error = "第一行不是有效的表头。")

        val normalized = headers.map { normalizeHeader(it) }
        val mapping = linkedMapOf<String, Int>()
        FIELDS.forEach { field ->
            val index = normalized.indexOfFirst { header ->
                field.aliases.any { normalizeHeader(it) == header }
            }
            if (index >= 0) mapping[field.key] = index
        }
        val missing = FIELDS.filter { it.required && it.key !in mapping }
        if (missing.isNotEmpty()) {
            return Plan(
                error = "缺少必要的列：${missing.joinToString("、") { it.label }}。请确认导出或模板的列名。",
                headers = headers,
                mapping = mapping,
            )
        }

        val usedColumns = mapping.values.toSet()
        val ignored = headers.filterIndexed { index, _ -> index !in usedColumns }

        val rows = mutableListOf<Row>()
        val seen = HashSet<String>()
        table.drop(1).forEachIndexed { offset, cells ->
            val lineNumber = offset + 2
            if (cells.all { it.isBlank() }) return@forEachIndexed
            rows += readRow(lineNumber, cells, mapping, existing, categories, rates, seen)
        }
        if (rows.isEmpty()) return Plan(error = "表头之后没有数据行。", headers = headers, mapping = mapping)
        return Plan(rows = rows, headers = headers, mapping = mapping, ignoredColumns = ignored)
    }

    private fun readRow(
        lineNumber: Int,
        cells: List<String>,
        mapping: Map<String, Int>,
        existing: List<Bill>,
        categories: List<xyz.ikuyo.finance.domain.model.Category>,
        rates: Map<String, BigDecimal>,
        seen: MutableSet<String>,
    ): Row {
        fun cell(key: String): String = mapping[key]?.let { index -> cells.getOrNull(index)?.trim().orEmpty() }.orEmpty()

        val rawDate = cell("date")
        val date = parseDate(rawDate)
            ?: return invalid(lineNumber, "日期「$rawDate」无法识别，支持 2026-11-15 或 2026/11/15 这类写法。")

        val rawTime = cell("time")
        val time = parseTime(rawTime) ?: LocalTime.NOON

        val rawType = cell("type")
        val type = parseType(rawType)
            ?: return invalid(lineNumber, "类型「$rawType」无法识别，应为支出、收入或退款。")

        val rawCurrency = cell("currency")
        val currency = if (rawCurrency.isBlank()) "CNY" else rawCurrency.uppercase(Locale.ROOT)
        if (currency !in SETTLED_CURRENCIES) {
            return invalid(lineNumber, "币种「$rawCurrency」不在支持范围内（CNY/JPY/USD/EUR/GBP/HKD）。")
        }

        val rawAmount = cell("amount")
        val amountMinor = Money.parseToMinor(rawAmount, currency)
            ?: return invalid(lineNumber, "金额「$rawAmount」不是有效数字。")
        if (amountMinor <= 0) return invalid(lineNumber, "金额必须大于 0。")

        val rawRate = cell("rate")
        val rate: BigDecimal = if (currency == "CNY") {
            BigDecimal.ONE
        } else {
            val parsed = rawRate.toBigDecimalOrNull()
            val fallback = rates[currency]
            when {
                parsed != null && parsed > BigDecimal.ZERO -> parsed
                fallback != null && fallback > BigDecimal.ZERO -> fallback
                else -> return invalid(lineNumber, "外币账单需要汇率，但「$currency」既没有提供也没有配置。")
            }
        }

        val rateScaled = Money.scaledFromRate(rate)
        val cnyAmountMinor = Money.convertToCnyMinor(amountMinor, currency, rate)

        val rawCategory = cell("category")
        val category = categories.firstOrNull { it.name == rawCategory }
        val categoryNote = if (rawCategory.isNotBlank() && category == null) {
            "分类「$rawCategory」不存在，将按未分类导入。"
        } else {
            ""
        }

        val rawPayment = cell("payment")
        val payment = parsePayment(rawPayment)

        val note = cell("note").let { Csv.stripGuard(it) }
        val occurredAt = Time.toMillis(date, time)

        val key = duplicateKey(occurredAt, amountMinor, type, category?.id, note)
        val duplicateOf = existing.firstOrNull { bill ->
            duplicateKey(
                occurredAt = bill.occurredAt,
                amountMinor = bill.amountMinor,
                type = bill.type,
                categoryId = bill.categoryId,
                note = bill.note,
            ) == key
        }
        val status = when {
            duplicateOf != null -> Status.DUPLICATE
            key in seen -> Status.DUPLICATE
            else -> Status.NEW
        }
        seen += key
        val reason = when {
            status == Status.DUPLICATE && duplicateOf != null -> "账本里已有同日同类型同金额同分类同备注的账单。"
            status == Status.DUPLICATE -> "文件内出现了完全相同的两行。"
            categoryNote.isNotBlank() -> categoryNote
            status == Status.CONFLICT -> ""
            else -> ""
        }
        val finalStatus = if (status == Status.NEW && categoryNote.isNotBlank()) Status.CONFLICT else status

        return Row(
            lineNumber = lineNumber,
            occurredAt = occurredAt,
            type = type,
            amountMinor = amountMinor,
            currency = currency,
            rateScaled = rateScaled,
            cnyAmountMinor = cnyAmountMinor,
            categoryId = category?.id,
            categoryName = category?.name ?: "未分类",
            paymentMethod = payment,
            note = note,
            status = finalStatus,
            reason = reason,
        )
    }

    private fun invalid(lineNumber: Int, reason: String): Row = Row(
        lineNumber = lineNumber,
        occurredAt = 0L,
        type = BillType.EXPENSE,
        amountMinor = 0L,
        currency = "CNY",
        rateScaled = Money.scaledFromRate(BigDecimal.ONE),
        cnyAmountMinor = 0L,
        categoryId = null,
        categoryName = "未分类",
        paymentMethod = PaymentMethod.OTHER,
        note = "",
        status = Status.INVALID,
        reason = reason,
    )

    private fun duplicateKey(
        occurredAt: Long,
        amountMinor: Long,
        type: BillType,
        categoryId: Long?,
        note: String,
    ): String {
        val day = Time.toLocalDate(occurredAt).toString()
        return "$day|$amountMinor|${type.dbValue}|${categoryId ?: 0L}|${note.trim()}"
    }

    fun parseDate(text: String): LocalDate? {
        if (text.isBlank()) return null
        val trimmed = text.trim().take(10)
        DATE_FORMATS.forEach { formatter ->
            try {
                return LocalDate.parse(trimmed, formatter)
            } catch (_: DateTimeParseException) {
                // 试下一个格式
            }
        }
        return null
    }

    fun parseTime(text: String): LocalTime? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        TIME_FORMATS.forEach { formatter ->
            try {
                return LocalTime.parse(trimmed, formatter)
            } catch (_: DateTimeParseException) {
                // 试下一个格式
            }
        }
        return null
    }

    fun parseType(text: String): BillType? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return BillType.entries.firstOrNull {
            it.label == trimmed || it.dbValue.equals(trimmed, ignoreCase = true)
        }
    }

    fun parsePayment(text: String): PaymentMethod {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return PaymentMethod.OTHER
        return PaymentMethod.entries.firstOrNull {
            it.label == trimmed || it.dbValue.equals(trimmed, ignoreCase = true)
        } ?: PaymentMethod.OTHER
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(" ", "").replace("_", "")

    /** 支持结算的币种，与汇率设置页保持一致。 */
    val SETTLED_CURRENCIES = setOf("CNY", "JPY", "USD", "EUR", "GBP", "HKD")

    /** 供界面展示的模板表头。 */
    val TEMPLATE_HEADER: List<String> = listOf("日期", "时间", "类型", "原币金额", "币种", "汇率", "分类", "备注", "支付方式")

    fun templateRow(): List<String> = listOf("2026-11-15", "12:30", "支出", "28.00", "CNY", "1", "餐饮", "午饭", "微信")

    private fun String.toBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(this.trim())
    } catch (_: NumberFormatException) {
        null
    }
}