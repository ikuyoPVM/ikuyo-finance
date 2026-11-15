package xyz.ikuyo.finance.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.data.local.entity.TagEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentPaymentEntity

/**
 * 备份编解码与结构检查。
 *
 * 覆盖计划书 §2.2（版本与完整性校验）和 §3（恢复前预览要能判断结构是否可恢复）。
 */
class BackupCodecTest {

    private fun category(id: Long, name: String) = CategoryEntity(
        id = id,
        name = name,
        emoji = "🍜",
        colorArgb = 0xFF6750A4.toInt(),
        isDefault = false,
        sortOrder = 1,
    )

    private fun bill(
        id: Long,
        amountMinor: Long,
        categoryId: Long? = 1L,
        clientKey: String = "key-$id",
        type: String = "EXPENSE",
        currency: String = "CNY",
        exchangeRateScaled: Long = 1_000_000L,
        refundOfBillId: Long? = null,
        deleted: Boolean = false,
    ) = BillEntity(
        id = id,
        type = type,
        amountMinor = amountMinor,
        currency = currency,
        exchangeRateScaled = exchangeRateScaled,
        cnyAmountMinor = amountMinor,
        categoryId = categoryId,
        paymentMethod = "WECHAT",
        note = "午餐",
        occurredAt = 1_794_744_000_000L,
        createdAt = 1_794_744_000_000L,
        source = "MANUAL",
        refundOfBillId = refundOfBillId,
        rateSource = "MANUAL",
        rateUpdatedAt = 1_794_744_000_000L,
        clientKey = clientKey,
        deleted = deleted,
        deletedAt = if (deleted) 1_794_744_000_000L else null,
    )

    private fun sampleData() = BackupData(
        bills = listOf(bill(1, 12_000L), bill(2, 3_000L, refundOfBillId = 1L, type = "REFUND")),
        categories = listOf(category(1, "餐饮")),
        tags = listOf(TagEntity(id = 1, name = "工作", colorArgb = 0xFF00AA00.toInt())),
        billTags = listOf(BillTagCrossRef(billId = 1, tagId = 1)),
        settings = BackupSettings(mapOf("fixedReminderDays" to "3")),
    )

    private fun meta() = BackupMeta(
        appVersion = "0.6.0",
        createdAt = 1_794_744_000_000L,
        baseCurrency = "CNY",
    )

    @Test
    fun `编码后能原样解码出账本数据`() {
        val data = sampleData()
        val text = BackupCodec.encode(data, meta())

        val parsed = BackupCodec.decode(text).getOrThrow()
        assertEquals(2, parsed.data.bills.size)
        assertEquals("key-1", parsed.data.bills[0].clientKey)
        assertEquals(3_000L, parsed.data.bills[1].amountMinor)
        assertEquals("REFUND", parsed.data.bills[1].type)
        assertEquals(1L, parsed.data.bills[1].refundOfBillId)
        assertEquals("餐饮", parsed.data.categories.single().name)
        assertEquals("工作", parsed.data.tags.single().name)
        assertEquals(1, parsed.data.billTags.single().tagId)
        assertEquals("3", parsed.data.settings.value("fixedReminderDays"))
        assertEquals(BackupFormat.FORMAT_VERSION, parsed.meta.formatVersion)
        assertEquals(BackupFormat.DATA_VERSION, parsed.meta.dataVersion)
        assertEquals("0.6.0", parsed.meta.appVersion)
        assertFalse(parsed.encrypted)
    }

    @Test
    fun `删除的账单也会被完整保留`() {
        val data = BackupData(
            bills = listOf(bill(1, 5_000L, deleted = true)),
            categories = listOf(category(1, "餐饮")),
        )
        val parsed = BackupCodec.decode(BackupCodec.encode(data, meta())).getOrThrow()
        assertTrue(parsed.data.bills.single().deleted)
        assertNotNull(parsed.data.bills.single().deletedAt)
    }

    @Test
    fun `多币种账单保留原币与汇率快照`() {
        val data = BackupData(
            bills = listOf(
                bill(1, 1_999L, currency = "USD", exchangeRateScaled = 6_672_004L)
            ),
            categories = listOf(category(1, "餐饮")),
        )
        val parsed = BackupCodec.decode(BackupCodec.encode(data, meta())).getOrThrow()
        val restored = parsed.data.bills.single()
        assertEquals("USD", restored.currency)
        assertEquals(1_999L, restored.amountMinor)
        assertEquals(6_672_004L, restored.exchangeRateScaled)
    }

    @Test
    fun `计数与创建时间随文件一起导出`() {
        val text = BackupCodec.encode(sampleData(), meta())
        val parsed = BackupCodec.decode(text).getOrThrow()
        assertEquals(2, parsed.meta.counts["bills"])
        assertEquals(1, parsed.meta.counts["categories"])
        assertEquals(1_794_744_000_000L, parsed.meta.createdAt)
    }

