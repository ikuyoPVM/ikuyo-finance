package xyz.ikuyo.finance.feature.reports

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
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.MonthlyReport
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月报页状态。
 *
 * 设计约束（阶段四计划书第 7 章）：
 * - 报告数字一律来自已落库的报告记录（由 MonthlySummaryBuilder 生成），页面不再现算；
 * - 重新生成会产生新版本，旧版本保留在「历史版本」里，不会被静默覆盖；
 * - 生成是耗时操作（要读全量账单并跑九段统计），全部在 IO 调度器里做。
 */
data class ReportsUiState(
    val reports: List<MonthlyReport> = emptyList(),
    val selected: MonthlyReport? = null,
    val generating: Boolean = false,
    val error: String? = null,
) {
    /** 选中项的所有版本（新到旧），用于「历史版本」。 */
    val versionsOfSelected: List<MonthlyReport>
        get() {
            val current = selected ?: return emptyList()
            return reports.filter { it.yearMonth == current.yearMonth }
        }

    val hasReports: Boolean get() = reports.isNotEmpty()
}

class ReportsViewModel : ViewModel() {

    private val repository = Graph.monthlyReportRepository

    private val generating = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val selectedId = MutableStateFlow<Long?>(null)

    val state: StateFlow<ReportsUiState> =
        combine(repository.observeAll(), generating, error, selectedId) { reports, busy, message, id ->
            val selected = when {
                id != null -> reports.firstOrNull { it.id == id } ?: reports.firstOrNull()
                else -> reports.firstOrNull()
            }
            ReportsUiState(
                reports = reports,
                selected = selected,
                generating = busy,
                error = message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReportsUiState(),
        )

    fun select(id: Long) {
        selectedId.value = id
    }

    /** 生成本月报告（已有则新增一个版本）。 */
    fun generateCurrentMonth() = generate(YearMonth.now(Time.zone))

    /** 生成上个月报告，用于「本月还没结束也先看一眼」的场景。 */
    fun generateLastMonth() = generate(YearMonth.now(Time.zone).minusMonths(1))

    fun generate(yearMonth: YearMonth) {
        if (generating.value) return
        viewModelScope.launch {
            generating.value = true
            error.value = null
            runCatching {
                repository.generate(yearMonth = yearMonth, today = LocalDate.now(Time.zone))
            }.onSuccess { report ->
                // 生成后直接选中新版本，用户能立刻看到内容与版本号。
                selectedId.value = report.id
            }.onFailure { throwable ->
                error.value = throwable.message ?: "生成月报失败"
            }
            generating.value = false
        }
    }

    /** 重新生成当前选中报告所属的月份。 */
    fun regenerateSelected() {
        val month = state.value.selected?.yearMonth ?: return
        val parsed = runCatching { YearMonth.parse(month) }.getOrNull() ?: return
        generate(parsed)
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onFailure { error.value = it.message ?: "删除失败" }
            if (selectedId.value == id) selectedId.value = null
        }
    }

    fun clearError() {
        error.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportsViewModel() }
        }
    }
}