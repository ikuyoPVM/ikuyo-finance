package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.data.ai.AiBillParser
import xyz.ikuyo.finance.data.ai.ChatCodec
import xyz.ikuyo.finance.domain.model.AiChatMessage
import xyz.ikuyo.finance.domain.model.BillCandidate
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.ChatRole
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.RateSource
import java.time.LocalDate

/**
 * AI 记账解析与会话存储的单元测试。
 *
 * 重点覆盖阶段三计划书第 5 章「候选账单」与第 10 章测试清单：
 * 多笔拆分、缺金额、分类不在清单、退款需指定原单、支付方式缺省、置信度阈值、会话往返。
 */
class AiChatTest {

    private val categories = listOf(
        Category(id = 1L, name = "餐饮", emoji = "🍜"),
        Category(id = 2L, name = "交通出行", emoji = "🚇"),
        Category(id = 3L, name = "零食饮料", emoji = "🧋"),
    )
    private val today = LocalDate.of(2026, 3, 15)

    private fun parser() = AiBillParser(idFactory = { "fixed-id" })

    private fun parse(content: String) = parser().parse(content, categories, today)

    @Test
    fun `外币候选按本地汇率折算并强制确认`() {
        val rates = mapOf(
            "USD" to CurrencyRate(
                currency = "USD",
                rateScaled = 7_120_000L,
                source = RateSource.MANUAL,
                updatedAt = 1_700_000_000_000L,
            ),
        )
        val turn = parser().parse(
            """
            {"reply":"记好了","bills":[{"type":"expense","amount":19.99,"currency":"USD","category":"餐饮","date":"2026-03-15","time":"12:30","payment":"微信","note":"咖啡","confidence":0.99}]}
            """.trimIndent(),
            categories = categories,
            today = today,
            rates = rates,
        )
        val candidate = turn.candidates.single()
        assertEquals("USD", candidate.currency)
        assertEquals(7_120_000L, candidate.exchangeRateScaled)
        // 模型给的高置信度也不能自动入账：外币必须确认。
        assertTrue(candidate.needsConfirm(0.85))
        assertTrue(candidate.reasons.any { it.contains("本地汇率") })
    }

    @Test
    fun `外币没有本地汇率时只提示设置而不编造汇率`() {
        val turn = parser().parse(
            """
            {"reply":"记好了","bills":[{"type":"expense","amount":19.99,"currency":"USD","category":"餐饮","date":"2026-03-15","time":"12:30","confidence":0.99}]}
            """.trimIndent(),
            categories = categories,
            today = today,
        )
        val candidate = turn.candidates.single()
        assertEquals("USD", candidate.currency)
        assertEquals(1_000_000L, candidate.exchangeRateScaled)
        assertTrue(candidate.needsConfirm(0.85))
        assertTrue(candidate.reasons.any { it.contains("汇率与币种") })
    }

    @Test
    fun `未知币种回落为人民币`() {
        val turn = parser().parse(
            """
            {"reply":"记好了","bills":[{"type":"expense","amount":12,"currency":"XYZ","category":"餐饮","date":"2026-03-15","time":"12:30","payment":"微信","confidence":0.99}]}
            """.trimIndent(),
            categories = categories,
            today = today,
        )
        val candidate = turn.candidates.single()
        assertEquals("CNY", candidate.currency)
        assertEquals(1_000_000L, candidate.exchangeRateScaled)
        assertFalse(candidate.needsConfirm(0.85))
    }

    @Test
    fun `解析单笔支出并匹配分类`() {
        val turn = parse(
            """
            {"reply":"记好了","bills":[{"type":"expense","amount":28.5,"category":"餐饮","date":"2026-03-15","time":"12:30","payment":"微信","note":"午饭","confidence":0.95}]}
            """.trimIndent()
        )
        assertEquals("记好了", turn.reply)
        assertEquals(1, turn.candidates.size)
        val candidate = turn.candidates.single()
        assertEquals(BillType.EXPENSE, candidate.type)
        assertEquals(2850L, candidate.amountMinor)
        assertEquals(1L, candidate.categoryId)
        assertEquals("餐饮", candidate.categoryName)
        assertEquals(PaymentMethod.WECHAT, candidate.paymentMethod)
        assertEquals("午饭", candidate.note)
        assertTrue(candidate.reasons.isEmpty())
        assertFalse(candidate.needsConfirm(0.85))
    }

