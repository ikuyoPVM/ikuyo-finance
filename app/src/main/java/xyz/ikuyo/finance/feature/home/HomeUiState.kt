package xyz.ikuyo.finance.feature.home

import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.domain.model.BalanceSummary
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.HomeLayout
import xyz.ikuyo.finance.domain.model.HomeStats
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.Tag

data class HomeUiState(
    val isLoading: Boolean = true,
    val monthLabel: String = "",
    val sortOrder: BillSortOrder = BillSortOrder.OCCURRED_DESC,
    val balance: BalanceSummary = BalanceSummary(),
    val stats: HomeStats = HomeStats(),
    val budget: BudgetStatus = BudgetStatus(),
    val recentBills: List<Bill> = emptyList(),
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val lastPaymentMethod: PaymentMethod? = null,
    val layout: HomeLayout = HomeLayout(),
    /** 近 7 天每天对应的账单，用于点趋势图看当天明细。 */
    val trendBills: Map<java.time.LocalDate, List<Bill>> = emptyMap(),
) {
    val hasBills: Boolean get() = recentBills.isNotEmpty()

    val budgetSubtitle: String
        get() = if (!budget.hasBudget) {
            "尚未设置预算"
        } else {
            "剩余 ${Money.format(budget.remainingMinor)}" +
                if (budget.alertLevel.needsAttention) " · ${budget.alertLevel.label}" else ""
        }
}