    @Test
    fun `内容被改动后完整性校验失败`() {
        val text = BackupCodec.encode(sampleData(), meta())
        val tampered = text.replace("12000", "99999")
        assertTrue(tampered != text)

        val error = BackupCodec.decode(tampered).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is BackupFormatException)
        assertEquals(
            "完整性校验失败：备份内容与校验值不一致，文件可能已损坏或被修改",
            error?.message,
        )
    }

    @Test
    fun `不是本应用的文件会被明确拒绝`() {
        val error = BackupCodec.decode("""{"hello":"world"}""").exceptionOrNull()
        assertEquals("这不是 Ikuyo Finance 的备份文件", error?.message)
    }

    @Test
    fun `损坏的 JSON 给出可读提示`() {
        val error = BackupCodec.decode("{not json").exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is BackupFormatException)
        assertEquals("文件内容不是有效的备份 JSON", error?.message)
    }

    @Test
    fun `更高格式版本的备份不会被误读`() {
        val text = BackupCodec.encode(sampleData(), meta())
            .replace("\"formatVersion\":1", "\"formatVersion\":2")
        // 改版本号不校验 checksum（checksum 只覆盖 data），所以能走到版本检查。
        val error = BackupCodec.decode(text).exceptionOrNull()
        assertTrue(
            error?.message?.startsWith("备份的文件格式版本（v2）高于当前应用（v1）") == true,
        )
    }

    @Test
    fun `更高数据版本的备份要求先升级应用`() {
        val text = BackupCodec.encode(sampleData(), meta())
            .replace("\"dataVersion\":9", "\"dataVersion\":10")
        val error = BackupCodec.decode(text).exceptionOrNull()
        assertTrue(
            error?.message?.startsWith("备份的数据版本（v10）高于当前应用（v9）") == true,
        )
    }

    @Test
    fun `结构检查通过时没有任何问题`() {
        val issues = BackupCodec.issuesOf(sampleData())
        assertFalse(issues.isBlocking)
        assertTrue(issues.warnings.isEmpty())
        assertEquals("结构检查通过", issues.summary())
    }

    @Test
    fun `账单挂在不存在的分类上会阻断恢复`() {
        val data = BackupData(
            bills = listOf(bill(1, 1_000L, categoryId = 99L)),
            categories = listOf(category(1, "餐饮")),
        )
        val issues = BackupCodec.issuesOf(data)
        assertTrue(issues.isBlocking)
        assertEquals(listOf("1 笔账单指向不存在的分类"), issues.errors)
    }

    @Test
    fun `重复的账单标识会阻断恢复`() {
        val data = BackupData(
            bills = listOf(bill(1, 1_000L, clientKey = "same"), bill(2, 2_000L, clientKey = "same")),
            categories = listOf(category(1, "餐饮")),
        )
        val issues = BackupCodec.issuesOf(data)
        assertTrue(issues.isBlocking)
        assertEquals(
            "有 1 个重复的账单标识（clientKey），导入会互相覆盖",
            issues.errors.single(),
        )
    }

    @Test
    fun `分期同一期重复付款会阻断恢复`() {
        val installment = InstallmentEntity(
            id = 1,
            name = "手机分期",
            totalMinor = 120_000L,
            periodCount = 12,
            firstPeriodMinor = 10_000L,
            perPeriodMinor = 10_000L,
            dueAt = 1_794_744_000_000L,
            anchorDay = 15,
            paymentMethod = "WECHAT",
            createdAt = 1_794_744_000_000L,
        )
        val payment = InstallmentPaymentEntity(
            installmentId = 1,
            periodIndex = 1,
            amountMinor = 10_000L,
            dueAt = 1_794_744_000_000L,
            paidAt = 1_794_744_000_000L,
        )
        val data = BackupData(
            categories = listOf(category(1, "餐饮")),
            installments = listOf(installment),
            installmentPayments = listOf(payment, payment.copy(id = 2)),
        )
        val issues = BackupCodec.issuesOf(data)
        assertTrue(issues.isBlocking)
        assertEquals(
            "有 1 个分期的同一期存在多条付款记录",
            issues.errors.single(),
        )
    }

    @Test
    fun `空备份会被指出来`() {
        val issues = BackupCodec.issuesOf(BackupData())
        assertTrue(issues.isBlocking)
        assertTrue(issues.errors.contains("备份里没有任何账本数据"))
    }

    @Test
    fun `悬空的退款引用只是提示不阻断`() {
        val data = BackupData(
            bills = listOf(bill(1, 1_000L, type = "REFUND", refundOfBillId = 42L)),
            categories = listOf(category(1, "餐饮")),
        )
        val issues = BackupCodec.issuesOf(data)
        assertFalse(issues.isBlocking)
        assertEquals(listOf("1 笔退款的原始账单不在备份里"), issues.warnings)
        assertTrue(issues.summary().contains("1 条提示"))
    }

    @Test
    fun `checksum 只覆盖数据子树`() {
        val data = sampleData()
        val a = BackupCodec.encode(data, meta())
        val b = BackupCodec.encode(data, meta().copy(appVersion = "9.9.9"))
        val checksumA = Json_parse(a).str("checksum")
        val checksumB = Json_parse(b).str("checksum")
        assertEquals(checksumA, checksumB)
        assertEquals(64, checksumA?.length)
        val changed = BackupCodec.encode(
            sampleData().copy(bills = sampleData().bills + bill(3, 1L)),
            meta(),
        )
        assertTrue(Json_parse(changed).str("checksum") != checksumA)
    }

    private fun Json_parse(text: String) =
        xyz.ikuyo.finance.core.Json.parseOrNull(text) as xyz.ikuyo.finance.core.JsonValue.Obj

    @Test
    fun `空字符串解码失败但不会崩溃`() {
        val error = BackupCodec.decode("").exceptionOrNull()
        assertNotNull(error)
        assertEquals("文件内容不是有效的备份 JSON", error?.message)
    }
}