package xyz.ikuyo.finance.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue

/**
 * 备份文件加密（计划书 §2.3）。
 *
 * 只用标准原语，不自造算法：
 * - 口令派生：PBKDF2WithHmacSHA256（随机 16 字节盐，默认 120000 次）
 * - 对称加密：AES-256-GCM（随机 12 字节 IV，128 位认证标签）
 * - 口令校验：HMAC-SHA256(key, "IKUYO-BACKUP:KEYCHECK")
 *
 * 为什么额外存 keyCheck：GCM 认证失败只说明「密文和密钥对不上」，
 * 分不清是密码打错还是文件被改坏。用派生密钥对固定串做一次 HMAC，
 * 解密失败时再比对它，就能给出「密码错误」或「文件损坏」两种不同提示。
 *
 * 备份密码只作为入参参与派生，从不写进文件、也不写进设置。
 */
class BackupCryptoException(message: String) : IllegalArgumentException(message)

object BackupCrypto {

    const val KDF = "PBKDF2WithHmacSHA256"
    const val CIPHER = "AES/GCM/NoPadding"
    const val DEFAULT_ITERATIONS = 120_000
    const val KEY_BITS = 256

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_CHECK_MESSAGE = "IKUYO-BACKUP:KEYCHECK"

    private val random = SecureRandom()

    /** 判断一段文本是不是「本应用的加密备份信封」（不看里面能不能解开）。 */
    fun isEncrypted(text: String): Boolean {
        val root = Json.parseOrNull(text) as? JsonValue.Obj ?: return false
        return root.bool("encrypted") == true && root.str("data") != null
    }

    /** 把明文备份文本加密成一个可保存的信封 JSON。 */
    fun encrypt(
        plainText: String,
        password: String,
        iterations: Int = DEFAULT_ITERATIONS,
    ): String {
        if (password.isEmpty()) throw BackupCryptoException("备份密码不能为空")
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(password, salt, iterations)

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val envelope = Json.obj(
            "magic" to Json.str(BackupFormat.MAGIC),
            "encrypted" to Json.bool(true),
            "kdf" to Json.str(KDF),
            "iterations" to Json.num(iterations.toLong()),
            "keyBits" to Json.num(KEY_BITS.toLong()),
            "cipher" to Json.str(CIPHER),
            "salt" to Json.str(b64(salt)),
            "iv" to Json.str(b64(iv)),
            "keyCheck" to Json.str(keyCheck(key)),
            "data" to Json.str(b64(cipherText)),
        )
        return Json.dumps(envelope)
    }

    /**
     * 解开信封，返回明文备份文本。
     *
     * 失败时抛出的异常消息可以直接给用户看，并且区分三种情况：
     * 不是加密备份 / 密码错误 / 文件损坏。
     */
    fun decrypt(text: String, password: String): Result<String> = runCatching {
        val root = Json.parseOrNull(text) as? JsonValue.Obj
            ?: throw BackupFormatException("文件内容不是有效的备份 JSON")
        if (root.bool("encrypted") != true) {
            throw BackupFormatException("这个备份没有设置密码，不需要输入密码")
        }
        val kdf = root.str("kdf")
        if (kdf != KDF) throw BackupFormatException("备份使用了当前版本不支持的加密方式（$kdf）")
        val iterations = root.int("iterations") ?: DEFAULT_ITERATIONS
        val salt = root.str("salt")?.let { unb64(it) }
            ?: throw BackupFormatException("备份文件缺少盐值，可能已损坏")
        val iv = root.str("iv")?.let { unb64(it) }
            ?: throw BackupFormatException("备份文件缺少加密参数，可能已损坏")
        val cipherText = root.str("data")?.let { unb64(it) }
            ?: throw BackupFormatException("备份文件缺少密文，可能已损坏")
        val expectedCheck = root.str("keyCheck")

        val key = deriveKey(password, salt, iterations)
        val checkMatches = expectedCheck != null && expectedCheck.equals(keyCheck(key), ignoreCase = true)

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val plain = try {
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // 认证失败：口令校验值能对上就说明密钥没错，问题出在密文本身。
            throw if (checkMatches) {
                BackupFormatException("备份文件已损坏，解密失败，请换一个备份文件")
            } else {
                BackupCryptoException("备份密码不正确")
            }
        }
        if (!checkMatches) {
            // 极少数情况：GCM 解开了但口令校验值不一致，说明文件被换过密钥参数。
            throw BackupFormatException("备份文件的加密参数与内容不一致，文件可能已损坏")
        }
        plain
    }

    // --------------------------------------------------------------- crypto

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(KDF)
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun keyCheck(key: SecretKey): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return hex(mac.doFinal(KEY_CHECK_MESSAGE.toByteArray(Charsets.UTF_8)))
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}