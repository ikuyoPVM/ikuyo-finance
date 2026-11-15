package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Csv

class CsvTest {

    @Test
    fun `普通字段不改变内容`() {
        assertEquals("午饭", Csv.escapeField("午饭"))
        assertEquals("28.00", Csv.escapeField("28.00", guardFormula = false))
    }

    @Test
    fun `含逗号引号换行的字段加引号并转义双引号`() {
        assertEquals("\"a,b\"", Csv.escapeField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escapeField("say \"hi\""))
        assertEquals("\"第一行\n第二行\"", Csv.escapeField("第一行\n第二行"))
    }

    @Test
    fun `以公式前缀开头的文本被加保护前缀`() {
        assertTrue(Csv.needsGuard("=SUM(A1)"))
        assertTrue(Csv.needsGuard("+1"))
        assertTrue(Csv.needsGuard("-1+1"))
        assertTrue(Csv.needsGuard("@cmd"))
        assertFalse(Csv.needsGuard("午饭 =12"))
        assertEquals("'=SUM(A1)", Csv.escapeField("=SUM(A1)"))
    }

    @Test
    fun `数字列不做公式保护`() {
        assertEquals("-12.34", Csv.escapeField("-12.34", guardFormula = false))
    }

    @Test
    fun `导入时去掉保护前缀`() {
        assertEquals("=SUM(A1)", Csv.stripGuard("'=SUM(A1)"))
        assertEquals("'普通", Csv.stripGuard("'普通"))
        assertEquals("", Csv.stripGuard(""))
    }

    @Test
    fun `解析往返保持字段内容`() {
        val rows = listOf(
            listOf("日期", "金额", "备注"),
            listOf("2026-11-15", "28.00", "早饭,加个蛋"),
            listOf("2026-11-15", "12.50", "他说\"好吃\""),
            listOf("2026-11-15", "9.90", "第一行\n第二行"),
        )
        // Csv.file 只做拼接，字段必须按契约先转义。
        val escaped = rows.map { row -> row.map { Csv.escapeField(it) } }
        val text = Csv.file(escaped)
        val parsed = Csv.parse(text)
        assertEquals(4, parsed.size)
        assertEquals(rows[1], parsed[1])
        assertEquals(rows[2], parsed[2])
        assertEquals(rows[3], parsed[3])
    }

    @Test
    fun `解析兼容 BOM、CRLF 与缺少结尾换行`() {
        val text = "\uFEFFa,b\r\n1,2"
        val parsed = Csv.parse(text)
        assertEquals(2, parsed.size)
        assertEquals(listOf("a", "b"), parsed[0])
        assertEquals(listOf("1", "2"), parsed[1])
    }

    @Test
    fun `解析保留空字段并跳过空行`() {
        val parsed = Csv.parse("a,,c\n\n1,2,\n")
        assertEquals(2, parsed.size)
        assertEquals(listOf("a", "", "c"), parsed[0])
        assertEquals(listOf("1", "2", ""), parsed[1])
    }
}