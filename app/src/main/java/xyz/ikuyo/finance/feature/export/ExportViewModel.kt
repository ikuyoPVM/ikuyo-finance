package xyz.ikuyo.finance.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.export.CsvExporter
import xyz.ikuyo.finance.data.export.ExcelExporter
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.Statistics
import java.time.LocalDate

/**
 * 导出范围（时间维度）。
 *
 * 导出只做筛选与文本生成，不写库：账单、预算、操作历史的数字全部来自既有仓储。
 */
enum class ExportRangeType(val label: String) {
    ALL("全部时间"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    CUSTOM("自定义"),
}

/** 导出内容：一次导出一种，保证每个文件都是合法且语义单一的 CSV。 */
enum class ExportContent(val label: String, val suffix: String) {
    BILLS("账单明细", "bills"),
    BUDGETS("预算执行", "budgets"),
    OPERATIONS("操作历史", "operations"),
}

/**
 * 导出预览。
 *
 * count 是「应用全部筛选后的行数」，sample 是前两行数据的首字段摘要，
 * 让用户在保存前就能确认范围和列结构是否正确。
 */
data class ExportPreview(
    val count: Int = 0,
    val columns: List<String> = emptyList(),
    val sample: List<String> = emptyList(),
)

data class ExportUiState(
    val rangeType: ExportRangeType = ExportRangeType.THIS_MONTH,
    val customStart: Long? = null,
    val customEndInclusive: Long? = null,
    val types: Set<BillType> = emptySet(),
    val selectedCategoryIds: Set<Long> = emptySet(),
    val selectedTagIds: Set<Long> = emptySet(),
    val includeDeleted: Boolean = false,
    val content: ExportContent = ExportContent.BILLS,
    val preview: ExportPreview = ExportPreview(),
    val rangeLabel: String = "",
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val hasCustomRange: Boolean get() = rangeType == ExportRangeType.CUSTOM
}

/**
 * 导出页 ViewModel。
 *
 * 口径：
 * - 账单筛选先按时间范围，再按类型/分类/标签，软删除账单只有在「包含已删除」时才进入结果；
 * - 预算执行走 [Statistics.budgetExecutions]，与统计页完全同一份实现，不另算一套；
 * - 操作历史来自操作仓储，条数上限 [OPERATION_LIMIT]。
 */
class ExportViewModel(
    private val billFlow: Flow<List<Bill>>,
    private val trashFlow: Flow<List<Bill>>,
    private val categoryFlow: Flow<List<Category>>,
    private val tagFlow: Flow<List<Tag>>,
    private val linkFlow: Flow<List<BillTagCrossRef>>,
    private val budgetFlow: Flow<List<Budget>>,
    private val operationFlow: Flow<List<BillOperation>>,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var activeBills: List<Bill> = emptyList()
    private var trashBills: List<Bill> = emptyList()
    private var categoryList: List<Category> = emptyList()
    private var tagList: List<Tag> = emptyList()
    private var linkList: List<BillTagCrossRef> = emptyList()
    private var budgetList: List<Budget> = emptyList()
    private var operationList: List<BillOperation> = emptyList()

    init {
        viewModelScope.launch {
            combine(
                combine(billFlow, trashFlow) { active: List<Bill>, trash: List<Bill> -> active to trash },
                combine(categoryFlow, tagFlow) { cats: List<Category>, tagRows: List<Tag> -> cats to tagRows },
                combine(linkFlow, budgetFlow) { linkRows: List<BillTagCrossRef>, budgetRows: List<Budget> ->
                    linkRows to budgetRows
                },
                operationFlow,
            ) { billPair: Pair<List<Bill>, List<Bill>>,
                tagPair: Pair<List<Category>, List<Tag>>,
                budgetPair: Pair<List<BillTagCrossRef>, List<Budget>>,
                ops: List<BillOperation> ->
                activeBills = billPair.first
                trashBills = billPair.second
                categoryList = tagPair.first
                tagList = tagPair.second
                linkList = budgetPair.first
                budgetList = budgetPair.second
                operationList = ops
                refresh()
            }.collect { }
        }
    }

    var categories: List<Category> = emptyList()
        private set
    var tags: List<Tag> = emptyList()
        private set

    fun setRangeType(type: ExportRangeType) {
        _state.value = _state.value.copy(rangeType = type, error = null)
        refresh()
    }

    fun setCustomRange(start: Long, endInclusive: Long) {
        _state.value = _state.value.copy(
            rangeType = ExportRangeType.CUSTOM,
            customStart = minOf(start, endInclusive),
            customEndInclusive = maxOf(start, endInclusive),
        )
        refresh()
    }

    fun toggleType(type: BillType) {
        val current = _state.value.types
        val next = if (type in current) current - type else current + type
        _state.value = _state.value.copy(types = next)
        refresh()
    }

    fun toggleCategory(categoryId: Long) {
        val current = _state.value.selectedCategoryIds
        val next = if (categoryId in current) current - categoryId else current + categoryId
        _state.value = _state.value.copy(selectedCategoryIds = next)
        refresh()
    }

    fun toggleTag(tagId: Long) {
        val current = _state.value.selectedTagIds
        val next = if (tagId in current) current - tagId else current + tagId
        _state.value = _state.value.copy(selectedTagIds = next)
        refresh()
    }

    fun setIncludeDeleted(value: Boolean) {
        _state.value = _state.value.copy(includeDeleted = value)
        refresh()
    }

    fun setContent(content: ExportContent) {
        _state.value = _state.value.copy(content = content, error = null)
        refresh()
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    /** 建议文件名：ikuyo-bills-20261115-1213.csv，与备份命名规则保持一致。 */
    fun suggestedFileName(): String {
        val now = Time.toLocalDateTime(Time.nowMillis())
        val stamp = String.format(
            "%04d%02d%02d-%02d%02d",
            now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute,
        )
        return "ikuyo-${_state.value.content.suffix}-$stamp.csv"
    }

    /** 生成当前筛选条件下的 CSV 文本；在 IO 线程执行，失败写 error。 */
    fun buildCsv(onReady: (String) -> Unit) {
        val snapshot = _state.value
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { render(snapshot) }
            }
            _state.value = _state.value.copy(busy = false)
            result.onSuccess { text ->
                if (text.isEmpty()) {
                    _state.value = _state.value.copy(error = "当前条件下没有可导出的数据")
                } else {
                    onReady(text)
                }
            }.onFailure { failure ->
                _state.value = _state.value.copy(error = failure.message ?: "导出失败")
            }
        }
    }

