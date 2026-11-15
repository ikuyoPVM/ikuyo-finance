package xyz.ikuyo.finance.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 金额工具。
 *
 * 约定：所有金额以「最小货币单位」的 Long 保存（人民币即「分」），
 * 禁止使用 Float / Double 参与任何财务运算。
 */
object Money {

    /** 汇率缩放倍数：1.0 汇率保存为 1_000_000。 */
    const val RATE_SCALE: Long = 1_000_000L

    const val BASE_CURRENCY: String = "CNY"

    fun decimalsOf(currency: String): Int = when (currency.uppercase(Locale.ROOT)) {
        "JPY" -> 0
        else -> 2
    }

    fun symbolOf(currency: String): String = when (currency.uppercase(Locale.ROOT)) {
        "CNY", "JPY" -> "¥"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "HKD" -> "HK$"
        else -> "$currency "
    }

    /** 把最小单位金额格式化为带千分位的显示文本。 */
    fun format(minorUnits: Long, currency: String = BASE_CURRENCY, showSymbol: Boolean = true): String {
        val decimals = decimalsOf(currency)
        val value = BigDecimal.valueOf(minorUnits)
            .movePointLeft(decimalsOf(currency))
            .setScale(decimals, RoundingMode.HALF_UP)
        val text = formatter(decimals).format(value)
        return if (showSymbol) symbolOf(currency) + text else text
    }

    /**
     * 输出不带货币符号、不带千分位的纯数字文本（如 `-1146.70`）。
     *
     * 用于 CSV / Excel 等在表格软件里必须保持数字语义的场景：带千分位的 "1,146.70"
     * 会被当成两个单元格或文本。展示用的文本请用 [format]。
     */
    fun plain(minorUnits: Long, currency: String = BASE_CURRENCY): String {
        val decimals = decimalsOf(currency)
        return BigDecimal.valueOf(minorUnits)
            .movePointLeft(decimals)
            .setScale(decimals, RoundingMode.HALF_UP)
            .toPlainString()
    }

    /** 格式化带正负号（用于净收支）。 */
    fun formatSigned(minorUnits: Long, currency: String = BASE_CURRENCY): String =
        if (minorUnits >= 0) "+" + format(minorUnits, currency) else "-" + format(-minorUnits, currency)

    /**
     * 归一化用户输入的数字串，让常见的手滑输入也能被正确解析：
     * - 去掉货币符号、千分位逗号、空白；
     * - 全角句点 / 中文句号统一为 "."
     * - 第二个小数点之后的内容丢弃（"1.2.3" -> "1.2"）；
     * - 去掉末尾多余的小数点（"28.50." -> "28.50"，"28." -> "28"）。
     */
    private fun normalizeNumeric(input: String): String {
        val raw = input.trim()
            .replace(",", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace(" ", "")
            .replace("．", ".")
            .replace("。", ".")
        val firstDot = raw.indexOf('.')
        val cut = if (firstDot < 0) {
            raw
        } else {
            val secondDot = raw.indexOf('.', firstDot + 1)
            if (secondDot < 0) raw else raw.substring(0, secondDot)
        }
        return if (cut.endsWith(".")) cut.dropLast(1) else cut
    }

    /** 解析用户输入为最小单位；空白或非法输入返回 null。 */
    fun parseToMinor(input: String, currency: String = BASE_CURRENCY): Long? {
        val cleaned = normalizeNumeric(input)
        if (cleaned.isEmpty() || cleaned == ".") return null
        val value = try {
            BigDecimal(cleaned)
        } catch (e: NumberFormatException) {
            return null
        }
        if (value.signum() < 0) return null
        val decimals = decimalsOf(currency)
        return try {
            value.movePointRight(decimals).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    /**
     * 原币金额 -> 人民币最小单位。
     * @param rate 1 单位原币可兑换的人民币金额。
     */
    fun convertToCnyMinor(amountMinor: Long, currency: String, rate: BigDecimal): Long {
        if (currency.equals(BASE_CURRENCY, ignoreCase = true) && rate.compareTo(BigDecimal.ONE) == 0) {
            return amountMinor
        }
        val major = BigDecimal.valueOf(amountMinor).movePointLeft(decimalsOf(currency))
        return major.multiply(rate).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    fun rateFromScaled(scaled: Long): BigDecimal =
        BigDecimal.valueOf(scaled).movePointLeft(6)

    fun scaledFromRate(rate: BigDecimal): Long =
        rate.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /**
     * 汇率展示文本：最多 4 位小数，并去掉末尾多余的 0。
     *
     * 汇率内部按 6 位小数存储（7_119_972 → 7.119972），直接展示会有六位数字噪音，
     * 而 4 位已经足够区分真实汇率差异；7.120000 → "7.12"、6.672004 → "6.672"。
     */
    fun formatRate(scaled: Long): String =
        rateFromScaled(scaled).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun formatter(decimals: Int): DecimalFormat {
        // 小数位必须用 0 而不是 #：金额显示需要固定小数位（12.00 而不是 12），# 会吞掉末尾的 0。
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        return DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US))
    }
}