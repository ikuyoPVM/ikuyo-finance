@file:Suppress("TooManyFunctions")

package xyz.ikuyo.finance.data.ai

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.BudgetRepository
import xyz.ikuyo.finance.data.repository.CategoryRepository
import xyz.ikuyo.finance.data.repository.DebtRepository
import xyz.ikuyo.finance.data.repository.FixedExpenseRepository
import xyz.ikuyo.finance.data.repository.InstallmentRepository
import xyz.ikuyo.finance.data.repository.SavingsRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillType
import xyz.ikuyo.finance.domain.model.Budget
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Debt
import xyz.ikuyo.finance.domain.model.DebtPayment
import xyz.ikuyo.finance.domain.model.FixedExpense
import xyz.ikuyo.finance.domain.model.FixedExpensePayment
import xyz.ikuyo.finance.domain.model.Installment
import xyz.ikuyo.finance.domain.model.InstallmentPayment
import xyz.ikuyo.finance.domain.model.SavingsEntry
import xyz.ikuyo.finance.domain.model.SavingsGoal
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.BudgetPeriods
import xyz.ikuyo.finance.domain.usecase.DebtLedger
import xyz.ikuyo.finance.domain.usecase.FixedExpenseSchedule
import xyz.ikuyo.finance.domain.usecase.InstallmentSchedule
import xyz.ikuyo.finance.domain.usecase.SavingsLedger
import xyz.ikuyo.finance.domain.usecase.Statistics
import xyz.ikuyo.finance.domain.usecase.Statistics.RangeType
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * AI 可调用的本地数据工具定义。
 *
 * 这些工具是 AI 与账本之间唯一的通道：AI 不接触数据库，也不能生成 SQL。
 * 每个工具的说明与参数都写在 schema 里，模型只能按 schema 传参，执行前还会再校验一次。
 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonValue.Obj,
) {
    fun toJson(): JsonValue.Obj = Json.obj(
        "type" to Json.str("function"),
        "function" to Json.obj(
            "name" to Json.str(name),
            "description" to Json.str(description),
            "parameters" to parameters,
        ),
    )
}

object AiTools {

    const val GET_ACCOUNT_SUMMARY = "get_account_summary"
    const val QUERY_TRANSACTIONS = "query_transactions"
    const val GET_CATEGORY_STATISTICS = "get_category_statistics"
    const val GET_SPENDING_TREND = "get_spending_trend"
    const val GET_BUDGET_STATUS = "get_budget_status"
    const val GET_TAG_STATISTICS = "get_tag_statistics"
    const val GET_RECURRING_EXPENSES = "get_recurring_expenses"
    const val GET_INSTALLMENT_SUMMARY = "get_installment_summary"
    const val GET_DEBT_SUMMARY = "get_debt_summary"
    const val GET_SAVINGS_GOALS = "get_savings_goals"
    const val SEARCH_TRANSACTIONS = "search_transactions"

    /** 时间范围取值。相对范围由 App 按设备当天换算成明确日期后再查，模型不自己算日期。 */
    val RANGES = listOf("this_month", "last_month", "last_7_days", "last_30_days", "custom")

    private fun rangeSchema(required: Boolean = true): JsonValue.Obj {
        val props = Json.obj(
            "range" to Json.obj(
                "type" to Json.str("string"),
                "enum" to Json.arr(RANGES.map { Json.str(it) }),
                "description" to Json.str("时间范围。this_month=本月，last_month=上月，last_7_days=最近 7 天（含今天），last_30_days=最近 30 天（含今天），custom=自定义（需同时给 start_date 与 end_date）。"),
            ),
            "start_date" to Json.obj(
                "type" to Json.str("string"),
                "description" to Json.str("自定义范围开始日期，格式 yyyy-MM-dd，仅 range=custom 时使用。"),
            ),
            "end_date" to Json.obj(
                "type" to Json.str("string"),
                "description" to Json.str("自定义范围结束日期（含当天），格式 yyyy-MM-dd，仅 range=custom 时使用。"),
            ),
        )
        val requiredKeys: List<JsonValue> = if (required) listOf(Json.str("range")) else emptyList()
        return Json.obj(
            "type" to Json.str("object"),
            "properties" to props,
            "required" to Json.arr(requiredKeys),
            "additionalProperties" to Json.bool(false),
        )
    }

