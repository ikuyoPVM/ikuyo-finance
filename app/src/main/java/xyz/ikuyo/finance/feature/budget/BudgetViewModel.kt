package xyz.ikuyo.finance.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.NoticeKind
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetChange
import xyz.ikuyo.finance.domain.model.BudgetPeriodType
import xyz.ikuyo.finance.domain.model.BudgetScope
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.BudgetTemplate
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.BudgetPeriods
import xyz.ikuyo.finance.domain.usecase.FinanceCalculator
import java.time.LocalDate

data class CategoryBudgetRow(
    val category: Category,
    val amountMinor: Long?,
    val spentMinor: Long,
    val alertLevel: BudgetAlertLevel = BudgetAlertLevel.NORMAL,
    val enabled: Boolean = true,
) {
    val progress: Float
        get() {
            val amount = amountMinor ?: return 0f
            if (amount <= 0L) return 0f
            return (spentMinor.toFloat() / amount.toFloat()).coerceIn(0f, 1f)
        }

    val isOverBudget: Boolean
        get() = amountMinor != null && spentMinor > amountMinor
}

/** 标签预算：预算金额挂在某个标签上，统计口径为该标签下账单的净支出。 */
data class TagBudgetRow(
    val tag: Tag,
    val amountMinor: Long?,
    val spentMinor: Long,
    val alertLevel: BudgetAlertLevel = BudgetAlertLevel.NORMAL,
    val enabled: Boolean = true,
) {
    val progress: Float
        get() {
            val amount = amountMinor ?: return 0f
            if (amount <= 0L) return 0f
            return (spentMinor.toFloat() / amount.toFloat()).coerceIn(0f, 1f)
        }
}

/** 专项预算 / 固定支出预算：独立名称、独立周期，可暂停。 */
data class SpecialBudgetRow(
    val budget: Budget,
    val status: BudgetStatus,
    val windowLabel: String,
)

data class BudgetUiState(
    val isLoading: Boolean = true,
    val periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    val carryOver: Boolean = false,
    val customStart: Long? = null,
    val customEnd: Long? = null,
    val windowLabel: String = "",
    val total: BudgetStatus = BudgetStatus(),
    val totalBudgetMinor: Long? = null,
    val totalEnabled: Boolean = true,
    val totalNote: String? = null,
    val categories: List<CategoryBudgetRow> = emptyList(),
    val tagBudgets: List<TagBudgetRow> = emptyList(),
    val specials: List<SpecialBudgetRow> = emptyList(),
    val templates: List<BudgetTemplate> = emptyList(),
    val changes: List<BudgetChange> = emptyList(),
    val availableTags: List<Tag> = emptyList(),
) {
    val hasTotalBudget: Boolean get() = totalBudgetMinor != null
    val tagBudgetsWithoutBudget: List<Tag>
        get() {
            val configured = tagBudgets.map { it.tag.id }.toSet()
            return availableTags.filter { it.id !in configured }
        }
}

class BudgetViewModel : ViewModel() {

    private val billRepository = Graph.billRepository
    private val budgetRepository = Graph.budgetRepository
    private val categoryRepository = Graph.categoryRepository
    private val tagRepository = Graph.tagRepository
    private val templateRepository = Graph.budgetTemplateRepository

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** 未保存的草稿：用户改了周期/结转但还没点保存。 */
    private val draftPeriod = MutableStateFlow<BudgetPeriodType?>(null)
    private val draftCarryOver = MutableStateFlow<Boolean?>(null)
    private val draftCustomStart = MutableStateFlow<Long?>(null)
    private val draftCustomEnd = MutableStateFlow<Long?>(null)

    private data class Drafts(
        val period: BudgetPeriodType?,
        val carryOver: Boolean?,
        val customStart: Long?,
        val customEnd: Long?,
    )

    private data class Extras(
        val tags: List<Tag>,
        val tagLinks: List<Long>,
        val templates: List<BudgetTemplate>,
    )

    private val base = combine(
        billRepository.observeActive(),
        budgetRepository.observeAll(),
        categoryRepository.observeAll(),
    ) { bills, budgets, categories -> Triple(bills, budgets, categories) }

