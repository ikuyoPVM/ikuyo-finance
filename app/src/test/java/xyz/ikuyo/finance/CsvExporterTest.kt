package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Csv
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.export.CsvExporter
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.model.BillOperationType
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.usecase.Statistics
import java.time.LocalDate
import java.time.LocalTime

class CsvExporterTest {

    private val day = Time.toMillis(LocalDate.of(2026, 11, 15), LocalTime.of(12, 30))

    private fun bill(
        id: Long,
        type: BillType = BillType.EXPENSE,
        amountMinor: Long = 2_800L,
        currency: String = "CNY",
        rateScaled: Long = 1_000_000L,
        cnyAmountMinor: Long = 2_800L,
        categoryId: Long? = 1L,
        categoryName: String? = "餐饮",
        note: String = "午饭",
    ) = Bill(
        id = id,
        type = type,
        amountMinor = amountMinor,
        currency = currency,
        exchangeRateScaled = rateScaled,
        cnyAmountMinor = cnyAmountMinor,
        categoryId = categoryId,
        categoryName = categoryName,
        paymentMethod = PaymentMethod.WECHAT,
        note = note,
        occurredAt = day,
        createdAt = day,
        source = BillSource.MANUAL,
    )

    @Test
    fun `账单表头稳定且顺序固定`() {
        val text = CsvExporter.bills(emptyList())
        val header = text.substringBefore("\r\n")
        assertEquals(CsvExporter.BILL_COLUMNS.joinToString(","), header)
    }

    @Test
    fun `账单金额与日期按纯数字与统一格式输出`() {
        val rows = Csv.parse(CsvExporter.bills(listOf(bill(14))))
        val row = rows[1]
        assertEquals("14", row[0])
        assertEquals("2026-11-15", row[1])
        assertEquals("12:30", row[2])
        assertEquals("支出", row[3])
        assertEquals("28.00", row[4])
        assertEquals("CNY", row[5])
        assertEquals("1", row[6])
        assertEquals("28.00", row[7])
        assertEquals("餐饮", row[8])
        assertEquals("午饭", row[11])
    }

    @Test
    fun `外币账单保留原币金额与汇率快照`() {
        val rows = Csv.parse(
            CsvExporter.bills(
                listOf(
                    bill(
                        id = 21,
                        amountMinor = 1_999L,
                        currency = "USD",
                        rateScaled = 6_672_000L,
                        cnyAmountMinor = 13_337L,
                    ),
                ),
            ),
        )
        val row = rows[1]
        assertEquals("19.99", row[4])
        assertEquals("USD", row[5])
        assertEquals("6.672", row[6])
        assertEquals("133.37", row[7])
    }

    @Test
    fun `备注以公式前缀开头时被加保护前缀`() {
        val text = CsvExporter.bills(listOf(bill(1, note = "=SUM(A1:A9)")))
        val rows = Csv.parse(text)
        assertEquals("'=SUM(A1:A9)", rows[1][11])
        assertTrue(text.contains("'=SUM"))
    }

    @Test
    fun `金额为负也不会被当成公式`() {
        val text = CsvExporter.bills(listOf(bill(1, type = BillType.REFUND, amountMinor = 1_000L)))
        val rows = Csv.parse(text)
        assertEquals("退款", rows[1][3])
        // 退款不改写符号，原币金额仍是正数，便于表格求和时人工判断。
        assertEquals("10.00", rows[1][4])
    }

    @Test
    fun `标签以顿号连接且多个标签可读`() {
        val rows = Csv.parse(
            CsvExporter.bills(
                bills = listOf(bill(1)),
                tagNames = mapOf(1L to listOf("工作日", "外卖")),
            ),
        )
        assertEquals("工作日、外卖", rows[1][9])
    }

    @Test
    fun `未分类与已删除列可表达`() {
        val rows = Csv.parse(
            CsvExporter.bills(
                bills = listOf(bill(3, categoryId = null, categoryName = null)),
                deletedIds = setOf(3L),
            ),
        )
        assertEquals("未分类", rows[1][8])
        assertEquals("是", rows[1][15])
    }

    @Test
    fun `预算执行给出剩余与使用率`() {
        val executions = listOf(
            Statistics.BudgetExecution(
                budgetId = 1L,
                name = "总预算",
                windowLabel = "11-01 至 11-30",
                amountMinor = 100_000L,
                spentMinor = 7_000L,
                availableMinor = 100_000L,
                carryOverMinor = 0L,
                usedPercent = 7,
                daysLeft = 15,
                alertLevel = BudgetAlertLevel.NORMAL,
            ),
        )
        val rows = Csv.parse(CsvExporter.budgets(executions))
        val row = rows[1]
        assertEquals("总预算", row[1])
        assertEquals("1000.00", row[3])
        assertEquals("70.00", row[6])
        assertEquals("930.00", row[7])
        assertEquals("0.07", row[8])
        assertEquals("15", row[9])
        assertEquals("正常", row[10])
    }

    @Test
    fun `操作历史标出撤销状态`() {
        val operations = listOf(
            BillOperation(
                id = 5L,
                billId = 14L,
                type = BillOperationType.DELETE,
                summary = "删除账单 午饭",
                amountMinor = 2_800L,
                undone = true,
                operatedAt = day,
            ),
        )
        val rows = Csv.parse(CsvExporter.operations(operations))
        val row = rows[1]
        assertEquals("5", row[0])
        assertEquals("14", row[1])
        assertEquals("删除", row[2])
        assertEquals("删除账单 午饭", row[3])
        assertEquals("28.00", row[4])
        assertEquals("是", row[5])
    }

    @Test
    fun `类型筛选只保留选中类型`() {
        assertTrue(CsvExporter.matchesType(bill(1), setOf(BillType.EXPENSE)))
        assertFalse(CsvExporter.matchesType(bill(1), setOf(BillType.INCOME)))
        assertTrue(CsvExporter.matchesType(bill(1), emptySet()))
    }

    @Test
    fun `纯数字金额不带千分位`() {
        assertEquals("1234567.89", Money.plain(123_456_789L))
    }
}