package xyz.ikuyo.finance.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.data.backup.BackupCodec
import xyz.ikuyo.finance.data.backup.BackupCrypto
import xyz.ikuyo.finance.data.backup.BackupFormat
import xyz.ikuyo.finance.data.backup.BackupIssues
import xyz.ikuyo.finance.data.backup.ParsedBackup
import xyz.ikuyo.finance.data.repository.BackupRepository
import xyz.ikuyo.finance.data.repository.RestoreReport
import xyz.ikuyo.finance.di.Graph

/** 恢复流程所处的位置，界面按它切换面板。 */
enum class RestoreStage { NONE, PASSWORD, PREVIEW, DONE }

/** 恢复预览要展示的信息，全部来自备份文件本身。 */
data class BackupPreview(
    val billCount: Int,
    val categoryCount: Int,
    val netExpenseMinor: Long,
    val expenseMinor: Long = 0L,
    val incomeMinor: Long = 0L,
    val refundMinor: Long = 0L,
    val createdAt: Long,
    val appVersion: String,
    val dataVersion: Int,
    val encrypted: Boolean,
)

data class BackupUiState(
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val exportText: String? = null,
    val restoreStage: RestoreStage = RestoreStage.NONE,
    val pendingEncryptedText: String? = null,
    val preview: BackupPreview? = null,
    val issues: BackupIssues? = null,
    val report: RestoreReport? = null,
    val integrityMessage: String? = null,
    val integrityClean: Boolean? = null,
)

class BackupViewModel(
    private val repository: BackupRepository,
    private val appVersion: String,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** 只解密用，不落盘、也不写进状态（计划书 §2.3）。 */
    private var prepared: ParsedBackup? = null

    // ------------------------------------------------------------ export

    fun exportBackup(password: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null, error = null) }
        viewModelScope.launch {
            runCatching {
                val plain = repository.exportJson(appVersion)
                if (password.isNullOrBlank()) plain else BackupCrypto.encrypt(plain, password)
            }.onSuccess { text ->
                _state.update {
                    it.copy(busy = false, exportText = text, notice = "备份已生成，请选择保存位置")
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(busy = false, error = error.message ?: "备份生成失败")
                }
            }
        }
    }

    fun consumeExport() = _state.update { it.copy(exportText = null, notice = "备份已保存") }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ----------------------------------------------------------- restore

    /** 选中文件后先判断是否需要密码，再决定下一步。 */
    fun loadFile(text: String) {
        _state.update { it.copy(error = null, notice = null) }
        if (BackupCrypto.isEncrypted(text)) {
            _state.update {
                it.copy(
                    restoreStage = RestoreStage.PASSWORD,
                    pendingEncryptedText = text,
                    preview = null,
                    issues = null,
                    report = null,
                )
            }
            return
        }
        acceptPlain(text, encrypted = false)
    }

    fun submitPassword(password: String) {
        val text = _state.value.pendingEncryptedText ?: return
        _state.update { it.copy(busy = true, error = null) }
        val result = BackupCrypto.decrypt(text, password)
        result.onSuccess { plain -> acceptPlain(plain, encrypted = true) }
            .onFailure { error ->
                _state.update {
                    it.copy(busy = false, error = error.message ?: "备份解密失败")
                }
            }
    }

    private fun acceptPlain(text: String, encrypted: Boolean) {
        BackupCodec.decode(text)
            .onSuccess { parsed ->
                prepared = parsed
                _state.update {
                    it.copy(
                        busy = false,
                        restoreStage = RestoreStage.PREVIEW,
                        pendingEncryptedText = null,
                        preview = BackupPreview(
                            billCount = parsed.data.bills.size,
                            categoryCount = parsed.data.categories.size,
                            netExpenseMinor = parsed.data.bills.sumOf { bill -> bill.cnyAmountMinor },
                            expenseMinor = parsed.data.bills
                                .filter { it.type == "EXPENSE" }
                                .sumOf { it.cnyAmountMinor },
                            incomeMinor = parsed.data.bills
                                .filter { it.type == "INCOME" }
                                .sumOf { it.cnyAmountMinor },
                            refundMinor = parsed.data.bills
                                .filter { it.type == "REFUND" }
                                .sumOf { it.cnyAmountMinor },
                            createdAt = parsed.meta.createdAt,
                            appVersion = parsed.meta.appVersion,
                            dataVersion = parsed.meta.dataVersion,
                            encrypted = encrypted,
                        ),
                        issues = BackupCodec.issuesOf(parsed.data),
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(busy = false, error = error.message ?: "备份文件无法识别")
                }
            }
    }

    fun cancelRestore() {
        prepared = null
        _state.update {
            it.copy(
                restoreStage = RestoreStage.NONE,
                pendingEncryptedText = null,
                preview = null,
                issues = null,
                report = null,
                busy = false,
            )
        }
    }

    /** 用户确认后才会真的写库，失败时库里的数据保持原样。 */
    fun confirmRestore() {
        val parsed = prepared ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            repository.restore(parsed)
                .onSuccess { report ->
                    prepared = null
                    _state.update {
                        it.copy(
                            busy = false,
                            restoreStage = RestoreStage.DONE,
                            report = report,
                            preview = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(busy = false, error = error.message ?: "恢复失败")
                    }
                }
        }
    }

    // --------------------------------------------------------- integrity

    /** 本地数据完整性检查：把当前账本导一遍再校验一次。 */
    fun checkIntegrity() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, integrityMessage = null) }
        viewModelScope.launch {
            runCatching {
                val text = repository.exportJson(appVersion)
                BackupCodec.decode(text).getOrThrow()
            }.onSuccess { parsed ->
                val issues = BackupCodec.issuesOf(parsed.data)
                _state.update {
                    it.copy(
                        busy = false,
                        integrityClean = !issues.isBlocking,
                        integrityMessage = if (issues.isBlocking) {
                            "发现 ${issues.errors.size} 个问题：" + issues.errors.joinToString("；")
                        } else {
                            "本地数据检查通过，共 ${parsed.data.bills.size} 笔账单、${parsed.data.categories.size} 个分类"
                        },
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        busy = false,
                        integrityClean = false,
                        error = error.message ?: "完整性检查失败",
                    )
                }
            }
        }
    }

    /** 清空数据同样走备份仓储的事务路径，保证要么全清、要么不动。 */
    fun clearAllData() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            repository.wipe()
                .onSuccess {
                    _state.update {
                        it.copy(busy = false, notice = "本地数据已清空（默认分类已重建）", integrityMessage = null)
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message ?: "清空失败") }
                }
        }
    }

    /** 界面层的输入校验也走同一条错误通道，避免两套提示。 */
    fun reportError(message: String) = _state.update { it.copy(error = message, busy = false) }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BackupViewModel(Graph.backupRepository, Graph.appVersion) as T
        }
    }
}