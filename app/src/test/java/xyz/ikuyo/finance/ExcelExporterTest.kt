package xyz.ikuyo.finance

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.data.export.ExcelExporter

/**
 * Excel 导出只验证「结构对不对、数字和文本有没有写对」。
 *
 * 不断言 XML 的具体排版，避免以后调样式就把测试全弄红；
 * 但对表名、列名、单元格内容、转义与单位换算这些「错了就没人能发现」的点必须断言。
 */
class ExcelExporterTest {

    private fun sheet(name: String = "账单明细"): ExcelExporter.Sheet = ExcelExporter.Sheet(
        name = name,
        header = listOf("账单ID", "日期", "类型", "本币金额"),
        rows = listOf(
            listOf(
                ExcelExporter.Cell.Number(14.0),
                ExcelExporter.Cell.Text("2026-11-15"),
                ExcelExporter.Cell.Text("支出"),
                ExcelExporter.Cell.Number(ExcelExporter.yuan(2_800L)),
            ),
        ),
    )

    private fun entries(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return result
    }

    @Test
    fun `工作簿包含内容类型、关系、样式与每个工作表`() {
        val files = entries(ExcelExporter.workbook(listOf(sheet("账单明细"), sheet("分类统计"))))
        assertTrue(files.containsKey("[Content_Types].xml"))
        assertTrue(files.containsKey("_rels/.rels"))
        assertTrue(files.containsKey("xl/workbook.xml"))
        assertTrue(files.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(files.containsKey("xl/styles.xml"))
        assertTrue(files.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(files.containsKey("xl/worksheets/sheet2.xml"))
        assertEquals(7, files.size)
    }

    @Test
    fun `工作簿登记了每个工作表的名称`() {
        val xml = entries(ExcelExporter.workbook(listOf(sheet("账单明细"), sheet("分类统计"), sheet("预算执行"), sheet("操作历史"))))["xl/workbook.xml"].orEmpty()
        listOf("账单明细", "分类统计", "预算执行", "操作历史").forEach { name ->
            assertTrue("workbook 缺少工作表 $name", xml.contains("name=\"$name\""))
        }
    }

    @Test
    fun `工作表包含表头与数据行`() {
        val xml = ExcelExporter.sheetXml(sheet())
        assertTrue(xml.contains("账单ID"))
        assertTrue(xml.contains("本币金额"))
        assertTrue(xml.contains("<v>14</v>"))
        assertTrue(xml.contains("2026-11-15"))
        // 28.00 元按整数写入，Excel 里仍是数字 28。
        assertTrue(xml.contains("<v>28</v>"))
    }

    @Test
    fun `表头使用加粗样式且数据行从第二行开始`() {
        val xml = ExcelExporter.sheetXml(sheet())
        val headerRow = xml.substringAfter("<row r=\"1\">").substringBefore("</row>")
        assertTrue("表头应带样式 3", headerRow.contains("s=\"3\""))
        assertTrue(xml.contains("<row r=\"2\">"))
    }

    @Test
    fun `文本单元格中的 XML 特殊字符会被转义`() {
        val xml = ExcelExporter.sheetXml(
            ExcelExporter.Sheet(
                name = "转义",
                header = listOf("备注"),
                rows = listOf(listOf(ExcelExporter.Cell.Text("午饭 & 咖啡 <test> \"引号\""))),
            ),
        )
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;test&gt;"))
        assertTrue(xml.contains("&quot;引号&quot;"))
        assertTrue("原始 & 不应残留", !xml.contains("& 咖啡"))
    }

    @Test
    fun `百分比单元格写入比例值`() {
        val xml = ExcelExporter.sheetXml(
            ExcelExporter.Sheet(
                name = "占比",
                header = listOf("占比"),
                rows = listOf(listOf(ExcelExporter.Cell.Percent(ExcelExporter.ratio(5_555)))),
            ),
        )
        assertTrue("应为 0.5555 而不是 5555", xml.contains("<v>0.5555</v>"))
    }

    @Test
    fun `金额按分换算成元`() {
        assertEquals(28.0, ExcelExporter.yuan(2_800L), 0.0001)
        assertEquals(0.05, ExcelExporter.yuan(5L), 0.0001)
        assertEquals(0.0, ExcelExporter.yuan(0L), 0.0001)
    }

    @Test
    fun `占比按万分比换算成比例`() {
        assertEquals(0.5555, ExcelExporter.ratio(5_555), 0.0000001)
        assertEquals(0.0, ExcelExporter.ratio(0), 0.0000001)
        assertEquals(1.0, ExcelExporter.ratio(10_000), 0.0000001)
    }

    @Test
    fun `列名按 Excel 规则递增`() {
        assertEquals("A", ExcelExporter.columnName(0))
        assertEquals("Z", ExcelExporter.columnName(25))
        assertEquals("AA", ExcelExporter.columnName(26))
        assertEquals("AB", ExcelExporter.columnName(27))
        assertEquals("AZ", ExcelExporter.columnName(51))
        assertEquals("BA", ExcelExporter.columnName(52))
    }

    @Test
    fun `工作表名过滤非法字符并限制长度`() {
        val long = "很长的表名".repeat(20)
        val files = entries(ExcelExporter.workbook(listOf(ExcelExporter.Sheet(long, listOf("a"), emptyList()))))
        val xml = files["xl/workbook.xml"].orEmpty()
        val name = Regex("name=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
        assertEquals(31, name?.length)
    }

    @Test
    fun `空工作表也能导出`() {
        val files = entries(ExcelExporter.workbook(listOf(ExcelExporter.Sheet("空表", listOf("列"), emptyList()))))
        val xml = files["xl/worksheets/sheet1.xml"].orEmpty()
        assertTrue(xml.contains("列"))
        assertTrue("没有数据行时不应多出行节点", !xml.contains("<row r=\"2\">"))
    }

    @Test
    fun `空单元格写成空样式单元格而不是零`() {
        val xml = ExcelExporter.sheetXml(
            ExcelExporter.Sheet(
                name = "空值",
                header = listOf("退款对应账单ID"),
                rows = listOf(listOf(ExcelExporter.Cell.Empty)),
            ),
        )
        assertTrue(xml.contains("<c r=\"A2\""))
        assertTrue("空单元格不应写入 0", !xml.contains("<v>0</v>"))
    }
}