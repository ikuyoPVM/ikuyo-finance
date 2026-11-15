package xyz.ikuyo.finance.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.domain.model.AiConfig
import xyz.ikuyo.finance.domain.model.AiEndpoint

private val Context.aiConfigStore: DataStore<Preferences> by preferencesDataStore(name = "ikuyo_ai_config")

/**
 * AI 配置仓库。只保存非敏感参数（Base URL、模型、超时、人设…），API Key 的
 * 密文与读取全部交给 [ApiKeyStore]。
 */
class AiSettingsRepository(private val context: Context) {

    private val keyStore = ApiKeyStore(context)

    val config: Flow<AiConfig> = context.aiConfigStore.data.map { AiConfigCodec.decode(it[CONFIG_JSON]) }

    suspend fun update(transform: (AiConfig) -> AiConfig) {
        context.aiConfigStore.edit { prefs ->
            val current = AiConfigCodec.decode(prefs[CONFIG_JSON])
            prefs[CONFIG_JSON] = AiConfigCodec.encode(transform(current))
        }
    }

    suspend fun setPrimary(baseUrl: String, model: String): Result<Unit> =
        AiUrls.normalizeBaseUrl(baseUrl).map { normalized ->
            update { it.copy(primary = AiEndpoint(normalized, model.trim())) }
        }

    suspend fun setBackup(baseUrl: String?, model: String?): Result<Unit> {
        val raw = baseUrl?.trim().orEmpty()
        if (raw.isEmpty()) {
            update { it.copy(backup = null) }
            return Result.success(Unit)
        }
        return AiUrls.normalizeBaseUrl(raw).map { normalized ->
            update { it.copy(backup = AiEndpoint(normalized, model?.trim().orEmpty())) }
        }
    }

    suspend fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    suspend fun setTimeoutSeconds(seconds: Int) = update {
        it.copy(
            timeoutSeconds = seconds.coerceIn(AiConfig.MIN_TIMEOUT_SECONDS, AiConfig.MAX_TIMEOUT_SECONDS)
        )
    }

    suspend fun setAutoPostThreshold(value: Double) = update {
        it.copy(
            autoPostThreshold = value.coerceIn(AiConfig.MIN_THRESHOLD, AiConfig.MAX_THRESHOLD)
        )
    }

    suspend fun setPersona(persona: xyz.ikuyo.finance.domain.model.AiPersona) = update { it.copy(persona = persona) }

    suspend fun resetPersona() = update { it.copy(persona = xyz.ikuyo.finance.domain.model.AiPersona()) }

    // ---- API Key：只有密文，读写都经过 Keystore ----

    fun saveApiKey(apiKey: String): Result<Unit> = keyStore.save(apiKey)

    fun clearApiKey() = keyStore.clear()

    fun hasApiKey(): Boolean = keyStore.hasKey()

    fun maskedApiKey(): String? = keyStore.maskedKey()

    fun apiKeyDecryptFailed(): Boolean = keyStore.lastDecryptFailed()

    /** 只给网络层用的明文；不缓存、不落日志。 */
    internal fun apiKey(): String? = keyStore.load()

    private companion object {
        val CONFIG_JSON = stringPreferencesKey("config_json")
    }
}