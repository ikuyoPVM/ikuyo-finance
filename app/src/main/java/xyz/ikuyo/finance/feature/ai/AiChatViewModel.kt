package xyz.ikuyo.finance.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.ai.AiBillParser
import xyz.ikuyo.finance.data.ai.AiContextBuilder
import xyz.ikuyo.finance.data.ai.AiException
import xyz.ikuyo.finance.data.ai.ChatMessage
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.AiChatMessage
import xyz.ikuyo.finance.domain.model.BillCandidate
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.BillOperationType
import xyz.ikuyo.finance.domain.model.BillSource
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.ChatRole
import xyz.ikuyo.finance.domain.model.RateSource
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.BudgetPeriods
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator
import java.time.LocalDate
import java.util.UUID

enum class AiMode(val label: String) {
    CHAT("AI 对话"),
    MANUAL("手动记账"),
}

data class AiChatUiState(
    val mode: AiMode = AiMode.CHAT,
    val messages: List<AiChatMessage> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val enabled: Boolean = true,
    val hasKey: Boolean = false,
    val modelLabel: String = "",
    val usingBackup: Boolean = false,
    val threshold: Double = 0.85,
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val lastError: String? = null,
) {
    val canSend: Boolean get() = input.isNotBlank() && !sending
    val showEmptyHint: Boolean get() = messages.isEmpty()
    val pendingCandidates: Int
        get() = messages.sumOf { message -> message.candidates.count { !it.applied } }
}

/**
 * AI 对话与自然语言记账。
 *
 * 三条硬规则在这里落地：
 * 1. 会话与候选账单只是草稿，确认后才经 [AddBillUseCase] 入账；
 * 2. 每张候选卡片带固定 clientKey，重复确认/重试不会重复入账；
 * 3. 请求失败或被停止时保留输入与原文，用户可以原地重试。
 */
class AiChatViewModel : ViewModel() {

    private val aiService = Graph.aiService
    private val settings = Graph.aiSettingsRepository
    private val chatRepository = Graph.chatRepository
    private val categoryRepository = Graph.categoryRepository
    private val tagRepository = Graph.tagRepository
    private val billRepository = Graph.billRepository
    private val budgetRepository = Graph.budgetRepository
    private val addBillUseCase = Graph.addBillUseCase
    private val undoUseCase = Graph.undoUseCase
    private val operationRepository = Graph.operationRepository

    private val parser = AiBillParser()

    private val local = MutableStateFlow(LocalState())
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private var sendJob: Job? = null

    /** 供 UI 侧手动记账表单直接复用：入账后给出可撤销提示。 */
    fun addManualBill(draft: BillDraft): Job = viewModelScope.launch {
        addBillUseCase(draft)
            .onSuccess { emit("已记一笔，可在「操作历史」撤销") }
            .onFailure { emit(it.message ?: "记录失败") }
    }