    private fun objectSchema(
        properties: Map<String, JsonValue.Obj>,
        required: List<String> = emptyList(),
    ): JsonValue.Obj = Json.obj(
        "type" to Json.str("object"),
        "properties" to Json.obj(*properties.map { it.key to it.value }.toTypedArray()),
        "required" to Json.arr(required.map { Json.str(it) }),
        "additionalProperties" to Json.bool(false),
    )

    private fun rangePlus(extra: Map<String, JsonValue.Obj>): JsonValue.Obj {
        val base = rangeSchema().obj("properties")?.fields ?: LinkedHashMap()
        val merged = LinkedHashMap<String, JsonValue>()
        merged.putAll(base)
        merged.putAll(extra)
        return Json.obj(
            "type" to Json.str("object"),
            "properties" to JsonValue.Obj(merged),
            "required" to Json.arr(listOf(Json.str("range"))),
            "additionalProperties" to Json.bool(false),
        )
    }

    val ALL: List<AiToolSpec> = listOf(
        AiToolSpec(
            GET_ACCOUNT_SUMMARY,
            "查询指定时间范围的收支摘要：支出、退款、净支出、收入、净收支、账单笔数，以及与上一周期相比的变化。金额单位为元。",
            rangeSchema(),
        ),
        AiToolSpec(
            QUERY_TRANSACTIONS,
            "按条件查询账单明细（最多 50 条）：时间范围、收支类型、分类名、标签名、关键词。用于回答「哪些花费」「具体那几笔」这类问题。",
            rangePlus(
                mapOf(
                    "types" to Json.obj(
                        "type" to Json.str("array"),
                        "items" to Json.obj("type" to Json.str("string"), "enum" to Json.arr(listOf("expense", "income", "refund").map { Json.str(it) })),
                        "description" to Json.str("收支类型筛选，不传等于全部。"),
                    ),
                    "category" to Json.obj("type" to Json.str("string"), "description" to Json.str("分类名称，例如「餐饮」；不传等于全部分类。")),
                    "tag" to Json.obj("type" to Json.str("string"), "description" to Json.str("标签名称，例如「工作」；不传等于全部标签。")),
                    "keyword" to Json.obj("type" to Json.str("string"), "description" to Json.str("备注关键词，模糊匹配。")),
                    "limit" to Json.obj("type" to Json.str("integer"), "description" to Json.str("返回条数，最大 50，默认 20。")),
                ),
            ),
        ),
        AiToolSpec(
            GET_CATEGORY_STATISTICS,
            "查询指定时间范围的分类支出排行：每个分类的净支出、占比与笔数，用于回答「哪个分类花得最多」。",
            rangePlus(
                mapOf(
                    "limit" to Json.obj("type" to Json.str("integer"), "description" to Json.str("返回前 N 个分类，最大 20，默认 10。")),
                ),
            ),
        ),
        AiToolSpec(
            GET_SPENDING_TREND,
            "查询消费趋势：按日或按月给出支出曲线，用于回答「最近趋势如何」「哪天花得最多」。",
            rangePlus(
                mapOf(
                    "granularity" to Json.obj(
                        "type" to Json.str("string"),
                        "enum" to Json.arr(listOf(Json.str("day"), Json.str("month"))),
                        "description" to Json.str("day=按天，month=按月；默认 day。"),
                    ),
                ),
            ),
        ),
        AiToolSpec(
            GET_BUDGET_STATUS,
            "查询预算执行情况：总预算/分类预算/标签预算的额度、已用、剩余、用量百分比与剩余天数。不需要时间范围，按预算自身周期计算。",
            objectSchema(
                mapOf(
                    "include_specials" to Json.obj("type" to Json.str("boolean"), "description" to Json.str("是否包含分类/标签等专项预算，默认 true。")),
                ),
            ),
        ),
        AiToolSpec(
            GET_TAG_STATISTICS,
            "查询标签维度的支出统计：每个标签的净支出、占比与关联账单笔数。",
            rangePlus(
                mapOf(
                    "limit" to Json.obj("type" to Json.str("integer"), "description" to Json.str("返回前 N 个标签，最大 20，默认 10。")),
                ),
            ),
        ),
        AiToolSpec(
            GET_RECURRING_EXPENSES,
            "查询固定支出计划：名称、金额、周期、到期日、剩余天数、是否已启用与最近一次付款。",
            objectSchema(
                mapOf(
                    "include_disabled" to Json.obj("type" to Json.str("boolean"), "description" to Json.str("是否包含已暂停的计划，默认 false。")),
                ),
            ),
        ),
        AiToolSpec(
            GET_INSTALLMENT_SUMMARY,
            "查询分期计划：总额、每期金额、已还期数、剩余期数与剩余金额、下一期到期日。",
            objectSchema(emptyMap()),
        ),
        AiToolSpec(
            GET_DEBT_SUMMARY,
            "查询债务与借出：本金、已还本金、剩余待还、是否结清、到期日。",
            objectSchema(emptyMap()),
        ),
        AiToolSpec(
            GET_SAVINGS_GOALS,
            "查询储蓄目标：目标金额、已存金额、剩余金额、进度、截止日期与剩余天数。",
            objectSchema(emptyMap()),
        ),
        AiToolSpec(
            SEARCH_TRANSACTIONS,
            "按关键词搜索账单（备注、分类名、标签名都参与匹配，最多 30 条），用于「上次那个咖啡多少钱」这类回忆式提问。",
            rangePlus(
                mapOf(
                    "keyword" to Json.obj("type" to Json.str("string"), "description" to Json.str("搜索关键词，必填。")),
                    "limit" to Json.obj("type" to Json.str("integer"), "description" to Json.str("返回条数，最大 30，默认 10。")),
                ),
            ),
        ),
    )

