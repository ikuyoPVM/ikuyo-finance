package xyz.ikuyo.finance.core

/**
 * CSV 读写工具（RFC 4180 风格）。
 *
 * 只负责文本层面的转义与切分，不含任何业务字段含义：
 * 导出器决定列顺序，导入器决定列映射。
 */
object Csv {

    /** 字段分隔符。 */
    const val DELIMITER = ','

    /** 行分隔符：写出时统一用 CRLF，Excel 与大多数表格软件都能正确识别。 */
    const val LINE_BREAK = "\r\n"

    /**
     * 会被电子表格当成公式开头的字符。
     *
     * 以这些字符开头的用户文本（备注、名称）如果不处理，打开 CSV 时会被 Excel/WPS
     * 当作公式求值，即公式注入。数字列本身由本应用生成，调用时传 [guardFormula] = false。
     */
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /** 转义后的保护前缀。 */
    private const val GUARD = '\''

    /** 需要加引号的字符。 */
    private const val NEEDS_QUOTE = ",\"\n\r"

    /**
     * 转义一个字段。
     *
     * @param value 原始文本。
     * @param guardFormula 是否做公式注入保护；用户可编辑的文本列必须为 true，
     *   由本应用生成的数字与日期列为 false（负数以 `-` 开头，不能被加前缀）。
     */
    fun escapeField(value: String, guardFormula: Boolean = true): String {
        val guarded = if (guardFormula && needsGuard(value)) "$GUARD$value" else value
        val mustQuote = guarded.any { it in NEEDS_QUOTE }
        if (!mustQuote) return guarded
        return buildString(guarded.length + 8) {
            append('"')
            for (ch in guarded) {
                if (ch == '"') append('"')
                append(ch)
            }
            append('"')
        }
    }

    /** 判断文本是否会被表格软件当作公式。 */
    fun needsGuard(value: String): Boolean =
        value.isNotEmpty() && value[0] in FORMULA_PREFIXES

    /** 去掉导入时遇到的保护前缀。 */
    fun stripGuard(value: String): String {
        if (value.length >= 2 && value[0] == GUARD && value[1] in FORMULA_PREFIXES) {
            return value.substring(1)
        }
        return value
    }

    /** 拼一行（不做额外处理，字段已由 [escapeField] 转义）。 */
    fun line(fields: List<String>): String = fields.joinToString(DELIMITER.toString()) + LINE_BREAK

    /** 拼一个文件。 */
    fun file(rows: List<List<String>>): String = buildString {
        for (row in rows) append(line(row))
    }

    /**
     * 解析 CSV 文本。
     *
     * 支持：UTF-8 BOM、CRLF/CR/LF、引号包裹的逗号与换行、双引号转义（""）。
     * 结尾没有换行也算完整一行；完全空白的行会被跳过。
     */
    fun parse(text: String): List<List<String>> {
        val source = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        var fieldStarted = false

        fun flushField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }

        fun flushRow() {
            flushField()
            if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
            row = ArrayList()
        }

        while (index < source.length) {
            val ch = source[index]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (index + 1 < source.length && source[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }

                ch == '"' -> {
                    inQuotes = true
                    fieldStarted = true
                }

                ch == DELIMITER -> flushField()

                ch == '\r' -> {
                    if (index + 1 < source.length && source[index + 1] == '\n') index++
                    flushRow()
                }

                ch == '\n' -> flushRow()

                else -> {
                    field.append(ch)
                    fieldStarted = true
                }
            }
            index++
        }
        if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) flushRow()
        return rows
    }
}