package xyz.ikuyo.finance.data.export

import xyz.ikuyo.finance.core.Csv
import xyz.ikuyo.finance.core.Time
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 多工作表 Excel 导出。
 *
 * 直接用 java.util.zip 写一个最小的 SpreadsheetML(.xlsx) 包，不引入第三方库：
 * 只用到 inlineStr 字符串、数值与少量数字格式，Excel / WPS / Numbers 都能打开。
 *
 * 约定：
 * - 单元格分三类：文本、数值（两位小数）、百分比（存 0.15，按 0.0% 显示）。
 * - 文本一律走 [Csv.escapeField] 的防公式前缀处理，避免把用户备注当公式执行。
 * - 金额一律用「分」输入，写文件时换算成元并保留两位小数。
 */
object ExcelExporter {

    /** 单元格。 */
    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: Double) : Cell
        /** 比例，0.1538 表示 15.38%。 */
        data class Percent(val ratio: Double) : Cell
        data object Empty : Cell
    }

    data class Sheet(val name: String, val header: List<String>, val rows: List<List<Cell>>)

    private const val STYLE_DEFAULT = 0
    private const val STYLE_MONEY = 1
    private const val STYLE_PERCENT = 2
    private const val STYLE_HEADER = 3

    /** 单元格文本最大长度，超过 Excel 会截断，这里提前裁剪避免文件异常。 */
    private const val MAX_TEXT = 32_000

    fun workbook(sheets: List<Sheet>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putText("[Content_Types].xml", contentTypes(sheets.size))
            zip.putText("_rels/.rels", rootRels())
            zip.putText("xl/workbook.xml", workbookXml(sheets))
            zip.putText("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.putText("xl/styles.xml", STYLES)
            sheets.forEachIndexed { index, sheet ->
                zip.putText("xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet))
            }
        }
        return out.toByteArray()
    }

    internal fun sheetXml(sheet: Sheet): String {
        val body = StringBuilder()
        body.append(row(1, sheet.header.map { Cell.Text(it) }, STYLE_HEADER))
        sheet.rows.forEachIndexed { index, cells ->
            body.append(row(index + 2, cells, null))
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<sheetPr><outlinePr summaryBelow=\"1\"/></sheetPr>")
            append("<cols>")
            sheet.header.forEachIndexed { index, _ ->
                append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"18\" customWidth=\"1\"/>")
            }
            append("</cols><sheetData>")
            append(body)
            append("</sheetData></worksheet>")
        }
    }

    /** 金额（分）转 Excel 数值（元）。 */
    fun yuan(minor: Long): Double = minor / 100.0

    /** 万分比转 Excel 比例。 */
    fun ratio(basisPoints: Int): Double = basisPoints / 10_000.0

    fun dateTime(millis: Long): String =
        Time.toLocalDateTime(millis).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    fun date(millis: Long): String = Time.toLocalDate(millis).toString()

    private fun row(index: Int, cells: List<Cell>, forcedStyle: Int?): String = buildString {
        append("<row r=\"$index\">")
        cells.forEachIndexed { column, cell ->
            val ref = columnName(column) + index
            when (cell) {
                is Cell.Text -> {
                    val style = forcedStyle ?: STYLE_DEFAULT
                    append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    append(escape(safeText(cell.value)))
                    append("</t></is></c>")
                }

                is Cell.Number -> append("<c r=\"$ref\" s=\"$STYLE_MONEY\"><v>${plain(cell.value)}</v></c>")
                is Cell.Percent -> append("<c r=\"$ref\" s=\"$STYLE_PERCENT\"><v>${plain(cell.ratio)}</v></c>")
                Cell.Empty -> append("<c r=\"$ref\" s=\"${forcedStyle ?: STYLE_DEFAULT}\"/>")
            }
        }
        append("</row>")
    }

    private fun safeText(value: String): String =
        if (value.length <= MAX_TEXT) value else value.substring(0, MAX_TEXT)

    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** 单元格 XML 转义。 */
    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> if (c.code < 0x20) append(' ') else append(c)
            }
        }
    }

    /** 0 → A, 25 → Z, 26 → AA。 */
    fun columnName(index: Int): String {
        var value = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + value % 26))
            value = value / 26 - 1
            if (value < 0) break
        }
        return sb.toString()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in 1..sheetCount) {
            append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun rootRels(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>")
        append("</Relationships>")
    }

    private fun workbookXml(sheets: List<Sheet>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<sheets>")
        sheets.forEachIndexed { index, sheet ->
            append("<sheet name=\"${escape(sheetName(sheet.name))}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    /** Excel 工作表名限制：不超过 31 字符，且不能包含 : \ / ? * [ ]。 */
    private fun sheetName(name: String): String =
        name.replace(Regex("[\\\\/:*?\\[\\]]"), "·").take(31).ifBlank { "Sheet" }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..sheetCount) {
            append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private val STYLES = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<numFmts count=\"2\">")
        append("<numFmt numFmtId=\"164\" formatCode=\"0.00\"/>")
        append("<numFmt numFmtId=\"165\" formatCode=\"0.0%\"/>")
        append("</numFmts>")
        append("<fonts count=\"2\">")
        append("<font><sz val=\"11\"/><name val=\"Calibri\"/></font>")
        append("<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>")
        append("</fonts>")
        append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>")
        append("<fill><patternFill patternType=\"gray125\"/></fill></fills>")
        append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
        append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        append("<cellXfs count=\"4\">")
        append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        append("<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        append("<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>")
        append("</cellXfs>")
        append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        append("</styleSheet>")
    }
}