    /** Excel 报告建议文件名：ikuyo-report-20261115-1213.xlsx。 */
    fun suggestedExcelName(): String {
        val now = Time.toLocalDateTime(Time.nowMillis())
        val stamp = String.format(
            "%04d%02d%02d-%02d%02d",
            now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute,
        )
        return "ikuyo-report-$stamp.xlsx"
    }

    /**
     * 生成四个工作表的 Excel 报告：账单明细、分类统计、预算执行、操作历史。
     *
     * 数字全部来自本地统计服务与当前筛选的账单，不掺任何 AI 推断；
     * 渲染放在 IO 线程，完成后把字节交回界面写文件。
     */
    fun buildWorkbook(onReady: (ByteArray) -> Unit) {
        val snapshot = _state.value
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { ExcelExporter.workbook(sheets(snapshot)) }
            }
            _state.value = _state.value.copy(busy = false)
            result.onSuccess { bytes -> onReady(bytes) }
                .onFailure { failure ->
                    _state.value = _state.value.copy(error = failure.message ?: "导出 Excel 失败")
                }
        }
    }

    private fun sheets(state: ExportUiState): List<ExcelExporter.Sheet> {
        val bills = filteredBills(state)
        val tagNames = tagNamesByBill()
        val deletedIds = trashBills.map { it.id }.toSet()
        val statistics = Statistics.snapshot(
            bills = activeBills,
            range = statisticsRange(state),
            categories = categoryList,
            tags = tagList,
            tagBillIds = tagIdsByBill(),
            budgets = budgetList,
        )

        val billSheet = ExcelExporter.Sheet(
            name = "账单明细",
            header = listOf(
                "账单ID", "日期", "时间", "类型", "原币金额", "币种", "汇率", "本币金额",
                "分类", "标签", "支付方式", "备注", "来源", "退款对应账单ID", "创建时间", "是否已删除",
            ),
            rows = bills.map { bill ->
                listOf(
                    ExcelExporter.Cell.Number(bill.id.toDouble()),
                    ExcelExporter.Cell.Text(ExcelExporter.date(bill.occurredAt)),
                    ExcelExporter.Cell.Text(
                        Time.toLocalDateTime(bill.occurredAt)
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                    ),
                    ExcelExporter.Cell.Text(bill.type.label),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(bill.amountMinor)),
                    ExcelExporter.Cell.Text(bill.currency),
                    ExcelExporter.Cell.Number(Money.rateFromScaled(bill.exchangeRateScaled).toDouble()),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(bill.cnyAmountMinor)),
                    ExcelExporter.Cell.Text(bill.categoryName ?: "未分类"),
                    ExcelExporter.Cell.Text(tagNames[bill.id]?.joinToString("、").orEmpty()),
                    ExcelExporter.Cell.Text(bill.paymentMethod.label),
                    ExcelExporter.Cell.Text(bill.note),
                    ExcelExporter.Cell.Text(bill.source.label),
                    bill.refundOfBillId?.let { ExcelExporter.Cell.Number(it.toDouble()) }
                        ?: ExcelExporter.Cell.Empty,
                    ExcelExporter.Cell.Text(ExcelExporter.dateTime(bill.createdAt)),
                    ExcelExporter.Cell.Text(if (bill.id in deletedIds) "是" else "否"),
                )
            },
        )

        val categorySheet = ExcelExporter.Sheet(
            name = "分类统计",
            header = listOf("统计区间", "分类", "笔数", "净支出", "占比"),
            rows = statistics.categories.map { stat ->
                listOf(
                    ExcelExporter.Cell.Text(statistics.range.label),
                    ExcelExporter.Cell.Text(stat.name),
                    ExcelExporter.Cell.Number(stat.billCount.toDouble()),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(stat.netExpenseMinor)),
                    ExcelExporter.Cell.Percent(ExcelExporter.ratio(stat.shareBp)),
                )
            } + listOf(
                listOf(
                    ExcelExporter.Cell.Text(statistics.range.label),
                    ExcelExporter.Cell.Text("合计"),
                    ExcelExporter.Cell.Number(statistics.totals.billCount.toDouble()),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(statistics.totals.netExpenseMinor)),
                    ExcelExporter.Cell.Percent(1.0),
                ),
            ),
        )

        val budgetSheet = ExcelExporter.Sheet(
            name = "预算执行",
            header = listOf("预算", "周期", "额度", "已用", "剩余", "使用率", "超支"),
            rows = statistics.budgets.map { execution ->
                listOf(
                    ExcelExporter.Cell.Text(execution.name),
                    ExcelExporter.Cell.Text(execution.windowLabel),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(execution.amountMinor)),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(execution.spentMinor)),
                    // availableMinor 是「额度 + 结转」，剩余要再减掉已用，否则表里会显示成额度本身。
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(execution.availableMinor - execution.spentMinor)),
                    ExcelExporter.Cell.Percent(execution.usedPercent / 100.0),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(execution.overMinor)),
                )
            },
        )

        val operationSheet = ExcelExporter.Sheet(
            name = "操作历史",
            header = listOf("操作ID", "账单ID", "操作类型", "说明", "金额", "是否已撤销", "操作时间"),
            rows = operationList.map { operation ->
                listOf(
                    ExcelExporter.Cell.Number(operation.id.toDouble()),
                    ExcelExporter.Cell.Number(operation.billId.toDouble()),
                    ExcelExporter.Cell.Text(operation.type.label),
                    ExcelExporter.Cell.Text(operation.summary),
                    ExcelExporter.Cell.Number(ExcelExporter.yuan(operation.amountMinor)),
                    ExcelExporter.Cell.Text(if (operation.undone) "是" else "否"),
                    ExcelExporter.Cell.Text(ExcelExporter.dateTime(operation.operatedAt)),
                )
            },
        )

        return listOf(billSheet, categorySheet, budgetSheet, operationSheet)
    }

    /** Excel 的统计区间：有自定义范围用自定义，否则按当前选择的日/周/月类型取含今天的区间。 */
    private fun statisticsRange(state: ExportUiState): Statistics.Range {
        val custom = state.customStart
        val customEnd = state.customEndInclusive
        if (custom != null && customEnd != null) {
            return Statistics.range(Statistics.RangeType.CUSTOM, customStart = custom, customEndInclusive = customEnd)
        }
        return when (state.rangeType) {
            ExportRangeType.ALL -> Statistics.range(Statistics.RangeType.MONTH)
            ExportRangeType.THIS_MONTH -> Statistics.range(Statistics.RangeType.MONTH)
            ExportRangeType.LAST_MONTH -> {
                val last = LocalDate.now(Time.zone).minusMonths(1)
                Statistics.range(Statistics.RangeType.MONTH, anchor = last)
            }
            ExportRangeType.CUSTOM -> Statistics.range(Statistics.RangeType.MONTH)
        }
    }

    private fun render(state: ExportUiState): String = when (state.content) {
        ExportContent.BILLS -> CsvExporter.bills(
            bills = filteredBills(state),
            tagNames = tagNamesByBill(),
            deletedIds = trashBills.map { it.id }.toSet(),
        )
        ExportContent.BUDGETS -> CsvExporter.budgets(
            Statistics.budgetExecutions(
                bills = activeBills,
                budgets = budgetList,
                tagBillIds = tagIdsByBill(),
                categoryNames = categoryList.associate { it.id to it.name },
                tagNames = tagList.associate { it.id to it.name },
            ),
        )
        ExportContent.OPERATIONS -> CsvExporter.operations(operationList)
    }

    private fun filteredBills(state: ExportUiState): List<Bill> {
        val pool = if (state.includeDeleted) activeBills + trashBills else activeBills
        val range = rangeOf(state)
        val tagFilter = state.selectedTagIds
        return pool
            .filter { bill ->
                if (range == null) true else bill.occurredAt in range
            }
            .filter { bill -> state.types.isEmpty() || bill.type in state.types }
            .filter { bill -> state.selectedCategoryIds.isEmpty() || bill.categoryId in state.selectedCategoryIds }
            .filter { bill ->
                if (tagFilter.isEmpty()) {
                    true
                } else {
                    linkList.any { it.billId == bill.id && it.tagId in tagFilter }
                }
            }
            .sortedByDescending { it.occurredAt }
    }

    private fun rangeOf(state: ExportUiState): LongRange? = when (state.rangeType) {
        ExportRangeType.ALL -> null
        ExportRangeType.THIS_MONTH -> {
            val month = LocalDate.now(Time.zone).let { java.time.YearMonth.of(it.year, it.monthValue) }
            Time.startOfMonth(month) until Time.endOfMonthExclusive(month)
        }
        ExportRangeType.LAST_MONTH -> {
            val month = java.time.YearMonth.from(java.time.YearMonth.now(Time.zone).minusMonths(1))
            Time.startOfMonth(month) until Time.endOfMonthExclusive(month)
        }
        ExportRangeType.CUSTOM -> {
            val start = state.customStart ?: return null
            val end = state.customEndInclusive ?: return null
            start..end
        }
    }

    private fun tagIdsByBill(): Map<Long, Set<Long>> =
        linkList.groupBy({ it.billId }, { it.tagId }).mapValues { it.value.toSet() }

    private fun tagNamesByBill(): Map<Long, List<String>> {
        val nameOf = tagList.associate { it.id to it.name }
        return linkList
            .groupBy { it.billId }
            .mapValues { (_, rows) -> rows.mapNotNull { nameOf[it.tagId] } }
    }

    /** 重算预览：条数、列名与前两行样例，全部来自与导出同一条筛选路径。 */
    private fun refresh() {
        val state = _state.value
        val filtered = filteredBills(state)
        val preview = when (state.content) {
            ExportContent.BILLS -> ExportPreview(
                count = filtered.size,
                columns = CsvExporter.BILL_COLUMNS,
                sample = filtered.take(2).map { bill ->
                    Time.toLocalDate(bill.occurredAt).toString() + " · " +
                        bill.type.label + " · " + Money.format(bill.cnyAmountMinor)
                },
            )
            ExportContent.BUDGETS -> {
                val rows = Statistics.budgetExecutions(
                    bills = activeBills,
                    budgets = budgetList,
                    tagBillIds = tagIdsByBill(),
                    categoryNames = categoryList.associate { it.id to it.name },
                    tagNames = tagList.associate { it.id to it.name },
                )
                ExportPreview(
                    count = rows.size,
                    columns = CsvExporter.BUDGET_COLUMNS,
                    sample = rows.take(2).map { row ->
                        row.name + " · 已用 " + Money.format(row.spentMinor) + " / " + Money.format(row.amountMinor)
                    },
                )
            }
            ExportContent.OPERATIONS -> ExportPreview(
                count = operationList.size,
                columns = CsvExporter.OPERATION_COLUMNS,
                sample = operationList.take(2).map { row ->
                    row.type.label + " · " + row.summary
                },
            )
        }
        categories = categoryList
        tags = tagList
        _state.value = _state.value.copy(
            preview = preview,
            rangeLabel = rangeLabelOf(state),
        )
    }

    private fun rangeLabelOf(state: ExportUiState): String = when (state.rangeType) {
        ExportRangeType.ALL -> "全部时间"
        ExportRangeType.THIS_MONTH -> "本月 · " + Time.formatMonth(java.time.YearMonth.now(Time.zone))
        ExportRangeType.LAST_MONTH -> "上月 · " + Time.formatMonth(java.time.YearMonth.now(Time.zone).minusMonths(1))
        ExportRangeType.CUSTOM -> {
            val start = state.customStart
            val end = state.customEndInclusive
            if (start == null || end == null) {
                "自定义（未选择日期）"
            } else {
                Time.toLocalDate(start).toString() + " 至 " + Time.toLocalDate(end).toString()
            }
        }
    }

    companion object {
        /** 操作历史上限：导出是取证用途，够用即可，避免一次拉全表。 */
        const val OPERATION_LIMIT = 2000

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExportViewModel(
                billFlow = Graph.billRepository.observeActive(),
                trashFlow = Graph.billRepository.observeTrash(),
                categoryFlow = Graph.categoryRepository.observeAll(),
                tagFlow = Graph.tagRepository.observeAll(),
                linkFlow = Graph.tagRepository.observeAllLinks(),
                budgetFlow = Graph.budgetRepository.observeAll(),
                operationFlow = Graph.operationRepository.observeRecent(OPERATION_LIMIT),
            ) as T
        }
    }
}