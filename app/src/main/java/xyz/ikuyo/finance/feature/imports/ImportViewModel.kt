package xyz.ikuyo.finance.feature.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.ikuyo.finance.data.import.CsvImporter
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.usecase.AddBillUseCase
import java.math.BigDecimal

/** 导入结果，界面只展示这里给出的数字，不自行统计。 */
data class ImportResult(
    val requested: Int,
    val succeeded: Int,
    val failed: Int,
    val duplicates: Int,
    val conflicts: Int,
    val invalid: Int,
    val failures: List<String> = emptyList(),
) {
    val message: String
        get() = buildString {
            append("已导入 ").append(succeeded).append(" 笔")
            if (failed > 0) append("，失败 ").append(failed).append(" 笔")
            append("；跳过重复 ").append(duplicates).append(" 笔")
            if (conflicts > 0) append("，其中 ").append(conflicts).append(" 笔按未分类或修正后的值导入")
            if (invalid > 0) append("，无效 ").append(invalid).append(" 笔未导入")
        }
}

data class ImportUiState(
    val fileName: String? = null,
    val busy: Boolean = false,
    val plan: CsvImporter.Plan? = null,
    val result: ImportResult? = null,
    val error: String? = null,
    val notice: String? = null,
    val showConfirm: Boolean = false,
) {
    val canImport: Boolean get() = !busy && (plan?.importCount ?: 0) > 0 && result == null
}

class ImportViewModel(
    private val billFlow: Flow<List<Bill>>,
    private val trashFlow: Flow<List<Bill>>,
    private val categoryFlow: Flow<List<Category>>,
    private val rateFlow: Flow<Map<String, CurrencyRate>>,
    private val addBillUseCase: AddBillUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** 选中文件后立刻解析，但绝不写库：写库必须经过预览与确认。 */
    fun loadFile(text: String, fileName: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null, result = null, fileName = fileName) }
        viewModelScope.launch {
            runCatching {
                val existing = billFlow.first() + trashFlow.first()
                val categories = categoryFlow.first()
                val rates = rateFlow.first().mapValues { (_, rate) -> rate.rate }
                withContext(Dispatchers.Default) {
                    CsvImporter.plan(text = text, existing = existing, categories = categories, rates = rates)
                }
            }.onSuccess { plan ->
                _state.update {
                    it.copy(
                        busy = false,
                        plan = plan,
                        error = plan.error,
                        notice = if (plan.error == null && plan.hasData) {
                            "已解析 ${plan.rows.size} 行，其中可导入 ${plan.importCount} 行。"
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, error = error.message ?: "读取文件失败") }
            }
        }
    }

    fun askConfirm() {
        if (!_state.value.canImport) return
        _state.update { it.copy(showConfirm = true) }
    }

    fun dismissConfirm() = _state.update { it.copy(showConfirm = false) }

    /** 用户确认后才逐条写入；写库在 IO 线程，clientKey 保证重复点击不会重复入账。 */
    fun confirmImport() {
        val plan = _state.value.plan ?: return
        if (_state.value.busy) return
        val rows = plan.rows.filter { it.importable }
        _state.update { it.copy(busy = true, showConfirm = false, error = null, notice = null) }
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            var succeeded = 0
            withContext(Dispatchers.IO) {
                rows.forEach { row ->
                    val draft = BillDraft(
                        type = row.type,
                        amountMinor = row.amountMinor,
                        currency = row.currency,
                        exchangeRateScaled = row.rateScaled,
                        categoryId = row.categoryId,
                        paymentMethod = row.paymentMethod,
                        note = row.note,
                        occurredAt = row.occurredAt,
                    )
                    val outcome = addBillUseCase(draft, clientKey = clientKeyOf(row))
                    if (outcome.isSuccess) {
                        succeeded++
                    } else {
                        failures += "第 ${row.lineNumber} 行：${outcome.exceptionOrNull()?.message ?: "写入失败"}"
                    }
                }
            }
            val result = ImportResult(
                requested = rows.size,
                succeeded = succeeded,
                failed = rows.size - succeeded,
                duplicates = plan.duplicateCount,
                conflicts = plan.conflictCount,
                invalid = plan.invalidCount,
                failures = failures.take(5),
            )
            _state.update {
                it.copy(busy = false, result = result, notice = result.message, plan = null)
            }
        }
    }

    fun reset() = _state.update {
        ImportUiState()
    }

    private fun clientKeyOf(row: CsvImporter.Row): String {
        val base = "${row.occurredAt}|${row.amountMinor}|${row.type.dbValue}|${row.categoryId ?: 0L}|${row.note.trim()}"
        return "import:" + base.hashCode().toString(16) + ":" + base.length
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(
                billFlow = Graph.billRepository.observeActive(),
                trashFlow = Graph.billRepository.observeTrash(),
                categoryFlow = Graph.categoryRepository.observeAll(),
                rateFlow = Graph.currencyRateRepository.rates,
                addBillUseCase = Graph.addBillUseCase,
            ) as T
        }
    }
}