package xyz.ikuyo.finance.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.domain.model.AiChatMessage
import xyz.ikuyo.finance.domain.model.BillCandidate
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.ChatRole
import xyz.ikuyo.finance.domain.model.PaymentMethod

private val Context.aiChatStore: DataStore<Preferences> by preferencesDataStore(name = "ikuyo_ai_chat")

/**
 * 会话仓库：只保存对话文本与候选账单状态（含幂等键），
 * 不含密钥、不含完整账本。清空会话不影响任何账单数据。 */
class ChatRepository(private val context: Context) {

    val messages: Flow<List<AiChatMessage>> =
        context.aiChatStore.data.map { ChatCodec.decode(it[CHAT_JSON]) }

    suspend fun save(messages: List<AiChatMessage>) {
        val trimmed = messages.takeLast(MAX_MESSAGES)
        context.aiChatStore.edit { it[CHAT_JSON] = ChatCodec.encode(trimmed) }
    }

    suspend fun append(message: AiChatMessage) {
        val current = readOnce()
        save(current + message)
    }

    suspend fun replace(message: AiChatMessage) {
        val current = readOnce()
        save(current.map { if (it.localId == message.localId) message else it })
    }

    suspend fun clear() {
        context.aiChatStore.edit { it.remove(CHAT_JSON) }
    }

    private suspend fun readOnce(): List<AiChatMessage> =
        ChatCodec.decode(context.aiChatStore.data.first()[CHAT_JSON])

    companion object {
        const val MAX_MESSAGES = 60
        private val CHAT_JSON = stringPreferencesKey("chat_json")
    }
}

/** 会话 JSON 编解码（手写，保持与 [Json] 一致的最小实现）。*/
internal object ChatCodec {

    fun encode(messages: List<AiChatMessage>): String =
        Json.dumps(Json.obj("messages" to Json.arr(messages.map(::encodeMessage))))

    fun decode(raw: String?): List<AiChatMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = Json.parseOrNull(raw) as? JsonValue.Obj ?: return emptyList()
        val arr = root.arr("messages") ?: return emptyList()
        return arr.items.mapNotNull { decodeMessage(it as? JsonValue.Obj ?: return@mapNotNull null) }
    }

    private fun encodeMessage(message: AiChatMessage): JsonValue = Json.obj(
        "id" to Json.str(message.localId),
        "role" to Json.str(message.role.dbValue),
        "content" to Json.str(message.content),
        "at" to Json.num(message.createdAt),
        "failed" to Json.bool(message.failed),
        "candidates" to Json.arr(message.candidates.map(::encodeCandidate)),
    )

    private fun decodeMessage(obj: JsonValue.Obj): AiChatMessage = AiChatMessage(
        localId = obj.str("id").orEmpty(),
        role = ChatRole.fromDb(obj.str("role").orEmpty()),
        content = obj.str("content").orEmpty(),
        createdAt = obj.int("at")?.toLong() ?: 0L,
        failed = obj.bool("failed") ?: false,
        candidates = obj.arr("candidates")?.items?.mapNotNull {
            decodeCandidate(it as? JsonValue.Obj ?: return@mapNotNull null)
        }.orEmpty(),
    )

    private fun encodeCandidate(candidate: BillCandidate): JsonValue = Json.obj(
        "id" to Json.str(candidate.localId),
        "clientKey" to Json.str(candidate.clientKey),
        "type" to Json.str(candidate.type.dbValue),
        "amount" to Json.num(candidate.amountMinor),
        "currency" to Json.str(candidate.currency),
        "rate" to Json.num(candidate.exchangeRateScaled),
        "categoryId" to (candidate.categoryId?.let { Json.num(it) } ?: JsonValue.Null),
        "categoryName" to (candidate.categoryName?.let { Json.str(it) } ?: JsonValue.Null),
        "at" to Json.num(candidate.occurredAt),
        "payment" to Json.str(candidate.paymentMethod.dbValue),
        "note" to Json.str(candidate.note),
        "tags" to Json.arr(candidate.tagNames.map { Json.str(it) }),
        "confidence" to Json.num(candidate.confidence),
        "reasons" to Json.arr(candidate.reasons.map { Json.str(it) }),
        "applied" to Json.bool(candidate.applied),
        "billId" to (candidate.appliedBillId?.let { Json.num(it) } ?: JsonValue.Null),
        "operationId" to (candidate.appliedOperationId?.let { Json.num(it) } ?: JsonValue.Null),
    )

    private fun decodeCandidate(obj: JsonValue.Obj): BillCandidate = BillCandidate(
        localId = obj.str("id").orEmpty(),
        clientKey = obj.str("clientKey") ?: obj.str("id").orEmpty(),
        type = BillType.fromDb(obj.str("type").orEmpty()),
        amountMinor = obj.long("amount") ?: 0L,
        currency = obj.str("currency") ?: "CNY",
        exchangeRateScaled = obj.long("rate") ?: 1_000_000L,
        categoryId = obj.long("categoryId"),
        categoryName = obj.str("categoryName"),
        occurredAt = obj.long("at") ?: 0L,
        paymentMethod = PaymentMethod.fromDb(obj.str("payment").orEmpty()),
        note = obj.str("note").orEmpty(),
        tagNames = obj.arr("tags")?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty(),
        confidence = obj.num("confidence") ?: 0.0,
        reasons = obj.arr("reasons")?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty(),
        applied = obj.bool("applied") ?: false,
        appliedBillId = obj.long("billId"),
        appliedOperationId = obj.long("operationId"),
    )
}