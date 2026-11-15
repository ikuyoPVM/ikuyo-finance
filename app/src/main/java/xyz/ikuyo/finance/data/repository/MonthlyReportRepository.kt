package xyz.ikuyo.finance.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.local.dao.MonthlyReportDao
import xyz.ikuyo.finance.data.local.entity.MonthlyReportEntity
import xyz.ikuyo.finance.domain.model.MonthlyReport
import xyz.ikuyo.finance.domain.model.SummarySection
import xyz.ikuyo.finance.domain.usecase.MonthlySummaryBuilder
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月报仓储（阶段四第 7 步）。
 *
 * 版本化规则（计划书第 7 章）：
 * 1. 每个月可以有多个版本；重新生成只新增版本并把旧版标 [MonthlyReportEntity.superseded]，
 *    绝不覆盖旧记录 —— 历史报告的数字不会因为后来的账单被静默改写。
 * 2. 九段正文以 JSON 存进 payloadJson，落库即冻结；再次查看直接读库，不重算。
 * 3. 所有数字都来自 [MonthlySummaryBuilder]（内部只调 Statistics）；AI 只能写 narrative 叙述。
 */
class MonthlyReportRepository(
    private val dao: MonthlyReportDao,
    private val bills: BillRepository,
    private val categories: CategoryRepository,
    private val tags: TagRepository,
    private val budgets: BudgetRepository,
) {

    fun observeAll(): Flow<List<MonthlyReport>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeMonth(yearMonth: String): Flow<List<MonthlyReport>> =
        dao.observeMonth(yearMonth).map { rows -> rows.map { it.toDomain() } }

    suspend fun findCurrent(yearMonth: String): MonthlyReport? = dao.findCurrent(yearMonth)?.toDomain()

    suspend fun find(id: Long): MonthlyReport? = dao.findById(id)?.toDomain()

    /**
     * 删除一版报告。如果删掉的是生效版本，就把同月剩下最高的版本恢复为生效版本，
     * 避免列表里只剩一条却还标着「已被新版取代」。
     */
    suspend fun delete(id: Long) {
        val report = dao.findById(id) ?: return
        dao.delete(id)
        if (dao.findCurrent(report.yearMonth) == null) {
            dao.promoteLatest(report.yearMonth)
        }
    }

    /**
     * 生成或重新生成某个月的报告：旧版本保留并标记 superseded，版本号自增。
     */
    suspend fun generate(
        yearMonth: YearMonth,
        today: LocalDate = LocalDate.now(Time.zone),
    ): MonthlyReport {
        val billList = bills.observeActive().first()
        val categoryList = categories.getAll()
        val tagList = tags.getAll()
        val tagBillIds = tags.observeAllLinks().first()
            .groupBy({ it.tagId }, { it.billId })
            .mapValues { (_, ids) -> ids.toSet() }
        val budgetList = budgets.observeAll().first()

        val draft = MonthlySummaryBuilder.build(
            yearMonth = yearMonth,
            bills = billList,
            categories = categoryList,
            tags = tagList,
            tagBillIds = tagBillIds,
            budgets = budgetList,
            today = today,
        )
        val key = draft.yearMonth
        dao.markAllSuperseded(key)
        val nextVersion = (dao.maxVersion(key) ?: 0) + 1
        val entity = MonthlyReportEntity(
            yearMonth = key,
            version = nextVersion,
            generatedAt = Time.nowMillis(),
            dataStart = draft.dataStart,
            dataEndInclusive = draft.dataEndInclusive,
            billCount = draft.billCount,
            netExpenseMinor = draft.netExpenseMinor,
            incomeMinor = draft.incomeMinor,
            overspendMinor = draft.overspendMinor,
            payloadJson = encodeSections(draft.sections),
        )
        val id = dao.insert(entity)
        return entity.copy(id = id).toDomain()
    }

    /** AI 解读回写：只写叙述，不碰任何数字。 */
    suspend fun updateNarrative(id: Long, narrative: String?) = dao.updateNarrative(id, narrative)

    // ── 序列化 ────────────────────────────────────────────────
    private fun encodeSections(sections: List<SummarySection>): String = Json.dumps(
        Json.obj(
            "sections" to Json.arr(
                sections.map { section ->
                    Json.obj(
                        "title" to Json.str(section.title),
                        "items" to Json.arr(section.items.map { Json.str(it) }),
                    )
                }
            )
        )
    )

    private fun decodeSections(json: String): List<SummarySection> {
        val root = Json.parseOrNull(json) as? JsonValue.Obj ?: return emptyList()
        val array = root.arr("sections") ?: return emptyList()
        return array.items.mapNotNull { item ->
            val obj = item as? JsonValue.Obj ?: return@mapNotNull null
            val title = obj.str("title") ?: return@mapNotNull null
            val items = obj.arr("items")?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
            SummarySection(title, items)
        }
    }

    private fun MonthlyReportEntity.toDomain(): MonthlyReport = MonthlyReport(
        id = id,
        yearMonth = yearMonth,
        version = version,
        generatedAt = generatedAt,
        dataStart = dataStart,
        dataEndInclusive = dataEndInclusive,
        billCount = billCount,
        netExpenseMinor = netExpenseMinor,
        incomeMinor = incomeMinor,
        overspendMinor = overspendMinor,
        sections = decodeSections(payloadJson),
        narrative = narrative,
        superseded = superseded,
    )
}