    private val drafts = combine(
        draftPeriod,
        draftCarryOver,
        draftCustomStart,
        draftCustomEnd,
    ) { period, carryOver, start, end -> Drafts(period, carryOver, start, end) }

    /** 只取标签 id 列表用于「哪些标签还没有预算」，标签与账单的关联单独在 TagBudgetRow 内计算。 */
    private val extras = combine(
        tagRepository.observeAll(),
        tagRepository.observeAllLinks(),
        templateRepository.observeAll(),
    ) { tags, links, templates ->
        Extras(tags, links.map { it.billId }, templates)
    }

    private val billLinks = tagRepository.observeAllLinks()

    val state: StateFlow<BudgetUiState> = combine(
        base,
        drafts,
        budgetRepository.observeRecentChanges(12),
        extras,
        billLinks,
    ) { (bills, budgets, categories), draft, changes, extra, links ->
        buildState(bills, budgets, categories, draft, changes, extra, links)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    private fun buildState(
        bills: List<Bill>,
        budgets: List<Budget>,
        categories: List<Category>,
        draft: Drafts,
        changes: List<BudgetChange>,
        extras: Extras,
        links: List<xyz.ikuyo.finance.data.local.entity.BillTagCrossRef>,
    ): BudgetUiState {
        val today = LocalDate.now(Time.zone)
        val totalRule = budgets.firstOrNull { it.scope == BudgetScope.TOTAL }
        val periodType = draft.period ?: totalRule?.periodType ?: BudgetPeriodType.MONTHLY
        val carryOver = draft.carryOver ?: totalRule?.carryOver ?: false
        val customStart = draft.customStart ?: totalRule?.customStart
        val customEnd = draft.customEnd ?: totalRule?.customEnd

        val window = BudgetPeriods.window(periodType, today, customStart, customEnd)
        val label = BudgetPeriods.label(periodType, window)

        // 用草稿值构建「生效规则」，让用户在保存前就能看到结转/周期变化后的结果。
        val effectiveRule = totalRule?.copy(
            periodType = periodType,
            carryOver = carryOver,
            customStart = customStart,
            customEnd = customEnd,
        )
        val totalStatus = if (effectiveRule != null) {
            FinanceCalculator.budgetStatusOf(bills, effectiveRule, window, today)
        } else {
            FinanceCalculator.budgetStatus(
                bills = bills,
                budgetAmountMinor = null,
                periodStart = window.start,
                periodEnd = window.endInclusive,
                today = today,
                windowLabel = label,
            )
        }

        val categoryRows = categories.map { category ->
            val rule = budgets.firstOrNull {
                it.scope == BudgetScope.CATEGORY && it.categoryId == category.id
            }
            val spent = FinanceCalculator.categorySpent(
                bills = bills,
                categoryId = category.id,
                periodStart = window.start,
                periodEnd = window.endInclusive,
            )
            CategoryBudgetRow(
                category = category,
                amountMinor = rule?.amountMinor,
                spentMinor = spent,
                alertLevel = if (rule?.enabled == false) {
                    BudgetAlertLevel.NORMAL
                } else {
                    rule?.amountMinor?.let { BudgetAlertLevel.of(it, spent) }
                        ?: BudgetAlertLevel.NORMAL
                },
                enabled = rule?.enabled ?: true,
            )
        }.sortedWith(
            compareByDescending<CategoryBudgetRow> { it.amountMinor != null }
                .thenBy { it.category.sortOrder }
        )

        val billsByTag = links.groupBy({ it.tagId }, { it.billId })
        val tagRows = extras.tags.mapNotNull { tag ->
            val rule = budgets.firstOrNull { it.scope == BudgetScope.TAG && it.tagId == tag.id }
                ?: return@mapNotNull null
            val ids = billsByTag[tag.id]?.toSet().orEmpty()
            val status = FinanceCalculator.budgetStatusOf(
                bills = bills,
                budget = rule,
                window = window,
                today = today,
                tagBillIds = ids,
            )
            TagBudgetRow(
                tag = tag,
                amountMinor = rule.amountMinor,
                spentMinor = status.spentMinor,
                alertLevel = if (rule.enabled) status.alertLevel else BudgetAlertLevel.NORMAL,
                enabled = rule.enabled,
            )
        }

        val specialRows = budgets
            .filter { it.scope == BudgetScope.SPECIAL }
            .sortedByDescending { it.createdAt }
            .map { rule ->
                val ruleWindow = BudgetPeriods.windowOf(rule, today)
                val status = FinanceCalculator.budgetStatusOf(bills, rule, ruleWindow, today)
                SpecialBudgetRow(
                    budget = rule,
                    status = status,
                    windowLabel = BudgetPeriods.label(rule.periodType, ruleWindow),
                )
            }

        return BudgetUiState(
            isLoading = false,
            periodType = periodType,
            carryOver = carryOver,
            customStart = customStart,
            customEnd = customEnd,
            windowLabel = label,
            total = totalStatus,
            totalBudgetMinor = totalRule?.amountMinor,
            totalEnabled = totalRule?.enabled ?: true,
            totalNote = totalRule?.note,
            categories = categoryRows,
            tagBudgets = tagRows,
            specials = specialRows,
            templates = extras.templates,
            changes = changes,
            availableTags = extras.tags,
        )
    }

    fun selectPeriod(type: BudgetPeriodType) {
        draftPeriod.value = type
    }

    fun setCarryOver(enabled: Boolean) {
        draftCarryOver.value = enabled
    }

    fun setCustomRange(startMillis: Long, endMillis: Long) {
        draftCustomStart.value = minOf(startMillis, endMillis)
        draftCustomEnd.value = maxOf(startMillis, endMillis)
        draftPeriod.value = BudgetPeriodType.CUSTOM
    }

    fun saveTotal(input: String) {
        val current = state.value
        val trimmed = input.trim()
        val amount = Money.parseToMinor(trimmed, Money.BASE_CURRENCY)
        if (trimmed.isEmpty() || amount == null || amount <= 0L) {
            emit("请输入有效的预算金额")
            return
        }
        viewModelScope.launch {
            budgetRepository.setTotal(
                amountMinor = amount,
                periodType = current.periodType,
                carryOver = current.carryOver,
                customStart = current.customStart,
                customEnd = current.customEnd,
            )
            emit("${current.periodType.label}预算已更新为 ${Money.format(amount)}")
        }
    }

    fun setTotalEnabled(enabled: Boolean) {
        val rule = Graph.budgetRepository
        viewModelScope.launch {
            val total = rule.total() ?: return@launch
            rule.setEnabled(total.id, enabled)
            emit(if (enabled) "总预算已启用" else "总预算已暂停，暂停期间不再提醒")
        }
    }

    fun clearTotal() {
        viewModelScope.launch {
            budgetRepository.clearTotal()
            draftPeriod.value = null
            draftCarryOver.value = null
            draftCustomStart.value = null
            draftCustomEnd.value = null
            emit("已删除总预算")
        }
    }

    fun saveCategoryBudget(categoryId: Long, input: String) {
        val current = state.value
        val category = current.categories.firstOrNull { it.category.id == categoryId }?.category
        val trimmed = input.trim()
        if (trimmed.isNotEmpty()) {
            val amount = Money.parseToMinor(trimmed, Money.BASE_CURRENCY)
            if (amount == null || amount <= 0L) {
                emit("请输入有效的预算金额")
                return
            }
            viewModelScope.launch {
                budgetRepository.setCategoryBudget(
                    categoryId = categoryId,
                    categoryName = category?.name,
                    amountMinor = amount,
                    periodType = current.periodType,
                )
                emit("分类预算已保存")
            }
            return
        }
        viewModelScope.launch {
            budgetRepository.setCategoryBudget(
                categoryId = categoryId,
                categoryName = category?.name,
                amountMinor = null,
                periodType = current.periodType,
            )
            emit("已清除该分类预算")
        }
    }

    fun saveTagBudget(tagId: Long, input: String) {
        val current = state.value
        val tag = current.availableTags.firstOrNull { it.id == tagId } ?: return
        val trimmed = input.trim()
        val amount = if (trimmed.isEmpty()) null else Money.parseToMinor(
            trimmed,
            Money.BASE_CURRENCY,
        )
        if (trimmed.isNotEmpty() && (amount == null || amount <= 0L)) {
            emit("请输入有效的预算金额")
            return
        }
        viewModelScope.launch {
            budgetRepository.setTagBudget(
                tagId = tagId,
                tagName = tag.name,
                amountMinor = amount,
                periodType = current.periodType,
            )
            emit(if (amount == null) "已清除 #${tag.name} 的预算" else "标签预算已保存")
        }
    }

    fun setTagBudgetEnabled(tagId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val rule = budgetRepository.tag(tagId) ?: return@launch
            budgetRepository.setEnabled(rule.id, enabled)
            emit(if (enabled) "已启用该标签预算" else "已暂停该标签预算，暂停期间不再提醒")
        }
    }

