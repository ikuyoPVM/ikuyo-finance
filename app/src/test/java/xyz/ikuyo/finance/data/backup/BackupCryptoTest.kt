package xyz.ikuyo.finance.data.backup

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.CategoryEntity

/**
 * 备份加密（计划书 §2.3）：标准 KDF + AES-GCM，口令不落盘，
 * 失败要能区分「密码错误」和「文件损坏」。
 */
class BackupCryptoTest {

    private val iterations = 1_000 // 单测用低轮数，避免拖慢构建

    private fun plainBackup(): String {
        val data = BackupData(
            bills = listOf(
                BillEntity(
                    id = 1,
                    type = "EXPENSE",
                    amountMinor = 12_000L,
                    cnyAmountMinor = 12_000L,
                    categoryId = 1L,
                    paymentMethod = "WECHAT",
                    note = "午餐",
                    occurredAt = 1_794_744_000_000L,
                    createdAt = 1_794_744_000_000L,
                    clientKey = "k1",
                )
            ),
            categories = listOf(
                CategoryEntity(id = 1, name = "餐饮", emoji = "🍜", isDefault = false, sortOrder = 1)
            ),
        )
        return BackupCodec.encode(data, BackupMeta(appVersion = "0.6.0", createdAt = 1L))
    }

    @Test
    fun `加密后能用同一密码解出原文`() {
        val plain = plainBackup()
        val envelope = BackupCrypto.encrypt(plain, "correct-horse", iterations)

        assertTrue(BackupCrypto.isEncrypted(envelope))
        assertEquals(plain, BackupCrypto.decrypt(envelope, "correct-horse").getOrThrow())
    }

    @Test
    fun `加密备份仍然可以被完整解析`() {
        val envelope = BackupCrypto.encrypt(plainBackup(), "pw-123", iterations)
        val text = BackupCrypto.decrypt(envelope, "pw-123").getOrThrow()

        val parsed = BackupCodec.decode(text).getOrThrow()
        assertEquals(1, parsed.data.bills.size)
        assertEquals(12_000L, parsed.data.bills.single().amountMinor)
    }

    @Test
    fun `密码错误时明确说是密码问题`() {
        val envelope = BackupCrypto.encrypt(plainBackup(), "right", iterations)
        val error = BackupCrypto.decrypt(envelope, "wrong").exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is BackupCryptoException)
        assertEquals("备份密码不正确", error?.message)
    }

    @Test
    fun `密文被改坏时明确说是文件损坏`() {
        val envelope = BackupCrypto.encrypt(plainBackup(), "right", iterations)
        val tampered = flipCipherByte(envelope)

        val error = BackupCrypto.decrypt(tampered, "right").exceptionOrNull()
        assertNotNull(error)
        assertEquals("备份文件已损坏，解密失败，请换一个备份文件", error?.message)
    }

    @Test
    fun `没有设密码的备份不需要输入密码`() {
        val error = BackupCrypto.decrypt(plainBackup(), "anything").exceptionOrNull()
        assertEquals("这个备份没有设置密码，不需要输入密码", error?.message)
    }

    @Test
    fun `同一份明文两次加密结果不同`() {
        val plain = plainBackup()
        val a = BackupCrypto.encrypt(plain, "pw", iterations)
        val b = BackupCrypto.encrypt(plain, "pw", iterations)

        assertNotEquals(a, b) // 随机盐与 IV
        assertEquals(plain, BackupCrypto.decrypt(a, "pw").getOrThrow())
        assertEquals(plain, BackupCrypto.decrypt(b, "pw").getOrThrow())
    }

    @Test
    fun `信封里没有明文账单内容`() {
        val envelope = BackupCrypto.encrypt(plainBackup(), "pw", iterations)
        assertFalse(envelope.contains("餐饮"))
        assertFalse(envelope.contains("午餐"))
        assertFalse(envelope.contains("clientKey"))
    }

    @Test
    fun `空密码不会被接受`() {
        val error = runCatching { BackupCrypto.encrypt(plainBackup(), "", iterations) }.exceptionOrNull()
        assertEquals("备份密码不能为空", error?.message)
    }

    @Test
    fun `明文文件不会被误判为加密备份`() {
        assertFalse(BackupCrypto.isEncrypted(plainBackup()))
        assertTrue(BackupCrypto.isEncrypted(BackupCrypto.encrypt("{}", "pw", iterations)))
    }

    @Test
    fun `信封缺字段时报文件损坏而不是崩溃`() {
        val envelope = BackupCrypto.encrypt(plainBackup(), "pw", iterations)
        val obj = Json.parseOrNull(envelope) as JsonValue.Obj
        val stripped = Json.dumps(
            Json.obj(
                "magic" to Json.str(BackupFormat.MAGIC),
                "encrypted" to Json.bool(true),
                "kdf" to Json.str(envelopeValue(obj, "kdf")),
                "iterations" to Json.num(obj.long("iterations") ?: iterations.toLong()),
                "cipher" to Json.str(envelopeValue(obj, "cipher")),
                "salt" to Json.str(envelopeValue(obj, "salt")),
                "iv" to Json.str(envelopeValue(obj, "iv")),
                "keyCheck" to Json.str(envelopeValue(obj, "keyCheck")),
            )
        )
        val error = BackupCrypto.decrypt(stripped, "pw").exceptionOrNull()
        assertEquals("备份文件缺少密文，可能已损坏", error?.message)
    }

    // -------------------------------------------------------------- helpers

    private fun envelopeValue(obj: JsonValue.Obj, key: String): String =
        obj.str(key) ?: obj.long(key)?.toString() ?: ""

    /** 翻转密文的最后一个字节，模拟文件传输损坏。 */
    private fun flipCipherByte(envelope: String): String {
        val obj = Json.parseOrNull(envelope) as JsonValue.Obj
        val bytes = Base64.getDecoder().decode(obj.str("data").orEmpty())
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val rebuilt = Json.obj(
            "magic" to Json.str(BackupFormat.MAGIC),
            "encrypted" to Json.bool(true),
            "kdf" to Json.str(obj.str("kdf") ?: ""),
            "iterations" to Json.num(obj.long("iterations") ?: iterations.toLong()),
            "keyBits" to Json.num(obj.long("keyBits") ?: 256L),
            "cipher" to Json.str(obj.str("cipher") ?: ""),
            "salt" to Json.str(obj.str("salt") ?: ""),
            "iv" to Json.str(obj.str("iv") ?: ""),
            "keyCheck" to Json.str(obj.str("keyCheck") ?: ""),
            "data" to Json.str(Base64.getEncoder().encodeToString(bytes)),
        )
        return Json.dumps(rebuilt)
    }
}