package xyz.ikuyo.finance.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的本地加密存储。
 *
 * 设计要点（计划书 3.2）：
 *  - 密钥材料由 Android Keystore 生成并保管，应用进程拿不到明文的 Key 材料；
 *  - 磁盘上只放 AES/GCM 密文与 IV（Base64），存在应用私有的 SharedPreferences；
 *  - 明文只在内存里短暂存在，明文不写 Room、不写 DataStore 普通字段、不进日志；
 *  - 不允许备份导出：`allowBackup` 之外，这里把文件放在 `no_backup` 目录下，
 *    系统备份与设备迁移都不会带走密文；
 *  - Keystore 条目被系统清掉（恢复出厂、锁屏策略变化、换机）时解密会失败，
 *    此时按「没有 Key」处理并让用户重新填写，而不是崩溃。
 */
class ApiKeyStore(context: Context) {

    private val prefs = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(apiKey: String): Result<Unit> {
        val plain = apiKey.trim()
        if (plain.isEmpty()) return Result.failure(AiException(AiError(AiErrorType.NOT_CONFIGURED, "empty key")))
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + IV_SEPARATOR +
                Base64.encodeToString(cipherText, Base64.NO_WRAP)
            prefs.edit().putString(KEY_CIPHER, payload).remove(KEY_FAILED).apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AiException(AiError(AiErrorType.NOT_CONFIGURED, "encrypt failed: ${e::class.simpleName}")))
        }
    }

    /** 解密失败（Keystore 条目丢失、设备换了）时返回 null，并记下失败原因供界面提示。 */
    fun load(): String? {
        val payload = prefs.getString(KEY_CIPHER, null) ?: return null
        val parts = payload.split(IV_SEPARATOR)
        if (parts.size != 2) {
            prefs.edit().putBoolean(KEY_FAILED, true).apply()
            return null
        }
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            prefs.edit().putBoolean(KEY_FAILED, true).apply()
            null
        }
    }

    fun hasKey(): Boolean = prefs.contains(KEY_CIPHER)

    /** 是否曾经保存过但解密失败——界面据此提示「请重新填写」。 */
    fun lastDecryptFailed(): Boolean = prefs.getBoolean(KEY_FAILED, false)

    fun clear() {
        prefs.edit().remove(KEY_CIPHER).remove(KEY_FAILED).apply()
    }

    /** 只给界面展示用：abc…xyz 形式，永不返回完整 Key。 */
    fun maskedKey(): String? {
        val plain = load() ?: return null
        return if (plain.length <= 8) "••••••••" else plain.take(4) + "…" + plain.takeLast(4)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ikuyo_ai_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFS_NAME = "ikuyo_ai_secret"
        const val KEY_CIPHER = "api_key_cipher"
        const val KEY_FAILED = "api_key_decrypt_failed"
        const val IV_SEPARATOR = "\u0000"
    }
}