    fun deleteTagBudget(tagId: Long) {
        viewModelScope.launch {
            val rule = budgetRepository.tag(tagId) ?: return@launch
            budgetRepository.deleteBudget(rule.id)
            emit("已删除该标签预算，账单与标签保持不变")
        }
    }

    fun saveSpecial(
        budgetId: Long,
        name: String,
        input: String,
        periodType: BudgetPeriodType,
        note: String,
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            emit("请填写预算名称")
            return
        }
        val amount = Money.parseToMinor(input.trim(), Money.BASE_CURRENCY)
        if (amount == null || amount <= 0L) {
            emit("请输入有效的预算金额")
            return
        }
        val existing = state.value.specials.firstOrNull { it.budget.id == budgetId }?.budget
        val budget = Budget(
            id = budgetId,
            scope = BudgetScope.SPECIAL,
            name = trimmedName,
            amountMinor = amount,
            periodType = periodType,
            carryOver = existing?.carryOver ?: false,
            enabled = existing?.enabled ?: true,
            note = note.trim().ifEmpty { null },
            createdAt = existing?.createdAt ?: Time.nowMillis(),
            updatedAt = Time.nowMillis(),
        )
        viewModelScope.launch {
            budgetRepository.saveSpecial(budget)
            emit(if (budgetId == 0L) "已创建专项预算：$trimmedName" else "已更新专项预算：$trimmedName")
        }
    }

    fun setBudgetEnabled(budgetId: Long, enabled: Boolean) {
        viewModelScope.launch {
            budgetRepository.setEnabled(budgetId, enabled)
            emit(if (enabled) "预算已启用" else "预算已暂停，暂停期间不再提醒")
        }
    }

    fun deleteBudget(budgetId: Long) {
        viewModelScope.launch {
            budgetRepository.deleteBudget(budgetId)
            emit("已删除预算，历史账单与已发生支出不受影响")
        }
    }

    fun saveTemplate(name: String) {
        val current = state.value
        val total = current.totalBudgetMinor
        if (total == null) {
            emit("先设置总预算，再保存为模板")
            return
        }
        val templateName = name.trim().ifEmpty { "${current.periodType.label}预算模板" }
        viewModelScope.launch {
            templateRepository.create(
                BudgetTemplate(
                    name = templateName,
                    scope = BudgetScope.TOTAL,
                    amountMinor = total,
                    periodType = current.periodType,
                    carryOver = current.carryOver,
                    note = current.totalNote,
                    createdAt = Time.nowMillis(),
                )
            )
            emit("已保存模板：$templateName")
        }
    }

    fun applyTemplate(template: BudgetTemplate) {
        viewModelScope.launch {
            budgetRepository.setTotal(
                amountMinor = template.amountMinor,
                periodType = template.periodType,
                carryOver = template.carryOver,
                customStart = null,
                customEnd = null,
                note = template.note,
            )
            draftPeriod.value = null
            draftCarryOver.value = null
            draftCustomStart.value = null
            draftCustomEnd.value = null
            emit("已应用模板：${template.name}")
        }
    }

    fun deleteTemplate(template: BudgetTemplate) {
        viewModelScope.launch {
            templateRepository.delete(template.id)
            emit("已删除模板：${template.name}")
        }
    }

    private fun emit(text: String, kind: NoticeKind = NoticeKind.Success) {
        viewModelScope.launch { _events.emit(UiEvent.Message(text, kind)) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BudgetViewModel() }
        }
    }
}