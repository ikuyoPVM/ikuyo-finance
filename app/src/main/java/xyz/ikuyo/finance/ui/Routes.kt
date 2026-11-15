package xyz.ikuyo.finance.ui

/** 全部导航路由。底部导航只使用 [TABS] 中的三个。 */
object Routes {
    const val HOME = "home"
    const val AI = "ai"
    const val MINE = "mine"

    const val BILLS = "bills"
    const val TRASH = "trash"
    const val BUDGET = "budget"
    const val CATEGORIES = "categories"
    const val TAGS = "tags"
    const val OPERATIONS = "operations"
    const val AI_SETTINGS = "ai/settings"
    const val STATS = "stats"
    const val REPORTS = "reports"

    const val FIXED_EXPENSES = "fixed-expenses"
    const val INSTALLMENTS = "installments"
    const val DEBTS = "debts"
    const val SAVINGS = "savings"
    const val RATES = "rates"

    const val BACKUP = "backup"
    const val EXPORT = "export"
    const val IMPORT = "import"
    const val STATS_BILLS = "stats/bills?title={title}&start={start}&end={end}&categoryId={categoryId}&tagId={tagId}"
    const val BILL_DETAIL = "bill/{billId}"
    const val BILL_EDIT = "bill/{billId}/edit"

    fun billDetail(billId: Long): String = "bill/$billId"

    fun billEdit(billId: Long): String = "bill/$billId/edit"

    fun statsBills(
        title: String,
        startMillis: Long,
        endMillisExclusive: Long,
        categoryId: Long? = null,
        tagId: Long? = null,
    ): String =
        "stats/bills?title=${java.net.URLEncoder.encode(title, "UTF-8")}&start=$startMillis&end=$endMillisExclusive" +
            "&categoryId=${categoryId ?: -1L}&tagId=${tagId ?: -1L}"

    val TABS: Set<String> = setOf(HOME, AI, MINE)
}