    val NAMES: Set<String> = ALL.map { it.name }.toSet()

    fun isEmptySpecAllowed(name: String): Boolean = name in NAMES

    fun schemasJson(): JsonValue.Arr = Json.arr(ALL.map { it.toJson() })
}

/**
 * 工具执行器：把模型给出的工具名与参数翻译成对现有 Repository 的只读查询。
 *
 * 所有结果都是 JSON 文本，金额同时给出「元」的小数字符串与「分」的整数，避免模型自行换算。
 */
class AiToolExecutor(
    private val billRepository: BillRepository,
    private val categoryRepository: CategoryRepository,
    private val tagRepository: TagRepository,
    private val budgetRepository: BudgetRepository,
    private val fixedExpenseRepository: FixedExpenseRepository,
    private val installmentRepository: InstallmentRepository,
    private val debtRepository: DebtRepository,
    private val savingsRepository: SavingsRepository,
    private val today: () -> LocalDate = { LocalDate.now(Time.zone) },
) {

    companion object {
        const val MAX_ROWS = 50
        const val MAX_TREND_POINTS = 62
        const val MAX_RESULT_CHARS = 4000
        private const val MAX_RANGE_DAYS = 400L
    }

    suspend fun execute(name: String, argumentsJson: String): String {
        if (!AiTools.isEmptySpecAllowed(name)) {
            return clamp(Json.dumps(errorObj(name, "未知工具")))
        }
        val args = Json.parseOrNull(argumentsJson) as? JsonValue.Obj ?: JsonValue.Obj()
        val result = try {
            when (name) {
                AiTools.GET_ACCOUNT_SUMMARY -> accountSummary(args)
                AiTools.QUERY_TRANSACTIONS -> queryTransactions(args)
                AiTools.GET_CATEGORY_STATISTICS -> categoryStatistics(args)
                AiTools.GET_SPENDING_TREND -> spendingTrend(args)
                AiTools.GET_BUDGET_STATUS -> budgetStatus(args)
                AiTools.GET_TAG_STATISTICS -> tagStatistics(args)
                AiTools.GET_RECURRING_EXPENSES -> recurringExpenses(args)
                AiTools.GET_INSTALLMENT_SUMMARY -> installmentSummary()
                AiTools.GET_DEBT_SUMMARY -> debtSummary()
                AiTools.GET_SAVINGS_GOALS -> savingsGoals()
                AiTools.SEARCH_TRANSACTIONS -> searchTransactions(args)
                else -> errorObj(name, "未知工具")
            }
        } catch (error: Exception) {
            errorObj(name, error.message ?: "查询失败")
        }
        return clamp(Json.dumps(result))
    }

    private fun clamp(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text else text.take(MAX_RESULT_CHARS - 20) + "…（结果已截断）"

    private fun errorObj(name: String, message: String): JsonValue.Obj =
        Json.obj("tool" to Json.str(name), "error" to Json.str(message))

    // ---- 时间范围 ----

    private fun resolveRange(args: JsonValue.Obj): Statistics.Range {
        val today = today()
        return when (args.str("range") ?: "this_month") {
            "last_month" -> Statistics.range(RangeType.MONTH, today.minusMonths(1))
            "last_7_days" -> customRange(today.minusDays(6), today)
            "last_30_days" -> customRange(today.minusDays(29), today)
            "custom" -> {
                val start = parseDate(args.str("start_date")) ?: today.withDayOfMonth(1)
                val end = parseDate(args.str("end_date")) ?: today
                customRange(start, end)
            }
            else -> Statistics.range(RangeType.MONTH, today)
        }
    }

    private fun customRange(start: LocalDate, end: LocalDate): Statistics.Range {
        val today = today()
        var from = if (start.isAfter(end)) end else start
        var to = if (start.isAfter(end)) start else end
        if (to.isAfter(today.plusDays(1))) to = today
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) from = to.minusDays(MAX_RANGE_DAYS)
        return Statistics.range(
            type = RangeType.CUSTOM,
            anchor = today,
            customStart = Time.startOfDay(from),
            customEndInclusive = Time.endOfDayInclusive(to),
        )
    }

    private fun parseDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    }

    private fun rangeJson(range: Statistics.Range): JsonValue.Obj = Json.obj(
        "label" to Json.str(range.label),
        "start_date" to Json.str(Time.toLocalDate(range.startMillis).toString()),
        "end_date" to Json.str(Time.toLocalDate(range.endMillisExclusive - 1).toString()),
        "days" to Json.num(range.dayCount.toLong()),
    )

    private fun yuan(minor: Long): JsonValue.Str = Json.str(Money.plain(minor))

    // ---- 查询 ----

    private suspend fun activeBills(): List<Bill> = billRepository.observeActive().first()

    private suspend fun accountSummary(args: JsonValue.Obj): JsonValue.Obj {
        val range = resolveRange(args)
        val bills = activeBills()
        val totals = Statistics.totals(bills, range)
        return Json.obj(
            "tool" to Json.str(AiTools.GET_ACCOUNT_SUMMARY),
            "range" to rangeJson(range),
            "expense" to yuan(totals.expenseMinor),
            "refund" to yuan(totals.refundMinor),
            "net_expense" to yuan(totals.netExpenseMinor),
            "income" to yuan(totals.incomeMinor),
            "net" to yuan(totals.netMinor),
            "bill_count" to Json.num(totals.billCount.toLong()),
            "previous_label" to Json.str(range.previousLabel),
            "previous_net_expense" to yuan(Statistics.totals(bills, Statistics.previousRange(range)).netExpenseMinor),
            "expense_change_percent" to (Statistics.changePercent(totals.netExpenseMinor, Statistics.totals(bills, Statistics.previousRange(range)).netExpenseMinor)?.let { value -> Json.num(value.toDouble()) } ?: Json.str("n/a")),
        )
    }

    private suspend fun queryTransactions(args: JsonValue.Obj): JsonValue.Obj {
        val range = resolveRange(args)
        val bills = activeBills()
        val categories = categoryRepository.getAll()
        val tags = tagRepository.getAll()
        val links = tagRepository.observeAllLinks().first()
        val tagNames = tags.associate { it.id to it.name }
        val tagsOfBill = links.groupBy({ it.billId }, { tagNames[it.tagId] ?: "" }).mapValues { it.value.filter { name -> name.isNotBlank() } }
        val types = args.arr("types")?.items?.mapNotNull { (it as? JsonValue.Str)?.value?.toBillType() }?.toSet().orEmpty()
        val categoryFilter = args.str("category")?.trim().orEmpty()
        val tagFilter = args.str("tag")?.trim().orEmpty()
        val keyword = args.str("keyword")?.trim().orEmpty()
        val limit = clampLimit(args.int("limit"), 20, MAX_ROWS)
        val rows = bills.asSequence()
            .filter { range.contains(it.occurredAt) }
            .filter { types.isEmpty() || it.type in types }
            .filter { categoryFilter.isEmpty() || (it.categoryName ?: categories.firstOrNull { c -> c.id == it.categoryId }?.name ?: "未分类") == categoryFilter }
            .filter { tagFilter.isEmpty() || tagsOfBill[it.id].orEmpty().any { name -> name == tagFilter } }
            .filter { keyword.isEmpty() || it.note.contains(keyword, ignoreCase = true) }
            .sortedByDescending { it.occurredAt }
            .take(limit)
            .map { bill ->
                Json.obj(
                    "date" to Json.str(Time.toLocalDate(bill.occurredAt).toString()),
                    "type" to Json.str(bill.type.dbValue.lowercase()),
                    "amount" to yuan(bill.cnyAmountMinor),
                    "currency" to Json.str(bill.currency),
                    "original_amount" to yuan(bill.amountMinor),
                    "category" to Json.str(bill.categoryName ?: categories.firstOrNull { c -> c.id == bill.categoryId }?.name ?: "未分类"),
                    "tags" to Json.arr(tagsOfBill[bill.id].orEmpty().map { Json.str(it) }),
                    "payment" to Json.str(bill.paymentMethod.label),
                    "note" to Json.str(bill.note),
                    "source" to Json.str(bill.source.dbValue),
                )
            }
            .toList()
        return Json.obj(
            "tool" to Json.str(AiTools.QUERY_TRANSACTIONS),
            "range" to rangeJson(range),
            "count" to Json.num(rows.size.toLong()),
            "rows" to Json.arr(rows),
        )
    }

    private suspend fun categoryStatistics(args: JsonValue.Obj): JsonValue.Obj {
        val range = resolveRange(args)
        val limit = clampLimit(args.int("limit"), 10, 20)
        val bills = activeBills()
        val categories = categoryRepository.getAll()
        val stats = Statistics.categoryStats(bills, range, categories, limit)
        return Json.obj(
            "tool" to Json.str(AiTools.GET_CATEGORY_STATISTICS),
            "range" to rangeJson(range),
            "rows" to Json.arr(
                stats.map { stat ->
                    Json.obj(
                        "category" to Json.str(stat.name),
                        "net_expense" to yuan(stat.netExpenseMinor),
                        "expense" to yuan(stat.expenseMinor),
                        "refund" to yuan(stat.refundMinor),
                        "bill_count" to Json.num(stat.billCount.toLong()),
                        "share_percent" to Json.str(percent(stat.shareBp)),
                    )
                },
            ),
        )
    }

    private suspend fun tagStatistics(args: JsonValue.Obj): JsonValue.Obj {
        val range = resolveRange(args)
        val limit = clampLimit(args.int("limit"), 10, 20)
        val bills = activeBills()
        val tags = tagRepository.getAll()
        val links = tagRepository.observeAllLinks().first()
        val tagBillIds = links.groupBy({ it.tagId }, { it.billId }).mapValues { it.value.toSet() }
        val stats = Statistics.tagStats(bills, range, tags, tagBillIds, limit)
        return Json.obj(
            "tool" to Json.str(AiTools.GET_TAG_STATISTICS),
            "range" to rangeJson(range),
            "rows" to Json.arr(
                stats.map { stat ->
                    Json.obj(
                        "tag" to Json.str(stat.name),
                        "net_expense" to yuan(stat.netExpenseMinor),
                        "bill_count" to Json.num(stat.billCount.toLong()),
                        "share_percent" to Json.str(percent(stat.shareBp)),
                    )
                },
            ),
        )
    }

    private suspend fun spendingTrend(args: JsonValue.Obj): JsonValue.Obj {
        val range = resolveRange(args)
        val monthly = (args.str("granularity") ?: "day") == "month"
        val bills = activeBills()
        val points = Statistics.dailySeries(bills, range)
        val rows = if (monthly) {
            points.groupBy { Time.toLocalDate(it.millis).withDayOfMonth(1) }
                .toSortedMap()
                .map { (month, group) ->
                    Json.obj(
                        "month" to Json.str(month.toString()),
                        "net_expense" to yuan(group.sumOf { it.netExpenseMinor }),
                    )
                }
        } else {
            points.takeLast(MAX_TREND_POINTS).map { point ->
                Json.obj(
                    "date" to Json.str(Time.toLocalDate(point.millis).toString()),
                    "net_expense" to yuan(point.netExpenseMinor),
                )
            }
        }
        return Json.obj(
            "tool" to Json.str(AiTools.GET_SPENDING_TREND),
            "range" to rangeJson(range),
            "granularity" to Json.str(if (monthly) "month" else "day"),
            "rows" to Json.arr(rows),
        )
    }

    private suspend fun budgetStatus(args: JsonValue.Obj): JsonValue.Obj {
        val includeSpecials = args.bool("include_specials") ?: true
        val bills = activeBills()
        val budgets: List<Budget> = if (includeSpecials) {
            budgetRepository.observeAll().first().filter { it.enabled }
        } else {
            listOfNotNull(budgetRepository.total()?.takeIf { it.enabled })
        }
        val categories = categoryRepository.getAll().associate { it.id to it.name }
        val tags = tagRepository.getAll().associate { it.id to it.name }
        val links = tagRepository.observeAllLinks().first()
        val tagBillIds = links.groupBy({ it.tagId }, { it.billId }).mapValues { it.value.toSet() }
        val executions = Statistics.budgetExecutions(
            bills = bills,
            budgets = budgets,
            today = today(),
            tagBillIds = tagBillIds,
            categoryNames = categories,
            tagNames = tags,
        )
        return Json.obj(
            "tool" to Json.str(AiTools.GET_BUDGET_STATUS),
            "today" to Json.str(today().toString()),
            "rows" to Json.arr(
                executions.map { exec ->
                    Json.obj(
                        "name" to Json.str(exec.name),
                        "period" to Json.str(exec.windowLabel),
                        "amount" to yuan(exec.amountMinor),
                        "spent" to yuan(exec.spentMinor),
                        // availableMinor 是「额度 + 结转」，余额要自己减出来，否则模型会把额度当成剩余。
                        "remaining" to yuan(exec.availableMinor - exec.spentMinor),
                        "used_percent" to Json.num(exec.usedPercent.toLong()),
                        "days_left" to Json.num(exec.daysLeft.toLong()),
                        "over" to yuan(exec.overMinor),
                        "status" to Json.str(exec.alertLevel.label),
                    )
                },
            ),
        )
    }

    private suspend fun recurringExpenses(args: JsonValue.Obj): JsonValue.Obj {
        val includeDisabled = args.bool("include_disabled") ?: false
        val today = today()
        val expenses = fixedExpenseRepository.observeAll().first()
            .filter { includeDisabled || it.enabled }
        val payments: List<FixedExpensePayment> = fixedExpenseRepository.observePayments().first()
        val rows = expenses.map { expense: FixedExpense ->
            val last = payments.filter { it.fixedExpenseId == expense.id }.maxByOrNull { it.paidAt }
            Json.obj(
                "name" to Json.str(expense.name),
                "amount" to yuan(expense.amountMinor),
                "period" to Json.str(expense.scheduleLabel()),
                "due_date" to Json.str(Time.toLocalDate(expense.dueAt).toString()),
                "days_until_due" to Json.num(FixedExpenseSchedule.daysUntil(expense.dueAt, today).toLong()),
                "enabled" to Json.bool(expense.enabled),
                "variable" to Json.bool(expense.isVariable),
                "category" to Json.str(expense.categoryName ?: "未分类"),
                "last_paid" to (last?.let { Json.str(Time.toLocalDate(it.paidAt).toString()) } ?: JsonValue.Obj()),
            )
        }
        return Json.obj(
            "tool" to Json.str(AiTools.GET_RECURRING_EXPENSES),
            "count" to Json.num(rows.size.toLong()),
            "rows" to Json.arr(rows),
        )
    }

    private suspend fun installmentSummary(): JsonValue.Obj {
        val installments = installmentRepository.observeAll().first().filter { it.enabled }
        val payments: List<InstallmentPayment> = installmentRepository.observePayments().first()
        val rows = installments.map { plan: Installment ->
            val progress = InstallmentSchedule.progress(
                installment = plan,
                payments = payments.filter { it.installmentId == plan.id },
            )
            Json.obj(
                "name" to Json.str(plan.name),
                "total" to yuan(plan.totalMinor),
                "per_period" to yuan(plan.perPeriodMinor),
                "paid_periods" to Json.num(progress.paidCount.toLong()),
                "remaining_periods" to Json.num(progress.remainingCount.toLong()),
                "remaining" to yuan(progress.remainingMinor),
                "next_due" to (progress.nextPeriod?.let { Json.str(Time.toLocalDate(it.dueAt).toString()) } ?: JsonValue.Obj()),
                "finished" to Json.bool(progress.isFinished),
            )
        }
        return Json.obj(
            "tool" to Json.str(AiTools.GET_INSTALLMENT_SUMMARY),
            "count" to Json.num(rows.size.toLong()),
            "rows" to Json.arr(rows),
        )
    }

    private suspend fun debtSummary(): JsonValue.Obj {
        val debts = debtRepository.observeAll().first()
        val payments: List<DebtPayment> = debtRepository.observePayments().first()
        val rows = debts.map { debt: Debt ->
            val progress = DebtLedger.progressOf(debt, payments.filter { it.debtId == debt.id })
            Json.obj(
                "name" to Json.str(debt.name),
                "type" to Json.str(debt.type.label),
                "principal" to yuan(debt.principalMinor),
                "repaid" to yuan(progress.repaidPrincipalMinor),
                "remaining" to yuan(progress.remainingPrincipalMinor),
                "settled" to Json.bool(progress.isSettled),
                "due_date" to Json.str(Time.toLocalDate(debt.dueAt).toString()),
                "days_until_due" to Json.num(DebtLedger.daysUntil(debt.dueAt, today()).toLong()),
            )
        }
        return Json.obj(
            "tool" to Json.str(AiTools.GET_DEBT_SUMMARY),
            "count" to Json.num(rows.size.toLong()),
            "rows" to Json.arr(rows),
        )
    }

    private suspend fun savingsGoals(): JsonValue.Obj {
        val goals = savingsRepository.observeGoals().first()
        val entries: List<SavingsEntry> = savingsRepository.observeEntries().first()
        val rows = goals.map { goal: SavingsGoal ->
            val progress = SavingsLedger.progressOf(
                goal = goal,
                entries = entries.filter { it.goalId == goal.id },
            )
            Json.obj(
                "name" to Json.str(goal.name),
                "target" to yuan(goal.targetMinor),
                "saved" to yuan(progress.savedMinor),
                "remaining" to yuan(progress.remainingMinor),
                "progress_percent" to Json.str(percent(progress.progressBp)),
                "due_date" to Json.str(Time.toLocalDate(goal.dueAt).toString()),
                "days_left" to (progress.daysLeft?.let { Json.num(it.toLong()) } ?: JsonValue.Obj()),
                "reached" to Json.bool(progress.isReached),
            )
        }
        return Json.obj(
            "tool" to Json.str(AiTools.GET_SAVINGS_GOALS),
            "total_saved" to yuan(SavingsLedger.balance(entries)),
            "count" to Json.num(rows.size.toLong()),
            "rows" to Json.arr(rows),
        )
    }

    private suspend fun searchTransactions(args: JsonValue.Obj): JsonValue.Obj {
        val keyword = args.str("keyword")?.trim().orEmpty()
        if (keyword.isEmpty()) return errorObj(AiTools.SEARCH_TRANSACTIONS, "缺少关键词")
        val range = resolveRange(args)
        val limit = clampLimit(args.int("limit"), 10, 30)
        val bills = activeBills()
        val categories = categoryRepository.getAll().associate { it.id to it.name }
        val tags = tagRepository.getAll().associate { it.id to it.name }
        val links = tagRepository.observeAllLinks().first()
        val tagsOfBill = links.groupBy({ it.billId }, { tags[it.tagId] ?: "" })
        val rows = bills.asSequence()
            .filter { range.contains(it.occurredAt) }
            .filter { bill ->
                bill.note.contains(keyword, ignoreCase = true) ||
                    (bill.categoryName ?: categories[bill.categoryId]).orEmpty().contains(keyword, ignoreCase = true) ||
                    tagsOfBill[bill.id].orEmpty().any { it.contains(keyword, ignoreCase = true) }
            }
            .sortedByDescending { it.occurredAt }
            .take(limit)
            .map { bill ->
                Json.obj(
                    "date" to Json.str(Time.toLocalDate(bill.occurredAt).toString()),
                    "type" to Json.str(bill.type.dbValue.lowercase()),
                    "amount" to yuan(bill.cnyAmountMinor),
                    "category" to Json.str(bill.categoryName ?: categories[bill.categoryId] ?: "未分类"),
                    "note" to Json.str(bill.note),
                )
            }
            .toList()
        return Json.obj(
            "tool" to Json.str(AiTools.SEARCH_TRANSACTIONS),
            "range" to rangeJson(range),
            "keyword" to Json.str(keyword),
            "count" to Json.num(rows.size.toLong()),
            "rows" to Json.arr(rows),
        )
    }

    private fun clampLimit(requested: Int?, fallback: Int, max: Int): Int {
        val value = requested ?: fallback
        return value.coerceIn(1, max)
    }

    private fun percent(bp: Int): String = "${bp / 100}.${(bp % 100).toString().padStart(2, '0')}%"

    private fun String.toBillType(): BillType? = when (lowercase()) {
        "expense" -> BillType.EXPENSE
        "income" -> BillType.INCOME
        "refund" -> BillType.REFUND
        else -> null
    }
}
