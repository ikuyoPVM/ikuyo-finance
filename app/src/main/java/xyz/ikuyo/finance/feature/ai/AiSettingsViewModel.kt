package xyz.ikuyo.finance.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.NoticeKind
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.ai.ModelListOutcome
import xyz.ikuyo.finance.data.ai.ModelOption
import xyz.ikuyo.finance.data.ai.ProbeException
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.AiConfig
import xyz.ikuyo.finance.domain.model.AiPersona

/** 密钥状态（Key 的明文永远不进入 UI 状态）。 */
data class KeyStatus(
    val hasKey: Boolean = false,
    val masked: String? = null,
    val decryptFailed: Boolean = false,
)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val model: String, val usedBackup: Boolean, val latencyMillis: Long) : TestState
    data class Failure(val message: String) : TestState
}

data class AiSettingsUiState(
    val config: AiConfig = AiConfig(),
    val key: KeyStatus = KeyStatus(),
    val test: TestState = TestState.Idle,
    val models: ModelListState = ModelListState.Idle,
)

/**
 * 模型列表状态。[Success.fetchedFor] 记住这份列表是按哪个 Base URL 取的，
 * 换了地址就视为失效，不会把旧服务商的模型当新配置的有效选项。
 */
sealed interface ModelListState {
    data object Idle : ModelListState
    data object Loading : ModelListState
    data class Success(val models: List<ModelOption>, val fetchedFor: String) : ModelListState
    data class Failure(val message: String) : ModelListState
}

class AiSettingsViewModel : ViewModel() {

    private val settings = Graph.aiSettingsRepository
    private val aiService = Graph.aiService

    private val keyStatus = MutableStateFlow(readKey())
    private val testState = MutableStateFlow<TestState>(TestState.Idle)
    private val modelList = MutableStateFlow<ModelListState>(ModelListState.Idle)

    val state: StateFlow<AiSettingsUiState> = combine(
        settings.config,
        keyStatus,
        testState,
        modelList,
    ) { config, key, test, models ->
        AiSettingsUiState(config, key, test, models)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    private fun readKey() = KeyStatus(
        hasKey = settings.hasApiKey(),
        masked = settings.maskedApiKey(),
        decryptFailed = settings.apiKeyDecryptFailed(),
    )

    fun savePrimary(baseUrl: String, model: String) = launch("已保存主模型配置") {
        settings.setPrimary(baseUrl, model).getOrThrow()
        testState.value = TestState.Idle
    }

    fun saveBackup(baseUrl: String, model: String) = launch("已保存备用模型配置") {
        settings.setBackup(baseUrl.ifBlank { null }, model.ifBlank { null }).getOrThrow()
    }

    fun clearBackup() = launch("已清除备用模型") { settings.setBackup(null, null).getOrThrow() }

    fun saveApiKey(text: String) = launch("密钥已加密保存") {
        settings.saveApiKey(text).let { result ->
            if (result.isFailure) throw result.exceptionOrNull() ?: IllegalStateException("保存失败")
        }
        keyStatus.value = readKey()
    }

    fun clearApiKey() = launch("已删除本机密钥") {
        settings.clearApiKey()
        keyStatus.value = readKey()
        testState.value = TestState.Idle
    }

    fun setEnabled(enabled: Boolean) = launch(null) { settings.setEnabled(enabled) }

    fun setTimeoutSeconds(seconds: Int) = launch(null) { settings.setTimeoutSeconds(seconds) }

    fun setAutoPostThreshold(value: Double) = launch(null) { settings.setAutoPostThreshold(value) }

    fun setPersona(persona: AiPersona) = launch(null) { settings.setPersona(persona) }

    fun resetPersona() = launch("人设已恢复默认") { settings.resetPersona() }

    fun testConnection() {
        if (testState.value == TestState.Running) return
        testState.value = TestState.Running
        viewModelScope.launch {
            val result = aiService.probe()
            testState.value = result.fold(
                onSuccess = { TestState.Success(it.model, it.usedBackup, it.latencyMillis) },
                onFailure = { error ->
                    val message = (error as? ProbeException)?.reason?.userText ?: "测试失败"
                    TestState.Failure(message)
                },
            )
        }
    }

    /** 拉取模型列表；失败不清空任何已填配置，只把原因显示出来。 */
    fun loadModels(baseUrl: String) {
        if (modelList.value == ModelListState.Loading) return
        modelList.value = ModelListState.Loading
        viewModelScope.launch {
            val target = baseUrl.trim()
            modelList.value = when (val outcome = aiService.fetchModels(target)) {
                is ModelListOutcome.Success -> ModelListState.Success(outcome.models, fetchedFor(target))
                is ModelListOutcome.Failure -> ModelListState.Failure(outcome.reason.userText)
            }
        }
    }

    fun refreshModels(baseUrl: String) {
        modelList.value = ModelListState.Idle
        loadModels(baseUrl)
    }

    /** 列表与当前 Base URL 绑定；换了地址就必须重新获取，避免用旧服务商的模型。 */
    private fun fetchedFor(baseUrl: String): String = baseUrl.trim().trimEnd('/').lowercase()

    /** 已选模型是否仍在最近一次成功获取的列表里；列表未加载时不判定为异常。 */
    fun isModelMissing(model: String, baseUrl: String): Boolean {
        val state = modelList.value as? ModelListState.Success ?: return false
        if (state.fetchedFor != fetchedFor(baseUrl)) return false
        return model.isNotBlank() && state.models.none { it.id == model.trim() }
    }

    private fun launch(successMessage: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { successMessage?.let { emit(it) } }
                .onFailure { emit(it.message ?: "操作失败", NoticeKind.Error) }
        }
    }

    private suspend fun emit(text: String, kind: NoticeKind = NoticeKind.Success) {
        _events.send(UiEvent.Message(text, kind))
    }

    private val _events = kotlinx.coroutines.channels.Channel<UiEvent>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<UiEvent> = _events.receiveAsFlow()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AiSettingsViewModel() }
        }
    }
}