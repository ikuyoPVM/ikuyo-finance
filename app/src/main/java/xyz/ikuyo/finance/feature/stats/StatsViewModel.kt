package xyz.ikuyo.finance.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.ai.AiError
import xyz.ikuyo.finance.data.ai.AiErrorType
import xyz.ikuyo.finance.data.ai.AiException
import xyz.ikuyo.finance.data.ai.SpendingAnalyst
import xyz.ikuyo.finance.data.ai.SpendingInsight
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.data.repository.AnomalyPreference
import xyz.ikuyo.finance.domain.usecase.AnomalyDetector
import xyz.ikuyo.finance.domain.usecase.SpendingFacts
import xyz.ikuyo.finance.domain.usecase.SpendingForecast
import xyz.ikuyo.finance.domain.usecase.Statistics
import xyz.ikuyo.finance.domain.usecase.StatisticsCache
import java.time.LocalDate

/**
 * AI 消费分析状态。
 *
 * 关键约束：分析结果绑定 [rangeKey]（当前区间的起止毫秒），区间一变就视为过期，
 * 避免把上个月的结论当成这个月的。数字本身始终来自 [Statistics]，这里只保存模型的解释。
 */
data class StatsAnalysisState(
    val running: Boolean = false,
    val insight: SpendingInsight? = null,
    val error: AiError? = null,
    val rangeKey: String = "",
    /** 生成时的本地事实，用来展示数据充分性；不随区间切换而变。 */
    val facts: SpendingFacts? = null,
) {
    val hasResult: Boolean get() = insight != null

    /** 结果属于另一个区间时返回 true（界面提示「区间已变化，请重新分析」）。 */
    fun isStale(currentKey: String): Boolean = insight != null && rangeKey != currentKey
}

/**
 * 统计分析页状态。
 *
 * 所有金额都来自 [Statistics]（本地统计算法），界面只负责展示，
 * 保证图表、排名、明细三处的数字完全一致。
 */
data class StatsUiState(
    val isLoading: Boolean = true,
    val rangeType: Statistics.RangeType = Statistics.RangeType.MONTH,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val snapshot: Statistics.Snapshot? = null,
    val billsInRange: List<Bill> = emptyList(),
    val analysis: StatsAnalysisState = StatsAnalysisState(),
    /** 本月消费预测（本地确定性推算，见 [SpendingForecast]）。 */
    val forecast: SpendingForecast.Forecast? = null,
    /** 异常消费候选项：只提醒、不改账单，已被标记正常或整类忽略的不会出现在这里。 */
    val anomalies: List<AnomalyDetector.Candidate> = emptyList(),
) {
    /** 当前区间：有快照时用快照口径，否则按当前选择实时推导（保证 subtitle 与图表同一口径）。 */
    val range: Statistics.Range
        get() = snapshot?.range ?: Statistics.range(
            rangeType,
            LocalDate.now(Time.zone),
            customStart?.let { Time.startOfDay(it) },
            customEnd?.let { Time.endOfDayInclusive(it) },
        )
    val isEmpty: Boolean get() = !isLoading && snapshot?.hasData == false
    val categories: List<Statistics.CategoryStat> get() = snapshot?.categories.orEmpty()
    val tags: List<Statistics.TagStat> get() = snapshot?.tags.orEmpty()
    val budgets: List<Statistics.BudgetExecution> get() = snapshot?.budgets.orEmpty()
    val daily: List<Statistics.DailyPoint> get() = snapshot?.daily.orEmpty()
}

/** 统计分析页：日 / 周 / 月 / 自定义区间，全部口径来自 [Statistics]。 */
class StatsViewModel : ViewModel() {

    private val billRepository = Graph.billRepository
    private val categoryRepository = Graph.categoryRepository
    private val tagRepository = Graph.tagRepository
    private val budgetRepository = Graph.budgetRepository

    private val rangeType = MutableStateFlow(Statistics.RangeType.MONTH)
    private val customStart = MutableStateFlow<LocalDate?>(null)
    private val customEnd = MutableStateFlow<LocalDate?>(null)
    private val analysisFlow = MutableStateFlow(StatsAnalysisState())

    private data class Sources(
        val bills: List<Bill>,
        val categories: List<Category>,
        val tags: List<Tag>,
        val links: List<BillTagCrossRef>,
    )

    private data class Selection(
        val type: Statistics.RangeType,
        val start: LocalDate?,
        val end: LocalDate?,
    )

    private val sources = combine(
        billRepository.observeActive(),
        categoryRepository.observeAll(),
        tagRepository.observeAll(),
        tagRepository.observeAllLinks(),
    ) { bills, categories, tags, links -> Sources(bills, categories, tags, links) }

    private val selection = combine(rangeType, customStart, customEnd) { type, start, end ->
        Selection(type, start, end)
    }

