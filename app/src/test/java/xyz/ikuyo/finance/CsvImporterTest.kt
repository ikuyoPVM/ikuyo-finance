package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Csv
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.import.CsvImporter
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.PaymentMethod
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class CsvImporterTest {

    private val categories = listOf(
        Category(id = 1L, name = "餐饮", emoji = "🍜", sortOrder = 1),
        Category(id = 2L, name = "交通出行", emoji = "🚇", sortOrder = 2),
    )

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Time.toMillis(LocalDate.of(2026, month, day), LocalTime.of(hour, minute))

    private fun bill(
        id: Long = 1L,
        type: BillType = BillType.EXPENSE,
        amountMinor: Long = 2800L,
        categoryId: Long? = 1L,
        note: String = "午饭",
        occurredAt: Long = at(11, 15, 12, 30),
    ): Bill = Bill(
        id = id,
        type = type,
        amountMinor = amountMinor,
        cnyAmountMinor = amountMinor,
        categoryId = categoryId,
        note = note,
        paymentMethod = PaymentMethod.WECHAT,
        occurredAt = occurredAt,
        exchangeRateScaled = 1_000_000L,
        createdAt = occurredAt,
    )

    private fun csv(vararg rows: String): String =
        listOf(CsvImporter.TEMPLATE_HEADER.joinToString(",")).plus(rows.toList()).joinToString("\r\n")

    @Test
    fun `缺少必要列时给出说明`() {
        val text = "备注,金额\r\n午饭,28.00"
        val plan = CsvImporter.plan(text, emptyList(), categories)
        assertNotNull(plan.error)
        assertTrue(plan.error!!.contains("日期"))
        assertTrue(plan.error!!.contains("类型"))
        assertEquals(0, plan.rows.size)
    }

    @Test
    fun `正常行解析出金额类型分类与时间`() {
        val plan = CsvImporter.plan(csv("2026-11-15,12:30,支出,28.00,CNY,1,餐饮,午饭,微信"), emptyList(), categories)
        assertNull(plan.error)
        assertEquals(1, plan.rows.size)
        val row = plan.rows.first()
        assertEquals(CsvImporter.Status.NEW, row.status)
        assertEquals(BillType.EXPENSE, row.type)
        assertEquals(2_800L, row.amountMinor)
        assertEquals(2_800L, row.cnyAmountMinor)
        assertEquals("CNY", row.currency)
        assertEquals(1L, row.categoryId)
        assertEquals("餐饮", row.categoryName)
        assertEquals(PaymentMethod.WECHAT, row.paymentMethod)
        assertEquals("午饭", row.note)
        assertEquals(at(11, 15, 12, 30), row.occurredAt)
        assertTrue(row.importable)
    }

    @Test
    fun `账本已有同样账单时标记重复`() {
        val text = csv("2026-11-15,12:30,支出,28.00,CNY,1,餐饮,午饭,微信")
        val plan = CsvImporter.plan(text, listOf(bill()), categories)
        assertEquals(1, plan.duplicateCount)
        assertEquals(0, plan.newCount)
        assertEquals(0, plan.importCount)
        assertTrue(plan.rows.first().reason.contains("账本里已有"))
    }

    @Test
    fun `备注不同则不视为重复`() {
        val text = csv("2026-11-15,12:30,支出,28.00,CNY,1,餐饮,晚饭,微信")
        val plan = CsvImporter.plan(text, listOf(bill()), categories)
        assertEquals(1, plan.newCount)
        assertEquals(0, plan.duplicateCount)
    }

    @Test
    fun `文件内重复的两行只保留一行`() {
        val row = "2026-11-15,12:30,支出,28.00,CNY,1,餐饮,午饭,微信"
        val plan = CsvImporter.plan(csv(row, row), emptyList(), categories)
        assertEquals(1, plan.newCount)
        assertEquals(1, plan.duplicateCount)
        assertTrue(plan.rows[1].reason.contains("文件内"))
    }

    @Test
    fun `日期无法识别时标记无效`() {
        val plan = CsvImporter.plan(csv("2026/13/45,12:30,支出,28.00,CNY,1,餐饮,午饭,微信"), emptyList(), categories)
        assertEquals(1, plan.invalidCount)
        assertTrue(plan.rows.first().reason.contains("日期"))
        assertEquals(CsvImporter.Status.INVALID, plan.rows.first().status)
    }

    @Test
    fun `金额非数字或非正数时标记无效`() {
        val plan = CsvImporter.plan(
            csv(
                "2026-11-15,12:30,支出,abc,CNY,1,餐饮,午饭,微信",
                "2026-11-15,12:30,支出,0,CNY,1,餐饮,午饭,微信",
            ),
            emptyList(),
            categories,
        )
        assertEquals(2, plan.invalidCount)
        assertTrue(plan.rows[0].reason.contains("金额"))
        assertTrue(plan.rows[1].reason.contains("大于 0"))
    }

    @Test
    fun `类型无法识别时标记无效`() {
        val plan = CsvImporter.plan(csv("2026-11-15,12:30,转账,28.00,CNY,1,餐饮,午饭,微信"), emptyList(), categories)
        assertEquals(1, plan.invalidCount)
        assertTrue(plan.rows.first().reason.contains("类型"))
    }

    @Test
    fun `不支持的币种标记无效`() {
        val plan = CsvImporter.plan(csv("2026-11-15,12:30,支出,28.00,KRW,1,餐饮,午饭,微信"), emptyList(), categories)
        assertEquals(1, plan.invalidCount)
        assertTrue(plan.rows.first().reason.contains("币种"))
    }

    @Test
    fun `外币缺少汇率时标记无效`() {
        val plan = CsvImporter.plan(csv("2026-11-15,12:30,支出,19.99,USD,,,,外币,微信"), emptyList(), categories)
        assertEquals(1, plan.invalidCount)
        assertTrue(plan.rows.first().reason.contains("汇率"))
    }

    @Test
    fun `外币按行内汇率折算本币`() {
        val plan = CsvImporter.plan(
            csv("2026-11-15,12:30,支出,19.99,USD,7.12,餐饮,外币,微信"),
            emptyList(),
            categories,
        )
        val row = plan.rows.first()
        assertEquals(CsvImporter.Status.NEW, row.status)
        assertEquals(1_999L, row.amountMinor)
        assertEquals(7_120_000L, row.rateScaled)
        assertEquals(14_233L, row.cnyAmountMinor)
    }

    @Test
    fun `外币缺少行内汇率时回落到已配置汇率`() {
        val plan = CsvImporter.plan(
            csv("2026-11-15,12:30,支出,10.00,USD,,,,外币,微信"),
            emptyList(),
            categories,
            rates = mapOf("USD" to BigDecimal("7.00")),
        )
        val row = plan.rows.first()
        assertEquals(CsvImporter.Status.NEW, row.status)
        assertEquals(7_000L, row.cnyAmountMinor)
    }

    @Test
    fun `未知分类按未分类导入但标记冲突`() {
        val plan = CsvImporter.plan(csv("2026-11-15,12:30,支出,28.00,CNY,1,宠物,猫粮,微信"), emptyList(), categories)
        val row = plan.rows.first()
        assertEquals(CsvImporter.Status.CONFLICT, row.status)
        assertNull(row.categoryId)
        assertEquals("未分类", row.categoryName)
        assertTrue(row.reason.contains("宠物"))
        assertTrue(row.importable)
        assertEquals(1, plan.conflictCount)
    }

    @Test
    fun `时间缺失时按中午导入`() {
        val plan = CsvImporter.plan("日期,类型,原币金额\r\n2026-11-15,支出,28.00", emptyList(), categories)
        assertNull(plan.error)
        assertEquals(at(11, 15, 12, 0), plan.rows.first().occurredAt)
    }

    @Test
    fun `模板表头可以被自己解析`() {
        val text = Csv.file(
            listOf(
                CsvImporter.TEMPLATE_HEADER.map { Csv.escapeField(it) },
                CsvImporter.templateRow().map { Csv.escapeField(it) },
            ),
        )
        val headerLine = text.substringBefore("\r\n")
        val plan = CsvImporter.plan(text, emptyList(), categories)
        assertNull(plan.error)
        assertEquals(1, plan.rows.size)
        assertEquals(CsvImporter.TEMPLATE_HEADER.joinToString(","), headerLine)
        assertEquals(2_800L, plan.rows.first().amountMinor)
    }

    @Test
    fun `导出文件可以被重新导入并全部识别为重复`() {
        val exported = Csv.file(
            listOf(
                listOf("账单ID", "日期", "时间", "类型", "原币金额", "币种", "汇率", "本币金额", "分类", "标签", "支付方式", "备注", "来源", "退款对应账单ID", "创建时间", "是否已删除")
                    .map { Csv.escapeField(it) },
                listOf("1", "2026-11-15", "12:30", "支出", "28.00", "CNY", "1", "28.00", "餐饮", "", "微信", "午饭", "手动", "", "2026-11-15 07:48:22", "否")
                    .map { Csv.escapeField(it) },
            ),
        )
        val plan = CsvImporter.plan(exported, listOf(bill()), categories)
        assertNull(plan.error)
        assertEquals(1, plan.rows.size)
        assertEquals(CsvImporter.Status.DUPLICATE, plan.rows.first().status)
    }
}