    @Test
    fun `多笔账单拆成多个候选且顺序稳定`() {
        val turn = parse(
            """
            ```json
            {"reply":"两笔都记了","bills":[
              {"type":"expense","amount":12,"category":"餐饮","payment":"现金","confidence":0.9},
              {"type":"expense","amount":5.5,"category":"交通出行","payment":"支付宝","confidence":0.9}
            ]}
            ```
            """.trimIndent()
        )
        assertEquals(2, turn.candidates.size)
        assertEquals(1200L, turn.candidates[0].amountMinor)
        assertEquals(550L, turn.candidates[1].amountMinor)
        assertEquals(2L, turn.candidates[1].categoryId)
    }

    @Test
    fun `金额缺失的那一笔被丢弃而不是记成零元`() {
        val turn = parse(
            """
            {"reply":"有一笔没听懂","bills":[
              {"type":"expense","category":"餐饮","payment":"微信"},
              {"type":"expense","amount":30,"category":"餐饮","payment":"微信"}
            ]}
            """.trimIndent()
        )
        assertEquals(1, turn.candidates.size)
        assertEquals(3000L, turn.candidates.single().amountMinor)
    }

    @Test
    fun `分类不在清单时要求手动选择`() {
        val candidate = parse(
            """{"reply":"ok","bills":[{"type":"expense","amount":9,"category":"宠物"}],"confidence":0.9}"""
        ).candidates.single()
        assertNull(candidate.categoryId)
        assertTrue(candidate.reasons.any { it.contains("分类") && it.contains("宠物") })
        assertTrue(candidate.needsConfirm(0.85))
    }

    @Test
    fun `退款一定需要确认原账单`() {
        val candidate = parse(
            """{"reply":"ok","bills":[{"type":"refund","amount":28.5,"category":"餐饮","payment":"微信","confidence":0.99}]}"""
        ).candidates.single()
        assertEquals(BillType.REFUND, candidate.type)
        assertTrue(candidate.reasons.any { it.contains("退款") })
        assertTrue(candidate.needsConfirm(0.5))
    }

    @Test
    fun `收入类型中英文都能识别`() {
        val english = parse("""{"reply":"ok","bills":[{"type":"income","amount":1000,"category":"餐饮","payment":"银行卡"}]}""")
            .candidates.single()
        assertEquals(BillType.INCOME, english.type)
        assertEquals(PaymentMethod.BANK_CARD, english.paymentMethod)

        val chinese = parse("""{"reply":"ok","bills":[{"type":"收入","amount":800,"category":"餐饮","payment":"现金"}]}""")
            .candidates.single()
        assertEquals(BillType.INCOME, chinese.type)
    }

    @Test
    fun `支付方式缺省时标记为待确认且落在其他`() {
        val candidate = parse("""{"reply":"ok","bills":[{"type":"expense","amount":18,"category":"零食饮料"}]}""")
            .candidates.single()
        assertEquals(PaymentMethod.OTHER, candidate.paymentMethod)
        assertTrue(candidate.reasons.any { it.contains("支付方式") })
    }

    @Test
    fun `没有日期时落在今天且默认中午`() {
        val candidate = parse("""{"reply":"ok","bills":[{"type":"expense","amount":18,"category":"餐饮","payment":"微信"}]}""")
            .candidates.single()
        val date = java.time.Instant.ofEpochMilli(candidate.occurredAt)
            .atZone(xyz.ikuyo.finance.core.Time.zone)
            .toLocalDate()
        assertEquals(today, date)
    }

    @Test
    fun `置信度缺省为零点五并夹在合法区间`() {
        val low = parse("""{"reply":"ok","bills":[{"type":"expense","amount":1,"category":"餐饮","payment":"微信"}]}""")
            .candidates.single()
        assertEquals(0.5, low.confidence, 0.0001)

        val high = parse("""{"reply":"ok","bills":[{"type":"expense","amount":1,"category":"餐饮","payment":"微信","confidence":1.8}]}""")
            .candidates.single()
        assertEquals(1.0, high.confidence, 0.0001)
    }