    val state: StateFlow<StatsUiState> = combine(
        sources,
        budgetRepository.observeAll(),
        selection,
        analysisFlow,
        Graph.anomalyRepository.preference,
    ) { source, budgets, picked, analysis, preference ->
        build(source, budgets, picked, analysis, preference)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    /** 「这是正常消费」：记下这条候选，之后不再提醒（不删除任何账单）。 */
    fun markAnomalyNormal(candidateId: String) {
        viewModelScope.launch { Graph.anomalyRepository.markNormal(candidateId) }
    }

    /** 「忽略这类」：整类提醒都关掉，避免反复打扰。 */
    fun muteAnomalyKind(kind: AnomalyDetector.Kind) {
        viewModelScope.launch { Graph.anomalyRepository.muteKind(kind) }
    }

    /**
     * 生成 AI 消费分析：本地事实先由 [Statistics] 算好，模型只负责解释（计划书第 4 章）。
     * 失败只影响这张卡，不影响统计与外发。
     */
    fun analyze() {
        val snapshot = state.value.snapshot ?: return
        if (analysisFlow.value.running) return
        val facts = SpendingFacts.from(snapshot)
        val key = rangeKeyOf(snapshot.range)
        if (!facts.hasData) {
            analysisFlow.value = StatsAnalysisState(
                running = false,
                insight = null,
                error = AiError(AiErrorType.NOT_CONFIGURED, "这个区间还没有可分析的账单"),
                rangeKey = key,
            )
            return
        }
        analysisFlow.value = StatsAnalysisState(running = true, insight = null, error = null, rangeKey = key, facts = facts)
        viewModelScope.launch {
            val result = SpendingAnalyst(Graph.aiService).analyze(facts)
            val current = analysisFlow.value
            analysisFlow.value = result.fold(
                onSuccess = { current.copy(running = false, insight = it, error = null, rangeKey = key, facts = facts) },
                onFailure = { failure ->
                    val error = (failure as? AiException)?.error
                        ?: AiError(AiErrorType.BAD_RESPONSE, failure.message ?: "分析失败")
                    current.copy(running = false, insight = null, error = error, rangeKey = key, facts = facts)
                },
            )
        }
    }

    fun clearAnalysis() {
        analysisFlow.value = StatsAnalysisState()
    }

    private fun rangeKeyOf(range: Statistics.Range): String =
        "${range.startMillis}-${range.endMillisExclusive}"

    fun setRangeType(type: Statistics.RangeType) {
        rangeType.value = type
    }

    /** 自定义区间；传入的两个日期顺序颠倒时由 [Statistics.range] 自动纠正。 */
    fun setCustomRange(start: LocalDate, end: LocalDate) {
        customStart.value = start
        customEnd.value = end
        rangeType.value = Statistics.RangeType.CUSTOM
    }

    fun clearCustomRange() {
        customStart.value = null
        customEnd.value = null
    }

    private suspend fun build(
        source: Sources,
        budgets: List<Budget>,
        picked: Selection,
        analysis: StatsAnalysisState,
        preference: AnomalyPreference,
    ): StatsUiState {
        val today = LocalDate.now(Time.zone)
        val range = Statistics.range(
            type = picked.type,
            anchor = today,
            customStart = picked.start?.let { Time.startOfDay(it) },
            customEndInclusive = picked.end?.let { Time.endOfDayInclusive(it) },
        )
        val tagBillIds: Map<Long, Set<Long>> = source.links
            .groupBy({ it.tagId }, { it.billId })
            .mapValues { (_, ids) -> ids.toSet() }

        // 聚合、预测、异常检测都是纯本地计算，账单多的时候放到 Default 线程，
        // 不占主线程（计划书第 9 章）；输入没变时直接复用上一次的快照。
        val computed = withContext(Dispatchers.Default) {
            val snapshot = StatisticsCache.snapshot(
                bills = source.bills,
                range = range,
                categories = source.categories,
                tags = source.tags,
                tagBillIds = tagBillIds,
                budgets = budgets,
                today = today,
            )
            Computed(
                snapshot = snapshot,
                billsInRange = Statistics.filterInRange(source.bills, range),
                // 预测固定按「当前自然月」算，与所选区间无关：预算和节奏都是以月为单位的。
                forecast = SpendingForecast.currentMonth(
                    bills = source.bills,
                    today = today,
                    budgets = snapshot.budgets,
                    categories = source.categories,
                ),
                // 异常检测与所选区间无关：看的是「最近 7 天 vs 自己的历史」，区间只影响图表。
                anomalies = AnomalyDetector.detect(
                    bills = source.bills,
                    categories = source.categories,
                    budgets = snapshot.budgets,
                    today = today,
                ).filter { preference.shouldShow(it) },
            )
        }

        return StatsUiState(
            isLoading = false,
            rangeType = picked.type,
            customStart = picked.start,
            customEnd = picked.end,
            snapshot = computed.snapshot,
            billsInRange = computed.billsInRange,
            analysis = analysis,
            forecast = computed.forecast,
            anomalies = computed.anomalies,
        )
    }

    private data class Computed(
        val snapshot: Statistics.Snapshot,
        val billsInRange: List<Bill>,
        val forecast: SpendingForecast.Forecast?,
        val anomalies: List<AnomalyDetector.Candidate>,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel() }
        }
    }
}