package xyz.ikuyo.finance.data.export

import xyz.ikuyo.finance.core.Csv
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import java.time.format.DateTimeFormatter
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.usecase.Statistics

/**
 * 导出用的第一行（列名）与数据行构造。
 *
 * 约定：
 * - 列名稳定、见名知义，不随界面语言变化（导出的 CSV 是数据文件，不是界面文案）。
 * - 金额一律输出纯数字文本（无货币符号、无千分位），保证表格软件识别为数字。
 * - 用户可编辑的文本列（备注、分类名、标签名、操作说明）做公式注入保护；
 *   数字与日期列不加保护（负数以 `-` 开头，加前缀会破坏数字语义）。
 */
object CsvExporter {

    /** 账单明细列名。 */
    val BILL_COLUMNS = listOf(
        "账单ID",
        "日期",
        "时间",
        "类型",
        "原币金额",
        "币种",
        "汇率",
        "本币金额",
        "分类",
        "标签",
        "支付方式",
        "备注",
        "来源",
        "退款对应账单ID",
        "创建时间",
        "是否已删除",
    )

    /** 预算执行列名。 */
    val BUDGET_COLUMNS = listOf(
        "预算ID",
        "预算名称",
        "周期",
        "金额",
        "结转",
        "可用",
        "已用",
        "剩余",
        "使用率",
        "剩余天数",
        "状态",
    )

    /** 操作历史列名。 */
    val OPERATION_COLUMNS = listOf(
        "操作ID",
        "账单ID",
        "操作类型",
        "说明",
        "金额",
        "是否已撤销",
        "操作时间",
    )

    /** 导出账单明细。 */
    fun bills(
        bills: List<Bill>,
        tagNames: Map<Long, List<String>> = emptyMap(),
        deletedIds: Set<Long> = emptySet(),
    ): String {
        val rows = ArrayList<List<String>>(bills.size + 1)
        rows.add(BILL_COLUMNS)
        for (bill in bills) {
            rows.add(
                listOf(
                    bill.id.toString(),
                    Time.toLocalDate(bill.occurredAt).toString(),
                    Time.formatTime(bill.occurredAt),
                    bill.type.label,
                    number(Money.plain(bill.amountMinor, bill.currency)),
                    bill.currency,
                    number(rateText(bill.exchangeRateScaled)),
                    number(Money.plain(bill.cnyAmountMinor)),
                    text(bill.categoryName ?: "未分类"),
                    text(tagNames[bill.id]?.joinToString("、").orEmpty()),
                    bill.paymentMethod.label,
                    text(bill.note),
                    bill.source.label,
                    bill.refundOfBillId?.toString().orEmpty(),
                    isoDateTime(bill.createdAt),
                    if (bill.id in deletedIds) "是" else "否",
                ),
            )
        }
        return Csv.file(rows)
    }

    /** 导出预算执行。 */
    fun budgets(executions: List<Statistics.BudgetExecution>): String {
        val rows = ArrayList<List<String>>(executions.size + 1)
        rows.add(BUDGET_COLUMNS)
        for (row in executions) {
            rows.add(
                listOf(
                    row.budgetId.toString(),
                    text(row.name),
                    text(row.windowLabel),
                    number(Money.plain(row.amountMinor)),
                    number(Money.plain(row.carryOverMinor)),
                    number(Money.plain(row.availableMinor)),
                    number(Money.plain(row.spentMinor)),
                    number(Money.plain(row.availableMinor - row.spentMinor)),
                    number((row.usedPercent / 100.0).let { percentText(it) }),
                    row.daysLeft.toString(),
                    text(row.alertLevel.label),
                ),
            )
        }
        return Csv.file(rows)
    }

    /** 导出操作历史。 */
    fun operations(operations: List<BillOperation>): String {
        val rows = ArrayList<List<String>>(operations.size + 1)
        rows.add(OPERATION_COLUMNS)
        for (operation in operations) {
            rows.add(
                listOf(
                    operation.id.toString(),
                    operation.billId.toString(),
                    operation.type.label,
                    text(operation.summary),
                    number(Money.plain(operation.amountMinor)),
                    if (operation.undone) "是" else "否",
                    isoDateTime(operation.operatedAt),
                ),
            )
        }
        return Csv.file(rows)
    }

    /** 用户文本列：转义并做公式注入保护。 */
    private fun text(value: String): String = Csv.escapeField(value, guardFormula = true)

    /** 数字/日期列：只做必要转义，不加公式前缀。 */
    private fun number(value: String): String = Csv.escapeField(value, guardFormula = false)

    /** 汇率按去尾零输出（1.0 → "1"，7.12 → "7.12"）。 */
    private fun rateText(scaled: Long): String =
        java.math.BigDecimal.valueOf(scaled)
            .movePointLeft(6)
            .stripTrailingZeros()
            .toPlainString()

    /** 百分比按两位小数的纯数字输出（12.34 表示 12.34%）。 */
    private fun percentText(percent: Double): String =
        java.math.BigDecimal.valueOf(percent)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .toPlainString()

    /** 类型筛选：null 表示不筛选。 */
    fun matchesType(bill: Bill, types: Set<BillType>): Boolean =
        types.isEmpty() || bill.type in types
    /** 导出统一用 ISO 文本，避免本地化日期在表格软件里排序错乱。 */
    private fun isoDateTime(millis: Long): String =
        Time.toLocalDateTime(millis).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