    @Test
    fun `纯散文回复没有候选账单`() {
        val turn = parse("今天一共花了 36 元，主要是午饭。")
        assertEquals("今天一共花了 36 元，主要是午饭。", turn.reply)
        assertTrue(turn.candidates.isEmpty())
    }

    @Test
    fun `标签名解析为字符串列表`() {
        val candidate = parse(
            """{"reply":"ok","bills":[{"type":"expense","amount":20,"category":"餐饮","payment":"微信","tags":["work","每月固定"]}]}"""
        ).candidates.single()
        assertEquals(listOf("work", "每月固定"), candidate.tagNames)
    }

    @Test
    fun `候选账单需要确认的阈值判断`() {
        val candidate = BillCandidate(
            localId = "a",
            clientKey = "k",
            type = BillType.EXPENSE,
            amountMinor = 100L,
            occurredAt = 0L,
            confidence = 0.9,
        )
        assertFalse(candidate.needsConfirm(0.85))
        assertTrue(candidate.needsConfirm(0.95))
        assertTrue(candidate.copy(reasons = listOf("分类不确定")).needsConfirm(0.5))
    }

    @Test
    fun `会话存储往返保留候选账单状态`() {
        val messages = listOf(
            AiChatMessage(localId = "m1", role = ChatRole.USER, content = "午饭 28", createdAt = 1_700_000_000_000L),
            AiChatMessage(
                localId = "m2",
                role = ChatRole.ASSISTANT,
                content = "记好了",
                createdAt = 1_700_000_001_000L,
                candidates = listOf(
                    BillCandidate(
                        localId = "c1",
                        clientKey = "client-1",
                        type = BillType.EXPENSE,
                        amountMinor = 2850L,
                        categoryId = 1L,
                        categoryName = "餐饮",
                        occurredAt = 1_700_000_000_000L,
                        paymentMethod = PaymentMethod.WECHAT,
                        note = "午饭",
                        tagNames = listOf("work"),
                        confidence = 0.92,
                        reasons = listOf("分类置信度一般"),
                        applied = true,
                        appliedBillId = 7L,
                        appliedOperationId = 9L,
                    )
                ),
            ),
        )

        val encoded = ChatCodec.encode(messages)
        val decoded = ChatCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals(ChatRole.USER, decoded[0].role)
        val restored = decoded[1].candidates.single()
        assertEquals("client-1", restored.clientKey)
        assertEquals(2850L, restored.amountMinor)
        assertEquals(1L, restored.categoryId)
        assertEquals("餐饮", restored.categoryName)
        assertEquals(PaymentMethod.WECHAT, restored.paymentMethod)
        assertEquals(listOf("work"), restored.tagNames)
        assertEquals(0.92, restored.confidence, 0.0001)
        assertEquals(listOf("分类置信度一般"), restored.reasons)
        assertTrue(restored.applied)
        assertEquals(7L, restored.appliedBillId)
        assertEquals(9L, restored.appliedOperationId)
    }

    @Test
    fun `坏数据不会让会话整体丢失`() {
        assertTrue(ChatCodec.decode("").isEmpty())
        assertTrue(ChatCodec.decode("not json").isEmpty())
        assertTrue(ChatCodec.decode("""{"messages":[]}""").isEmpty())
        assertNotNull(ChatCodec.decode("""[{"id":"m","role":"assistant","content":"hi","at":1}]"""))
    }

    @Test
    fun `会话存储往返保留全部消息且上限为六十条`() {
        val messages = (1..40).map {
            AiChatMessage(
                localId = "m$it",
                role = ChatRole.USER,
                content = "第 $it 句",
                createdAt = it.toLong(),
            )
        }
        val decoded = ChatCodec.decode(ChatCodec.encode(messages))
        assertEquals(40, decoded.size)
        assertEquals("第 40 句", decoded.last().content)
        // 裁剪发生在仓储层写入时（只保留最近 60 条），编解码本身不丢数据。
        assertEquals(60, xyz.ikuyo.finance.data.ai.ChatRepository.MAX_MESSAGES)
    }
}