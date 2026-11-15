package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.model.BillCandidate
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.ChatRole
import xyz.ikuyo.finance.domain.model.CurrencyCatalog
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.model.PaymentMethod
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 一次模型回复解析出来的结果。 */
data class ParsedTurn(
    val reply: String,
    val candidates: List<BillCandidate> = emptyList(),
)

/**
 * 把模型回复解析成「回答文本 + 候选账单」。
 *
 * 全部是纯函数（无 IO、无网络），因此可以在 JVM 单测里穷举各种畸形输出：
 * 带 Markdown 围栏、缺字段、金额是字符串、分类不在清单、多笔拆分等。
 * 任何解析失败都退化成「纯文本回答」，绝不抛异常、绝不自动入账。
 */
class AiBillParser(private val idFactory: () -> String = { UUID.randomUUID().toString() }) {

    fun parse(
        content: String,
        categories: List<Category>,
        today: LocalDate = LocalDate.now(Time.zone),
        /** 本地汇率表：模型只给币种，折算率一律取本地记录（计划书 6.4）。 */
        rates: Map<String, CurrencyRate> = emptyMap(),
    ): ParsedTurn {
        val text = content.trim()
        if (text.isEmpty()) return ParsedTurn("模型返回了空内容，换个说法再试一次。")

        val root = Json.extractJson(text) as? JsonValue.Obj
            ?: return ParsedTurn(text)

        val reply = root.str("reply")?.trim().orEmpty().ifBlank { fallbackReply(root) }
        val bills = root.arr("bills") ?: root.arr("transactions")
        if (bills == null) return ParsedTurn(reply)

        val parsed = bills.items.mapNotNull { item ->
            val obj = item as? JsonValue.Obj ?: return@mapNotNull null
            parseBill(obj, categories, today, rates)
        }
        val notes = parsed.mapNotNull { it.second }
        val candidates = parsed.map { it.first }
        val finalReply = if (notes.isEmpty()) {
            reply
        } else {
            listOf(reply, notes.joinToString("；")).filter { it.isNotBlank() }.joinToString("\n")
        }
        return ParsedTurn(finalReply, candidates)
    }

    /** @return 候选账单 与 需要额外告知用户的一句话（可空） */
    private fun parseBill(
        obj: JsonValue.Obj,
        categories: List<Category>,
        today: LocalDate,
        rates: Map<String, CurrencyRate>,
    ): Pair<BillCandidate, String?>? {
        val amountText = (obj.get("amount") as? JsonValue.Num)?.raw
            ?: obj.str("amount")
        val amountMinor = amountText?.let { Money.parseToMinor(it) } ?: 0L
        if (amountMinor <= 0L) return null

        val reasons = mutableListOf<String>()

        val type = parseType(obj.str("type"))
        val categoryName = obj.str("category")?.trim().orEmpty().ifBlank {
            (obj.obj("category") as? JsonValue.Obj)?.str("name").orEmpty()
        }
        val category = matchCategory(categoryName, categories)
        if (category == null && categoryName.isNotBlank()) {
            reasons += "分类「$categoryName」不在你的分类清单里，请手动选择"
        }

        val payment = parsePayment(obj.str("payment"))
        if (payment == PaymentMethod.OTHER) {
            reasons += "支付方式未确认"
        }
        if (type == BillType.REFUND) {
            reasons += "退款需要你指定原账单"
        }

        val tags = obj.arr("tags")?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()
        val confidence = (obj.get("confidence") as? JsonValue.Num)?.value ?: 0.5
        val aiReasons = obj.arr("reasons")?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()
        aiReasons.filter { it.isNotBlank() }.forEach { reasons += it }

        // 币种只做「合法化」，汇率一律取本地记录：模型给的任何汇率数字都不采用。
        val currency = (obj.str("currency")?.uppercase()?.trim().orEmpty())
            .takeIf { CurrencyCatalog.isSupported(it) }
            ?: Money.BASE_CURRENCY
        var exchangeRateScaled = Money.RATE_SCALE
        if (currency != Money.BASE_CURRENCY) {
            val rate = CurrencyCatalog.rateOf(currency, rates)
            if (rate == null) {
                reasons += "没有 ${CurrencyCatalog.label(currency)} 的本地汇率，请先在「汇率与币种」设置再确认入账"
            } else {
                exchangeRateScaled = rate.rateScaled
                val rateText = Money.formatRate(rate.rateScaled)
                reasons += "外币 ${CurrencyCatalog.label(currency)}，按本地汇率 ¥$rateText 折算，请确认金额"
            }
        }

        val candidate = BillCandidate(
            localId = idFactory(),
            clientKey = "ai-${idFactory()}",
            type = type,
            amountMinor = amountMinor,
            currency = currency,
            exchangeRateScaled = exchangeRateScaled,
            categoryId = category?.id,
            categoryName = category?.name ?: categoryName.ifBlank { null },
            occurredAt = resolveOccurredAt(obj.str("date"), obj.str("time"), today),
            paymentMethod = payment,
            note = obj.str("note")?.trim().orEmpty(),
            tagNames = tags,
            confidence = confidence.coerceIn(0.0, 1.0),
            reasons = reasons.distinct(),
        )
        return candidate to null
    }

    private fun fallbackReply(root: JsonValue.Obj): String =
        if ((root.arr("bills")?.size ?: 0) > 0 || (root.arr("transactions")?.size ?: 0) > 0) {
            "已经解析出候选账单，确认后才会入账。"
        } else {
            ""
        }

    private fun parseType(raw: String?): BillType {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when (value) {
            "income", "收入", "入账" -> BillType.INCOME
            "refund", "退款", "退回" -> BillType.REFUND
            else -> BillType.EXPENSE
        }
    }

    private fun parsePayment(raw: String?): PaymentMethod {
        val value = raw?.trim().orEmpty()
        val upper = value.uppercase()
        return when {
            upper.contains("WECHAT") || value.contains("微信") -> PaymentMethod.WECHAT
            upper.contains("ALIPAY") || value.contains("支付宝") -> PaymentMethod.ALIPAY
            upper.contains("BANK") || value.contains("银行卡") || value.contains("信用卡") ->
                PaymentMethod.BANK_CARD
            upper.contains("CASH") || value.contains("现金") -> PaymentMethod.CASH
            else -> PaymentMethod.OTHER
        }
    }

    private fun matchCategory(name: String, categories: List<Category>): Category? {
        val needle = name.trim()
        if (needle.isEmpty()) return null
        return categories.firstOrNull { it.name == needle }
            ?: categories.firstOrNull { it.name.contains(needle) || needle.contains(it.name) }
    }

    private fun resolveOccurredAt(dateText: String?, timeText: String?, today: LocalDate): Long {
        val date = dateText?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: today
        val time = parseTime(timeText)
        return date.atTime(time).atZone(Time.zone).toInstant().toEpochMilli()
    }

    private fun parseTime(raw: String?): LocalTime {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return LocalTime.NOON
        val parts = text.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return LocalTime.NOON
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return runCatching { LocalTime.of(hour, minute) }.getOrDefault(LocalTime.NOON)
    }

    companion object {
        /** 会话消息里保留的最近条数（超出丢弃最早的用户提问，保留上下文摘要不在这里做）。 */
        const val MAX_CONTEXT_MESSAGES = 12

        val ROLE_USER = ChatRole.USER
    }
}