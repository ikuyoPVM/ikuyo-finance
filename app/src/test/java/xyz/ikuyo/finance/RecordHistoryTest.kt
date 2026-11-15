package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.BillSnapshot
import xyz.ikuyo.finance.core.FlatJson
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperationType
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetChange
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.PaymentMethod

class RecordHistoryTest {

    private fun bill(
        id: Long = 7L,
        type: BillType = BillType.EXPENSE,
        amountMinor: Long = 2850L,
        note: String = "午饭",
    ) = Bill(
        id = id,
        type = type,
        amountMinor = amountMinor,
        exchangeRateScaled = 1_000_000L,
        cnyAmountMinor = amountMinor,
        categoryId = 3L,
        categoryName = "餐饮",
        categoryEmoji = "🍜",
        paymentMethod = PaymentMethod.WECHAT,
        note = note,
        occurredAt = 1_776_000_000_000L,
        createdAt = 1_776_000_100_000L,
        source = BillSource.MANUAL,
        refundOfBillId = null,
    )

    // ---- FlatJson ----

    @Test
    fun flatJsonRoundTripsPlainValues() {
        val text = FlatJson.encode(mapOf("a" to "1", "b" to null))
        val back = FlatJson.decode(text)
        assertNotNull(back)
        assertEquals("1", back!!["a"])
        assertTrue(back.containsKey("b"))
        assertNull(back["b"])
    }

    @Test
    fun flatJsonEscapesQuotesNewlinesAndUnicode() {
        val raw = "他说\"好\"\n然后\r\n换行\t与🍜"
        val text = FlatJson.encode(mapOf("note" to raw))
        assertTrue(text.contains("\\n"))
        assertTrue(text.contains("\\\""))
        assertEquals(raw, FlatJson.decode(text)!!["note"])
    }

    @Test
    fun flatJsonDecodeRejectsGarbage() {
        assertNull(FlatJson.decode("不是 json"))
        assertNull(FlatJson.decode("{"))
        assertNull(FlatJson.decode(""))
    }

    @Test
    fun flatJsonDecodeHandlesEmptyObject() {
        assertEquals(emptyMap<String, String?>(), FlatJson.decode("{}"))
    }

    // ---- BillSnapshot ----

    @Test
    fun snapshotRoundTripsEveryField() {
        val original = bill()
        val restored = BillSnapshot.decode(BillSnapshot.encode(original))
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun snapshotKeepsRefundLink() {
        val refund = bill(type = BillType.REFUND).copy(refundOfBillId = 42L, note = "")
        val restored = BillSnapshot.decode(BillSnapshot.encode(refund))!!
        assertEquals(42L, restored.refundOfBillId)
        assertEquals(BillType.REFUND, restored.type)
        assertEquals("", restored.note)
    }

    @Test
    fun snapshotDecodeRejectsBrokenPayload() {
        assertNull(BillSnapshot.decode(null))
        assertNull(BillSnapshot.decode(""))
        assertNull(BillSnapshot.decode("{\"id\":\"x\"}"))
    }

    @Test
    fun snapshotSurvivesNoteWithJsonMetacharacters() {
        val tricky = bill(note = "金额 \"28.50\" \\ 备注 {a:b}, c]")
        val restored = BillSnapshot.decode(BillSnapshot.encode(tricky))!!
        assertEquals(tricky.note, restored.note)
        assertEquals(tricky.amountMinor, restored.amountMinor)
    }

    // ---- 操作类型与摘要 ----

    @Test
    fun operationTypeFromDbFallsBackToCreate() {
        assertEquals(BillOperationType.UPDATE, BillOperationType.fromDb("UPDATE"))
        assertEquals(BillOperationType.PURGE, BillOperationType.fromDb("PURGE"))
        assertEquals(BillOperationType.CREATE, BillOperationType.fromDb("nonsense"))
    }

    @Test
    fun summaryOfDeleteMentionsAmountAndCategory() {
        val summary = OperationRepository.summaryOf(BillOperationType.DELETE, bill())
        assertTrue(summary.contains("删除"))
        assertTrue(summary.contains("28.50"))
        assertTrue(summary.contains("餐饮"))
    }

    @Test
    fun summaryOfIncomeUsesSignedAmount() {
        val summary = OperationRepository.summaryOf(
            BillOperationType.CREATE,
            bill(type = BillType.INCOME, amountMinor = 100_000L, note = "工资"),
        )
        assertTrue(summary.contains("+"))
        assertTrue(summary.contains("1,000.00"))
    }

    // ---- 预算展示名与变更历史 ----

    @Test
    fun budgetDisplayNamePrefersCustomName() {
        val budget = Budget(scope = BudgetScope.CATEGORY, categoryId = 3L, amountMinor = 1L, name = "饭钱上限")
        assertEquals("饭钱上限", budget.displayName(categoryName = "餐饮"))
    }

    @Test
    fun budgetDisplayNameFallsBackByScope() {
        assertEquals("总预算", Budget(scope = BudgetScope.TOTAL, amountMinor = 1L).displayName())
        assertEquals(
            "餐饮",
            Budget(scope = BudgetScope.CATEGORY, categoryId = 3L, amountMinor = 1L).displayName(categoryName = "餐饮"),
        )
        assertEquals(
            "室友",
            Budget(scope = BudgetScope.TAG, tagId = 2L, amountMinor = 1L).displayName(tagName = "室友"),
        )
        assertEquals("专项预算", Budget(scope = BudgetScope.SPECIAL, amountMinor = 1L).displayName())
    }

    @Test
    fun budgetChangeTargetLabelUsesSnapshot() {
        assertEquals(
            "总预算",
            BudgetChange(scope = BudgetScope.TOTAL, changedAt = 0L).targetLabel,
        )
        assertEquals(
            "春节专项",
            BudgetChange(scope = BudgetScope.SPECIAL, categoryName = "春节专项", changedAt = 0L).targetLabel,
        )
        assertEquals(
            "标签预算",
            BudgetChange(scope = BudgetScope.TAG, changedAt = 0L).targetLabel,
        )
    }
}