    val state: StateFlow<AiChatUiState> = combine(
        chatRepository.messages,
        categoryRepository.observeAll(),
        tagRepository.observeAll(),
        settings.config,
        local,
    ) { messages, categories, tags, config, localState ->
        AiChatUiState(
            mode = localState.mode,
            messages = messages,
            input = localState.input,
            sending = localState.sending,
            enabled = config.enabled,
            hasKey = settings.hasApiKey(),
            modelLabel = config.primary.model,
            usingBackup = localState.usingBackup,
            threshold = config.autoPostThreshold,
            categories = categories,
            tags = tags,
            lastError = localState.lastError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiChatUiState())

    fun setMode(mode: AiMode) {
        local.value = local.value.copy(mode = mode)
    }

    fun setInput(text: String) {
        local.value = local.value.copy(input = text, lastError = null)
    }

    fun send(text: String = local.value.input) {
        val content = text.trim()
        if (content.isEmpty() || local.value.sending) return
        viewModelScope.launch {
            val config = settings.config.first()
            if (!config.enabled) {
                emit("AI 已在设置里关闭，可以在「手动记账」里继续记录")
                return@launch
            }
            if (!settings.hasApiKey()) {
                emit("先去「我的 → AI 设置」填写 Base URL、模型与 API Key")
                return@launch
            }

            local.value = local.value.copy(input = "", sending = true, lastError = null)
            val userMessage = AiChatMessage(
                localId = newId(),
                role = ChatRole.USER,
                content = content,
                createdAt = Time.nowMillis(),
            )
            chatRepository.append(userMessage)
            requestReply(config)
        }
    }

    /** 停止生成：只影响本次请求，已落库的账单不受影响。 */
    fun stop() {
        val job = sendJob
        if (job == null || !job.isActive) return
        job.cancel()
        sendJob = null
        viewModelScope.launch {
            local.value = local.value.copy(sending = false)
            chatRepository.append(
                AiChatMessage(
                    localId = newId(),
                    role = ChatRole.ASSISTANT,
                    content = "（已停止生成，可以重新发送或补充条件）",
                    createdAt = Time.nowMillis(),
                    failed = true,
                )
            )
        }
    }

    /** 重试上一条用户提问（复用同一段文本，POST 幂等键不会变）。 */
    fun retry() {
        viewModelScope.launch {
            val lastUser = chatRepository.messages.first()
                .lastOrNull { it.role == ChatRole.USER }
                ?.content
                ?: return@launch
            send(lastUser)
        }
    }

    private suspend fun requestReply(config: xyz.ikuyo.finance.domain.model.AiConfig) {
        val job = viewModelScope.launch {
            val history = chatRepository.messages.first()
                .takeLast(AiBillParser.MAX_CONTEXT_MESSAGES)
                .mapNotNull { message ->
                    when (message.role) {
                        ChatRole.USER -> ChatMessage.user(message.content)
                        ChatRole.ASSISTANT -> if (message.content.isNotBlank() && !message.failed) {
                            ChatMessage.assistant(message.content)
                        } else {
                            null
                        }
                    }
                }
            val categories = categoryRepository.observeAll().first()
            val tags = tagRepository.observeAll().first()
            val summary = buildSummary()
            val systemPrompt = AiContextBuilder.systemPrompt(
                persona = config.persona,
                categories = categories,
                tags = tags,
                summary = summary,
            )

            val result = aiService.reply(history, systemPrompt)
            local.value = local.value.copy(sending = false)
            result.fold(
                onSuccess = { reply ->
                    val parsed = parser.parse(
                        content = reply.content,
                        categories = categories,
                        today = LocalDate.now(Time.zone),
                        // 折算率只认本地记录：模型给的汇率一律不用（计划书 6.4）。
                        rates = Graph.currencyRateRepository.snapshot(),
                    )
                    local.value = local.value.copy(usingBackup = reply.usedBackup)
                    val assistantMessage = AiChatMessage(
                        localId = newId(),
                        role = ChatRole.ASSISTANT,
                        content = parsed.reply.ifBlank { "（模型没有返回文字）" },
                        createdAt = Time.nowMillis(),
                        candidates = parsed.candidates,
                    )
                    chatRepository.append(assistantMessage)
                    autoApply(assistantMessage, config.autoPostThreshold)
                },
                onFailure = { error ->
                    val text = (error as? AiException)?.error?.message
                        ?: error.message
                        ?: "请求失败"
                    local.value = local.value.copy(lastError = text)
                    chatRepository.append(
                        AiChatMessage(
                            localId = newId(),
                            role = ChatRole.ASSISTANT,
                            content = "请求失败：$text",
                            createdAt = Time.nowMillis(),
                            failed = true,
                        )
                    )
                    emit(text)
                },
            )
        }
        sendJob = job
    }

    private suspend fun buildSummary(): AiContextBuilder.FinanceSummary {
        val bills = billRepository.observeActive().first()
        val today = LocalDate.now(Time.zone)
        val stats = FinanceCalculator.homeStats(bills, today)
        val balance = FinanceCalculator.balance(bills)
        val totalBudget = budgetRepository.total()
        val window = totalBudget?.let { BudgetPeriods.windowOf(it, today) }
            ?: BudgetPeriods.window(BudgetPeriodType.MONTHLY, today)
        val status = FinanceCalculator.budgetStatusOf(bills, totalBudget, window, today)
        return AiContextBuilder.FinanceSummary(
            todayExpenseMinor = stats.todayExpenseMinor,
            monthExpenseMinor = stats.monthExpenseMinor,
            monthIncomeMinor = stats.monthIncomeMinor,
            balanceMinor = balance.totalMinor,
            budgetName = totalBudget?.displayName(),
            budgetAmountMinor = status.amountMinor ?: 0L,
            budgetSpentMinor = status.spentMinor,
            budgetRemainingMinor = status.availableMinor,
        )
    }

    /** 置信度达标且无歧义的候选直接入账；其余等用户点确认。 */
    private suspend fun autoApply(message: AiChatMessage, threshold: Double) {
        val auto = message.candidates.filter { !it.needsConfirm(threshold) }
        if (auto.isEmpty()) {
            if (message.candidates.isNotEmpty()) {
                emit("有 ${message.candidates.size} 笔待确认，核对后再入账")
            }
            return
        }
        val applied = auto.count { applyCandidate(it) }
        if (applied > 0) {
            emit("已自动入账 $applied 笔（置信度 ≥ ${(threshold * 100).toInt()}%），可在卡片上撤销")
        }
    }

    fun confirm(candidate: BillCandidate) {
        viewModelScope.launch {
            if (applyCandidate(candidate)) {
                emit("已入账 ${xyz.ikuyo.finance.core.Money.format(candidate.amountMinor, candidate.currency)}")
            } else {
                emit("入账失败，请稍后重试")
            }
        }
    }

    private suspend fun applyCandidate(candidate: BillCandidate): Boolean {
        if (candidate.applied) return true
        val tags = resolveTagIds(candidate.tagNames)
        // 汇率来源要照实记录：本地汇率表里那条是自动/手动/缓存，就写哪个；取不到才回落手动。
        val localRate = if (candidate.currency == Money.BASE_CURRENCY) {
            null
        } else {
            Graph.currencyRateRepository.snapshot()[candidate.currency]
        }
        val draft = BillDraft(
            type = candidate.type,
            amountMinor = candidate.amountMinor,
            currency = candidate.currency,
            exchangeRateScaled = candidate.exchangeRateScaled,
            rateSource = localRate?.source ?: RateSource.MANUAL,
            rateUpdatedAt = localRate?.updatedAt?.takeIf { it > 0L }
                ?: if (candidate.currency == Money.BASE_CURRENCY) 0L else Time.nowMillis(),
            categoryId = candidate.categoryId,
            paymentMethod = candidate.paymentMethod,
            note = candidate.note,
            occurredAt = candidate.occurredAt,
            source = BillSource.AI,
            tagIds = tags,
        )
        val result = addBillUseCase(draft, candidate.clientKey)
        return result.fold(
            onSuccess = { billId ->
                val operationId = operationRepository
                    .observeRecent(6).first()
                    .firstOrNull { it.billId == billId && it.type == BillOperationType.CREATE }
                    ?.id
                markCandidate(candidate.localId) {
                    it.copy(applied = true, appliedBillId = billId, appliedOperationId = operationId)
                }
                true
            },
            onFailure = { error ->
                val duplicate = error.message?.contains("已存在") == true
                if (duplicate) {
                    markCandidate(candidate.localId) { it.copy(applied = true) }
                    true
                } else {
                    false
                }
            },
        )
    }

    /** 撤销刚入账的候选账单：按操作历史精确定位，不会误撤更早的记录。 */
    fun undoCandidate(candidate: BillCandidate) {
        viewModelScope.launch {
            val target = candidate.appliedOperationId
            val result = if (target != null) undoUseCase(target) else undoUseCase()
            result
                .onSuccess {
                    markCandidate(candidate.localId) {
                        it.copy(applied = false, appliedBillId = null, appliedOperationId = null)
                    }
                    emit("已撤销这笔账")
                }
                .onFailure { emit(it.message ?: "撤销失败") }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            chatRepository.clear()
            local.value = local.value.copy(input = "", lastError = null)
            emit("会话已清空，账单与预算统计不受影响")
        }
    }

    fun dismissCandidate(candidate: BillCandidate) {
        viewModelScope.launch {
            markCandidate(candidate.localId) { it.copy(reasons = it.reasons + "已忽略") }
        }
    }

    private suspend fun markCandidate(localId: String, transform: (BillCandidate) -> BillCandidate) {
        val messages = chatRepository.messages.first()
        val updated = messages.map { message ->
            if (message.candidates.none { it.localId == localId }) {
                message
            } else {
                message.copy(
                    candidates = message.candidates.map {
                        if (it.localId == localId) transform(it) else it
                    }
                )
            }
        }
        chatRepository.save(updated)
    }

    private suspend fun resolveTagIds(names: List<String>): List<Long> {
        if (names.isEmpty()) return emptyList()
        val existing = tagRepository.observeAll().first()
        return names.mapNotNull { name ->
            existing.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
        }
    }

    private suspend fun emit(text: String) {
        _events.send(UiEvent.Message(text))
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private data class LocalState(
        val mode: AiMode = AiMode.CHAT,
        val input: String = "",
        val sending: Boolean = false,
        val usingBackup: Boolean = false,
        val lastError: String? = null,
    )

    companion object {
        const val QUICK_EXPENSE = "午饭 28 微信"
        const val QUICK_TODAY = "查一下今天花了多少"
        const val QUICK_MONTH = "这个月还剩多少预算"
        const val QUICK_ANALYZE = "分析一下我这个月的消费结构"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AiChatViewModel() }
        }
    }
}